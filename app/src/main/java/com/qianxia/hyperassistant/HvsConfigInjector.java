package com.qianxia.hyperassistant;

import android.content.pm.ApplicationInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XposedHelpers;

/**
 * system_server 侧：把负一屏**正式加进小米的 HyperViewScale 配置表**。
 *
 * ===== 为什么这比"放行权限"好 =====
 * 反汇编 `miui-services.hyperviewscale.jar` 里的
 * `com.android.server.wm.hyperviewscale.HyperViewScaleConfigManager`：
 * <pre>
 *   void preBindApplication(ApplicationInfo appInfo) {
 *       HyperViewScaleConfig.Pkg pkg = mViewScaleConfigMap.get(appInfo.packageName);
 *       if (pkg == null) return;
 *       Bundle b = new Bundle();
 *       b.putParcelable("HyperViewScale", pkg);
 *       appInfo.metaData = b;              // 注入到应用自己的 ApplicationInfo
 *       mAllowedUids.add(appInfo.uid);     // ← 官方 uid 白名单
 *   }
 *   boolean isHyperViewScaleAllowedUid(int uid) { return mAllowedUids.contains(uid); }
 * </pre>
 *
 * `isHyperViewScaleAllowedUid` 正是 system_server 里放行远程动画权限检查的判据
 * （日志串 `HyperViewScale remoteTransition bypass: pid=`）。
 * 也就是说 —— **只要包名进了这张配置表，权限、底图、圆角三件事一起解决**：
 *
 *   1. uid 自动进白名单 → 不需要再 hook `ActivityTaskManagerService.checkPermission`
 *   2. 应用侧 `HyperViewScaleManager.parseBundle(appInfo)` 拿到配置 →
 *      `getViewCorner` / `getBgColor` / `getListCover` / `needMatchParent` / `isViewSupport`
 *      全部可用 → **白边黑边、大卡片不生效** 的根因就在这里
 *   3. 配置表里声明了哪些"视图类可以做动画"，负一屏的
 *      `WidgetCardView` / `MaMlHostView` 才算"支持"
 *
 * 本地只有 5 个第三方包（bilibili / 爱奇艺 / 拼多多 / 小红书 / 腾讯视频），
 * 负一屏从来不在里面 —— 这就是它一直没有动画的**制度性原因**。
 *
 * ===== 为什么用 hook 而不是改 JSON =====
 * 云控 JSON 在 `/data/system/view_scale/`，本机 `adb shell` 没有 `su`；
 * 而我们在 system_server 里（uid 1000），直接往内存里的配置表塞一条就行，
 * 而且 `preBindApplication` 每次绑应用都会跑 —— 重启一次负一屏即可生效，**不用重启手机**。
 *
 * ⚠️ 只在 `packageName == 配置里的目标包` 时插入一条，其它包一律不碰。
 */
public final class HvsConfigInjector {

    private HvsConfigInjector() {
    }

    /** system_server 侧的实现类。 */
    static final String MGR = "com.android.server.wm.hyperviewscale.HyperViewScaleConfigManager";
    /** framework.jar 里的配置数据类（Parcelable）。 */
    static final String CFG = "android.app.hyperviewscale.HyperViewScaleConfig";

    /** 要"伪装"成已配置的包。 */
    static final String TARGET_PKG = "com.miui.personalassistant";

    /**
     * 视图类 → {corner(dp), matchParent}.
     *
     * `corner` 是**圆角半径（dp）**，`getViewCorner` 里会 dp2px。
     * `matchParent`: 0=不匹配, 1/2=取源矩形时往父容器找
     * （负一屏的卡片视图 bg 多为 null，往上取一层矩形观感才对）。
     *
     * 这些类名来自真机日志（见 README 的 `指纹` 记录）：
     *   android.widget.ImageView                                    128x128 / 165x208  小图标
     *   android.widget.LinearLayout                                 486x486            2x2 卡片
     *   com.miui.personalassistant.core.view.WidgetCardView         282x282 / 564x564  大卡片
     *   com.miui.personalassistant.core.view.MaMlHostView           486x486            MaMl 卡片
     */
    static final Object[][] VIEWS = {
            {"com.miui.personalassistant.core.view.WidgetCardView", 16f, 2},
            {"com.miui.personalassistant.core.view.MaMlHostView", 16f, 2},
            {"android.widget.LinearLayout", 16f, 2},
            {"android.widget.FrameLayout", 16f, 2},
            {"android.widget.RelativeLayout", 16f, 2},
            {"android.widget.ImageView", 12f, 0},
            // 实测补：扫码支付/快捷方式渲染成 RemoteViews，点击源是 TextView。
            // 之前它 isViewSupport=false、corner 也查不到 → 那条路完全没动画。
            {"android.widget.TextView", 12f, 0},
            {"android.widget.Button", 12f, 0},
            {"android.widget.ImageButton", 12f, 0},
    };

    /**
     * `startFrom[].attr` —— 负一屏里"能当起跳点"的 Activity。
     *
     * `handleThirdPartyStart` 用 `matchStartFromActivity(activity.getComponentName().getClassName())`
     * 卡这一条。真机还没确认宿主 Activity 的准确类名，所以这里多写几个候选；
     * 多写不会有害（只是个 Set 成员判断）。
     */
    static final String[] START_FROM = {
            "com.miui.personalassistant.PersonalAssistantActivity",
            "com.miui.personalassistant.ui.PersonalAssistantActivity",
            "com.miui.personalassistant.core.activity.PersonalAssistantActivity",
            "com.miui.personalassistant.MainActivity",
    };

    /**
     * `startTo[].attr` —— 目标 Activity。
     *
     * 说明：`handleThirdPartyStart` 要求**目标 Activity 命中**这张表才能生效，
     * 小米的配置就是这么一条条枚举的（bilibili 那条足足列了 8 个目标 Activity）。
     * 所以我们把真机实测点过的目标补进来。
     * 而桌面走的是 `handleSystemStart`（靠 `android:activity.animType`），**不要求匹配目标** ——
     * 模块当前用的正是那条路，这里主要是让"第三方路径"也能用。
     */
    static final String[] START_TO = {
            "com.deepseek.chat.MainActivity",
            "com.xiaomi.market.ui.DefaultLauncherIcon",
            "com.coolapk.market.view.main.MainActivity",
            "com.tencent.mm.plugin.scanner.ui.BaseScanUI",
    };

    private static volatile boolean sInjected;
    private static volatile boolean sMapMissingLogged;
    private static volatile int sAllowedLogCount;

    // ------------------------------------------------------------------ 安装

    public static void install(ClassLoader cl) {
        if (!Config.ENABLE_HVS_CONFIG_INJECT) {
            L.w("[HVS配置] ENABLE_HVS_CONFIG_INJECT=false，不注入配置");
            return;
        }
        Class<?> mgr = HookKit.findClass(cl, MGR);
        if (mgr == null) {
            L.w("[HVS配置] 找不到 " + MGR + "（这个 ROM 没有 HyperViewScale？）");
            return;
        }

        // 主注入点：每次绑定应用之前。map 已经在 init() 里异步加载好了。
        HookKit.hookAll(mgr, "preBindApplication", new HookKit.Cb("preBindApplication [HVS配置]") {
            @Override
            protected void onBefore(MethodHookParam p) {
                Object ai = (p.args != null && p.args.length > 0) ? p.args[0] : null;
                String pkg = (ai instanceof ApplicationInfo) ? ((ApplicationInfo) ai).packageName : null;
                boolean mine = TARGET_PKG.equals(pkg);
                if (!ensureInjected(p.thisObject, mine)) {
                    return;
                }
                if (mine && !sInjected) {
                    sInjected = true;
                    L.i("[HVS配置] ✔✔ 负一屏已插入配置表，本次绑定将自动注入 metaData + uid 白名单");
                }
            }
        });

        // 兜底注入点：初始化完成后再补一次（配置是异步加载的，可能晚于第一次绑定）。
        HookKit.hookAll(mgr, "init", new HookKit.Cb("init [HVS配置]") {
            @Override
            protected void onAfter(MethodHookParam p) {
                final Object self = p.thisObject;
                for (int delay : new int[]{300, 1200, 3000, 8000}) {
                    postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            ensureInjected(self, false);
                        }
                    }, delay);
                }
            }
        });

        // 观察点：官方白名单到底认没认负一屏。这是"权限问题已解决"的直接证据。
        HookKit.hookAll(mgr, "isHyperViewScaleAllowedUid", new HookKit.Cb("isHVScaleAllowedUid [HVS配置]") {
            @Override
            protected void onAfter(MethodHookParam p) {
                if (p.args == null || p.args.length < 1 || !(p.args[0] instanceof Integer)) {
                    return;
                }
                int uid = (Integer) p.args[0];
                if ((uid % 100000) != Config.TARGET_APP_ID) {
                    return;
                }
                if (sAllowedLogCount++ < 12) {
                    L.i("[HVS配置] 官方白名单查询 uid=" + uid + " -> " + p.getResult()
                            + (Boolean.TRUE.equals(p.getResult()) ? "  ✔ 已被官方放行" : "  ✘ 仍未放行"));
                }
            }
        });
    }

    // ------------------------------------------------------------------ 注入

    /** @return true 表示这次调用之后配置表里已经有目标包（或本来就有）。 */
    private static boolean ensureInjected(Object mgr, boolean verbose) {
        try {
            Object mapObj = XposedHelpers.getObjectField(mgr, "mViewScaleConfigMap");
            if (!(mapObj instanceof Map)) {
                if (verbose || !sMapMissingLogged) {
                    sMapMissingLogged = true;
                    L.w("[HVS配置] mViewScaleConfigMap 还不可用（配置尚未加载），稍后重试");
                }
                return false;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) mapObj;
            Object exist = map.get(TARGET_PKG);
            if (exist != null) {
                if (verbose) {
                    L.i("[HVS配置] 配置表里已有 " + TARGET_PKG + "（无需注入），表大小=" + map.size());
                }
                return true;
            }
            Object pkg = buildPkg(mgr.getClass().getClassLoader());
            if (pkg == null) {
                return false;
            }
            map.put(TARGET_PKG, pkg);
            L.i("[HVS配置] 已注入 " + TARGET_PKG + "  ->  配置表大小=" + map.size());
            listPkgs(map);
            return true;
        } catch (Throwable t) {
            L.e("[HVS配置] ensureInjected 失败", t);
            return false;
        }
    }

    private static void listPkgs(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        for (String k : map.keySet()) {
            sb.append(sb.length() == 0 ? "" : ", ").append(k);
        }
        L.i("[HVS配置] 配置表现有包: " + sb);
    }

    /** 用反射把 `HyperViewScaleConfig$Pkg` 造出来。 */
    private static Object buildPkg(ClassLoader cl) {
        try {
            Class<?> pkgC = Class.forName(CFG + "$Pkg", true, cl);
            Class<?> sfC = Class.forName(CFG + "$StartFrom", true, cl);
            Class<?> stC = Class.forName(CFG + "$StartTo", true, cl);
            Class<?> vcC = Class.forName(CFG + "$ViewClass", true, cl);

            Object pkg = pkgC.getDeclaredConstructor().newInstance();
            call(pkgC, pkg, "setAttr", TARGET_PKG);

            List<Object> sfs = listOf(pkgC, pkg, "getStartFrom");
            for (String act : START_FROM) {
                Object sf = sfC.getDeclaredConstructor().newInstance();
                call(sfC, sf, "setAttr", act);
                call(sfC, sf, "setTopCover", 0f);
                call(sfC, sf, "setBottomCover", 0f);
                // bgColor: 2 -> getBackgroundColorFromParent，取不到再退 DecorView。
                // （1 是"要跟深浅色匹配"的严格模式，负一屏卡片背景多为空，2 更稳）
                callInt(sfC, sf, "setBgColor", 2);
                sfs.add(sf);
            }
            L.i("[HVS配置] startFrom 候选 " + sfs.size() + " 个");

            List<Object> sts = listOf(pkgC, pkg, "getStartTo");
            for (String act : START_TO) {
                Object st = stC.getDeclaredConstructor().newInstance();
                call(stC, st, "setAttr", act);
                List<Object> views = listOf(stC, st, "getView");
                for (Object[] row : VIEWS) {
                    Object vc = vcC.getDeclaredConstructor().newInstance();
                    call(vcC, vc, "setAttr", (String) row[0]);
                    call(vcC, vc, "setCorner", (Float) row[1]);
                    callInt(vcC, vc, "setMatchParent", (Integer) row[2]);
                    callInt(vcC, vc, "setMatchChild", (Integer) row[2]);
                    views.add(vc);
                }
                sts.add(st);
            }
            L.i("[HVS配置] startTo 候选 " + sts.size() + " 个，每个 " + VIEWS.length + " 个视图类");
            return pkg;
        } catch (Throwable t) {
            L.e("[HVS配置] 构造 Pkg 失败", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ 反射小工具

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Class<?> c, Object o, String getter) throws Exception {
        Object v = c.getMethod(getter).invoke(o);
        if (v instanceof List) {
            return (List<Object>) v;
        }
        // getter 返回 null（理论上不会，<init> 里都 new 了）→ 换字段路径
        for (Field f : c.getDeclaredFields()) {
            if (List.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                Object cur = f.get(o);
                if (cur instanceof List) {
                    L.i("[HVS配置] " + getter + "() 返回 null，改用字段 " + f.getName());
                    return (List<Object>) cur;
                }
                List<Object> fresh = new ArrayList<Object>();
                f.set(o, fresh);
                L.i("[HVS配置] " + getter + "() 返回 null，已新建字段 " + f.getName());
                return fresh;
            }
        }
        throw new IllegalStateException(getter + " 拿不到 List");
    }

    private static void call(Class<?> c, Object o, String name, Object arg) throws Exception {
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != 1) {
                continue;
            }
            Class<?> t = m.getParameterTypes()[0];
            if (t == String.class && arg instanceof String) {
                m.invoke(o, arg);
                return;
            }
            if (t == float.class && arg instanceof Float) {
                m.invoke(o, arg);
                return;
            }
            if (t == int.class && arg instanceof Integer) {
                m.invoke(o, arg);
                return;
            }
        }
        throw new NoSuchMethodException(name + "(" + arg + ")");
    }

    private static void callInt(Class<?> c, Object o, String name, int arg) throws Exception {
        call(c, o, name, Integer.valueOf(arg));
    }

    /** 在 system_server 主线程上延迟执行（init 是异步加载配置的）。 */
    private static void postDelayed(Runnable r, long delayMs) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(r, delayMs);
        } catch (Throwable t) {
            L.w("[HVS配置] postDelayed 失败: " + t);
        }
    }
}
