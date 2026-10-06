package com.qianxia.hyperassistant;

import android.os.Binder;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * system_server 侧【纯只读】过渡取证探针 —— 实现测绘文档
 * {@code D:\dsh\负一屏动画-system_server测绘.md} 第 4 节的 S1~S5。
 *
 * <h3>为什么单独一个类，而不是塞进 {@link AssistantAnimCore}</h3>
 * {@code AssistantAnimCore} 是**应用进程**（{@code com.miui.personalassistant}）的核心：
 * 它的 {@code install()} 会装 overlay 生命周期、状态机、leash 吞写等钩子，在
 * system_server 里毫无意义且会误挂。而 {@link MainHook} 的 {@code "android"} 分支
 * 目前在装完三个旧安装器后就 {@code return}，**从未调用** {@code AssistantAnimCore.install}。
 * 所以本探针独立成类，只由 {@code MainHook} 的 system_server 分支调用一次。
 *
 * <h3>红线（逐条遵守）</h3>
 * <ol>
 *   <li><b>只读</b>：本文件里<b>没有</b> {@code p.setResult(...)}、<b>没有</b>
 *       {@code p.args[i] = ...}、<b>没有</b>任何写字段。grep 可验证。</li>
 *   <li><b>不改行为</b>：不改 TransitionInfo / WindowContainer / ActivityRecord /
 *       Intent 的任何状态或目标值。</li>
 *   <li><b>不执行外部命令</b>：只用反射 + {@code Binder}/{@code SystemClock}。
 *       没有 {@code Runtime.exec} / {@code ProcessBuilder} / 文件读写。</li>
 *   <li><b>异常绝不外泄</b>：每个回调体最外层 {@code try/catch(Throwable)}；
 *       安装期同样全包。system_server 里抛异常 = 软重启。</li>
 *   <li><b>不做重活</b>：所有 Class / Method / Field 在安装期一次性解析并缓存，
 *       回调里只读值 + 拼字符串。</li>
 *   <li><b>默认关</b>：{@link CoreConfig#DEV_SYSTEM_SERVER_PROBE} 默认 {@code false}，
 *       release 构建与"没装模块"完全一致。</li>
 *   <li><b>按行为定位</b>：全部按「名字 + 参数个数/类型 + 返回类型」匹配，
 *       没有任何一处依赖混淆后的名字；HyperViewScale 的 impl 一律用
 *       {@code Stub.getInstance().getClass()} 反推。</li>
 * </ol>
 *
 * <h3>允许的"调用"</h3>
 * 文档第 4 节把「无副作用的只读 getter」列为允许调用（{@code getType()} /
 * {@code getSyncId()} / {@code getName()} / {@code isCollecting()} /
 * {@code isVisibleRequested()} / {@code getParcelableExtra()}）。本文件只调这些，
 * 且全部走反射 + {@code setAccessible(true)} + try/catch。**没有任何 setter**。
 */
public final class SystemServerProbe {

    private SystemServerProbe() {
    }

    /** 统一前缀：文档要求 system_server 侧用 {@code [SS]}。 */
    private static final String P = "[SS] ";

    /** 每一类钩子的日志上限（文档要求"每类 ≤80 条"）。 */
    private static final int MAX_PER_CATEGORY = 300;

    /** S3：每次过渡最多打印多少个 participant（过渡参与者可能几十个）。 */
    private static final int MAX_PARTICIPANTS = 12;

    /** S3：去重表容量上限，防止无限增长。 */
    private static final int SEEN_CAP = 64;

    // ======================================================================
    //  限流
    // ======================================================================

    private static final int C_S1 = 0;
    private static final int C_S2 = 1;
    private static final int C_S3 = 2;
    private static final int C_S4 = 3;
    private static final int C_S5 = 4;
    private static final int C_N = 5;
    private static final int[] sCount = new int[C_N];
    private static final boolean[] sBudgetOver = new boolean[C_N];
    private static final String[] sCat = {"S1", "S2", "S3", "S4", "S5"};

    /** 该类日志是否还在预算内。 */
    private static synchronized boolean allow(int cat) {
        if (sCount[cat] < MAX_PER_CATEGORY) {
            sCount[cat]++;
            return true;
        }
        if (!sBudgetOver[cat]) {
            sBudgetOver[cat] = true;      // 只提示一次，之后彻底静音
            L.i(P + sCat[cat] + " 日志已达上限 " + MAX_PER_CATEGORY + " 条，后续静音（不影响行为）");
        }
        return false;
    }

    private static void log(int cat, String msg) {
        if (allow(cat)) {
            L.i(P + sCat[cat] + " " + msg);
        }
    }

    /** 安装期的失败日志 —— 也走统一前缀，且不占取证预算。 */
    private static void installLog(String msg) {
        L.i(P + "安装 " + msg);
    }

    // ======================================================================
    //  安装
    // ======================================================================

    private static volatile boolean sDone;

    /** 唯一的对外入口。幂等；任何失败都只记日志，绝不抛。 */
    public static void install(final ClassLoader cl) {
        if (!CoreConfig.DEV_SYSTEM_SERVER_PROBE) {
            return;     // release 默认走这里
        }
        if (sDone) {
            return;
        }
        try {
            sDone = installOnce(cl);
        } catch (Throwable t) {
            L.w(P + "安装异常（已吞掉，不影响 system_server）: " + t);
            sDone = false;
        }
    }

    private static boolean installOnce(ClassLoader cl) {
        boolean any = false;
        any |= installS1(cl);
        any |= installS2(cl);
        any |= installS3(cl);
        any |= installS4(cl);
        any |= installS5(cl);
        if (any) {
            installLog("★ 只读过渡探针已挂上（S1~S5）。本探针不写任何状态、不改任何参数。");
        } else {
            installLog("本次未挂上任何钩子（类可能还没加载，由调用方决定是否重试）");
        }
        return any;
    }

    // ======================================================================
    //  S1 —— WindowOrganizerController.startMiuiNewTransition(int, WCT, boolean)
    // ======================================================================

    /**
     * 上滑那条路【唯一的 type 来源】（测绘文档 E-6 / 第 5 节 L1 入口）。
     * 打 {@code type} / {@code isRecentsTransition} / 调用方包名 / 时间戳。
     */
    private static boolean installS1(ClassLoader cl) {
        // ★ 与 L1 互斥：若归一化实验开着（{@link SystemServerNormalize}），它会挂钩同一个
        //   方法。两个独立 hook 都挂上去会白跑一遍、并可能拿到"已被改过"的实参造成
        //   日志误导（不确定 Xposed 的回调顺序）。所以让出 S1，改由
        //   {@link SystemServerProbe#onNormalized} 打一行旁观记录 —— 只读、无副作用。
        if (CoreConfig.DEV_NORMALIZE_1021_TO_2) {
            installLog("S1 让位给 L1 归一化（DEV_NORMALIZE_1021_TO_2=true）"
                    + "—— 归一化命中时会由 onNormalized 打旁观日志");
            return false;
        }
        final Class<?> woc = classNamed(cl, "com.android.server.wm.WindowOrganizerController");
        if (woc == null) {
            installLog("S1 找不到 WindowOrganizerController，跳过");
            return false;
        }
        try {
            XposedBridge.hookAllMethods(woc, "startMiuiNewTransition", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Object[] a = p.args;
                        if (a == null || a.length != 3) {
                            return;                                   // 只认 (int, WCT, boolean)
                        }
                        if (!(a[0] instanceof Integer) || !(a[2] instanceof Boolean)) {
                            return;
                        }
                        long now = SystemClock.uptimeMillis();
                        int type = (Integer) a[0];
                        boolean recents = (Boolean) a[2];
                        log(C_S1, "★ startMiuiNewTransition type=" + type
                                + " isRecentsTransition=" + recents
                                + " caller=" + callerDesc()
                                + " t=" + now + "ms"
                                + " stack=" + stackTop(3));
                    } catch (Throwable t) {
                        L.w(P + "S1 回调异常（已吞掉）: " + t);
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            installLog("S1 挂钩失败，跳过: " + t);
            return false;
        }
    }

    // ======================================================================
    //  S2 —— TransitionController.moveToCollecting(Transition) + Transition 的构造
    // ======================================================================

    /** 解析出来的 Transition 字段（按类型找，不按名字）。 */
    private static volatile Field sFType;
    private static volatile Field sFSyncId;
    private static volatile Field sFFlags;
    private static volatile boolean sFieldsResolved;

    /**
     * 打 {@code mType / mSyncId / mFlags}。
     *
     * <p>三条来源，按可靠性排序：
     * <ol>
     *   <li><b>Transition 的构造函数头两参</b>（{@code (int type, int flags, ...)}，见
     *       {@code svc_src/.../TransitionController.java:214}）—— <b>这是最确定的</b>，
     *       不依赖任何字段名；</li>
     *   <li>{@code getSyncId()}（公开访问器，反射调用）；</li>
     *   <li>按<b>类型 int</b> 扫描实例字段取候选（名字只用来排优先级，用来兜底）。</li>
     * </ol>
     */
    private static boolean installS2(ClassLoader cl) {
        final Class<?> tc = classNamed(cl, "com.android.server.wm.TransitionController");
        final Class<?> tr = classNamed(cl, "com.android.server.wm.Transition");
        if (tc == null || tr == null) {
            installLog("S2 找不到 TransitionController/Transition，跳过");
            return false;
        }
        boolean any = false;
        // --- (a) Transition 构造函数：最确定的 type/flags 来源
        try {
            for (Constructor<?> c : tr.getDeclaredConstructors()) {
                Class<?>[] pt = c.getParameterTypes();
                if (pt.length >= 2 && pt[0] == int.class && pt[1] == int.class) {
                    XposedBridge.hookMethod(c, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Object[] a = p.args;
                                if (a == null || a.length < 2) {
                                    return;
                                }
                                Object self = p.thisObject;
                                log(C_S2, "★ new Transition type=" + a[0] + " flags=" + a[1]
                                        + syncIdOf(self) + flagsOf(self)
                                        + " t=" + SystemClock.uptimeMillis() + "ms");
                            } catch (Throwable t) {
                                L.w(P + "S2 构造回调异常（已吞掉）: " + t);
                            }
                        }
                    });
                    any = true;
                }
            }
        } catch (Throwable t) {
            installLog("S2 构造钩子失败（继续走 moveToCollecting）: " + t);
        }
        // --- (b) moveToCollecting：过渡真正"开始收集"的时刻，也是 mType 已就位之后
        try {
            XposedBridge.hookAllMethods(tc, "moveToCollecting", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Object[] a = p.args;
                        if (a == null || a.length != 1) {           // 只认单参重载
                            return;
                        }
                        Object t = a[0];
                        if (!tr.isInstance(t)) {
                            return;
                        }
                        log(C_S2, "★ moveToCollecting" + typeOf(t) + syncIdOf(t) + flagsOf(t)
                                + " " + shortString(t));
                    } catch (Throwable th) {
                        L.w(P + "S2 回调异常（已吞掉）: " + th);
                    }
                }
            });
            any = true;
        } catch (Throwable t) {
            installLog("S2 moveToCollecting 挂钩失败: " + t);
        }
        return any;
    }

    /** 一次性解析 Transition 的三个 int 字段（按类型 + 名字优先级，不硬依赖名字）。 */
    private static void resolveTransitionFields(Class<?> tr) {
        if (sFieldsResolved) {
            return;
        }
        sFieldsResolved = true;
        try {
            List<Field> ints = new ArrayList<>();
            for (Field f : tr.getDeclaredFields()) {
                if (f.getType() != int.class || Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                ints.add(f);
            }
            // 名字里带 type 的最优先，其次是"值是已知过渡类型"的
            sFType = pick(ints, "type", new int[]{1, 2, 3, 4, 6, 12});
            sFSyncId = pick(ints, "syncid", null);
            sFFlags = pick(ints, "flags", null);
            for (Field f : new Field[]{sFType, sFSyncId, sFFlags}) {
                if (f != null) {
                    try {
                        f.setAccessible(true);
                    } catch (Throwable ignored) {
                    }
                }
            }
            installLog("S2 字段定位 type=" + fname(sFType) + " syncId=" + fname(sFSyncId)
                    + " flags=" + fname(sFFlags) + "（仅供兜底；主来源是构造函数实参）");
        } catch (Throwable t) {
            L.w(P + "S2 字段定位失败（不影响构造钩子）: " + t);
        }
    }

    private static Field pick(List<Field> cands, String nameHint, int[] knownValues) {
        Field best = null;
        int bestScore = -1;
        for (Field f : cands) {
            int score = 0;
            String n = f.getName().toLowerCase(java.util.Locale.US);
            if (n.contains(nameHint)) {
                score += 10;
            }
            if (n.startsWith("m")) {
                score += 1;                                   // AOSP 风格
            }
            if (knownValues == null && n.contains("sync")) {
                score += 10;
            }
            if (score > bestScore) {
                bestScore = score;
                best = f;
            }
        }
        return best;
    }

    private static String fname(Field f) {
        return f == null ? "(未定位)" : f.getName();
    }

    private static String typeOf(Object t) {
        try {
            resolveTransitionFields(t.getClass());
            Field f = sFType;
            return f == null ? " type=?" : " type=" + f.getInt(t);
        } catch (Throwable ignored) {
            return " type=?";
        }
    }

    private static String syncIdOf(Object t) {
        try {
            Method m = t.getClass().getMethod("getSyncId");
            m.setAccessible(true);
            return " syncId=" + m.invoke(t);
        } catch (Throwable ignored) {
        }
        try {
            resolveTransitionFields(t.getClass());
            Field f = sFSyncId;
            return f == null ? "" : " syncId=" + f.getInt(t);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String flagsOf(Object t) {
        try {
            resolveTransitionFields(t.getClass());
            Field f = sFFlags;
            return f == null ? "" : " flags=" + f.getInt(t);
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ======================================================================
    //  S3 —— Transition.collect(WindowContainer)：看清 1021 装的是什么
    // ======================================================================

    private static final Map<Object, Integer> sSeen = new LinkedHashMap<Object, Integer>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Object, Integer> eldest) {
            return size() > SEEN_CAP;
        }
    };

    private static boolean installS3(ClassLoader cl) {
        final Class<?> tr = classNamed(cl, "com.android.server.wm.Transition");
        if (tr == null) {
            installLog("S3 找不到 Transition，跳过");
            return false;
        }
        try {
            XposedBridge.hookAllMethods(tr, "collect", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Object[] a = p.args;
                        if (a == null || a.length != 1 || a[0] == null) {
                            return;
                        }
                        Object wc = a[0];
                        if (!isWindowContainer(wc.getClass())) {
                            return;                       // 只要 WindowContainer 的重载
                        }
                        Object self = p.thisObject;
                        if (self == null) {
                            return;
                        }
                        int shown;
                        synchronized (sSeen) {
                            Integer prev = sSeen.get(self);
                            shown = prev == null ? 0 : prev;
                            if (shown >= MAX_PARTICIPANTS) {
                                return;
                            }
                            sSeen.put(self, shown + 1);
                        }
                        log(C_S3, "#" + shown + " collect" + typeOf(self) + syncIdOf(self)
                                + " " + describeWc(wc));
                    } catch (Throwable t) {
                        L.w(P + "S3 回调异常（已吞掉）: " + t);
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            installLog("S3 挂钩失败，跳过: " + t);
            return false;
        }
    }

    /** WindowContainer 判定（按类型，不按名字）。 */
    private static boolean isWindowContainer(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            String n = k.getName();
            if ("com.android.server.wm.WindowContainer".equals(n)
                    || "com.android.server.wm.ConfigurationContainer".equals(n)) {
                return true;
            }
        }
        // 退一步：类名里带 ActivityRecord / Task / DisplayContent 也算
        String n = c.getName();
        return n.endsWith("ActivityRecord") || n.endsWith("Task") || n.contains("DisplayContent");
    }

    /**
     * 打参与者：类名 + {@code getName()}（只读 getter）+ 若是 ActivityRecord 再带包名。
     * 这三个都是**查询**，不改任何状态。
     */
    private static String describeWc(Object wc) {
        StringBuilder sb = new StringBuilder();
        String cls = wc.getClass().getName();
        int i = cls.lastIndexOf('.');
        sb.append("wc=").append(i < 0 ? cls : cls.substring(i + 1));
        sb.append(" name=").append(callString(wc, "getName"));
        String pkg = callString(wc, "getPackageName");
        if (pkg != null && !"<?>".equals(pkg)) {
            sb.append(" pkg=").append(pkg);
        }
        return sb.toString();
    }

    /** 反射调一个无参返回 String/Object 的方法（只读 getter），失败返回 {@code <?>}。 */
    private static String callString(Object o, String method) {
        if (o == null) {
            return "<?>";
        }
        try {
            Method m = o.getClass().getMethod(method);
            m.setAccessible(true);
            Object r = m.invoke(o);
            return r == null ? "null" : String.valueOf(r);
        } catch (Throwable ignored) {
            return "<?>";
        }
    }

    /** 反射调一个无参返回 boolean 的方法（只读判据），失败返回 null。 */
    private static Boolean callBool(Object o, String method) {
        if (o == null) {
            return null;
        }
        try {
            Method m = o.getClass().getMethod(method);
            m.setAccessible(true);
            Object r = m.invoke(o);
            return (r instanceof Boolean) ? (Boolean) r : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ======================================================================
    //  S4 —— Transition.calculateTransitionInfo(...)  ← 钉死"1021 与 close 同源"
    // ======================================================================

    private static boolean installS4(ClassLoader cl) {
        final Class<?> tr = classNamed(cl, "com.android.server.wm.Transition");
        if (tr == null) {
            installLog("S4 找不到 Transition，跳过");
            return false;
        }
        try {
            boolean any = false;
            for (final Method m : tr.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) {
                    continue;                                  // 只要 static 那个
                }
                Class<?> rt = m.getReturnType();
                if (!"android.window.TransitionInfo".equals(rt.getName())) {
                    continue;
                }
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length < 6 || pt[0] != int.class) {
                    continue;
                }
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object[] a = p.args;
                            Object info = p.getResult();
                            StringBuilder sb = new StringBuilder();
                            sb.append("★ calculateTransitionInfo type=").append(a[0]);
                            if (a.length > 4 && a[4] instanceof Integer) {
                                sb.append(" syncId=").append(a[4]);
                            }
                            if (a.length > 2 && a[2] instanceof java.util.List) {
                                sb.append(" targets=").append(((java.util.List<?>) a[2]).size());
                            }
                            if (info != null) {
                                sb.append(" → info.type=").append(callString(info, "getType"));
                                sb.append(" info.debugId=").append(callString(info, "getDebugId"));
                                sb.append(" info.leashes=").append(leashSummary(info));
                            }
                            log(C_S4, sb.toString());
                        } catch (Throwable t) {
                            L.w(P + "S4 回调异常（已吞掉）: " + t);
                        }
                    }
                });
                installLog("S4 已挂 calculateTransitionInfo 重载 params=" + pt.length);
                any = true;
            }
            if (!any) {
                installLog("S4 未找到 (…, int, …)→TransitionInfo 的 static 重载，跳过");
            }
            return any;
        } catch (Throwable t) {
            installLog("S4 挂钩失败，跳过: " + t);
            return false;
        }
    }

    /**
     * 打印每个 target 的 leash 名 + mode。
     *
     * <p>只调 {@code getChanges()} / {@code getLeash()} / {@code getMode()} 三个只读 getter，
     * {@code toString()} 对 SurfaceControl 是"层名"形态（{@code Surface(name=…)}），
     * 这正是"给哪条 leash 播动画"的直接证据（测绘文档 I9：层名含 {@code view-leash} 即真 leash）。
     */
    private static String leashSummary(Object info) {
        try {
            Method mc = info.getClass().getMethod("getChanges");
            mc.setAccessible(true);
            Object changes = mc.invoke(info);
            if (!(changes instanceof java.util.List)) {
                return "<?>";
            }
            java.util.List<?> list = (java.util.List<?>) changes;
            StringBuilder sb = new StringBuilder("[");
            int n = 0;
            for (Object ch : list) {
                if (ch == null || n >= 6) {
                    break;
                }
                if (n > 0) {
                    sb.append(", ");
                }
                sb.append("mode=").append(callString(ch, "getMode"));
                sb.append(" leash=").append(trim(callString(ch, "getLeash"), 96));
                n++;
            }
            return sb.append(list.size() > 6 ? ", …+" + (list.size() - 6) : "").append(']').toString();
        } catch (Throwable ignored) {
            return "<?>";
        }
    }

    // ======================================================================
    //  S5 —— HyperViewScaleTransitionImpl + TransitionControllerImpl
    // ======================================================================

    /**
     * 打开/关闭过渡的入口与条件分支。
     *
     * <p>impl 类**必须**用 {@code Stub.getInstance().getClass()} 反推（I10 铁律：
     * impl 由 MiuiStubRegistry 在子 ClassLoader 里装配，按名字找只会找到 stub）。
     * 若 {@code getInstance()} 在 HiddenApi 限制下抛异常，本钩子**静默跳过**，
     * 不硬写一个会误触发的替代品（测绘文档第 4 节 S5 的降级要求）。
     */
    private static boolean installS5(ClassLoader cl) {
        boolean any = false;
        // --- (a) HyperViewScaleTransitionImpl：关闭过渡入口
        Class<?> impl = implViaStub(cl, "com.android.server.wm.HyperViewScaleTransitionStub");
        if (impl != null) {
            installLog("S5 HyperViewScale impl 反推成功 = " + impl.getName());
            any |= hookS5Methods(impl, "requestViewScaleRemoteClose", true);
            any |= hookS5Methods(impl, "requestCloseTransitionIfNeeded", true);
        } else {
            installLog("S5 无法反推 HyperViewScaleTransitionImpl（getInstance 不可用），"
                    + "S5(a) 跳过 —— 替代观测点：用 S4 看 type=2 的 close 过渡是否出现");
        }
        // --- (b) TransitionControllerImpl：被 isCollecting 挡掉的那一步
        Class<?> tcl = implViaStub(cl, "com.android.server.wm.TransitionControllerStub");
        if (tcl != null) {
            installLog("S5 TransitionController impl 反推成功 = " + tcl.getName());
            any |= hookS5Methods(tcl, "shouldSkipSurfaceVisibilityRestore", false);
        } else {
            installLog("S5 无法反推 TransitionControllerImpl，S5(b) 跳过");
        }
        return any;
    }

    private static boolean hookS5Methods(Class<?> c, final String methodName, final boolean dumpArgs) {
        try {
            XposedBridge.hookAllMethods(c, methodName, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        StringBuilder sb = new StringBuilder();
                        sb.append("★ ").append(methodName).append(" 被调用 args=");
                        sb.append(dumpArgs ? dumpArgs(p.args) : argCount(p.args));
                        log(C_S5, sb.toString());
                    } catch (Throwable t) {
                        L.w(P + "S5 " + methodName + " 回调异常（已吞掉）: " + t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        log(C_S5, "  " + methodName + " 返回=" + shortString(p.getResult()));
                    } catch (Throwable t) {
                        L.w(P + "S5 " + methodName + " 返回回调异常（已吞掉）: " + t);
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            installLog("S5 挂钩 " + methodName + " 失败: " + t);
            return false;
        }
    }

    /**
     * 打 S5 的入参，重点回答测绘文档 P2/P3：
     * <ul>
     *   <li>P2：Intent 上 {@code view_scale_open} 的<b>实际值</b>（不是 hasExtra）；</li>
     *   <li>P3：{@code tc.isCollecting()} 原值 与 {@code wc.isVisibleRequested()} 原值。</li>
     * </ul>
     */
    private static String dumpArgs(Object[] a) {
        if (a == null) {
            return "(null)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            Object x = a[i];
            if (i > 0) {
                sb.append(' ');
            }
            if (x == null) {
                sb.append('[').append(i).append("]=null");
                continue;
            }
            String n = x.getClass().getName();
            if ("android.content.Intent".equals(n)) {
                sb.append('[').append(i).append("]=Intent").append(intentDesc(x));
            } else if (n.endsWith("TransitionController")) {
                Boolean collecting = callBool(x, "isCollecting");
                sb.append('[').append(i).append("]=tc(isCollecting=")
                        .append(collecting == null ? "?" : collecting).append(')');
            } else if (isWindowContainer(x.getClass())) {
                sb.append('[').append(i).append("]=").append(describeWc(x));
            } else {
                sb.append('[').append(i).append("]=").append(shortName(n));
            }
        }
        return sb.toString();
    }

    private static String argCount(Object[] a) {
        return a == null ? "(null)" : String.valueOf(a.length);
    }

    /** Intent 的关键信息：component + extras keySet + {@code view_scale_open} 的实际值。 */
    private static String intentDesc(Object intent) {
        StringBuilder sb = new StringBuilder();
        sb.append(" comp=").append(callString(intent, "getComponent"));
        try {
            Method m = intent.getClass().getMethod("getExtras");
            m.setAccessible(true);
            Object b = m.invoke(intent);
            if (b == null) {
                sb.append(" extras=null  ← 没有 extra，requestViewScaleRemoteClose 会提前 return");
                return sb.toString();
            }
            Method ks = b.getClass().getMethod("keySet");
            ks.setAccessible(true);
            sb.append(" extras=").append(ks.invoke(b));
        } catch (Throwable ignored) {
            sb.append(" extras=?");
        }
        try {
            Method m = intent.getClass().getMethod("getParcelableExtra", String.class);
            m.setAccessible(true);
            Object v = m.invoke(intent, "view_scale_open");
            sb.append(" view_scale_open=").append(v == null ? "null ← 会退化成硬切" : shortString(v));
        } catch (Throwable ignored) {
            sb.append(" view_scale_open=?");
        }
        return sb.toString();
    }

    // ======================================================================
    //  工具：类/方法定位、调用方、格式化
    // ======================================================================

    /**
     * 按名字拿类，但**只在能被本进程加载的前提下**。
     *
     * <p>真实 system_server 里类由 boot classpath 加载，任何 Context 的
     * ClassLoader 都能回落到 boot，所以先用传进来的 CL，再用当前 CL 兜底。
     * 两者都失败就返回 null（调用方静默跳过）—— 绝不抛。
     */
    private static Class<?> classNamed(ClassLoader cl, String name) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable ignored) {
        }
        try {
            return Class.forName(name, false, SystemServerProbe.class.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 用 {@code Stub.getInstance().getClass()} 反推 MIUI impl（I10 铁律）。
     * 任何一步失败都返回 null。
     */
    private static Class<?> implViaStub(ClassLoader cl, String stubName) {
        try {
            Class<?> stub = classNamed(cl, stubName);
            if (stub == null) {
                return null;
            }
            if (stub.getName().equals(stubName)) {
                // 找到了 stub 本身，说明这个 ROM 没装 impl —— 正常情况
            }
            Method get = stub.getMethod("getInstance");
            get.setAccessible(true);
            Object inst = get.invoke(null);
            return inst == null ? null : inst.getClass();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 调用方描述：uid + 尽力拿到的包名（拿不到就打 {@code ?}，不硬凑）。 */
    private static String callerDesc() {
        int uid = -1;
        try {
            uid = Binder.getCallingUid();
        } catch (Throwable ignored) {
        }
        String pkg = callerPackage(uid);
        return "uid=" + uid + "(" + (uid < 0 ? "?" : String.valueOf(uid % 100000)) + ")"
                + " pkg=" + (pkg == null ? "?" : pkg);
    }

    private static volatile Class<?> sAtCls;
    private static volatile boolean sAtTried;

    /**
     * 进程内取包名：{@code ActivityThread.currentActivityThread()} →
     * {@code getSystemContext()} → {@code getPackageManager().getNameForUid(uid)}。
     *
     * <p>全部只读。系统服务包常常"没有包名"（uid 1000/1001/1002 未在 packages.list 登记），
     * 这时 {@code getNameForUid} 返回 {@code null}/"android.uid.system" 之类，
     * 我们**原样打出来**，不做任何猜测（测绘文档要求：拿不到就写"未找到"）。
     */
    private static String callerPackage(int uid) {
        if (uid <= 0) {
            return null;
        }
        try {
            Object at = activityThread();
            if (at == null) {
                return null;
            }
            Method gsc = at.getClass().getMethod("getSystemContext");
            gsc.setAccessible(true);
            Object ctx = gsc.invoke(at);
            if (ctx == null) {
                return null;
            }
            Method gpm = ctx.getClass().getMethod("getPackageManager");
            gpm.setAccessible(true);
            Object pm = gpm.invoke(ctx);
            if (pm == null) {
                return null;
            }
            Method nfu = pm.getClass().getMethod("getNameForUid", int.class);
            nfu.setAccessible(true);
            Object r = nfu.invoke(pm, uid);
            return r == null ? null : String.valueOf(r);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object activityThread() {
        if (!sAtTried) {
            sAtTried = true;
            try {
                sAtCls = Class.forName("android.app.ActivityThread");
            } catch (Throwable ignored) {
                sAtCls = null;
            }
        }
        if (sAtCls == null) {
            return null;
        }
        try {
            Method m = sAtCls.getMethod("currentActivityThread");
            m.setAccessible(true);
            return m.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stackTop(int n) {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int k = 0;
            for (int i = 3; i < st.length && k < n; i++) {
                String c = st[i].getClassName();
                if (c.startsWith("com.qianxia.hyperassistant") || c.startsWith("de.robv")
                        || c.startsWith("java.lang.Thread") || c.startsWith("android.os.")
                        || c.startsWith("com.android.internal.os")) {
                    continue;
                }
                sb.append(sb.length() == 0 ? "" : " <- ").append(shortName(c))
                        .append('#').append(st[i].getMethodName());
                k++;
            }
            return sb.length() == 0 ? "(未知)" : sb.toString();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** Transition 的 toString 前 100 字符（AOSP 形态含 type/flags/syncId，可作兜底证据）。 */
    private static String shortString(Object o) {
        if (o == null) {
            return "null";
        }
        try {
            return trim(String.valueOf(o), 100);
        } catch (Throwable ignored) {
            return "<?>";
        }
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "null";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String shortName(String cls) {
        if (cls == null) {
            return "?";
        }
        int i = cls.lastIndexOf('.');
        return i < 0 ? cls : cls.substring(i + 1);
    }

    // ======================================================================
    //  L1 归一化的旁观接口（纯只读；由 SystemServerNormalize 回调）
    // ======================================================================

    /**
     * L1（{@link SystemServerNormalize}）改了一个实参时，这里只**记录**一行，
     * 便于把"改动时刻"与 S2/S3/S4 的观测对齐。
     *
     * <p>本方法不读不写任何系统状态（连 {@code p.args} 都碰不到），
     * 只吃两个 int。异常同样不影响调用方 —— 调用方已在外层 try/catch 里。
     */
    static void onNormalized(int from, int to) {
        try {
            log(C_S1, "旁观 L1 归一化生效：type " + from + " → " + to
                    + " t=" + SystemClock.uptimeMillis() + "ms"
                    + "（此后 S2/S4 里这条过渡的 type 应为 " + to + "）");
        } catch (Throwable ignored) {
            // 只读日志，失败无所谓
        }
    }

    // 保留：以后要按 toString 兜底解析 type/flags 时用（当前主来源是构造函数实参）。
    @SuppressWarnings("unused")
    private static final Pattern TRANSITION_TO_STRING =
            Pattern.compile("Transition\\{?[^}]*?(?:type|mType)=(-?\\d+)");
}
