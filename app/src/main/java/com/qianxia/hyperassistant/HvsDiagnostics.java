package com.qianxia.hyperassistant;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 负一屏进程侧：HyperViewScale 内部打点 + 补齐 bundle。
 *
 * ===== 为什么要有这个类 =====
 * 打开动画现在走的是官方链路
 * （`miuix.transition.ActivityOptionsHelper.makeHyperViewScaleAnimation(view)`
 *   → `android.app.ActivityOptions.makeHyperViewScaleAnimation(view)`
 *   → `HyperViewScaleRemoteStub.setSystemView(view)`），
 * 所以**弹簧曲线、并行驱动、打断续接都是小米原生实现，不用我们复刻**。
 *
 * 但 `setSystemView` 造的 bundle 是"系统味"的：
 * <pre>
 *   bundle_third_party / bundle_dark_mode / bundle_radius(固定值)
 * </pre>
 * 它**没有** `bundle_fill_bg_color`、没有按视图类算的 `corner`、没有 `bundle_list_cover`。
 * 而这三样正是"白边/黑边""大卡片观感不对"的来源：
 * <pre>
 *   HyperViewScaleRemoteManager.parseBundle(...)
 *     bundle_radius        -> ViewInfo.setRadius
 *     bundle_fill_bg_color -> getBackgroundColor(view, fillColorType)
 *                             type 不是 1/2 就记 "invalid fillColorType" 然后给个兜底色
 *                             → 截图后面垫的颜色不对 = 白边/黑边
 *     bundle_list_cover    -> ViewInfo.setListCover(topCover,bottomCover)
 * </pre>
 * 官方第三方路径（`performClick`）是从配置表里查这些值的，
 * 而 `performClick` 被 `isSystemProcess()` 挡住了 —— 所以我们在**同一个位置**
 * 把配置驱动的值补进去：hook `parseBundle` 的 before，缺哪个补哪个。
 *
 * ===== 关闭动画的观察点 =====
 * `HyperViewScaleRemoteTransition.startAnimation(...)` 第 3 步要求源视图
 * `isAttachedToWindow()` 且能重新 `getBoundsOnScreen()`；第 5 步把源视图挂到
 * `RenderNode.addPositionUpdateListener` 上。**源视图如果被回收/窗口销毁，
 * 关闭时就退化成纯 alpha 淡出 = 用户说的"没有退出动画"。**
 * 所以这里同时记录：源视图在整个 App 期间还挂不挂着、alpha 被改成了多少、
 * 关闭时系统有没有回调 `startAnimation`。
 */
public final class HvsDiagnostics {

    private HvsDiagnostics() {
    }

    static final String RM = "android.app.hyperviewscale.HyperViewScaleRemoteManager";
    static final String MGR = "android.app.hyperviewscale.HyperViewScaleManager";
    static final String RT = "android.app.hyperviewscale.HyperViewScaleRemoteTransition";
    static final String VI = "android.app.hyperviewscale.ViewInfo";
    /** framework.jar 里的 stub —— 官方启动桥就在它身上。 */
    static final String STUB = "android.app.hyperviewscale.HyperViewScaleRemoteStub";

    private static volatile Class<?> sStubClass;
    private static int sStubLogCount;
    private static int sBridgeLogCount;
    private static int sAnimLogCount2;
    private static int sBackAnimLogCount;
    /** 最近一次"开启动画"的时刻，用于"强制反向"的防误伤窗口。 */
    private static volatile long sLastOpenAt;
    private static volatile int sOpenGen;
    private static int sOnFinishLog;
    private static int sForceReverseLog;
    /**
     * 判定为"上滑回桌面"的那次合并的 debugId。
     *
     * `mergeAnimation` 和随后 `reverseAnimationWhenMerge` 拿到的是**同一个**
     * `TransitionInfo.getDebugId()`（实测 id=131/131、133/133……），
     * 所以用 id 对账比用时间窗口可靠得多，也不会误伤开启动画。
     */
    private static volatile long sReverseMergeId = -1L;
    private static volatile long sReverseMergeAt;
    private static volatile long sForceReverseFiredId = -1L;
    private static int sArmLogCount;
    /** 最近一次 `finishCurrentAnimation()` 的时刻 —— 打断守卫用。 */
    private static volatile long sFinishCurrentAt;

    /** `reverseAnimationWhenMerge(binder, A, B, view, debugId)` 的第 5 个参数。 */
    private static long mergeIdOf(XC_MethodHook.MethodHookParam p) {
        if (p.args != null && p.args.length >= 5 && p.args[4] instanceof Number) {
            return ((Number) p.args[4]).longValue();
        }
        return -1L;
    }

    /** 这个 id 是不是我们刚刚判定为"上滑回桌面"的那次合并。 */
    private static boolean armedFor(long id) {
        return sReverseMergeId >= 0 && id == sReverseMergeId
                && android.os.SystemClock.uptimeMillis() - sReverseMergeAt < 3000L;
    }

    private static volatile boolean sInstalled;
    private static int sParseLogCount;
    private static int sAnimLogCount;
    private static int sConfigLogCount;

    // ------------------------------------------------------------------ 安装

    public static void install(ClassLoader cl) {
        if (sInstalled) {
            return;
        }
        if (!Config.ENABLE_HVS_DIAG) {
            return;
        }
        final ClassLoader fcl = cl;
        if (installNow(fcl)) {
            return;
        }
        // impl jar 是 MiuiStubRegistry 运行时加载的，可能晚于 handleLoadPackage，
        // 所以在主线程上重试几次。
        final int[] delays = {300, 1000, 2500, 6000, 12000, 25000};
        for (final int d : delays) {
            post(new Runnable() {
                @Override
                public void run() {
                    if (!sInstalled) {
                        installNow(fcl);
                    }
                }
            }, d);
        }
    }

    private static synchronized boolean installNow(ClassLoader cl) {
        if (sInstalled) {
            return true;
        }
        // ---------------------------------------------------------------
        // A) stub 钩子：装在 framework.jar 里的类上，随时可用，不依赖 impl jar。
        //    目的：确认"到底有没有人调用 MIUI 自己的 startActivity 桥"。
        // ---------------------------------------------------------------
        Class<?> stub = findQuiet(cl, STUB);
        if (stub != null) {
            sStubClass = stub;
            for (final String m : new String[]{"startActivity", "performClick",
                    "handleBindApplication", "setSystemView", "onDestroy"}) {
                HookKit.hookAll(stub, m, new HookKit.Cb("HVS.stub." + m) {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        if (sStubLogCount++ < 40) {
                            L.i("[HVS桥] ⚑ 有人调用了 HyperViewScaleRemoteStub." + m
                                    + "  args=" + java.util.Arrays.toString(p.args)
                                    + "  from=" + stackTop());
                        }
                    }
                });
            }
            L.i("[HVS桥] 已挂 stub 钩子（用于确认 MIUI 自己的 startActivity 桥有没有被调用）");
        } else {
            L.w("[HVS桥] 找不到 " + STUB);
        }

        // ---------------------------------------------------------------
        // B) impl 钩子：要等 miui-framework.hyperviewscale.jar 被 MiuiStubRegistry 加载
        //
        // ⚠️ 踩过的坑：`MiuiStubRegistry.loadJar(path, false, appClassLoader)` 是把那个
        //    12.3MB 的 jar 装进一个**子 ClassLoader**（以 app CL 为 parent），impl 类
        //    **不能**用 `Class.forName(name, appCl)` 找到 —— 之前 6 次重试全部返回 null，
        //    日志里连一行都看不到，原因就在这里。
        //    正确做法：从 stub 的 `getInstance()` 拿到实现对象，再 `getClass()` +
        //    `getClassLoader()` 反推出来。
        // ---------------------------------------------------------------
        Class<?> rm = resolveImpl(cl);
        if (rm == null) {
            return false;
        }
        ClassLoader jarCl = rm.getClassLoader();
        L.i("[HVS] impl ClassLoader = " + (jarCl == null ? "null(boot)" : jarCl.getClass().getName()));
        sInstalled = true;
        L.i("################################################");
        L.i("# [HVS] 找到 HyperViewScale 实现，开始打点");
        L.i("################################################");
        logOurConfig(cl);

        // 1) 解析 bundle：补齐配置驱动的值 + 打印原始内容
        HookKit.hookAll(rm, "parseBundle", new HookKit.Cb("HVS.parseBundle") {
            @Override
            protected void onBefore(MethodHookParam p) {
                enrichBundle(p);
            }

            @Override
            protected void onAfter(MethodHookParam p) {
                if (sParseLogCount < 30) {
                    L.i("[HVS] parseBundle -> " + p.getResult());
                }
                // ★ 顺手把"返场动画"需要的素材留下来：
                //   官方截的小部件位图 + 起点矩形 + 圆角。
                //   TO_BACK（上滑回桌面）系统根本不给回调，只能我们自己播这一段。
                try {
                    Object vi = (p.args != null && p.args.length > 3) ? p.args[3] : null;
                    if (vi != null) {
                        Object v = XposedHelpers.callMethod(vi, "getView");
                        Object bmp = XposedHelpers.callMethod(vi, "getBitmap");
                        Object r = XposedHelpers.callMethod(vi, "getViewRect");
                        Object rad = XposedHelpers.callMethod(vi, "getRadius");
                        if (v instanceof View && bmp instanceof android.graphics.Bitmap
                                && r instanceof android.graphics.Rect) {
                            ReturnFlight.arm(cl, (View) v, (android.graphics.Bitmap) bmp,
                                    (android.graphics.Rect) r,
                                    rad instanceof Float ? (Float) rad : 0f);
                        }
                    }
                } catch (Throwable t) {
                    L.w("[返场] 留素材失败: " + t);
                }
            }
        });

        // 2) 动画真正起跑 / 关闭
        Class<?> rt = findQuiet2(jarCl, cl, RT);
        if (rt != null) {
            HookKit.hookAll(rt, "startAnimation", new HookKit.Cb("HVS.startAnimation") {
                @Override
                protected void onBefore(MethodHookParam p) {
                    logAnimation("startAnimation", p);
                }
            });
            HookKit.hookAll(rt, "mergeAnimation", new HookKit.Cb("HVS.mergeAnimation") {
                @Override
                protected void onBefore(MethodHookParam p) {
                    logAnimation("mergeAnimation(打断/合并)", p);
                    // ★ 判断"这次合并到底是不是上滑回桌面"。
                    //
                    // 事实（反汇编 `TransitionInfo.modeToString` 得到的真实值）：
                    //   MODE_NONE=0 OPENING=1 CLOSING=2 TO_FRONT=3 TO_BACK=4 CHANGING=6
                    // 实测三种合并：
                    //   type=1(TRANSIT_OPEN)   changeModes=[1,4]     ← 开启动画自己的合并
                    //   type=2(TRANSIT_CLOSE)  changeModes=[2,3,3]   ← 侧划返回（官方自己会反向）
                    //   type=1021              changeModes=[3,4,3] / [6,4]  ← 上滑回桌面
                    // 于是判据 = "type 不是 OPEN" 且 "有参与者被标成 TO_BACK(4)"。
                    // 这比原来的"距离开启动 1200ms"靠谱：不靠猜时间，看系统给的语义。
                    armReverseIfToBack(p);
                    logRootAlpha("merge");         // ★ 第 76 轮：合并/打断那一刻的窗口 alpha
                    // ★ 第 34 轮：这里**同步**补一次收尾事务。
                    //   依据：延迟 150/350/700/1100ms 的事务全部失败（句柄都已 <released>），
                    //   说明这个打断会在 150ms 内收掉 leash —— 只有"打断那一刻"它还活着。
                    // ★★ 第 37 轮：四个量写全（scale/crop/position/corner）后重新打开，
                    //   验证"整屏终态"这个假设。判据：应用白内容左边界 0px。
                    finishLeashToFullScreen(0L);
                    repeatFinish(6);
                }
            });
            HookKit.hookAll(rt, "onFinish", new HookKit.Cb("HVS.onFinish") {
                @Override
                protected void onBefore(MethodHookParam p) {
                    long dt = android.os.SystemClock.uptimeMillis() - sLastOpenAt;
                    // ★ 谁在 +2s 把过渡结束掉？—— 已查明（栈，17:43）：

                    //   android.os.Handler.handleCallback
                    //     ← HyperViewScaleRemoteTransition$$ExternalSyntheticLambda8.run
                    //       ← HyperViewScaleRemoteTransition.lambda$onFinish$4:536
                    //         ← HyperViewScaleRemoteTransition.onFinish
                    // 也就是 **MIUI 自己在 onFinish 里用 Handler postDelayed 约 2000ms
                    // 再回调一次 onFinish** 来收尾。这一次收尾一发生，系统就不再给我们
                    // `mergeAnimation` 了 —— 实测上滑延迟 1.0s 还能反向、2.0/3.0/4.0s 就
                    // 完全没有 merge。所以"窗口只有 1~2 秒"的元凶就是它。
                    //
                    // 对策：把这一次**推后**（`HVS_HOLD_TRANSITION_MS`），过渡继续活着，
                    // 窗口内上滑仍然走小米自己的原生反向（不是浮层）。
                    boolean delayed = false;
                    for (StackTraceElement e : new Throwable().getStackTrace()) {
                        if (e.getMethodName().contains("lambda$onFinish")) {
                            delayed = true;
                            break;
                        }
                    }
                    if (delayed && Config.HVS_HOLD_TRANSITION_MS > 0) {
                        final Object self = p.thisObject;
                        final java.lang.reflect.Member m = p.method;
                        final Object[] args = p.args;
                        final int gen = sOpenGen;
                        L.i("[HVS续命] 把 +" + (android.os.SystemClock.uptimeMillis() - sLastOpenAt)
                                + "ms 的自动收尾推后 " + Config.HVS_HOLD_TRANSITION_MS
                                + "ms（这之前上滑都还是原生反向）");
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (gen != sOpenGen) {
                                            L.i("[HVS续命] 期间已经又开了一次应用 → 不补这次收尾");
                                            return;
                                        }
                                        try {
                                            if (m instanceof java.lang.reflect.Method) {
                                                ((java.lang.reflect.Method) m).invoke(self, args);
                                                L.i("[HVS续命] 补上收尾（过渡结束）");
                                            } else {
                                                L.w("[HVS续命] 收尾成员不是 Method: " + m);
                                            }
                                        } catch (Throwable t) {
                                            L.w("[HVS续命] 补收尾失败: " + t);
                                        }
                                    }
                                }, Config.HVS_HOLD_TRANSITION_MS);
                        p.setResult(null);
                        return;
                    }
                    if (sOnFinishLog++ < 40) {
                        StringBuilder sb = new StringBuilder();
                        StackTraceElement[] st = new Throwable().getStackTrace();
                        for (int i = 0; i < st.length && i < 10; i++) {
                            sb.append(i == 0 ? "" : " ← ").append(st[i].getClassName()
                                    .replace("android.app.hyperviewscale.", ""))
                                    .append('.').append(st[i].getMethodName())
                                    .append(':').append(st[i].getLineNumber());
                        }
                        L.i("[HVS收尾] onFinish 距开启动画 " + dt + "ms   栈: " + sb);
                    }
                }

                @Override
                protected void onAfter(MethodHookParam p) {
                    L.i("[HVS] onFinish -> 过渡结束");
                }
            });
        } else {
            L.w("[HVS] 找不到 " + RT);
        }

        // 3) 收尾 / 状态清理 —— 关闭流程的必经点
        HookKit.hookAll(rm, "finishCurrentAnimation", new HookKit.Cb("HVS.finishCurrentAnimation") {
            @Override
            protected void onBefore(MethodHookParam p) {
                sFinishCurrentAt = android.os.SystemClock.uptimeMillis();
                L.i("[HVS] finishCurrentAnimation() 被调用（有动画在跑 → 收尾）");
                // ★★ 第 48 轮关键修正：**在这个方法里抓活 leash**。
                //   证据：`seq2`（进程内连续两次启动）那条路径上 `startHyperViewScaleAnimation`
                //   钩子根本不触发（日志里 `抓到leash字段=0`），于是我手里没有句柄、收尾一进来就
                //   `return`（成功/失败都是 0，正好对上）。
                //   而 `finishCurrentAnimation` 是**动画实例自己的方法**，`p.thisObject` 就是持有
                //   `mLeash` 的那个对象 —— 在这里抓，谁触发的都能覆盖。
                dumpScFields(p.thisObject);
                logRootAlpha("finish");        // ★ 第 76 轮：记录负一屏窗口此刻的 alpha
                finishLeashToFullScreen(0L);
                repeatFinish(4);
                // ★ 被打断时把 leash **推到终态**（铺满 + 屏幕圆角）
                //
                // 依据（量化实测）：被中断那次应用内容左边界留 ~32px、正常播完是 0px
                //  → 说明打断后窗口停在半途位置/尺寸，没人把它走完。
                // 做法：延迟两条事务（~80ms / ~400ms）分别补一次，避免和 MIUI 自己的收尾打架。
                if (sLeashSc != null) {
                    for (final long delay : new long[]{80L, 400L, 900L}) {
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        finishLeashToFullScreen(delay);
                                    }
                                }, delay);
                    }
                }
            }
        });
        HookKit.hookAll(rm, "reset", new HookKit.Cb("HVS.reset") {
            @Override
            protected void onBefore(MethodHookParam p) {
                L.i("[HVS] reset() —— 动画状态清空");
            }
        });

        // 4) ★ 关闭/反向动画的真正驱动点
        //
        // 实测：退出时系统给过来的**不是** type=2(CLOSE)，而是 mergeAnimation(type=1021)。
        // 也就是说"飞回去"这条路走的是 mergeAnimation → handleMergeAnimation
        // → HyperViewScaleAnimationImpl.reverseAnimationWhenMerge(...)。
        // 所以这里盯住这两个方法，并且把 ViewInfo 的 isBackAnim 打出来 ——
        // isBackAnim=false 就意味着官方压根没打算反向。
        installLatencyProbe();

        Class<?> ani = findQuiet2(jarCl, cl,
                "android.app.hyperviewscale.HyperViewScaleAnimationImpl");
        if (ani != null) {
            HookKit.hookAll(ani, "startHyperViewScaleAnimation", new HookKit.Cb("HVS.startHVScaleAnim") {
                @Override
                protected void onBefore(MethodHookParam p) {
                    // 记下开启动画时刻：给"强制反向"的防误伤窗口用
                    sLastOpenAt = android.os.SystemClock.uptimeMillis();
                    sOpenGen++;
                    sLatLogged = false;         // 新一次过渡 = 允许再量一次"点击→第一帧"
                    // ★ 诊断（第 33 轮）：把动画实例里**所有 SurfaceControl 字段**打出来。
                    //   动机：`createViewLeash` 给的那个 SurfaceControl 在 150ms 就 <released> 了，
                    //   所以"抓它、事后补事务"走不通。真正的 leash 一定由这个实例持有 —— 先看清字段名。
                    // ★ 第 74 轮：**每次动画开始时重置两个计数器**。
                    //   实测教训：写入探针上限 600 条在上一次验证时就用完了 → 之后所有过渡
                    //   一条都不再记录（"mask/blur/start 写入行数=0"），照这样去跑用户会话等于白测。
                    if (!sSfQueryTried && sLiveLeash != null) {
                        sSfQueryTried = true;
                        try {
                            Class<?> tq = Class.forName("android.view.SurfaceControl$Transaction");
                            java.lang.reflect.Constructor<?> cq = tq.getDeclaredConstructor();
                            cq.setAccessible(true);
                            Object t2 = cq.newInstance();
                            java.lang.reflect.Method mq = tq.getMethod("getSFAnimationParams",
                                    android.view.SurfaceControl.class, int[].class);
                            mq.invoke(t2, sLiveLeash, new int[]{-1, -1, 0, 2});
                            L.i("[SF查询2] getSFAnimationParams 成功（未 apply）leash有效="
                                    + sLiveLeash.isValid());
                        } catch (Throwable e) {
                            L.w("[SF查询2] 失败: " + e);
                        }
                    }
                    sLeashWriteLog = 0;
                    sScFieldLog = 0;

                    // ★ 第 17 轮：**只读验证** —— SF 是否接受我们对这个 leash 的调用。
                    //   调 getSFAnimationParams（查询用），**不 apply**，因此不改任何姿态。
                    if (!sSfQueryTried && sLiveLeash != null) {
                        sSfQueryTried = true;
                        try {
                            Class<?> tq = Class.forName("android.view.SurfaceControl$Transaction");
                            java.lang.reflect.Constructor<?> cq = tq.getDeclaredConstructor();
                            cq.setAccessible(true);
                            Object t2 = cq.newInstance();
                            java.lang.reflect.Method mq = tq.getMethod("getSFAnimationParams",
                                    android.view.SurfaceControl.class, int[].class);
                            mq.invoke(t2, sLiveLeash, new int[]{-1, -1, 0, 2});
                            L.i("[SF查询] getSFAnimationParams 成功（未 apply）  leash有效="
                                    + sLiveLeash.isValid());
                        } catch (Throwable e) {
                            L.w("[SF查询] 失败: " + e);
                        }
                    }
                    dumpScFields(p.thisObject);
                    // ★ 第 110 轮：**动画期间让"源组件快照"一直盖住那片黑**。
                    //   实测证据：动画中窗口背后是**无纹理死黑**（每像素≈1、方差≈0 ⇒ 那儿没东西在画），
                    //   而遮罩层 view_scale_mask（层列表里 z 最高）本该显示源组件快照 —— 淡出太早就露出黑。
                    //   这里在动画开始后 ~0.7 秒内反复把 mask/blur 的 alpha 顶到 1。
                    // ★ 第 111 轮：**动画期间把 mask 的 alpha 强制压在 0**（不动 blur）。
                    //   依据：上一轮"把 mask/blur 顶到 1"造成了整屏黑↔亮横跳，且亮度最低到 4.9
                    //   ⇒ **遮罩层一旦现形就是黑的**；正常播放时它本该淡出（0.84 → 0.00）。
                    //   所以"动画内部闪一下"最可能是它**某一两帧没按预期保持 0**。这里就把它压住。
                    if (Config.HVS_MASK_ZERO) {
                        for (final long d : new long[]{0L, 80L, 160L, 260L, 380L, 500L, 700L, 900L}) {
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                    .postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            forceMaskTransparent(d);
                                        }
                                    }, d);
                        }
                    }
                    if (Config.HVS_KEEP_MASK) {
                        for (final long d : new long[]{0L, 80L, 160L, 260L, 380L, 500L, 700L}) {
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                    .postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            keepMaskOpaque(d);
                                        }
                                    }, d);
                        }
                    }
                    // ★ B1 实验（仅 HVS_B1_TEST_WRITE=true 时生效）：动画开始 250ms 后**由我们抢写一帧**，
                    //   用来判断：① 我们的写入能否生效（画面是否变成我们写的值）；
                    //             ② MIUI 下一帧会不会立刻覆盖（决定后面是"每帧抢写"还是"必须先停掉它的驱动"）。
                    if (Config.HVS_B1_TEST_WRITE && sLiveLeash != null) {
                        final android.view.SurfaceControl b1sc = sLiveLeash;
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            Class<?> t = Class.forName(
                                                    "android.view.SurfaceControl$Transaction");
                                            java.lang.reflect.Constructor<?> c =
                                                    t.getDeclaredConstructor();
                                            c.setAccessible(true);
                                            Object tx = c.newInstance();
                                            t.getMethod("setPosition",
                                                            android.view.SurfaceControl.class,
                                                            float.class, float.class)
                                                    .invoke(tx, b1sc, 150f, 150f);
                                            t.getMethod("setScale",
                                                            android.view.SurfaceControl.class,
                                                            float.class, float.class)
                                                    .invoke(tx, b1sc, 0.4f, 0.4f);
                                            t.getMethod("setCornerRadius",
                                                            android.view.SurfaceControl.class,
                                                            float.class)
                                                    .invoke(tx, b1sc, 0f);
                                            t.getMethod("apply").invoke(tx);
                                            L.i("[B1] 抢写一帧: pos(150,150) scale0.4 corner0");
                                        } catch (Throwable e) {
                                            L.w("[B1] 写入失败: " + e);
                                        }
                                    }
                                }, 250L);
                    }
                    if (sAnimLogCount2++ < 200000) {
                        L.i("[HVS反向] startHyperViewScaleAnimation args="
                                + java.util.Arrays.toString(p.args)
                                + "  viewInfo=" + describeStaticViewInfo(p.thisObject));
                    }
                }
            });
            HookKit.hookAll(ani, "reverseAnimationWhenMerge", new HookKit.Cb("HVS.reverseWhenMerge") {
                @Override
                protected void onBefore(MethodHookParam p) {
                    L.i("[HVS反向] reverseAnimationWhenMerge args="
                            + java.util.Arrays.toString(p.args)
                            + "  viewInfo=" + describeStaticViewInfo(p.thisObject)
                            + "  动画在跑=" + boolField(p.thisObject, "mIsRunning")
                            + "  " + describeAnimInfo(p.thisObject));

                    // ★ TO_BACK（上滑回桌面）强制反向。
                    //
                    // 反汇编 `HyperViewScaleRemoteTransition.handleMergeAnimation`：
                    //     int type = info.getType();
                    //     mReverseToActivityA = (type == 2 /*TRANSIT_CLOSE*/) && mOpenScene;
                    //     mReverseToActivityB = (type == 1 /*TRANSIT_OPEN*/) && !mOpenScene;
                    //     reverseAnimationWhenMerge(binder, A, B, mViewInfo.getView(), info.getDebugId());
                    // 上滑回桌面时系统给的是 MIUI 自定义 type=1021，两个都落空 → 不反向。
                    // 参数就在调用点上，直接改参数，动画仍然由小米自己的实现跑
                    //（`HyperViewScaleAnimationImpl.reverseAnimationWhenMerge` 内部
                    //  用 Folme 把 leash 的 center/ratio/size/corner/maskAlpha 拉回 from 值）。
                    //
                    // 参数取 (true, false)：这正是实测**侧划返回**（type=2）时系统自己用的
                    // 组合，40/40 次都是它，用户确认能飞回去。B 保持原值不动，别自作聪明。
                    long id = mergeIdOf(p);
                    boolean armed = armedFor(id);
                    if (Config.HVS_FORCE_REVERSE_ON_TOBACK && armed && p.args != null && p.args.length >= 3) {
                        boolean alreadyTrue = Boolean.TRUE.equals(p.args[1]);
                        sForceReverseFiredId = id;
                        if (!alreadyTrue) {
                            p.args[1] = Boolean.TRUE;
                            if (sForceReverseLog++ < 30) {
                                L.i("[HVS反向] ★★ 强制反向：id=" + id + "，把 mReverseToActivityA false→true"
                                        + "（上滑回桌面系统自己不会反向，这一下就是「从哪里来回哪里去」）");
                            }
                        }
                    }
                }

                @Override
                protected void onAfter(MethodHookParam p) {
                    Object res = p.getResult();
                    long id = mergeIdOf(p);
                    L.i("[HVS反向] reverseAnimationWhenMerge 返回=" + res
                            + "（true=真的反向飞回去了，false=动画没在跑或 gate 没过 → 硬切）");
                    // Tier-2 兜底：官方反向没跑起来（最常见的原因是开启动画已经
                    // `mIsRunning=false`，那第一个 gate 就 return false 了），
                    // 就由我们自己把小部件"飞回去"这一段补上 —— 浮层用的还是
                    // 小米官方 CLOSE_POS_EASE 那条弹簧（ζ=0.29 / T=0.95s），
                    // 见 ReturnFlight.XiaomiCloseSpring。
                    if (Boolean.FALSE.equals(res) && id >= 0 && id == sForceReverseFiredId) {
                        sForceReverseFiredId = -1L;
                        // 官方反向没成立，几乎只有一个原因：开启动画已经收尾，
                        // `mIsRunning=false` 且 leash 已被 release。
                        // 这时候只能自己补浮层返场（曲线仍是官方 CLOSE_POS_EASE）。
                        L.i("[HVS反向] 官方反向没成立（多半是开启动画已收尾、leash 已 release）"
                                + " → 走 Tier-2 浮层返场");
                        ReturnFlight.playWhenWindowVisible();
                    } else if (id >= 0 && id == sForceReverseFiredId) {
                        sForceReverseFiredId = -1L;
                    }
                }
            });
        } else {
            L.w("[HVS反向] 找不到 HyperViewScaleAnimationImpl");
        }

        Class<?> viCls = findQuiet2(jarCl, cl, VI);
        if (viCls != null) {
            HookKit.hookAll(viCls, "setBackAnim", new HookKit.Cb("HVS.setBackAnim") {
                @Override
                protected void onBefore(MethodHookParam p) {
                    if (sBackAnimLogCount++ < 40) {
                        L.i("[HVS反向] ⚑ ViewInfo.setBackAnim("
                                + java.util.Arrays.toString(p.args) + ")  ← 谁把方向设成了反向");
                    }
                }
            });
            HookKit.hookAll(viCls, "setBitmap", new HookKit.Cb("HVS.setBitmap") {
                @Override
                protected void onBefore(MethodHookParam p) {
                    Object a = (p.args != null && p.args.length > 0) ? p.args[0] : null;
                    L.i("[HVS位图] ViewInfo.setBitmap("
                            + (a == null ? "❌ null（会退化成 alpha 淡入）" : "ok " + a) + ")");
                    dumpBitmap(a);
                }
            });
        }

        // 5) ★ 填充底色 —— 白边/黑边的正主
        //
        // `HyperViewScaleRemoteManager.setMaskSurface(rect, viewInfo)` 会建一块
        // **rect 那么大**（实测整屏 1200x2608）的 TRANSLUCENT buffer，然后调
        // `drawViewOnCanvas`：
        //
        //     c.drawColor(vi.getViewBackgroundColor());          // ①
        //     c.drawColor(getViewDominantColor(vi), SRC_OVER);   // ② ① 为 0 时走这条
        //     c.drawBitmap(bitmap, 0, 0, paint);                 // ③ 位图**按原尺寸**画在 (0,0)
        //
        // 而这块 mask 在 `initLeashChild` 里是
        //     reparent -> leash, setPosition(0,0), setScale(realScale),
        //     setLayer(Integer.MAX_VALUE), setAlpha(maskAlphaFrom), show()
        // 也就是**压在最上层的整块遮罩**。
        //
        // 实测几何（图标场景，来自 ViewScaleAnimInfo 的打印）：
        //     viewRect   = Rect(629,1320 - 757,1448)   128x128
        //     realScale  = 0.10666667 = 128/1200
        //     clipRect   = Rect(0,0 - 1200,1200)       = viewRect 尺寸 / realScale
        // 所以 buffer 里**真正对应图标的那块**是 (0,0)-(1200,1200)，可位图只有 128x128
        // 且画在 (0,0) —— 可见区里 128x128 是图标，**剩下 1072x1200 全是铺色**：
        //   · 铺官方主色（实测对负一屏图标是 #FFF8F8F8 / #FFE8E8E8）= **白框/矩形白边**
        //   · 铺深色（实测深色图标边缘色 #FF0B0B16，主色甚至直接 #FF000000）= **黑屏+只剩图标**
        // 这就是"有时候白、有时候黑"的原因：**取决于图标底色深浅**，跟改铺色无关。
        //
        // 正解：原始画完之后**自己重画这块 buffer** —— 清透明 + 位图等比铺满那块可见区。
        // 图标四角本来就是透明的（实测四角=[0,0,0,0]），圆角外自然什么都没有。
        if (Config.HVS_FIX_MASK_BUFFER) {
            HookKit.hookAll(rm, "setMaskSurface", new HookKit.Cb("HVS.setMaskSurface") {
                @Override
                protected void onAfter(MethodHookParam p) {
                    try {
                        Object sc = p.getResult();
                        Rect buf = (p.args != null && p.args.length > 0
                                && p.args[0] instanceof Rect) ? (Rect) p.args[0] : null;
                        Object vi = (p.args != null && p.args.length > 1) ? p.args[1] : null;
                        redrawMaskBuffer(sc, buf, vi);
                    } catch (Throwable t) {
                        L.w("[HVS遮罩] 重画失败: " + t);
                    }
                }
            });
        }

        // 5.5) ★ 拔掉 `_letterbox-bg` 整屏色块
        //
        // `initLeashChild` 里有一段：
        // <pre>
        //   if (letterboxBounds != null && !letterboxBounds.isEmpty()
        //           && backgroundColor != 0 && otherLeash != null && otherLeash.isValid()) {
        //       mLetterboxBgLayer = new SurfaceControl.Builder()
        //               .setName(otherLeash + "_letterbox-bg").setEffectLayer()
        //               .setParent(leash).setHidden(false).build();
        //       setColor(mLetterboxBgLayer, ARGB(backgroundColor));
        //       setAlpha(mLetterboxBgLayer, alpha);
        //       setLayer(mLetterboxBgLayer, -1);     // 压到最底
        //       show(mLetterboxBgLayer);
        //   }
        // </pre>
        // `backgroundColor` 来自 `extractLetterboxColor(change)`，也就是**应用自己的
        // letterbox 底色**。它是整屏一层不透明色块，压在应用窗口下面：动画期间应用窗口
        // 还没铺满屏幕，露出来的就是它 —— 深色应用就是"黑屏"。最后一个 `&&`
        // 是 `backgroundColor != 0`，所以让它返回 0，**这一层根本不会被创建**。
        if (Config.HVS_LETTERBOX_NO_BG) {
            HookKit.hookAll(rt, "extractLetterboxColor", new HookKit.Cb("HVS.extractLetterboxColor") {
                @Override
                protected void onAfter(MethodHookParam p) {
                    Object orig = p.getResult();
                    p.setResult(0);
                    if (sLetterboxLogCount++ < 30) {
                        L.i("[HVS边衬] extractLetterboxColor 原值=" + hex(orig)
                                + " → 0（_letterbox-bg 那一整层就不会被创建）");
                    }
                }
            });
        }

        if (Config.HVS_FILL_FROM_BITMAP_EDGE) {
            HookKit.hookAll(rm, "getBackgroundColor", new HookKit.Cb("HVS.getBackgroundColor") {
                @Override
                protected void onAfter(MethodHookParam p) {
                    Object orig = p.getResult();
                    int edge = ReturnFlight.armedEdgeColor();
                    if (edge == 0) {
                        return;                 // 没素材就别动，保持官方值
                    }
                    p.setResult(edge);
                    if (sFillLogCount++ < 30) {
                        L.i("[HVS填充] getBackgroundColor 原值=" + hex(orig)
                                + " → 改为位图边缘色 " + hex(edge));
                    }
                }
            });
        } else if (Config.HVS_FORCE_TRANSPARENT_FILL) {
            HookKit.hookAll(rm, "getBackgroundColor", new HookKit.Cb("HVS.getBackgroundColor") {
                @Override
                protected void onAfter(MethodHookParam p) {
                    Object orig = p.getResult();
                    p.setResult(0);      // 0 = 全透明
                    if (sFillLogCount++ < 30) {
                        L.i("[HVS填充] getBackgroundColor 原值=" + hex(orig)
                                + " → 改为 0x00000000（透明）");
                    }
                }
            });
        }

        // 6) ★★ 遮罩层的"主色" —— 矩形白边的真凶（同一个坑的第二条路）
        //
        // `drawViewOnCanvas` 是这么画的：
        //     c.drawColor(vi.getViewBackgroundColor());            // 上面第 5 步
        //     c.drawColor(getViewDominantColor(vi), SRC_OVER);     // ← ViewInfo 背景色为 0 时走这条
        //     c.drawBitmap(bitmap, 0, 0, paint);
        // `getViewDominantColor` 在位图存在且 viewColor 未设置时走
        // `ColorPickerUtils.pickColorFromBitmap` —— 取的是**图标主色**。
        // 实测拉下来的位图里，`bitmap_8` 有 72.9% 的不透明像素接近纯白、`bitmap_6` 12.6%
        // ——这些图标的主色就是白色 → 遮罩层被铺成**白色矩形** → 位图的圆角之外露出白色
        // = 用户说的「矩形白边」。
        //
        // 同样改成**位图边缘色**（不是透明！透明会露出黑底 = 黑屏）。
        // 只在**位图存在**时才动手：位图缺失时这个颜色是唯一的可见填充。
        if (Config.HVS_FILL_FROM_BITMAP_EDGE || Config.HVS_FORCE_TRANSPARENT_DOMINANT) {
            HookKit.hookAll(rm, "getViewDominantColor", new HookKit.Cb("HVS.getViewDominantColor") {
                @Override
                protected void onAfter(MethodHookParam p) {
                    Object orig = p.getResult();
                    Object vi = (p.args != null && p.args.length > 0) ? p.args[0] : null;
                    Object bmp = refl(vi, "getBitmap");
                    if (!(bmp instanceof android.graphics.Bitmap)) {
                        return;     // 没位图就不能动
                    }
                    int edge = Config.HVS_FILL_FROM_BITMAP_EDGE
                            ? edgeColorOf((android.graphics.Bitmap) bmp)
                            : 0;
                    if (Config.HVS_FILL_FROM_BITMAP_EDGE && edge == 0) {
                        return;
                    }
                    p.setResult(edge);
                    if (sDominantLogCount++ < 30) {
                        L.i("[HVS主色] getViewDominantColor 原值=" + hex(orig)
                                + " → 改为 " + (Config.HVS_FILL_FROM_BITMAP_EDGE
                                ? "位图边缘色 " + hex(edge) : "0x00000000（透明）"));
                    }
                }
            });
        }

        // 7) ★ 几何量打点 —— 定位"图标外面那圈白框"
        //
        // 反馈：图标外面套了一圈白框、框和图标之间还有空隙。
        // 说明有一块**比图标大的方形区域**被画成了白色，而位图本身只有圆角外透明。
        // 靠这几个数就能分清到底是遮罩层（两个颜色都已改透明）还是 leash（应用窗口本身）：
        //   · ViewScaleAnimInfo.toString() 会把 startBounds / viewRect / screenBounds /
        //     fromCorner / toCorner / fromCornerRadii / realScale / shadowRadius 全打出来
        //   · 遮罩层矩形 vs 位图尺寸 —— 相等就说明白框不是遮罩层
        Class<?> vsai = findQuiet2(jarCl, cl, "android.app.hyperviewscale.ViewScaleAnimInfo");
        if (vsai != null) {
            // 注意：构造函数要用 hookAllConstructors，hookAllMethods("<init>") 打不中
            // （上一版就是这么白挂的，一条 ViewScaleAnimInfo 都没打出来）
            try {
                de.robv.android.xposed.XposedBridge.hookAllConstructors(vsai,
                        new HookKit.Cb("HVS.ViewScaleAnimInfo.<init>") {
                            @Override
                            protected void onBefore(MethodHookParam p) {
                                fakeOnScreenSource(p);
                            }

                            @Override
                            protected void onAfter(MethodHookParam p) {
                                // ★★ 第 2 步探针「快照保活」：把 mask 的**淡出目标**从 0 改成 1。
                                //
                                // 依据（录屏逐帧实证）：那张"空白大圆角窗"发生在**应用窗口已经
                                // 顶上来、但应用还没画出第一帧**的那 0.15~0.40 秒里（空白窗结束后
                                // 紧接着就是应用内容）。MIUI 的 mask（`maskAlphaFrom=1.0 → To=0`）
                                // 本该在这段时间把**源组件的快照**留在画面上，而它淡出得太早，
                                // 于是露出应用的**空白色窗口**。
                                //
                                // 所以这里把 `maskAlphaTo` 顶成 1.0，让快照不提前淡出。
                                // 代价（已知）：过渡期间源快照会多留一会儿（最多到 MIUI 收尾 / release），
                                // 先用它做**一次验证**：如果空白段归零、且没有新问题，再把它做成正式开关；
                                // 如果变成"组件一直盖在应用上"，就撤掉改成"按首帧回调撤快照"。
                                try {
                                    java.lang.reflect.Field mf = null;
                                    for (Class<?> c = p.thisObject.getClass(); c != null && mf == null;
                                            c = c.getSuperclass()) {
                                        try {
                                            mf = c.getDeclaredField("maskAlphaTo");
                                        } catch (NoSuchFieldException ignored) {
                                        }
                                    }
                                    if (mf != null) {
                                        mf.setAccessible(true);
                                        Object cur = mf.get(p.thisObject);
                                        // ★★ 第 55 轮**撤掉**这个"快照保活"探针：
                                        //   它把 `maskAlphaTo` 从 0 顶成 1.0，但第 31 轮已实测证明
                                        //   **它并没有解决空窗问题**（空白段 10→13，没改善），
                                        //   却一直在改系统的遮罩淡出行为（实测日志里 maskAlphaTo 恒为 1.0）。
                                        //   没有收益的改动就得撤 —— 这是我给自己定的规矩。
                                        if (false && cur instanceof Number
                                                && ((Number) cur).floatValue() < 0.99f) {
                                            mf.setFloat(p.thisObject, 1.0f);
                                            if (sMaskHoldLog++ < 60) {
                                                L.i("[快照保活] maskAlphaTo " + cur
                                                        + " → 1.0（首帧前不再淡出）");
                                            }
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                                // ★ 第 93 轮准备（默认关，需用户点头才开）：把这次过渡的**全屏模糊压掉**，
                                //   用来验证"模糊是否拖垮合成"（判据：重复帧比例 0.37 → ?）。
                                if (Config.HVS_DISABLE_TRANSITION_BLUR) {
                                    for (String fn : new String[]{"blurRadiusTo", "blurAlphaTo",
                                            "blurRatioTo"}) {
                                        try {
                                            java.lang.reflect.Field bf = null;
                                            for (Class<?> c = p.thisObject.getClass(); c != null && bf == null;
                                                    c = c.getSuperclass()) {
                                                try {
                                                    bf = c.getDeclaredField(fn);
                                                } catch (NoSuchFieldException ignored2) {
                                                }
                                            }
                                            if (bf != null) {
                                                bf.setAccessible(true);
                                                bf.setFloat(p.thisObject, 0f);
                                            }
                                        } catch (Throwable ignored2) {
                                        }
                                    }
                                    if (sBlurOffLog++ < 20) {
                                        L.i("[关模糊] 已把 blurRadiusTo/blurAlphaTo/blurRatioTo 压成 0");
                                    }
                                }
                                // ★ B2（第 100 轮）：把"MIUI 每帧推进动画的入口"看清 —— 只读，不改行为。
                                //   背景：B1 已证明"跟 MIUI 抢帧"无效（我们写的那一帧没生效），
                                //   所以要自持驱动，必须先知道它的驱动回调长什么样、拦不拦得住。
                                try {
                                    Object info = p.thisObject;
                                    java.lang.reflect.Field cf = null;
                                    for (Class<?> c = info.getClass(); c != null && cf == null;
                                            c = c.getSuperclass()) {
                                        try {
                                            cf = c.getDeclaredField("mAnimationCallback");
                                        } catch (NoSuchFieldException e2) {
                                        }
                                    }
                                    if (cf == null) {
                                        if (sB2Log++ < 8) {
                                            L.i("[B2] 没有 mAnimationCallback 字段");
                                        }
                                    } else {
                                        cf.setAccessible(true);
                                        Object cb = cf.get(info);
                                        if (cb == null) {
                                            if (sB2Log++ < 8) {
                                                L.i("[B2] mAnimationCallback = null（此刻还没设）");
                                            }
                                        } else {
                                            Class<?> ci = cb.getClass();
                                            StringBuilder msb = new StringBuilder();
                                            for (java.lang.reflect.Method m : ci.getMethods()) {
                                                if (m.getDeclaringClass() != Object.class) {
                                                    msb.append(m.getName()).append('/')
                                                            .append(m.getParameterCount()).append(' ');
                                                }
                                            }
                                            if (sB2Log++ < 8) {
                                                L.i("[B2] callback=" + ci.getName()
                                                        + "  ifaces=" + java.util.Arrays.toString(
                                                                ci.getInterfaces())
                                                        + "  methods=" + msb);
                                            }
                                            // ★ B2 修正（第 102 轮）：**在这里**挂 animationProgress 的 hook。
                                            //   为什么放这儿：`Class.forName` 在模块自己的 ClassLoader 里
                                            //   看不到 android.app.hyperviewscale.AnimationCallback（实测
                                            //   ClassNotFoundException），但用**实例的 getClass()** 就能拿到
                                            //   并且能 hook —— 我们正是用它打印出了方法列表。
                                            if (!sB2Hooked) {
                                                sB2Hooked = true;
                                                try {
                                                    HookKit.hookAll(ci, "animationProgress",
                                                            new HookKit.Cb("B2.animationProgress") {
                                                                @Override
                                                                protected void onBefore(MethodHookParam p2) {
                                                                    try {
                                                                        if (sB2ProgLog++ < 6000) {
                                                                            long now = android.os.SystemClock
                                                                                    .uptimeMillis();
                                                                            Object a = (p2.args != null
                                                                                    && p2.args.length > 0)
                                                                                    ? p2.args[0] : null;
                                                                            L.i("[B2进度] t=" + now + " v=" + a);
                                                                        }
                                                                    } catch (Throwable ignored3) {
                                                                    }
                                                                }
                                                            });
                                                    L.i("[B2进度] 已按实例类挂上 hook: " + ci.getName());
                                                } catch (Throwable t3) {
                                                    L.w("[B2进度] 挂 hook 失败: " + t3);
                                                }
                                            }
                                        }
                                    }
                                } catch (Throwable t2) {
                                    if (sB2Log++ < 8) {
                                        L.w("[B2] 取 callback 失败: " + t2);
                                    }
                                }
                                if (sGeomLogCount++ < 300) {
                                    L.i("[HVS几何] ViewScaleAnimInfo = " + p.thisObject);
                                }
                            }
                        });
                L.i("hookAll OK ViewScaleAnimInfo.<init>");
            } catch (Throwable t) {
                L.w("hookAll FAIL ViewScaleAnimInfo.<init>  (" + t + ")");
            }
            // 双保险：isValid() 紧跟构造之后被调用，就算构造函数钩不上也能拿到几何量
            HookKit.hookAll(vsai, "isValid", new HookKit.Cb("HVS.ViewScaleAnimInfo.isValid") {
                @Override
                protected void onAfter(MethodHookParam p) {
                    if (sGeomLogCount2++ < 300) {
                        L.i("[HVS几何] ViewScaleAnimInfo(isValid=" + p.getResult() + ") = " + p.thisObject);
                    }
                }
            });
        } else {
            L.w("[HVS几何] 找不到 ViewScaleAnimInfo");
        }

        HookKit.hookAll(rm, "setMaskSurface", new HookKit.Cb("HVS.setMaskSurface") {
            @Override
            protected void onBefore(MethodHookParam p) {
                Object rect = (p.args != null && p.args.length > 0) ? p.args[0] : null;
                Object vi = (p.args != null && p.args.length > 1) ? p.args[1] : null;
                String viDesc;
                try {
                    Object bmp = XposedHelpers.callMethod(vi, "getBitmap");
                    viDesc = "bitmap=" + (bmp instanceof android.graphics.Bitmap
                            ? ((android.graphics.Bitmap) bmp).getWidth() + "x"
                            + ((android.graphics.Bitmap) bmp).getHeight() : "null")
                            + " radius=" + XposedHelpers.callMethod(vi, "getRadius")
                            + " shadow=" + XposedHelpers.callMethod(vi, "getShadowRadius")
                            + " bgColor=" + hex(XposedHelpers.callMethod(vi, "getViewBackgroundColor"))
                            + " viewColor=" + hex(XposedHelpers.callMethod(vi, "getViewColor"))
                            + " visible=" + XposedHelpers.callMethod(vi, "isViewVisible")
                            + " thirdParty=" + XposedHelpers.callMethod(vi, "isThirdParty");
                } catch (Throwable t) {
                    viDesc = "<" + t + ">";
                }
                L.i("[HVS几何] setMaskSurface rect=" + rect + "   " + viDesc
                        + "   ← 遮罩层就是这块矩形，比位图大出来的部分＝白框嫌疑");
            }
        });

        HookKit.hookAll(rm, "createViewLeash", new HookKit.Cb("HVS.createViewLeash") {
            @Override
            protected void onBefore(MethodHookParam p) {
                // ★ 记住 leash 与几何：中断时我们要**自己把它推到终态**
                //   实测（第 29 轮量化）：被中断的那次，应用内容左边界留了 ~32px（没铺满），
                //   正常播完是 0px。所以要拿这个 SurfaceControl 在中断后补一条事务。
                try {
                    if (p.args != null && p.args.length >= 4) {
                        if (p.args[1] instanceof android.view.SurfaceControl) {
                            sLeashSc = (android.view.SurfaceControl) p.args[1];
                        }
                        sLeashInfo = p.args[3];
                        sLeashAt = android.os.SystemClock.uptimeMillis();
                    }
                } catch (Throwable ignored) {
                }
                // ★ 超时兜底收尾（第 30 轮教训：把触发点挂在 `finishCurrentAnimation` 上**不会触发** ——
                //   实测那个打断模式里 MIUI 一次都没调它，于是我的收尾例程从没执行、左边界还是 36px）。
                //   所以改成**在动画一开始就排定时器**：700ms / 1100ms 后无论系统给什么形状的合并，
                //   都把 leash 推到终态（铺满 + 屏幕圆角）。正常播完时这些事务是幂等的。
                for (final long d : new long[]{150L, 350L, 700L, 1100L}) {
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    finishLeashToFullScreen(d);
                                }
                            }, d);
                }
                if (sLeashLogCount++ < 30) {
                    L.i("[HVS几何] createViewLeash args=" + dumpArgs(p.args));
                }
            }
        });

        HookKit.hookAll(rm, "applyCornerRadii", new HookKit.Cb("HVS.applyCornerRadii") {
            @Override
            protected void onBefore(MethodHookParam p) {
                if (sLeashLogCount++ < 30) {
                    L.i("[HVS几何] applyCornerRadii args=" + dumpArgs(p.args));
                }
            }
        });
        return true;
    }

    private static int sGeomLogCount;
    private static int sGeomLogCount2;
    private static int sLeashLogCount;

    private static String hex(Object v) {
        return (v instanceof Integer) ? "#" + String.format("%08X", (Integer) v) : String.valueOf(v);
    }

    /** 打点用：把参数列表压成一行（Rect / 浮点数组会展开）。 */
    private static String dumpArgs(Object[] args) {
        if (args == null) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (i > 0) {
                sb.append(", ");
            }
            if (a == null) {
                sb.append("null");
            } else if (a instanceof float[]) {
                sb.append(java.util.Arrays.toString((float[]) a));
            } else if (a instanceof android.graphics.Rect || a instanceof Float
                    || a instanceof Integer || a instanceof Boolean || a instanceof String) {
                sb.append(a);
            } else {
                sb.append(a.getClass().getSimpleName()).append('@')
                        .append(Integer.toHexString(System.identityHashCode(a)));
            }
        }
        return sb.append(']').toString();
    }

    private static int sDominantLogCount;

    private static int sFillLogCount;
    private static int sLetterboxLogCount;
    // ------------------------------------------------------------------ 位图落盘

    /**
     * 把官方截下来的位图存到应用外部目录，用来**直接看**白边到底是什么。
     *
     * 之前只能靠推理：到底是图标本身自带白底，还是遮罩层填充色露出来？
     * 把这张图画出来一看就知道。存到
     * `/sdcard/Android/data/com.miui.personalassistant/files/hvs/`，
     * adb 可以直接 pull。
     */
    private static int sBitmapSaveCount;

    private static void dumpBitmap(Object bmp) {
        if (!Config.HVS_SAVE_BITMAPS || !(bmp instanceof android.graphics.Bitmap)) {
            return;
        }
        if (sBitmapSaveCount >= Config.HVS_SAVE_BITMAP_MAX) {
            return;
        }
        final android.graphics.Bitmap b = (android.graphics.Bitmap) bmp;
        final int idx = ++sBitmapSaveCount;
        post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context app = appContext();
                    if (app == null) {
                        L.w("[HVS位图] 拿不到 Context，无法落盘");
                        return;
                    }
                    java.io.File dir = new java.io.File(app.getExternalFilesDir(null), "hvs");
                    if (!dir.exists() && !dir.mkdirs()) {
                        L.w("[HVS位图] 建目录失败: " + dir);
                        return;
                    }
                    java.io.File f = new java.io.File(dir, "bitmap_" + idx + "_"
                            + b.getWidth() + "x" + b.getHeight() + ".png");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                    b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
                    fos.close();
                    // 顺带统计：四角是不是不透明的（不透明 = 图标自带底色 = 白边来源之一）
                    L.i("[HVS位图] 已保存 " + f.getAbsolutePath()
                            + "  hasAlpha=" + b.hasAlpha()
                            + "  四角=" + cornerAlpha(b));
                } catch (Throwable t) {
                    L.w("[HVS位图] 落盘失败: " + t);
                }
            }
        }, 0L);
    }

    private static String cornerAlpha(android.graphics.Bitmap b) {
        try {
            int w = b.getWidth();
            int h = b.getHeight();
            int x = Math.max(0, Math.min(1, w - 1));
            int y = Math.max(0, Math.min(1, h - 1));
            return "["
                    + Integer.toHexString(b.getPixel(x, y)) + ","
                    + Integer.toHexString(b.getPixel(w - 1 - x, y)) + ","
                    + Integer.toHexString(b.getPixel(x, h - 1 - y)) + ","
                    + Integer.toHexString(b.getPixel(w - 1 - x, h - 1 - y)) + "]";
        } catch (Throwable t) {
            return "<?>";
        }
    }

    private static volatile Context sAppCtx;

    private static Context appContext() {
        Context c = sAppCtx;
        if (c != null) {
            return c;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = XposedHelpers.callStaticMethod(at, "currentApplication");
            if (app instanceof Context) {
                sAppCtx = (Context) app;
                return sAppCtx;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Class<?> findQuiet(ClassLoader cl, String name) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 先按 jar 的 ClassLoader 找，再按 app ClassLoader 找。
     *
     * impl jar 里的类（`ViewInfo` / `HyperViewScaleRemoteTransition` 等）只在子 CL 里可见，
     * 而 `HyperViewScaleConfig` 这种在 framework.jar 里的只在 app/boot CL 里可见。
     */
    private static Class<?> findQuiet2(ClassLoader jarCl, ClassLoader appCl, String name) {
        Class<?> c = jarCl == null ? null : findQuiet(jarCl, name);
        return c != null ? c : findQuiet(appCl, name);
    }

    private static volatile Class<?> sResolvedImpl;
    private static volatile boolean sLoggedNoInstance;

    /**
     * 通过 stub 的 `getInstance()` 拿到实现类（它在那个 12.3MB jar 的子 ClassLoader 里）。
     *
     * 注意 `getInstance()` 可能返回 null —— `MiuiStubRegistry` 还没把 impl 绑上去。
     * 那就等下一次重试，不要报错。
     */
    private static Class<?> resolveImpl(ClassLoader appCl) {
        Class<?> cached = sResolvedImpl;
        if (cached != null) {
            return cached;
        }
        Class<?> stub = findQuiet(appCl, STUB);
        if (stub == null) {
            return null;
        }
        try {
            Object inst = XposedHelpers.callStaticMethod(stub, "getInstance");
            if (inst == null) {
                if (!sLoggedNoInstance) {
                    sLoggedNoInstance = true;
                    L.i("[HVS] stub.getInstance() 还是 null（impl 尚未绑定），继续等重试");
                }
                return null;
            }
            Class<?> impl = inst.getClass();
            sResolvedImpl = impl;
            L.i("[HVS] 由 getInstance() 解析到实现类: " + impl.getName());
            return impl;
        } catch (Throwable t) {
            L.w("[HVS] getInstance() 调用失败: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------ 配置自检

    /**
     * 打印"我们自己的 ApplicationInfo 里有没有 HyperViewScale 配置"。
     *
     * 这是判断 system_server 注入有没有成功的**唯一可靠证据**：
     * 官方 `HyperViewScaleConfigManager.preBindApplication` 会把
     * `metaData["HyperViewScale"]` 塞进来。
     */
    private static void logOurConfig(ClassLoader cl) {
        try {
            Class<?> at = findQuiet(cl, "android.app.ActivityThread");
            Object app = at == null ? null : XposedHelpers.callStaticMethod(at, "currentApplication");
            Object ai = app == null ? null : XposedHelpers.callMethod(app, "getApplicationInfo");
            if (ai == null) {
                L.w("[HVS] 拿不到 ApplicationInfo");
                return;
            }
            String pkg = (String) XposedHelpers.getObjectField(ai, "packageName");
            Bundle md = (Bundle) XposedHelpers.getObjectField(ai, "metaData");
            Object cfg = null;
            if (md != null) {
                try {
                    Class<?> cfgC = findQuiet(cl, "android.app.hyperviewscale.HyperViewScaleConfig");
                    if (cfgC != null) {
                        Method m = Bundle.class.getMethod("getParcelable", String.class, Class.class);
                        cfg = m.invoke(md, "HyperViewScale", cfgC);
                    }
                } catch (Throwable t) {
                    L.w("[HVS] 读 metaData 失败: " + t);
                }
            }
            L.i("[HVS] 本进程包名=" + pkg
                    + "  metaData" + (md == null ? "=null" : ".keys=" + md.keySet())
                    + "  HyperViewScale配置=" + (cfg == null ? "❌ 没有（system_server 注入未生效）" : "✔ " + cfg));
            if (cfg != null) {
                for (String g : new String[]{"getStartFrom", "getStartTo"}) {
                    try {
                        Object list = XposedHelpers.callMethod(cfg, g);
                        L.i("[HVS] 配置 " + g + "() = " + list);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            L.w("[HVS] logOurConfig 失败: " + t);
        }
    }

    // ------------------------------------------------------------------ 本地圆角兜底

    /**
     * 官方配置表里没有这个视图类时用的本地圆角表（单位 dp）。
     *
     * 为什么要在应用侧再抄一份：配置表那份是 system_server 注入的，加一个视图类
     * 就得重启 system_server（=重启手机）。而负一屏的快捷方式/卡片视图类是会变的，
     * 放在应用侧兜底，改一次只要重装 + 重启负一屏。
     */
    private static final Object[][] LOCAL_CORNERS = {
            {"android.widget.ImageView", 12f},
            {"android.widget.TextView", 12f},
            {"android.widget.Button", 12f},
            {"android.widget.ImageButton", 12f},
            {"android.widget.LinearLayout", 16f},
            {"android.widget.FrameLayout", 16f},
            {"android.widget.RelativeLayout", 16f},
            {"android.widget.ConstraintLayout", 16f},
            {"com.miui.personalassistant.core.view.WidgetCardView", 16f},
            {"com.miui.personalassistant.core.view.MaMlHostView", 16f},
    };

    private static float localCornerDp(String viewClass) {
        // 先精确匹配类名，再退到"父类名"（很多卡片是自定义子类）
        for (Object[] row : LOCAL_CORNERS) {
            if (row[0].equals(viewClass)) {
                return (Float) row[1];
            }
        }
        try {
            Class<?> c = Class.forName(viewClass, false,
                    HvsDiagnostics.class.getClassLoader());
            Class<?> sup = c.getSuperclass();
            if (sup != null) {
                for (Object[] row : LOCAL_CORNERS) {
                    if (row[0].equals(sup.getName())) {
                        return (Float) row[1];
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return 16f;
    }

    private static float density(Context ctx) {
        try {
            return ctx.getResources().getDisplayMetrics().density;
        } catch (Throwable t) {
            return 3f;
        }
    }

    // ------------------------------------------------------------------ 官方启动桥

    /**
     * 补上"关闭动画"缺失的那一环：调用小米自己的
     * `HyperViewScaleRemoteStub.startActivity(Activity, Context, Bundle, Intent) -> Bundle`。
     *
     * ===== 为什么需要这一步（这是本轮最关键的发现）=====
     * `android.app.ActivityOptions.makeHyperViewScaleAnimation(View)` 的真实实现只有三行：
     * <pre>
     *   ActivityOptions o = new ActivityOptions();
     *   o.mAnimationType = 104;                                   // -> android:activity.animType
     *   viewName = view.getClass().getName();                     // -> android:activity.view.name
     *   HyperViewScaleRemoteStub.getInstance().setSystemView(view);  // 只"建数据"，不返回
     *   return o;                                                 // ← options 里【没有】RemoteTransition
     * </pre>
     * 真正的"把 RemoteTransition 交出去"发生在
     * `HyperViewScaleRemoteManager.startActivity(...)` 里：
     * <pre>
     *   startActivity(act, ctx, options, intent)
     *     └─ isSystemStart(options) ? handleSystemStart(options, intent)      // animType 路径（桌面走这条）
     *                                : handleThirdPartyStart(act, options, intent)
     *          └─ modifyNextIntent(intent, bundle):
     *                 RemoteTransition rt = ActivityOptions.fromBundle(b).getRemoteTransition();
     *                 intent.putExtra("view_scale_open", rt);      // ★ 关闭动画唯一的触发条件
     * </pre>
     * 而 system_server 侧的关闭过渡正是从这个 extra 起的：
     * <pre>
     *   ActivityRecord.finishIfPossible() / ActivityRecord.handleAppDied()
     *     └─ HyperViewScaleTransitionStub.getInstance()
     *            .requestViewScaleRemoteClose(TransitionController, Intent, WindowContainer)
     * </pre>
     * **但 framework.jar 里没有任何地方调用 `HyperViewScaleRemoteStub.startActivity`**
     * （已用补全了 range 形式 invoke 的反汇编器全量扫过 framework.jar / services.jar / 两个
     *   hyperviewscale jar）。所以那个 extra 从来没人写 → 系统不知道该怎么"飞回去"
     *   → **退出时没有过渡动画**。
     *
     * 这里直接调用小米自己的那个方法（而不是自己复刻 `modifyNextIntent`），
     * 让官方代码去写 extra 并返回富化后的 options。
     *
     * 传 `null` 当 Activity：`startActivity` 里 `ActivityStub.isEmbedded(null)` 之后
     * 走 `isSystemStart(options)` 分支（我们的 options 是 animType=104，正是"系统启动"），
     * 那条路径不用 Activity。任何异常都吞掉并回退原 options —— 保证应用照样能开。
     */
    public static Bundle bridgeToMiui(Context ctx, Bundle opts, Intent it) {
        if (!Config.USE_MIUI_START_ACTIVITY_BRIDGE || opts == null || it == null) {
            return opts;
        }
        Class<?> stub = sStubClass;
        if (stub == null) {
            if (sBridgeLogCount++ < 3) {
                L.w("[HVS桥] stub 类未就绪，跳过（应用照常打开）");
            }
            return opts;
        }
        try {
            Object inst = XposedHelpers.callStaticMethod(stub, "getInstance");
            java.lang.reflect.Method m = stub.getMethod("startActivity",
                    android.app.Activity.class, Context.class, Bundle.class, Intent.class);
            m.setAccessible(true);
            Object r = m.invoke(inst, null, ctx, opts, it);
            // ⚠️ `hasExtra` 为 true **不代表值不是 null** —— Bundle 允许 put 一个 null。
            //    而 system_server 侧 requestViewScaleRemoteClose 里是
            //        intent.getExtras().getParcelable("view_scale_open", RemoteTransition.class)
            //    拿到 null 就等于"没有远程过渡"，退出就变成默认硬切。
            //    所以这里必须打**实际值**，不能只打 true/false。
            Object extraVal = null;
            boolean hasExtra = it.hasExtra("view_scale_open");
            try {
                extraVal = it.getParcelableExtra("view_scale_open");
            } catch (Throwable ignored) {
            }
            boolean rtInOpts = opts.containsKey("android:activity.remoteTransition");
            Object rtVal = null;
            try {
                rtVal = opts.getParcelable("android:activity.remoteTransition");
            } catch (Throwable ignored) {
            }
            if (sBridgeLogCount++ < 40) {
                L.i("[HVS桥] 官方 startActivity 返回=" + (r instanceof Bundle ? "Bundle" : String.valueOf(r))
                        + "  intent.view_scale_open: has=" + hasExtra + " 值=" + extraVal
                        + "  |  options.remoteTransition: 有键=" + rtInOpts + " 值=" + rtVal
                        + (r instanceof Bundle
                        ? "  新keys=" + ((Bundle) r).keySet() : ""));
            }
            // 如果官方没写进去（或写了个 null），就用 options 里那个补上 ——
            // 这一条是退出动画能不能起来的直接开关。
            if (extraVal == null && rtVal != null) {
                try {
                    it.putExtra("view_scale_open", (android.os.Parcelable) rtVal);
                    L.i("[HVS桥] ★ view_scale_open 为空/缺失，已用 options 里的 RemoteTransition 补写"
                            + "（debugName=" + debugNameOf(rtVal) + "）");
                } catch (Throwable t) {
                    L.w("[HVS桥] 补写 view_scale_open 失败: " + t);
                }
            } else if (extraVal != null) {
                L.i("[HVS桥] view_scale_open 已就位（debugName=" + debugNameOf(extraVal) + "）");
            } else if (sBridgeLogCount < 12) {
                L.w("[HVS桥] ⚠ options 里也没有 RemoteTransition —— 退出必然退化成默认硬切。"
                        + "说明 setSystemView 没能建立过渡（多半是 isFeatureEnable=false 或 rect 为空）");
            }
            if (r instanceof Bundle) {
                applyHyperExitToHome((Bundle) r, it);
                return (Bundle) r;
            }
        } catch (Throwable t) {
            L.w("[HVS桥] 调用官方 startActivity 失败（回退原 options，应用照常打开）: " + t);
        }
        // 兜底：官方方法不可用时，自己复刻 modifyNextIntent 的那一行
        try {
            if (!it.hasExtra("view_scale_open")) {
                Object rt = opts.getParcelable("android:activity.remoteTransition");
                if (rt instanceof android.os.Parcelable) {
                    it.putExtra("view_scale_open", (android.os.Parcelable) rt);
                    L.i("[HVS桥] 兜底：已手工写入 view_scale_open extra（来自原 options）");
                } else if (sBridgeLogCount++ < 12) {
                    L.w("[HVS桥] 兜底也拿不到 RemoteTransition —— options 里确实没有，"
                            + "说明 setSystemView 没成功建立过渡");
                }
            }
        } catch (Throwable t) {
            L.w("[HVS桥] 兜底写 extra 失败: " + t);
        }
        return opts;
    }

    /**
     * ★ 把 `RemoteTransition.exitToHome` 置为 true —— "回桌面"那条路的第一步试探。
     *
     * ===== 依据（都是反汇编出来的事实）=====
     * MIUI 给 `android.window.RemoteTransition` 加了这些字段：
     * <pre>
     *   IRemoteTransition       mRemoteTransition
     *   IHyperRemoteTransition  mHyperRemoteTransition     ← 我们一直是 null
     *   boolean                 mExitToHome                ← 我们一直是 false
     *   long                    mHyperToken
     *   IPreStartupAnimation    mPreStartupAnimation
     *   IBinder                 mRecentRunner
     * </pre>
     * 而"上滑/小白条回桌面"整条路在 system_server（`miui-services.jar`）：
     * <pre>
     *   WindowOrganizerControllerImpl.interceptKeyEventToLauncher()
     *     → markSysBarExitToHomeOnIntercept() → maybeMarkSysBarExitToHome() → isSysBarExitToHomeApp()
     *   ActivityStarterImpl.setHyperTransition()             → RemoteTransition.getHyperRemoteTransition()
     *   ActivityStarterImpl.notifyHomeStartActivityFinish()   → getHyperRemoteTransition()
     *                                                          + TransitionStub.setHyperRemoteTransition(...)
     *   TransitionImpl.notifyHomeTransitionAbortIfNeeded()/abort() → getHyperRemoteTransition()
     * </pre>
     * 桌面的原生库里正是 `IRemoteTransitionImpl` + `IHyperRemoteTransitionImpl` +
     * `handleClosingRemoteTransitionInAppPage` / `takeOverFinishRemoteTransition` /
     * `finishTransitionReconcileHome` —— **桌面的"飞回图标"就是它的 hyper transition**。
     *
     * ===== 为什么先只改这一个字段 =====
     * 真正完整的做法是自己实现 `IHyperRemoteTransition`（只有 2 个方法：
     * `onTransitionAbort(int)` / `startActivityFinished(ActivityStartInfo, IHyperRemoteTransitionFinishedCallback)`），
     * 但它是 MIUI 私有接口、公开 android.jar 里没有，而设备上的 framework.jar 是 **dex 不是 class**
     * （javac 编译不了），得走"自己写 Binder.onTransact + Proxy 满足 setter 类型"那条路，
     * 还要先读 `$Stub.onTransact` 拿事务码。
     * 所以先花 5 行试试光设 `exitToHome` 有没有用 —— 如果 MIUI 那条路对 `isExitToHome`
     * 有兜底分支，这一步就够了；不够再上完整实现。
     */
    private static void applyHyperExitToHome(Bundle enriched, Intent it) {
        if (!Config.HVS_SET_EXIT_TO_HOME) {
            return;
        }
        try {
            Object rt = enriched.getParcelable("android:activity.remoteTransition");
            if (rt == null) {
                L.w("[返场/exitToHome] options 里没有 RemoteTransition，跳过");
                return;
            }
            Object before = XposedHelpers.callMethod(rt, "isExitToHome");
            XposedHelpers.callMethod(rt, "setExitToHome", Boolean.TRUE);
            Object after = XposedHelpers.callMethod(rt, "isExitToHome");

            // ★★ 关键：塞进 hyper 通道。实测只设 exitToHome 没有任何效果
            //     （用户看到的仍是"从中间直接消失"），真正被调用的只有 hyperRemoteTransition。
            //     见 HyperTransition 的类注释。
            String hyperDesc = "null";
            if (Config.HVS_USE_HYPER_TRANSITION) {
                Object proxy = HyperTransition.proxy(
                        HvsDiagnostics.class.getClassLoader());
                if (proxy != null) {
                    XposedHelpers.callMethod(rt, "setHyperRemoteTransition", proxy);
                    Object got = XposedHelpers.callMethod(rt, "getHyperRemoteTransition");
                    hyperDesc = got == null ? "❌ 设不进去" : "✔ " + got.getClass().getName();
                }
            }
            L.i("[返场/exitToHome] RemoteTransition.exitToHome: " + before + " → " + after
                    + "   hyperRemoteTransition=" + hyperDesc);
            // intent 上那个 extra 是同一个对象，一起改（serialize 前改都生效）
            Object extra = it.getParcelableExtra("view_scale_open");
            if (extra != null && extra != rt) {
                L.w("[返场/exitToHome] 注意：intent 上的 RemoteTransition 与 options 里的不是同一个对象");
            }

            // ★★ 方案 B 第一步：把过渡对象换成**我们自己的**（四个方法全部转发给上面这个原对象）。
            // 目的是先证明"系统愿意听我们的四个方法"，开/关动画必须与替换前完全一致。
            if (Config.HVS_OWN_REMOTE_TRANSITION && rt instanceof Parcelable) {
                Object ours = OwnRemoteTransition.build(
                        HvsDiagnostics.class.getClassLoader(), rt);
                if (ours instanceof Parcelable) {
                    Parcelable p = (Parcelable) ours;
                    enriched.putParcelable("android:activity.remoteTransition", p);
                    it.putExtra("view_scale_open", p);
                    L.i("[自有过渡] 已把 view_scale_open + options 的 remoteTransition"
                            + " 一起换成我们自己的对象（原对象仍被持有并转发）");
                }
            }
        } catch (Throwable t) {
            L.w("[返场/exitToHome] 设置失败: " + t);
        }
    }

    /** 打点用：过渡对象的 debugName（system_server 就是靠它认出这套动画的）。 */
    private static String debugNameOf(Object rt) {
        try {
            return String.valueOf(XposedHelpers.callMethod(rt, "getDebugName"));
        } catch (Throwable t) {
            return "<?>";
        }
    }

    /** 打点用的调用栈顶部（谁调了 stub）。 */
    private static String stackTop() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (int i = 2; i < st.length && n < 4; i++) {
                String c = st[i].getClassName();
                if (c.startsWith("com.qianxia.hyperassistant") || c.startsWith("de.robv")
                        || c.startsWith("java.lang.Thread")) {
                    continue;
                }
                sb.append(sb.length() == 0 ? "" : " <- ").append(c).append('#').append(st[i].getMethodName());
                n++;
            }
            return sb.length() == 0 ? "(未知)" : sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ bundle 补齐

    /**
     * `parseBundle(Context, Rect, Bundle, ViewInfo)` 的 before：
     * 只用**小米自己的查询接口**取值，缺什么补什么。
     */
    private static void enrichBundle(XC_MethodHook.MethodHookParam p) {
        Object[] a = p.args;
        if (a == null || a.length < 4) {
            return;
        }
        Context ctx = (a[0] instanceof Context) ? (Context) a[0] : null;
        Bundle b = (a[2] instanceof Bundle) ? (Bundle) a[2] : null;
        Object viewInfo = a[3];
        View view = null;
        try {
            Object v = XposedHelpers.callMethod(viewInfo, "getView");
            view = (v instanceof View) ? (View) v : null;
        } catch (Throwable ignored) {
        }
        if (verbose()) {
            L.i("[HVS] parseBundle 原 bundle=" + dump(b)
                    + "  view=" + (view == null ? "null" : view.getClass().getName())
                    + "  ctx=" + (ctx == null ? "null" : ctx.getClass().getName()));
        }
        if (b == null || view == null || ctx == null) {
            return;
        }

        Object self = p.thisObject;
        String viewClass = view.getClass().getName();

        // -- 视图类名：官方 getCurrentBundle / isViewSupport 都是按这个名字查表
        String actName = null;
        try {
            Object r = XposedHelpers.callMethod(self, "getActivityName", ctx);
            actName = (r instanceof String) ? (String) r : null;
        } catch (Throwable ignored) {
        }

        Object mgr = findManager(self);
        if (mgr == null) {
            if (sConfigLogCount++ < 3) {
                L.w("[HVS] 找不到 HyperViewScaleManager 实例，无法补齐（配置注入可能没生效）");
            }
            return;
        }

        // -- corner：优先用官方配置表的 view[].corner（dp -> px 由官方 dp2px 完成）
        try {
            Object c = XposedHelpers.callMethod(mgr, "getViewCorner", viewClass, ctx);
            if (c instanceof Float && ((Float) c) > 0f) {
                b.putFloat("bundle_radius", (Float) c);
                L.i("[HVS] 补 bundle_radius=" + L.f((Float) c) + "  (来自配置 corner, view=" + viewClass + ")");
            } else {
                // 配置表里没有这个视图类（例如扫码快捷方式的 TextView）→ 本地兜底。
                // 这样就不必为了加一个视图类而重启 system_server。
                float dp = localCornerDp(viewClass);
                float px = dp * density(ctx);
                if (px > 0f) {
                    b.putFloat("bundle_radius", px);
                    L.i("[HVS] 补 bundle_radius=" + L.f(px) + "  (本地兜底 " + L.f(dp)
                            + "dp × " + L.f(density(ctx)) + ", view=" + viewClass
                            + "；官方表里查不到，值=" + c + ")");
                }
            }
        } catch (Throwable t) {
            L.w("[HVS] getViewCorner 失败: " + t);
        }

        // -- 支持性：不在配置表里的视图类官方判定为"不支持"
        try {
            Object sup = XposedHelpers.callMethod(mgr, "isViewSupport", viewClass);
            Object mp = XposedHelpers.callMethod(mgr, "needMatchParent", viewClass);
            L.i("[HVS] 视图类 " + viewClass + "  isViewSupport=" + sup + "  needMatchParent=" + mp);
        } catch (Throwable ignored) {
        }

        // -- 填充底色：白边/黑边的正主
        //
        // ⚠️ 实测教训：`getBgColor(actName)` 拿到的永远是 0，因为点击时的 `ctx` 是
        //    `PABaseContextThemeWrapper`（不是 Activity），小米的
        //    `getActivityName(Context)` 里 `((Activity) ctx).getComponentName()` 取不到，
        //    返回**空字符串**；`getBgColor("")` → map 查不到 → 返回 int 默认值 0。
        //    而 `parseBundle` 把这个 0 当成 fillColorType 交给
        //    `getBackgroundColor(view, 0)`，那里只认 1 / 2，其它一律记
        //    "invalid fillColorType" 然后给个兜底色垫在截图后面
        //    → **图标背后的白边/黑边就是这么来的**。
        //    所以：查不到就用我们配置里的默认类型，绝不把 0 交出去。
        int bgType = 0;
        if (actName != null) {
            try {
                Object type = XposedHelpers.callMethod(mgr, "getBgColor", actName);
                if (type instanceof Integer) {
                    bgType = (Integer) type;
                }
            } catch (Throwable t) {
                L.w("[HVS] getBgColor 失败: " + t);
            }
        }
        if (bgType != 1 && bgType != 2) {
            bgType = Config.HVS_DEFAULT_BG_COLOR_TYPE;
            L.i("[HVS] getBgColor(startFrom='" + actName + "') 无效，改用默认 fillColorType="
                    + bgType + "（0 会让官方走 invalid 分支 → 白边/黑边）");
        }
        b.putInt("bundle_fill_bg_color", bgType);
        L.i("[HVS] 补 bundle_fill_bg_color=" + bgType + " (startFrom='" + actName + "')");

        // 顺便把官方最终选中的颜色打出来 —— 这是"白边到底修没修"的直接证据
        try {
            Object color = XposedHelpers.callMethod(self, "getBackgroundColor", view, bgType);
            if (color instanceof Integer) {
                L.i("[HVS] 官方据此选中的背景色 = #" + String.format("%08X", (Integer) color)
                        + "  (alpha=" + ((Integer) color >>> 24) + ")");
            }
        } catch (Throwable t) {
            L.w("[HVS] 预演 getBackgroundColor 失败: " + t);
        }

        if (actName != null) {
            try {
                Object cover = XposedHelpers.callMethod(mgr, "getListCover", actName);
                if (cover instanceof float[]) {
                    b.putFloatArray("bundle_list_cover", (float[]) cover);
                    L.i("[HVS] 补 bundle_list_cover=" + java.util.Arrays.toString((float[]) cover));
                }
            } catch (Throwable ignored) {
            }
        }

        if (!b.containsKey("bundle_third_party")) {
            b.putBoolean("bundle_third_party", true);
        }
        // -- 官方 setSystemView 不带这两个键，而它们都影响观感：
        //   bundle_view_visible: 动画期间要不要把源视图藏起来。
        //     官方 performClick 路径会写它；setSystemView 不写 → 默认 false。
        //     实测我们的 ViewInfo.isViewVisible() 一直是 false。
        //     源视图不藏的话，飞回去这个动作看不出来（小部件一直在原地）。
        //   bundle_list_cover: [topCover, bottomCover]，用来把起始矩形按内容比例内缩。
        //     配置里第三方应用都给了具体值（如 0.16/0.08）；我们查不到就是 null，
        //     null 会让起始矩形不被修正 —— 白框很可能就是 leash 起始矩形比图标大造成的。
        if (!b.containsKey("bundle_view_visible")) {
            b.putBoolean("bundle_view_visible", Config.HVS_VIEW_VISIBLE);
        }
        if (!b.containsKey("bundle_list_cover")) {
            b.putFloatArray("bundle_list_cover", Config.HVS_LIST_COVER);
            L.i("[HVS] 补 bundle_list_cover=" + java.util.Arrays.toString(Config.HVS_LIST_COVER)
                    + "（官方配置查不到 → 用显式值，避免起始矩形不内缩导致白框）");
        }
        L.i("[HVS] bundle_view_visible=" + b.getBoolean("bundle_view_visible", false)
                + "  (true = 动画期间隐藏源视图，退出时才看得出来飞回去)");
        try {
            Object dark = XposedHelpers.callMethod(self, "isDarkMode", ctx);
            if (dark instanceof Boolean && !b.containsKey("bundle_dark_mode")) {
                b.putBoolean("bundle_dark_mode", (Boolean) dark);
            }
        } catch (Throwable ignored) {
        }

        sConfigLogCount++;
        if (verbose()) {
            L.i("[HVS] parseBundle 补后 bundle=" + dump(b));
        }
    }

    /** 在 RemoteManager 实例里找到那个 `HyperViewScaleManager` 字段（配置表就在它身上）。 */
    private static volatile Field sMgrField;
    private static volatile boolean sMgrFieldResolved;

    private static Object findManager(Object remoteManager) {
        if (!sMgrFieldResolved) {
            synchronized (HvsDiagnostics.class) {
                if (!sMgrFieldResolved) {
                    sMgrFieldResolved = true;
                    Class<?> c = remoteManager.getClass();
                    while (c != null && sMgrField == null) {
                        for (Field f : c.getDeclaredFields()) {
                            if (f.getType().getName().equals(MGR)) {
                                try {
                                    f.setAccessible(true);
                                    sMgrField = f;
                                } catch (Throwable ignored) {
                                }
                                break;
                            }
                        }
                        c = c.getSuperclass();
                    }
                    L.i("[HVS] HyperViewScaleManager 字段 = "
                            + (sMgrField == null ? "❌ 没找到" : sMgrField.getName()));
                }
            }
        }
        Field f = sMgrField;
        if (f == null) {
            return null;
        }
        try {
            return f.get(remoteManager);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 动画打点

    /**
     * 直接反射调用，**不用** `XposedHelpers.callMethod`。
     *
     * 教训：`XposedHelpers.callMethod` 会检查
     * `method.getDeclaringClass().isInstance(obj)`，而 hyperviewscale 的类是在
     * 一个**子 ClassLoader** 里加载的 —— 拿另一个 Class 对象查出来的方法一进去就
     * 抛 `IllegalArgumentException: Expected receiver of type ...`。
     * 之前这个异常把 `describeStaticViewInfo` 整个打断，顺带把后面"强制反向"的
     * 判断也一起跳过了（异常在 onBefore 里，后面的代码根本没执行），
     * 表现就是"日志里全是异常 + 强制反向从来没生效"。
     */
    static Object refl(Object obj, String method) {
        if (obj == null) {
            return null;
        }
        try {
            Method m = obj.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Throwable t) {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    Method m = c.getDeclaredMethod(method);
                    m.setAccessible(true);
                    return m.invoke(obj);
                } catch (NoSuchMethodException e) {
                    c = c.getSuperclass();
                } catch (Throwable t2) {
                    return null;
                }
            }
            return null;
        }
    }

    /**
     * ★ 重画遮罩层 buffer：清透明 + 位图等比铺满"视图矩形对应的那块区域"。
     *
     * 为什么必须重画：`drawViewOnCanvas` 把位图**按原尺寸**画在 (0,0)，而遮罩层
     * 会被 `setScale(realScale)` 缩小（realScale = viewRect.w / screenW），
     * 于是 buffer 里对应视图矩形的那块可见区 = `(0,0)-(bufW, viewRect.h * bufW / viewRect.w)`，
     * 位图只占它的左上角一小块，其余全是铺色 → 白框 / 黑屏。
     *
     * 这里把那块可见区算出来，位图等比缩放居中铺进去。正常情况位图宽高比和可见区
     * 完全相等（两者都由 viewRect 推导），所以就是精确铺满；万一不等也是居中留边，
     * 不会拉变形。
     */
    private static volatile Constructor<?> sSurfaceCtor;
    private static int sMaskRedrawLog;

    private static void redrawMaskBuffer(Object sc, Rect buf, Object viewInfo) {
        if (!(sc instanceof SurfaceControl) || buf == null || viewInfo == null) {
            return;
        }
        Object bmpO = refl(viewInfo, "getBitmap");
        if (!(bmpO instanceof Bitmap)) {
            return;
        }
        Bitmap bmp = (Bitmap) bmpO;
        if (bmp.isRecycled() || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) {
            return;
        }
        int bufW = buf.width();
        int bufH = buf.height();
        if (bufW <= 0 || bufH <= 0) {
            return;
        }
        Object vrO = refl(viewInfo, "getViewRect");
        float clipH = bufH;
        if (vrO instanceof Rect && ((Rect) vrO).width() > 0) {
            clipH = (float) ((Rect) vrO).height() * bufW / ((Rect) vrO).width();
        }
        if (clipH <= 0f || clipH > bufH) {
            clipH = bufH;
        }
        float sx = bufW / (float) bmp.getWidth();
        float sy = clipH / (float) bmp.getHeight();
        float s = Math.min(sx, sy);
        float w = bmp.getWidth() * s;
        float h = bmp.getHeight() * s;
        RectF dst = new RectF((bufW - w) / 2f, (clipH - h) / 2f,
                (bufW + w) / 2f, (clipH + h) / 2f);
        Surface surface = newSurfaceFor(sc);
        if (surface == null) {
            return;
        }
        try {
            Canvas c = surface.lockHardwareCanvas();
            try {
                c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
                Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
                c.drawBitmap(bmp, null, dst, paint);
            } finally {
                surface.unlockCanvasAndPost(c);
            }
            if (sMaskRedrawLog++ < 40) {
                L.i("[HVS遮罩] 重画: buffer=" + bufW + "x" + bufH
                        + " viewRect=" + vrO + " 位图=" + bmp.getWidth() + "x" + bmp.getHeight()
                        + " → 铺满 " + dst + "（原来只画在 (0,0)，其余是铺色 = 白框/黑屏）");
            }
        } finally {
            try {
                surface.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** `Surface(SurfaceControl)` 是 @hide，公开 SDK 里没有，只能反射造。 */
    private static Surface newSurfaceFor(Object sc) {
        try {
            Constructor<?> ctor = sSurfaceCtor;
            if (ctor == null) {
                ctor = Surface.class.getConstructor(SurfaceControl.class);
                ctor.setAccessible(true);
                sSurfaceCtor = ctor;
            }
            return (Surface) ctor.newInstance(sc);
        } catch (Throwable t) {
            L.w("[HVS遮罩] 造 Surface 失败: " + t);
            sSurfaceCtor = null;
            return null;
        }
    }

    /**
     * 从位图**边缘**取一个代表色 —— 用来给遮罩层铺底。
     *
     * 目的：`drawViewOnCanvas` 会先把整块 buffer 铺满一个颜色再画位图，
     * 位图（圆角方图）盖不住的地方就露出这个颜色。官方取"主色"（对负一屏图标
     * 算出来是 #FFF8F8F8 = 白色 → 白框）；改成透明又会露出黑底（黑屏）。
     * 取**边缘那一圈的颜色**，铺上去以后圆角外和多出来的部分就跟图标本身接上了。
     *
     * 沿四条边各采 24 个点（往里缩 1px，避开抗锯齿），取出现次数最多的不透明色。
     */
    private static int edgeColorOf(android.graphics.Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) {
            return 0;
        }
        try {
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            if (w < 4 || h < 4) {
                return 0;
            }
            java.util.HashMap<Integer, Integer> hist = new java.util.HashMap<>();
            int best = 0;
            int bestCount = 0;
            int n = 24;
            for (int i = 0; i < n; i++) {
                int x = 1 + (w - 3) * i / (n - 1);
                int y = 1 + (h - 3) * i / (n - 1);
                int[] samples = {
                        bmp.getPixel(x, 1),           // 上边
                        bmp.getPixel(x, h - 2),       // 下边
                        bmp.getPixel(1, y),           // 左边
                        bmp.getPixel(w - 2, y),       // 右边
                };
                for (int c : samples) {
                    if ((c >>> 24) < 0xF0) {      // 完全/半透明的不算
                        continue;
                    }
                    Integer k = c;
                    Integer cnt = hist.get(k);
                    int v = cnt == null ? 1 : cnt + 1;
                    hist.put(k, v);
                    if (v > bestCount) {
                        bestCount = v;
                        best = c;
                    }
                }
            }
            return best;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int sFakeLog;

    /**
     * ★ 测试脚手架：源视图在屏幕外时，**伪造一个屏幕内的起点矩形**。
     *
     * 为什么需要：负一屏被 launcher 停在旁边时，它的内容整体在屏幕外
     * （实测 `screen x = -1125…-219`），于是 `ViewScaleAnimInfo.isValid = false`，
     * MIUI 的 `startAnimation` 直接走 `alphaAnimByException(...)` **纯 alpha 降级** ——
     * 不建遮罩、不做缩放动画。结果就是我全自动的测试**永远测不到真正的动画**
     * （实测 8 次触发全是 `isValid=false`，`[HVS遮罩] 重画`、`强制反向` 一次都没触发）。
     *
     * 现在：`isValid` 为 false 时把 `ViewInfo.viewRect` 改成一个屏幕内的合成矩形，
     * MIUI 就会走**真实**那条路（遮罩、leash、官方曲线全都跑起来），
     * 于是白框/黑屏、动画形状、反向这些都能**自动验证**，不必等人工滑到负一屏。
     *
     * 代价：动画起点是编的（不是真图标位置），所以只能验「机制与观感缺陷」，
     * 不能验「位置对不对」。默认关闭（`Config.HVS_FAKE_ON_SCREEN_SOURCE`）。
     */
    private static void fakeOnScreenSource(XC_MethodHook.MethodHookParam p) {
        if (!Config.HVS_FAKE_ON_SCREEN_SOURCE || p.args == null || p.args.length < 1) {
            return;
        }
        try {
            Object vi = p.args[0];
            Object r = refl(vi, "getViewRect");
            if (!(r instanceof Rect)) {
                return;
            }
            Rect rect = (Rect) r;
            boolean off = rect.left < 0 || rect.top < 0
                    || rect.right > 1200 || rect.bottom > 2608;
            if (!off) {
                return;
            }
            Rect fake = new Rect(rect);
            // 最小平移把它拉进屏幕：**保留原始尺寸与比例**（128x128 图标、486x486 小部件
            // 都不变形），只挪位置。比硬编码一个 629,1320 更接近真实。
            int dx = 0;
            int dy = 0;
            if (fake.right > 1200) {
                dx = 1200 - fake.right;
            }
            if (fake.left + dx < 0) {
                dx = -fake.left;
            }
            if (fake.bottom > 2608) {
                dy = 2608 - fake.bottom;
            }
            if (fake.top + dy < 0) {
                dy = -fake.top;
            }
            fake.offset(dx, dy);
            Method m = vi.getClass().getMethod("setViewRect", Rect.class, boolean.class);
            m.setAccessible(true);
            m.invoke(vi, fake, Boolean.FALSE);
            if (sFakeLog++ < 40) {
                L.w("[HVS几何] ⚠ 测试脚手架：源视图在屏幕外 " + rect
                        + " → 伪造成 " + fake + "（让 MIUI 走真实动画路径）");
            }
        } catch (Throwable t) {
            L.w("[HVS几何] fakeOnScreenSource 出错: " + t);
        }
    }

    /** TransitionInfo.Change 的 mode 名字（值来自 framework 的 modeToString）。 */    private static String modeName(Object mode) {
        if (!(mode instanceof Number)) {
            return String.valueOf(mode);
        }
        switch (((Number) mode).intValue()) {
            case 0: return "NONE";
            case 1: return "OPENING";
            case 2: return "CLOSING";
            case 3: return "TO_FRONT";
            case 4: return "TO_BACK";
            case 6: return "CHANGING";
            default: return String.valueOf(mode);
        }
    }

    /** 读一个 boolean 字段，读不到返回 "?"。 */
    private static String boolField(Object obj, String name) {
        if (obj == null) {
            return "?";
        }
        try {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return String.valueOf(f.getBoolean(obj));
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    /**
     * ★ 判断这次 merge 是不是"上滑回桌面"，是的话把 debugId 记下来，
     * 交给 `reverseAnimationWhenMerge` 那一步去强制反向。
     *
     * 判据（全部来自实测，不靠猜）：
     *   · `type != 1`（1 = TRANSIT_OPEN，那是开启动画自己的合并，绝不能反向）
     *   · 至少有一个 change 的 mode == 4（TO_BACK）—— 有窗口被送回后台
     * 满足就记 id；不满足只记一行日志，说明"没打算反向这次合并"。
     */
    private static void armReverseIfToBack(XC_MethodHook.MethodHookParam p) {
        Object info = null;
        if (p.args != null) {
            for (Object x : p.args) {
                if (x != null && x.getClass().getName().endsWith("TransitionInfo")) {
                    info = x;
                    break;
                }
            }
        }
        if (info == null) {
            return;
        }
        Object t = refl(info, "getType");
        int type = t instanceof Number ? ((Number) t).intValue() : -1;
        Object ido = refl(info, "getDebugId");
        long id = ido instanceof Number ? ((Number) ido).longValue() : -1L;
        boolean hasToBack = false;
        boolean hasClosing = false;
        boolean hasOpening = false;
        StringBuilder who = new StringBuilder();
        Object changes = refl(info, "getChanges");
        if (changes instanceof java.util.List) {
            for (Object ch : (java.util.List<?>) changes) {
                Object m = refl(ch, "getMode");
                if (!(m instanceof Number)) {
                    continue;
                }
                int mode = ((Number) m).intValue();
                if (mode == 4) {
                    hasToBack = true;
                }
                if (mode == 1) {
                    hasOpening = true;
                }
                if (mode == 2) {
                    hasClosing = true;
                }
                who.append(who.length() == 0 ? "" : " ").append(modeName(mode));
            }
        }
        // 判据（★ 第三轮校准：这次是**负一屏真的在屏幕上**（`isValid=true`）采集到的数据）。
        //
        //   只有 MIUI 自定义的 `type=1021` 才是"上滑回桌面"这条手势路径。
        //
        // 旧判据是"有 TO_BACK 且没有 OPENING"，在**停旁边**的降级路径上看着对，
        // 一到真实路径就崩了 —— 实测点**小组件**打开应用时，merge 给的是
        //     type=1(TRANSIT_OPEN) [TO_FRONT, TO_BACK, TO_BACK]
        // 有 TO_BACK、没有 OPENING → 被误判成"上滑回桌面"→ 强制反向 →
        // 开启动画刚跑 34ms 就被 MIUI 的反向动画顶掉，应用被缩回小组件那个方框里，
        // 过一会儿才恢复全屏。这正是用户报的「播放完动画仍然在图标内显示内容，
        // 过会恢复」。
        //
        // ★★ 2026-09-26 18:5x 受控实验（我点完组件**完全不上滑**、等 5 秒，连做 3 次）：
        //    每次都会收到 **type=1 [TO_FRONT CLOSING]** 的合并 —— 也就是"打开小组件"
        //    这条正常链路里**本来就带 CLOSING**（负一屏那个窗口在关）。
        //    而上一轮我为了兼容 `[CLOSING TO_FRONT]`，把判据放宽成
        //        `!hasOpening && ((type == 1021) || hasClosing)`
        //    → **每一次正常打开都被当成"上滑回桌面"→ 强制反向 → 应用刚打开就被缩回
        //    组件方框里，然后再弹回来**。这正是用户报的「内容留在图标/组件里，过会恢复」
        //    「动画不稳定」「组件不可打断」，是**我自己引入的回归**（见用户 18:50 截图）。
        //
        // 判据回到**只认 MIUI 自定义的 type=1021**：
        //   真上滑（3/3 实测）→ `type=1021 [TO_FRONT TO_BACK TO_FRONT]`
        //   正常打开         → `type=1 [TO_FRONT CLOSING]` / `[TO_FRONT TO_BACK TO_BACK]` → 绝不反向
        // 教训：**CLOSING 不能当"回桌面"的证据** —— 负一屏窗口关闭同样算 CLOSING。
        // （要更细，得看"是应用那个 container 在 CLOSING，还是负一屏在 CLOSING"，留待下一轮。）
        boolean toBack = (type == 1021) && !hasOpening;
        // ★ 打断守卫：`finishCurrentAnimation()` 刚发生 → 这是"点了新图标把上一个
        // 过渡收尾"产生的合并，不是用户上滑。
        //
        // 实测（15:32:53）打断场景的真实时序：
        //     53.815 finishCurrentAnimation()        ← MIUI 收尾正在跑的动画
        //     53.851 mergeAnimation type=1 [TO_FRONT,TO_BACK]
        // 而真的上滑是**反过来的**：合并先来、finish 随后。
        // 形状（type=1 [TO_FRONT,TO_BACK]）两者完全一样，只有时序能分开。
        // 曾经因为误判，在这种打断里也强推了一次反向 + 播了一次浮层。
        if (toBack && android.os.SystemClock.uptimeMillis() - sFinishCurrentAt
                < Config.HVS_INTERRUPT_GUARD_MS) {
            if (sArmLogCount++ < 400) {
                L.i("[HVS反向] 合并判定 type=" + type + " id=" + id + " [" + who + "]  → "
                        + "✗ 刚 finishCurrentAnimation（打断场景），不当作上滑回桌面");
            }
            return;
        }
        if (toBack) {
            sReverseMergeId = id;
            sReverseMergeAt = android.os.SystemClock.uptimeMillis();
            // ★ 第 112 轮：**退出段开始**（我们即将强制反向，MIUI 会播"缩回"）。
            //   在这里打点，之后的 leash 写入会被我们按自己的曲线改写（只在退出段，打开段不动）。
            initExitTakeover();
            // ★ 分工：缩回段交回 MIUI（SF 接管缩回会让负一屏回不来 → 四周常黑）
            if (Config.HVS_SF_EXIT) {
                bringOverlayBack();
                startSfAnimWhole(false);
            } else if (Config.HVS_USE_SF_ANIM) {
                startSfAnimForExit();
            }
            // ★ 模糊归零：上滑(type=1021)后 700ms 把 blur 层压 0
            //   （治"一层模糊覆盖，要手动返回才消失"：实测 BLUR alpha 停在 0.2 无人清零）
            if (Config.HVS_BLUR_RESET) {
                sBlurGuardUntil = android.os.SystemClock.uptimeMillis() + 3000L;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        new Runnable() {
                            @Override
                            public void run() {
                                zeroBlurLayer("上滑 700ms 后");
                            }
                        }, 700L);
            }
        }
        if (sArmLogCount++ < 400) {
            L.i("[HVS反向] 合并判定 type=" + type + " id=" + id + " [" + who + "]  → "
                    + (toBack ? "★ 上滑回桌面(type=1021)，已记下待强制反向"
                              : (type == 1 ? "✗ 这是开启动画自己的合并(type=1)，不反向"
                                           : "不是回桌面（不反向）")));
        }
        // 5) ★ ① 延迟量化：**点击 → 第一帧真正改了画面**（都在设备的钟上，不用对齐录屏）。
        //
        // 点下去之后眼睛看到的第一处变化，就是 leash 被改写的那一帧；MIUI 通过
        // `SurfaceControl.Transaction.apply()` 把它提交。所以拿
        //   · 起点 = 记源时刻（= 用户那一按，`WidgetOpenAnim.armedAtMs()`）
        //   · 终点 = 本次过渡开始后第一条 apply
        // 两者都是 `uptimeMillis`，于是**完全不需要**再去对录屏的起录偏差
        // （之前用录屏算出的 230ms 就是被那 100~300ms 偏差污染的，不可信）。
        Class<?> txnCls = null;
        try {
            // 框架类，直接用 Class.forName（不需要助手进程的 ClassLoader）
            txnCls = Class.forName("android.view.SurfaceControl$Transaction");
        } catch (Throwable e) {
            L.w("[延迟] 找不到 Transaction 类: " + e);
        }
        if (txnCls != null && !sLatHookTried) {
            sLatHookTried = true;      // ★ 只装一次（这块代码目前在 armReverseIfToBack 里，
                                       //   下一轮要挪进 install(cl)；加守卫避免每次合并都重复装钩子）
            HookKit.hookAll(txnCls, "apply", new HookKit.Cb("Transaction.apply [延迟]") {
                @Override
                protected void onBefore(MethodHookParam p) {
                    long tap = WidgetOpenAnim.lastTapAtMs();
                    if (tap <= 0 || sLatLogged) {
                        return;
                    }
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now < tap || now - tap > 4000L) {
                        return;             // 与本次点击无关的 apply
                    }
                    sLatLogged = true;
                    L.i("[延迟] 点击 → 第一帧画面变更 = " + (now - tap)
                            + "ms（设备自己的钟，不含录屏起录偏差）");
                }
            });
        } else {
            L.w("[延迟] 找不到 SurfaceControl$Transaction");
        }
    }

    private static volatile boolean sLatLogged;
    private static volatile boolean sLatHookTried;

    /**
     * ① 延迟量化：**点击 → 第一帧真正改了画面**，两个时间点都在设备的钟上
     * （`uptimeMillis`），所以不需要去对齐录屏的起录偏差（那个偏差 100~300ms，
     * 之前算出的 230ms 就是被它污染的）。
     *
     * 起点 = 记源时刻（用户那一按）；终点 = 本次过渡开始后第一条
     * `SurfaceControl.Transaction.apply()`（leash 被改写、画面真正变的那一帧）。
     */
    private static void installLatencyProbe() {
        if (sLatHookTried) {
            return;
        }
        sLatHookTried = true;
        Class<?> txnCls = null;
        try {
            txnCls = Class.forName("android.view.SurfaceControl$Transaction");
        } catch (Throwable e) {
            L.w("[延迟] 找不到 Transaction 类: " + e);
        }
        if (txnCls == null) {
            return;
        }
        HookKit.hookAll(txnCls, "apply", new HookKit.Cb("Transaction.apply [延迟]") {
            @Override
            protected void onBefore(MethodHookParam p) {
                long tap = WidgetOpenAnim.lastTapAtMs();
                if (tap <= 0 || sLatLogged) {
                    return;
                }
                long now = android.os.SystemClock.uptimeMillis();
                if (now < tap || now - tap > 4000L) {
                    return;                 // 与本次点击无关的 apply
                }
                sLatLogged = true;
                L.i("[延迟] 点击 → 第一帧画面变更 = " + (now - tap)
                        + "ms（设备自己的钟，不含录屏起录偏差）");
            }
        });
        L.i("[延迟] 探测已安装（SurfaceControl$Transaction.apply）");
        installOverlayProbes();

        // ★ 第 16 轮：**只读探针** —— 验证澎湃4 框架的"原生 SF 动画 API"在我们这个进程里能不能拿到。
        //   只查：版本号、5 个方法是否可用、两个回调是否接口。**不碰任何画面**。
        try {
            int ver = -1;
            String how = "none";
            try {
                Object o = android.view.SurfaceControl.class.getMethod("getsfAnimVersion2")
                        .invoke(null);
                ver = ((Integer) o).intValue();
                how = "v2";
            } catch (Throwable t1) {
                try {
                    Object o2 = android.view.SurfaceControl.class.getMethod("getsfAnimVersion")
                            .invoke(null);
                    ver = ((Integer) o2).intValue();
                    how = "v1";
                } catch (Throwable t2) {
                    how = "both-failed";
                }
            }
            Class<?> txn = Class.forName("android.view.SurfaceControl$Transaction");
            Class<?> cb = Class.forName("android.view.OnSFAnimationCallback");
            Class<?> cbHome = Class.forName("android.view.OnSFAnimationHomeCallback");
            String r1;
            String r2;
            String r3;
            String r4;
            String r5;
            try {
                txn.getMethod("startSFAnimation", android.view.SurfaceControl.class,
                        float[].class, int.class, cb, android.os.Handler.class, int[].class,
                        int.class);
                r1 = "OK";
            } catch (Throwable t) {
                r1 = "FAIL";
            }
            try {
                txn.getMethod("startSFAnimationFO", android.view.SurfaceControl.class,
                        android.view.SurfaceControl.class, int.class, cb,
                        android.os.Handler.class, int[].class, int.class);
                r2 = "OK";
            } catch (Throwable t) {
                r2 = "FAIL";
            }
            try {
                txn.getMethod("adjustSFAnimation", android.view.SurfaceControl.class,
                        float[].class, int.class, int.class);
                r3 = "OK";
            } catch (Throwable t) {
                r3 = "FAIL";
            }
            try {
                txn.getMethod("stopSFAnimation", android.view.SurfaceControl.class, int.class);
                r4 = "OK";
            } catch (Throwable t) {
                r4 = "FAIL";
            }
            try {
                txn.getMethod("getSFAnimationParams", android.view.SurfaceControl.class,
                        int[].class);
                r5 = "OK";
            } catch (Throwable t) {
                r5 = "FAIL";
            }
            java.lang.reflect.Method[] cbm = cb.getMethods();
            StringBuilder ms = new StringBuilder();
            for (java.lang.reflect.Method m : cbm) {
                if (m.getDeclaringClass() != Object.class) {
                    ms.append(m.getName()).append('/').append(m.getParameterCount()).append(' ');
                }
            }
            L.i("[SF动画探针] pid=" + android.os.Process.myPid() + " 版本=" + ver + "(" + how + ")"
                    + " 回调是接口=" + cb.isInterface() + " home回调是接口=" + cbHome.isInterface()
                    + " | start=" + r1 + " startFO=" + r2 + " adjust=" + r3 + " stop=" + r4
                    + " getParams=" + r5 + " | 回调方法=" + ms);
        } catch (Throwable t) {
            L.w("[SF动画探针] 失败: " + t);
        }

        // ★ B2（第 101 轮）：**只读** hook `AnimationCallback.animationProgress`，
        //   把 MIUI 传给它的"进度值 + 时刻"打出来，用来判断它自己是不是断续推进的。
        //   背景：B1 已证明"跟 MIUI 抢 leash 写入"无效；但 animationProgress 是个具体类的普通方法
        //   → 可直接 hook。若确认它推进断续，下一步就把它的进度值换成按显示时钟算的平滑值（真正的 B）。
        try {
            Class<?> cbCls = Class.forName("android.app.hyperviewscale.AnimationCallback");
            HookKit.hookAll(cbCls, "animationProgress", new HookKit.Cb("B2.animationProgress") {
                @Override
                protected void onBefore(MethodHookParam p) {
                    try {
                        if (sB2ProgLog++ < 6000) {
                            long now = android.os.SystemClock.uptimeMillis();
                            Object a = (p.args != null && p.args.length > 0) ? p.args[0] : null;
                            L.i("[B2进度] t=" + now + " v=" + a);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            L.i("[B2进度] hook 已安装");
        } catch (Throwable t) {
            L.w("[B2进度] 安装失败: " + t);
        }


        // ★★ 第 36 轮诊断：MIUI 在**我们那条 leash** 上到底写了哪些事务？
        //
        // 动机：第 35 轮我猜 `setPosition(0,0)` 是终态，实测把窗口从 36px 推到 116px（更糟）——
        // 说明终态不是我猜的那样。所以改成**观察**：把 MIUI 在一次**正常播完**的动画里
        // 对 mLeash 的写入（方法名 + 参数）全打出来，之后再"重放"这组值，而不是我编一个。
        try {
            Class<?> txnCls2 = Class.forName("android.view.SurfaceControl$Transaction");
            for (final String mn : new String[]{"setPosition", "setMatrix", "setCornerRadius",
                    "setAlpha", "setWindowCrop", "setCrop", "setScale", "setLayer"}) {
                HookKit.hookAll(txnCls2, mn, new HookKit.Cb("leash写入." + mn) {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        try {
                            Object tgt = (p.args != null && p.args.length > 0) ? p.args[0] : null;
                            if (tgt == null) {
                                return;
                            }
                            // ★ 第 73 轮：把探针从"只记 leash"扩到**四个层**。
                            //   原因（实测）：6 段"近黑"里 4 段在变黑前 0.35s 内**没有任何过渡事件**，
                            //   说明黑是动画中途糊上来的；而我们只记了 mLeash 上的写入，
                            //   mask(view_scale_mask)/blur(view_scale_blur) 上写了什么**一条都没有** ——
                            //   没有数据就没法判断"黑是谁画的"。这里只加记录，不改任何行为。
                            String which = (tgt == sLiveLeash) ? "leash"
                                    : (tgt == sLiveMask) ? "mask"
                                    : (tgt == sLiveBlur) ? "blur"
                                    : (tgt == sLiveStart) ? "start" : null;
                            // ★★ 模糊归零（按【层名字】匹配，不再依赖句柄）：
                            //   实测每笔过渡都会新建 view_scale_blur/view_scale_mask 实例，
                            //   只压我们记住的那一个是无效的（这就是"几乎没有变"的原因）。
                            if (Config.HVS_BLUR_RESET && "setAlpha".equals(mn)
                                    && String.valueOf(tgt).contains("view_scale_blur")
                                    && (android.os.SystemClock.uptimeMillis() < sBlurGuardUntil
                                        || android.os.SystemClock.uptimeMillis()
                                           - sLastLeashWriteAt > 1200L)
                                    && p.args != null && p.args.length >= 2
                                    && p.args[1] instanceof Float
                                    && ((Float) p.args[1]).floatValue() > 0.001f) {
                                p.args[1] = Float.valueOf(0f);
                                dumpOverlayState("发现静止中的 blur 写入");
                                new android.os.Handler(android.os.Looper.getMainLooper())
                                        .post(new Runnable() {
                                            @Override
                                            public void run() {
                                                clearBlurStandard("发现静止中的 blur 写入");
                                            }
                                        });
                                if (sBackLog++ < 120) {
                                    L.i("[模糊归零] 按名字拦截 " + tgt + " → 0");
                                }
                            }
                            if ("leash".equals(which)) {
                                sLastLeashWriteAt = android.os.SystemClock.uptimeMillis();
                            }
                            if (which == null) {
                                // ★ 记录"其它层"的 setAlpha（找出那层停在 0.2 的模糊到底是谁）
                                if ("setAlpha".equals(mn) && p.args != null && p.args.length >= 2
                                        && p.args[1] instanceof Float && sOtherLog++ < 400) {
                                    L.i("[其它层] setAlpha " + p.args[1] + " @" + tgt);
                                }
                                return;
                            }
                            // ★ 模糊归零（加强版）：上滑后 3 秒内，任何非 0 的 blur 写入都强制改成 0
                            if (Config.HVS_BLUR_RESET && "blur".equals(which)
                                    && "setAlpha".equals(mn)
                                    && android.os.SystemClock.uptimeMillis() < sBlurGuardUntil
                                    && p.args != null && p.args.length >= 2
                                    && p.args[1] instanceof Float
                                    && ((Float) p.args[1]).floatValue() > 0.001f) {
                                p.args[1] = Float.valueOf(0f);
                                if (sBackLog++ < 80) {
                                    L.i("[模糊归零] 拦截 blur 写入 → 0");
                                }
                            }
                            // ★ B：整条过渡交给 SF 引擎 —— 打开段：leash 一出现就起 SF 动画
                            if (Config.HVS_SF_WHOLE && "leash".equals(which)
                                    && sSfWholeGen != sOpenGen && sLiveLeash != null) {
                                sSfWholeGen = sOpenGen;
                                startSfAnimWhole(true);
                            }
                            if (sSfAnimActive && "leash".equals(which)
                                    && ("setPosition".equals(mn) || "setScale".equals(mn)
                                        || "setCrop".equals(mn)
                                        || "setCornerRadius".equals(mn)
                                        || "setAlpha".equals(mn))) {
                                p.setResult(p.thisObject);
                                return;
                            }
                            // SF 模式下 mask/blur 一律不许现形（桌面动画不用遮罩）
                            if (sLeashWriteLog++ > 20000) {
                                return;
                            }
                            // ★ 第 112 轮：**退出段由我们驱动**（打开段不动）：
                            //   280ms、smoothstep、圆角 174→源圆角(36)、位置→源矩形、缩放→realScale。
                            if (Config.HVS_OWN_EXIT && !Config.HVS_USE_SF_ANIM && "leash".equals(which) && sExitActive) {
                                rewriteExit(p.args, mn);
                            }
                            if (Config.HVS_B_CLAMP && "leash".equals(which)) {
                                bClamp(p.args, mn);
                            }
                            if (!sSfQueryTried && "leash".equals(which) && sLiveLeash != null) {
                                sSfQueryTried = true;
                                try {
                                    Class<?> tq =
                                            Class.forName("android.view.SurfaceControl$Transaction");
                                    java.lang.reflect.Constructor<?> cq = tq.getDeclaredConstructor();
                                    cq.setAccessible(true);
                                    Object t2 = cq.newInstance();
                                    java.lang.reflect.Method mq = tq.getMethod("getSFAnimationParams",
                                            android.view.SurfaceControl.class, int[].class);
                                    mq.invoke(t2, sLiveLeash, new int[]{-1, -1, 0, 2});
                                    L.i("[SF查询3] getSFAnimationParams 成功（未 apply）leash有效="
                                            + sLiveLeash.isValid());
                                } catch (Throwable e) {
                                    L.w("[SF查询3] 失败: " + e);
                                }
                            }
                            L.i("[层写入:" + which + "] " + mn + " " + dumpArgs(p.args));
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
            L.i("[leash写入] 探测已安装（只记 mLeash 上的写入）");
        } catch (Throwable t) {
            L.w("[leash写入] 安装失败: " + t);
        }
    }

    private static int sMaskHoldLog;

    private static volatile android.view.SurfaceControl sLeashSc;
    private static volatile Object sLeashInfo;
    private static volatile long sLeashAt;
    private static int sFinishLeashLog;
    /** 只把 **mask** 层的 alpha 压到 0（不动 blur）—— 不让遮罩层在动画期间现形。 */
    private static void forceMaskTransparent(long delay) {
        try {
            android.view.SurfaceControl sc = sLiveMask;
            if (sc == null) {
                return;
            }
            Class<?> t = Class.forName("android.view.SurfaceControl$Transaction");
            java.lang.reflect.Constructor<?> c = t.getDeclaredConstructor();
            c.setAccessible(true);
            Object tx = c.newInstance();
            t.getMethod("setAlpha", android.view.SurfaceControl.class, float.class)
                    .invoke(tx, sc, 0.0f);
            t.getMethod("apply").invoke(tx);
            if (sMaskZeroLog++ < 40) {
                L.i("[压遮罩] mask alpha=0  delay=" + delay + "ms");
            }
        } catch (Throwable e) {
            if (sMaskZeroLog++ < 40) {
                L.w("[压遮罩] 失败 delay=" + delay + ": " + e);
            }
        }
    }

    private static int sMaskZeroLog;

    private static int sLeashWriteLog;
    private static int sBlurOffLog;

    // ===== 第 18 轮：原生 SF 动画（澎湃桌面同款引擎）=====
    private static float eFromW = 1200f;
    private static float eFromH = 2608f;
    private static int sSfLog;

    // ===== 任务第 1 步：MIUI 负一屏状态机探针（只打印，不改行为）=====
    private static int sProbeLog;

    /** 把 blur 层压到 0（治"一层模糊覆盖，要手动返回才消失"：打断后 blur 停在 0.2）。 */
    private static void zeroBlurLayer(String why) {
        try {
            if (sLiveBlur == null) {
                L.w("[模糊归零] 没有 blur 层引用（" + why + "）");
                return;
            }
            Class<?> tq = Class.forName("android.view.SurfaceControl$Transaction");
            java.lang.reflect.Constructor<?> c = tq.getDeclaredConstructor();
            c.setAccessible(true);
            Object t = c.newInstance();
            tq.getMethod("setAlpha", android.view.SurfaceControl.class, float.class)
                    .invoke(t, sLiveBlur, 0.0f);
            tq.getMethod("apply").invoke(t);
            L.i("[模糊归零] " + why + " → blur setAlpha 0");
        } catch (Throwable e) {
            L.w("[模糊归零] 失败(" + why + "): " + e);
        }
    }


    /** 找到 OpenState 实例：扫基类 h 的静态字段，挑 a() 返回 1.0f 的那个（对混淆改名免疫）。 */
    private static Object findOpenState(Class<?> stateManagerCls) {
        try {
            ClassLoader cl = stateManagerCls.getClassLoader();
            Class<?> hCls = cl.loadClass("com.miui.launcher.overlay.server.pane.h");
            for (java.lang.reflect.Field f : hCls.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (!hCls.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v == null) {
                        continue;
                    }
                    Object pv = hCls.getMethod("a").invoke(v);
                    if (pv instanceof Float && Math.abs(((Float) pv).floatValue() - 1.0f) < 1e-3f) {
                        return v;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }


    private static void hookProbe(ClassLoader cl, String cls, String[] methods) {
        try {
            Class<?> c = cl.loadClass(cls);
            for (final String m : methods) {
                final boolean isGoToState = "b".equals(m) && cls.endsWith("SlidingPaneStateManager");
                HookKit.hookAll(c, m, new HookKit.Cb("probe") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        try {
                            if (isGoToState && Config.HVS_KEEP_ASSIST_OPEN && p.args != null
                                    && p.args.length >= 1 && p.args[0] != null) {
                                try {
                                    Object open = findOpenState(c);
                                    if (open != null && p.args[0] != open) {
                                        L.i("[拦状态] 目标状态 "
                                                + p.args[0].getClass().getSimpleName()
                                                + " → 改成 OpenState("
                                                + open.getClass().getSimpleName() + ")");
                                        p.args[0] = open;
                                    }
                                } catch (Throwable t2) {
                                    if (sProbeLog++ < 3000) {
                                        L.w("[拦状态] 失败: " + t2);
                                    }
                                }
                            }
                            if (cls.contains("OverlayWindow") || cls.endsWith("SlidingPaneWindow")) {
                                StringBuilder stk2 = new StringBuilder();
                                try {
                                    int n2 = 0;
                                    for (StackTraceElement e :
                                            Thread.currentThread().getStackTrace()) {
                                        String low2 = e.getClassName().toLowerCase();
                                        if (!low2.contains("miui")
                                                || low2.contains("hvsdiagnostics")) {
                                            continue;
                                        }
                                        stk2.append(e.getClassName()
                                                        .substring(e.getClassName()
                                                                .lastIndexOf('.') + 1))
                                                .append('.').append(e.getMethodName())
                                                .append(" <- ");
                                        if (++n2 >= 4) {
                                            break;
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                                L.i("[入口探针] " + cls.substring(cls.lastIndexOf('.') + 1) + "."
                                        + m + " | 来自: " + stk2);
                            }
                            if (isGoToState && p.args != null && p.args.length >= 2) {
                                StringBuilder stk = new StringBuilder();
                                try {
                                    StackTraceElement[] st =
                                            Thread.currentThread().getStackTrace();
                                    int n = 0;
                                    for (StackTraceElement e : st) {
                                        String cn = e.getClassName();
                                        String low = cn.toLowerCase();
                                        // ★ 只要 MIUI 自己的帧（其它一律跳过）
                                        if (!low.contains("miui") || low.contains("hvsdiagnostics")) {
                                            continue;
                                        }
                                        stk.append(cn.substring(cn.lastIndexOf('.') + 1))
                                                .append('.').append(e.getMethodName()).append(" <- ");
                                        if (++n >= 3) {
                                            break;
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                                L.i("[goToState] state="
                                        + (p.args[0] == null ? "null"
                                           : p.args[0].getClass().getSimpleName())
                                        + " animFlag=" + p.args[1] + " | 来自: " + stk);
                                if (Config.HVS_CONTENT_REDRAW) {
                                    try {
                                        Object st0 = p.args[0];
                                        Object pv = null;
                                        // ★ 不依赖 JADX 假名：找该类里"返回 float 的无参方法"= 目标进度
                                        for (java.lang.reflect.Method mm
                                                : st0.getClass().getMethods()) {
                                            if (mm.getParameterCount() == 0
                                                    && mm.getReturnType() == float.class) {
                                                pv = mm.invoke(st0);
                                                break;
                                            }
                                        }
                                        if (pv instanceof Float
                                                && Math.abs(((Float) pv).floatValue() - 1.0f) < 1e-3f) {
                                            // ★ 切到 OpenState（负一屏展开）也武装模糊归零窗口 2 秒
                                            if (Config.HVS_BLUR_RESET) {
                                                sBlurGuardUntil = android.os.SystemClock
                                                        .uptimeMillis() + 2000L;
                                            }
                                            new android.os.Handler(
                                                    android.os.Looper.getMainLooper()).postDelayed(
                                                    new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            reshowAssistContent("切到 OpenState 后");
                                                            clearBlurStandard("切到 OpenState 后 1.2s");
                                                        }
                                                    }, 300L);
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                            if (sProbeLog++ < 3000) {
                                StringBuilder sb = new StringBuilder();
                                if (p.args != null) {
                                    for (Object o : p.args) {
                                        sb.append(o == null ? "null"
                                                : (o.getClass().getSimpleName() + "=" + o)).append(' ');
                                    }
                                }
                                L.i("[状态探针] " + m + "(" + sb + ")");
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
            L.i("[状态探针] 已挂钩 " + cls + " -> " + java.util.Arrays.toString(methods));
        } catch (Throwable e) {
            L.w("[状态探针] " + cls + " 挂钩失败: " + e);
        }
    }

    private static int sProbeRetry;

    private static void installOverlayProbes() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app == null) {
                if (sProbeRetry++ < 15) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            new Runnable() {
                                @Override
                                public void run() {
                                    installOverlayProbes();
                                }
                            }, 2000L);
                } else {
                    L.w("[状态探针] 重试 15 次仍拿不到 application");
                }
                return;
            }
            L.i("[状态探针] 拿到 application（第 " + sProbeRetry + " 次重试）");
            ClassLoader cl = (ClassLoader) app.getClass().getMethod("getClassLoader").invoke(app);
            hookProbe(cl, "com.miui.launcher.overlay.server.pane.SlidingPaneStateManager",
                    new String[]{"a", "b", "c", "d", "e", "onAnimationEnd"});
            hookProbe(cl, "com.miui.launcher.overlay.server.pane.SwipeDetector",
                    new String[]{"a", "b"});
            hookProbe(cl, "com.miui.personalassistant.device.DeviceBlurBlendAdapter",
                    new String[]{"onEnter", "onLeave", "onScroll", "updateBlurState", "blurView",
                                 "blurOverlayWindow", "onResume"});
            hookProbe(cl, "com.miui.personalassistant.core.blur.BlurPresentDrawView",
                    new String[]{"backgroundBlurStateChanged"});
            // ★ 任务第 1 步：关闭入口 g / 切回入口 M（只探针）
            hookProbe(cl, "com.miui.personalassistant.core.overlay.AssistantOverlayWindow",
                    new String[]{"g", "M"});
            hookProbe(cl, "com.miui.launcher.overlay.server.pane.SlidingPaneWindow",
                    new String[]{"g", "M"});
        } catch (Throwable t) {
            L.w("[状态探针] 安装失败: " + t);
        }
    }

    private static volatile boolean sTimelineOn;

    /** 状态时间线探针：缩回开始后 1 秒内每 25ms 采样"负一屏 tx + SF 状态"。 */
    private static void startTimelineProbe() {
        try {
            if (sTimelineOn) {
                return;
            }
            sTimelineOn = true;
            final long t0 = android.os.SystemClock.uptimeMillis();
            final android.os.Handler h =
                    new android.os.Handler(android.os.Looper.getMainLooper());
            for (int i = 0; i < 40; i++) {
                final int k = i;
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            android.view.View tv = pickTranslated(findOverlayRoot());
                            L.i("[时间线] +" + (k * 25) + "ms tx="
                                    + (tv == null ? "n/a" : String.valueOf(tv.getTranslationX()))
                                    + " sfActive=" + sSfAnimActive);
                        } catch (Throwable ignored) {
                        }
                        if (k == 39) {
                            sTimelineOn = false;
                        }
                    }
                }, i * 25L);
            }
        } catch (Throwable ignored) {
        }
    }


    /** 动画结束后的残留清理：停掉 SF 动画 + 把 mask/blur 压到 0（不留模糊盖层）。 */
    private static void cleanupResidue() {
        try {
            Class<?> tq = Class.forName("android.view.SurfaceControl$Transaction");
            java.lang.reflect.Constructor<?> c = tq.getDeclaredConstructor();
            c.setAccessible(true);
            Object t = c.newInstance();
            if (sLastSfAnimId > 0 && sLiveLeash != null && sLiveLeash.isValid()) {
                try {
                    tq.getMethod("stopSFAnimation", android.view.SurfaceControl.class, int.class)
                            .invoke(t, sLiveLeash, sLastSfAnimId);
                } catch (Throwable ignored) {
                }
            }
            java.lang.reflect.Method sa = tq.getMethod("setAlpha",
                    android.view.SurfaceControl.class, float.class);
            if (sLiveMask != null) {
                sa.invoke(t, sLiveMask, 0.0f);
            }
            if (sLiveBlur != null) {
                sa.invoke(t, sLiveBlur, 0.0f);
            }
            tq.getMethod("apply").invoke(t);
            if (sBackLog++ < 40) {
                L.i("[清理] 已停SF动画并压 mask/blur=0");
            }
            // ★ 再把负一屏拉回一次：若 MIUI 在"回桌面"时又把它推走，这里把最终落点钉在负一屏
            try {
                android.view.View ov = findOverlayRoot();
                android.view.View tv = pickTranslated(ov);
                if (tv != null && Math.abs(tv.getTranslationX()) > 1f) {
                    if (sBackLog++ < 40) {
                        L.i("[清理] 负一屏又偏了 tx=" + tv.getTranslationX() + "，再拉回");
                    }
                    final android.view.View fv = tv;
                    final float from2 = fv.getTranslationX();
                    final long t2 = android.os.SystemClock.uptimeMillis();
                    android.animation.ValueAnimator a2 =
                            android.animation.ValueAnimator.ofFloat(0f, 1f);
                    a2.setDuration(500);
                    a2.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(android.animation.ValueAnimator an) {
                            float tt = (android.os.SystemClock.uptimeMillis() - t2) / 1000f;
                            float w = 14.147f;
                            float p = 1f - (1f + w * tt) * (float) Math.exp(-w * tt);
                            fv.setTranslationX(from2 * (1f - p));
                        }
                    });
                    a2.start();
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable e) {
            if (sBackLog++ < 40) {
                L.w("[清理] 失败: " + e);
            }
        }
    }


    private static android.view.View pickTranslated(android.view.View root) {
        if (root == null) {
            return null;
        }
        android.view.View best = null;
        float bestTx = 1f;
        java.util.ArrayDeque<android.view.View> q = new java.util.ArrayDeque<android.view.View>();
        q.add(root);
        int guard = 0;
        StringBuilder sb = new StringBuilder();
        while (!q.isEmpty() && guard++ < 400) {
            android.view.View v = q.poll();
            float tx = v.getTranslationX();
            if (Math.abs(tx) > 1f) {
                sb.append(v.getClass().getSimpleName()).append("(tx=").append(tx).append(") ");
                if (Math.abs(tx) > Math.abs(bestTx)) {
                    bestTx = tx;
                    best = v;
                }
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    q.add(g.getChildAt(i));
                }
            }
        }
        if (sBackLog++ < 40) {
            L.i("[回程] 扫描树: 被平移=" + (sb.length() == 0 ? "（无）" : sb.toString()) + " | 选中="
                    + (best == null ? "无" : best.getClass().getSimpleName() + " tx=" + bestTx));
        }
        return best;
    }

    private static int sBackLog;

    // ===== B2：缩回时我们自己把"负一屏"滑回来（消黑）=====
    private static android.view.View findOverlayRoot() {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object inst = wmg.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field fr = wmg.getDeclaredField("mRoots");
            fr.setAccessible(true);
            java.util.ArrayList<?> roots = (java.util.ArrayList<?>) fr.get(inst);
            if (roots == null) {
                return null;
            }
            for (Object root : roots) {
                try {
                    java.lang.reflect.Field fv = root.getClass().getDeclaredField("mView");
                    fv.setAccessible(true);
                    android.view.View v = (android.view.View) fv.get(root);
                    java.lang.reflect.Field fa = root.getClass().getDeclaredField("mWindowAttributes");
                    fa.setAccessible(true);
                    Object attrs = fa.get(root);
                    String title = String.valueOf(attrs.getClass().getMethod("getTitle").invoke(attrs));
                    if (title.contains("LauncherOverlay") && v != null) {
                        return v;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            if (sBackLog++ < 40) {
                L.w("[回程] 找窗口失败: " + t);
            }
        }
        return null;
    }

    /** 缩回段：把负一屏窗口从当前位置滑回 0（背景回来 → 不再黑）。 */
    private static void bringOverlayBack() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                bringOverlayBackMain();
            }
        });
    }

    private static void bringOverlayBackMain() {
        try {
            android.view.View root = findOverlayRoot();
            final android.view.View v = pickTranslated(root);
            if (v == null) {
                if (sBackLog++ < 40) {
                    L.w("[回程] 没找到负一屏窗口");
                }
                return;
            }
            final float from = v.getTranslationX();
            // ★ 与 leash 用同一条曲线：临界阻尼弹簧（response=0.444s → ω=2π/0.444）
            final long t0 = android.os.SystemClock.uptimeMillis();
            final float span = from;
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(900);
            a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(android.animation.ValueAnimator an) {
                    try {
                        float t = (android.os.SystemClock.uptimeMillis() - t0) / 1000f;
                        float w = 14.147f;                      // 2π / 0.444
                        float p = 1f - (1f + w * t) * (float) Math.exp(-w * t);   // 临界阻尼阶跃响应
                        v.setTranslationX(span * (1f - p));
                    } catch (Throwable ignored) {
                    }
                }
            });
            a.start();
            if (sBackLog++ < 40) {
                L.i("[回程] 找到负一屏窗口 tx=" + from + " alpha=" + v.getAlpha()
                        + " vis=" + v.getVisibility() + " size=" + v.getWidth() + "x"
                        + v.getHeight() + " -> 开始滑回 0");
            }
            for (final long d : new long[]{120L, 300L, 600L, 1000L}) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        new Runnable() {
                            @Override
                            public void run() {
                                if (sBackLog++ < 40) {
                                    L.i("[回程] +" + d + "ms  tx=" + v.getTranslationX()
                                            + " alpha=" + v.getAlpha() + " vis="
                                            + v.getVisibility());
                                }
                            }
                        }, d);
            }
        } catch (Throwable t) {
            if (sSfLog++ < 30) {
                L.w("[回程] 失败: " + t);
            }
        }
    }

    private static volatile boolean sSfAnimActive;
    private static volatile int sLastSfAnimId = -1;


    /** 构造 V3 的 float[] 并对 leash 调 startSFAnimation（只用于"退出/缩回"段）。 */
    private static void startSfAnimForExit() {
        try {
            if (sLiveLeash == null || !sLiveLeash.isValid()) {
                if (sSfLog++ < 10) {
                    L.w("[SF动画] leash 无效，跳过");
                }
                return;
            }
            // 屏幕尺寸（全屏段）
            float sw = 1200f;
            float sh = 2608f;
            // 源（图标）矩形
            float ix = eFromX;
            float iy = eFromY;
            float iw = eFromW > 1f ? eFromW : 128f;
            float ih = eFromH > 1f ? eFromH : 128f;
            // 弹簧参数（取自澎湃3 SpringBundle）
            float dGeo = 0.8f;
            float rGeo = 0.444f;          // = 2π/√200
            float dRatio = 0.8f;
            float rRatio = 0.314f;        // = 2π/√400
            float dAlpha = 0.99f;
            float rAlpha = 0.314f;
            float dExt = 1.0f;
            float rExt = 0.2f;
            float[] p = new float[94];
            int k = 0;
            // 8 个属性 × 9 元组：[2.0, start, end, delay, velocity, damping, response, mass, minVisibleChange]
            k = put9(p, k, sw / 2f, ix + iw / 2f, dGeo, rGeo);                 // centerX
            k = put9(p, k, sh / 2f, iy + ih / 2f, dGeo, rGeo);                 // centerY
            k = put9(p, k, sw, iw, dGeo, rGeo);                                 // width
            k = put9(p, k, sh, ih, dGeo, rGeo);                                 // height
            k = put9(p, k, sh / sw, ih / iw, dRatio, rRatio);                   // ratio = h/w
            k = put9(p, k, 1.0f, 1.0f, dAlpha, rAlpha);                         // alpha
            k = put9(p, k, eToCorner, eFromCorner, dGeo, rGeo);                 // radius
            float dS = (float) Math.sqrt(sw * sw + sh * sh);
            float dI = (float) Math.sqrt(iw * iw + ih * ih);
            k = put9(p, k, eToCorner / dS, eFromCorner / dI, dGeo, rGeo);       // radiusRatio
            // 附加对：图标矩形 / 遮罩 / 着色 / 旋转
            float[] extra = {-1f, 0f, -1f, ix, -1f, iy, -1f, iw, -1f, ih,
                    -1f, 0f, -1f, 0f, -1f, 0f, -1f, 0f, -1f, 0f, -1f, 0f};
            for (float v : extra) {
                if (k < p.length) {
                    p[k++] = v;
                }
            }
            float[] use = new float[k];
            if (sSfAnimActive && sLastSfAnimId > 0) {
                try {
                    Class<?> tq0 = Class.forName("android.view.SurfaceControl$Transaction");
                    java.lang.reflect.Constructor<?> c0 = tq0.getDeclaredConstructor();
                    c0.setAccessible(true);
                    Object t0 = c0.newInstance();
                    tq0.getMethod("stopSFAnimation", android.view.SurfaceControl.class, int.class)
                            .invoke(t0, sLiveLeash, sLastSfAnimId);
                    tq0.getMethod("apply").invoke(t0);
                    if (sSfLog++ < 30) {
                        L.i("[SF动画] 已停掉上一条 id=" + sLastSfAnimId);
                    }
                } catch (Throwable ignored) {
                }
            }
            System.arraycopy(p, 0, use, 0, k);

            Class<?> tq = Class.forName("android.view.SurfaceControl$Transaction");
            Class<?> cb = Class.forName("android.view.OnSFAnimationCallback");
            java.lang.reflect.Constructor<?> cq = tq.getDeclaredConstructor();
            cq.setAccessible(true);
            Object t2 = cq.newInstance();
            java.lang.reflect.Method m = tq.getMethod("startSFAnimation",
                    android.view.SurfaceControl.class, float[].class, int.class, cb,
                    android.os.Handler.class, int[].class, int.class);
            Object callback = java.lang.reflect.Proxy.newProxyInstance(
                    cb.getClassLoader(), new Class<?>[]{cb},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method mm,
                                Object[] aa) {
                            try {
                                if (aa != null && aa.length >= 2 && aa[1] instanceof Integer) {
                                    sLastSfAnimId = ((Integer) aa[1]).intValue();
                                }
                                if (aa != null && aa.length >= 1 && aa[0] instanceof Integer
                                        && "onSFAnimationStatusCallback".equals(mm.getName())) {
                                    int st = ((Integer) aa[0]).intValue();
                                    sSfAnimActive = st == 1;
                                    if (st == 0) {
                                        // ★ 动画结束：清掉可能残留的模糊/遮罩层（针对"一层模糊要手动返回才消失"）
                                        new android.os.Handler(android.os.Looper.getMainLooper())
                                                .post(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        cleanupResidue();
                                                    }
                                                });
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                            if (sSfLog++ < 60) {
                                L.i("[SF动画] 回调 " + mm.getName() + " args="
                                        + java.util.Arrays.toString(aa));
                            }
                            return null;
                        }
                    });
            m.invoke(t2, sLiveLeash, use, 2, callback,
                    new android.os.Handler(android.os.Looper.getMainLooper()),
                    new int[]{1, 10, 100}, 1);
            sSfAnimActive = true;
            tq.getMethod("apply").invoke(t2);
            if (sSfLog++ < 20) {
                L.i("[SF动画] startSFAnimation 已调用 参数个数=" + k + " 源矩形=(" + ix + "," + iy
                        + "," + iw + "x" + ih + ")");
            }
        } catch (Throwable t) {
            if (sSfLog++ < 20) {
                L.w("[SF动画] 调用失败: " + t);
            }
        }
    }

    private static int sSfWholeGen = -1;

    /** B：由 SF 引擎驱动整条过渡。open=true 打开段（源矩形→全屏），false 缩回段（全屏→源矩形）。 */
    private static void startSfAnimWhole(boolean open) {
        try {
            if (!open) {
                bringOverlayBack();
                startTimelineProbe();
            }
            if (sLiveLeash == null || !sLiveLeash.isValid()) {
                if (sSfLog++ < 20) {
                    L.w("[SF整体] leash 无效，跳过 open=" + open);
                }
                return;
            }
            Object o = sLeashInfo;
            float wf = gf(o, "widthFrom", 128f);
            float hf = gf(o, "heightFrom", 128f);
            float cfx = gf(o, "centerFromX", 600f);
            float cfy = gf(o, "centerFromY", 1304f);
            float fCorner = gf(o, "fromCorner", 36f);
            float tCorner = gf(o, "toCorner", 174f);
            // 源矩形（组件）
            float sw2 = 1200f;
            float sh2 = 2608f;
            float ix = cfx - wf / 2f;
            float iy = cfy - hf / 2f;
            // 全屏矩形
            float fx = 0f;
            float fy = 0f;
            float fw = sw2;
            float fh = sh2;
            float sax;
            float say;
            float saw;
            float sah;
            float eax;
            float eay;
            float eaw;
            float eah;
            float sCorner;
            float eCorner;
            if (open) {
                sax = ix; say = iy; saw = wf; sah = hf; sCorner = fCorner;
                eax = fx; eay = fy; eaw = fw; eah = fh; eCorner = tCorner;
            } else {
                sax = fx; say = fy; saw = fw; sah = fh; sCorner = tCorner;
                eax = ix; eay = iy; eaw = wf; eah = hf; eCorner = fCorner;
            }
            float dGeo = 1.0f;          // ★ 用户反馈"过弹" → 几何改临界阻尼（不过冲）
            float rGeo = 0.444f;
            float dRatio = 1.0f;        // ★ 同上
            float rRatio = 0.314f;
            float dAlpha = 0.99f;
            float rAlpha = 0.314f;
            // ★ 速度总闸（与桌面同一个系统设置）：刚度 ÷ ratio² ⟺ response × ratio
            float spd = 1.0f;
            try {
                Class<?> at2 = Class.forName("android.app.ActivityThread");
                Object app2 = at2.getMethod("currentApplication").invoke(null);
                android.content.ContentResolver cr2 = (android.content.ContentResolver)
                        app2.getClass().getMethod("getContentResolver").invoke(app2);
                spd = android.provider.Settings.Global.getFloat(cr2,
                        "transition_animation_duration_ratio", 1.0f);
                if (spd <= 0f) {
                    spd = 1.0f;
                }
                if (spd > 1f) {
                    spd = 1f;
                }
            } catch (Throwable ignored) {
            }
            rGeo *= spd;
            rRatio *= spd;
            rAlpha *= spd;
            if (sSfLog++ < 40) {
                L.i("[SF整体] 速度比率=" + spd);
            }
            float[] p = new float[96];
            int k = 0;
            k = put9(p, k, sax + saw / 2f, eax + eaw / 2f, dGeo, rGeo);          // centerX
            k = put9(p, k, say + sah / 2f, eay + eah / 2f, dGeo, rGeo);          // centerY
            k = put9(p, k, saw, eaw, dGeo, rGeo);                                 // width
            k = put9(p, k, sah, eah, dGeo, rGeo);                                 // height
            k = put9(p, k, sah / saw, eah / eaw, dRatio, rRatio);                 // ratio = h/w
            k = put9(p, k, 1.0f, 1.0f, dAlpha, rAlpha);                           // alpha
            k = put9(p, k, sCorner, eCorner, dGeo, rGeo);                         // radius
            float dS = (float) Math.sqrt(saw * saw + sah * sah);
            float dE = (float) Math.sqrt(eaw * eaw + eah * eah);
            k = put9(p, k, sCorner / dS, eCorner / dE, dGeo, rGeo);               // radiusRatio
            float[] extra = {-1f, 0f, -1f, ix, -1f, iy, -1f, wf, -1f, hf,
                    -1f, 0f, -1f, 0f, -1f, 0f, -1f, 0f, -1f, 0f, -1f, 0f};
            for (float v : extra) {
                if (k < p.length) {
                    p[k++] = v;
                }
            }
            float[] use = new float[k];
            System.arraycopy(p, 0, use, 0, k);
            if (sSfAnimActive && sLastSfAnimId > 0) {
                try {
                    Class<?> tq0 = Class.forName("android.view.SurfaceControl$Transaction");
                    java.lang.reflect.Constructor<?> c0 = tq0.getDeclaredConstructor();
                    c0.setAccessible(true);
                    Object t0 = c0.newInstance();
                    tq0.getMethod("stopSFAnimation", android.view.SurfaceControl.class, int.class)
                            .invoke(t0, sLiveLeash, sLastSfAnimId);
                    tq0.getMethod("apply").invoke(t0);
                } catch (Throwable ignored) {
                }
            }
            Class<?> tq = Class.forName("android.view.SurfaceControl$Transaction");
            Class<?> cb = Class.forName("android.view.OnSFAnimationCallback");
            java.lang.reflect.Constructor<?> cq = tq.getDeclaredConstructor();
            cq.setAccessible(true);
            Object t2 = cq.newInstance();
            if (!open && sSfAnimActive) {
                try {
                    java.lang.reflect.Method adj = tq.getMethod("adjustSFAnimation",
                            android.view.SurfaceControl.class, float[].class, int.class,
                            int.class);
                    adj.invoke(t2, sLiveLeash, use, 1, 1);
                    tq.getMethod("apply").invoke(t2);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            if (sSfAnimActive) {
                                L.i("[兜底] 900ms 到，强制清理并释放（打断路径无 status=0）");
                            }
                            sSfAnimActive = false;
                            cleanupResidue();
                        }
                    }, 900L);
                    sSfAnimActive = true;   // ★ 关键修复：打断路径原先没打开"吞写"开关 → 两个引擎同时写 leash = 飘
                    if (sSfLog++ < 60) {
                        L.i("[SF整体] adjust 改向 参数=" + k + " 目标=(" + eax + "," + eay + " "
                                + eaw + "x" + eah + ") | 几何源(组件)=" + gf(o, "centerFromX", -1f)
                                + "," + gf(o, "centerFromY", -1f) + " " + gf(o, "widthFrom", -1f)
                                + "x" + gf(o, "heightFrom", -1f) + " | 几何目标="
                                + gf(o, "centerToX", -1f) + "," + gf(o, "centerToY", -1f) + " "
                                + gf(o, "widthTo", -1f) + "x" + gf(o, "heightTo", -1f));
                    }
                    return;
                } catch (Throwable t) {
                    if (sSfLog++ < 40) {
                        L.w("[SF整体] adjust 失败，退回 start: " + t);
                    }
                }
            }
            // ★ 把 mask/blur 压到 0（桌面动画不用遮罩 → 消黑闪）
            try {
                java.lang.reflect.Method sa = tq.getMethod("setAlpha",
                        android.view.SurfaceControl.class, float.class);
                // blur 不压：缩回时的背景靠它

            } catch (Throwable ignored) {
            }
            java.lang.reflect.Method m = tq.getMethod("startSFAnimation",
                    android.view.SurfaceControl.class, float[].class, int.class, cb,
                    android.os.Handler.class, int[].class, int.class);
            Object callback = java.lang.reflect.Proxy.newProxyInstance(cb.getClassLoader(),
                    new Class<?>[]{cb}, new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method mm,
                                Object[] aa) {
                            try {
                                if (aa != null && aa.length >= 2 && aa[1] instanceof Integer) {
                                    sLastSfAnimId = ((Integer) aa[1]).intValue();
                                }
                                if (aa != null && aa.length >= 1 && aa[0] instanceof Integer
                                        && "onSFAnimationStatusCallback".equals(mm.getName())) {
                                    sSfAnimActive = ((Integer) aa[0]).intValue() == 1;
                                }
                            } catch (Throwable ignored) {
                            }
                            if (sSfLog++ < 80) {
                                L.i("[SF整体] 回调 " + mm.getName() + " args="
                                        + java.util.Arrays.toString(aa));
                            }
                            return null;
                        }
                    });
            m.invoke(t2, sLiveLeash, use, 2, callback,
                    new android.os.Handler(android.os.Looper.getMainLooper()),
                    new int[]{1, 10, 100}, 1);
            tq.getMethod("apply").invoke(t2);
            sSfAnimActive = true;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            if (sSfAnimActive) {
                                L.i("[兜底] 900ms 到，强制清理并释放（打断路径无 status=0）");
                            }
                            sSfAnimActive = false;
                            cleanupResidue();
                        }
                    }, 900L);
            if (sSfLog++ < 60) {
                L.i("[SF整体] 明细 open=" + open
                        + " | 几何源(组件)=" + gf(o, "centerFromX", -1f) + "," + gf(o, "centerFromY", -1f)
                        + " " + gf(o, "widthFrom", -1f) + "x" + gf(o, "heightFrom", -1f)
                        + " corner=" + gf(o, "fromCorner", -1f)
                        + " | 几何目标=" + gf(o, "centerToX", -1f) + "," + gf(o, "centerToY", -1f)
                        + " " + gf(o, "widthTo", -1f) + "x" + gf(o, "heightTo", -1f)
                        + " corner=" + gf(o, "toCorner", -1f)
                        + " | 本次使用 起点=(" + sax + "," + say + " " + saw + "x" + sah
                        + ") 终点=(" + eax + "," + eay + " " + eaw + "x" + eah + ")"
                        + " | lease实时sLeashInfo=" + (sLeashInfo == null ? "null"
                        : sLeashInfo.getClass().getSimpleName()));
            }

            if (open && Config.HVS_TEST_AUTO_REVERSE) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        new Runnable() {
                            @Override
                            public void run() {
                                L.i("[SF自测] 1500ms 后自动触发缩回（模拟中途上滑打断）");
                                startSfAnimWhole(false);
                            }
                        }, 1500L);
            }
        } catch (Throwable t) {
            if (sSfLog++ < 40) {
                L.w("[SF整体] 失败: " + t);
            }
        }
    }

    /** 写一个属性的 9 元组。 */
    private static int put9(float[] p, int k, float start, float end, float damping,
            float response) {
        if (k + 9 > p.length) {
            return k;
        }
        p[k++] = 2.0f;          // 弹簧类型标记
        p[k++] = start;
        p[k++] = end;
        p[k++] = 0f;            // delay
        p[k++] = 0f;            // velocity
        p[k++] = damping;
        p[k++] = response;
        p[k++] = 1f;            // mass
        p[k++] = 1.0E-4f;       // minVisibleChange
        return k;
    }

    private static boolean sSfQueryTried;


    // ===== 第 112 轮：接管"退出段"（缩回）=====
    private static volatile boolean sExitActive;
    private static long sExitT0;
    private static boolean sExitGeomOk;
    private static float eRealScale = 1f;
    private static float eFromCorner = 36f;
    private static float eToCorner = 174f;
    private static float eFromX;
    private static float eFromY;
    private static int sExitLog;

    /** 退出段开始：打点 + 取一次几何（源的圆角/位置/缩放）。 */
    private static void initExitTakeover() {
        sExitT0 = android.os.SystemClock.uptimeMillis();
        sExitActive = true;
        sExitGeomOk = false;
        try {
            Object o = sLeashInfo;
            if (o != null) {
                eRealScale = gf(o, "realScale", 1f);
                eFromCorner = gf(o, "fromCorner", 36f);
                eToCorner = gf(o, "toCorner", 174f);
                float wf = gf(o, "widthFrom", 0f);
                float hf = gf(o, "heightFrom", 0f);
                eFromX = gf(o, "centerFromX", 0f) - wf / 2f;
                eFromY = gf(o, "centerFromY", 0f) - hf / 2f;
                eFromW = wf;
                eFromH = hf;
                sExitGeomOk = true;
            }
        } catch (Throwable ignored) {
        }
        if (sExitLog++ < 20) {
            L.i("[退出接管] 开始 几何ok=" + sExitGeomOk + " realScale=" + eRealScale
                    + " corner " + eToCorner + "->" + eFromCorner + " 位置->(" + eFromX + "," + eFromY + ")");
        }
    }

    private static float gf(Object o, String name, float dflt) {
        try {
            java.lang.reflect.Field f = null;
            for (Class<?> c = o.getClass(); c != null && f == null; c = c.getSuperclass()) {
                try {
                    f = c.getDeclaredField(name);
                } catch (NoSuchFieldException e) {
                    // 继续往父类找
                }
            }
            if (f == null) {
                return dflt;
            }
            f.setAccessible(true);
            return f.getFloat(o);
        } catch (Throwable t) {
            return dflt;
        }
    }

    /** 系统/桌面那条标准曲线：cubic-bezier(0.2, 0, 0, 1)（Material "emphasized"）。
     *  用牛顿迭代解 x→t 再求 y，**不靠我编的采样点**。 */
    private static float emphasize(float q) {
        if (q <= 0f) {
            return 0f;
        }
        if (q >= 1f) {
            return 1f;
        }
        final float x1 = 0.2f, y1 = 0f, x2 = 0f, y2 = 1f;
        float t = q;
        for (int i = 0; i < 8; i++) {
            float u = 1f - t;
            float xt = 3f * u * u * t * x1 + 3f * u * t * t * x2 + t * t * t;
            float dx = 3f * u * u * x1 + 6f * u * t * (x2 - x1) + 3f * t * t * (1f - x2);
            if (Math.abs(dx) < 1e-6f) {
                break;
            }
            t -= (xt - q) / dx;
            if (t < 0f) {
                t = 0f;
            }
            if (t > 1f) {
                t = 1f;
            }
        }
        float u = 1f - t;
        return 3f * u * u * t * y1 + 3f * u * t * t * y2 + t * t * t;
    }

    /** 退出段：把 leash 的几何按我们的曲线推进（280ms、smoothstep、174→36）。 */
    private static void rewriteExit(Object[] args, String mn) {
        try {
            if (!sExitGeomOk || args == null) {
                return;
            }
            long now = android.os.SystemClock.uptimeMillis();
            float q = (now - sExitT0) / 280f;
            if (q >= 1f) {
                sExitActive = false;
                q = 1f;
            }
            if (q < 0f) {
                q = 0f;
            }
            float e = emphasize(q);
            // ★ 按用户决定（方案 A）：**只改圆角**，绝不碰 scale / position / crop。
            //   原因：上一版改了 scale/position 却没同步 crop → 应用内容被整幅缩进图标矩形
            //   → 出现"图标短暂显示应用内容"。圆角是**独立量**，不参与内容映射，单独改它零风险。
            //   （代价：曲线/速度这两种要动几何量的需求，本方案下改不了。）
            if ("setCornerRadius".equals(mn) && args.length >= 2) {
                float v = eToCorner + (eFromCorner - eToCorner) * e;
                for (int i = 1; i < args.length; i++) {
                    args[i] = v;
                }
            }
            if (sExitLog++ < 60) {
                L.i("[退出接管] " + mn + " q=" + String.format("%.2f", q) + " args=" + dumpArgs(args));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把 mask / blur 层的 alpha 顶到 1（让源组件快照盖住背后的黑）。 */
    private static void keepMaskOpaque(long delay) {
        try {
            Class<?> t = Class.forName("android.view.SurfaceControl$Transaction");
            java.lang.reflect.Constructor<?> c = t.getDeclaredConstructor();
            c.setAccessible(true);
            Object tx = c.newInstance();
            java.lang.reflect.Method setAlpha = t.getMethod("setAlpha",
                    android.view.SurfaceControl.class, float.class);
            int n = 0;
            if (sLiveMask != null) {
                setAlpha.invoke(tx, sLiveMask, 1.0f);
                n++;
            }
            if (sLiveBlur != null) {
                setAlpha.invoke(tx, sLiveBlur, 1.0f);
                n++;
            }
            if (n > 0) {
                t.getMethod("apply").invoke(tx);
                if (sKeepMaskLog++ < 40) {
                    L.i("[保快照] mask/blur alpha=1  delay=" + delay + "ms  层数=" + n);
                }
            }
        } catch (Throwable e) {
            if (sKeepMaskLog++ < 40) {
                L.w("[保快照] 失败 delay=" + delay + ": " + e);
            }
        }
    }

    private static int sKeepMaskLog;

    // ===== B 迭代 1：值跳变限速（scale / position / corner）=====
    private static int sClampGen = -1;
    private static float sClampScale = -1f;
    private static float sClampPosX = Float.NaN;
    private static float sClampPosY = Float.NaN;
    private static float sClampCorner = -1f;
    private static int sClampLog;

    private static void bClamp(Object[] args, String mn) {
        try {
            if (sClampGen != sOpenGen) {
                sClampGen = sOpenGen;
                sClampScale = -1f;
                sClampPosX = Float.NaN;
                sClampCorner = -1f;
            }
            if (args == null) {
                return;
            }
            if ("setScale".equals(mn) && args.length == 3) {
                float v = ((Number) args[1]).floatValue();
                float lim = 0.02f;
                if (sClampScale < 0f) {
                    sClampScale = v;
                    return;
                }
                if (v - sClampScale > lim) {
                    float nv = sClampScale + lim;
                    args[1] = nv;
                    args[2] = nv;
                    sClampScale = nv;
                    if (sClampLog++ < 30) {
                        L.i("[B限速] scale " + v + " -> " + nv);
                    }
                } else {
                    sClampScale = v;
                }
            } else if ("setPosition".equals(mn) && args.length == 3) {
                float x = ((Number) args[1]).floatValue();
                float y = ((Number) args[2]).floatValue();
                if (Float.isNaN(sClampPosX)) {
                    sClampPosX = x;
                    sClampPosY = y;
                    return;
                }
                float lim = 12f;
                float dx = x - sClampPosX;
                float dy = y - sClampPosY;
                if (dx > lim) {
                    x = sClampPosX + lim;
                } else if (dx < -lim) {
                    x = sClampPosX - lim;
                }
                if (dy > lim) {
                    y = sClampPosY + lim;
                } else if (dy < -lim) {
                    y = sClampPosY - lim;
                }
                args[1] = x;
                args[2] = y;
                sClampPosX = x;
                sClampPosY = y;
            } else if ("setCornerRadius".equals(mn) && args.length >= 2) {
                float v = ((Number) args[1]).floatValue();
                float lim = 6f;
                if (sClampCorner < 0f) {
                    sClampCorner = v;
                    return;
                }
                if (v - sClampCorner > lim) {
                    v = sClampCorner + lim;
                }
                for (int i = 1; i < args.length; i++) {
                    args[i] = v;
                }
                sClampCorner = v;
            }
        } catch (Throwable ignored) {
        }
    }

    private static int sB2Log;
    private static int sB2ProgLog;
    private static boolean sB2Hooked;

    /** 连续补帧收尾：打断后每 ~16ms 补一次，用来压过 MIUI 自己每帧对 leash 的写法。 */
    private static void repeatFinish(final int n) {
        if (n <= 0) {
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                finishLeashToFullScreen(1L);
                repeatFinish(n - 1);
            }
        }, 16L);
    }

    private static volatile android.view.SurfaceControl sLiveLeash;
    private static volatile android.view.SurfaceControl sLiveStart;
    private static volatile android.view.SurfaceControl sLiveMask;
    private static volatile android.view.SurfaceControl sLiveBlur;
    /** 模糊归零保护的截止时刻（uptimeMillis）。 */
    private static volatile long sBlurGuardUntil;
    private static int sOtherLog;
    private static volatile long sLastLeashWriteAt;
    private static volatile long sDumpAt;

    /** ★ 标准入口清模糊：调用负一屏自己的 y5.i.a(View)（清 blendColor/blurMode/radius/passBlur）。 */
    private static void clearBlurStandard(String why) {
        try {
            android.view.View root = findOverlayRoot();
            if (root == null) {
                L.w("[标准清模糊] 找不到 overlay 窗口（" + why + "）");
                return;
            }
            android.view.View decor = root;
            Class<?> yi = Class.forName("y5.i");
            java.lang.reflect.Method m = yi.getMethod("a", android.view.View.class);
            m.invoke(null, decor);
            // 再补一刀：直接关 pass-window-blur（对付桌面侧 view_scale_blur 那层）
            try {
                java.lang.reflect.Method sp = android.view.View.class
                        .getMethod("setPassWindowBlurEnabled", boolean.class);
                sp.invoke(decor, Boolean.FALSE);
            } catch (Throwable e2) {
                L.w("[标准清模糊] setPassWindowBlurEnabled 失败: " + e2);
            }
            L.i("[标准清模糊] " + why + " → 已调用 y5.i.a(decor) + setPassWindowBlurEnabled(false)");
        } catch (Throwable e) {
            L.w("[标准清模糊] 失败(" + why + "): " + e);
        }
    }


    /** 静止时快照负一屏视图状态（找"只剩模糊"时内容到底在哪）。 */
    private static void dumpOverlayState(String why) {
        try {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - sDumpAt < 1500L) {
                return;
            }
            sDumpAt = now;
            android.view.View root = findOverlayRoot();
            if (root == null) {
                L.i("[负一屏快照] " + why + "：找不到 overlay 窗口");
                return;
            }
            StringBuilder sb = new StringBuilder();
            java.util.ArrayDeque<android.view.View> q = new java.util.ArrayDeque<>();
            q.add(root);
            int n = 0;
            while (!q.isEmpty() && n < 8) {
                android.view.View v = q.poll();
                sb.append(v.getClass().getSimpleName())
                        .append("{vis=").append(v.getVisibility())
                        .append(",a=").append(v.getAlpha())
                        .append(",tx=").append(v.getTranslationX())
                        .append(",ty=").append(v.getTranslationY())
                        .append(",").append(v.getWidth()).append('x').append(v.getHeight())
                        .append("} ");
                n++;
                if (v instanceof android.view.ViewGroup) {
                    android.view.ViewGroup g = (android.view.ViewGroup) v;
                    for (int i = 0; i < Math.min(3, g.getChildCount()); i++) {
                        q.add(g.getChildAt(i));
                    }
                }
            }
            L.i("[负一屏快照] " + why + " 根=" + root.getClass().getSimpleName()
                    + " 可见=" + root.getVisibility() + " | " + sb);
        } catch (Throwable e) {
            L.w("[负一屏快照] 失败: " + e);
        }
    }


    /** 让负一屏内容重新可见并重绘（治"只剩模糊背景、要手动返回才恢复"）。 */
    private static void reshowAssistContent(String why) {
        try {
            android.view.View root = findOverlayRoot();
            if (root == null) {
                L.w("[内容重绘] 找不到负一屏窗口（" + why + "）");
                return;
            }
            android.view.View target = null;
            java.util.ArrayDeque<android.view.View> q = new java.util.ArrayDeque<>();
            q.add(root);
            while (!q.isEmpty()) {
                android.view.View v = q.poll();
                if (v.getClass().getSimpleName().contains("AssistContentView")) {
                    target = v;
                    break;
                }
                if (v instanceof android.view.ViewGroup) {
                    android.view.ViewGroup g = (android.view.ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        q.add(g.getChildAt(i));
                    }
                }
            }
            if (target == null) {
                L.w("[内容重绘] 树里没有 AssistContentView（" + why + "）");
                return;
            }
            int vis = target.getVisibility();
            float al = target.getAlpha();
            if (vis != android.view.View.VISIBLE) {
                target.setVisibility(android.view.View.VISIBLE);
            }
            if (al < 1f) {
                target.setAlpha(1f);
            }
            target.invalidate();
            target.requestLayout();
            L.i("[内容重绘] " + why + "：可见性 " + vis + "→" + target.getVisibility()
                    + " alpha " + al + "→" + target.getAlpha() + " 已 invalidate/requestLayout");
        } catch (Throwable e) {
            L.w("[内容重绘] 失败(" + why + "): " + e);
        }
    }

    private static volatile long sLiveAt;

    /**
     * 被打断后把 leash 推到终态（铺满全屏 + 屏幕圆角 174）。
     * 依据（量化实测）：被中断那次应用内容左边界留 ~32px，正常播完是 0px ——
     * 即打断后窗口停在半途位置、没人把它走完。
     */
    private static void finishLeashToFullScreen(long delay) {
        // ★ 第 76 轮修正：把"负一屏窗口 alpha"的采样点放到**这里** —— 因为这函数一定会被执行
        //   （`createViewLeash` 里那 4 个定时器 150/350/700/1100ms 都会调它），
        //   而我先前放在 `finishCurrentAnimation` 钩子里 —— 实测那个钩子在很多路径上**根本不触发**
        //   （本轮 `merge=0`、收尾 4 行全来自定时器），所以采样一条都没打出来。
        logRootAlpha("t" + delay);
        // ★ 用**活的**句柄（第 34 轮）：createViewLeash 的参数已 <released>，改用实例字段。
        android.view.SurfaceControl sc = sLiveLeash != null ? sLiveLeash : sLeashSc;
        if (sc == null) {
            return;
        }
        try {
            Class<?> txnCls = Class.forName("android.view.SurfaceControl$Transaction");
            java.lang.reflect.Constructor<?> ctor = txnCls.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object txn = ctor.newInstance();
            java.lang.reflect.Method setPos = txnCls.getMethod("setPosition",
                    android.view.SurfaceControl.class, float.class, float.class);
            java.lang.reflect.Method setCorner = null;
            java.lang.reflect.Method setAlpha = null;
            try {
                setCorner = txnCls.getMethod("setCornerRadius",
                        android.view.SurfaceControl.class, float.class);
            } catch (Throwable ignored) {
            }
            try {
                setAlpha = txnCls.getMethod("setAlpha",
                        android.view.SurfaceControl.class, float.class);
            } catch (Throwable ignored) {
            }
            // 应用 leash → **四个量一起写全**（第 37 轮）：
            //   第 35 轮只写了 position + corner，**漏了 setScale 和 setCrop**，
            //   于是窗口的缩放还停在半途（0.43）→ 我把它越摆越歪（36px → 116px）。
            //   实测 MIUI 每帧写的是：setAlpha / setCrop(0,0,1200,H) / setScale(s,s)
            //                          / setPosition(x,y) / setCornerRadius(r)，
            //   那么"整屏终态"就该是：scale=1、position=0,0、crop=(0,0,1200,2608)、corner=174。
            try {
                java.lang.reflect.Method setScale = txnCls.getMethod("setScale",
                        android.view.SurfaceControl.class, float.class, float.class);
                setScale.invoke(txn, sc, 1f, 1f);
            } catch (Throwable ignored) {
            }
            try {
                java.lang.reflect.Method setCrop = txnCls.getMethod("setCrop",
                        android.view.SurfaceControl.class, float.class, float.class,
                        float.class, float.class);
                setCrop.invoke(txn, sc, 0f, 0f, 1200f, 2608f);
            } catch (Throwable ignored) {
            }
            setPos.invoke(txn, sc, 0f, 0f);
            if (setCorner != null) {
                setCorner.invoke(txn, sc, 174f);
            }
            // activity surface 也把圆角设成屏幕圆角
            if (sLiveStart != null && setCorner != null) {
                setCorner.invoke(txn, sLiveStart, 174f);
            }
            // 遮罩 / 模糊层：撤掉（解决"源组件快照残留在应用上"）
            // ★★ 第 64 轮：**不再把遮罩/模糊层 alpha 归零**。
            //   线索：字段名是 view_scale_blur（模糊背景层），而用户录像里"黑屏段"的真相是
            //   「应用窗口在缩小、但它背后的背景是黑的」。把背景层 alpha 归零只会加重这个现象。
            //   "铺满全屏"靠的是几何那四个量（scale/crop/position/corner），不靠这两笔 alpha。
            if (false && setAlpha != null) {
                if (sLiveMask != null) {
                    setAlpha.invoke(txn, sLiveMask, 0f);
                }
                if (sLiveBlur != null) {
                    setAlpha.invoke(txn, sLiveBlur, 0f);
                }
            }
            txnCls.getMethod("apply").invoke(txn);
            if (sFinishLeashLog++ < 30) {
                L.i("[收尾] 推到终态 delay=" + delay + "ms  活句柄=" + (sLiveLeash != null)
                        + " 圆角=" + (setCorner != null) + " alpha=" + (setAlpha != null));
            }
        } catch (Throwable t) {
            if (sFinishLeashLog++ < 30) {
                // ★ 打真正的**原因**：InvocationTargetException 只是外壳，
                //   要看清是 "SurfaceControl already released" 还是别的。
                Throwable c = t.getCause() != null ? t.getCause() : t;
                L.w("[收尾] 失败 delay=" + delay + "ms: " + c);
            }
        }
    }

    private static int sScFieldLog;

    /**
     * 诊断：打印实例里所有 `SurfaceControl` 类型的字段（含父类），用于找到**真正存活的那条 leash**。
     * 背景：`createViewLeash` 的参数在 150ms 就 `<released>`，抓它没用。
     */
    private static void dumpScFields(Object obj) {
        if (obj == null || sScFieldLog > 12) {
            return;
        }
        sScFieldLog++;
        StringBuilder sb = new StringBuilder();
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (!android.view.SurfaceControl.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    // ★ 顺手**存下活的句柄**（第 34 轮核心）：createViewLeash 的参数 150ms 就释放了，
                    //   真正活着的是这些字段。带上时刻，避免把上一轮的旧对象当成本次的。
                    if (v instanceof android.view.SurfaceControl) {
                        String nm = f.getName();
                        long now = android.os.SystemClock.uptimeMillis();
                        if ("mLeash".equals(nm)) {
                            sLiveLeash = (android.view.SurfaceControl) v;
                            sLiveAt = now;
                        } else if ("mStartActivity".equals(nm)) {
                            sLiveStart = (android.view.SurfaceControl) v;
                        } else if ("mMaskSurface".equals(nm)) {
                            sLiveMask = (android.view.SurfaceControl) v;
                        } else if ("mBlurLeash".equals(nm)) {
                            sLiveBlur = (android.view.SurfaceControl) v;
                        }
                    }
                    sb.append(sb.length() == 0 ? "" : " | ")
                            .append(c.getSimpleName()).append('.').append(f.getName())
                            .append('=').append(String.valueOf(v).replace("Surface(name=", ""));
                }
            }
        } catch (Throwable t) {
            sb.append(" 取字段失败: ").append(t);
        }
        L.i("[leash字段] " + (sb.length() == 0 ? "(没有 SurfaceControl 字段)" : sb.toString()));
    }

    /**
     * 记录我们进程里各根视图（**含负一屏覆盖窗口 LauncherOverlayWindow**）的 alpha / translationX。
     *
     * 为什么需要它（第 76 轮）：实测发现过渡期间"背景层" blur 的 alpha 全程最高只有 0.20，
     * 而 mask 会淡到 0 —— 如果 blur 底下**没有底**（例如负一屏窗口此时 alpha=0），
     * 叠出来就是近黑（我们逐帧量到的 #0B0B16 那一类）。所以"负一屏窗口在那一刻的 alpha"
     * 是判定"背景没底"的关键量。纯记录，不改行为。
     */
    private static void logRootAlpha(String tag) {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object inst = wmg.getMethod("getInstance").invoke(null);
            // Android 17(SDK37) 上 `getRootViews()` 已不存在（实测 NoSuchMethodException）→ 依次尝试：
            //   ① 无参 getRootViews()  ② getRootViews(boolean)  ③ 直接读字段 mRoots
            Object roots = null;
            for (java.lang.reflect.Method m : wmg.getDeclaredMethods()) {
                if (!"getRootViews".equals(m.getName())) {
                    continue;
                }
                m.setAccessible(true);
                try {
                    if (m.getParameterTypes().length == 0) {
                        roots = m.invoke(inst);
                    } else if (m.getParameterTypes().length == 1) {
                        roots = m.invoke(inst, Boolean.FALSE);
                    }
                    if (roots != null) {
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (roots == null) {
                java.lang.reflect.Field fr = wmg.getDeclaredField("mRoots");
                fr.setAccessible(true);
                roots = fr.get(inst);
            }
            StringBuilder sb = new StringBuilder();
            if (roots instanceof java.util.List) {
                for (Object rv : (java.util.List<?>) roots) {
                    Object v = rv.getClass().getMethod("getView").invoke(rv);
                    if (v instanceof android.view.View) {
                        android.view.View view = (android.view.View) v;
                        // ★ 第 77 轮：还要读**窗口自身**的 alpha（ViewRootImpl.mWindowAttributes.alpha）。
                        //   我们之前记录的"负一屏停靠时 alpha=0"指的是这个量，**不是** View.getAlpha()——
                        //   两者完全不同，第 76 轮我就是把 View alpha 当成窗口 alpha 记的。
                        float winA = -1f;
                        try {
                            java.lang.reflect.Field fa =
                                    rv.getClass().getDeclaredField("mWindowAttributes");
                            fa.setAccessible(true);
                            Object lp = fa.get(rv);
                            if (lp != null) {
                                winA = lp.getClass().getField("alpha").getFloat(lp);
                            }
                        } catch (Throwable ignored) {
                        }
                        sb.append(view.getClass().getSimpleName())
                                .append(":viewA=").append(String.format("%.3f", view.getAlpha()))
                                .append(",winA=").append(String.format("%.3f", winA))
                                .append(",tx=").append(String.format("%.0f", view.getTranslationX()))
                                .append("  ");
                        // ★ 第 94 轮：再往里记一层**内容视图**（AssistContentView 那一层）。
                        //   动机：③（反向时窗口四周"未被绘制的死黑"）已排除"层被写成黑"与
                        //   "负一屏窗口 alpha 被隐藏"，剩下最可能是**内容视图被挪走/不参与绘制**
                        //   —— 而"停靠"正是靠内容视图位移实现的，我们之前却只记了 DecorView。
                        if (view instanceof android.view.ViewGroup) {
                            android.view.ViewGroup vg = (android.view.ViewGroup) view;
                            int cn = Math.min(vg.getChildCount(), 4);
                            for (int ci = 0; ci < cn; ci++) {
                                android.view.View ch = vg.getChildAt(ci);
                                sb.append('{').append(ch.getClass().getSimpleName())
                                        .append(":a=").append(String.format("%.2f", ch.getAlpha()))
                                        .append(",tx=").append(String.format("%.0f", ch.getTranslationX()))
                                        .append(",vis=").append(ch.getVisibility())
                                        .append("}  ");
                            }
                        }
                    }
                }
            }
            L.i("[窗口alpha " + tag + "] " + sb);
        } catch (Throwable t) {
            // 不再静默吞掉：第 76 轮第一次装上去"0 行"，就是因为这里把异常吃了、无法判断是
            // "钩子没触发"还是"反射失败"。只打前几次，避免刷屏。
            if (sRootAlphaErr++ < 5) {
                L.w("[窗口alpha] 取根视图失败: " + t);
            }
        }
    }

    private static int sRootAlphaErr;

    private static void logAnimation(String tag, XC_MethodHook.MethodHookParam p) {
        if (sAnimLogCount++ > 2000000) {
            return;
        }
        Object[] a = p.args;
        String type = "?";
        String debugId = "?";
        String modes = "-";
        if (a != null) {
            for (Object x : a) {
                if (x == null) {
                    continue;
                }
                String n = x.getClass().getName();
                if (n.endsWith("TransitionInfo")) {
                    type = String.valueOf(refl(x, "getType"));
                    debugId = String.valueOf(refl(x, "getDebugId"));
                    // 每个 change 的 mode —— 真实取值从 `TransitionInfo.modeToString`
                    // 的 packed-switch 里读出来的：
                    //   0=NONE 1=OPENING 2=CLOSING 3=TO_FRONT 4=TO_BACK 6=CHANGING
                    Object changes = refl(x, "getChanges");
                    StringBuilder sb = new StringBuilder();
                    if (changes instanceof java.util.List) {
                        java.util.List<?> list = (java.util.List<?>) changes;
                        for (int i = 0; i < list.size() && i < 8; i++) {
                            sb.append(sb.length() == 0 ? "" : ",")
                                    .append(modeName(refl(list.get(i), "getMode")));
                        }
                    }
                    modes = sb.length() == 0 ? "-" : sb.toString();
                }
            }
        }
        L.i("[HVS] " + tag + "  transition type=" + type + " id=" + debugId
                + " changeModes=[" + modes + "]  viewInfo=" + describeStaticViewInfo(p.thisObject));
    }

    /**
     * 找 `ViewInfo` 字段 —— **必须按接收者的实际类查**，不能全局缓存一个 Field。
     *
     * 教训：`mergeAnimation`/`startAnimation` 传进来的是
     * `HyperViewScaleRemoteTransition`，而 `reverseAnimationWhenMerge` 传进来的是
     * `HyperViewScaleAnimationImpl`，两者的 `mViewInfo` 是两个不同类上的字段。
     * 之前缓存成静态 Field，拿 RemoteTransition 的字段去读 AnimationImpl，
     * 每次都抛 `IllegalArgumentException: Expected receiver of type
     * HyperViewScaleRemoteTransition, but got HyperViewScaleAnimationImpl`，
     * 日志里最有用的一栏全是异常。
     */
    private static volatile Field sViewInfoField;
    private static volatile Class<?> sViewInfoFieldOwner;
    private static volatile String sViewInfoFieldNote;

    private static String describeStaticViewInfo(Object rtInstance) {
        try {
            if (rtInstance == null) {
                return "?";
            }
            Class<?> rc = rtInstance.getClass();
            if (sViewInfoField == null || sViewInfoFieldOwner != rc) {
                Field found = null;
                Class<?> c = rc;
                while (c != null && found == null) {
                    for (Field f : c.getDeclaredFields()) {
                        if (f.getType().getName().equals(VI)) {
                            try {
                                f.setAccessible(true);
                                found = f;
                            } catch (Throwable ignored) {
                            }
                            break;
                        }
                    }
                    c = c.getSuperclass();
                }
                sViewInfoField = found;
                sViewInfoFieldOwner = rc;
                sViewInfoFieldNote = rc.getSimpleName() + "."
                        + (found == null ? "❌ 没找到 ViewInfo 字段" : found.getName());
                L.i("[HVS] ViewInfo 字段 = " + sViewInfoFieldNote);
            }
            Field f = sViewInfoField;
            if (f == null) {
                return "?(" + sViewInfoFieldNote + ")";
            }
            // 之前这里写成 f.get(null) —— 对实例字段会直接抛
            // NullPointerException("null receiver")，把最有价值的一批数据全丢了。
            Object vi = f.get(java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    ? null : rtInstance);
            if (vi == null) {
                return "null";
            }
            Object view = refl(vi, "getView");
            boolean attached = false;
            String cls = "null";
            float alpha = -1f;
            if (view instanceof View) {
                View v = (View) view;
                attached = v.isAttachedToWindow();
                cls = v.getClass().getSimpleName();
                alpha = v.getAlpha();
            }
            Object r = refl(vi, "getViewRect");
            Object bmp = refl(vi, "getBitmap");
            return "{view=" + cls
                    + " attached=" + attached
                    + " alpha=" + L.f(alpha)
                    + " rect=" + r
                    + " backAnim=" + refl(vi, "isBackAnim")
                    + " thirdParty=" + refl(vi, "isThirdParty")
                    + " radius=" + refl(vi, "getRadius")
                    + " bitmap=" + (bmp == null ? "null(⚠会退化成alpha淡入)" : "ok")
                    + "}";
        } catch (Throwable t) {
            return "<" + t + ">";
        }
    }

    /**
     * `HyperViewScaleAnimationImpl` 反向动画真正依赖的东西。
     *
     * 反汇编 `reverseAnimationWhenMerge` 的第一个 gate 就是
     * `if (!mIsRunning) return false;` —— 动画要是已经跑完了，
     * 不管我们怎么改 A/B 参数它都只会返回 false，然后
     * `handleMergeAnimation` 走 `finishAnimation()` 那条路 = 硬切。
     * 所以每次都要把 mIsRunning 打出来，好区分"没反向"到底是哪个门槛没过。
     */
    private static String describeAnimInfo(Object impl) {
        if (impl == null) {
            return "animInfo=?";
        }
        Object ai = fieldValue(impl, "mAnimInfo");
        if (ai == null) {
            return "animInfo=null";
        }
        // ViewScaleAnimInfo 的这些值都是**公开字段**（反汇编里全是 iget，不是 getter）
        return "from{cx=" + fieldValue(ai, "centerFromX")
                + " cy=" + fieldValue(ai, "centerFromY")
                + " ratio=" + fieldValue(ai, "ratioFrom")
                + " w=" + fieldValue(ai, "widthFrom")
                + " h=" + fieldValue(ai, "heightFrom")
                + " corner=" + fieldValue(ai, "fromCorner")
                + " maskA=" + fieldValue(ai, "maskAlphaFrom")
                + " valid=" + refl(ai, "isValid") + "}";
    }

    private static Object fieldValue(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 源视图看护

    /**
     * 启动被接受之后，隔几个时间点看源视图还在不在。
     *
     * **关闭动画的前提**：源视图必须一直 attached 且能算 bounds
     * （`HyperViewScaleRemoteTransition.startAnimation` 里要
     *  `View.isAttachedToWindow()` + `getBoundsOnScreen()` + `RenderNode.addPositionUpdateListener`）。
     * 如果负一屏在 App 打开后把卡片回收了，关闭就只能 alpha 淡出。
     */
    public static void watchSource(final View v, final String target) {
        if (!Config.ENABLE_HVS_DIAG || !Config.HVS_WATCH_SOURCE || v == null) {
            return;
        }
        final int[] delays = {0, 250, 1000, 3000, 6000};
        for (final int d : delays) {
            post(new Runnable() {
                @Override
                public void run() {
                    try {
                        // getBoundsOnScreen / isVisibleToUser / getRenderNode 都是 @hide，
                        // 公开 android.jar 里没有 —— 只能反射调。
                        Rect r = new Rect();
                        Object boundsOk = XposedHelpers.callMethod(v, "getBoundsOnScreen", r);
                        Object visible = XposedHelpers.callMethod(v, "isVisibleToUser");
                        Object node = XposedHelpers.callMethod(v, "getRenderNode");
                        L.i("[HVS看护] +" + d + "ms 目标=" + target
                                + " view=" + v.getClass().getSimpleName()
                                + " attached=" + v.isAttachedToWindow()
                                + " visibleToUser=" + visible
                                + " vis=" + v.getVisibility()
                                + " alpha=" + L.f(v.getAlpha())
                                + " boundsOk=" + boundsOk
                                + " bounds=" + r
                                + " renderNode=" + (node != null));
                    } catch (Throwable t) {
                        L.w("[HVS看护] +" + d + "ms 读取失败: " + t);
                    }
                }
            }, d);
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static boolean verbose() {
        return Config.LOG_HVS_VERBOSE;
    }

    private static String dump(Bundle b) {
        if (b == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("{");
        for (String k : b.keySet()) {
            Object v = b.get(k);
            sb.append(sb.length() > 1 ? ", " : "").append(k).append('=').append(v);
        }
        return sb.append('}').toString();
    }

    private static void post(Runnable r, long delayMs) {
        try {
            new Handler(Looper.getMainLooper()).postDelayed(r, delayMs);
        } catch (Throwable t) {
            L.w("[HVS] post 失败: " + t);
        }
    }
}
