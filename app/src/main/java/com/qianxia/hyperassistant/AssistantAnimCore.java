package com.qianxia.hyperassistant;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceControl;
import android.view.View;
import android.view.Window;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * ============================================================================
 *  负一屏（com.miui.personalassistant）动画核心 —— 重写版
 * ============================================================================
 *
 * <h2>1. 这个类解决什么问题</h2>
 * "点负一屏里的组件 → 应用铺满"这一段，原本由负一屏自己的
 * {@code HyperViewScale} 过渡驱动，观感一般；我们要的是桌面同款那种
 * 原生 SurfaceControl 弹簧动画（旧版实测：重复帧 0.00、114~119fps）。
 * 同时，这台机器上"上滑缩回"之后会留一层整屏模糊洗不掉，需要收尾清掉。
 *
 * <p>于是本类只做两件事：
 * <pre>
 *   (A) 打开段：拦截 MIUI 过渡开始时对 leash 的第一笔写入，改用
 *       SurfaceControl$Transaction.startSFAnimation(...) 播原生弹簧动画；
 *   (B) 缩回段收尾：动画停稳之后，调用负一屏自己的标准入口
 *       y5.i.a(decorView) + decorView.setPassWindowBlurEnabled(false) 清模糊。
 * </pre>
 *
 * <h2>2. 为什么这么设计</h2>
 *
 * <h3>2.1 每个量只有唯一的写入者</h3>
 * 状态机只有一个入口 {@link #setState(int, String)}；
 * SF 动画的句柄 {@code sSfAnimId} / {@code sSfActive} 只由
 * {@code startOpenSf()} 与 {@code stopSf()} 这一对方法写，而它们又被
 * {@code sSfStartedInCycle}（"每个过渡周期只允许起一次"）守卫。
 * 因此不可能出现旧版那种"HVS_OWN_EXIT 与 HVS_USE_SF_ANIM 同时为真、
 * 两个引擎轮流写同一条 leash"的局面。
 *
 * <h3>2.2 为什么不压 blur 层的 alpha</h3>
 * 已确证：{@code view_scale_blur} 那一层自己没有 buffer，我们看到的模糊是
 * 它下面被合成的 pass-blur。对那一层 {@code setAlpha(0)} 是无效操作
 * （旧版试过 117 次，全部无效）。真正的关模糊入口只有
 * {@code y5.i.a(View)}（内部依次调 View 的
 * {@code clearMiBackgroundBlendColor / setMiBackgroundBlurMode(0) /
 * setMiBackgroundBlurRadius(0) / setPassWindowBlurEnabled(false)}）
 * 与 {@code Window.setBackgroundBlurRadius(0)}。
 * 并且必须在动画停止写入之后再调：滑动期间系统每帧重写 radius，
 * 一次压 0 立刻被覆盖。这就是 (B) 只在"停稳"后动手的原因。
 *
 * <h3>2.3 为什么缩回段完全不接管</h3>
 * 两条血的教训（都有真机数据）：
 * <ul>
 *   <li>把 MIUI 上滑时传的 {@code mReverseToActivityA=false} 改成 true 强制反向
 *       → "系统回桌面、动画却往左"的对拉；</li>
 *   <li>硬改 {@code goToState(...)} 的目标状态 → 直接把手机卡死。</li>
 * </ul>
 * 所以本类绝不触碰 SlidingPaneStateManager / merge 参数 / 状态目标值，
 * 只在缩回结束之后做一次幂等的清理。
 * 代码上体现为：对 MIUI 状态相关的钩子全部是 onAfter 只读观察；
 * 唯一会去改参数的地方是 (A) 的 leash 写入吞并，而且只作用于我们正在驱动的那一条 leash
 * —— 只吞 {@code SurfaceControl} 上的几何写入，绝不吞任何 Java 层生命周期调用。
 *
 * <h3>2.3.1 整屏模糊残留的完整因果链（为什么 (B) 必须存在）</h3>
 * <ol>
 *   <li>本机模糊状态 {@code BLUR_VIEW}、{@code maxBlurRadius=180}、
 *       {@code mForceEnablePassBlur=false}。</li>
 *   <li>MIUI 有三条关模糊路径，其中两条在 BLUR_VIEW 下是空操作：
 *       {@code suppressForcePassBlur()}（被 {@code mForceEnablePassBlur} 挡住，
 *       但 hideOverlay 每次都调它 ⇒ 无效）与
 *       {@code setBlurRadiusWithMax(boolean)}（只在 {@code needCompatibleBlur()}
 *       ＝BLUR_WINDOW 时有效）。</li>
 *   <li>唯一有效的关模糊是 {@code AssistantOverlayWindow.A()}(onClosed) 末尾的
 *       {@code this.D.onLeave()}，它在 BLUR_VIEW 分支里调 {@code y5.i.a(decorView)}。</li>
 *   <li>而 {@code A()} 的唯一调用者是状态管理器的收尾，条件是
 *       "进度动画真的跑到 0"（{@code Float.compare(progress, 0) == 0}）。</li>
 *   <li>⇒ 只要进度动画没跑完，收尾不执行 ⇒ {@code A()} 不执行 ⇒
 *       {@code D.onLeave()} 永不执行 ⇒ 模糊状态机停在"开着" ⇒ 整屏残留；
 *       手动返回会重跑一遍完整动画，模糊才被关掉
 *       —— 与用户"要手动返回才消失"逐字吻合。</li>
 *   <li>{@code onLeave()} 还是幂等的（{@code isOnLeaveCalled}），
 *       只有 {@code initBlurParams()} 会重新武装 ⇒ 不能指望"下一次会补上"。</li>
 * </ol>
 * 注意：我们自己接管打开段（吞掉 leash 几何写入）本身也会让 MIUI 的进度动画
 * 失去依据，因此 (B) 不只是"缩回段"的收尾，而是**每一次过渡之后都必须有的兜底**。
 *
 * <h3>2.4 反射一律按"类型/行为"匹配，不按反编译假名匹配</h3>
 * JADX 里的 A()/D()/E()/y5.i 都是混淆名，真机上不一定存在。因此：
 * <ul>
 *   <li>生命周期钩子挂在按包名确认出来的 AssistantOverlayWindow 上，
 *       用 {@code hookAllMethods(name)} 而不写死签名；</li>
 *   <li>取 leash 用"按层名/类型扫描 + 逐级降级"的四条路（见 2.4.1），
 *       **不依赖任何反编译假名**；</li>
 *   <li>取模糊适配器用"字段类型名含 {@code DeviceAdapter}"的类型扫描
 *       （不认 JADX 假名 D，也不要指望它挂在 {@code Window} 上 ——
 *       它挂在窗口管理器实例上，所以额外挂了 {@code ViewRootImpl.setView} 只读钩子
 *       去拿那个实例）；取内容视图同理；</li>
 *   <li>判断"是不是 OpenState"用**行为**：调该状态对象返回 float 的无参方法，
 *       值≈1.0 即 OpenState、≈0.0 即 Close/DragCloseState（名字只作辅助提示）；</li>
 *   <li>取 decorView 优先用窗口实例自己的 {@code getDecorView()}，
 *       只在拿不到时才回退到遍历 {@code WindowManagerGlobal} 的窗口标题。</li>
 * </ul>
 * 已经被假名坑过两次，这条是硬约束。
 *
 * <h3>2.4.1 leash 的四条获取路径（优先级 + 日志标识）</h3>
 * 这是本类最容易踩坑的地方，**四路并存、逐级降级，任何一条成功就够**：
 * <ol>
 *   <li><b>④ {@code 第一笔写入目标层}</b>（最可靠）：{@code Transaction.setPosition}
 *       等几何写入的第一个参数就是目标层 —— "几何写在哪一层，就驱动哪一层"。
 *       带层名且含 {@code leash}（不含 blur/mask/letterbox）就采信；
 *       完全无层名时，只有在"手上还没有任何 leash"的情况下才兜底采用。
 *       这条不需要任何反射，是**必然能拿到**的底。</li>
 *   <li><b>② {@code 字段扫描}</b>：{@code startHyperViewScaleAnimation} 与
 *       {@code createViewLeash} 时遍历动画实例（含父类）的 {@code SurfaceControl}
 *       字段，取 toString 含 {@code view-leash} 的那个（真机字段 {@code mLeash}）。
 *       <b>真机上只有这条路的字符串带层名</b> —— 旧模块 {@code sLiveLeash} 就是这么来的。</li>
 *   <li><b>① {@code createViewLeash参数}</b>：签名里的第 2 个参数。
 *       ⚠️ 真机上这个参数的 {@code toString()} 是**默认形式**
 *       （{@code SurfaceControl@4dd71e9}），**没有任何层名** ——
 *       所以不能用层名过滤它，只能如实标注来源后兜底采用。
 *       第 1 轮就是误以为它有层名，结果日志打"参数里没找到 leash"⇒ 完全没接管。</li>
 *   <li><b>③ {@code 调用后对象}</b>：{@code createViewLeash} 返回之后，
 *       用返回值（若是 SurfaceControl）或调用方实例再扫一遍，
 *       覆盖"调用之后才把 leash 挂上对象"的实现。</li>
 * </ol>
 * 每一笔过渡都会打一行 {@code leash 来源 = <上面四个标识之一>}，真机可直接核对。
 * 路径②的字段扫描**不做惰性**（它是唯一带层名的来源），但已由路径④确定的句柄
 * 不会被旧实例覆盖。
 *
 * <h3>2.5 任何异常都不许影响系统原行为</h3>
 * 所有反射都包 try/catch；拿不到对象就直接放弃（不干预），
 * 永远不会有"猜一个值硬写进去"的分支。
 *
 * <h2>3. 已确证的标准调用链（新核心依赖的部分）</h2>
 * <pre>
 * 打开段：
 *   createViewLeash(TransitionInfo, SurfaceControl, Transaction,
 *                   ViewScaleAnimInfo, Z, Z, F)
 *     ├─ 第 2 个参数是真机 leash，但它的 toString 是默认形式、**没有层名**
 *     └─ 该对象的 mLeash 字段（toString 含 view-leash）才是带层名的那个
 *     → 每帧 Transaction.setCrop/setScale/setPosition/setCornerRadius 写 leash
 *   startHyperViewScaleAnimation(...)                 ← 动画实例建立（此时可扫到 mLeash 字段）
 *   ── 我们在"第一笔 leash 写入"处起 startSFAnimation，并吞掉 MIUI 的逐帧写入 ──
 *   OnSFAnimationCallback.onSFAnimationStatusCallback(1, id)  ← 开始，id 在 args[1]
 *   OnSFAnimationCallback.onSFAnimationStatusCallback(0, id)  ← 结束
 *
 * 缩回段（我们只看不动）：
 *   StateListener.c(SlidingPaneState 实例)   ← 过渡开始（用"返回 float 的无参方法≈0" 判定缩回）
 *   AssistantOverlayWindow.g(int flags)      ← hideOverlay(0/1) 真正开始收窗口
 *   状态管理器收尾要求"进度动画跑到 0" → AssistantOverlayWindow.A()(onClosed)
 *     → A() 内 L195 this.D.onLeave() → DeviceBlurBlendAdapter.onLeave()
 *       → BLUR_VIEW 分支调 y5.i.a(decorView)（唯一有效的关模糊路径）
 *   ── 进度动画没跑完时 A() 整条链都不会执行 ⇒ 模糊残留 ──
 *   ── 我们在这些信号之后，等"距最后一次 leash 写入 &gt; 1200ms"再走标准收尾 ──
 *
 * View 侧模糊状态机（DeviceBlurBlendAdapter）：
 *   onEnter(bool)   → blurBackground(1.0f)
 *   onScroll(float) → getRealProgress → mCurrentBlurRadius 更新 → blurBackground()
 *   onLeave()       → 幂等（isOnLeaveCalled），只有 initBlurParams() 会重新武装
 *   initBlurParams() 的武装点：N(long)=startScroll / M(int)=showOverlay(flags=1) / Z() 补做
 *   本机状态 BLUR_VIEW + maxBlurRadius=180 + mForceEnablePassBlur=false ⇒
 *   suppressForcePassBlur()/setBlurRadiusWithMax() 都是空操作，
 *   onLeave() 也不会替我们清干净。
 * </pre>
 *
 * <h2>4. 已知限制</h2>
 * <ol>
 *   <li>(A) 只在 HyperViewScale 这条链路上有效（服务卡片/小部件走它）；
 *       其它启动路径不干预。</li>
 *   <li>同一过渡内我们只认一个 leash。若系统在一次过渡里换 leash（未观测到），
 *       会在 {@code isValid()==false} 时主动放弃并停止动画，绝不硬写已释放句柄。</li>
 *   <li>(B) 的"缩回信号"来自 StateListener；若某条系统路径不回调它，
 *       就退化成 A() / g() 触发；两者都没触发时本轮不清模糊
 *       —— 宁可漏一次，也不在动画中途乱清。</li>
 *   <li>{@code y5.i} 是反编译类名，ROM 更新后若改名会清不掉模糊，
 *       日志里会打"标准清模糊入口 y5.i 缺失"。这是"按行为匹配"也解决不了的
 *       一次性类名依赖，只能靠日志发现。</li>
 *   <li>打开段的目标矩形取自 leash 自己的 centerTo/widthTo，
 *       不再硬编码 1200x2608，但仍假设"铺满整屏"。</li>
 * </ol>
 */
public final class AssistantAnimCore {

    private AssistantAnimCore() {
    }

    // ======================================================================
    //  日志（统一 [核心] 前缀 + 限流）
    // ======================================================================

    private static final String P = "[核心] ";

    private static final AtomicInteger sLogAnim = new AtomicInteger();
    private static final AtomicInteger sLogInstall = new AtomicInteger();
    private static final AtomicInteger sLogSweep = new AtomicInteger();
    private static final AtomicInteger sLogFail = new AtomicInteger();
    private static final AtomicInteger sLogLeash = new AtomicInteger();
    /**
     * leash 写入探针的**独立预算**。
     * 真机反馈：探针和"leash 来源"共用 60 条预算时，
     * 40 条探针会把"标准收尾完成"那种关键结论挤掉 ⇒ 现在互不挤占。
     */
    private static final AtomicInteger sLogProbe = new AtomicInteger();
    /** 上滑返回观测点的专用预算（与探针/收尾互不挤占）。 */
    private static final AtomicInteger sLogSwipe = new AtomicInteger();
    /** mask buffer 修复的专用预算（安装/命中/跳过三条日志互不挤占）。 */
    private static final AtomicInteger sLogMask = new AtomicInteger();
    /** 失败保险（首帧放行/首回调超时）的专用预算。 */
    private static final AtomicInteger sLogFailsafe = new AtomicInteger();

    /**
     * ★ 第 13 轮新增：**"返回方向，不接管"** 专用日志类别。
     *
     * <p>为什么必须单独一类：{@link #key(AtomicInteger, String)} 的预算是**进程级**的
     * （{@code LOG_BUDGET} 条，且没有按过渡复位），把它挂在 {@code sLogAnim} 上会
     * **一次性吃掉整条诊断通道**。真机取证（{@code shots/dir_log.txt}）：返回段一笔
     * 过渡里这个判断被触发 60 次，直接把 {@code sLogAnim} 用满 ⇒ 之后的
     * `打开段：leash 无效 / from 几何无效` 等诊断全部静默。
     */
    private static final AtomicInteger sLogReverse = new AtomicInteger();

    /** 关键路径（必打）。 */
    private static void key(String msg) {
        L.i(P + msg);
    }

    /** 关键路径 + 限流。 */
    private static void key(AtomicInteger budget, String msg) {
        int n = budget.incrementAndGet();
        if (n <= CoreConfig.LOG_BUDGET) {
            L.i(P + msg);
        } else if (n == CoreConfig.LOG_BUDGET + 1) {
            L.i(P + "（本条日志已达 " + CoreConfig.LOG_BUDGET + " 条上限，后续同类只记数）");
        }
    }

    /** 失败路径：必打但限流，避免每帧刷。 */
    private static void fail(String what, Throwable t) {
        int n = sLogFail.incrementAndGet();
        if (n <= CoreConfig.LOG_BUDGET) {
            L.w(P + what + " 失败: " + (t == null ? "unknown" : t.toString()));
        }
    }

    private static void fail(String what, String detail) {
        int n = sLogFail.incrementAndGet();
        if (n <= CoreConfig.LOG_BUDGET) {
            L.w(P + what + " 失败: " + detail);
        }
    }

    private static void verbose(String msg) {
        if (CoreConfig.LOG_VERBOSE) {
            L.i(P + msg);
        }
    }

    // ======================================================================
    //  状态机
    // ======================================================================

    static final int IDLE = 0;
    static final int OPENING = 1;
    static final int AT_REST = 2;
    static final int EXITING = 3;

    /** 状态机唯一的写入者：setState()。 */
    private static volatile int sState = IDLE;

    private static String stateName(int s) {
        switch (s) {
            case IDLE: return "IDLE";
            case OPENING: return "OPENING";
            case AT_REST: return "AT_REST";
            case EXITING: return "EXITING";
            default: return "?" + s;
        }
    }

    private static synchronized void setState(int next, String why) {
        int old = sState;
        if (old == next) {
            return;
        }
        sState = next;
        key("状态 " + stateName(old) + " → " + stateName(next) + "  (" + why + ")");
    }

    private static int state() {
        return sState;
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static void postDelayed(Runnable r, long delayMs) {
        try {
            MAIN.postDelayed(r, delayMs);
        } catch (Throwable t) {
            fail("postDelayed", t);
        }
    }

    // ======================================================================
    //  安装
    // ======================================================================

    private static volatile boolean sInstalled;
    private static int sInstallAttempt;

    /**
     * 应用（宿主）的 ClassLoader —— 由 {@link #install(ClassLoader)} 记下。
     *
     * <p>★ 真机教训（第 4 轮）：{@code Class.forName("y5.i")} **不传 CL** 时用的是
     * 我们模块自己的 ClassLoader，而 {@code y5.i} 是**应用内的类** ⇒
     * {@code ClassNotFoundException: y5.i}，标准清模糊入口整条失效。
     * 所有"按名字找应用内类"的地方都必须显式传这个 CL。
     */
    private static volatile ClassLoader sAppCl;

    /**
     * 唯一的对外入口。幂等；失败只记日志，绝不抛。
     *
     * <p>建议在 MainHook 的 {@code com.miui.personalassistant} 分支里调用一次
     * （旧核心是 {@code Config.ENABLE_HVS_DIAG} 控制安装 HvsDiagnostics 的；
     * 新核心由 {@link CoreConfig#ENABLED} 自己控制总闸）。
     */
    public static void install(final ClassLoader cl) {
        if (!CoreConfig.ENABLED) {
            return;
        }
        // ★ 第一件事就是记下应用 CL（后面所有"找应用内类"都要用它）
        if (cl != null) {
            sAppCl = cl;
        }
        if (sInstalled) {
            return;
        }
        try {
            if (installOnce(cl)) {
                sInstalled = true;
                return;
            }
        } catch (Throwable t) {
            fail("安装", t);
        }
        // HyperViewScale 的实现类由 MiuiStubRegistry 运行时加载，可能晚于
        // handleLoadPackage，按旧代码验证过的节奏重试（不要更密，重试本身有成本）。
        final long[] delays = {300L, 1000L, 2500L, 6000L, 12000L, 25000L, 60000L};
        for (final long d : delays) {
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (sInstalled) {
                        return;
                    }
                    try {
                        if (installOnce(cl)) {
                            sInstalled = true;
                        }
                    } catch (Throwable t) {
                        fail("安装(重试)", t);
                    }
                }
            }, d);
        }
    }

    private static synchronized boolean installOnce(ClassLoader cl) {
        if (sInstalled) {
            return true;
        }
        sInstallAttempt++;
        key("开始安装（第 " + sInstallAttempt + " 次尝试）");

        boolean any = false;
        any |= installOverlayLifecycle(cl);
        any |= installStateListener(cl);
        any |= installTransactionHooks();
        any |= installHvsHooks(cl);
        any |= installRootViewProbe(cl);

        if (any) {
            key("安装完成：状态机就绪（打开段=" + CoreConfig.ENABLE_OPEN_SF
                    + " 缩回清模糊=" + CoreConfig.ENABLE_BLUR_SWEEP + "）");
            sfApiVersion();                 // ★ 只读：把本机 SF 动画 API 版本打出来
            return true;
        }
        key(sLogInstall, "本次未挂上任何钩子（实现类可能还没加载）");
        return false;
    }

    // ======================================================================
    //  钩子 4/4：ViewRootImpl.setView —— 拿到窗口管理器实例（唯一能到 DeviceAdapter 的路）
    // ======================================================================

    /**
     * 只读观察 {@code ViewRootImpl.setView}（它在 framework 里，永远存在）。
     *
     * <p>为什么要它：{@code DeviceBlurBlendAdapter} 挂在**窗口管理器实例**
     * （{@code AssistantOverlayWindow}，父类 {@code c5.e}）的字段上，
     * 而 {@code WindowManagerGlobal} 的 roots 只给得到 {@code View}，
     * 从 View 是回不到那个实例的。真机日志里 setView 的 thisObject 就是它。
     *
     * <p>判据（不认假名）：
     * <ul>
     *   <li>窗口标题含 {@code AssistantOverlay} / {@code LauncherOverlay}，或</li>
     *   <li>view 的类型是/属于我们解析出来的 overlay 窗口类。</li>
     * </ul>
     */
    private static boolean installRootViewProbe(ClassLoader cl) {
        Class<?> vri = findQuiet(cl, "android.view.ViewRootImpl");
        if (vri == null) {
            key(sLogInstall, "找不到 ViewRootImpl（窗口实例将只能靠生命周期回调拿到）");
            return false;
        }
        return HookKit.hookAll(vri, "setView", new HookKit.Cb("核心.rootView.setView") {
            @Override
            protected void onBefore(MethodHookParam p) {
                try {
                    if (p.args == null || p.args.length < 2) {
                        return;
                    }
                    if (!(p.args[0] instanceof View)) {
                        return;
                    }
                    View decor = (View) p.args[0];
                    String title = null;
                    if (p.args[1] instanceof android.view.WindowManager.LayoutParams) {
                        CharSequence t = ((android.view.WindowManager.LayoutParams) p.args[1]).getTitle();
                        title = t == null ? null : t.toString();
                    }
                    boolean hit = (title != null
                            && (title.contains("AssistantOverlay") || title.contains("LauncherOverlay")));
                    if (!hit && sOverlayWindowCls != null) {
                        hit = sOverlayWindowCls.isInstance(p.thisObject);
                    }
                    if (!hit) {
                        return;
                    }
                    sOverlayWindowRef = p.thisObject;
                    sOverlayDecorRef = decor;
                    verbose("setView 命中 overlay 窗口: mgr="
                            + p.thisObject.getClass().getName() + " title=" + title);
                } catch (Throwable t) {
                    fail("setView 记录", t);
                }
            }
        });
    }

    // ======================================================================
    //  钩子 1/3：overlay 窗口生命周期（只读观察）
    // ======================================================================

    private static Class<?> sOverlayWindowCls;

    /**
     * 找到 {@code com.miui.personalassistant} 的 overlay 窗口类。
     * 先按 {@link Targets#ASSISTANT_WINDOW} 直连；失败则退回
     * {@link Targets#PANE_WINDOW}（基类，仍然只有负一屏会用到这些方法）。
     */
    private static Class<?> resolveOverlayWindowClass(ClassLoader cl) {
        if (sOverlayWindowCls != null) {
            return sOverlayWindowCls;
        }
        Class<?> byName = findQuiet(cl, Targets.ASSISTANT_WINDOW);
        if (byName != null) {
            sOverlayWindowCls = byName;
            return byName;
        }
        Class<?> base = findQuiet(cl, Targets.PANE_WINDOW);
        if (base != null) {
            // 基类里 D(float) 等方法的实现体已被子类覆写，
            // LSPosed 会 hook 到子类的实现上，所以这里仍然可用。
            sOverlayWindowCls = base;
            return base;
        }
        return null;
    }

    // ======================================================================
    //  ★★ 上滑返回 → 接入"带动画的关闭"（第 13 轮最终方案）
    // ======================================================================

    /**
     * 把 {@code X()}（= {@code lockToHideOverlay}，无动画地把负一屏收掉）改走 {@code g(1)}
     * —— 也就是**侧滑返回用的同一个方法**，让小米自己那条带动画的关闭跑起来。
     *
     * <p>源码依据见 {@link CoreConfig#ROUTE_UP_SWIPE_TO_ANIMATED_CLOSE}（含行号与真机取证）。
     *
     * <p><b>保守原则</b>：任一判据读不出来就**不动手**（宁可回到小米原状，也绝不猜）。
     * 只改这一处调用；不改状态机目标、不碰几何、不碰 merge 参数。
     */
    private static void routeUpSwipeToAnimatedClose(
            de.robv.android.xposed.XC_MethodHook.MethodHookParam p) {
        try {
            final Object self = p.thisObject;
            if (self == null) {
                return;
            }
            if (p.args != null && p.args.length != 0) {
                return;                        // 只认无参那个重载（源码 X()）
            }
            // ⚠️ 第 13 轮踩到的坑：原来这里读 MIUI 的私有字段
            //    `f11511v`（展开着）/ `f11672n`（拖动中），但那是 **JADX 生成的假名**，
            //    运行时真名不是它 ⇒ 读不到 ⇒ 一直保守返回，一次都没生效
            //    （真机证据：`hookAll OK AssistantOverlayWindow.X` 挂上了、
            //      `lockToHideOverlay` 一直在打，但 `★ 上滑接入带动画关闭` 零次）。
            //    ⇒ 改用**我们自己的状态机**（它由 MIUI 的 onOpened/onScroll/hideOverlay
            //      回调驱动，是可靠的）：
            //    ① 必须是 AT_REST（稳定展开态）—— 拖动中会是 OPENING/EXITING，天然排除；
            final int st = state();
            if (st != AT_REST) {
                routeNote("状态不是 AT_REST（当前=" + stateName(st) + "）⇒ 不介入");
                return;
            }
            // ② 屏幕必须亮着（息屏那条是 `R(); g(0)`，跳过它可能漏掉资源释放，不碰）
            Boolean interactive = isScreenInteractive();
            if (interactive == null) {
                routeNote("读不到屏幕状态 ⇒ 不介入（保守）");
                return;
            }
            if (!interactive.booleanValue()) {
                routeNote("屏幕是灭的 ⇒ 不介入（走小米自己的 R(); g(0)）");
                return;
            }
            // ③ 动手：调 g(1)（与侧滑同一方法），然后跳过原方法体
            if (!call1Int(self, "g", 1)) {
                routeNote("反射调 g(1) 失败 ⇒ 让原方法自己跑");
                return;
            }
            p.setResult(null);
            key(sLogSwipe, "★ 上滑接入带动画关闭：X() → g(1)（与侧滑同一条路）");
        } catch (Throwable t) {
            fail("上滑接动画", t);
        }
    }

    /** 上滑判定的限流诊断（前 N 条），用来在真机上定位"为什么没介入"。 */
    private static final java.util.concurrent.atomic.AtomicInteger sLogRoute =
            new java.util.concurrent.atomic.AtomicInteger();

    private static void routeNote(String msg) {
        if (sLogRoute.incrementAndGet() <= 12) {
            key(sLogSwipe, "上滑判定：" + msg);
        }
    }

    /** 屏幕是否亮着；拿不到返回 null（调用方据此不介入）。 */
    private static Boolean isScreenInteractive() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (!(app instanceof android.content.Context)) {
                return null;
            }
            Object pm = ((android.content.Context) app)
                    .getSystemService(android.content.Context.POWER_SERVICE);
            if (pm == null) {
                return null;
            }
            Object r = pm.getClass().getMethod("isInteractive").invoke(pm);
            return r instanceof Boolean ? (Boolean) r : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 沿继承链找方法（并 setAccessible）。找不到返回 null。 */
    private static java.lang.reflect.Method findMethodUp(Class<?> c, String name, Class<?>... pt) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                java.lang.reflect.Method m = k.getDeclaredMethod(name, pt);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
            }
        }
        try {
            java.lang.reflect.Method m = c.getMethod(name, pt);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 沿继承链找字段（并 setAccessible）。找不到返回 null。 */
    private static java.lang.reflect.Field findFieldUp(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 读 boolean 字段；读不到返回 null（调用方据此"不动手"）。 */
    private static Boolean boolField(Object obj, String name) {
        try {
            java.lang.reflect.Field f = findFieldUp(obj.getClass(), name);
            return f == null ? null : Boolean.valueOf(f.getBoolean(obj));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Boolean asBool(Object o) {
        return o instanceof Boolean ? (Boolean) o : null;
    }

    /** 调一个无参方法；拿不到返回 null。 */
    private static Object call0(Object obj, String name) {
        try {
            java.lang.reflect.Method m = findMethodUp(obj.getClass(), name);
            return m == null ? null : m.invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 调一个单参方法；拿不到返回 null。（实验 ⑤ 取主色调用） */
    private static Object callWith(Object obj, String name, Object arg) {
        try {
            for (Class<?> k = obj.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                    if (!m.getName().equals(name) || m.getParameterTypes().length != 1) {
                        continue;
                    }
                    m.setAccessible(true);
                    return m.invoke(obj, arg);
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 调一个静态无参方法；拿不到返回 null。 */
    private static Object callStatic(String className, String name) {
        try {
            Class<?> c = Class.forName(className);
            java.lang.reflect.Method m = c.getMethod(name);
            return m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 调一个 (int) 方法；成功返回 true。 */
    private static boolean call1Int(Object obj, String name, int arg) {
        try {
            java.lang.reflect.Method m = findMethodUp(obj.getClass(), name, int.class);
            if (m == null) {
                return false;
            }
            m.invoke(obj, Integer.valueOf(arg));
            return true;
        } catch (Throwable t) {
            fail("反射调 " + name + "(int)", t);
            return false;
        }
    }

    private static boolean installOverlayLifecycle(ClassLoader cl) {
        final Class<?> cls = resolveOverlayWindowClass(cl);
        if (cls == null) {
            key(sLogInstall, "overlay 窗口类尚未加载（" + Targets.ASSISTANT_WINDOW + "），稍后重试");
            return false;
        }
        key("overlay 窗口类 = " + cls.getName());

        boolean ok = true;

        // 打开段完成：onOpened（子类 final E()，无参）
        ok &= HookKit.hookAll(cls, "E", new HookKit.Cb("核心.onOpened") {
            @Override
            protected void onAfter(MethodHookParam p) {
                if (p.args != null && p.args.length != 0) {
                    return;                      // 只认无参那个重载
                }
                onMiuiOpened(p.thisObject);
            }
        });

        // 缩回段结束：onClosed（子类 final A()，无参）
        ok &= HookKit.hookAll(cls, "A", new HookKit.Cb("核心.onClosed") {
            @Override
            protected void onAfter(MethodHookParam p) {
                if (p.args != null && p.args.length != 0) {
                    return;
                }
                onMiuiClosed(p.thisObject);
            }
        });

        // 缩回段起点：hideOverlay(int)
        ok &= HookKit.hookAll(cls, "g", new HookKit.Cb("核心.hideOverlay") {
            @Override
            protected void onBefore(MethodHookParam p) {
                if (p.args == null || p.args.length != 1 || !(p.args[0] instanceof Integer)) {
                    return;
                }
                onHideOverlay(((Integer) p.args[0]).intValue());
            }
        });

        // ★★ 第 13 轮：把"上滑返回"接入小米自己那条**带动画**的关闭。
        //    见 CoreConfig.ROUTE_UP_SWIPE_TO_ANIMATED_CLOSE 的长注释（含真机取证与源码行号）。
        if (CoreConfig.ROUTE_UP_SWIPE_TO_ANIMATED_CLOSE) {
            ok &= HookKit.hookAll(cls, "X", new HookKit.Cb("核心.routeUpSwipe") {
                @Override
                protected void onBefore(MethodHookParam p) {
                    routeUpSwipeToAnimatedClose(p);
                }
            });
        }

        // 缩回段其它信号
        ok &= HookKit.hookAll(cls, "H", new HookKit.Cb("核心.onScrollStart") {
            @Override
            protected void onBefore(MethodHookParam p) {
                onMiuiScrollStart(firstFloat(p));
            }
        });
        ok &= HookKit.hookAll(cls, "G", new HookKit.Cb("核心.onScrollEnd") {
            @Override
            protected void onBefore(MethodHookParam p) {
                onMiuiScrollEnd(firstFloat(p));
            }
        });
        // A(boolean) 是 onDragStart（注意：A() 无参是 onClosed，用参数个数区分）
        ok &= HookKit.hookAll(cls, "A", new HookKit.Cb("核心.onDragStart") {
            @Override
            protected void onBefore(MethodHookParam p) {
                if (p.args != null && p.args.length == 1 && p.args[0] instanceof Boolean) {
                    onMiuiDragStart(((Boolean) p.args[0]).booleanValue());
                }
            }
        });

        // 进度观察（只读）
        ok &= HookKit.hookAll(cls, "D", new HookKit.Cb("核心.onScroll") {
            @Override
            protected void onBefore(MethodHookParam p) {
                onMiuiScroll(firstFloat(p));
            }
        });

        return ok;
    }

    private static float firstFloat(XC_MethodHook.MethodHookParam p) {
        if (p.args == null || p.args.length == 0) {
            return Float.NaN;
        }
        Object a = p.args[0];
        if (a instanceof Float) {
            return ((Float) a).floatValue();
        }
        if (a instanceof Number) {
            return ((Number) a).floatValue();
        }
        return Float.NaN;
    }

    // ======================================================================
    //  钩子 2/3：StateListener（只读观察，绝不改参数）
    // ======================================================================

    private static boolean installStateListener(ClassLoader cl) {
        Class<?> mgr = findQuiet(cl, Targets.PANE_STATE_MGR);
        if (mgr == null) {
            key(sLogInstall, "SlidingPaneStateManager 未加载（缩回信号将用 A()/g() 兜底）");
            return false;
        }
        Class<?> listener = null;
        for (Class<?> inner : mgr.getDeclaredClasses()) {
            if (inner.isInterface() && inner.getDeclaredMethods().length == 3) {
                listener = inner;
                break;
            }
        }
        if (listener == null) {
            key(sLogInstall, "找不到 StateListener 内部接口（缩回信号将用 A()/g() 兜底）");
            return false;
        }
        key("StateListener 接口 = " + listener.getName());
        for (final String m : new String[]{"a", "b", "c"}) {
            HookKit.hookAll(listener, m, new HookKit.Cb("核心.state." + m) {
                @Override
                protected void onAfter(MethodHookParam p) {
                    Object st = (p.args != null && p.args.length > 0) ? p.args[0] : null;
                    onMiuiStateEvent(m, st);
                }
            });
        }
        return true;
    }

    // ======================================================================
    //  钩子 3/3：SurfaceControl$Transaction（唯一会写画的通路）
    // ======================================================================

    private static Class<?> sTxnCls;
    private static Class<?> sSfCallbackCls;
    /** 我们**直接用 Class.forName 默认 CL** 解析到的那一份（用于日志对账）。 */
    private static Class<?> sSfCallbackClsDirect;
    private static ClassLoader sSfCallbackClDirect;
    /** HVS impl 的 ClassLoader（子 CL）；回调接口应优先用它解析。 */
    private static ClassLoader sSfCallbackClImpl;
    private static volatile boolean sSfCallbackClsFromImplCl;

    private static String clDesc(ClassLoader cl) {
        if (cl == null) {
            return "boot/null";
        }
        return cl.getClass().getSimpleName() + "@"
                + Integer.toHexString(System.identityHashCode(cl));
    }

    /**
     * 从 {@code Transaction} 上**所有**名为 {@code startSFAnimation} 的方法里，
     * 按签名形状 {@code (SurfaceControl, float[], int, <回调接口>, Handler, int[], int)}
     * 取出**权威的**回调接口 Class。
     *
     * <p>为什么这是权威的：它来自 framework 自己的方法签名 ⇒ 与 SF 实际期望的
     * 接口 **必然是同一个 Class**，不受我们用什么 CL 解析的影响。
     */
    private static Class<?> findAuthoritativeSfCallbackCls() {
        if (sTxnCls == null) {
            return null;
        }
        try {
            for (Method m : sTxnCls.getMethods()) {
                if (!"startSFAnimation".equals(m.getName())) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length < 4) {
                    continue;
                }
                if (ps[0] != SurfaceControl.class || ps[1] != float[].class
                        || ps[2] != int.class) {
                    continue;
                }
                Class<?> iface = ps[3];
                key("startSFAnimation 签名 = " + m
                        + " ⇒ 权威回调接口 = " + iface.getName()
                        + " loader=" + clDesc(iface.getClassLoader())
                        + " interface=" + iface.isInterface());
                return iface;
            }
        } catch (Throwable t) {
            fail("读取 startSFAnimation 签名", t);
        }
        return null;
    }

    // ----------------------------------------------------------------------
    //  反射对象缓存 —— 这些解析一律只做一次。
    //
    //  为什么：`newTxn()` / `startSFAnimation` / `stopSFAnimation` 都在
    //  "点击 → 动画开始"这条主线程热路径上。旧实现每次都 getDeclaredConstructor/
    //  getMethod，属于"每帧解析反射"，是用户反馈"点击有延迟"最可疑的一环。
    //  解析一次存静态字段后，热路径上只剩 allocate + invoke。
    // ----------------------------------------------------------------------
    private static volatile java.lang.reflect.Constructor<?> sTxnCtor;
    private static volatile Method sStartSfMethod;
    private static volatile Method sStopSfMethod;
    private static volatile Method sApplyMethod;
    private static volatile Method sAdjustSfMethod;
    private static volatile Method sGetSfParamsMethod;
    /** (A1) 自补 crop 的两个重载（float×4 优先，Rect 兜底）；都拿不到 ⇒ 静默降级为不写。 */
    private static volatile Method sSetCrop4Method;
    private static volatile Method sSetCropRectMethod;

    private static boolean installTransactionHooks() {
        try {
            sTxnCls = Class.forName("android.view.SurfaceControl$Transaction");
        } catch (Throwable t) {
            fail("找不到 SurfaceControl$Transaction", t);
            return false;
        }
        try {
            // ① 先按最直白的方式找（模块自己的 CL；framework 类一般 boot CL 就能看到）
            sSfCallbackCls = Class.forName("android.view.OnSFAnimationCallback");
            sSfCallbackClsDirect = sSfCallbackCls;
            sSfCallbackClDirect = sSfCallbackCls.getClassLoader();
        } catch (Throwable t) {
            key(sLogInstall, "找不到 OnSFAnimationCallback（打开段 SF 动画不可用）");
        }
        if (sSfCallbackCls != null) {
            // ② ★★ 权威版：`HyperViewScaleRemoteManager` 的 ClassLoader 命名空间里的那一份。
            //    impl jar 由 MiuiStubRegistry 装进**子 ClassLoader**；万一它里面也带了一份
            //    同名的回调接口，我们用模块 CL 解析到的那份就会**是另一个 Class** ⇒
            //    SF 接受调用却永远不回调（"有 id、0 回调"）。所以优先用 impl CL 的那份。
            Class<?> impl = resolveHvsRemoteManager(null);
            ClassLoader implCl = impl != null ? impl.getClassLoader() : null;
            Class<?> preferred = null;
            if (implCl != null) {
                try {
                    preferred = Class.forName("android.view.OnSFAnimationCallback", false, implCl);
                } catch (Throwable ignored) {
                }
            }
            if (preferred != null && preferred != sSfCallbackCls) {
                key("★ 回调接口有两份！已改用 impl 命名空间里的那份"
                        + "（模块默认解析到的是另一个 Class）");
                sSfCallbackCls = preferred;
                sSfCallbackClsFromImplCl = true;
            }
            sSfCallbackClImpl = implCl;
            // ★ 两个 loader 都打出来对账
            key("回调接口 = " + sSfCallbackCls.getName()
                    + " loader=" + clDesc(sSfCallbackCls.getClassLoader())
                    + "（模块默认解析到的 = " + (sSfCallbackClsDirect == null ? "无"
                        : sSfCallbackClsDirect.getName()) + " loader=" + clDesc(sSfCallbackClDirect)
                    + "） 两份是同一个 Class = " + (sSfCallbackCls == sSfCallbackClsDirect)
                    + " implCL=" + clDesc(implCl));
        }
        // ---- 反射对象只解析一次（装钩子时做，不在热路径上做）----
        try {
            java.lang.reflect.Constructor<?> c = sTxnCls.getDeclaredConstructor();
            c.setAccessible(true);
            sTxnCtor = c;
            sApplyMethod = sTxnCls.getMethod("apply");
            sStopSfMethod = sTxnCls.getMethod("stopSFAnimation", SurfaceControl.class, int.class);
            try {
                // 预留：若将来需要在"动画正在跑"时改向，用它比 stop+start 更贴近系统原意。
                // 当前返回段是"停掉我们自己的动画 + 交回 MIUI"，所以暂未使用。
                sAdjustSfMethod = sTxnCls.getMethod("adjustSFAnimation",
                        SurfaceControl.class, float[].class, int.class, int.class);
            } catch (Throwable ignored) {
            }
            try {
                // 只查询、不 apply：用来判断"交回控制权后 leash 有没有收敛"。
                sGetSfParamsMethod = sTxnCls.getMethod("getSFAnimationParams",
                        SurfaceControl.class, int[].class);
            } catch (Throwable ignored) {
                sGeomQueryBroken = true;    // 本机没有这个方法，直接用自记录极值
            }
            // ★ (A1) 自补 crop 用的两个重载：float×4 是本机 MIUI/HVS 用的那个
            //   （桌面侧 SfAnimApiWrapper.java:93 用的是 Rect 版，故两个都留）。
            //   解析不到 ⇒ **静默降级**（只打一行日志，不写 crop），其余功能不受影响。
            try {
                sSetCrop4Method = sTxnCls.getMethod("setCrop", SurfaceControl.class,
                        float.class, float.class, float.class, float.class);
            } catch (Throwable ignored) {
            }
            try {
                sSetCropRectMethod = sTxnCls.getMethod("setCrop", SurfaceControl.class,
                        android.graphics.Rect.class);
            } catch (Throwable ignored) {
            }
            if (sSetCrop4Method == null && sSetCropRectMethod == null) {
                key(sLogInstall, "找不到 Transaction.setCrop（A1 自补 crop 静默降级为不写）");
            }
            if (sSfCallbackCls != null) {
                try {
                    sStartSfMethod = sTxnCls.getMethod("startSFAnimation",
                            SurfaceControl.class, float[].class, int.class, sSfCallbackCls,
                            Handler.class, int[].class, int.class);
                } catch (Throwable t) {
                    // ★★ 如果这里抛 NoSuchMethodException，说明我们手上这份回调接口**不是**
                    //   framework 期望的那份 Class（"两份同名类"的实锤）。
                    //   那就**改用方法签名里的权威那份**再解析一次。
                    fail("用当前回调接口解析 startSFAnimation 失败（可能有两份同名类）", t);
                    Class<?> authoritative = findAuthoritativeSfCallbackCls();
                    if (authoritative != null && authoritative != sSfCallbackCls) {
                        key("★ 已切换到方法签名里的权威回调接口 = " + authoritative.getName()
                                + " loader=" + clDesc(authoritative.getClassLoader()));
                        sSfCallbackCls = authoritative;
                        sSfCallbackClsFromImplCl = true;
                        sStartSfMethod = sTxnCls.getMethod("startSFAnimation",
                                SurfaceControl.class, float[].class, int.class, sSfCallbackCls,
                                Handler.class, int[].class, int.class);
                    } else {
                        key("★ 权威回调接口也拿不到（findAuthoritativeSfCallbackCls=null）"
                                + " ⇒ 打开段 SF 动画不可用，保持不干预");
                    }
                }
            }
            key("反射缓存就绪（Transaction 构造/apply/startSF/stopSF 各解析一次）");
        } catch (Throwable t) {
            fail("预解析 Transaction 方法", t);
        }

        boolean ok = true;

        // 时序保证很重要：LSPosed 对同一方法的多个回调**按注册顺序**调用，
        // 所以"起动画"必须先注册，之后才轮到"吞写"。
        ok &= HookKit.hookAll(sTxnCls, "setPosition", new HookKit.Cb("核心.txn.起动画") {
            @Override
            protected void onBefore(MethodHookParam p) {
                maybeStartOpenSf(p);
            }
        });

        // ★ crop 的钩子面由"谁是 crop 的写者"决定（矩阵见 CoreConfig 的「(A2) leash 裁切(crop)」）：
        //   A0（ALLOW_MIUI_CROP=true 且 ENABLE_OWN_CROP=false）⇒ **不 hook setCrop**，
        //      MIUI 的每帧 crop 原样通过，我们一笔都不写；
        //   A1（ENABLE_OWN_CROP=true）⇒ 必须 hook 并**继续吞**，否则 MIUI 每帧会覆盖我们写的 crop；
        //   基线（两者都 false）⇒ hook 并吞掉、但不补（症状存在，留作对照）。
        final boolean hookCrop = CoreConfig.ENABLE_OWN_CROP || !CoreConfig.ALLOW_MIUI_CROP;
        final String[] watched = hookCrop
                ? new String[]{"setPosition", "setScale", "setCrop", "setCornerRadius", "setAlpha"}
                : new String[]{"setPosition", "setScale", "setCornerRadius", "setAlpha"};
        for (final String mn : watched) {
            ok &= HookKit.hookAll(sTxnCls, mn, new HookKit.Cb("核心.txn." + mn) {
                @Override
                protected void onBefore(MethodHookParam p) {
                    onLeashWrite(mn, p);
                }
            });
        }

        ok &= HookKit.hookAll(sTxnCls, "apply", new HookKit.Cb("核心.txn.apply") {
            @Override
            protected void onBefore(MethodHookParam p) {
                onLeashApplied();
            }
        });

        key("Transaction 钩子安装" + (ok ? "完成" : "部分失败（其余功能不受影响）"));
        return true;
    }

    private static boolean installHvsHooks(ClassLoader cl) {
        Class<?> rm = resolveHvsRemoteManager(cl);
        if (rm == null) {
            key(sLogInstall, "HyperViewScaleRemoteManager 未加载，稍后重试");
            return false;
        }
        key("HyperViewScale 实现 = " + rm.getName()
                + "（loader=" + (rm.getClassLoader() == null ? "boot" : "非boot") + "）");

        boolean ok = true;
        // ★ 第 13 轮修正：impl 包里的类要用 **impl 子 CL** 查（宿主 CL 查不到，
        //   真机事实：`找不到 HyperViewScaleRemoteTransition`）。
        ClassLoader implClForAni = rm.getClassLoader();
        Class<?> ani = implClForAni != null
                ? resolveImplClass(implClForAni, "android.app.hyperviewscale.HyperViewScaleAnimationImpl")
                : null;
        if (ani == null) {
            ani = findQuiet(cl, "android.app.hyperviewscale.HyperViewScaleAnimationImpl");
        }
        if (ani != null) {
            ok &= HookKit.hookAll(ani, "startHyperViewScaleAnimation", new HookKit.Cb("核心.hvs.start") {
                @Override
                protected void onBefore(MethodHookParam p) {
                    onMiuiTransitionStart(p.thisObject);
                }
            });
        }

        // leash 的出生点。真机签名已核对：
        //   createViewLeash(TransitionInfo, SurfaceControl, Transaction, ViewScaleAnimInfo, Z, Z, F)
        ok &= HookKit.hookAll(rm, "createViewLeash", new HookKit.Cb("核心.hvs.createViewLeash") {
            @Override
            protected void onBefore(MethodHookParam p) {
                onLeashCreated(p.thisObject, p.args);
            }

            @Override
            protected void onAfter(MethodHookParam p) {
                // ③ 有些实现是"调用后才把 leash 挂到对象上"：
                //    这里用**返回值 / 调用后实例**再扫一遍（只在还没有 leash 时）。
                onLeashCreatedAfter(p.thisObject, p.getResult());
            }
        });

        // ★★ 黑白边修复（从旧模块 HvsDiagnostics 的 HVS_FIX_MASK_BUFFER 逐字移植）
        ok |= installMaskBufferFix(rm);

        // ★ 上滑返回的"标准观测点"补齐（只读，不改任何参数）
        //
        // ★★ 第 13 轮修正（真机事实）：这里原来用**宿主 CL** 去查
        //    `android.app.hyperviewscale.HyperViewScaleRemoteTransition`，结果是
        //    `[核心] 找不到 HyperViewScaleRemoteTransition（上滑观测点无法补）`。
        //    原因早就在本文件别处记过：整个 `android.app.hyperviewscale` **实现包**
        //    在子 ClassLoader（`miui-framework.hyperviewscale.jar`）里，
        //    宿主 CL 看不到。而 `rm`（HyperViewScaleRemoteManager）刚刚解析成功，
        //    它的 ClassLoader 就是那个子 CL ⇒ 用它去查。
        ClassLoader implCl = rm.getClassLoader();
        Class<?> rt = implCl != null
                ? resolveImplClass(implCl, "android.app.hyperviewscale.HyperViewScaleRemoteTransition")
                : null;
        if (rt == null) {
            rt = resolveImplClass(cl, "android.app.hyperviewscale.HyperViewScaleRemoteTransition");
        }
        if (rt == null) {
            key(sLogInstall, "HyperViewScaleRemoteTransition 仍未找到（implCL="
                    + (implCl == null ? "boot" : implCl.getClass().getName())
                    + "）—— 上滑强制反向用不了，其余功能不受影响");
        }
        installMergeObservers(rt, ani);
        // ★ 闪黑修复（本轮唯一变量）：maskAlphaFrom 1.0 → 0.0，见 CoreConfig.MASK_ALPHA_FROM_ZERO。
        installMaskAlphaZeroFix(implCl, cl);
        return ok;
    }

    // ======================================================================
    //  ★ 闪黑修复：ViewScaleAnimInfo.maskAlphaFrom 归零（唯一变量）
    // ======================================================================

    /** 验证行打印计数（自带预算，不占 LOG_BUDGET）。 */
    private static int sMaskAlphaZeroLog;
    /** 归零失败时只提示一次。 */
    private static boolean sMaskAlphaZeroWarned;

    /**
     * hook {@code ViewScaleAnimInfo} 构造函数（onAfter），把 {@code maskAlphaFrom} 写成 0。
     *
     * <p>为什么放在构造 onAfter：MIUI 在构造**之后**才用它去起 1→0 的淡出动画与
     * {@code initLeashChild} 的 {@code setAlpha(maskAlphaFrom)}，此时改还来得及；
     * 而字段本身若为 {@code final} 反射写会抛 —— 捕获后只提示一次，不打断宿主。
     */
    private static boolean installMaskAlphaZeroFix(ClassLoader implCl, ClassLoader hostCl) {
        // ★ 挖洞法（MASK_AS_BACKDROP）优先级更高：它要 mask **一直可见**（alpha=1）才有意义。
        final float target = CoreConfig.MASK_AS_BACKDROP
                ? 1.0f
                : (CoreConfig.MASK_ALPHA_FROM_ZERO ? 0.0f : Float.NaN);
        if (Float.isNaN(target)) {
            key("【闪黑修复】mask 的 alpha 都不改（MASK_ALPHA_FROM_ZERO=false 且 "
                    + "MASK_AS_BACKDROP=false ⇒ 官方原样 1.0 → 0.0）");
            return false;
        }
        Class<?> vsai = implCl != null
                ? resolveImplClass(implCl, "android.app.hyperviewscale.ViewScaleAnimInfo")
                : null;
        if (vsai == null) {
            vsai = resolveImplClass(hostCl, "android.app.hyperviewscale.ViewScaleAnimInfo");
        }
        if (vsai == null) {
            key("【闪黑修复】找不到 ViewScaleAnimInfo ⇒ 本变量未生效");
            return false;
        }
        try {
            // 构造函数必须用 hookAllConstructors（hookAllMethods("<init>") 打不中 —— 真机教训）
            de.robv.android.xposed.XposedBridge.hookAllConstructors(vsai,
                    new HookKit.Cb("核心.maskAlpha") {
                        @Override
                        protected void onAfter(MethodHookParam p) {
                            try {
                                Object info = p.thisObject;
                                float old = gf(info, "maskAlphaFrom", Float.NaN);
                                boolean okFrom = setFloatField(info, "maskAlphaFrom", target);
                                boolean okTo = true;
                                if (CoreConfig.MASK_AS_BACKDROP) {
                                    // 挖洞法要求 mask **全程不淡出**：to 也钉成 1.0。
                                    okTo = setFloatField(info, "maskAlphaTo", target);
                                }
                                if (okFrom) {
                                    if (sMaskAlphaZeroLog++ < 40) {
                                        key("【闪黑修复】maskAlphaFrom " + old + " → " + target
                                                + (CoreConfig.MASK_AS_BACKDROP
                                                ? "（挖洞法：mask 当「源快照底」一直可见；"
                                                  + "maskAlphaTo 也钉 1.0 ok=" + okTo + "）"
                                                : "（mask 全程不可见 ⇒ 全屏纯色不再闪）"));
                                    }
                                } else if (!sMaskAlphaZeroWarned) {
                                    sMaskAlphaZeroWarned = true;
                                    key("【闪黑修复】写入 maskAlphaFrom 失败（old=" + old
                                            + "，字段可能为 final）⇒ 本变量未生效");
                                }
                            } catch (Throwable t) {
                                fail("maskAlpha 改写", t);
                            }
                        }
                    });
            key("【闪黑修复】ViewScaleAnimInfo 构造钩子安装完成（maskAlphaFrom → " + target
                    + "，挖洞法=" + CoreConfig.MASK_AS_BACKDROP + "） 目标类 = " + vsai.getName());
            return true;
        } catch (Throwable t) {
            fail("安装 maskAlpha 钩子", t);
            return false;
        }
    }

    /** 写 float 字段（含父类）；找不到/写不进返回 false。 */
    private static boolean setFloatField(Object obj, String name, float v) {
        if (obj == null) {
            return false;
        }
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setFloat(obj, v);
                return true;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    // ======================================================================
    //  ★ 黑白边修复：重画 mask buffer（移植自旧模块 HVS_FIX_MASK_BUFFER）
    // ======================================================================

    /** {@code Surface(SurfaceControl)} 是 @hide，只能反射造；解析一次缓存。 */
    private static volatile java.lang.reflect.Constructor<?> sSurfaceCtor;
    private static int sMaskRedrawLog;

    /** DEV 闪黑取证：上一块 mask 的句柄与建立时刻（只读用，见 CoreConfig.DEV_FLASH_PROBE）。 */
    private static SurfaceControl sPrevMaskSc;
    private static long sPrevMaskAt = -1L;
    /** DEV 闪黑取证②：mask 逐帧 alpha 的打印计数（自带预算，不占用 LOG_BUDGET）。 */
    private static int sMaskAlphaLog;
    /** DEV 闪黑取证③：本窗口内已见过的目标层（用来发现"没预料到的参与层"）。 */
    private static final java.util.HashSet<String> sFlashWindowNames = new java.util.HashSet<>();
    /** DEV 闪黑取证③："窗口新层"那类日志的计数。 */
    private static int sFlashWindowNameLog;
    /** DEV 闪黑取证④：★mask / ★leash 的逐笔明细计数（每次建 mask 复位）。 */
    private static int sMaskDetailLog;
    private static int sLeashDetailLog;

    // ======================================================================
    //  ★ 挖洞法（MASK_AS_BACKDROP）的状态：mask buffer 尺寸 + leash 当帧几何
    // ======================================================================

    /** mask 那块 buffer 的尺寸（官方 setMaskSurface 的 Rect 给的，实测 1200x2608）。 */
    private static volatile int sMaskBufW = 1200;
    private static volatile int sMaskBufH = 2608;
    /** leash（应用窗口）当帧几何：位置、缩放、裁切、圆角。 */
    private static volatile float sLScale = Float.NaN;
    private static volatile float sLPosX = Float.NaN;
    private static volatile float sLPosY = Float.NaN;
    private static volatile float sLCropW = Float.NaN;
    private static volatile float sLCropH = Float.NaN;
    private static volatile float sLRadius = Float.NaN;
    /** 上一次真正重画"洞"的时刻与洞矩形（用来限流 + 去重）。 */
    private static volatile long sHoleAt;
    private static volatile float sHoleX = Float.NaN;
    private static volatile float sHoleY = Float.NaN;
    private static volatile float sHoleW = Float.NaN;
    private static volatile float sHoleH = Float.NaN;
    /** 挖洞法日志计数。 */
    private static int sBackdropLog;
    /** 快照为 null 时只提示一次。 */
    private static boolean sBackdropMissingLogged;
    /**
     * mask buffer 当前状态：0=未知/需要全量重画，1=已有"底+快照+洞"，2=整块已清空。
     * 增量重画靠它把每帧成本从 3.1M 像素降到"两块小矩形"。
     */
    private static volatile int sMaskBufState;
    /** buffer 里**当前被挖掉**的那块（下一帧要先还原它）。 */
    private static volatile float sHolePrevX = Float.NaN;
    private static volatile float sHolePrevY = Float.NaN;
    private static volatile float sHolePrevW = Float.NaN;
    private static volatile float sHolePrevH = Float.NaN;
    private static volatile float sHolePrevR = Float.NaN;

    /**
     * hook {@code HyperViewScaleRemoteManager.setMaskSurface(Rect, ViewInfo)}，
     * 在 onAfter 里拿返回值 SurfaceControl + 入参 Rect/ViewInfo 重画那块 buffer。
     *
     * <p>为什么必须重画：{@code drawViewOnCanvas} 把位图**按原尺寸**画在 (0,0)，
     * 而遮罩层会被 {@code setScale(realScale)} 缩小（realScale = viewRect.w / screenW），
     * 于是 buffer 里对应视图矩形的那块可见区 = {@code (0,0)-(bufW, viewRect.h*bufW/viewRect.w)}，
     * 位图只占它的左上角一小块，其余全是**铺色** ⇒ 图标底色深就是黑边、浅就是白边。
     */
    private static boolean installMaskBufferFix(Class<?> rm) {
        if (!CoreConfig.FIX_MASK_BUFFER) {
            key(sLogMask, "setMaskSurface 钩子安装 = 跳过（CoreConfig.FIX_MASK_BUFFER=false）");
            return false;
        }
        // ★ 安装期可证伪日志：把类名、方法签名、"到底有没有这个方法"全打出来
        String sig = describeMethods(rm, "setMaskSurface");
        key("setMaskSurface 目标类 = " + rm.getName() + " 候选方法 = " + sig);

        boolean ok = HookKit.hookAll(rm, "setMaskSurface", new HookKit.Cb("核心.hvs.setMaskSurface") {
            @Override
            protected void onAfter(MethodHookParam p) {
                try {
                    Object sc = p.getResult();
                    android.graphics.Rect buf = (p.args != null && p.args.length > 0
                            && p.args[0] instanceof android.graphics.Rect)
                            ? (android.graphics.Rect) p.args[0] : null;
                    Object vi = (p.args != null && p.args.length > 1) ? p.args[1] : null;
                    boolean drive = sSfActive || sSfStartedInCycle;
                    // ★★★ DEV 闪黑取证（只读）：见 CoreConfig.DEV_FLASH_PROBE 的长注释。
                    //   假设②："manager 只有一个 mMaskSurface 字段，快速切换时后一次会移除/替换
                    //   前一次的 mask，而前一条 leash 可能还在动 ⇒ 那条 leash 的 mask 没了 ⇒ 黑。"
                    //   记录：上一块 mask 是否仍 valid（若仍 valid 就说明"旧 mask 未释放"假设需要修正，
                    //   若已被释放/替换，则假设成立）。
                    if (CoreConfig.DEV_FLASH_PROBE) {
                        String prev = "无";
                        SurfaceControl pm = sPrevMaskSc;
                        if (pm != null) {
                            boolean v = false;
                            try {
                                v = pm.isValid();
                            } catch (Throwable ignored) {
                            }
                            prev = (v ? "★仍valid" : "已失效") + "@" + Integer.toHexString(pm.hashCode());
                        }
                        String dom = "?";
                        try {
                            Object c = callWith(p.thisObject, "getViewDominantColor", vi);
                            if (c instanceof Integer) {
                                dom = "0x" + Integer.toHexString((Integer) c);
                            }
                        } catch (Throwable ignored) {
                        }
                        long since = sPrevMaskAt <= 0 ? -1L : (SystemClock.uptimeMillis() - sPrevMaskAt);
                        // ★★★ 闪黑取证②（只读）：假设①的**决定性数字** —— 这块全屏铺色 mask
                        //   的起始 alpha。`ViewScaleAnimInfo` 由 createViewLeash 记在 sLeashInfo
                        //   （若此刻还是上一轮的旧对象，用 info=hash 能看出来）。
                        Object li = sLeashInfo;
                        String infoDesc = li == null ? "null" : Integer.toHexString(li.hashCode());
                        float aFrom = gf(li, "maskAlphaFrom", Float.NaN);
                        float aTo = gf(li, "maskAlphaTo", Float.NaN);
                        float rs = gf(li, "realScale", Float.NaN);
                        key("【闪黑取证】新 mask 建立：主色=" + dom
                                + " 上一块 mask=" + prev
                                + " 距上次建 mask=" + since + "ms"
                                + " maskAlphaFrom=" + aFrom + " maskAlphaTo=" + aTo
                                + " realScale=" + rs + " info=" + infoDesc
                                + "（<800ms 且上一块仍 valid ⇒ 快速切换重叠）");
                        if (sc instanceof SurfaceControl) {
                            // ★ 取证③：新窗口开始 ⇒ 清空"已见过的层"，让每个参与层重新各打一行。
                            sFlashWindowNames.clear();
                            sMaskDetailLog = 0;
                            sLeashDetailLog = 0;
                            sPrevMaskSc = (SurfaceControl) sc;
                            sPrevMaskAt = SystemClock.uptimeMillis();
                        }
                    }
                    // ★ 每次调用都打（限流 20 条）：无论是否重画
                    key(sLogMask, "setMaskSurface 命中：args="
                            + (buf == null ? "null" : buf.toString())
                            + " viewInfo=" + (vi == null ? "null" : vi.getClass().getSimpleName())
                            + " 返回=" + (sc == null ? "null" : sc.getClass().getSimpleName())
                            + " 本过渡我们驱动=" + drive
                            + (sc instanceof SurfaceControl ? "" : " ⚠返回值不是 SurfaceControl"));
                    if (!CoreConfig.ONLY_FIX_MASK_WHEN_WE_DRIVE || drive) {
                        redrawMaskBuffer(p.thisObject, sc, buf, vi);
                    } else {
                        // ★ 闸门判定也打出来
                        key(sLogMask, "mask buffer 重画跳过：sSfActive=" + sSfActive
                                + " sSfStartedInCycle=" + sSfStartedInCycle
                                + "（原因：本过渡我们没有驱动打开段，"
                                + "ONLY_FIX_MASK_WHEN_WE_DRIVE=" + CoreConfig.ONLY_FIX_MASK_WHEN_WE_DRIVE + "）");
                    }
                } catch (Throwable t) {
                    fail("mask buffer 重画", t);
                }
            }
        });
        key("setMaskSurface 钩子安装 = " + (ok ? "成功" : "失败（该方法可能不存在或签名不符）")
                + " 类=" + rm.getName() + " 方法=" + sig);
        return ok;
    }

    /**
     * 把某个类上所有同名方法的签名拼成一行，用于日志（安装期调用一次，不在热路径）。
     * 找不到也如实返回 {@code (无此方法)}。
     */
    private static String describeMethods(Class<?> c, String name) {
        if (c == null) {
            return "(类为 null)";
        }
        StringBuilder sb = new StringBuilder();
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (!name.equals(m.getName())) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(ps[i].getSimpleName());
                }
                sb.append(") -> ").append(m.getReturnType().getSimpleName());
            }
        } catch (Throwable t) {
            return "(读取失败: " + t + ")";
        }
        return sb.length() == 0 ? "(无此方法)" : sb.toString();
    }

    /**
     * ★ 逐字移植自旧模块 {@code HvsDiagnostics.redrawMaskBuffer}：
     * 清透明 + 位图等比**铺满**"视图矩形对应的那块可见区"。
     */
    /**
     * 位图缺失时的兜底：**不铺纯黑**，改用**系统自己的高斯模糊**（用户建议）。
     *
     * <p>官方 {@code drawViewOnCanvas} 对"位图为 null"的处理是
     * {@code canvas.drawColor(0xff000000)} + {@code Slog.w("…using black")}
     * ⇒ 真机表现 = 用户报的"**快速切换 app 时闪黑**（基本上只有这一个场景出现）"。
     *
     * <p>做法：先确认本机有
     * {@code Transaction.setBackgroundBlurRadius(SurfaceControl,int)}，
     * **再**把这块 mask 清成透明并在它上面挂系统背景模糊 ——
     * 让它下面的内容被**系统**模糊后透出来，视觉上是"糊一下"而不是"黑一下"。
     *
     * <p>顺序很重要：**先确认能力、再清透明**。否则一旦模糊挂不上，我们就把 mask 弄成
     * 全透明了 —— 而 ④/⑥ 已经证明"全透明 ⇒ 应用实时内容到处露"，那比闪黑更糟。
     */
    private static void maskNullFallback(Object sc, Object mgr, Object viewInfo) {
        if (!(sc instanceof SurfaceControl)) {
            return;
        }
        if (!CoreConfig.MASK_NULL_USE_BLUR) {
            return;                                  // 关掉时保持官方行为（铺黑）
        }
        try {
            if (sTxnCls == null) {
                return;
            }
            Method m;
            try {
                m = sTxnCls.getMethod("setBackgroundBlurRadius", SurfaceControl.class, int.class);
            } catch (Throwable t) {
                m = null;
            }
            if (m == null) {
                key(sLogMask, "mask 模糊兜底：本机没有 Transaction.setBackgroundBlurRadius"
                        + " ⇒ 什么都不做（保持官方结果，绝不把 mask 弄成全透明）");
                return;
            }
            Object txn = newTxn();
            if (txn == null) {
                return;
            }
            // ① 清透明（去掉官方的铺色/纯黑）
            android.view.Surface surface = newSurfaceFor((SurfaceControl) sc);
            if (surface != null) {
                try {
                    android.graphics.Canvas c = surface.lockHardwareCanvas();
                    try {
                        c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
                    } finally {
                        surface.unlockCanvasAndPost(c);
                    }
                } finally {
                    try {
                        surface.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
            // ② 挂系统高斯模糊（这就是"铺系统的模糊"）
            m.invoke(txn, (SurfaceControl) sc, Integer.valueOf(CoreConfig.MASK_NULL_BLUR_RADIUS));
            applyTxn(txn);
            if (sMaskNullLog++ < 6) {
                key(sLogMask, "★★ mask 模糊兜底：位图缺失 ⇒ 不铺纯黑，改挂系统高斯模糊 radius="
                        + CoreConfig.MASK_NULL_BLUR_RADIUS + "（官方原行为 = 铺 0xff000000 ⇒ 闪黑）");
            }
        } catch (Throwable t) {
            fail("mask 模糊兜底", t);
        }
    }

    /** 模糊兜底的日志限流。 */
    private static int sMaskNullLog;

    // ======================================================================
    //  ★ 挖洞法：把官方 mask 变成"负一屏整屏快照 + 窗口处透明洞"
    // ======================================================================

    /**
     * 记录 leash 当帧几何，并在**几何真的变了**时重画 mask buffer 的洞。
     *
     * <p>几何来源（实测，见 {@code shots/geo_log.txt}）：MIUI 每 ~8ms 给 leash 写一轮
     * {@code setCrop(0,0,1200,H)} + {@code setScale(s)} + {@code setPosition(x,y)} + {@code setCornerRadius(r)}。
     * 窗口的**屏幕矩形** = {@code (x, y, x + cropW*s, y + cropH*s)}（mask 是 1:1 屏幕对齐的，
     * 所以这个矩形可以直接当 buffer 坐标用）。
     */
    private static void onLeashGeometry(Object txn, String method, Object[] args) {
        if (!CoreConfig.MASK_AS_BACKDROP) {
            return;
        }
        try {
            if ("setScale".equals(method) && args.length >= 2 && args[1] instanceof Number) {
                sLScale = ((Number) args[1]).floatValue();
            } else if ("setPosition".equals(method) && args.length >= 3
                    && args[1] instanceof Number && args[2] instanceof Number) {
                sLPosX = ((Number) args[1]).floatValue();
                sLPosY = ((Number) args[2]).floatValue();
            } else if ("setCrop".equals(method)) {
                // 两种重载：setCrop(sc, l, t, r, b) 与 setCrop(sc, srcRect, dstRect)
                if (args.length >= 5 && args[3] instanceof Number && args[4] instanceof Number) {
                    sLCropW = ((Number) args[3]).floatValue();
                    sLCropH = ((Number) args[4]).floatValue();
                } else if (args.length >= 2 && args[1] instanceof android.graphics.Rect) {
                    android.graphics.Rect r = (android.graphics.Rect) args[1];
                    if (r.width() > 0 && r.height() > 0) {
                        sLCropW = r.width();
                        sLCropH = r.height();
                    }
                }
            } else if ("setCornerRadius".equals(method) && args.length >= 2
                    && args[1] instanceof Number) {
                sLRadius = ((Number) args[1]).floatValue();
            } else {
                return;
            }
            drawBackdropWithHole(txn);
        } catch (Throwable t) {
            fail("挖洞法几何跟踪", t);
        }
    }

    /** 按当帧几何重画 mask buffer：整屏负一屏快照 + 窗口处 CLEAR 出洞。 */
    private static void drawBackdropWithHole(Object txn) {
        SurfaceControl mask = sPrevMaskSc;
        if (mask == null) {
            return;
        }
        android.graphics.Bitmap bmp = WidgetOpenAnim.backdrop();
        if (bmp == null || bmp.isRecycled()) {
            if (!sBackdropMissingLogged) {
                sBackdropMissingLogged = true;
                key("【挖洞法】还没有负一屏快照（点击时没拍到 / 已被回收） ⇒ 本轮不挖洞，"
                        + "并把 mask 放回 alpha=0（免得它用 1.0 盖成纯色块）");
            }
            // 兜底：没有底就别让 mask 以 1.0 盖着 —— 那会变成官方那种纯色块。
            if (txn != null && sTxnCls != null) {
                try {
                    java.lang.reflect.Method ma = sTxnCls.getMethod(
                            "setAlpha", SurfaceControl.class, float.class);
                    ma.invoke(txn, mask, Float.valueOf(0.0f));
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        // 屏幕矩形
        float x = sLPosX, y = sLPosY, s = sLScale, cw = sLCropW, ch = sLCropH;
        boolean hole = !(Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(s)
                || Float.isNaN(cw) || Float.isNaN(ch)) && s > 0f && cw > 0f && ch > 0f;
        float hx = 0f, hy = 0f, hw = 0f, hh = 0f;
        boolean clearAll = false;
        if (hole) {
            hw = cw * s;
            hh = ch * s;
            hx = x;
            hy = y;
            // 去重 + 限流：位置/尺寸变化不足 2px，或距上次不足 16ms（≈60Hz）就跳过。
            long now = SystemClock.uptimeMillis();
            boolean same = !Float.isNaN(sHoleW)
                    && Math.abs(hx - sHoleX) < 2f && Math.abs(hy - sHoleY) < 2f
                    && Math.abs(hw - sHoleW) < 2f && Math.abs(hh - sHoleH) < 2f;
            if (same || now - sHoleAt < 16L) {
                return;
            }
            sHoleX = hx;
            sHoleY = hy;
            sHoleW = hw;
            sHoleH = hh;
            sHoleAt = now;
            // ★ 收尾夹紧：窗口已经铺满 ≥95% 屏幕时，**整块 mask 清空**（= 完全透明）。
            if (hw * hh >= 0.95f * sMaskBufW * sMaskBufH) {
                clearAll = true;
            }
        }
        // ★ 稳态短路：已经"整块清空"且还是要清空 ⇒ 一个像素都不用动（收尾阶段最省）。
        if (clearAll && sMaskBufState == 2) {
            keepMaskOpaque(txn, mask);
            return;
        }
        android.view.Surface surface = newSurfaceFor(mask);
        if (surface == null) {
            return;
        }
        final boolean needFullDraw = !clearAll && sMaskBufState != 1;
        try {
            android.graphics.Canvas c = surface.lockHardwareCanvas();
            try {
                if (clearAll) {
                    // 整块透明：一次 CLEAR 就够，随后进入状态 2
                    c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
                    sMaskBufState = 2;
                    sHolePrevW = Float.NaN;
                } else {
                    if (needFullDraw) {
                        // ★ 全量重画（只在过渡前几帧 / 从"满屏"退回时发生）：
                        //   ① 先**铺底色**——`DecorView.draw()` 出来的负一屏背景常是透明的
                        //      （模糊壁纸是另一个窗口的层），不铺底 = 透明 mask = 黑。
                        //   ② 再画整屏快照。
                        c.drawColor(WidgetOpenAnim.backdropFill());
                        android.graphics.Paint bp =
                                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
                        c.drawBitmap(bmp, null,
                                new android.graphics.RectF(0f, 0f, sMaskBufW, sMaskBufH), bp);
                    } else {
                        // ★ 增量重画（每帧只动"上一帧的洞"这一小块，而不是 3.1M 像素全屏）：
                        //   把上一帧挖掉的那块**还原**（底 + 快照对应区域），再挖新的洞。
                        restorePrevHole(c, bmp, s);
                    }
                    if (hole) {
                        punchHole(c, hx, hy, hw, hh, s);
                        sHolePrevX = hx;
                        sHolePrevY = hy;
                        sHolePrevW = hw;
                        sHolePrevH = hh;
                        sHolePrevR = Float.isNaN(sLRadius) ? 0f : sLRadius * s;
                    } else {
                        sHolePrevW = Float.NaN;      // 还没有几何：整屏盖着
                    }
                    sMaskBufState = 1;
                }
            } finally {
                surface.unlockCanvasAndPost(c);
            }
            keepMaskOpaque(txn, mask);
            if (sBackdropLog++ < CoreConfig.MASK_BACKDROP_LOG_BUDGET) {
                key("【挖洞法】mask=负一屏快照 " + sMaskBufW + "x" + sMaskBufH
                        + " 底色=0x" + Integer.toHexString(WidgetOpenAnim.backdropFill())
                        + (clearAll ? "  ★窗口已满屏 ⇒ 整块 mask 清空（收尾夹紧）"
                        : hole ? "  洞=(" + (int) hx + "," + (int) hy + ") " + (int) hw + "x"
                        + (int) hh + " 圆角=" + (int) (Float.isNaN(sLRadius) ? 0 : sLRadius * s)
                        + (needFullDraw ? "  [全量]" : "  [增量]")
                        : "  洞=暂无几何（先整屏盖着）"));
            }
        } catch (Throwable t) {
            fail("挖洞法重画 mask", t);
        } finally {
            try {
                surface.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 把那笔 Transaction 里的 mask alpha 钉成 1.0（防 MIUI 的 1→0 淡出把我们抹掉）。 */
    private static void keepMaskOpaque(Object txn, SurfaceControl mask) {
        if (txn == null || sTxnCls == null) {
            return;
        }
        try {
            java.lang.reflect.Method ma = sTxnCls.getMethod(
                    "setAlpha", SurfaceControl.class, float.class);
            ma.invoke(txn, mask, Float.valueOf(1.0f));
        } catch (Throwable ignored) {
        }
    }

    /** 增量重画的"还原"：把上一帧挖掉的那块用"底 + 快照"补回来（外扩 2px 防接缝）。 */
    private static void restorePrevHole(android.graphics.Canvas c, android.graphics.Bitmap bmp,
            float s) {
        if (Float.isNaN(sHolePrevW) || sHolePrevW <= 0f || sHolePrevH <= 0f) {
            return;
        }
        android.graphics.Rect pr = new android.graphics.Rect(
                (int) (sHolePrevX - 2f), (int) (sHolePrevY - 2f),
                (int) (sHolePrevX + sHolePrevW + 2f), (int) (sHolePrevY + sHolePrevH + 2f));
        // 必须 clip，否则 drawColor 会把整块 buffer 铺满底色、把快照全抹掉。
        int save = c.save();
        c.clipRect(pr);
        c.drawColor(WidgetOpenAnim.backdropFill());
        // 再把该区域的快照贴回去（src=dst=同一块，1:1 ⇒ 与全量重画像素等价）
        c.drawBitmap(bmp, pr, pr,
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        c.restoreToCount(save);
    }

    /** 在新的窗口位置 CLEAR 出洞（带圆角）。 */
    private static void punchHole(android.graphics.Canvas c, float hx, float hy, float hw, float hh,
            float s) {
        android.graphics.Paint clear = new android.graphics.Paint();
        clear.setAntiAlias(true);
        clear.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.CLEAR));
        float r = Float.isNaN(sLRadius) ? 0f : sLRadius * s;
        if (r > 0.5f) {
            c.drawRoundRect(new android.graphics.RectF(hx, hy, hx + hw, hy + hh), r, r, clear);
        } else {
            c.drawRect(hx, hy, hx + hw, hy + hh, clear);
        }
    }

    private static void redrawMaskBuffer(Object mgr, Object sc, android.graphics.Rect buf,
            Object viewInfo) {
        // ★ 挖洞法：把 mask 画成"负一屏整屏快照 + 窗口处透明洞"（官方的主色铺色/小快照都不再用）。
        if (CoreConfig.MASK_AS_BACKDROP) {
            if (buf != null && buf.width() > 0 && buf.height() > 0) {
                sMaskBufW = buf.width();
                sMaskBufH = buf.height();
            }
            // 先按已有几何画一次（此刻 leash 几何可能还没到，先整屏盖着，下一帧就会开洞）
            sHoleAt = 0L;
            // ★ 新 mask 是**新的 buffer**：必须全量重画，不能沿用上一块的增量状态。
            sMaskBufState = 0;
            sHolePrevW = Float.NaN;
            drawBackdropWithHole(null);
            return;
        }

        // ★ 每一步的前置条件都留痕：这样"没重画"能立刻区分是钩子没中、闸挡了、还是这里的输入不齐。
        if (!(sc instanceof SurfaceControl)) {
            key(sLogMask, "mask buffer 重画中止：返回值不是 SurfaceControl（"
                    + (sc == null ? "null" : sc.getClass().getName()) + "）");
            return;
        }
        if (buf == null) {
            key(sLogMask, "mask buffer 重画中止：args[0] 不是 Rect");
            return;
        }
        if (viewInfo == null) {
            key(sLogMask, "mask buffer 重画中止：args[1]（ViewInfo）为 null");
            return;
        }
        Object bmpO = refl(viewInfo, "getBitmap");
        if (!(bmpO instanceof android.graphics.Bitmap)) {
            key(sLogMask, "mask buffer：ViewInfo.getBitmap() 不是 Bitmap（"
                    + (bmpO == null ? "null" : bmpO.getClass().getSimpleName())
                    + "）⇒ 走模糊兜底（不铺纯黑）");
            maskNullFallback(sc, mgr, viewInfo);
            return;
        }
        android.graphics.Bitmap bmp = (android.graphics.Bitmap) bmpO;
        if (bmp.isRecycled() || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) {
            key(sLogMask, "mask buffer：位图不可用（recycled=" + bmp.isRecycled()
                    + " " + bmp.getWidth() + "x" + bmp.getHeight() + "）⇒ 走模糊兜底");
            maskNullFallback(sc, mgr, viewInfo);
            return;
        }        int bufW = buf.width();
        int bufH = buf.height();
        if (bufW <= 0 || bufH <= 0) {
            key(sLogMask, "mask buffer 重画中止：Rect 尺寸无效 " + bufW + "x" + bufH);
            return;
        }
        Object vrO = refl(viewInfo, "getViewRect");
        float clipH = bufH;
        if (vrO instanceof android.graphics.Rect && ((android.graphics.Rect) vrO).width() > 0) {
            clipH = (float) ((android.graphics.Rect) vrO).height() * bufW
                    / ((android.graphics.Rect) vrO).width();
        }
        if (clipH <= 0f || clipH > bufH) {
            clipH = bufH;
        }
        // ★★ 第 13 轮实验 ⑤：**保留官方的铺色，只把位图挪到"视图矩形"**。
        //
        // 官方（反汇编）：drawColor(背景色 或 主色调) + drawBitmap(bitmap, 0, 0, …)
        //   —— 快照被画在 buffer 的**左上角**，而视图（图标/卡片）其实在 leash 的
        //      (967,1618) / (108,2063) 等处。
        // 假设：**"位图画错位置"这一个 bug 同时解释两个症状** ——
        //   ① 快照该在的地方没有它 ⇒ 露出主色调铺色 = 用户报的"矩形黑白边"；
        //   ② 该显示快照的组件区域显示的是应用实时内容 = "组件里显示应用内容"。
        //
        // 已验证必须**保留铺色**（实验 ④：清掉铺色 ⇒ 整块 mask 透明 ⇒ 应用内容到处露）。
        // 兜底：拿不到铺色颜色时退回实验 ③（铺满整块 buffer）—— 至少没有黑白边。
        int fill = 0;
        Object bgO = call0(viewInfo, "getViewBackgroundColor");
        if (bgO instanceof Integer) {
            fill = ((Integer) bgO).intValue();
        }
        if (fill == 0) {
            // ★ `getViewDominantColor` 是**实例方法**，而实例就是钩子的 thisObject
            //   （`setMaskSurface` 是实例方法）。**不要**用 `Class.forName(...).getInstance()`：
            //   `android.app.hyperviewscale.*` 在**子 ClassLoader** 里，宿主 CL 拿不到 ⇒ 恒 null
            //   ⇒ fill=0 ⇒ 兜底退回 ③（真机表现："基本没有变化"，白测一轮）。
            Object dom = mgr == null ? null : callWith(mgr, "getViewDominantColor", viewInfo);
            if (dom instanceof Integer) {
                fill = ((Integer) dom).intValue();
            }
        }
        android.graphics.Rect vr = (vrO instanceof android.graphics.Rect)
                ? (android.graphics.Rect) vrO : null;
        android.graphics.RectF dst;
        int strategy;
        // 实验 ⑥：只要有视图矩形就用它（不再依赖铺色是否取到）。
        if (vr != null && vr.width() > 0 && vr.height() > 0) {
            float dl = vr.left - buf.left;
            float dt = vr.top - buf.top;
            dst = new android.graphics.RectF(dl, dt, dl + vr.width(), dt + vr.height());
            strategy = 5;
        } else {
            // 兜底 = 实验 ③：cover 铺满整块 buffer
            float s = Math.max(bufW / (float) bmp.getWidth(), bufH / (float) bmp.getHeight());
            float w = bmp.getWidth() * s;
            float h = bmp.getHeight() * s;
            dst = new android.graphics.RectF((bufW - w) / 2f, (bufH - h) / 2f,
                    (bufW + w) / 2f, (bufH + h) / 2f);
            strategy = 3;
        }
        android.view.Surface surface = newSurfaceFor(sc);
        if (surface == null) {
            key(sLogMask, "mask buffer 重画中止：Surface(SurfaceControl) 构造失败");
            return;
        }
        try {
            android.graphics.Canvas c = surface.lockHardwareCanvas();
            try {
                c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
                // ★★ 第 13 轮结论（六次真机实验定案）：**这层铺色必须保留**。
                //    ④ 与 ⑥（都是"不铺色、其余透明"）两次都得到同一结果：**应用实时内容露出来**。
                //    ⑤（铺色 + 快照挪到视图位置）= 用户唯一确认"不显示内容了"的一版。
                //    ⇒ mask 是 1:1 全屏覆盖层（见 initLeashChild 的 setScale(1/realScale) +
                //      setLayer(MAX_VALUE)）：它**不透明的地方就一定盖住下层**，所以
                //        · 铺主色 ⇒ 大矩形色块（深/浅图标就是黑/白边）
                //        · 透明   ⇒ 露出应用实时内容
                //      两者是这个机制下仅有的两个选择；用户在"不显示内容"和"无色块"之间
                //      明确选择了前者（"小部件仍然显示内容"是他主动报的 bug）。
                //    ⇒ **mask 这条线到此搁置**，不再尝试（详见交接文档 §4.11 的六次结果表）。
                if (fill != 0) {
                    c.drawColor(fill);
                }
                android.graphics.Paint paint =
                        new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
                c.drawBitmap(bmp, null, dst, paint);
                strategy = 7;
            } finally {
                surface.unlockCanvasAndPost(c);
            }
            if (sMaskRedrawLog++ < CoreConfig.LOG_BUDGET) {
                // ★ 必须把"走了哪条策略 / 铺色拿到没有"打出来：
                //   实验 ⑤ 第一次真机测试时，因为兜底悄悄退回 ③，用户看到的是"基本没有变化"，
                //   而日志里只有落点矩形 —— 我差点把它误读成"⑤ 无效"。诊断必须自带这个字段。
                key("mask buffer 重画: 策略=" + strategy
                        + " 铺色=0x" + Integer.toHexString(fill)
                        + " buffer=" + bufW + "x" + bufH
                        + " viewRect=" + vrO
                        + " 位图=" + bmp.getWidth() + "x" + bmp.getHeight()
                        + " → 落点 " + dst);
            }
        } finally {
            try {
                surface.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** {@code Surface(SurfaceControl)} 是 @hide，公开 SDK 里没有，只能反射造。 */
    private static android.view.Surface newSurfaceFor(Object sc) {
        try {
            java.lang.reflect.Constructor<?> ctor = sSurfaceCtor;
            if (ctor == null) {
                ctor = android.view.Surface.class.getConstructor(SurfaceControl.class);
                ctor.setAccessible(true);
                sSurfaceCtor = ctor;
            }
            return (android.view.Surface) ctor.newInstance(sc);
        } catch (Throwable t) {
            fail("造 Surface(SurfaceControl)", t);
            sSurfaceCtor = null;
            return null;
        }
    }

    /**
     * 只读取证：这条 leash 到底能不能用、有没有交给 SF。
     *
     * <p>用于区分三种"SF 不回调"的原因：
     * <ol>
     *   <li>{@code isValid=false} ⇒ 句柄已经释放（我们驱动的是死对象）；</li>
     *   <li>{@code isValid=true} 但 {@code setPosition} 抛异常 ⇒ 层已不被 SF 接纳
     *       （例如它已经被 MIUI 从场景里摘掉）——"接受调用但不回调"的典型形态；</li>
     *   <li>两者都正常 ⇒ 说明问题不在句柄，而是 SF/参数层面的（那时再查 mode/帧参数）。</li>
     * </ol>
     * 全程只读：不 apply、不改任何东西。
     */
    private static void probeLeashUsability(SurfaceControl sc) {
        if (sc == null) {
            key(sLogFailsafe, "leash 取证：句柄为 null");
            return;
        }
        boolean valid;
        try {
            valid = sc.isValid();
        } catch (Throwable t) {
            valid = false;
        }
        String writeResult;
        if (sTxnCtor == null || sTxnCls == null) {
            writeResult = "拿不到 Transaction";
        } else {
            Object txn = null;
            try {
                txn = sTxnCtor.newInstance();
            } catch (Throwable t) {
                writeResult = "Transaction 构造失败: " + t;
                key(sLogFailsafe, "leash 取证：isValid=" + valid + " " + writeResult
                        + " 层=" + sLeashShort);
                return;
            }
            try {
                Method setPos = sTxnCls.getMethod("setPosition",
                        SurfaceControl.class, float.class, float.class);
                setPos.invoke(txn, sc, 0f, 0f);      // 写进"未 apply"的事务里，**不提交**
                writeResult = "写入可接受（未 apply）";
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                writeResult = "写入抛异常: " + c;
            }
        }
        key(sLogFailsafe, "leash 取证：isValid=" + valid + " " + writeResult
                + " 层=" + sLeashShort);
    }

    /** 反射调无参方法（含父类）；移植自旧模块的 {@code refl}。 */
    private static Object refl(Object obj, String method) {        if (obj == null) {
            return null;
        }
        try {
            Method m = obj.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Throwable t) {
            for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod(method);
                    m.setAccessible(true);
                    return m.invoke(obj);
                } catch (NoSuchMethodException e) {
                    // 继续往父类找
                } catch (Throwable t2) {
                    return null;
                }
            }
            return null;
        }
    }

    // ======================================================================
    //  ★ 上滑返回的标准观测点补齐（只读；不改任何参数）
    // ======================================================================

    /**
     * 侧滑返回走标准 {@code onClosed}/StateListener，而上滑（HyperOS type=1021 手势）
     * 的可观测点不同 —— 真机反馈"上滑没有返回过渡动画，侧滑有"。
     *
     * <p>这里补**只读**观测点，命中即 {@link #beginReturn(String)}：
     * <ol>
     *   <li>{@code mergeAnimation}（带 TO_BACK 语义的合并）；</li>
     *   <li>{@code reverseAnimationWhenMerge}（MIUI 自己决定反向的那一刻）；</li>
     *   <li>{@code finishAnimation} / {@code reset}（收尾）。</li>
     * </ol>
     * <b>绝不修改任何参数</b>——旧模块在这里改过 {@code mReverseToActivityA}，
     * 造成"系统回桌面、动画却往左"的对拉，那是禁区。
     */
    private static void installMergeObservers(Class<?> rt, Class<?> implAni) {
        if (rt == null) {
            key(sLogInstall, "找不到 HyperViewScaleRemoteTransition（上滑观测点无法补）");
            return;
        }
        key("上滑观测点：已挂在 " + rt.getSimpleName());
        HookKit.hookAll(rt, "mergeAnimation", new HookKit.Cb("核心.hvs.mergeAnimation") {
            @Override
            protected void onBefore(MethodHookParam p) {
                try {
                    String modes = describeChangeModes(p.args);
                    boolean toBack = modes.contains("TO_BACK") || modes.contains("4");
                    key(sLogSwipe, "上滑返回观测点命中：mergeAnimation 类型="
                            + describeTransitionType(p.args) + " changeModes=[" + modes + "]"
                            + (toBack ? " ✓含TO_BACK" : ""));
                    // 注意：mergeAnimation 在**打开段**也会触发（type=1，changeModes 含 TO_BACK），
                    // 所以必须加闸：只有"我们确实接管过打开动画、而且已经不再驱动"时才当作返回信号。
                    //
                    // ★★ 第 13 轮修正：原来的三条判据（toBack / 本周期驱动过 / 已不再驱动）
                    //    在**打开段的尾部 merge** 上也会全部成立 ⇒ 每次打开都被误判成"上滑返回"，
                    //    于是它立刻去"交回控制权 + 停我们的 SF 动画" —— 打开动画被半路掐掉 = 抽。
                    //    真机证据（`shots/exitguard_log.txt`）：
                    //      01.556 `AT_REST → OPENING (SF 动画启动)`      ← 我们刚开始播打开
                    //      02.115 `返回段：已把控制权交回 MIUI（上滑：mergeAnimation 带 TO_BACK 语义）`
                    //    这里再补一条：**必须已经处于稳定展开态（AT_REST）**，
                    //    即"打开动画确实已经播完、用户此刻才可能是在往回走"。
                    if (toBack && sSfStartedInCycle && !sSfActive && state() == AT_REST) {
                        beginReturn("上滑：mergeAnimation 带 TO_BACK 语义");
                    } else {
                        verbose("mergeAnimation 不作为返回信号（toBack=" + toBack
                                + " 本周期驱动过=" + sSfStartedInCycle + " 仍在驱动=" + sSfActive
                                + " 状态=" + stateName(state()) + "）");
                    }
                } catch (Throwable t) {
                    fail("上滑 mergeAnimation 观测", t);
                }
            }
        });
        // ★★ 第 13 轮：本核心**唯一**一处"写 MIUI 参数"的改动，
        //    由 {@link CoreConfig#FORCE_REVERSE_ON_MERGE_1021} 独立控制。
        //
        // 依据 = 源码 + 真机 S4 取证：
        //   HyperViewScaleRemoteTransition.java:549-553
        //     mReverseToActivityA = info.getType() == 2 && mOpenScene;
        //     mReverseToActivityB = info.getType() == 1 && !mOpenScene;
        //     reverseToStart = A || B;  → mAnimationImpl.reverseAnimationWhenMerge(..., reverseToStart, ...)
        //   上滑返回那条过渡是 type=1021（FIRST_CUSTOM+21），两个闸门恒为 false
        //   ⇒ reverseToStart=false ⇒ 小米**根本不播**反向动画 ⇒ 用户看到的"上滑没有返回动画"。
        //   真机：`[SS] S4 calculateTransitionInfo type=1021 … parallelCollectType=RECENTS
        //          info.leashes=[Task=1 mode=3(TO_FRONT), Task=<app> mode=4(TO_BACK), Wallpaper mode=3]`
        //
        // 做法：只把 mReverseToActivityA 置 true，然后让**小米自己的**
        //   mAnimationImpl.reverseAnimationWhenMerge(...) 去决定并播反向动画。
        //   我们不写几何、不碰状态机、不驱动 leash —— 用的是系统的实现。
        //
        // 为什么这次不会重演旧模块"系统回桌面、动画却往左"的对拉：
        //   ① 只在 !swallowOn()（我们没在驱动这条 leash）时才动手；
        //   ② 打开段的方向闸（startOpenSf 的面积判据）保证我们不会去驱动返回段；
        //   ③ 独立开关，一键可关。
        HookKit.hookAll(rt, "handleMergeAnimation", new HookKit.Cb("核心.hvs.handleMerge") {
            @Override
            protected void onBefore(MethodHookParam p) {
                try {
                    if (!CoreConfig.FORCE_REVERSE_ON_MERGE_1021) {
                        return;
                    }
                    Object self = p.thisObject;
                    String typeStr = describeTransitionType(p.args);
                    String modes = describeChangeModes(p.args);
                    boolean openScene = false;
                    try {
                        openScene = de.robv.android.xposed.XposedHelpers
                                .getBooleanField(self, "mOpenScene");
                    } catch (Throwable ignored) {
                    }
                    boolean is1021 = "1021".equals(typeStr);
                    if (!is1021 || !openScene || swallowOn()) {
                        sReverseArm = false;
                        verbose("handleMergeAnimation 不动手：type=" + typeStr
                                + " mOpenScene=" + openScene + " 我们在驱动=" + swallowOn()
                                + " changeModes=[" + modes + "]");
                        return;
                    }
                    // ★★ 这里**只能举旗，不能直接改字段**：
                    //    handleMergeAnimation 紧接着的第 550 行会
                    //    `mReverseToActivityA = info.getType() == 2 && mOpenScene`
                    //    把我们写进去的值原地覆盖掉。真正的落点是它第 553 行调用的
                    //    `mAnimationImpl.reverseAnimationWhenMerge(..., reverseToStart, ...)`
                    //    的**第 2 个实参** —— 由下面挂在 implAni 上的钩子去改。
                    sReverseArm = true;
                    key(sLogSwipe, "★ 强制反向已举旗：handleMergeAnimation type=" + typeStr
                            + " mOpenScene=true ⇒ 准备把 reverseToStart 置 true"
                            + "（交给小米自己的 reverseAnimationWhenMerge 播反向动画）"
                            + " changeModes=[" + modes + "]");
                } catch (Throwable t) {
                    // 这里的异常绝不能外抛（我们在 MIUI 的过渡回调栈上）
                    fail("强制反向 handleMergeAnimation", t);
                }
            }
        });
        // ★★ 第 13 轮：真正的"写入点"。
        //
        // `handleMergeAnimation:553` 是唯一决定"播反向动画还是硬切"的地方：
        //     boolean reverse = mAnimationImpl.reverseAnimationWhenMerge(
        //         mergeTarget, reverseToStart, mReverseToActivityB, view, debugId);
        // 上滑那条过渡是 type=1021 ⇒ 小米算出的 reverseToStart 恒为 false ⇒ 走到
        // :568 `finishAnimation()` = 硬切。这里在**它调用前**把 args[1] 改成 true。
        //
        // 为什么挂在 implAni（HyperViewScaleAnimationImpl）而不是 rt：
        //   该方法声明在实现类上；以前挂在 rt 上，实际一次都没命中过。
        //
        // 判定为什么不在这里做：这里拿不到 TransitionInfo（args[0] 是 IBinder
        //   mergeTarget）。所以由上面 handleMergeAnimation 的钩子举旗，这里消费。
        if (implAni != null) {
            HookKit.hookAll(implAni, "reverseAnimationWhenMerge", new HookKit.Cb("核心.hvs.reverse") {
                @Override
                protected void onBefore(MethodHookParam p) {
                    try {
                        key(sLogSwipe, "上滑返回观测点命中：reverseAnimationWhenMerge args="
                                + java.util.Arrays.toString(p.args));
                        if (sSfStartedInCycle) {
                            beginReturn("上滑：reverseAnimationWhenMerge 被调用");
                        }
                        // ★ 唯一的参数改动：把 reverseToStart 从 false 改成 true
                        if (CoreConfig.FORCE_REVERSE_ON_MERGE_1021 && sReverseArm
                                && p.args != null && p.args.length > 1) {
                            sReverseArm = false;               // 只消费一次
                            boolean before = p.args[1] instanceof Boolean
                                    && (Boolean) p.args[1];
                            p.args[1] = Boolean.TRUE;
                            key(sLogSwipe, "★★ 强制反向生效：reverseToStart "
                                    + before + " → true（小米自己的实现会据此播反向动画）");
                        } else {
                            sReverseArm = false;
                            verbose("reverseAnimationWhenMerge 未改参（开关="
                                    + CoreConfig.FORCE_REVERSE_ON_MERGE_1021 + "）");
                        }
                    } catch (Throwable t) {
                        fail("上滑 reverse", t);
                    }
                }
            });
        } else {
            key(sLogInstall, "HyperViewScaleAnimationImpl 未找到 ⇒ 强制反向下不了手");
        }
    }

    /**
     * ★ 强制反向的"旗"：由 {@code handleMergeAnimation} 的 before 钩子举起
     * （它拿得到 {@code TransitionInfo}，能判 type / mOpenScene），
     * 由 {@code reverseAnimationWhenMerge} 的 before 钩子消费并清掉。
     *
     * <p>两个钩子在同一条线程上同步执行（{@code handleMergeAnimation} 直接调用
     * {@code reverseAnimationWhenMerge}），所以一个 volatile 静态量足够；
     * 万一没被消费，也会在下一次 {@code handleMergeAnimation} 时被重写。
     */
    private static volatile boolean sReverseArm;

    /** 只读：把 TransitionInfo 的类型/changeModes 描述出来（用于日志）。 */
    private static String describeChangeModes(Object[] args) {
        try {
            if (args == null) {
                return "-";
            }
            for (Object a : args) {
                if (a == null) {
                    continue;
                }
                if (!a.getClass().getName().endsWith("TransitionInfo")) {
                    continue;
                }
                Object changes = refl(a, "getChanges");
                if (!(changes instanceof java.util.List)) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                java.util.List<?> list = (java.util.List<?>) changes;
                for (int i = 0; i < list.size() && i < 8; i++) {
                    Object mode = refl(list.get(i), "getMode");
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(modeName(mode)).append('/').append(mode);
                }
                return sb.toString();
            }
        } catch (Throwable ignored) {
        }
        return "-";
    }

    private static String describeTransitionType(Object[] args) {
        try {
            if (args == null) {
                return "?";
            }
            for (Object a : args) {
                if (a != null && a.getClass().getName().endsWith("TransitionInfo")) {
                    return String.valueOf(refl(a, "getType"));
                }
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    /** 把 mode 数值翻译成可读名（0=NONE 1=OPENING 2=CLOSING 3=TO_FRONT 4=TO_BACK 6=CHANGING）。 */
    private static String modeName(Object mode) {
        int m = mode instanceof Number ? ((Number) mode).intValue() : -1;
        switch (m) {
            case 0: return "NONE";
            case 1: return "OPENING";
            case 2: return "CLOSING";
            case 3: return "TO_FRONT";
            case 4: return "TO_BACK";
            case 6: return "CHANGING";
            default: return "M" + m;
        }
    }

    private static Class<?> resolveImplClass(ClassLoader cl, String name) {
        return findQuiet(cl, name);
    }

    /**
     * 路径③：{@code createViewLeash} 返回之后再尝试一次。
     * 若返回值本身就是 {@code SurfaceControl}，优先用它；否则再扫一遍调用方实例字段。
     */
    private static void onLeashCreatedAfter(Object thisObject, Object result) {
        try {
            if (srcIsWrite()) {
                return;
            }
            if (result instanceof SurfaceControl) {
                SurfaceControl sc = (SurfaceControl) result;
                String s = String.valueOf(sc);
                if (hasLayerName(s) ? isLeashLayer(s) : (sLeash == null)) {
                    adoptLeash(sc, SRC_RETURN + "(返回值)");
                    return;
                }
            }
            if (thisObject != null && !srcIsWrite()) {
                scanLeashFields(thisObject, false);
            }
        } catch (Throwable t) {
            fail("createViewLeash 之后取 leash", t);
        }
    }

    /**
     * 解析 HyperViewScale 的实现类。
     *
     * <p>★ 第 8 轮：**逐字照旧文件的取法**（{@code HvsDiagnostics.resolveImpl}）——
     * 优先 {@code HyperViewScaleRemoteStub.getInstance().getClass()}，
     * 而不是先 {@code Class.forName(...RemoteManager, appCl)}。
     * 原因（旧文件注释里写得很清楚）：impl jar 被 {@code MiuiStubRegistry} 装进
     * **子 ClassLoader**，按名字找到的很可能不是真正的实现类 ⇒ 钩子挂到错类上、
     * 表现为"钩子装上了但永远不触发"（mask 修复不生效就是这么来的）。
     */
    private static volatile Class<?> sResolvedHvsImpl;
    private static volatile boolean sLoggedNoHvsInstance;

    private static Class<?> resolveHvsRemoteManager(ClassLoader cl) {
        Class<?> cached = sResolvedHvsImpl;
        if (cached != null) {
            return cached;
        }
        // ① 旧文件的取法：从 stub 的 getInstance() 反推实现类（子 CL 里的那个）
        Class<?> stub = findQuiet(cl, "android.app.hyperviewscale.HyperViewScaleRemoteStub");
        if (stub != null) {
            try {
                Object inst = XposedHelpers.callStaticMethod(stub, "getInstance");
                if (inst != null) {
                    Class<?> impl = inst.getClass();
                    sResolvedHvsImpl = impl;
                    key("HyperViewScale 实现类由 getInstance() 解析 = " + impl.getName()
                            + "（loader=" + (impl.getClassLoader() == null ? "boot" : "非boot") + "）");
                    return impl;
                }
                if (!sLoggedNoHvsInstance) {
                    sLoggedNoHvsInstance = true;
                    key(sLogInstall, "stub.getInstance() 还是 null（impl 尚未绑定），继续等重试");
                }
            } catch (Throwable t) {
                fail("HyperViewScaleRemoteStub.getInstance", t);
            }
        }
        // ② 退回按名字找（可能拿到 stub/接口，只作兜底）
        Class<?> byName = findQuiet(cl, "android.app.hyperviewscale.HyperViewScaleRemoteManager");
        if (byName != null) {
            key(sLogInstall, "HyperViewScale 实现类退回按名字解析 = " + byName.getName()
                    + "（loader=" + (byName.getClassLoader() == null ? "boot" : "非boot")
                    + "）—— 若 mask 钩子不触发，多半是这个类不对");
        }
        return byName;
    }

    private static Class<?> findQuiet(ClassLoader cl, String name) {
        if (cl != null) {
            try {
                return XposedHelpers.findClass(name, cl);
            } catch (Throwable ignored) {
            }
        }
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ======================================================================
    //  leash：句柄与几何
    // ======================================================================

    private static volatile SurfaceControl sLeash;
    private static volatile Object sLeashInfo;
    private static volatile String sLeashShort;
    /** leash 是从哪条路拿到的（每一笔过渡都会打日志）。 */
    private static volatile String sLeashSrc = "无";
    /** 还没遇到 {@code view-leash} 时记下的候选（等不到才会兜底采用）。 */
    private static volatile SurfaceControl sLeashCandidate;
    private static volatile String sLeashCandidateName;
    /** 我们是不是**宽容采用**（非 {@code view-leash}）的 leash —— 一旦拿到真 leash 就要换掉它。 */
    private static volatile boolean sLeashLenient;
    /** {@code createViewLeash} 参数候选（最后才会用）。 */
    private static volatile SurfaceControl sParamCandidate;
    private static volatile String sParamCandidateName;

    /** 距最后一次 leash 写入的时刻（uptimeMillis）。(B) 的"停稳"判据。 */
    private static final AtomicLong sLastLeashWriteAt = new AtomicLong(0L);

    // ---- 四条获取路径的标识（日志里会原样出现）----
    /** ④ 第一笔 leash 写入的目标层 —— 最可靠（"几何写在哪一层，就驱动哪一层"）。 */
    private static final String SRC_WRITE = "第一笔写入目标层";
    /** ② 动画实例字段扫描 —— 旧模块就是靠这条拿到 mLeash 的。 */
    private static final String SRC_FIELD = "字段扫描";
    /** ① createViewLeash 参数 —— 真机上这个参数的 toString 是默认形式（无层名）！ */
    private static final String SRC_ARG = "createViewLeash参数";
    /** ③ createViewLeash 之后新建对象上的再扫一遍。 */
    private static final String SRC_RETURN = "调用后对象";
    private static final String SRC_NONE = "无";

    private static void adoptLeash(SurfaceControl sc, String why) {
        if (sc == null) {
            return;
        }
        if (sc == sLeash && !SRC_NONE.equals(sLeashSrc)) {
            // 同一个句柄（跨周期复用时很常见）：只刷新内部诊断时刻戳。
            sLeashAt = SystemClock.uptimeMillis();
            return;
        }
        sLeash = sc;
        sLeashSrc = why;
        sLeashLenient = !(hasLayerName(String.valueOf(sc)) && isViewLeashLayer(String.valueOf(sc)));
        // 内部诊断用（**不参与耗时计算**，延迟拆解已改为纯局部变量）。
        sLeashAt = SystemClock.uptimeMillis();
        sLastLeashWriteAt.set(sLeashAt);
        String s = String.valueOf(sc);
        sLeashShort = s.length() > 64 ? s.substring(0, 64) : s;
        key(sLogLeash, "leash 来源 = " + why
                + (isTransitionLeashLayer(s) ? "  [★transition-leash 首选]"
                    : isViewLeashLayer(s) ? "  [view-leash 回退]"
                    : "  [非严格 leash，宽容采用]")
                + "  " + sLeashShort);
    }

    /**
     * ★★ 层分类的唯一入口。真机教训（第 5 轮）：
     *
     * <p>真机的 leash 层 toString 是**嵌套形态**：
     * <pre>
     *   Surface(name=Surface(name=Task=1896#72814)/@0x272a9d5_view-leash#72815)
     * </pre>
     * 而第 4 轮我为了排除状态栏加的"嵌套包装一律排除"规则，
     * **把真正的 view-leash 也一起排除了**（探针日志里 #5/#6/#7… 全是它，
     * 全被判"禁止层"）⇒ "20 笔都没见到 view-leash" ⇒ 退化成抓 Task 层。
     *
     * <p>所以现在**顺序固定为**：
     * <ol>
     *   <li>含 {@code view-leash} ⇒ **直接接受**（硬条件，不看嵌套、不看其它）；</li>
     *   <li>命中负向关键字（状态栏/导航栏/blur/mask/letterbox/wallpaper）⇒ 排除，
     *       并把命中的关键字记下来给日志用；</li>
     *   <li>既不含 {@code view-leash} 也不含 {@code task=} ⇒ 视为**纯包装层**排除；</li>
     *   <li>其余 ⇒ 候选（最后兜底才用）。</li>
     * </ol>
     */
    static final int LAYER_TRANSITION_LEASH = 0;  // ★ 最高优先：SF/HVS 真正动画的那族
    static final int LAYER_VIEW_LEASH = 1;        // 次优先（旧行为）
    static final int LAYER_FORBIDDEN = 2;         // 负向关键字命中
    static final int LAYER_WRAPPER = 3;           // 纯包装层（无 leash 名、无 task=）
    static final int LAYER_TASK_OTHER = 4;        // 其它（含 task= 或普通名字）

    /** 返回分类；命中负向关键字时把关键字写进 {@code outKeyword[0]}。 */
    private static int classifyLayer(String s, String[] outKeyword) {
        if (s == null) {
            return LAYER_WRAPPER;
        }
        String low = s.toLowerCase(Locale.US);
        // ① ★★ 最高优先：`transition-leash`。
        //    真机取证（vis_log.txt + SurfaceFlinger）：
        //      · 桌面/系统真正在动画的是 `…_transition-leash#…`
        //        （`WindowTransitionUtil reparent_app_sc … target_leash=…_transition-leash#6001`）；
        //      · 我们原来驱动的 `…_view-leash#…` 被 SF 判 `visible:0`
        //        并 `non-clone unreachable, force stop anim` ⇒ 动画被强停 ⇒ 0 回调。
        //    所以含 transition-leash 的一律优先取用。
        if (low.contains("transition-leash")) {
            return LAYER_TRANSITION_LEASH;
        }
        // ② 次优先：view-leash（旧行为，保留为回退）
        if (low.contains("view-leash")) {
            return LAYER_VIEW_LEASH;
        }
        // ③ 负向关键字
        String hit = forbiddenKeyword(low);
        if (hit != null) {
            if (outKeyword != null && outKeyword.length > 0) {
                outKeyword[0] = hit;
            }
            return LAYER_FORBIDDEN;
        }
        // ④ 纯包装层：既没有 leash 名，也没有 task= ⇒ 没有实体内容
        if (!low.contains("task=")) {
            return LAYER_WRAPPER;
        }
        return LAYER_TASK_OTHER;
    }

    /** 是不是"最高优先"的那族层（transition-leash）。 */
    private static boolean isTransitionLeashLayer(String s) {
        return classifyLayer(s, null) == LAYER_TRANSITION_LEASH;
    }

    /** 是不是"严格 leash 层"（transition-leash 或 view-leash）。 */
    private static boolean isStrictLeashLayer(String s) {
        int c = classifyLayer(s, null);
        return c == LAYER_TRANSITION_LEASH || c == LAYER_VIEW_LEASH;
    }

    /** 负向关键字表；返回命中的那个（便于日志一眼定位），没有则 null。 */
    private static String forbiddenKeyword(String low) {
        if (low.contains("statusbar")) {
            return "statusbar";
        }
        if (low.contains("navigationbar")) {
            return "navigationbar";
        }
        if (low.contains("view_scale_blur")) {
            return "view_scale_blur";
        }
        if (low.contains("view_scale_mask")) {
            return "view_scale_mask";
        }
        if (low.contains("blur")) {
            return "blur";
        }
        if (low.contains("mask")) {
            return "mask";
        }
        if (low.contains("letterbox")) {
            return "letterbox";
        }
        if (low.contains("wallpaper")) {
            return "wallpaper";
        }
        return null;
    }

    /**
     * 层名分类。真机上**只有**从实例字段读出来的那个 SurfaceControl 才有层名
     * （形如 {@code Task=1819#67898)/@0x4e589ca_view-leash#70606)}）；
     * 而 {@code createViewLeash} 的参数 toString 是默认的 {@code SurfaceControl@4dd71e9}，
     * **没有任何层名** —— 第 1 轮就是栽在这里。
     */
    private static boolean hasLayerName(String s) {
        return s != null && s.contains("Surface(name=");
    }

    /** 严格的"这就是 leash 层"判据：含 {@code view-leash} 即成立（硬条件，不看嵌套）。 */
    private static boolean isViewLeashLayer(String s) {
        return classifyLayer(s, null) == LAYER_VIEW_LEASH;
    }

    /** 宽松判据（兜底）：非禁止层、非纯包装层。 */
    private static boolean isLeashLayer(String s) {
        int c = classifyLayer(s, null);
        return c == LAYER_VIEW_LEASH || c == LAYER_TASK_OTHER;
    }

    /** 明确**不是** leash 的层（负向关键字 / 纯包装层）。 */
    private static boolean isForbiddenLayer(String s) {
        int c = classifyLayer(s, null);
        return c == LAYER_FORBIDDEN || c == LAYER_WRAPPER;
    }

    /** 路径标识里是否出现过对应编号（用于覆盖优先级判断）。 */
    private static boolean srcIsWrite() {
        return SRC_WRITE.equals(sLeashSrc);
    }

    /**
     * 记住 {@code ViewScaleAnimInfo} 之类的几何对象；顺带（仅在还没有 leash 时）
     * 从调用方实例的字段里补一次 leash —— 那是**唯一带层名**的来源。
     */
    private static void onLeashCreated(Object thisObject, Object[] args) {
        try {
            SurfaceControl fromArgs = null;
            Object info = null;
            if (args != null) {
                for (Object a : args) {
                    if (a instanceof SurfaceControl) {
                        // 参数里可能有多个 SurfaceControl，取最后一个（真机签名
                        // createViewLeash(TransitionInfo, SurfaceControl, Transaction, ViewScaleAnimInfo, Z, Z, F)
                        // 里 leash 在第 2 位；这里不按位置写死，按"最后一个"兜住变体）。
                        fromArgs = (SurfaceControl) a;
                    } else if (a != null && a.getClass().getSimpleName().contains("AnimInfo")) {
                        info = a;
                    }
                }
            }
            if (info != null) {
                sLeashInfo = info;
            }

            // ② 字段扫描（只要还没有"写入目标层"级别的 leash，就扫 —— 这是主要来源）
            boolean forceScan = SRC_ARG.equals(sLeashSrc) || SRC_RETURN.equals(sLeashSrc);
            boolean scanned = false;
            if (thisObject != null && !srcIsWrite()) {
                scanned = scanLeashFields(thisObject, forceScan);
            }
            if (!scanned && fromArgs != null) {
                // ★ ① 参数**不再立即采用**（第 4 轮教训：它可能是 Task 层，不是 view-leash）。
                //   先记成"参数候选"，等字段扫描/写入目标层给出更好来源；
                //   直到最终兜底（finalizeLeashCandidate）才用它，且会标注"宽容采用"。
                if (sParamCandidate == null && !srcIsWrite()) {
                    sParamCandidate = fromArgs;
                    String s = String.valueOf(fromArgs);
                    sParamCandidateName = s.length() > 60 ? s.substring(0, 60) : s;
                    verbose("参数候选（暂不采用）=" + sParamCandidateName);
                }
            }

            if (sLeash == null) {
                key(sLogLeash, "leash 来源 = " + SRC_NONE
                        + "（本轮尚未拿到；会在过渡开始扫字段、或在写入目标层出现时认下）");
            }
        } catch (Throwable t) {
            fail("读取 leash", t);
        }
    }

    /**
     * 遍历实例（含父类）的 {@code SurfaceControl} 字段找 leash。
     * 真机字段是 {@code mLeash}，其 toString 含 {@code view-leash}。
     *
     * @param force 即使当前已有 leash 也重新扫（用于"换成这一笔的 leash"）
     * @return 是否扫到了 leash
     */
    private static boolean scanLeashFields(Object instance, boolean force) {
        if (instance == null) {
            return false;
        }
        if (srcIsWrite() && !force && leashUsable()) {
            return false;                    // 已经有"写入目标层"级别的句柄，不用换
        }
        boolean found = false;
        try {
            for (Class<?> c = instance.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!SurfaceControl.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    Object v;
                    try {
                        f.setAccessible(true);
                        v = f.get(instance);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (!(v instanceof SurfaceControl)) {
                        continue;
                    }
                    SurfaceControl sc = (SurfaceControl) v;
                    String s = String.valueOf(sc);
                    if (!hasLayerName(s)) {
                        // 没有层名：只有"字段名像 leash 且不是 blur/mask"才敢收（真机 mLeash 有层名，
                        // 这里主要兜住"层名已被回收"的边角情况）。
                        if (f.getName().toLowerCase(Locale.US).contains("leash")
                                && !isForbiddenLayer(s)) {
                            adoptLeash(sc, SRC_FIELD + "(" + c.getSimpleName() + "." + f.getName()
                                    + ",无层名)");
                            found = true;
                        }
                        continue;
                    }
                    if (isLeashLayer(s)) {
                        adoptLeash(sc, SRC_FIELD + "(" + c.getSimpleName() + "." + f.getName() + ")");
                        found = true;
                    }
                }
            }
        } catch (Throwable t) {
            fail("扫描 leash 字段", t);
        }
        return found;
    }

    /**
     * 过渡开始时扫一遍动画实例字段（不论手上有没有 leash）。
     *
     * <p>这一条**不做惰性**：它是主要来源（唯一带层名的那条路），
     * 而且顺带能把"换了一次动画实例"这件事纠正过来。
     * 代价只有十几个字段的取值，可忽略。
     */
    private static void scanLeashFromInstance(Object instance) {
        maybeAdoptLeash(instance, false);
    }

    /** 只有在还没有 leash 时才去扫实例字段（避免旧实例覆盖新句柄）。 */
    private static void maybeAdoptLeash(Object instance, boolean force) {
        if (instance == null) {
            return;
        }
        if (!force && sLeash != null && !SRC_NONE.equals(sLeashSrc) && leashUsable()) {
            return;
        }
        scanLeashFields(instance, force);
    }

    /**
     * 路径④：按"这一笔写入的目标层"认 leash。
     *
     * <p>★ 真机教训（第 3/4 轮）：
     * <ul>
     *   <li>第 3 轮：第一笔可能是 {@code StatusBar#108} ⇒ 已加入禁止名单，只记候选不采信；</li>
     *   <li>第 4 轮：兜底采用时抓到了 {@code Task=1893#72768}（**Task 层，不是 view-leash**）
     *       ⇒ 真 leash 来得晚或形态不同。所以现在**严格层一旦出现就顶掉宽容层**
     *       （{@link #sLeashLenient} 标记），并把动画迁到正确的层上。</li>
     * </ul>
     */
    /** ★ 外部（system_server 探针）声明的"本次过渡权威 leash"：只读取用。 */
    private static volatile SurfaceControl sAuthoritativeLeash;
    private static volatile long sAuthoritativeLeashAt;
    private static volatile String sAuthoritativeLeashName;
    private static final long AUTHORITATIVE_LEASH_TTL_MS = 3000L;

    /**
     * 供 system_server 侧探针调用：登记本次过渡的权威 leash
     * （来自 {@code [SS] S4 calculateTransitionInfo … info.leashes=[…]}，
     * 那是 system_server **自己声明**的本次过渡 leash，比"哪一笔写入先到"可靠）。
     *
     * <p>只读取用：我们不改任何过渡参数，只是拿它当驱动目标。
     */
    public static void setAuthoritativeLeash(SurfaceControl sc) {
        if (sc == null) {
            return;
        }
        sAuthoritativeLeash = sc;
        sAuthoritativeLeashAt = SystemClock.uptimeMillis();
        String s = String.valueOf(sc);
        sAuthoritativeLeashName = s.length() > 60 ? s.substring(0, 60) : s;
        verbose("S4 权威 leash 已登记 = " + sAuthoritativeLeashName);
    }

    private static boolean authoritativeLeashFresh() {
        SurfaceControl sc = sAuthoritativeLeash;
        if (sc == null) {
            return false;
        }
        if (SystemClock.uptimeMillis() - sAuthoritativeLeashAt > AUTHORITATIVE_LEASH_TTL_MS) {
            return false;
        }
        try {
            return sc.isValid();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 宽容判据：不是严格 leash 层就算宽容采用。 */
    private static boolean isStrictLeashLayerOf(SurfaceControl sc) {
        return sc != null && isStrictLeashLayer(String.valueOf(sc));
    }

    private static boolean adoptLeashFromWrite(SurfaceControl target) {
        if (target == null) {
            return false;
        }
        String s = String.valueOf(target);
        String[] kw = new String[1];
        int kindClass = classifyLayer(s, kw);
        probeWrite(s, kindClass, kw[0]);

        if (kindClass == LAYER_FORBIDDEN || kindClass == LAYER_WRAPPER) {
            return false;                    // 状态栏/模糊/遮罩/纯包装：绝不采用
        }
        // 本过渡里**已经确定过**更高优先的层（例如 S4 权威 / transition-leash），
        // 就不允许被 view-leash 或 Task 层顶掉 —— 保证"一次只驱动一条"。
        int currentKind = sLeash == null ? -1 : classifyLayer(String.valueOf(sLeash), null);

        // ★★ 权威优先：如果 system_server 侧已经登记了本次过渡的 leash，
        //    而这一笔写入的目标正好是它 ⇒ 直接采用（来源标注 "S4 权威"）。
        if (authoritativeLeashFresh() && target == sAuthoritativeLeash && sLeash != target) {
            SurfaceControl old = sLeash;
            adoptLeash(target, "S4 权威");
            sLeashLenient = false;
            sLeashCandidate = null;
            if (old != null && old != target) {
                migrateAnimationToNewLeash(old, target, "S4 权威");
            }
            return true;
        }

        if (sLeash == target) {
            if (!srcIsWrite()) {
                sLeashSrc = SRC_WRITE;       // 升格为最高优先级来源
                sLeashAt = SystemClock.uptimeMillis();
                sLeashLenient = !isStrictLeashLayer(s);
            }
            if (isStrictLeashLayer(s) && sLeashLenient) {
                sLeashLenient = false;
                key("leash 升级：同一个句柄被确认为 "
                        + (isTransitionLeashLayer(s) ? "transition-leash" : "view-leash")
                        + "（原为宽容采用）");
            }
            return true;
        }

        // ---- 优先级：transition-leash(0) > view-leash(1) ----
        boolean strict = kindClass == LAYER_TRANSITION_LEASH || kindClass == LAYER_VIEW_LEASH;
        if (strict) {
            // 若手上已经是**同优先或更高**的严格层，就不换（避免来回震荡/双写）
            boolean currentIsStrict = currentKind == LAYER_TRANSITION_LEASH
                    || currentKind == LAYER_VIEW_LEASH;
            boolean upgrade = !currentIsStrict
                    || (kindClass == LAYER_TRANSITION_LEASH && currentKind == LAYER_VIEW_LEASH);
            if (!upgrade) {
                return false;
            }
            SurfaceControl old = sLeash;
            adoptLeash(target, SRC_WRITE);
            sLeashLenient = false;
            sLeashCandidate = null;
            if (old != null && old != target) {
                migrateAnimationToNewLeash(old, target,
                        kindClass == LAYER_TRANSITION_LEASH ? "transition-leash" : "view-leash");
            }
            return true;
        }

        // LAYER_TASK_OTHER：Task 层 —— **只作最后兜底**
        if (sLeash == null) {
            sLeashCandidate = target;
            sLeashCandidateName = s.length() > 60 ? s.substring(0, 60) : s;
        }
        return false;
    }

    /** 把正在跑的 SF 动画从旧层迁到新层上（停旧 → 在新层重起同一条打开动画）。 */
    private static void migrateAnimationToNewLeash(SurfaceControl oldLeash, SurfaceControl newLeash,
            String newKind) {
        // ★★ 第 11 轮修的门控漏洞：这里原来**没有** ENABLE_OPEN_SF 判断，
        //    而"选层升级"（S4 权威 / transition-leash 出现）会走到这里并**直接重起动画**
        //    ⇒ 即使 ENABLE_OPEN_SF=false，打开段仍会被我们接管（真机 34 次）。
        if (!CoreConfig.ENABLE_OPEN_SF) {
            noteOpenSfDisabled("leash 升级到 " + newKind + " 时");
            return;
        }
        try {
            if (sSfActive && sSfAnimId >= 0) {
                Object txn = newTxn();
                if (txn != null) {
                    stopSfOn(txn, oldLeash, sSfAnimId, "leash 升级为 " + newKind);
                    applyTxn(txn);
                }
            }
            sSfActive = false;
            sSfDrivenLeash = null;
            sSfStartedInCycle = false;           // 允许在新层上重起一条
            key("leash 升级：把打开动画迁到 " + newKind + " 层（" + whyFromSrc()
                    + " → 写入目标层）");
            long tMigrate = SystemClock.uptimeMillis();
            startOpenSf(tMigrate, tMigrate);
        } catch (Throwable t) {
            fail("迁移动画到新 leash", t);
        }
    }

    /** 打开段接管被关掉时的统一提示（限流）。 */
    private static void noteOpenSfDisabled(String where) {
        key(sLogOpenGate, "打开段接管已关闭（ENABLE_OPEN_SF=false），本过渡不介入（" + where + "）");
    }

    private static final AtomicInteger sLogOpenGate = new AtomicInteger();

    private static String whyFromSrc() {
        return sLeashSrc;
    }

    /**
     * 打印每一笔几何写入的目标层（前 60 字符）+ **判据结果**，用于判断
     * "view-leash 什么时候出现、被哪条规则接受/排除"。
     *
     * <p>★ 用**独立预算**（{@link AssistantAnimCore#sLogProbe}），
     * 不再和"leash 来源"抢同一个 60 条预算 —— 真机出现过"40 条探针把关键结论挤掉"。
     */
    private static void probeWrite(String layer, int kindClass, String keyword) {
        if (!CoreConfig.LOG_LEASH_WRITE_PROBE || layer == null) {
            return;
        }
        String shortName = layer.length() > 60 ? layer.substring(0, 60) : layer;
        if (sProbeSeen.contains(shortName)) {
            return;                          // 同一个层名只记一次
        }
        if (sProbeCount.incrementAndGet() > CoreConfig.LEASH_WRITE_PROBE_MAX) {
            return;
        }
        sProbeSeen.add(shortName);
        String verdict;
        switch (kindClass) {
            case LAYER_TRANSITION_LEASH:
                verdict = "[★★transition-leash 首选]";
                break;
            case LAYER_VIEW_LEASH:
                verdict = "[view-leash 接受(次优先)]";
                break;
            case LAYER_FORBIDDEN:
                verdict = "[排除: 命中负向关键字=" + keyword + "]";
                break;
            case LAYER_WRAPPER:
                verdict = "[排除: 纯包装层(无 leash 名/无 task=)]";
                break;
            default:
                verdict = "[其它层(Task，仅作兜底)]";
                break;
        }
        key(sLogProbe, "写入目标层#" + sProbeCount.get() + " " + verdict + " " + shortName);
    }

    private static final java.util.Set<String> sProbeSeen =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static final AtomicInteger sProbeCount = new AtomicInteger();

    /** 等不到 {@code view-leash} 时的兜底：优先用写入目标层的候选，其次用参数候选。 */
    private static void finalizeLeashCandidate(String why) {
        if (srcIsWrite() && leashUsable() && !sLeashLenient) {
            return;                          // 已经是严格的写入目标层
        }
        SurfaceControl cand = sLeashCandidate;
        String kind = SRC_WRITE + "(宽容兜底," + why + ")";
        if (cand == null) {
            cand = sParamCandidate;
            kind = SRC_ARG + "(宽容兜底," + why + ")";
        }
        if (cand == null || cand == sLeash) {
            return;
        }
        adoptLeash(cand, kind);
        sLeashLenient = true;
        sLeashCandidate = null;
        sParamCandidate = null;
        key(sLogLeash, "注意：本次没能等到 view-leash 层，**宽容采用**了 " + kind
                + " —— 若观感与 view-leash 不同，请把上面的『写入目标层#N』日志发回");
    }

    private static boolean leashUsable() {
        SurfaceControl sc = sLeash;
        if (sc == null) {
            return false;
        }
        try {
            if (!sc.isValid()) {
                sLeash = null;              // 已释放：丢掉，绝不硬写
                return false;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    // ======================================================================
    //  (A) 打开段：SF 原生动画
    // ======================================================================

    private static final AtomicInteger sAnimIdSeq = new AtomicInteger();

    /** startSFAnimation 的帧回调粒度参数（与旧版验证过的一致）。 */
    private static final int[] SF_FRAME_TIMES = {1, 10, 100};

    /**
     * ★ 第 10 轮：把"桌面参考实现用的那三个值"提成**具名常量**，并标出出处。
     *
     * <p>桌面参考（{@code miui_home_750} 反编译）：
     * <ul>
     *   <li>{@code SfAnimApiWrapper.java:42} — {@code int[] iArr = {1, 10, 100};}</li>
     *   <li>{@code SfAnimApiWrapper.java:56} — **app 层**：
     *       {@code startSfAnim(trans, sc, params, 2, appCallback, handler, iArr, i)}
     *       ⇒ {@code mode = SF_MODE_APP = 2}</li>
     *   <li>{@code SfAnimApiWrapper.java:81/98} — **icon 层**：
     *       {@code …, 1, iconCallback, …} ⇒ {@code mode = SF_MODE_ICON = 1}</li>
     *   <li>{@code SfAnim.java:425-428 / 450} — 最后一个参数是 {@code localSessionId}
     *       （每次动画 +1；回调里用 {@code this.localSessionId == i2} 过滤）</li>
     *   <li>{@code SfAnimApiWrapper.java:53} — 起动画前对 app 层
     *       {@code appTrans.setVisibility(sc, true)}</li>
     * </ul>
     * 结论：**我们原来的 mode=2 / frames={1,10,100} 与桌面完全一致**，
     * 这两个值不是本次"0 回调"的分叉点（最后一个参数语义不同，见
     * {@link CoreConfig#SF_SESSION_ID_MODE}）。
     */
    private static final int SF_MODE_APP = 2;
    private static final int SF_MODE_ICON = 1;
    /** 我们自增的"会话 id"（对应桌面那个 localSessionId）。 */
    private static final AtomicInteger sSfSessionSeq = new AtomicInteger();
    /** 上一次真正传给 SF 的 session id（用于对账日志）。 */
    private static volatile int sSfLastSessionId = -1;

    /**
     * ★ 只读：把 94 个 float 按桌面 {@code SfAnimCompatibilityV2/V3} 的布局**逐项打出来**。
     *
     * <p>目的：真机上要能一眼看出"参数块到底对不对"，而不是只有一个总长度。
     * 布局（8 组 × 9 值 = 72，再加 22 个附加对）：
     * <pre>
     *   [0..8]   centerX  : 2.0, start, end, delay, velocity, damping, response, mass, minVisibleChange
     *   [9..17]  centerY
     *   [18..26] width
     *   [27..35] height
     *   [36..44] ratio(h/w)
     *   [45..53] alpha
     *   [54..62] radius
     *   [63..71] radiusRatio
     *   [72..]   附加对：iconRect / drawWindowMask / shaderColor(R,G,B) / rotation
     * </pre>
     * 默认关闭（见 {@link CoreConfig#SF_VERBOSE_PARAM_DUMP}）。
     */
    private static void dumpSfParams(float[] p) {
        if (p == null || p.length < 72) {
            key(sLogAnim, "参数块异常：长度=" + (p == null ? "null" : p.length));
            return;
        }
        String[] names = {"centerX", "centerY", "width", "height",
                "ratio", "alpha", "radius", "radiusRatio"};
        StringBuilder sb = new StringBuilder("参数块明细(共" + p.length + ") ");
        for (int g = 0; g < 8; g++) {
            int b = g * 9;
            if (b + 8 >= p.length) {
                break;
            }
            sb.append(names[g]).append("[type=").append(f(p[b]))
                    .append(" start=").append(f(p[b + 1]))
                    .append(" end=").append(f(p[b + 2]))
                    .append(" delay=").append(f(p[b + 3]))
                    .append(" vel=").append(f(p[b + 4]))
                    .append(" damp=").append(f(p[b + 5]))
                    .append(" resp=").append(f(p[b + 6]))
                    .append(" mass=").append(f(p[b + 7]))
                    .append(" minVis=").append(p[b + 8]).append("] ");
        }
        key(sLogAnim, sb.toString());
    }

    /**
     * ★ 解析 SF 动画 API 版本（{@code SurfaceControl.getsfAnimVersion2()} → {@code getsfAnimVersion()}）。
     *
     * <p>为什么要打这个：桌面侧 {@code SfAnimApiProxy} 用版本决定用 **V3 还是 V2** 的参数布局
     * （{@code isSystemSupportedV3() = version >= 3}）。参数块虽然都是 94 个 float，
     * 但大图标场景 V3 会**多两个值** —— 版本错了就等于参数错位。
     * 这里**只读**、只打日志，不改参数。
     */
    private static volatile int sSfApiVersion = -1;

    private static int sfApiVersion() {
        int v = sSfApiVersion;
        if (v >= 0) {
            return v;
        }
        v = -1;
        String how = "none";
        try {
            Object o = SurfaceControl.class.getMethod("getsfAnimVersion2").invoke(null);
            if (o instanceof Integer) {
                v = ((Integer) o).intValue();
                how = "getsfAnimVersion2";
            }
        } catch (Throwable ignored) {
        }
        if (v < 0) {
            try {
                Object o = SurfaceControl.class.getMethod("getsfAnimVersion").invoke(null);
                if (o instanceof Integer) {
                    v = ((Integer) o).intValue();
                    how = "getsfAnimVersion";
                }
            } catch (Throwable ignored) {
            }
        }
        sSfApiVersion = v;
        key("SF 动画 API 版本 = " + v + "（来源 " + how + "）"
                + " ⇒ 桌面参考会用 " + (v >= 3 ? "V3（大图标时参数多 2 个值）" : "V2") + " 布局");
        return v;
    }

    /** 我们这次 SF 动画的启动时刻（返回检测的"最小接管时长"基准）。 */
    private static volatile long sSfStartAt;

    /** 速度比率缓存（见 speedRatio()）。 */
    private static volatile float sSpeedRatio = -1f;

    /**
     * 读系统速度比率（与桌面共用 {@code transition_animation_duration_ratio}）。
     *
     * <p>只读一次并缓存：这是一次同步 ContentProvider 查询，
     * 不该出现在"点击 → 动画开始"的主线程热路径上。
     */
    private static float speedRatio() {
        float cached = sSpeedRatio;
        if (cached > 0f) {
            return cached;
        }
        float v = 1.0f;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app != null) {
                Object cr = app.getClass().getMethod("getContentResolver").invoke(app);
                v = android.provider.Settings.Global.getFloat(
                        (android.content.ContentResolver) cr,
                        "transition_animation_duration_ratio", 1.0f);
            }
        } catch (Throwable ignored) {
        }
        if (v <= 0f || v > 1f) {
            v = 1f;
        }
        sSpeedRatio = v;
        verbose("速度比率（首次读取并缓存）= " + v);
        return v;
    }

    /**
     * 延迟拆解链的时刻锚点。
     *
     * <p>结构上是 {@code [点击] → 过渡开始 → 拿到 leash → startSFAnimation}，
     * 但**点击那一刻本核心看不到**（那是应用自己的 onClick），
     * 所以这里只测我们能测的两段：过渡开始→拿到 leash、拿到 leash→起动画。
     * 这两段之和如果是**个位数毫秒**，就说明"点击之后的延迟"发生在本核心之前，
     * 不是我们造成的。
     */
    private static volatile long sTransitionAt;
    /**
     * 内部诊断用：最近一次设置 leash 句柄的时刻。
     *
     * <p>★ 第 8 轮起**不再参与任何耗时计算**（延迟拆解已改为纯局部变量）。
     * 历史上它两次导致日志打出 ~7500 万毫秒的坏值（一次是漏赋值、一次是跨周期复用句柄），
     * 所以彻底把它从计算路径里摘出去了。
     */
    private static volatile long sLeashAt;

    /** 当前 SF 动画句柄。只有 startOpenSf() 写它。 */
    private static volatile int sSfAnimId = -1;
    /** 是否正在由我们驱动。只有 startOpenSf()/stopSf() 写它。 */
    private static volatile boolean sSfActive;
    /**
     * ★★ 驱动器是否**真的活着**。
     *
     * <p>硬不变量：**只有收到过至少一次 SF 回调**（status 或 progress）才为 true。
     * `startSFAnimation` 返回 id **不等于**它在跑 —— 真机出现过
     * "id=2 已调用" 但**一次回调都没来**（系统 load 9.33），
     * 这时如果我们还在吞 MIUI 的 leash 写入，就变成
     * **没有任何人在驱动 + 我们还在挡** ⇒ 画面卡住/错位。
     * 所以"吞写"必须同时要求 {@link #sSfActive} 与它都为真。
     */
    private static volatile boolean sDriverAlive;
    /** 发起 startSFAnimation 的时刻（用于"首回调超时"判据与日志）。 */
    private static volatile long sSfArmedAt;

    /**
     * ★★ 第 13 轮：最近一次"本次动作放弃接管"的时刻（首回调超时的那一刻）。
     *
     * <p>用途见 {@link CoreConfig#SF_ABANDON_EPISODE_MS}：窗口内 {@code startOpenSf} 不再重起，
     * 避免在同一条 leash 上把打开动画从头再来一遍（用户看到"中途打断再出现"）。
     */
    private static volatile long sSfAbandonedAt = -1L;

    /** "本次动作已放弃接管"这条日志的独立限流（见上）。 */
    private static final java.util.concurrent.atomic.AtomicInteger sLogAbandon =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 这是不是本轮动画：防止上一轮的延迟任务误判。 */
    private static volatile int sSfEpoch;
    private static volatile long sSfLastActivityAt;
    /** 本周期是否已经起过一次（防一次过渡起两条）。 */
    private static volatile boolean sSfStartedInCycle;
    /** 本周期我们驱动的那条 leash。 */
    private static volatile SurfaceControl sSfDrivenLeash;

    /**
     * 吞写是否生效。**唯一判据**，全类只有这一处判断 ——
     * 这样"吞写开着但没人驱动"的组合从结构上就不可能出现。
     */
    private static boolean swallowOn() {
        return sSfActive && sDriverAlive && CoreConfig.SWALLOW_MIUI_LEASH_WRITES;
    }

    /**
     * MIUI 过渡开始。这里只清标志、扫 leash，不改任何参数。
     * （旧代码在此处改 mReverseToActivityA / goToState，导致对拉与死机，已彻底删除。）
     */
    private static void onMiuiTransitionStart(Object animInstance) {
        try {
            sTransitionAt = SystemClock.uptimeMillis();
            sSfStartedInCycle = false;
            sSweptThisEpisode = false;      // 新的过渡 = 允许下一次收尾
            sHandedOff = false;             // 新的过渡 = 允许再交回一次
            sReturnGuardArmed = false;
            sLeashCandidate = null;         // 新的过渡 = 候选重新开始攒
            sLeashCandidateName = null;
            sParamCandidate = null;
            sParamCandidateName = null;
            sProbeSeen.clear();
            sUnadoptedWrites = 0;
            sLeashFrameWrites = 0;
            sWroteIntoExitLogged = false;
            // ★ 新的一次打开过渡 = 取消清模糊资格、清掉旧的返回信号。
            //   否则上一轮遗留的资格可能让本轮"打开段结束"又误清一次模糊。
            sSweepArmed = false;
            sSweepPending = false;
            sReturnSignalAt = 0L;
            sReturnSignal = "无";
            resetReturnDetectionBase();
            // ★ 挖洞法：新过渡 ⇒ 上一轮的 leash 几何与洞全部作废（否则会拿旧坐标开洞）。
            sLScale = Float.NaN;
            sLPosX = Float.NaN;
            sLPosY = Float.NaN;
            sLCropW = Float.NaN;
            sLCropH = Float.NaN;
            sLRadius = Float.NaN;
            sHoleX = Float.NaN;
            sHoleY = Float.NaN;
            sHoleW = Float.NaN;
            sHoleH = Float.NaN;
            sHoleAt = 0L;
            // ★ 日志额度**每次过渡复位**（上一版是全局 80 条 ⇒ 洞的曲线记到一半就断了，
            //   看不出它最终有没有长到整屏）。
            sBackdropLog = 0;
            // ★ 新过渡：mask buffer 状态未知（新 mask 建出来时要全量重画）。
            sMaskBufState = 0;
            sHolePrevW = Float.NaN;
            scanLeashFromInstance(animInstance);
            verbose("MIUI 过渡开始（本周期允许起一次 SF 动画）");
        } catch (Throwable t) {
            fail("过渡开始处理", t);
        }
    }

    /** 每笔 leash 写入：定 leash（路径④）→ 记时刻 → 返回探测 → 吞写。 */
    private static void onLeashWrite(String method, XC_MethodHook.MethodHookParam p) {
        try {
            // ★★ (A1) 自重入守卫：我们自己补写 crop 的那一笔**也会走这个钩子**
            //     （HookKit.hookAll 拦的是 Transaction.setCrop 本身）。
            //     不在这里放行的话，这笔会被下面的吞写逻辑吞掉 ⇒
            //     表现就是"日志说写了 crop、画面一点没变"（是最容易误判成 A2 的假象）。
            if (sInOwnCropWrite && Thread.currentThread() == sOwnCropThread) {
                return;
            }
            if (p.args == null || p.args.length == 0 || !(p.args[0] instanceof SurfaceControl)) {
                return;
            }
            // ★★★ DEV 闪黑取证③（**纯只读**：只加日志，不改任何参数、不吞写）：
            //   第 2 轮已证明"mask 自己以 alpha=1.0 起步、铺色=图标主色"确实是闪的一层；
            //   但 `MASK_ALPHA_FROM_ZERO=true`（mask 全程不可见）时**闪黑仍在**
            //   ⇒ **黑的源头在 mask 底下那一层**。本轮把两个盲区补上：
            //     ① 之前**故意排除**了 `sLeash`（应用窗口 leash）—— 现在纳入并标 `★leash`；
            //     ② 建 mask 后 800ms 窗口内，**每个首次出现的目标层**打一行
            //        （用来发现我们根本不知道的"参与层" = 黑的候选）；
            //     ③ `★mask` / `★leash` 的每笔写入（alpha/crop/position/cornerRadius）逐笔打（限流）。
            if (CoreConfig.DEV_FLASH_PROBE && sPrevMaskSc != null && sPrevMaskAt > 0) {
                long gap = SystemClock.uptimeMillis() - sPrevMaskAt;
                if (gap <= 800L) {
                    SurfaceControl tgt = (SurfaceControl) p.args[0];
                    String label;
                    if (tgt == sPrevMaskSc) {
                        label = "★mask";
                    } else if (tgt == sLeash) {
                        label = "★leash";
                    } else {
                        label = String.valueOf(tgt);
                        if (label.length() > 70) {
                            label = label.substring(0, 70);
                        }
                    }
                    boolean first = sFlashWindowNames.add(label);
                    // ★ 计数按目标分开、且**每次建 mask 复位**（之前共用 200 的全局额度，
                    //   被 ★mask 的逐帧行吃光 ⇒ ★leash 的 setScale/setCrop 一条都没打出来，
                    //   而"官方 mask 怎么在被缩放的 leash 里保持正确显示"正是我们要照抄的配方）。
                    boolean detail;
                    if ("★mask".equals(label)) {
                        detail = sMaskDetailLog++ < 40;
                    } else if ("★leash".equals(label)) {
                        detail = sLeashDetailLog++ < 40;
                    } else {
                        detail = false;
                    }
                    if ((first && sFlashWindowNameLog++ < 60) || detail) {
                        StringBuilder sb = new StringBuilder("【闪黑取证】");
                        if (first) {
                            sb.append("窗口新层 ");
                        }
                        sb.append(method).append('(').append(label).append(')');
                        for (int i = 1; i < p.args.length; i++) {
                            sb.append(' ').append(p.args[i]);
                        }
                        sb.append(" 距建mask=").append(gap).append("ms");
                        String out = sb.toString();
                        key(out.length() > 160 ? out.substring(0, 160) : out);
                    }
                }
            }
            // ★★ 路径④：按"这一笔写入的目标层"认 leash。
            //    真机教训：第一笔写入的目标可能是 StatusBar#108 状态栏 —— 那**不是** leash，
            //    此时 adoptLeashFromWrite 只把它记成候选、**不采信**，我们继续等下一笔。
            adoptLeashFromWrite((SurfaceControl) p.args[0]);

            if (p.args[0] != sLeash) {
                // 还没认下 leash：给 view-leash 足够的观察窗口（见 LEASH_UNATTENDED_WRITES），
                // 超过之后才做兜底决定（采用候选，并标注"宽容采用"）。
                if (sLeash == null
                        && ++sUnadoptedWrites >= CoreConfig.LEASH_UNATTENDED_WRITES) {
                    finalizeLeashCandidate("已观察 " + sUnadoptedWrites + " 笔仍未见到 view-leash");
                }
                return;                         // 不是我们认下的那条 leash
            }
            if (CoreConfig.ENABLE_RETURN_HANDOFF && !sSfActive && !sHandedOff
                    && !sWroteIntoExitLogged
                    && (state() == EXITING || state() == AT_REST)) {
                // 我们在退出相位里看到 leash 写入 —— 说明系统正在动这条 leash
                sWroteIntoExitLogged = true;
                key(sLogSwipe, "退出相位里看到 leash 写入（" + method + "），说明系统在驱动这条 leash");
            }
            long now = SystemClock.uptimeMillis();
            sLastLeashWriteAt.set(now);
            // ★ 交回控制权之后的写入要单独计数：这是"谁在驱动 leash"的判据。
            if (sHandedOff && !sSfActive) {
                sWritesSinceHandoff++;
            }
            if (sLeashFrameWrites == 0 && "setCrop".equals(method)
                    && p.args.length >= 5 && p.args[3] instanceof Number) {
                // leash 自身坐标系的宽度（crop 空间），用于把 setScale 归一成像素。
                float cw = ((Number) p.args[3]).floatValue();
                if (cw > 1f) {
                    sLeashNorm = cw;
                }
            }
            sLeashFrameWrites++;
            // ★ 挖洞法：leash 每帧的几何（position/scale/crop/cornerRadius）就是"洞"的位置。
            onLeashGeometry(p.thisObject, method, p.args);
            // 返回探测（零反射、只有几次浮点比较）：必须放在吞写**之前**，
            // 因为吞写之后这笔写入就不会被系统看到，但我们已经据此判定要交回控制权。
            detectReturnOnWrite(method, p.args);
            if (sSfActive) {
                sSfLastActivityAt = now;
                // ★★ 吞写的**唯一**判据：驱动器必须真的活着（收到过 SF 回调）。
                //   "startSFAnimation 返回了 id" **不等于**它在跑 —— 真机出现过
                //   "已调用 id=2 但一次回调都没来"（load 9.33）⇒ 那时我们必须放行 MIUI 的写入，
                //   否则就是"没有任何人在驱动 + 我们还在挡" = 画面卡住/错位。
                if (swallowOn()) {
                    // ★ (A1) 顺手记下"MIUI 本来想写什么 crop"：A2 判据（我们写了、画面却不变）
                    //   要靠这一行区分"我们的写入没生效" vs "裁切根本不是那个变量"。
                    if (CoreConfig.ENABLE_OWN_CROP && "setCrop".equals(method) && p.args.length >= 5) {
                        sMiuiCropWrites++;
                        if (p.args[3] instanceof Number) {
                            sMiuiCropR = ((Number) p.args[3]).floatValue();
                        }
                        if (p.args[4] instanceof Number) {
                            sMiuiCropB = ((Number) p.args[4]).floatValue();
                        }
                    }
                    // 吞掉：设成 thisObject（Transaction 自身的链式返回），
                    // 这样 MIUI 那边的链式调用不会 NPE。
                    p.setResult(p.thisObject);
                    verbose("吞写 " + method);
                } else if (CoreConfig.LOG_VERBOSE) {
                    verbose("放行（首帧放行：SF 尚无回调）" + method);
                }
            }
        } catch (Throwable t) {
            fail("leash 写入处理", t);
        }
    }

    /** 交回控制权之后 leash 被写入了多少笔（"谁在驱动"的判据）。 */
    private static volatile int sWritesSinceHandoff;
    /** 退出相位里是否已经打过"系统在驱动 leash"的日志。 */
    private static volatile boolean sWroteIntoExitLogged;

    /** 还没认下 leash 时观察到的写入笔数。 */
    private static volatile int sUnadoptedWrites;
    /** 认下 leash 之后的写入笔数（用于确定 leash 坐标系宽度）。 */
    private static volatile int sLeashFrameWrites;

    private static void onLeashApplied() {
        // ★ (A1) 我们自己补 crop 的那笔 apply 不算"SF 引擎还活着"：
        //   否则 8ms 一次的心跳会一直刷新 sSfLastActivityAt，把"SF 卡住"的兜底看门狗饿死。
        if (sInOwnCropWrite && Thread.currentThread() == sOwnCropThread) {
            return;
        }
        if (sSfActive) {
            sSfLastActivityAt = SystemClock.uptimeMillis();
        }
    }

    /**
     * 起动画的时机：认下 leash 之后的第一笔 leash 写入，且处在允许的相位。
     *
     * <p>注意 {@code p.args[0] != sLeash} 时必须**直接放弃这一笔**：
     * 真机第一笔写入的目标是 {@code StatusBar#108}，如果在这里用"sLeash 为空"就去起动画
     * （上一版的写法），会拿错误对象/错误几何去驱动 ⇒ 本次已改为继续等下一笔。
     */
    private static void maybeStartOpenSf(XC_MethodHook.MethodHookParam p) {
        // ★ 第 8 轮：延迟测量全部用**局部变量**，不再让任何静态字段参与
        //   （连续两轮都因为静态字段为 0/遗留，打出 ~7500 万毫秒的坏值）。
        final long tEnter = SystemClock.uptimeMillis();
        try {
            if (!CoreConfig.ENABLE_OPEN_SF) {
                // ★ 入口闸：打开段接管整个关掉 ⇒ 不认 leash 的写入目标、不起动画、不起 crop、不吞写
                noteOpenSfDisabled("第一笔 leash 写入");
                return;
            }
            if (p.args == null || p.args.length < 3) {
                return;
            }
            if (!(p.args[0] instanceof SurfaceControl)
                    || !(p.args[1] instanceof Number)
                    || !(p.args[2] instanceof Number)) {
                return;
            }
            if (p.args[0] != sLeash) {
                return;                         // 还不是我们认下的 leash，等下一笔
            }
            if (sSfActive || sSfStartedInCycle) {
                return;
            }
            if (state() == EXITING) {
                return;                         // 缩回段一律不接管
            }
            final long tLeash = SystemClock.uptimeMillis();   // ★ 本周期"认得 leash"的局部时刻
            startOpenSf(tEnter, tLeash);
        } catch (Throwable t) {
            fail("起打开动画", t);
        }
    }

    /**
     * 用框架自带引擎播"组件矩形 → 全屏"的弹簧动画。
     * 94 个 float 的 V3 参数块与旧版验证过的布局完全一致，
     * 只是把屏幕尺寸从硬编码换成 leash 提供的 to 矩形。
     *
     * @param tEnter 进入 {@code maybeStartOpenSf} 的时刻（局部量，由调用方传入）
     * @param tLeash "认得 leash"的时刻（局部量，由调用方传入）
     */
    private static void startOpenSf(long tEnter, long tLeash) {
        // ★★ 第 11 轮：**最后一道闸**。放在函数第一行，保证无论将来谁调用它，
        //    只要 ENABLE_OPEN_SF=false 就绝不 startSFAnimation / 不起 crop / 不吞写。
        if (!CoreConfig.ENABLE_OPEN_SF) {
            noteOpenSfDisabled("startOpenSf 入口");
            return;
        }
        // ★★ 第 13 轮：**缩回段绝不接管**（这条判据原来是漏的）。
        //
        // 真机证据（`shots/route3_log.txt`）：
        //   20:16:55.036  状态 AT_REST → EXITING  (hideOverlay(1))     ← 关闭开始
        //   20:16:55.065  状态 EXITING → OPENING  (SF 动画启动)        ← 29ms 后我们自己又起了一条打开动画
        // 用户观感 = "动画还是抽" —— 关闭动画正播着，被我们这条打开动画抢掉。
        //
        // 为什么会漏：{@code maybeStartOpenSf} 里本来就有 `if (state() == EXITING) return;`，
        // 但 **`migrateAnimationToNewLeash`（leash 升级）这条路直接调 startOpenSf，没有这个判据**。
        // 放在这里做"最后一道闸"，两条调用路都能罩住；而且不会挡住正常的打开
        // （正常打开时状态是 IDLE/OPENING，不会是 EXITING）。
        if (state() == EXITING) {
            key(sLogAnim, "打开段：当前是缩回段（EXITING）⇒ 不接管（否则会和关闭动画抢）");
            return;
        }
        // ★★ 第 13 轮：**一次用户动作只尝试接管一次**（见 CoreConfig.SF_ABANDON_EPISODE_MS）。
        //    首回调超时后，MIUI 大约 150ms 又会起一条新过渡，把"本周期已驱动过"复位，
        //    于是我们会在**同一条 leash** 上把打开动画从头再起一遍 ——
        //    用户原话："不是曲线抽搐，而是中途打断再出现"。
        //    真机证据：id=6 超时 → 150ms 后 id=7 重起 → id=7 在 7ms 内 status=0 结束。
        final long abandonedAt = sSfAbandonedAt;
        if (abandonedAt > 0L) {
            final long dt = SystemClock.uptimeMillis() - abandonedAt;
            if (dt < CoreConfig.SF_ABANDON_EPISODE_MS) {
                // ⚠️ 这里在放弃窗口内会被**每笔 leash 写入**触发（实测 11ms 一条），
                //    必须走独立限流：挂 sLogAnim 会把进程级预算吃光、静默掉其它诊断
                //    （第 13 轮已经在方向闸上踩过一次同样的坑）。
                if (sLogAbandon.incrementAndGet() <= 6) {
                    key(sLogAnim, "打开段：本次动作已放弃接管（首回调超时后 " + dt
                            + "ms < " + CoreConfig.SF_ABANDON_EPISODE_MS + "ms）⇒ 不重起"
                            + "（避免【打断再重播】）");
                }
                return;
            }
        }
        if (!leashUsable()) {
            key(sLogAnim, "打开段：leash 无效，改为不干预（交给 MIUI 自己的过渡）");
            return;
        }
        if (sSfCallbackCls == null) {
            key(sLogAnim, "打开段：本机没有 OnSFAnimationCallback，不干预");
            return;
        }
        SurfaceControl leash = sLeash;
        Object info = sLeashInfo;
        if (info == null) {
            key(sLogAnim, "打开段：拿不到几何（ViewScaleAnimInfo），改为不干预");
            return;
        }

        float wFrom = gf(info, "widthFrom", 128f);
        float hFrom = gf(info, "heightFrom", 128f);
        float cxFrom = gf(info, "centerFromX", 600f);
        float cyFrom = gf(info, "centerFromY", 1304f);
        float cornerFrom = gf(info, "fromCorner", 36f);
        float cornerTo = gf(info, "toCorner", 174f);

        float wTo = gf(info, "widthTo", 0f);
        float hTo = gf(info, "heightTo", 0f);
        float cxTo = gf(info, "centerToX", 0f);
        float cyTo = gf(info, "centerToY", 0f);
        if (wTo <= 1f || hTo <= 1f) {
            wTo = 1200f;
            hTo = 2608f;
            cxTo = wTo / 2f;
            cyTo = hTo / 2f;
            key(sLogAnim, "打开段：ViewScaleAnimInfo 没有 to 几何，用兜底全屏 "
                    + wTo + "x" + hTo);
        }
        if (wFrom <= 1f || hFrom <= 1f) {
            key(sLogAnim, "打开段：from 几何无效，改为不干预");
            return;
        }

        // ★★ 第 13 轮：**方向闸**（"返回动画飘了"的真正根因，真机取证）。
        //
        // 事实（shots/crop3_log.txt）：
        //   19:02:48.503 `leash 升级：把打开动画迁到 view-leash 层`
        //   19:02:48.505 `startSFAnimation 已调用 id=4 源=(0,0 1200x2608) 目标=(967,1618 128x128)`
        //   19:02:48.505 `crop 首帧 画布=1200.0x128.0 s=20.375`
        //   19:02:57.560/561 同样一对（id=9）
        //   —— 全部 30 次 start 的**同一毫秒**都先有"leash 升级"那一行：
        //      说明本机实际入口几乎全是
        //      `adoptLeash → migrateAnimationToNewLeash → startOpenSf`。
        //
        // 而缩回（上滑返回）时 MIUI 的 ViewScaleAnimInfo 已经是
        // **源 = 整屏 → 目标 = 组件**（反向几何）。原来的 startOpenSf
        // **没有任何方向判断**，于是我们用自己的弹簧（+ 自己的 crop）替 MIUI
        // 播完了整个返回动画 —— 这正是用户说的"返回飘了 / 走咱们失败的路"，
        // 也是 `画布=1200x128`（把"目标"当画布）这个荒谬值的来源。
        //
        // 判据只用几何（不依赖状态机命名）：打开动画一定是**变大**。
        // 面积不增 ⇒ 这是返回方向 ⇒ 一根手指都不碰，完全交给 MIUI 自己播。
        //
        // ★ 日志走**独立类别** {@code sLogReverse}（不是 sLogAnim）：
        //   这一类判断在一笔过渡里会被触发几十次，挂 sLogAnim 会把整条诊断通道吃光。
        if (wTo * hTo <= wFrom * hFrom) {
            key(sLogReverse, "打开段：几何是缩小（" + f(wFrom) + "x" + f(hFrom)
                    + " → " + f(wTo) + "x" + f(hTo) + "）⇒ 返回方向，不接管（交给 MIUI）");
            return;
        }

        // ★★ 第 13 轮：**NaN 闸**（真机取证：`shots/dir_log.txt` 19:07:45.402
        //    `返回段备选：startSFAnimation 已调用 id=10 源=(NaN,2185.0 477.0x244.0)`）。
        //    NaN 进到 94 个 float 的参数块里，等于把整条弹簧交给未定义行为
        //    —— 真机上表现为"起了但没人回调"（同一次也是首回调超时）。
        //    宁可不干预，也绝不把 NaN 递给框架。
        if (bad(wFrom) || bad(hFrom) || bad(wTo) || bad(hTo)
                || bad(cxFrom) || bad(cyFrom) || bad(cxTo) || bad(cyTo)) {
            key(sLogReverse, "打开段：几何含 NaN/Inf（" + f(wFrom) + "x" + f(hFrom)
                    + " → " + f(wTo) + "x" + f(hTo) + " 源中心(" + f(cxFrom) + ","
                    + f(cyFrom) + ") 目标中心(" + f(cxTo) + "," + f(cyTo)
                    + ")）⇒ 不接管（交给 MIUI）");
            return;
        }

        float srcX = cxFrom - wFrom / 2f;
        float srcY = cyFrom - hFrom / 2f;
        float dstX = cxTo - wTo / 2f;
        float dstY = cyTo - hTo / 2f;

        // 速度总闸：与桌面共用同一个系统设置。
        //
        // ★ 延迟修复：`Settings.Global.getFloat` 是一次**同步 ContentProvider 查询**，
        //   放在"点击 → 动画开始"的主线程热路径上是不该做的事（首次还要等 AMS）。
        //   现在只在第一次读，之后用缓存值（用户在设置里改了速度，重启进程后生效）。
        float speed = speedRatio();
        if (speed <= 0f) {
            speed = 1.0f;
        }
        if (speed > 1f) {
            speed = 1f;
        }
        // ★ 用户反馈"过弹" → 几何用临界阻尼（damping=1.0，不过冲）；ratio 同理。
        float dGeo = 1.0f;
        float rGeo = 0.444f * speed;
        float dRatio = 1.0f;
        float rRatio = 0.314f * speed;
        float dAlpha = 0.99f;
        float rAlpha = 0.314f * speed;

        float[] block = new float[94];
        int k = 0;
        k = put9(block, k, srcX + wFrom / 2f, dstX + wTo / 2f, dGeo, rGeo);   // centerX
        k = put9(block, k, srcY + hFrom / 2f, dstY + hTo / 2f, dGeo, rGeo);   // centerY
        k = put9(block, k, wFrom, wTo, dGeo, rGeo);                           // width
        k = put9(block, k, hFrom, hTo, dGeo, rGeo);                           // height
        k = put9(block, k, hFrom / wFrom, hTo / wTo, dRatio, rRatio);         // ratio = h / w
        k = put9(block, k, 1.0f, 1.0f, dAlpha, rAlpha);                       // alpha
        k = put9(block, k, cornerFrom, cornerTo, dGeo, rGeo);                 // radius
        float dS = (float) Math.sqrt(wFrom * wFrom + hFrom * hFrom);
        float dE = (float) Math.sqrt(wTo * wTo + hTo * hTo);
        k = put9(block, k, cornerFrom / dS, cornerTo / dE, dGeo, rGeo);       // radiusRatio
        // 附加对：图标矩形 / 遮罩 / 着色 / 旋转 —— 与旧版验证过的 V3 布局逐字一致，
        // 共 22 个值。8×9=72，72+22=94，正好填满参数块。
        // ★ 不要"顺手精简"这个数组：少一个值就会整体错位。
        float[] extra = {
                -1f, srcX, -1f, srcY, -1f, wFrom, -1f, hFrom,
                -1f, 0f, -1f, 0f, -1f, 0f, -1f, 0f, -1f, 0f,
                -1f, 0f, -1f, 0f,
        };
        for (float v : extra) {
            if (k < block.length) {
                block[k++] = v;
            }
        }
        if (k != block.length) {
            // 参数块长度不对 ⇒ 布局错位 ⇒ 绝不能交给系统（宁可不干预）。
            key(sLogAnim, "打开段：参数块长度 " + k + " != " + block.length
                    + "，布局异常，放弃接管（交给 MIUI）");
            return;
        }

        try {
            Object txn = newTxn();
            if (txn == null) {
                key(sLogAnim, "打开段：Transaction 构造失败，不干预");
                return;
            }
            // 同一 leash 上不允许两条 SF 动画：先把上一次的停掉（只停我们自己的 id）
            if (sSfAnimId >= 0) {
                stopSfOn(txn, leash, sSfAnimId, "起新动画前清旧");
            }

            int id = sAnimIdSeq.incrementAndGet();
            Object callback = makeSfCallback(id);
            Method start = sStartSfMethod;
            if (start == null) {
                key(sLogAnim, "打开段：startSFAnimation 方法不可用，不干预");
                return;
            }
            // ★ 对齐桌面（SfAnimApiWrapper.java:53）：起动画前让 app 层可见。
            //   桌面每次 startSfAnim 之前都会 appTrans.setVisibility(sc, true)，
            //   我们以前没做。这是"与参考实现逐个对齐"的第二个差异点。
            if (CoreConfig.SF_SET_VISIBILITY_BEFORE_START) {
                try {
                    Method setVis = sTxnCls.getMethod("setVisibility",
                            SurfaceControl.class, boolean.class);
                    setVis.invoke(txn, leash, Boolean.TRUE);
                } catch (Throwable t) {
                    verbose("setVisibility(true) 不可用: " + t);
                }
            }
            final int sessionId = sessionIdForNextAnim();
            Object startRet = start.invoke(txn, leash, block, SF_MODE_APP, callback, MAIN,
                    SF_FRAME_TIMES, sessionId);
            final long tSfStart = SystemClock.uptimeMillis();   // ★ 局部量
            applyTxn(txn);
            final long tApply = SystemClock.uptimeMillis();     // ★ 局部量

            sSfAnimId = id;
            sSfActive = true;
            // ★★ 关键：**先不要相信它真的在跑**。
            //    只有收到第一次 SF 回调，sDriverAlive 才会变 true，吞写才会开始
            //    （在此之前 MIUI 的写入一律放行 —— 这就是"首帧放行"）。
            sDriverAlive = false;
            sSfArmedAt = tSfStart;
            final int epoch = ++sSfEpoch;
            sSfStartedInCycle = true;
            sSfDrivenLeash = leash;
            // ★ (A1) 自补 crop（默认关闭，见 CoreConfig.ENABLE_OWN_CROP）：
            //   与上面 applyTxn 的几何在**同一主线程回合**里提交 ⇒ 落在同一 vsync 帧，
            //   满足"首帧必须与几何同帧"（否则第一帧就是"窗口已缩小、裁切还是旧值"）。
            startOwnCrop(leash, wFrom, hFrom, wTo, hTo, dGeo, rGeo);
            // 记录源/目标几何：返回段（含备选方案 D）要用它当"回家的目标"。
            sSrcCx = cxFrom;
            sSrcCy = cyFrom;
            sSrcW = wFrom;
            sSrcH = hFrom;
            sSrcCorner = cornerFrom;
            sDstCorner = cornerTo;
            resetReturnDetectionBase();
            long now = SystemClock.uptimeMillis();
            sSfStartAt = now;                   // ★ 返回检测的"最小接管时长"从这一刻起算
            sSfLastActivityAt = now;
            sLastLeashWriteAt.set(now);
            setState(OPENING, "SF 动画启动");

            key("打开段：startSFAnimation 已调用 id=" + id + " 参数个数=" + k
                    + " 源=(" + f(srcX) + "," + f(srcY) + " " + f(wFrom) + "x" + f(hFrom) + ")"
                    + " 目标=(" + f(dstX) + "," + f(dstY) + " " + f(wTo) + "x" + f(hTo) + ")"
                    + " 速度比率=" + speed + " leash=" + sLeashShort);
            key(sLogFailsafe, "已发起 SF 动画 id=" + id + "：**暂不吞写**（首帧放行），"
                    + CoreConfig.SF_FIRST_CALLBACK_MS + "ms 内等首回调；"
                    + "等不到就立刻释放控制权");
            // ★ 对账日志：一眼看出"我们注册的回调接口/SF 期望的接口"是否同一份
            key(sLogFailsafe, "回调已注册：iface=" + sSfCallbackCls.getName()
                    + "@" + clDesc(sSfCallbackCls.getClassLoader())
                    + " proxy=" + callback.getClass().getName()
                    + "@" + clDesc(callback.getClass().getClassLoader())
                    + " 来自implCL=" + sSfCallbackClsFromImplCl
                    + " proxy implements iface=" + sSfCallbackCls.isInstance(callback));
            // ★ startSFAnimation 的**返回值**：负值/异常码 = SF 直接拒绝；
            //   正值 = SF 接受了这次请求（那"0 回调"就是 SF 侧没跑，而不是被拒）。
            key(sLogFailsafe, "startSFAnimation 返回 = " + startRet
                    + "（" + (startRet instanceof Number && ((Number) startRet).intValue() < 0
                        ? "⚠负值 = SF 拒绝了这次请求" : "非负 = SF 接受") + "）"
                    + " 实参: mode=" + SF_MODE_APP
                    + " frames=" + java.util.Arrays.toString(SF_FRAME_TIMES)
                    + " sessionId=" + sessionId
                    + " setVisibility=" + CoreConfig.SF_SET_VISIBILITY_BEFORE_START
                    + " sfApiVersion=" + sSfApiVersion
                    + " 签名=" + start.getName() + java.util.Arrays.toString(start.getParameterTypes()));
            if (CoreConfig.SF_VERBOSE_PARAM_DUMP) {
                dumpSfParams(block);
            }
            // ★ 只读取证：确认这条 leash 本身可用（给"SF 不回调"的判断提供依据）
            probeLeashUsability(leash);

            if (CoreConfig.LOG_LATENCY_CHAIN) {
                // ★ 第 8 轮：**全部用局部变量**，不管静态字段对不对，这行都不可能再打出坏值。
                key("延迟拆解：进入→认得leash=" + (tLeash - tEnter) + "ms，"
                        + "认得leash→startSFAnimation=" + (tSfStart - tLeash) + "ms，"
                        + "startSFAnimation→apply=" + (tApply - tSfStart) + "ms；"
                        + "★本核心从进入→apply总共=" + (tApply - tEnter) + "ms");
            }

            // ★ 失败保险①②：首回调超时 ⇒ 立刻释放吞写（硬不变量，默认开）
            if (CoreConfig.SF_FAILSAFE_RELEASE) {
                armFirstCallbackFailsafe(id, epoch);
            }
            armSfWatchdog(id, CoreConfig.SF_FALLBACK_STOP_MS);
        } catch (Throwable t) {
            // 起不来就彻底放手：不改任何参数，让 MIUI 自己播。
            fail("startSFAnimation", t);
            sSfActive = false;
            sDriverAlive = false;               // ★ 吞写立刻失效
            sSfStartedInCycle = true;           // 本次过渡不再重试，避免每帧重试
            sSfDrivenLeash = null;
        }
    }

    /**
     * ★★ 失败保险：{@code SF_FIRST_CALLBACK_MS} 内没等到任何 SF 回调，
     * 就**立刻释放吞写**并把控制权交回 MIUI。
     *
     * <p>为什么必须有：真机出现过 "startSFAnimation id=2 已调用" 但**一次回调都没来**
     * （开机 5 分钟、load 9.33），此时我们仍在吞写 ⇒ 没有任何人在驱动 + 我们还在挡
     * ⇒ 窗口停在半途/错位（用户看到的"卡住/乱掉"）。
     *
     * <p>注意：释放吞写是**第一位**的，停动画只是"尽力而为"。
     */
    private static void armFirstCallbackFailsafe(final int id, final int epoch) {
        postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (epoch != sSfEpoch || sSfAnimId != id) {
                        return;                          // 已经是新一轮动画
                    }
                    if (sDriverAlive) {
                        return;                          // 回调已到，无需释放
                    }
                    sDriverAlive = false;
                    sSfActive = false;                   // ★ 第一件事：松手（吞写立即失效）
                    // ★★ 第 13 轮：记下"本次动作放弃了接管"，让 startOpenSf 在窗口内不再重起
                    //    （否则同一条 leash 上会把打开动画从头再来一遍 = "打断再出现"）。
                    sSfAbandonedAt = SystemClock.uptimeMillis();
                    long waited = SystemClock.uptimeMillis() - sSfArmedAt;
                    key("SF 首回调超时（" + waited + "ms）⇒ 已释放吞写，交回 MIUI 收尾"
                            + "（绝不留下卡住态）id=" + id);
                    // ★ 失败取证（只读）：判断是"leash 无效"、"没交给 SF"，还是"SF 就是不回调"
                    SurfaceControl dl = sSfDrivenLeash != null ? sSfDrivenLeash : sLeash;
                    probeLeashUsability(dl);
                    key(sLogFailsafe, "失败取证：startSFAnimation 用的接口="
                            + (sSfCallbackCls == null ? "null" : sSfCallbackCls.getName())
                            + "@" + clDesc(sSfCallbackCls == null ? null
                                : sSfCallbackCls.getClassLoader())
                            + " 授权来源=implCL(" + sSfCallbackClsFromImplCl + ")"
                            + " 期间MIUI写入笔数=" + sWritesSinceHandoff
                            + "（若为 0，说明我们既没驱动、也没放行到任何写入）");
                    // 尽力停掉我们那条动画；失败/异常都无所谓，控制权已经放开了。
                    try {
                        SurfaceControl leash = sSfDrivenLeash != null ? sSfDrivenLeash : sLeash;
                        if (leash != null && sSfAnimId >= 0) {
                            Object txn = newTxn();
                            if (txn != null) {
                                stopSfOn(txn, leash, sSfAnimId, "首回调超时");
                                applyTxn(txn);
                            }
                        }
                    } catch (Throwable t) {
                        fail("stopSF 失败但已释放控制权", t);
                    } finally {
                        sSfDrivenLeash = null;
                    }
                    // 之后一律不再吞写，直到下一轮 onMiuiTransitionStart 重置。
                } catch (Throwable t) {
                    fail("首回调保险", t);
                }
            }
        }, CoreConfig.SF_FIRST_CALLBACK_MS);
    }

    /** 回调代理：只认 status（args[0]）与 id（args[1]），只在 status!=1 时通知状态机。 */
    private static Object makeSfCallback(final int myId) {
        return Proxy.newProxyInstance(sSfCallbackCls.getClassLoader(),
                new Class<?>[]{sSfCallbackCls},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] args) {
                        try {
                            String name = m.getName();
                            int status = Integer.MIN_VALUE;
                            int id = myId;
                            if (args != null) {
                                if (args.length >= 1 && args[0] instanceof Integer) {
                                    status = ((Integer) args[0]).intValue();
                                }
                                if (args.length >= 2 && args[1] instanceof Integer) {
                                    id = ((Integer) args[1]).intValue();
                                }
                            }
                            if ("onSFAnimationStatusCallback".equals(name)) {
                                onSfStatus(status, id, myId);
                            } else {
                                verbose("SF 回调 " + name + " args=" + java.util.Arrays.toString(args));
                            }
                        } catch (Throwable t) {
                            fail("SF 回调", t);
                        }
                        return null;
                    }
                });
    }

    private static void onSfStatus(int status, int id, int myId) {
        if (id != myId) {
            // 不是我们这条动画的回调：忽略（防止上一条的收尾污染本周期）。
            verbose("忽略过期回调 id=" + id + "（当前 " + myId + "）");
            return;
        }
        // ★★ 首回调 = 驱动器确实活着 ⇒ 从这里开始才允许吞写（首帧放行结束）。
        if (!sDriverAlive) {
            sDriverAlive = true;
            long dt = SystemClock.uptimeMillis() - sSfArmedAt;
            key("SF 首回调已到（" + dt + "ms）⇒ 开始吞写 id=" + id + " status=" + status);
        }
        sSfLastActivityAt = SystemClock.uptimeMillis();
        if (status == 1) {
            key(sLogAnim, "SF 回调：动画运行中 id=" + id);
            setState(OPENING, "SF status=1");
            return;
        }
        // status != 1 → 结束（真机取值为 0）
        key("打开段：SF 动画结束 id=" + id + " status=" + status);
        // ★ (A1) 正常播完 ⇒ 停心跳并把 crop 复位成整幅画布（防"内容被裁掉"残留）。
        endOwnCrop("SF 回调结束 status=" + status, true);
        sSfActive = false;
        sDriverAlive = false;
        sSfDrivenLeash = null;
        sLastLeashWriteAt.set(SystemClock.uptimeMillis());
        setState(AT_REST, "SF status=" + status);
        // ★ 第 6 轮修掉的 bug：这里**不能再**叫醒收尾看门狗。
        //   打开段结束 ≠ 可以清模糊 —— 打开动画自带的背景模糊正是这时候在显示，
        //   上一版在这里 arm 了看门狗，于是在"应用刚铺满"时就跑了标准收尾，
        //   用户看到"背景模糊丢了"。
        //   清模糊只允许由"返回/关闭信号"触发（见 scheduleBlurSweep / attemptBlurSweep 的闸 0）。
    }

    /**
     * 兜底看门狗。
     *
     * <p>★ 第 9 轮：**第一件事是清掉吞写标志**，然后才是停动画 ——
     * 之前"超时兜底"只调了 stopSf，一旦 stopSFAnimation 抛异常
     * （真机出现过 {@code InvocationTargetException}），吞写还挂着 ⇒ 卡住。
     */
    private static void armSfWatchdog(final int id, long delayMs) {
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!sSfActive || sSfAnimId != id) {
                    return;
                }
                long idle = SystemClock.uptimeMillis() - sSfLastActivityAt;
                if (idle < CoreConfig.SF_STALL_MS) {
                    armSfWatchdog(id, CoreConfig.SF_STALL_MS - idle);
                    return;
                }
                key(sLogAnim, "SF 动画 " + id + " 超时无回调，主动停止（兜底）");
                // ★★ 先松手（吞写立即失效），再尽力停动画。
                sDriverAlive = false;
                sSfActive = false;
                stopSf("超时兜底");
            }
        }, delayMs);
    }

    /**
     * 停止我们驱动的 SF 动画。只允许停我们自己的 id，
     * 不动 MIUI 的任何东西（旧版正是在这里抢了缩回段，才出现对拉）。
     */
    private static void stopSf(String why) {
        SurfaceControl leash = sSfDrivenLeash != null ? sSfDrivenLeash : sLeash;
        int id = sSfAnimId;
        boolean wasActive = sSfActive;
        // ★ (A1) 打断/返回/超时一律**只停心跳、不写 crop**：交回 MIUI 后它会立刻接管裁切，
        //   而此刻写"整幅 crop"会造成一帧"整幅应用缩进组件"（正是要修的症状）。
        //   正常播完的复位在 onSfStatus 里做（那里传 true）。
        endOwnCrop("stopSf:" + why, false);
        // ★★ 硬不变量：**先把吞写相关的标志全部清掉**，再去尝试停动画。
        //   顺序不能反 —— stopSFAnimation 真机抛过 InvocationTargetException，
        //   如果先停后清，异常路径就会留下"吞写还开着、驱动器已经没了"的组合。
        sSfActive = false;
        sDriverAlive = false;
        sSfDrivenLeash = null;
        if (!wasActive && id < 0) {
            return;
        }
        try {
            if (leash != null && id >= 0) {
                Object txn = newTxn();
                if (txn != null) {
                    stopSfOn(txn, leash, id, why);
                    applyTxn(txn);
                    key(sLogAnim, "已停止 SF 动画 id=" + id + "（" + why + "）");
                }
            }
        } catch (Throwable t) {
            fail("stopSFAnimation 失败但已释放控制权", t);
        }
    }

    private static void stopSfOn(Object txn, SurfaceControl leash, int id, String why) {
        Method m = sStopSfMethod;
        if (id < 0 || leash == null || m == null) {
            return;
        }
        try {
            m.invoke(txn, leash, id);
        } catch (Throwable t) {
            // ★ 真机出现过 InvocationTargetException（外层只是壳，真正原因是
            //   "SurfaceControl already released" 之类）。这里把**真实原因**打出来，
            //   并且**绝不因此影响我们自己的状态清理** —— 调用方已经先把
            //   sDriverAlive / sSfActive 清掉了，吞写不会留在"开着但没人驱动"的状态。
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            fail("stopSFAnimation(" + why + ") 失败但已释放控制权", cause);
        }
    }

    // ======================================================================
    //  (A1) 自补 crop：动画期间每帧补写 leash 的裁切
    //
    //  与《负一屏动画-图标裁切规格.md》§2/§4 的 P1~P6 一一对应：
    //    ① 起 SF 动画的同一主线程回合内写**首帧** crop（与几何同帧）；
    //    ② CROP_TICK_MS 心跳，用与 SF **同参数**的镜像弹簧算 (w,h)；
    //    ③ 每笔只写 setCrop(sc,0,0,cw,ch)：s = max(w/画布W, h/画布H)，
    //       cw = min(w/s, 画布W)、ch = min(h/s, 画布H)；绝不写 position/scale/corner；
    //    ④ 正常结束复位成整幅画布（防"内容被裁掉"残留）；
    //       中途打断/返回 ⇒ endOwnCrop(..., false)，**一笔 crop 都不写**（交回 MIUI）。
    //
    //  ★ 必须知道的实现细节：我们自己这笔 setCrop **也会命中本类的 setCrop 钩子**
    //    （HookKit.hookAll 拦的是同一个方法），所以用 sInOwnCropWrite 做自重入守卫；
    //    没有它，我们自己的写入会被自己的吞写逻辑吞掉 ⇒ 表现正是"日志说写了、画面没变"。
    // ======================================================================

    /** 自重入守卫：我们自己调用 setCrop 期间为 true，钩子必须直接放行（不吞、不探测）。 */
    private static volatile boolean sInOwnCropWrite;
    /**
     * 自重入守卫所属线程。
     *
     * <p>加线程判定的理由：钩子会被**任何**线程的 {@code Transaction.setCrop} 命中。
     * 只用一个布尔量的话，万一 MIUI 在别的线程同时写 crop，那一笔会被误当成"我们自己的"
     * 而放行（每 8ms 有一个这样的窗口）。要求"两者同时成立"才不会误放行。
     */
    private static volatile Thread sOwnCropThread;

    private static volatile SurfaceControl sCropLeash;
    private static volatile long sCropDeadline;
    private static MirrorSpring sCropW;
    private static MirrorSpring sCropH;
    /** 画布尺寸（leash 自身坐标系，本机 1200×2608）；由 startOwnCrop 按优先级解析。 */
    private static volatile float sCropCanvasW = -1f;
    private static volatile float sCropCanvasH = -1f;
    /** 本周期我们写了多少笔 crop（首帧 + 心跳）。 */
    private static volatile int sCropFrames;
    /** 本周期被我们吞掉的 MIUI setCrop 笔数 + 最后一次的右/下边界（A2 判据要用）。 */
    private static volatile int sMiuiCropWrites;
    private static volatile float sMiuiCropR = -1f;
    private static volatile float sMiuiCropB = -1f;
    /** "组合说明"只打一次。 */
    private static boolean sCropComboLogged;
    /** crop 日志的独立预算（不与其它类别的 60 条互相挤占）。 */
    private static final AtomicInteger sLogCrop = new AtomicInteger();

    /** 心跳：每 CROP_TICK_MS 一笔，只写 crop。任何异常都只记日志（不抛、不中断动画）。 */
    private static final Runnable sCropTick = new Runnable() {
        @Override
        public void run() {
            try {
                SurfaceControl sc = sCropLeash;
                if (!CoreConfig.ENABLE_OWN_CROP) {
                    // ★ 第 11 轮：开关关掉时**主动收手并解绑**（原来只是 return，
                    //   会把 sCropLeash 留着 ⇒ 那一周期里 MIUI 的 crop 会被我们继续吞掉）。
                    if (sc != null) {
                        endOwnCrop("ENABLE_OWN_CROP=false", false);
                    }
                    return;                          // 不再续期
                }
                if (sc == null || !sSfActive) {
                    return;                          // 已停 / 已交回：不再续期
                }
                long now = SystemClock.uptimeMillis();
                if (now > sCropDeadline) {
                    endOwnCrop("心跳到点", true);       // 兜底：等同"播完"，复位后收手
                    return;
                }
                sCropW.update(now);
                sCropH.update(now);
                // ⚠️ 计数**不放这里**：writeCropFrame 里可能因为"还没确认在驱动"而早退
                //    （见那边的 swallowOn 闸）。计数必须只统计**真实写入**，
                //    否则日志会谎报"我们写 18 笔"（实际 0 笔），把诊断带偏 —— 第 13 轮踩过。
                writeCropFrame(sc, sCropW.value(), sCropH.value());
                postDelayed(this, CoreConfig.CROP_TICK_MS);
            } catch (Throwable t) {
                fail("crop 心跳", t);
            }
        }
    };

    /**
     * 起 SF 动画之后立刻调用（与几何同一主线程回合 ⇒ 落在同一 vsync 帧）。
     *
     * @param w0/h0 源矩形宽高（与喂给 SF 的 width/height 起值相同）
     * @param w1/h1 目标矩形宽高（终值；同时用于兜底画布尺寸）
     * @param d/r   与 SF 参数块**完全相同**的 damping / response
     */
    private static void startOwnCrop(SurfaceControl sc, float w0, float h0,
                                     float w1, float h1, float d, float r) {
        if (!CoreConfig.ENABLE_OWN_CROP || sc == null) {
            return;
        }
        endOwnCrop("起新 crop 前清旧", false);         // 幂等：上一周期若有残留先停（不写）
        if (sSetCrop4Method == null && sSetCropRectMethod == null) {
            return;                                  // 反射不可用 ⇒ 静默降级
        }
        if (!sCropComboLogged) {
            sCropComboLogged = true;
            key("crop 组合 = A1（自补 crop）：ENABLE_OWN_CROP=true、ALLOW_MIUI_CROP="
                    + CoreConfig.ALLOW_MIUI_CROP + "（A1 下被忽略）"
                    + " ⇒ setCrop 仍被吞（防 MIUI 覆盖我们），我们每 "
                    + CoreConfig.CROP_TICK_MS + "ms 补一笔");
        }
        sMiuiCropWrites = 0;
        sMiuiCropR = -1f;
        sMiuiCropB = -1f;
        sCropFrames = 0;
        // 画布尺寸优先级：① 真机学到的 sLeashNorm（第一笔 MIUI setCrop 的右边界）
        //                ② 目标矩形（ViewScaleAnimInfo 的 to 几何） ③ 配置兜底
        float norm = sLeashNorm;
        sCropCanvasW = norm > 1f ? norm : (w1 > 1f ? w1 : CoreConfig.CROP_CANVAS_FALLBACK_W);
        sCropCanvasH = h1 > 1f ? h1 : CoreConfig.CROP_CANVAS_FALLBACK_H;
        sCropLeash = sc;
        sCropW = new MirrorSpring(w0, w1, d, r);
        sCropH = new MirrorSpring(h0, h1, d, r);
        sCropDeadline = SystemClock.uptimeMillis() + CoreConfig.SF_FALLBACK_STOP_MS + 200L;
        writeCropFrame(sc, w0, h0);                  // ★ 首帧（与几何同帧）
        key("crop 首帧（与几何同帧）：" + cropDesc(w0, h0)
                + " 画布=" + f(cropCanvasW()) + "x" + f(cropCanvasH())
                + " 源=" + f(w0) + "x" + f(h0) + " 目标=" + f(w1) + "x" + f(h1));
        postDelayed(sCropTick, CoreConfig.CROP_TICK_MS);
    }

    /**
     * 停止自补 crop。{@code snapToFull=true} 时先把 crop 复位成整幅画布（防残留裁切）。
     *
     * <p><b>中途打断/返回必须传 false</b>（交回 MIUI）：那一刻窗口可能已经很小，
     * 写整幅 crop 会造成"整幅应用缩进组件"的一帧。
     */
    private static void endOwnCrop(String why, boolean snapToFull) {
        SurfaceControl sc = sCropLeash;
        if (sc == null) {
            return;
        }
        if (snapToFull && CoreConfig.CROP_FINAL_SNAP) {
            float l = cropCanvasW();
            float hh = cropCanvasH();
            writeCropFrame(sc, l, hh);               // s=1 ⇒ crop=(0,0,画布W,画布H)
            key("crop 结束复位：crop=(0,0," + f(l) + "," + f(hh) + ")（" + why + "）");
        }
        sCropLeash = null;                           // 先清句柄：心跳下一次进来就自然退出
        // ★ A2 判据就在这一行：我们写了多少笔、MIUI 想写多少笔、末值对不对
        key("crop 汇总（" + why + "）：我们写 " + sCropFrames + " 笔；MIUI 的 setCrop 被吞 "
                + sMiuiCropWrites + " 笔"
                + (sMiuiCropR > 0f
                    ? "（MIUI 末次想写 crop=(0,0," + f(sMiuiCropR) + "," + f(sMiuiCropB) + ")）" : "")
                + "；末值 " + cropDesc(sCropW == null ? 0f : sCropW.value(),
                                      sCropH == null ? 0f : sCropH.value())
                + " ★A2：这一行正常而画面仍出现【组件里显示应用内容】"
                + " ⇒ 说明 SF 原生引擎自己也写 crop 覆盖了我们 ⇒ 改 ALLOW_MIUI_CROP=true（走 A0）"
                + " 或改为驱动 widget 快照层");
    }

    /** 只写 crop 一笔。**绝不写** position/scale/corner/alpha（那些归 SF 引擎，两写就打架）。 */
    private static void writeCropFrame(SurfaceControl sc, float w, float h) {
        if (sInOwnCropWrite && Thread.currentThread() == sOwnCropThread) {
            return;                                  // 防重入（理论上不会发生）
        }
        // ★★ 第 13 轮：**只有"确实由我们驱动并且正在吞写"时才写 crop**。
        //
        // 真机证据（`shots/widget_log.txt`，组件打开最明显）：
        //   我们于 `startSFAnimation` 同帧起 crop 心跳（8ms 一笔），
        //   但在首回调到达之前 `sDriverAlive=false` ⇒ **没有吞写** ⇒ MIUI 的 setCrop 也在写。
        //   于是那 ~150ms 里 **两条 crop 同时写同一层**：
        //     `crop 汇总：我们写 18 笔；MIUI 的 setCrop 被吞 0 笔`
        //   组件（1050x1050 / 1050x204）长宽比偏离全屏最多 ⇒ 打架最明显 ⇒ 用户说"小组件最容易抽"。
        //
        // 这与既有的"首帧放行"是**同一个原则**：SF 没回调确认之前，一律不碰这条 leash 的任何量
        // （几何不吞、crop 也不写）。SF 真在跑 ⇒ 首回调后我们自己（且只有我们）写 crop；
        // SF 根本没跑（真机组件打开时**每次都是 150ms 超时**）⇒ 我们**一笔都不写**，全交给 MIUI。
        if (!swallowOn()) {
            return;
        }
        try {
            float l = cropCanvasW();
            float hc = cropCanvasH();
            if (sc == null || w <= 1f || h <= 1f || l <= 1f || hc <= 1f) {
                return;
            }
            float s = Math.max(w / l, h / hc);       // 与 HVS 的 Math.max 一致（统一 cover 缩放）
            if (s <= 0f || Float.isNaN(s) || Float.isInfinite(s)) {
                return;
            }
            float cw = Math.min(w / s, l);
            float ch = Math.min(h / s, hc);
            Method m4 = sSetCrop4Method;
            Method mr = sSetCropRectMethod;
            if (m4 == null && mr == null) {
                return;                              // 静默降级
            }
            Object txn = newTxn();
            if (txn == null) {
                return;
            }
            sInOwnCropWrite = true;                   // ★ 自重入守卫：这笔不许被自己的吞写钩子拦掉
            sOwnCropThread = Thread.currentThread();
            try {
                if (m4 != null) {
                    m4.invoke(txn, sc, 0f, 0f, cw, ch);
                } else {
                    mr.invoke(txn, sc, new android.graphics.Rect(0, 0, Math.round(cw), Math.round(ch)));
                }
                applyTxn(txn);
                // ★ 计数只统计**真实写入**（这里之后才算一笔）：见上面 swallowOn 闸的注释。
                int n = ++sCropFrames;
                if (n % CoreConfig.CROP_LOG_EVERY_N_FRAMES == 0) {
                    key(sLogCrop, "crop 第 " + n + " 帧：" + cropDesc(w, h));
                }
            } finally {
                sInOwnCropWrite = false;
                sOwnCropThread = null;
            }
        } catch (Throwable t) {
            fail("写 crop", t);                       // leash 可能已 <released>（打断后），只记日志
        }
    }

    /** 画布宽（已解析值；拿不到就用配置兜底）。 */
    private static float cropCanvasW() {
        float v = sCropCanvasW;
        return v > 1f ? v : CoreConfig.CROP_CANVAS_FALLBACK_W;
    }

    /** 画布高（已解析值；拿不到就用配置兜底）。 */
    private static float cropCanvasH() {
        float v = sCropCanvasH;
        return v > 1f ? v : CoreConfig.CROP_CANVAS_FALLBACK_H;
    }

    /** 一行描述：w/h → s → crop（日志用）。 */
    private static String cropDesc(float w, float h) {
        try {
            float l = cropCanvasW();
            float hc = cropCanvasH();
            float s = Math.max(w / l, h / hc);
            return "w=" + f(w) + " h=" + f(h) + " s=" + String.format(Locale.US, "%.3f", s)
                    + " → crop=(0,0," + f(Math.min(w / s, l)) + "," + f(Math.min(h / s, hc)) + ")";
        } catch (Throwable t) {
            return "w=" + f(w) + " h=" + f(h);
        }
    }

    /**
     * 与 SF 参数块**同参数**的镜像弹簧（标量）。
     *
     * <p>刚度换算抄自桌面 {@code SpringAnimator.stiffnessConvert}：{@code k = (2π/response)²}，
     * 阻尼 {@code c = 2ζ√k}，半隐式欧拉推进。参数与喂给 SF 的完全同源，
     * 两条轨道同参同时起跑 ⇒ 误差是二阶小量（真机表现 ≤1~2px）。
     */
    private static final class MirrorSpring {
        private final float target;
        private final float k;
        private final float c;
        private float value;
        private float v;
        private long last;

        MirrorSpring(float from, float to, float zeta, float response) {
            this.value = from;
            this.target = to;
            this.last = 0L;
            float r = response > 0f ? response : 0.444f;
            this.k = (float) Math.pow(6.283185307179586d / r, 2.0d);
            this.c = 2f * (float) Math.sqrt(this.k) * zeta;
        }

        void update(long nowMs) {
            if (last == 0L) {
                last = nowMs;
                return;
            }
            float dt = (nowMs - last) / 1000f;
            last = nowMs;
            if (dt <= 0f) {
                return;
            }
            if (dt > 0.064f) {
                dt = 0.064f;                         // 掉帧保护
            }
            float a = -k * (value - target) - c * v;
            v += a * dt;
            value += v * dt;
            if (Math.abs(v) < 0.01f && Math.abs(value - target) < 0.5f) {
                value = target;                      // 贴住终点，避免无限拖尾
                v = 0f;
            }
        }

        float value() {
            return value;
        }
    }

    // ======================================================================
    //  (C) 返回段：把控制权交回 MIUI（+ (D) 交回失败时的备选）
    // ======================================================================

    /** 源组件矩形（返回段的目标）。只由 startOpenSf() 写入。 */
    private static volatile float sSrcCx = Float.NaN;
    private static volatile float sSrcCy = Float.NaN;
    private static volatile float sSrcW = Float.NaN;
    private static volatile float sSrcH = Float.NaN;
    private static volatile float sSrcCorner = 36f;
    private static volatile float sDstCorner = 174f;
    /** 是否已经交回控制权（本次过渡内只交一次）。 */
    private static volatile boolean sHandedOff;
    private static volatile long sHandedOffAt;
    private static volatile boolean sReturnGuardArmed;
    /** 惰性：读不到时再置位，之后不再反复打失败日志。 */
    private static volatile boolean sGeomQueryBroken;
    /** 我们驱动期间 leash 位置的**极值**（用 x 的单调解读方向）。 */
    private static volatile float sMaxX = Float.NEGATIVE_INFINITY;
    private static volatile float sMinX = Float.POSITIVE_INFINITY;
    private static volatile float sLastX = Float.NaN;
    /** x 的运动方向：-1 减小（打开段）、+1 增大（返回段）、0 未知。 */
    private static volatile int sDir;

    /**
     * 收到"返回/退出"信号时**立刻**把控制权交回 MIUI：
     * <ol>
     *   <li>{@code stopSFAnimation(我们自己的 id)} —— 停掉我们的引擎；</li>
     *   <li>清 {@code sSfActive} ⇒ 吞写随即停止 ⇒ MIUI 的每帧写入立刻恢复通过；</li>
     *   <li>起一个观察窗口，看 leash 有没有被 MIUI 送回源矩形
     *       （没有 ⇒ 才启用备选 (D)）。</li>
     * </ol>
     * 全程**不改** merge 参数、**不改** goToState 目标、**不碰**状态机。
     */
    private static void beginReturn(String why) {
        if (!CoreConfig.ENABLE_RETURN_HANDOFF) {
            // 即使不接管返回段，也必须记下"返回信号"——清模糊扫要靠它武装。
            markReturnSignal(why);
            return;
        }
        try {
            markReturnSignal(why);            // ★ 第 6 轮：返回信号是清模糊扫的唯一合法触发源
            boolean wasActive = sSfActive;
            if (wasActive) {
                stopSf(why);                   // 内部会把 sSfActive 置 false ⇒ 吞写停止
            }
            if (!sHandedOff) {
                sHandedOff = true;
                sHandedOffAt = SystemClock.uptimeMillis();
                sWritesSinceHandoff = 0;     // 从 0 开始数"交回后 MIUI 写了多少笔"
                key("返回段：已把控制权交回 MIUI（" + why + "）"
                        + " 我们原本" + (wasActive ? "在驱动" : "未驱动")
                        + " ⇒ stopSFAnimation(我们的 id) 已调用、吞写已停止、MIUI 的每帧写入放行");
                armReturnGuard();
            }
        } catch (Throwable t) {
            fail("交回控制权", t);
        }
    }

    /**
     * 观察窗口：判断**交回控制权之后到底谁在驱动 leash**，据此决定要不要走备选 (D)。
     *
     * <p>判据（全部打进日志，不猜）：
     * <ol>
     *   <li>{@code 交回后写入笔数}：为 0 ⇒ MIUI 压根没驱动 ⇒ **必须走备选 (D)**；</li>
     *   <li>{@code 几何变化}：有写入但几何完全不动（冻结）⇒ 等于没驱动 ⇒ 也走 (D)；</li>
     *   <li>几何在向源矩形收敛 ⇒ MIUI 自己在送回去，**不干预**；</li>
     *   <li>既在写、又没收敛 ⇒ 打印实际数值，交给日志判定（仍走 (D) 兜底）。</li>
     * </ol>
     */
    private static void armReturnGuard() {
        if (sReturnGuardArmed || !CoreConfig.ENABLE_RETURN_SF_FALLBACK) {
            return;
        }
        sReturnGuardArmed = true;
        // 记录交回那一刻的几何基准（用来判断"冻结/收敛"）
        final float[] g0 = leashGeom();
        postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!sHandedOff || sSfActive || state() != EXITING) {
                        key(sLogAnim, "返回段：观察窗口到点时状态已变（handedOff=" + sHandedOff
                                + " sfActive=" + sSfActive + " state=" + stateName(state())
                                + "），不做备选");
                        return;
                    }
                    int writes = sWritesSinceHandoff;
                    float[] g = leashGeom();
                    String geomDesc = g == null ? "读不到"
                            : ("当前 x=" + f(g[0]) + " y=" + f(g[1])
                               + " w=" + f(g[2]) + " h=" + f(g[3]));
                    key("返回段【谁在驱动 leash】交回后写入笔数=" + writes
                            + "（窗口 " + CoreConfig.RETURN_GUARD_MS + "ms）"
                            + " " + geomDesc
                            + " 源矩形=(x=" + f(sSrcCx - sSrcW / 2f)
                            + " y=" + f(sSrcCy - sSrcH / 2f)
                            + " " + f(sSrcW) + "x" + f(sSrcH) + ")"
                            + " 容差=" + f(Math.max(24f, sSrcW * 0.2f)) + "px");

                    if (writes == 0) {
                        key("返回段：交回控制权后 **leash 一笔写入都没有** ⇒ "
                                + "MIUI 根本没有驱动返回 ⇒ 启用备选方案 (D)");
                        startReturnSf();
                        return;
                    }
                    if (g == null) {
                        key("返回段：有写入但读不到几何 ⇒ 启用备选方案 (D)");
                        startReturnSf();
                        return;
                    }
                    if (g0 != null && Math.abs(g[0] - g0[0]) < 1f && Math.abs(g[2] - g0[2]) < 1f
                            && writes > 0) {
                        key("返回段：有 " + writes + " 笔写入但几何**完全没变**（冻结在 x="
                                + f(g[0]) + " w=" + f(g[2]) + "）⇒ 等于没驱动 ⇒ 启用备选方案 (D)");
                        startReturnSf();
                        return;
                    }
                    if (convergedToSource(g)) {
                        key("返回段：leash 已朝源矩形收敛（" + geomDesc
                                + "）⇒ MIUI 自己在送回，**不干预**");
                        return;
                    }
                    key("返回段：MIUI 在写但几何未收敛（" + geomDesc
                            + "）⇒ 为保住回家的观感，仍启用备选方案 (D)");
                    startReturnSf();
                } catch (Throwable t) {
                    fail("返回段守卫", t);
                }
            }
        }, CoreConfig.RETURN_GUARD_MS);
    }

    /**
     * 读 leash 当前几何：{@code [left, top, width, height]}。
     *
     * <p>优先用 {@code getSFAnimationParams}（只查询、**不 apply**，不改任何姿态）；
     * 本机若没有这个方法或它失败，就退回我们自己逐帧记录的极值。
     */
    private static float[] leashGeom() {
        SurfaceControl sc = sSfDrivenLeash != null ? sSfDrivenLeash : sLeash;
        if (sc == null) {
            return null;
        }
        if (!sGeomQueryBroken && sGetSfParamsMethod != null) {
            try {
                int[] out = new int[4];
                Object txn = newTxn();
                if (txn != null) {
                    sGetSfParamsMethod.invoke(txn, sc, out);
                    if (out[2] > 0) {
                        return new float[]{out[0], out[1], out[2], out[3]};
                    }
                }
            } catch (Throwable t) {
                // 置位后不再反复尝试（这个方法在不同版本上签名/行为都不保证）
                sGeomQueryBroken = true;
                verbose("getSFAnimationParams 不可用，改用自记录的 leash 极值");
            }
        }
        return geomFromTracking();
    }

    /** 用逐帧记录的位置推当前几何（近似，但足以判断"有没有在往回走"）。 */
    private static float[] geomFromTracking() {
        if (sLastX == Float.NaN) {
            return null;
        }
        float w = sSrcW > 1f ? sSrcW : 1200f;
        float h = sSrcH > 1f ? sSrcH : 2608f;
        return new float[]{sLastX, sSrcCy - h / 2f, w, h};
    }

    /**
     * 是否已经"差不多回到源矩形"。
     *
     * <p>用**位置**判（比宽度可靠，且没有 scale→宽度 的歧义）：
     * 目标左边界与当前左边界之差 &lt; max(24px, 20% 源宽) 且竖直方向同理。
     */
    private static boolean convergedToSource(float[] g) {
        if (Float.isNaN(sSrcW)) {
            return false;
        }
        float tx = sSrcCx - sSrcW / 2f;
        float ty = sSrcCy - sSrcH / 2f;
        float tolX = Math.max(24f, sSrcW * 0.2f);
        float tolY = maxd(24f, sSrcH * 0.2f);
        return Math.abs(g[0] - tx) < tolX && Math.abs(g[1] - ty) < tolY;
    }

    private static float maxd(float a, float b) {
        return a > b ? a : b;
    }

    /**
     * 备选方案 (D)：用我们自己的 SF 引擎播一次"整屏 → 源组件矩形"。
     *
     * <p>前提：调用点已经确认 MIUI 没有把 leash 送回去，并且**此刻 MIUI 不在驱动**
     * （我们的动画已停，且处于 EXITING 相位）—— 二者择一，绝不抢。
     */
    private static void startReturnSf() {
        if (!leashUsable()) {
            key(sLogAnim, "返回段备选：leash 无效，放弃");
            return;
        }
        if (Float.isNaN(sSrcW) || sSrcW <= 1f) {
            key(sLogAnim, "返回段备选：没有源矩形几何，放弃");
            return;
        }
        SurfaceControl leash = sSfDrivenLeash != null ? sSfDrivenLeash : sLeash;
        if (leash == null) {
            return;
        }
        float[] cur = leashGeom();
        if (cur == null) {
            key(sLogAnim, "返回段备选：拿不到当前几何，放弃");
            return;
        }

        // 当前（= 整屏或半途）→ 目标（组件矩形）
        float sw = cur[2];
        float sh = cur[3] > 1f ? cur[3] : cur[2];
        float scx = cur[0] + sw / 2f;
        float scy = cur[1] + sh / 2f;
        float dW = sSrcW;
        float dH = sSrcH;
        float dCx = sSrcCx;
        float dCy = sSrcCy;

        // ★★ 第 13 轮 NaN 闸：真机取证 19:07:45.402 这里就是
        //    `源=(NaN,2185.0 477.0x244.0)` —— cur[0] 是 NaN，
        //    `NaN + sw/2` 仍是 NaN，于是整块参数带着 NaN 起了动画
        //    （并且同一次照样"首回调超时"）。NaN 一律不递给框架。
        if (bad(sw) || bad(sh) || bad(scx) || bad(scy)
                || bad(dW) || bad(dH) || bad(dCx) || bad(dCy)) {
            key(sLogReverse, "返回段备选：几何含 NaN/Inf（当前 (" + f(scx) + "," + f(scy)
                    + " " + f(sw) + "x" + f(sh) + ") 目标组件 (" + f(dCx) + "," + f(dCy)
                    + " " + f(dW) + "x" + f(dH) + ")）⇒ 放弃（交给 MIUI）");
            return;
        }

        // 返回段稍快（response 小 = 更快），仍然临界阻尼不过冲。
        float dGeo = 1.0f;
        float rGeo = 0.35f;
        float dRatio = 1.0f;
        float rRatio = 0.28f;
        float dAlpha = 0.99f;
        float rAlpha = 0.3f;

        float[] block = new float[94];
        int k = 0;
        k = put9(block, k, scx, dCx, dGeo, rGeo);                       // centerX
        k = put9(block, k, scy, dCy, dGeo, rGeo);                       // centerY
        k = put9(block, k, sw, dW, dGeo, rGeo);                         // width
        k = put9(block, k, sh, dH, dGeo, rGeo);                         // height
        k = put9(block, k, sh / sw, dH / dW, dRatio, rRatio);           // ratio = h / w
        k = put9(block, k, 1.0f, 1.0f, dAlpha, rAlpha);                 // alpha
        k = put9(block, k, sDstCorner, sSrcCorner, dGeo, rGeo);         // radius 174 → 36
        float dS = (float) Math.sqrt(sw * sw + sh * sh);
        float dE = (float) Math.sqrt(dW * dW + dH * dH);
        k = put9(block, k, sDstCorner / dS, sSrcCorner / dE, dGeo, rGeo);   // radiusRatio
        float srcX = dCx - dW / 2f;
        float srcY = dCy - dH / 2f;
        float[] extra = {
                -1f, srcX, -1f, srcY, -1f, dW, -1f, dH,
                -1f, 0f, -1f, 0f, -1f, 0f, -1f, 0f, -1f, 0f,
                -1f, 0f, -1f, 0f,
        };
        for (float v : extra) {
            if (k < block.length) {
                block[k++] = v;
            }
        }
        if (k != block.length) {
            key(sLogAnim, "返回段备选：参数块长度 " + k + " != " + block.length + "，放弃");
            return;
        }

        try {
            Object txn = newTxn();
            if (txn == null) {
                return;
            }
            if (sSfAnimId >= 0) {
                stopSfOn(txn, leash, sSfAnimId, "返回段起新动画前清旧");
            }
            int id = sAnimIdSeq.incrementAndGet();
            Object callback = makeSfCallback(id);
            Method start = sStartSfMethod;
            if (start == null) {
                return;
            }
            start.invoke(txn, leash, block, 2, callback, MAIN, SF_FRAME_TIMES, 1);
            applyTxn(txn);

            // 重新置位 ⇒ 重新开始吞写（这次吞的是 MIUI 可能残留的写入，避免与我们抢）
            sSfAnimId = id;
            sSfActive = true;
            // ★ (D) 同样遵守失败保险：**先不吞写**，等首回调确认驱动器活着。
            sDriverAlive = false;
            sSfArmedAt = SystemClock.uptimeMillis();
            final int epoch = ++sSfEpoch;
            sSfDrivenLeash = leash;
            long now = SystemClock.uptimeMillis();
            sSfLastActivityAt = now;
            sLastLeashWriteAt.set(now);
            key("返回段备选：startSFAnimation 已调用 id=" + id + " 参数个数=" + k
                    + " 源=(" + f(scx) + "," + f(scy) + " " + f(sw) + "x" + f(sh) + ")"
                    + " 目标=组件(" + f(dCx) + "," + f(dCy) + " " + f(dW) + "x" + f(dH) + ")"
                    + " 圆角 " + sDstCorner + "→" + sSrcCorner);
            key(sLogFailsafe, "返回段备选：**暂不吞写**（首帧放行），"
                    + CoreConfig.SF_FIRST_CALLBACK_MS + "ms 内等首回调");
            if (CoreConfig.SF_FAILSAFE_RELEASE) {
                armFirstCallbackFailsafe(id, epoch);
            }
            armSfWatchdog(id, CoreConfig.SF_FALLBACK_STOP_MS);
        } catch (Throwable t) {
            fail("返回段 startSFAnimation", t);
            sSfActive = false;
            sDriverAlive = false;
            sSfDrivenLeash = null;
        }
    }

    /**
     * leash 写入时的返回探测（**零反射**，几次浮点比较，可在每帧路径上跑）。
     *
     * <p>★ 真机教训（第 3 轮）：旧版判据 `v &lt; max * 0.9` 会被**打开段自己的几何变化**
     * 立刻触发 —— 日志里出现 {@code setScale 明显回落（1200.0 → 0.5）}，
     * 而 1200 是 {@code setCrop} 的像素宽度、0.5 是 {@code setScale} 的比例，
     * 两个不同量纲的值被放在一起比，于是"打开动画刚起步就被我们自己掐死"。
     *
     * <p>现在改为**强确认**，三条同时满足才交回：
     * <ol>
     *   <li>我们自己的 SF 动画已经跑了 ≥ {@link CoreConfig#RETURN_MIN_MS}；</li>
     *   <li>存在"至少写过几笔"的基线（避免拿第一笔初始化值当参照）；</li>
     *   <li>方向与打开段相反**连续** {@link CoreConfig#RETURN_REVERSE_WRITES} 笔，
     *       或者尺寸相对**目标（全屏）**回落超过
     *       {@link CoreConfig#RETURN_SHRINK_RATIO}（只认同量纲的 setCrop 像素宽度，
     *       或按 leash 宽度归一后的 setScale）。</li>
     * </ol>
     * 首选判据仍然是标准信号（StateListener 进 CloseState / hideOverlay），
     * 这里只作次要判据。
     */
    private static void detectReturnOnWrite(String method, Object[] args) {
        if (!CoreConfig.ENABLE_RETURN_HANDOFF || !sSfActive || sHandedOff) {
            return;
        }
        if (args == null || args.length < 2) {
            return;
        }
        // 强确认①：接管时长
        if (SystemClock.uptimeMillis() - sSfStartAt < CoreConfig.RETURN_MIN_MS) {
            return;
        }

        // setPosition：方向反转（连续 N 笔）
        if ("setPosition".equals(method) && args[1] instanceof Number) {
            float x = ((Number) args[1]).floatValue();
            if (Float.isNaN(x)) {
                return;
            }
            if (sLastX != Float.NaN) {
                float dx = x - sLastX;
                if (dx > 0.01f) {
                    if (sDir == -1 && ++sReverseDirCount >= CoreConfig.RETURN_REVERSE_WRITES) {
                        beginReturn("位置方向连续 " + sReverseDirCount + " 笔与打开段相反（x="
                                + f(x) + "，打开段极值 [" + f(sMinX) + "," + f(sMaxX) + "]）");
                        return;
                    }
                    sDir = 1;
                } else if (dx < -0.01f) {
                    sReverseDirCount = 0;    // 还在打开段方向 ⇒ 计数清零
                    sDir = -1;
                }
            }
            sLastX = x;
            if (x > sMaxX) {
                sMaxX = x;
            }
            if (x < sMinX) {
                sMinX = x;
            }
            return;
        }

        // setCrop：像素宽度（与打开段"全屏宽度"同量纲，能直接比）
        if ("setCrop".equals(method) && args.length >= 5 && args[3] instanceof Number) {
            float w = ((Number) args[3]).floatValue();
            if (Float.isNaN(w) || w <= 0f) {
                return;
            }
            checkShrink(w, "crop 宽度");
            return;
        }

        // setScale：归一化后再比（用 leash 自身宽度换算成像素，避免与 setCrop 混淆量纲）
        if ("setScale".equals(method) && args[1] instanceof Number) {
            float sc = ((Number) args[1]).floatValue();
            if (Float.isNaN(sc) || sc <= 0f) {
                return;
            }
            float px = sc * (sLeashNorm > 1f ? sLeashNorm : 1200f);
            checkShrink(px, "scale 归一后宽度");
        }
    }

    /**
     * 强确认②③：宽度（像素）相对打开段达到过的最大宽度回落超过阈值才算返回。
     * 兼容 setScale(比例) 与 setCrop(像素) 两种写法 —— 两个量都必须先换算成像素再进来。
     */
    private static void checkShrink(float px, String what) {
        if (px > sMaxWrittenPx) {
            sMaxWrittenPx = px;              // 打开段还在变大
            sDrivenWrites++;
            return;
        }
        sDrivenWrites++;
        if (sDrivenWrites < CoreConfig.RETURN_MIN_WRITES) {
            return;                          // 强确认②：至少写过几笔
        }
        if (sMaxWrittenPx > 0f && px < sMaxWrittenPx * CoreConfig.RETURN_SHRINK_RATIO) {
            beginReturn(what + "相对打开段最大值回落超过 "
                    + (int) ((1f - CoreConfig.RETURN_SHRINK_RATIO) * 100) + "%（"
                    + f(sMaxWrittenPx) + " → " + f(px) + "px）");
        }
    }

    /** 打开段期间见过的最大写入宽度（**统一换算成像素**后的基准）。 */
    private static volatile float sMaxWrittenPx;
    /** 见过多少笔几何写入（用于"强确认②"）。 */
    private static volatile int sDrivenWrites;
    /** leash 自身坐标系宽度（crop 空间），用于把 setScale 归一成像素。 */
    private static volatile float sLeashNorm = 1200f;
    /** 与打开段相反方向的连续笔数。 */
    private static volatile int sReverseDirCount;

    private static void resetReturnDetectionBase() {
        sDrivenWrites = 0;
        sMaxWrittenPx = 0f;
        sReverseDirCount = 0;
        sMaxX = -Float.MAX_VALUE;
        sMinX = Float.MAX_VALUE;
        sLastX = Float.NaN;
        sDir = 0;
    }

    private static Object newTxn() {
        java.lang.reflect.Constructor<?> c = sTxnCtor;
        if (c == null) {
            return null;
        }
        try {
            return c.newInstance();
        } catch (Throwable t) {
            fail("构造 Transaction", t);
            return null;
        }
    }

    /**
     * 桌面参考（{@code SfAnim.java:425-428/450}）每次起动画都会
     * {@code localSessionId++} 并把新值当最后一个参数传进去；回调里用
     * {@code this.localSessionId == i2} **过滤**回调。
     *
     * <p>我们原来**每个周期硬编码传 1**。虽然我们只看自己的回调，但为了与参考实现
     * 逐字对齐（且万一 SF 对 id 有"必须递增/非零"的隐式要求），这里按开关给出两种模式。
     * 见 {@link CoreConfig#SF_SESSION_ID_MODE}。
     */
    private static int sessionIdForNextAnim() {
        int sid;
        if (CoreConfig.SF_SESSION_ID_MODE == 1) {
            if (sSfLastSessionId < 1) {
                sSfLastSessionId = 1;           // 从 1 开始（与旧模块"硬编码 1"兼容）
            }
            sid = sSfLastSessionId + 1;
        } else if (CoreConfig.SF_SESSION_ID_MODE == 0) {
            sid = 0;                            // 强制 0
        } else {
            sid = 1;                            // 旧行为：永远 1（默认，零风险）
        }
        sSfLastSessionId = sid;
        return sid;
    }

    private static void applyTxn(Object txn) {
        Method m = sApplyMethod;
        if (txn == null || m == null) {
            key(sLogAnim, "Transaction.apply 跳过：txn=" + (txn == null ? "null" : "ok")
                    + " method=" + (m == null ? "null" : "ok"));
            return;
        }
        try {
            m.invoke(txn);
            // ★ 正面成功日志（原来只有失败会打，导致"到底 apply 成功没有"无从判断）
            if (sLogApply.incrementAndGet() <= CoreConfig.LOG_BUDGET) {
                key("Transaction.apply 成功（第 " + sLogApply.get() + " 次）");
            }
        } catch (Throwable t) {
            fail("Transaction.apply", t);
        }
    }

    private static final AtomicInteger sLogApply = new AtomicInteger();

    /**
     * 写一个属性的 9 元组：
     * {@code [2.0, start, end, delay, velocity, damping, response, mass, minVisibleChange]}。
     */
    private static int put9(float[] p, int k, float start, float end, float damping, float response) {
        if (k + 9 > p.length) {
            return k;
        }
        p[k++] = 2.0f;
        p[k++] = start;
        p[k++] = end;
        p[k++] = 0f;                 // delay
        p[k++] = 0f;                 // velocity
        p[k++] = damping;
        p[k++] = response;
        p[k++] = 1f;                 // mass
        p[k++] = 1.0E-4f;            // minVisibleChange
        return k;
    }

    // ======================================================================
    //  MIUI 生命周期回调（缩回段的信号来源）
    // ======================================================================

    private static void onMiuiOpened(Object window) {
        try {
            rememberWindow(window);
            setState(AT_REST, "MIUI onOpened");
        } catch (Throwable t) {
            fail("onOpened", t);
        }
    }

    private static void onMiuiClosed(Object window) {
        try {
            rememberWindow(window);
            if (state() == IDLE) {
                // 首次 attach 时也会走 A()，那时没打开过，别乱清。
                verbose("onClosed 但状态是 IDLE，跳过收尾");
                return;
            }
            setState(EXITING, "MIUI onClosed");
            scheduleBlurSweep("onClosed");
        } catch (Throwable t) {
            fail("onClosed", t);
        }
    }

    /**
     * 记住窗口管理器实例（以及顺手取到的 decorView 与 android.view.Window）。
     * 生命周期回调的 {@code thisObject} 就是 {@code AssistantOverlayWindow} 真身，
     * 是唯一能按类型扫到 {@code DeviceAdapter} 的对象。
     */
    private static void rememberWindow(Object windowMgr) {
        if (windowMgr == null) {
            return;
        }
        sOverlayWindowRef = windowMgr;
        if (sOverlayDecorRef == null) {
            sOverlayDecorRef = decorFrom(windowMgr);
        }
        if (sOverlayWindowObjRef == null) {
            sOverlayWindowObjRef = overlayWindowOf(windowMgr);
        }
    }

    private static void onHideOverlay(int flags) {
        try {
            key(sLogSwipe, "观测点命中：hideOverlay(flags=" + flags + ")"
                    + " 当前状态=" + stateName(state()));
            if (flags != 0 && flags != 1) {
                return;                      // 2 之类是别的语义，不猜
            }
            if (state() == IDLE) {
                return;
            }
            beginReturn("hideOverlay(" + flags + ")");     // ← 把控制权交回 MIUI
            setState(EXITING, "hideOverlay(" + flags + ")");
            scheduleBlurSweep("hideOverlay(" + flags + ")");
        } catch (Throwable t) {
            fail("hideOverlay", t);
        }
    }

    private static void onMiuiScrollStart(float progress) {
        try {
            if (state() == IDLE || state() == AT_REST) {
                setState(OPENING, "MIUI scrollStart");
            }
            sSfStartedInCycle = false;       // 一次新手势 = 允许(重新)起动画
        } catch (Throwable t) {
            fail("scrollStart", t);
        }
    }

    private static void onMiuiScrollEnd(float progress) {
        try {
            if (Float.isNaN(progress)) {
                return;
            }
            verbose("scrollEnd progress=" + f(progress));
            if (progress < 0.5f) {
                setState(EXITING, "scrollEnd(" + f(progress) + ")");
                scheduleBlurSweep("scrollEnd");
            } else {
                setState(AT_REST, "scrollEnd(" + f(progress) + ")");
            }
        } catch (Throwable t) {
            fail("scrollEnd", t);
        }
    }

    private static void onMiuiDragStart(boolean open) {
        try {
            if (!open) {
                beginReturn("dragStart(false)");           // ← 把控制权交回 MIUI
                setState(EXITING, "dragStart(false)");
                scheduleBlurSweep("dragStart(false)");
            }
        } catch (Throwable t) {
            fail("dragStart", t);
        }
    }

    private static volatile long sLastProgressLog;
    private static volatile float sLastProgress = 1f;

    private static void onMiuiScroll(float progress) {
        try {
            if (Float.isNaN(progress)) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now - sLastProgressLog > 2000L) {
                sLastProgressLog = now;
                verbose("progress=" + f(progress));
            }
            // 已经开始往回滑且我们还在驱动 → 立刻交回控制权（只停我们自己的动画）
            if (sSfActive && progress < sLastProgress - 0.02f) {
                beginReturn("进度回落 " + f(sLastProgress) + "→" + f(progress));
                setState(EXITING, "进度回落");
                scheduleBlurSweep("进度回落");
            }
            sLastProgress = progress;
        } catch (Throwable t) {
            fail("onScroll", t);
        }
    }

    /** 状态机事件：只读观察，绝不改参数。 */
    private static void onMiuiStateEvent(String method, Object stateObj) {
        try {
            if (stateObj == null) {
                return;
            }
            String desc = stateObj.getClass().getSimpleName();
            float v = floatGetter(stateObj);
            // ★ 第 7 轮：**每一次**状态回调都打一行（限流），
            //   这样"上滑到底触发了哪些观测点"可以直接从日志读出来，不用猜。
            key(sLogSwipe, "状态回调 " + method + " = " + desc
                    + "（行为值=" + (Float.isNaN(v) ? "读不到" : f(v)) + "）"
                    + " 当前状态=" + stateName(state()));
            boolean open = isOpenState(stateObj);
            boolean close = !open && isCloseState(stateObj);
            if ("c".equals(method) && close) {
                beginReturn("状态机进入 " + desc);          // ← 把控制权交回 MIUI
                setState(EXITING, "stateTransitionStart(" + desc + ")");
                scheduleBlurSweep("stateTransitionStart");
            } else if ("b".equals(method) && close) {
                setState(EXITING, "stateTransitionComplete(" + desc + ")");
                scheduleBlurSweep("stateTransitionComplete");
            } else if (open && "c".equals(method)) {
                setState(OPENING, "stateTransitionStart(" + desc + ")");
            } else {
                verbose("状态事件 " + method + " " + desc
                        + " (open=" + open + " close=" + close + ")");
            }
        } catch (Throwable t) {
            fail("状态事件", t);
        }
    }

    /**
     * 是不是 OpenState。**按行为判**：调该状态对象上返回 float 的无参方法
     * （真机是 {@code h.a()}，JADX 名 {@code a()}），值 ≈1.0 即 OpenState；
     * 行为判不出来时才退回类名提示。
     */
    private static boolean isOpenState(Object stateObj) {
        float v = floatGetter(stateObj);
        if (!Float.isNaN(v)) {
            return v >= 0.5f;
        }
        return stateObj.getClass().getName().contains("OpenState");
    }

    private static boolean isCloseState(Object stateObj) {
        float v = floatGetter(stateObj);
        if (!Float.isNaN(v)) {
            return v < 0.5f;
        }
        String n = stateObj.getClass().getName();
        return n.contains("CloseState");
    }

    // ======================================================================
    //  (B) 缩回段收尾：停稳后清模糊
    // ======================================================================

    private static volatile boolean sSweepPending;
    private static volatile long sLastSweepAt;
    private static volatile boolean sSweptThisEpisode;
    private static volatile boolean sQuietWatchdogOn;

    // ----------------------------------------------------------------------
    //  ★ 第 6 轮真机教训：清模糊扫**只能由"返回/关闭信号"触发**。
    //
    //  旧实现在"打开段 SF status=0"时也叫醒了看门狗，于是"打开动画刚结束、
    //  应用正前台"时就跑了标准收尾 ⇒ 把**打开动画自带的背景模糊**清掉了，
    //  用户看到"背景模糊丢了"。
    //
    //  现在三道闸：
    //    ① 只有返回信号会武装扫（scheduleBlurSweep 的调用点全部在返回/关闭路径上）；
    //    ② 执行前要求"最近 RETURN_SWEEP_MAX_MS 内发生过返回信号"；
    //    ③ 停稳（距末次 leash 写入 > QUIET_WINDOW_MS）+ 可见性判据不变。
    // ----------------------------------------------------------------------

    /** 最近一次"返回/关闭信号"的时刻（uptimeMillis）；0 = 本周期还没有过。 */
    private static volatile long sReturnSignalAt;
    private static volatile String sReturnSignal = "无";
    /**
     * 本轮的"清模糊资格"。
     *
     * <p>只有 {@link #scheduleBlurSweep}（全部调用点都在返回/关闭路径上）才会置位；
     * 新的一次打开过渡会把它清掉。这是**闸 0** 的落点 ——
     * 保证"打开段结束"永远不会触发清模糊。
     */
    private static volatile boolean sSweepArmed;
    private static volatile long sSweepArmedAt;

    private static void markReturnSignal(String what) {
        sReturnSignalAt = SystemClock.uptimeMillis();
        sReturnSignal = what;
    }

    /**
     * 排一次"标准收尾"。**只允许从返回/关闭路径调用**；
     * 真正的动作在 {@link #attemptBlurSweep} 里由三道闸决定。
     */
    private static void scheduleBlurSweep(String why) {
        if (!CoreConfig.ENABLE_BLUR_SWEEP) {
            return;
        }
        if (sReturnSignalAt == 0L) {
            // 防御：没有返回信号就不排（调用点理论上都带了 markReturnSignal）
            markReturnSignal(why);
        }
        if (sSweepPending || sSweptThisEpisode) {
            return;                          // 同一次过渡只排一次
        }
        sSweepPending = true;
        sSweepArmed = true;                  // ★ 闸 0：武装清模糊资格（只有返回路径能到这里）
        sSweepArmedAt = SystemClock.uptimeMillis();
        key(sLogSweep, "已排标准收尾（" + why + "）：等 leash 停写 "
                + CoreConfig.QUIET_WINDOW_MS + "ms，最迟 "
                + Math.max(CoreConfig.SWEEP_DELAY_MS, CoreConfig.QUIET_WINDOW_MS) + "ms");
        postDelayed(new Runnable() {
            @Override
            public void run() {
                sSweepPending = false;
                attemptBlurSweep("定时兜底");
            }
        }, Math.max(CoreConfig.SWEEP_DELAY_MS, CoreConfig.QUIET_WINDOW_MS));
        startQuietWatchdog();
    }

    /**
     * "停稳"看门狗：按 {@code 距最后一次 leash/层写入} 判断。
     *
     * 只要还在 EXITING / AT_REST 相位就继续 tick ——
     * 因为窗口可能已经消失（{@link #attemptBlurSweep} 里会因"仍在显示"而放弃），
     * 所以不能一次放弃就永远不再试。
     */
    private static void startQuietWatchdog() {
        if (sQuietWatchdogOn) {
            return;
        }
        sQuietWatchdogOn = true;
        armQuietWatchdog(0);
    }

    /** 看门狗最多重试这么多次（≈ 30s / TICK），防止无限空转。 */
    private static final int WATCHDOG_MAX_TICKS = 100;

    private static void armQuietWatchdog(final int tick) {
        postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!CoreConfig.ENABLE_BLUR_SWEEP) {
                        sQuietWatchdogOn = false;
                        return;
                    }
                    if (sState != EXITING && sState != AT_REST) {
                        sQuietWatchdogOn = false;
                        return;
                    }
                    if (tick >= WATCHDOG_MAX_TICKS) {
                        sQuietWatchdogOn = false;
                        key(sLogSweep, "收尾看门狗到达最大重试次数，本轮放弃"
                                + "（宁可漏一次，也不在不确定的状态下清模糊）");
                        return;
                    }
                    boolean again;
                    if (sSfActive) {
                        again = true;               // 等我们自己的动画结束
                    } else {
                        long quiet = SystemClock.uptimeMillis() - sLastLeashWriteAt.get();
                        if (quiet < CoreConfig.QUIET_WINDOW_MS) {
                            again = true;           // 还没停稳
                        } else {
                            // 条件都满足了：attemptBlurSweep 返回 false 只可能是
                            // "窗口还在显示"或"拿不到对象"，那就继续等下一 tick。
                            again = !attemptBlurSweep("leash 静默 " + quiet + "ms");
                        }
                    }
                    if (again) {
                        armQuietWatchdog(tick + 1);
                    } else {
                        sQuietWatchdogOn = false;
                    }
                } catch (Throwable t) {
                    sQuietWatchdogOn = false;
                    fail("停稳看门狗", t);
                }
            }
        }, CoreConfig.WATCHDOG_TICK_MS);
    }

    /**
     * 真正动手：走到这里必须同时满足（**第 6 轮新增第 0 条闸**）
     * <ol>
     *   <li><b>闸 0（最重要）</b>：最近 {@link CoreConfig#RETURN_SWEEP_MAX_MS} 内
     *       **发生过返回/关闭信号**。没有信号 ⇒ 一律不清 ——
     *       打开动画自带的背景模糊不能被我们清掉（用户反馈"背景模糊丢了"就是这个原因）；</li>
     *   <li>我们自己的 SF 动画已经不在跑（否则压 0 会被下一帧覆盖）；</li>
     *   <li>距最后一次 leash 写入 &gt; {@link CoreConfig#QUIET_WINDOW_MS}（"停稳"）；</li>
     *   <li>负一屏当前**不可见**；</li>
     *   <li>节流窗口内没做过。</li>
     * </ol>
     * 任何一条不满足就放弃，绝不硬来。
     *
     * @return true 表示本轮不需要再试
     */
    private static boolean attemptBlurSweep(String why) {
        try {
            if (!CoreConfig.ENABLE_BLUR_SWEEP) {
                return true;
            }
            long now = SystemClock.uptimeMillis();
            // ---- 闸 0：必须由返回/关闭信号武装过（打开段结束绝不武装）----
            if (!sSweepArmed) {
                key(sLogSweep, "收尾**拒绝**：本周期没有发生过返回/关闭信号（" + why
                        + "）—— 打开段的背景模糊必须保留");
                return true;                 // 明确不做，也不重试
            }
            long sinceSignal = sReturnSignalAt == 0L ? -1L : (now - sReturnSignalAt);
            if (sReturnSignalAt == 0L) {
                key(sLogSweep, "收尾**拒绝**：已武装但没有任何返回信号时刻（" + why + "）");
                return true;
            }
            if (sinceSignal > CoreConfig.RETURN_SWEEP_MAX_MS) {
                key(sLogSweep, "收尾**拒绝**：距返回信号已 " + sinceSignal + "ms（>"
                        + CoreConfig.RETURN_SWEEP_MAX_MS + "ms），视为过期（" + why + "）");
                sSweepArmed = false;
                return true;
            }
            if (sSweptThisEpisode && now - sLastSweepAt < CoreConfig.SWEEP_COOLDOWN_MS) {
                verbose("收尾节流跳过（" + why + "）");
                return true;                 // 刚做过，不能再做
            }
            if (sSfActive) {
                verbose("收尾跳过：SF 动画仍在跑");
                return false;
            }
            long quiet = now - sLastLeashWriteAt.get();
            if (quiet < CoreConfig.QUIET_WINDOW_MS) {
                verbose("收尾跳过：距上次 leash 写入仅 " + quiet + "ms");
                return false;
            }
            Object win = resolveOverlayWindow();
            if (win == null) {
                verbose("收尾跳过：拿不到 overlay 窗口");
                return false;
            }
            View decor = decorFrom(win);
            if (decor == null) {
                verbose("收尾跳过：拿不到 decorView");
                return false;
            }
            // ★ 真机反馈（第 3 轮）：点开组件后"过渡进度"仍然是 1.0（负一屏只是被移到
            //   屏幕外/被应用盖住），于是旧判据连续 5 次都判"仍在显示"、收尾永不执行。
            //   现在改成**看得见才算仍在使用**：进度≈1 但 decor 不可见
            //   （!isShown / alpha=0 / translationX 被推到屏幕外）⇒ 允许收尾。
            boolean visible = isOverlayVisible(win, decor);
            if (visible) {
                key(sLogSweep, "收尾跳过：负一屏仍看得见（" + why + "）"
                        + " " + overlayStateDesc(win, decor));
                return false;
            }
            key(sLogSweep, "收尾放行：信号=" + sReturnSignal + " 距信号=" + sinceSignal
                    + "ms 判据=" + why + " " + overlayStateDesc(win, decor));
            clearBlurStandard(decor, win, why);
            sSweptThisEpisode = true;
            sLastSweepAt = now;
            setState(AT_REST, "标准收尾完成");
            return true;
        } catch (Throwable t) {
            fail("标准收尾", t);
            return true;                     // 出错也不再重试，避免刷日志
        }
    }

    /**
     * 标准收尾 —— 本类唯一"改变系统状态"的写操作。三步都是 UI 线程同步执行，
     * 且都是幂等的：
     * <pre>
     *   ① y5.i.a(decorView)      // clearMiBackgroundBlendColor
     *                            //   + setMiBackgroundBlurMode(0)
     *                            //   + setMiBackgroundBlurRadius(0)
     *                            //   + setPassWindowBlurEnabled(false)
     *      —— 与 D.onLeave() 在 BLUR_VIEW 分支里做的事**完全一致**
     *   ② View.setPassWindowBlurEnabled(false)   // 补一刀（直击桌面侧 view_scale_blur 的来源）
     *   ③ DeviceBlurBlendAdapter.onScroll(0.0f)  // 可选：用 MIUI 自己的状态机拉回 0
     *                                            //   （progress=0 ⇒ 各状态都会清 radius/blend）
     *   ④ Window.setBackgroundBlurRadius(0)      // 可选，见 CoreConfig.CLEAR_WINDOW_BLUR
     * </pre>
     * 只在装饰视图仍然 attached 时才动手。
     */
    /**
     * 标准收尾。**每一步都单独记成功/失败**（真机反馈：一句"标准收尾完成"看不出哪步真成了）：
     * <pre>
     *   ① y5.i.a(decor)   —— 负一屏自己的一键关模糊（与 D.onLeave() 的 BLUR_VIEW 分支等价）
     *   ② View 隐藏 API 内联等价动作（④修复）：y5.i 找不到也能工作
     *        clearMiBackgroundBlendColor()
     *        setMiBackgroundBlurMode(0)
     *        setMiBackgroundBlurRadius(0)
     *        setPassWindowBlurEnabled(false)
     *        setForceEnablePassBlur(false)
     *   ③ DeviceBlurBlendAdapter.onScroll(0.0f) —— 用 MIUI 自己的状态机拉回 0
     *   ④ Window.setBackgroundBlurRadius(0) —— 可选，见 CoreConfig.CLEAR_WINDOW_BLUR
     * </pre>
     * 只做幂等动作；都在 UI 线程；decor 未 attach 时整体放弃。
     */
    private static void clearBlurStandard(View decor, Object win, String why) {
        if (!decor.isAttachedToWindow()) {
            key(sLogSweep, "标准收尾跳过：decorView 已 detach（" + why + "）");
            return;
        }
        StringBuilder done = new StringBuilder();
        StringBuilder skipped = new StringBuilder();

        // ① 负一屏自己的一键关模糊（用**应用 CL**，不是我们模块的 CL）
        boolean y5ok = false;
        Class<?> y5i = null;
        ClassLoader appCl = appClassLoader(decor);
        try {
            y5i = findAppClass(appCl, "y5.i");
            if (y5i == null) {
                skipped.append("①y5.i(找不到类) ");
                key(sLogSweep, "① y5.i 未找到（appCl=" + clName(appCl) + "）⇒ 走第②步内联等价动作");
            } else {
                Method m = y5i.getMethod("a", View.class);
                m.invoke(null, decor);
                y5ok = true;
                done.append("①y5.i.a(成功) ");
            }
        } catch (Throwable t) {
            skipped.append("①y5.i(调用失败) ");
            fail("① y5.i.a(decor)", t);
        }

        // ② View 隐藏 API 内联等价动作。
        //    y5.i.a(View) 的内部实现就是这 4 个反射调用（见 BlurColorUtil），
        //    所以我们不依赖那个混淆类名也能达到同样效果。
        if (y5ok) {
            // 已经做过等价动作，② 只补一刀 pass-blur（等价动作里本来就含它，幂等无害）
            if (invokeViewBool(decor, "setPassWindowBlurEnabled", false, done, skipped)) {
                done.append("②passWindowBlur(false) ");
            }
        } else {
            invokeViewBool(decor, "clearMiBackgroundBlendColor", false, done, skipped);
            invokeViewInt(decor, "setMiBackgroundBlurMode", 0, done, skipped);
            invokeViewInt(decor, "setMiBackgroundBlurRadius", 0, done, skipped);
            invokeViewBool(decor, "setPassWindowBlurEnabled", false, done, skipped);
            invokeViewBool(decor, "setForceEnablePassBlur", false, done, skipped);
        }

        // ③ 用 MIUI 自己的状态机拉回 0（按类型找 DeviceAdapter，不认假名）
        if (CoreConfig.CALL_DEVICE_ON_SCROLL_ZERO) {
            Object device = findDeviceAdapter(overlayWindowMgr(win), win);
            if (device != null) {
                try {
                    Method onScroll = device.getClass().getMethod("onScroll", float.class);
                    onScroll.invoke(device, Float.valueOf(0f));
                    done.append("③onScroll(0)[")
                            .append(device.getClass().getSimpleName()).append("] ");
                } catch (Throwable t) {
                    skipped.append("③onScroll(0)(失败) ");
                    fail("③ DeviceAdapter.onScroll(0.0f)", t);
                }
            } else {
                skipped.append("③onScroll(0)(未找到适配器) ");
            }
        }

        // ④ 可选：窗口级模糊
        Window w = overlayWindowOf(win);
        if (CoreConfig.CLEAR_WINDOW_BLUR && w != null) {
            try {
                Method br = Window.class.getMethod("setBackgroundBlurRadius", int.class);
                br.invoke(w, 0);
                done.append("④setBackgroundBlurRadius(0) ");
            } catch (Throwable t) {
                skipped.append("④窗口模糊(失败) ");
                fail("④ Window.setBackgroundBlurRadius(0)", t);
            }
        }

        key("标准收尾完成（" + why + "）"
                + " | 成功: " + (done.length() == 0 ? "无" : done.toString())
                + " | 跳过/失败: " + (skipped.length() == 0 ? "无" : skipped.toString())
                + " | decor=" + decor.getClass().getSimpleName()
                + " appCl=" + clName(appCl)
                + " 距末次leash写入=" + (SystemClock.uptimeMillis() - sLastLeashWriteAt.get()) + "ms");
    }

    /** {@code view.setXxx(boolean)} 形式的隐藏 API 调用；成功/失败都如实记录。 */
    private static boolean invokeViewBool(View v, String name, boolean arg,
            StringBuilder done, StringBuilder skipped) {
        try {
            Method m = View.class.getMethod(name, boolean.class);
            m.invoke(v, Boolean.valueOf(arg));
            done.append(name).append('(').append(arg).append(") ");
            return true;
        } catch (Throwable t) {
            skipped.append(name).append("(失败) ");
            verbose(name + " 不可用: " + t);
            return false;
        }
    }

    /** {@code view.setXxx(int)} 形式的隐藏 API 调用。 */
    private static boolean invokeViewInt(View v, String name, int arg,
            StringBuilder done, StringBuilder skipped) {
        try {
            Method m = View.class.getMethod(name, int.class);
            m.invoke(v, Integer.valueOf(arg));
            done.append(name).append('(').append(arg).append(") ");
            return true;
        } catch (Throwable t) {
            skipped.append(name).append("(失败) ");
            verbose(name + " 不可用: " + t);
            return false;
        }
    }

    private static String clName(ClassLoader cl) {
        return cl == null ? "null" : cl.getClass().getSimpleName();
    }

    /**
     * 找应用内的类。★ 必须显式传应用 CL —— 第 4 轮的真机 bug 就是
     * {@code Class.forName("y5.i")} 用了模块自己的 CL 导致 ClassNotFoundException。
     *
     * @param cl 应用 CL（可为 null，此时退回 Thread 上下文 CL）
     */
    private static Class<?> findAppClass(ClassLoader cl, String name) {
        ClassLoader loader = cl != null ? cl : Thread.currentThread().getContextClassLoader();
        if (loader != null) {
            try {
                return Class.forName(name, false, loader);
            } catch (Throwable ignored) {
            }
        }
        // 最后再试一次"不传 CL"（某些 ROM 上应用类其实在 boot CL 里可见）
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 解析"应用 ClassLoader"。多路兜底，按可靠性排序：
     * <ol>
     *   <li>{@link #install(ClassLoader)} 记下的那个（最可靠）；</li>
     *   <li>overlay 窗口类的 CL（一定是应用 CL）；</li>
     *   <li>decorView（View 子类）的 CL；</li>
     *   <li>{@code DeviceBlurBlendAdapter} 实例的 CL（通过它一定能看到同包类）；</li>
     *   <li>Thread 上下文 CL。</li>
     * </ol>
     */
    private static ClassLoader appClassLoader(View decor) {
        ClassLoader cl = sAppCl;
        if (cl == null && sOverlayWindowCls != null) {
            cl = sOverlayWindowCls.getClassLoader();
        }
        if (cl == null && decor != null) {
            cl = decor.getClass().getClassLoader();
        }
        if (cl == null) {
            Object device = findDeviceAdapter(sOverlayWindowRef, null);
            if (device != null) {
                cl = device.getClass().getClassLoader();
            }
        }
        if (cl == null) {
            cl = Thread.currentThread().getContextClassLoader();
        }
        return cl;
    }

    // ======================================================================
    //  按类型找对象：DeviceAdapter / 内容视图（不认 JADX 假名）
    // ======================================================================

    /** 缓存：窗口管理器实例 → DeviceAdapter 字段。 */
    private static volatile Field sDeviceAdapterField;

    /**
     * 按**类型**扫描找 {@code DeviceAdapter}（真机字段名可能是 D 或别的）。
     *
     * <p>先在窗口**管理器**对象（AssistantOverlayWindow 实例，字段 D 挂在这里）上找；
     * 找不到再在外层 {@code android.view.Window} 上找（防御性，不假设挂在谁身上）。
     * 判据：字段类型名以 {@code DeviceAdapter} 结尾。
     */
    private static Object findDeviceAdapter(Object windowMgr, Object window) {
        Object v = scanFieldByType(windowMgr, "DeviceAdapter");
        if (v != null) {
            return v;
        }
        return scanFieldByType(window, "DeviceAdapter");
    }

    /**
     * 字段类型满足 {@code contains} 就取它的值（逐层父类）。
     * 这是"不认反编译假名、只认类型"的通用手段。
     */
    private static Object scanFieldByType(Object owner, String typeNameContains) {
        if (owner == null) {
            return null;
        }
        // 命中过一次就记住（字段不会换），省掉每轮遍历。
        Field cached = sDeviceAdapterField;
        if (cached != null && "DeviceAdapter".equals(typeNameContains)
                && cached.getDeclaringClass().isInstance(owner)) {
            try {
                Object v = cached.get(owner);
                if (v != null) {
                    return v;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    if (!f.getType().getName().contains(typeNameContains)) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        Object v = f.get(owner);
                        if (v != null) {
                            if ("DeviceAdapter".equals(typeNameContains)) {
                                sDeviceAdapterField = f;
                            }
                            verbose("按类型找到字段 " + typeNameContains + ": "
                                    + c.getSimpleName() + "." + f.getName()
                                    + " (" + f.getType().getSimpleName() + ")");
                            return v;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            fail("按类型扫描字段 " + typeNameContains, t);
        }
        return null;
    }

    // ======================================================================
    //  窗口 / decorView 解析（按类型与标题匹配，不猜字段名）
    // ======================================================================

    /** 窗口管理器实例（真身）/ decorView / android.view.Window —— 都由钩子写入。 */
    private static volatile Object sOverlayWindowRef;
    private static volatile View sOverlayDecorRef;
    private static volatile Window sOverlayWindowObjRef;

    /**
     * 拿 overlay 窗口管理器实例（{@code AssistantOverlayWindow}）。
     *
     * <p>顺序：
     * <ol>
     *   <li>钩子记下的实例（{@code ViewRootImpl.setView} / 生命周期回调，最可靠）；</li>
     *   <li>窗口类里的静态 {@code WeakReference}（旧模块用的 {@code Y} / {@code U()} 那条路，
     *       按**类型**扫静态字段，不认名字）；</li>
     *   <li>{@code WindowManagerGlobal} 的 roots 里标题含 {@code AssistantOverlay} /
     *       {@code LauncherOverlay} 的那个 —— 这条只能拿到 View，
     *       拿不到 DeviceAdapter，所以放在最后，并且会同时填好 decorView。</li>
     * </ol>
     */
    private static Object resolveOverlayWindow() {
        Object cached = sOverlayWindowRef;
        if (cached != null) {
            return cached;
        }
        // ② 静态 WeakReference
        Object viaWeak = resolveViaStaticWeakRef();
        if (viaWeak != null) {
            rememberWindow(viaWeak);
            return viaWeak;
        }
        // ③ WindowManagerGlobal（只可能拿到 View）
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object inst = wmg.getMethod("getInstance").invoke(null);
            List<?> roots = rootsOf(wmg, inst);
            if (roots == null) {
                return null;
            }
            for (Object rv : roots) {
                Object v = viewOf(rv);
                String title = titleOf(rv);
                if (v instanceof View && title != null && title.contains("AssistantOverlay")) {
                    sOverlayDecorRef = (View) v;
                    return v;
                }
            }
            for (Object rv : roots) {
                Object v = viewOf(rv);
                String title = titleOf(rv);
                if (v instanceof View && title != null && title.contains("LauncherOverlay")) {
                    sOverlayDecorRef = (View) v;
                    return v;
                }
            }
        } catch (Throwable t) {
            fail("查找 overlay 窗口", t);
        }
        return null;
    }

    /**
     * 从窗口类的静态字段里找 {@code WeakReference<AssistantOverlayWindow>}
     * 并取实例（真机是 {@code Y}，另有公开静态 {@code U()} 可取；这里按类型扫，不认名字）。
     */
    private static Object resolveViaStaticWeakRef() {
        Class<?> cls = sOverlayWindowCls;
        if (cls == null) {
            return null;
        }
        try {
            for (Field f : cls.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (!java.lang.ref.WeakReference.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                Object ref = f.get(null);
                if (ref instanceof java.lang.ref.WeakReference) {
                    Object v = ((java.lang.ref.WeakReference<?>) ref).get();
                    if (v != null && cls.isInstance(v)) {
                        verbose("从静态 " + f.getName() + " 取到 overlay 窗口");
                        return v;
                    }
                }
            }
        } catch (Throwable t) {
            fail("静态 WeakReference 取窗口", t);
        }
        return null;
    }

    private static List<?> rootsOf(Class<?> wmg, Object inst) {
        // Android 17(SDK37) 上 getRootViews() 已不存在，依次尝试：
        //   ① 无参 getRootViews()  ② getRootViews(boolean)  ③ 直接读字段 mRoots
        try {
            for (Method m : wmg.getDeclaredMethods()) {
                if (!"getRootViews".equals(m.getName())) {
                    continue;
                }
                m.setAccessible(true);
                Object roots = null;
                try {
                    if (m.getParameterTypes().length == 0) {
                        roots = m.invoke(inst);
                    } else if (m.getParameterTypes().length == 1) {
                        roots = m.invoke(inst, Boolean.FALSE);
                    }
                } catch (Throwable ignored) {
                }
                if (roots instanceof List) {
                    return (List<?>) roots;
                }
            }
            Field f = wmg.getDeclaredField("mRoots");
            f.setAccessible(true);
            Object roots = f.get(inst);
            if (roots instanceof List) {
                return (List<?>) roots;
            }
        } catch (Throwable t) {
            fail("读取 WindowManagerGlobal.mRoots", t);
        }
        return null;
    }

    private static Object viewOf(Object rootView) {
        try {
            return rootView.getClass().getMethod("getView").invoke(rootView);
        } catch (Throwable ignored) {
        }
        try {
            Field f = rootView.getClass().getDeclaredField("mView");
            f.setAccessible(true);
            return f.get(rootView);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String titleOf(Object rootView) {
        try {
            Field f = rootView.getClass().getDeclaredField("mWindowAttributes");
            f.setAccessible(true);
            Object attrs = f.get(rootView);
            if (attrs == null) {
                return null;
            }
            return String.valueOf(attrs.getClass().getMethod("getTitle").invoke(attrs));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 从"窗口或视图"里拿 decorView。
     * 注意：{@link #resolveOverlayWindow()} 缓存下来的可能是
     * {@code android.view.Window}（生命周期回调里的 {@code thisObject.f6547a}），
     * 也可能是窗口管理器实例或某个 View，所以三种都要能处理。
     */
    private static View decorFrom(Object win) {
        if (sOverlayDecorRef != null) {
            return sOverlayDecorRef;
        }
        if (win instanceof View) {
            return (View) win;              // 已经是 decorView
        }
        Window w = overlayWindowOf(win);
        if (w != null) {
            try {
                View v = w.getDecorView();
                if (v != null) {
                    sOverlayDecorRef = v;
                    return v;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 从缓存对象里取出真正的 {@code android.view.Window}。
     * 真机字段是 {@code f6547a}（JADX 名），这里**按精确类型**找
     * （{@code type.getName().endsWith(".Window")}；{@code WindowManager} 不满足该判据）。
     */
    private static Window overlayWindowOf(Object obj) {
        if (obj instanceof Window) {
            return (Window) obj;
        }
        if (obj == null) {
            return null;
        }
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    if (!f.getType().getName().endsWith(".Window")) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v instanceof Window) {
                            return (Window) v;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            fail("查找 android.view.Window 字段", t);
        }
        return null;
    }

    private static Object overlayWindowMgr(Object win) {
        return sOverlayWindowRef != null ? sOverlayWindowRef : win;
    }

    /**
     * 负一屏是不是**真的还看得见**（= 用户正在用它，绝不能清模糊）。
     *
     * <p>★ 真机教训（第 3 轮）：点开组件后，过渡进度仍然读到 1.0
     * （负一屏没有被"收起"，只是被应用盖住 / 内容被平移到屏幕外），
     * 于是"进度≥0.5 ⇒ 仍在使用"这条判据让收尾连续 5 次全部跳过。
     *
     * <p>现在改为**以可见性为准**，只要任一成立就认为"看不见"：
     * <ul>
     *   <li>{@code !decor.isShown()}（未 attach 或 GONE）</li>
     *   <li>窗口 alpha &lt; 0.05（K 方法把窗口 alpha 置 0 是负一屏收起时的标准做法）</li>
     *   <li>内容视图/装饰视图的 {@code |translationX|} ≥ 屏幕宽度一半
     *       （真机 f11510u 会被平移 +1200）</li>
     *   <li>窗口的 {@code View.getAlpha()} &lt; 0.05</li>
     * </ul>
     * 另外兼容"进度已经明确小于 0.5"的情况（那时肯定收起了）。
     * 判据用到的每个数值都会打进日志，便于一眼核对。
     */
    private static boolean isOverlayVisible(Object window, View decor) {
        try {
            // 进度明确收起 ⇒ 直接算不可见
            float p = overlayProgress(window);
            if (!Float.isNaN(p) && p < 0.5f) {
                return false;
            }
            if (decor == null || !decor.isAttachedToWindow()) {
                return false;
            }
            if (decor.getVisibility() != View.VISIBLE) {
                return false;
            }
            if (decor.getAlpha() < 0.05f) {
                return false;
            }
            // 窗口侧 alpha（收起时会被置 0/接近 0）
            float winA = windowAlpha(window);
            if (!Float.isNaN(winA) && winA < 0.05f) {
                return false;
            }
            // 被平移出屏幕（真机 AssistContentView translationX=+1200）
            int screenW = decor.getWidth() > 0 ? decor.getWidth() : 1200;
            if (Math.abs(decor.getTranslationX()) >= screenW * 0.5f) {
                return false;
            }
            View content = findContentChild(decor);
            if (content != null && Math.abs(content.getTranslationX()) >= screenW * 0.5f) {
                return false;
            }
            return true;
        } catch (Throwable t) {
            fail("判断可见性", t);
            return true;                     // 判不出来时保守：不清
        }
    }

    /** 一行说清"为什么判它可见/不可见"，把用到的原始数值都带上。 */
    private static String overlayStateDesc(Object window, View decor) {
        try {
            float p = overlayProgress(window);
            float winA = windowAlpha(window);
            String d = decor == null ? "decor=null"
                    : ("decor[vis=" + decor.getVisibility()
                        + ",shown=" + decor.isShown()
                        + ",attached=" + decor.isAttachedToWindow()
                        + ",alpha=" + f(decor.getAlpha())
                        + ",tx=" + f(decor.getTranslationX())
                        + "," + decor.getWidth() + "x" + decor.getHeight() + "]");
            View content = decor == null ? null : findContentChild(decor);
            String c = content == null ? "content=未找到"
                    : ("content[" + content.getClass().getSimpleName()
                        + ",vis=" + content.getVisibility()
                        + ",alpha=" + f(content.getAlpha())
                        + ",tx=" + f(content.getTranslationX()) + "]");
            return "进度=" + (Float.isNaN(p) ? "读不到" : f(p))
                    + " 窗口alpha=" + (Float.isNaN(winA) ? "读不到" : f(winA))
                    + " " + d + " " + c;
        } catch (Throwable t) {
            return "(状态描述失败: " + t + ")";
        }
    }

    /** 找内容视图（按类型名，真机 AssistContentView；不认假名字段）。 */
    private static View findContentChild(View root) {
        try {
            java.util.ArrayDeque<View> q = new java.util.ArrayDeque<>();
            q.add(root);
            int guard = 0;
            while (!q.isEmpty() && guard++ < 200) {
                View v = q.poll();
                if (v.getClass().getName().contains("AssistContentView")) {
                    return v;
                }
                if (v instanceof android.view.ViewGroup) {
                    android.view.ViewGroup g = (android.view.ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        q.add(g.getChildAt(i));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 读窗口属性里的 alpha（收起时会置 0）。 */
    private static float windowAlpha(Object windowMgr) {
        Window w = overlayWindowOf(windowMgr);
        if (w == null) {
            return Float.NaN;
        }
        try {
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            return lp == null ? Float.NaN : lp.alpha;
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    /**
     * 读当前过渡进度（0=收起，1=展开）。
     *
     * <p>按**行为/类型**找：窗口里那个"过渡控制器"字段
     * （类型名含 {@code SlidingPaneTransitionController}，或字段名像 controller/transition）
     * 上，调用返回 float 且无参的方法。
     * **不**直接扫窗口自己的方法 —— 那太容易撞上别的 float getter。取不到返回 NaN。
     */
    private static float overlayProgress(Object window) {
        if (window == null) {
            return Float.NaN;
        }
        try {
            for (Class<?> c = window.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    String tn = f.getType().getName();
                    String fn = f.getName().toLowerCase(Locale.US);
                    if (!tn.contains("SlidingPaneTransitionController")
                            && !fn.contains("controller")
                            && !fn.contains("transition")) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    Object v;
                    try {
                        v = f.get(window);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    float p = floatGetter(v);
                    if (!Float.isNaN(p)) {
                        return p;
                    }
                }
            }
        } catch (Throwable t) {
            fail("读取过渡进度", t);
        }
        return Float.NaN;
    }

    /** 在对象上找一个"返回 float 且无参"的方法并调用；找不到返回 NaN。 */
    private static float floatGetter(Object obj) {
        if (obj == null) {
            return Float.NaN;
        }
        try {
            for (Method m : obj.getClass().getDeclaredMethods()) {
                if (m.getReturnType() != float.class || m.getParameterTypes().length != 0
                        || Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    Object r = m.invoke(obj);
                    if (r instanceof Float) {
                        return ((Float) r).floatValue();
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return Float.NaN;
    }

    // ======================================================================
    //  小工具
    // ======================================================================

    private static String f(float v) {
        return String.format(Locale.US, "%.1f", v);
    }

    /**
     * 几何量是否**不可用**（NaN / ±Inf）。
     *
     * <p>为什么需要：{@code v <= 1f} 这类判据对 NaN **恒为 false**
     * （NaN 参与的任何比较都是 false），所以 NaN 能一路穿过
     * "几何无效"检查、进入 94 个 float 的参数块。
     * 真机取证：{@code shots/dir_log.txt} 19:07:45.402
     * {@code 源=(NaN,2185.0 477.0x244.0)}。
     */
    private static boolean bad(float v) {
        return Float.isNaN(v) || Float.isInfinite(v);
    }

    /**
     * 时间差格式化。**所有**时间戳统一用 {@code SystemClock.uptimeMillis()}，
     * 这里只做防御：万一某处漏了赋值（曾发生过：sLeashAt 未赋值 ⇒ 打出 73,999,509ms），
     * 就把"负数 / 超过 1 分钟"的值显式标出来，不让它冒充耗时。
     */
    private static String ms(long delta) {
        if (delta < 0) {
            return "?" + delta + "(时间戳异常)";
        }
        if (delta > 60000L) {
            return delta + "(>60s,时间戳可疑)";
        }
        return String.valueOf(delta);
    }

    /** 递归读字段（含父类）；名为反编译名时的兜底手段。 */
    private static float gf(Object obj, String name, float def) {
        Object v = fieldValue(obj, name);
        if (v instanceof Number) {
            return ((Number) v).floatValue();
        }
        return def;
    }

    private static Object fieldValue(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
