package com.qianxia.hyperassistant;

import android.os.Binder;
import android.os.SystemClock;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * system_server 侧【L1】实验：把「上滑返回」那条过渡的 {@code type=1021} 窄归一化成
 * {@code 2}（TRANSIT_CLOSE），让应用侧的 {@code HyperViewScaleRemoteTransition}
 * 自己算出 {@code mReverseToActivityA=true}，从而走小米已有的反向动画分支。
 *
 * <p>测绘依据：{@code D:\dsh\负一屏动画-system_server测绘.md} 第 5 节 L1 与第 7 节 E-3/E-6/E-7。
 *
 * <h3>为什么单独一个类 + 与 L0 的关系</h3>
 * L0（{@link SystemServerProbe} 的 S1~S5）只读，用来**先拿到 P4 的真机证据**；
 * 本类是 L1，**默认关**（{@link CoreConfig#DEV_NORMALIZE_1021_TO_2}）。
 * 两者可同时开：{@link SystemServerProbe} 会让出 S1（避免同一方法被挂两次），
 * 并通过 {@link SystemServerProbe#onNormalized} 打一行旁观记录。
 *
 * <h3>唯一的改动点（不可扩大）</h3>
 * 只改 {@code WindowOrganizerController.startMiuiNewTransition(int type,
 * WindowContainerTransaction, boolean isRecentsTransition)} 的**第一个实参**，
 * 且命中收窄条件时才改。<b>绝不</b>改 {@code Transition} 的构造、<b>绝不</b>全局改
 * 任何 {@code mType} 字段 —— {@code mType==1021} 在别处也被读
 * （{@code TransitionControllerImpl.shouldSkipSurfaceVisibilityRestore}，测绘文档 E-4）。
 *
 * <h3>★ 收窄条件：实测「起始包」拿不到，用父代理指定的替代条件</h3>
 * 有效条件 = <b>{@code type==1021 && isRecentsTransition==true}</b>。
 * 逐条证据与原因见 {@link #decide}。
 *
 * <h3>安全</h3>
 * <ol>
 *   <li>整个 hook 体 {@code try/catch(Throwable)}，异常只 {@code L.w}，绝不外抛；</li>
 *   <li>不做重活：判决只读两个已反序列化实参 + 一个主线程缓存的 uid；</li>
 *   <li>不调任何系统方法（只用 {@code Binder.getCallingUid()}，本地静态查询）；
 *       不写文件；不起进程；</li>
 *   <li>同一个 500ms bucket 内的重复通知只归一化一次（去重）；</li>
 *   <li>命中/跳过各有一行 {@code [SS] L1} 日志，限流 80 条。</li>
 * </ol>
 */
public final class SystemServerNormalize {

    private SystemServerNormalize() {
    }

    private static final String P = "[SS] L1 ";

    /** L1 自己的日志上限。 */
    private static final int MAX_LOG = 80;
    private static int sLogCount;
    private static boolean sBudgetOver;

    /** 归一化成功次数（供日志里的序号）。 */
    private static int sNormalized;

    /** 去重：同一个 bucket 只归一化一次。 */
    private static final long BUCKET_MS = 500L;
    private static volatile long sLastBucket = -1L;

    // ------------------------------------------------------------------ ★ 保险阀
    //
    //  用户批准的"自动过期"：L1 只在重启后的一个有限窗口里生效，
    //  避免万一这条归一化在别处（最近任务 / 桌面）有副作用而长期存在。
    //  窗口同时受"时长"和"次数"两个上限约束，先到者为准。

    /**
     * ★ 保险阀①：从挂钩成功起算，L1 最多生效这么久。
     *
     * <p>取 30 分钟而不是 10 分钟：窗口是从 <b>system_server 挂钩成功</b>（开机早期）算起的，
     * 而真机上一轮实验还要先重启、重开无线调试、重连 adb 才能开始测 —— 10 分钟会被这些
     * 协调开销吃掉，导致"窗口还没测就过期"。真正的硬约束是 {@link #MAX_SHOTS}（次数），
     * 时长只用来兜住"忘了关"的情况。
     */
    private static final long WINDOW_MS = 30 * 60 * 1000L;
    /** ★ 保险阀②：最多归一化这么多次。 */
    private static final int MAX_SHOTS = 30;
    /** 挂钩成功的时刻（{@code uptimeMillis}）；<0 = 还没挂钩成功。 */
    private static volatile long sArmedAt = -1L;
    /** "已过期"那行日志只打一次。 */
    private static volatile boolean sExpiredNoted;

    // 调用方身份（判决期只读 int；包名只用于打印）
    private static volatile int sCallerUid = -1;
    private static volatile String sCallerPkg = "?";
    private static volatile int sSelfUid = -1;
    private static volatile int sPkgResolvedForUid = Integer.MIN_VALUE;

    private static void log(String msg) {
        if (sLogCount < MAX_LOG) {
            sLogCount++;
            L.i(P + msg);
            return;
        }
        if (!sBudgetOver) {
            sBudgetOver = true;
            L.i(P + "日志已达上限 " + MAX_LOG + " 条，后续静音（归一化逻辑不受影响）");
        }
    }

    // ======================================================================
    //  安装
    // ======================================================================

    private static volatile boolean sDone;

    /** 唯一的对外入口。幂等；任何失败都只记日志，绝不抛。 */
    public static void install(final ClassLoader cl) {
        if (!CoreConfig.DEV_NORMALIZE_1021_TO_2) {
            return;     // release 默认走这里：一个实参都不改
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
        Class<?> woc = classNamed(cl, "com.android.server.wm.WindowOrganizerController");
        if (woc == null) {
            L.i(P + "安装 找不到 WindowOrganizerController，本次不挂（由调用方决定是否重试）");
            return false;
        }
        try {
            sSelfUid = android.os.Process.myUid();      // 公开 API，本地查询
        } catch (Throwable ignored) {
            sSelfUid = -1;
        }
        try {
            XposedBridge.hookAllMethods(woc, "startMiuiNewTransition", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        onStartMiuiNewTransition(p);
                    } catch (Throwable t) {
                        // 绝不能外抛：这里任何异常都会进 system_server
                        L.w(P + "回调异常（已吞掉，未做任何改动）: " + t);
                    }
                }
            });
            L.i(P + "★ 已挂钩 startMiuiNewTransition"
                    + "（L1 实验；只改 type 这一个实参；当前 selfUid=" + sSelfUid + "）");
            sArmedAt = SystemClock.uptimeMillis();
            L.i(P + "★ 保险阀已武装：本次开机后最多生效 " + (WINDOW_MS / 60000L)
                    + " 分钟或 " + MAX_SHOTS + " 次归一化，先到者为准；"
                    + "到期自动恢复系统原行为（重启即完全复位）");
            return true;
        } catch (Throwable t) {
            L.i(P + "安装 挂钩失败: " + t);
            return false;
        }
    }

    // ======================================================================
    //  判决 + 唯一的那一处写入
    // ======================================================================

    /** 源类型：MIUI 自定义的「开始最近任务过渡」（测绘文档 E-4 的 1021）。 */
    private static final int SRC = 1021;
    /** 目标类型：TRANSIT_CLOSE，应用侧会据此算出反向（测绘文档 E-3）。 */
    private static final int DST = 2;

    private static void onStartMiuiNewTransition(XC_MethodHook.MethodHookParam p) {
        Object[] a = p.args;
        // 1) 只认 (int, WindowContainerTransaction, boolean) 这**一个**重载
        if (a == null || a.length != 3) {
            return;
        }
        if (!(a[0] instanceof Integer) || !(a[2] instanceof Boolean)) {
            return;
        }
        final int type = (Integer) a[0];
        final boolean isRecents = (Boolean) a[2];

        // 2) 只关心 1021（其它 type 直接静默返回，不占日志预算）
        if (type != SRC) {
            return;
        }

        readCaller();                                   // 只读 uid + 尽力取包名（仅用于打印）

        long now = SystemClock.uptimeMillis();
        final long bucket = now / BUCKET_MS;

        // 3) 收窄条件
        String reason = decide(type, isRecents, bucket);
        if (reason != null) {
            log("跳过：" + reason + " type=" + type + " isRecentsTransition=" + isRecents
                    + " callerUid=" + sCallerUid + " callerPkg=" + sCallerPkg
                    + " bucket=" + bucket);
            return;
        }

        // 4) ★ 唯一的写入：把实参 type 从 1021 换成 2
        a[0] = Integer.valueOf(DST);
        sLastBucket = bucket;
        sNormalized++;
        log("★ 归一化 type " + SRC + " → " + DST
                + " callerUid=" + sCallerUid + " callerPkg=" + sCallerPkg
                + " isRecentsTransition=" + isRecents
                + " bucket=" + bucket + " 第 " + sNormalized + " 次");
        // 让 L0 的旁观日志也能看到这次改动（只读接口，无副作用）
        SystemServerProbe.onNormalized(SRC, DST);
    }

    /**
     * 收窄判决。{@code null} = 允许归一化；否则返回跳过原因。
     *
     * <h4>★ 为什么这里没有「起始包 == com.miui.personalassistant」这一条</h4>
     * 本函数跑在 {@code startMiuiNewTransition} 的 <b>before</b> 里，能拿到的只有
     * {@code type} 与 {@code isRecentsTransition} 两个已反序列化的实参。实测三条路全断
     * （这不是偷懒，是"无法可靠拿到"的结论）：
     * <ol>
     *   <li><b>WCT 里根本没有包名</b>：反编译 {@code android.window.WindowContainerTransaction}
     *       全量成员后确认，它只有容器操作（{@code mChanges / mHierarchyOps /
     *       mErrorCallbackToken / mHandlePackageUpdate} 等），<b>没有任何启动来源/包名字段</b>。
     *       唯一带 "package" 的是一个与 {@code startShortcut} 有关的 bundle key，无关本场景。</li>
     *   <li><b>Binder 调用方不是应用</b>：该方法的声明在 AIDL
     *       {@code android/window/IWindowOrganizerController} 里（framework.jar），
     *       而方法名字符串在 Shell jar 与 services.jar 里<b>都搜不到</b>
     *       ⇒ 调用来自<b>另一个进程</b>（聚合进程为 Shell / SystemUI）。
     *       所以 {@code Binder.getCallingUid()} 拿到的是那个系统进程，不是负一屏。</li>
     *   <li><b>此刻还不在过渡里</b>：{@code Transition.collect} 的 participant 列表要在本方法
     *       <b>返回之后</b>才会填充，所以这里读不到本过渡的参与者。</li>
     * </ol>
     * 因此按指示改用**替代收窄条件**，并且只保留真正能起作用的约束：
     * <pre>
     *   type == 1021  &&  isRecentsTransition == true
     * </pre>
     * 调用方 uid/包名照打，作为人工核验信息；若真机发现调用方不是预期的 Shell/SystemUI 进程，
     * 打开 {@link CoreConfig#DEV_L1_REQUIRE_UI_CALLER} 即可把这一条也变成硬约束。
     *
     * <p><b>没有</b>写「起始包」那条：它在此刻结构上不可得，加进去只会恒为 null 让归一化永不生效。
     */
    private static String decide(int type, boolean isRecents, long bucket) {
        if (type != SRC) {
            return "type != " + SRC;
        }
        if (expired()) {
            return "L1 实验窗口已结束（保险阀）";
        }
        if (CoreConfig.DEV_L1_REQUIRE_IS_RECENTS && !isRecents) {
            return "isRecentsTransition=false（可能是 LAUNCH_FROM_HOME 那条，不归一化）";
        }
        if (CoreConfig.DEV_L1_REQUIRE_UI_CALLER && !(sCallerUid > 0 && sCallerUid == sSelfUid)) {
            return "callerUid=" + sCallerUid + " 不是本进程（预期 Shell/SystemUI）";
        }
        if (bucket == sLastBucket) {
            return "同一 bucket 内已归一化过（防重复，bucket=" + bucket + "）";
        }
        return null;
    }

    /**
     * ★ 保险阀（用户批准的"自动过期"）：
     * 重启后 L1 只生效 {@link #WINDOW_MS} 或前 {@link #MAX_SHOTS} 次归一化，先到者为准；
     * 到期后**自动恢复**成"一个实参都不改"，并打一行醒目日志。
     *
     * <p>目的：万一这条归一化在别处（最近任务 / 桌面）有副作用，最长也只影响 10 分钟，
     * <b>重启即完全复位</b> —— 用户不需要去 LSPosed 里手动关模块。
     */
    private static boolean expired() {
        final long armed = sArmedAt;
        if (armed < 0L) {
            return false;                                   // 还没挂钩成功，不判过期
        }
        final boolean byTime = SystemClock.uptimeMillis() - armed > WINDOW_MS;
        final boolean byCount = sNormalized >= MAX_SHOTS;
        if (!byTime && !byCount) {
            return false;
        }
        if (!sExpiredNoted) {
            sExpiredNoted = true;
            L.i(P + "★ L1 实验窗口已结束（" + (byTime
                    ? "已满 " + (WINDOW_MS / 60000L) + " 分钟"
                    : "已归一化 " + sNormalized + " 次") + "）"
                    + "⇒ 此后 type " + SRC + " 一律不再归一化，已恢复系统原行为");
        }
        return true;
    }

    // ======================================================================
    //  调用方身份（只读；包名仅用于打印）
    // ======================================================================

    /**
     * 读 {@code Binder.getCallingUid()}（本地静态查询，无 IPC），
     * 并**尽力**把 uid 映射成包名 —— 只用于日志，<b>不参与判决</b>。
     * 同一个 uid 只解析一次（有缓存），拿不到就打 {@code ?}。
     */
    private static void readCaller() {
        try {
            sCallerUid = Binder.getCallingUid();
        } catch (Throwable ignored) {
            sCallerUid = -1;
        }
        final int uid = sCallerUid;
        if (uid <= 0 || uid == sPkgResolvedForUid) {
            return;                                     // 已解析过（或 uid 无效）
        }
        sPkgResolvedForUid = uid;
        sCallerPkg = resolvePkg(uid);
    }

    private static volatile Method sMGetSystemContext;
    private static volatile Method sMGetPackageManager;
    private static volatile Method sMGetNameForUid;
    private static volatile Object sSystemContext;
    private static volatile boolean sRefsTried;

    private static String resolvePkg(int uid) {
        try {
            if (!sRefsTried) {
                sRefsTried = true;
                Class<?> at = Class.forName("android.app.ActivityThread");
                Method cur = at.getMethod("currentActivityThread");
                cur.setAccessible(true);
                Object atObj = cur.invoke(null);
                if (atObj != null) {
                    Method gsc = atObj.getClass().getMethod("getSystemContext");
                    gsc.setAccessible(true);
                    Object ctx = gsc.invoke(atObj);
                    if (ctx != null) {
                        Method gpm = ctx.getClass().getMethod("getPackageManager");
                        gpm.setAccessible(true);
                        Object pm = gpm.invoke(ctx);
                        if (pm != null) {
                            Method nfu = pm.getClass().getMethod("getNameForUid", int.class);
                            nfu.setAccessible(true);
                            sSystemContext = ctx;
                            sMGetPackageManager = gpm;
                            sMGetNameForUid = nfu;
                        }
                    }
                }
            }
            if (sMGetNameForUid == null || sSystemContext == null) {
                return "?";
            }
            Object pm = sMGetPackageManager.invoke(sSystemContext);
            Object r = sMGetNameForUid.invoke(pm, uid);
            return r == null ? "?" : String.valueOf(r);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static Class<?> classNamed(ClassLoader cl, String name) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable ignored) {
        }
        try {
            return Class.forName(name, false, SystemServerNormalize.class.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }
}
