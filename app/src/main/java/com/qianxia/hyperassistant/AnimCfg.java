package com.qianxia.hyperassistant;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

/**
 * v3 运行期配置：**改参数不用重打包**。
 *
 * <p>文件（存在即生效，不存在就用内置默认值）：
 * <pre>
 *   /sdcard/Download/dsh-debuglog/animv3.conf
 *
 *   # 全部键（示例值就是内置默认值）
 *   probe=true          # 探针总开关；false = 三个探针一个都不装（一键回滚）
 *   write=false         # 是否把日志落盘；false = 只走 logcat（默认，符合"只观测不落盘"）
 *   logcat=true         # 是否输出 logcat
 *   pane=true           # 探针① p#f(h)
 *   stateManager=true   # 探针② SlidingPaneStateManager#b(h,int,r)
 *   assist=true         # 探针③ AssistContentView#onScroll/onScrollEnd/onEnter/onLeave
 *   overlay=true        # 探针④（P3）桌面→负一屏 AIDL 并行通道：ILauncherOverlay#onTransact
 *                       #   + BaseCallback/LauncherOverlayCallback#handleMessage（k=overlay）
 *   engine=true         # 探针⑤（P4）HyperViewScale 引擎兜底探针（k=engine，只读）：
 *                       #   SurfaceControl$Transaction#startSFAnimationV2（①，fw 3656376）
 *                       #   HyperViewScaleRemoteManager#setMaskSurface（②，hvs 1756452）
 *                       #   HyperViewScaleRemoteTransition#startAnimation 11 参（③，hvs 1774776）
 *                       #   预期：负一屏「打开→返回」一整轮里 k=engine 应为 0 条（引擎零参与）
 *   stack=false         # 事件行里是否附带调用栈深度（开启有额外开销，默认关）
 *   buf=8192            # 内存环形缓冲条数（满了丢最旧）
 *   loglevel=1          # ★ 日志级别（照 HyperCeiler）：0=关闭 / 1=常规 / 2=详细；默认 1
 *                       #   只管"推给 App 的内容"，不改 ANIMV4-P1.txt 的落盘行为
 *                       #   （App 侧没存储权限 ⇒ 级别由 App 通过 Bridge 的 LEVEL 广播推来，
 *                       #    并存到 animv3.loglevel；conf 里显式写 loglevel= 可压过它）
 *                       #   ★ 逐帧行（ac.onScroll）只在 2（详细）产出；0/1 只更新内存态
 *   hb=on               # ★ LOW#4：5 秒兜底心跳（PONG 推送）开关：on / off；默认 on
 *                       #   off = 不起心跳定时器（PING 应答 + onEnter/onLeave 交互推送照旧）
 *
 *   # ---- P2 新增（只作用于"返回方向"，见 ReturnDrive / Apply / Motion）----
 *   applyReturn=true    # 返回方向（负一屏→桌面）生效 ← P2 的目标；false = 停掉这次改动
 *   applyOpen=false     # 打开方向（桌面→负一屏）**默认交回原厂**（false ⇒ 该方向零写入）
 *   smooth=0.62         # 指数平滑系数 α：越小越"缓"，1.0 = 完全跟随系统（等于不改）
 * </pre>
 *
 * <p><b>读取时机（硬规矩，来自 v1 冻结事故）：热路径绝不读盘。</b>
 * 本类只提供两个入口：
 * <ul>
 *   <li>{@link #reloadNow()} —— 冷路径（onPackageReady）读一次；</li>
 *   <li>{@link #reloadIfChanged()} —— 只做一次 {@code lastModified()} stat，
 *       由 {@link Entry} 的定时轮询（默认 3s）在<b>主线程空闲时</b>调用。</li>
 * </ul>
 * 探针回调里一次都不会调用本类。
 */
final class AnimCfg {

    private static final String PATH = "/data/local/tmp/animv4.conf";

    /**
     * ★ 日志级别（照 HyperCeiler 的三态，见 {@code LogLevelManager.kt:29-31}）。
     *
     * <pre>
     *   HyperCeiler                         本模块（逐字同值）
     *   LEVEL_DISABLED   = 0  "Disabled"    LOG_DISABLED = 0
     *   LEVEL_ERROR_ONLY = 1  "General"     LOG_GENERAL  = 1   ← 默认值（同 HC getDefaultLogLevel）
     *   LEVEL_VERBOSE    = 2  "Detailed"    LOG_VERBOSE  = 2
     * </pre>
     * 语义也照 {@code LoggerUtils.shouldLog(logLevel, requiredLevel)}（LoggerUtils.kt:4-10）：
     * <pre>
     *   0 ⇒ 一条都不推；1 ⇒ 只推 required==1 的行；2 ⇒ required==1 与 2 都推
     * </pre>
     * <b>只作用于"推给 App 的内容"</b>：文件日志 {@code ANIMV4-P1.txt} 仍然照
     * {@link #write} 的开关写全部事件（那是我们排查的主通道，级别管不着它）。
     */
    static final int LOG_DISABLED = 0;
    static final int LOG_GENERAL = 1;
    static final int LOG_VERBOSE = 2;

    /**
     * ★ 修复（2026-10-03 审计）：这些键是**布尔键** ⇒ `reload()` 里走白名单校验
     * （拼错的值以前被 {@link Boolean#parseBoolean} 静默当成 false ⇒ 开关"莫名其妙失效"）。
     */
    private static boolean isBoolKey(String k) {
        switch (k) {
            case "probe": case "write": case "logcat": case "pane": case "stateManager":
            case "assist": case "overlay": case "engine": case "stack": case "closeanim": case "srcprobe": case "srcsnap": case "srcitems": case "srcanim": case "srchide": case "viewanim": case "winmatrix": case "springcurve": case "itemcurve": case "itemscale": case "cardprobe": case "cardopen":
            case "applyReturn": case "applyOpen": case "hb":
                return true;
            default:
                return false;
        }
    }

    /**
     * ★ 修复（2026-10-03 审计）：布尔键白名单解析 —— 只接受 {@code true/false}
     * （{@code hb} 额外接受 {@code on/off}）；**非法返回 {@code null}**（调用方记坏键 + 保留原值）。
     */
    private static Boolean boolKey(String k, String v) {
        final String s = v == null ? "" : v.trim();
        if ("true".equalsIgnoreCase(s)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(s)) {
            return Boolean.FALSE;
        }
        if ("hb".equals(k)) {
            if ("on".equalsIgnoreCase(s)) {
                return Boolean.TRUE;
            }
            if ("off".equalsIgnoreCase(s)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    /**
     * 级别落地文件（**只有级别**，所以不和 {@link #PATH} 抢着写）：
     * <pre>
     *   /sdcard/Download/dsh-debuglog/animv4.loglevel     内容就一个数字：0 / 1 / 2
     * </pre>
     * 为什么单独一个文件：App 侧**没有存储权限**、写不了 {@link #PATH}（那是 hook 进程的配置），
     * 所以 App 把级别<b>推</b>过来（{@code Bridge} 的 LEVEL 广播），这里把它存下来，
     * 宿主进程重启后仍然生效。优先级：{@link #PATH} 里的 {@code loglevel=} 显式写了就压过它。
     */
    private static final String LEVEL_PATH = "/sdcard/Download/dsh-debuglog/animv4.loglevel";

    /**
     * ★ 修复 #3：级别落盘的"常驻线程 + 最新值邮箱"。
     *
     * <p>原来 {@link #setLoglevelFromPush(int)} 每次 {@code new Thread}（App 每推一次就起一个
     * 短命线程写盘）。现在只在首次推送时懒启动一条守护线程；之后每次推送只更新
     * {@link #sPendingLevel}（后写覆盖先写）并唤醒它 ⇒ 同一时刻最多一个线程、且总是落最新的值。
     */
    private static final java.util.concurrent.atomic.AtomicInteger sPendingLevel =
            new java.util.concurrent.atomic.AtomicInteger(Integer.MIN_VALUE);
    private static final Object LEVEL_LOCK = new Object();
    private static boolean sLevelWriterStarted;

    // ------------------------------------------------------------------ 默认值

    /** 探针总开关。默认 on：探针编译进去、默认只观测。 */
    static volatile boolean probe = true;

    /** 落盘开关。默认 <b>off</b>：默认只走 logcat，不写任何文件。 */
    static volatile boolean write = true;   // ★ Lead 改：默认落盘（原先 false 只走 logcat，用户看不到日志）

    /** logcat 开关。默认 on（没有 adb 时用户可以开 write 落盘看文件）。 */
    static volatile boolean logcat = true;

    /** 探针①：pane.p#f(h)（状态切换唯一入口）。 */
    static volatile boolean pane = true;

    /** ★ P21/P23：把"关闭"的 animFlag 由 0 强制为 1（走原厂 420ms 动画分支）。false = 一键回到硬切。 */
    static volatile boolean closeAnim = true;

    /** ★ S1：源层可见性探针（在负一屏 `AssistantOverlayWindow#g(int)` 里加一条自己的品红层）。false = 一键关闭。 */
    static volatile boolean srcProbe = false;

    /** ★ S2a：用**真实内容快照**代替品红探针色（false = 回到品红探针）。 */
    static volatile boolean srcSnap = true;

    /** ★ S3a-2：逐项建层（9 张卡片 + 11 个图标各自一层）。false = 回到"整屏一张"的 S2a 模式。 */
    static volatile boolean srcItems = false;

    /** ★ S3b：是否用 progress 驱动逐项层（false = 只静态显示，不动）。 */
    static volatile boolean srcAnim = false;

    /** ★ S3b：驱动期间是否把真内容 alpha 置 0（默认 false = 保守；置 true 才是最终观感，一次装机可切换 ✓）。 */
    static volatile boolean srcHide = false;

    /** ★ S6'：桌面那套过渡打在**真实 View** 上（根 View 轻微缩放 + 图标/卡片各自缩放淡出）。 */
    static volatile boolean viewAnim = false;

    /** ★ S7a：静态窗口矩阵探针（窗口 SC 上打 setMatrix/setCornerRadius，1.5s 后自动还原）。 */
    static volatile boolean winMatrix = false;

    /** ★ S9：把桌面逐帧进度换成澎湃3弹簧曲线（只改一个数值 ✓ 安全路线 ✓）。 */
    static volatile boolean springCurve = false;

    /** ★ S10：接管图标/组件的**进场动画曲线**（只替换 AnimConfig.setEase 的入参 ✓）。 */
    static volatile boolean itemCurve = false;

    /** ★ S11-A：各项自己缩放（图标/组件进场"从小变大" ✓）。默认 **false** ⇒ A 版确认后再开 ✓ */
    static volatile boolean itemScale = false;

    /** ★ P67 只读探针：点卡片 → 打开的链（只打日志 ✓）。 */
    static volatile boolean cardProbe = true;

    /** ★ S13：点卡片 ⇒ 从原位放大展开（注入 ActivityOptions.makeScaleUpAnimation ✓）。 */
    static volatile boolean cardOpen = true;
    /** ★ P127：HVS 圆角半径（px ✓ 0 = 不干预 ⇒ 用系统默认 ✓）。配置键 hvs_radius */
    static volatile float hvsRadius = 0f;
    /** ★ P130：HVS 弹簧参数（8 组×9 float 的简化：逗号分隔 ✓ 空 = 不干预 ✓）。配置键 hvs_anim_param */
    static volatile float[] hvsAnimParam = null;
    /** ★ P130：是否给 bundle 带上 splashScreenStyle（默认带上 ✓ 实测两段无差 ✓）。配置键 hvs_splash */
    static volatile boolean hvsSplash = true;

    /**
     * ★ P133：是否把 options/intent 里的 RemoteTransition 换成**我们自己的**
     * （自定义 debugName ⇒ 避开 MIUI 的 HVS 白名单分支，走标准 RemoteTransition 路径）。
     * 依据：`结论总表 §FE`（HVS 对负一屏平台级不可达）。配置键 `ownrt`，默认 true。
     */
    static volatile boolean ownRt = true;

    /**
     * ★ P134：是否让**桌面侧**用我们发布的卡片 Rect 当远程过渡的源矩形。
     * 依据：`结论总表 §FG`（桌面 RT 的 `FastLaunchWindowElement#getSourceStackBounds()`
     * 在没有 `FastLaunchData` 时返回整屏 ⇒ 动画从屏幕中心放大）。配置键 `homert`，默认 true。
     */
    static volatile boolean homeRt = true;

    /** ★ P135：SystemUI 侧只读探针（找"决定动画源矩形"的那段代码，§FI）。配置键 suiprobe，默认 true。 */
    static volatile boolean suiProbe = true;

    /**
     * ★ P137：是否**真的接管**"从负一屏发起"的那次过渡（自己驱动 leash）。
     * 默认 **false** —— 先只验证判据命中率（宁可慢一步，也不拿系统过渡当赌注）。配置键 `suirtakeover`。
     */
    static volatile boolean suiRtTakeover = false;

    /**
     * ★ P139：接管时先跑**空动画**（进入即复位 + 立即回调）——只验证"接管不卡系统"。
     * 默认 true；确认不卡之后再置 false 上真正的几何动画。配置键 `suirtempty`。
     */
    static volatile boolean suiRtEmpty = true;

    /**
     * ★ P142：**判别实验模式** —— 把动画冻在中间态（p=0.35）保持 2.5 秒。
     * 目的只有一个：确认"我们写在 leash 上的变换到底有没有画出来"。配置键 `suirttest`，默认 false。
     */
    static volatile boolean suiRtTest = false;

    /**
     * ★ P143：在测试模式下**只设 alpha（完全不碰矩阵）** —— 用来判别"这个 leash 到底能不能被我们控制"。
     * 配置键 `suirtalpha`，默认 false。
     */
    static volatile boolean suiRtAlphaOnly = false;

    /** ★ P144：测试模式下**只改位置（不缩放、不动 alpha）** —— 分离出"矩阵 vs 位置"到底哪个不生效。配置键 `suirtpos`。 */
    static volatile boolean suiRtPosOnly = false;

    /** ★ P145：测试模式下**只改 matrix（缩放）、完全不动 position** —— 分离"矩阵"与"位置"。配置键 `suirtmatrix`。 */
    static volatile boolean suiRtMatrixOnly = false;

    /** ★ P146：测试模式下**把 rootLeash + 每个 change 的 leash 全部一起缩放**，用"全试"定位哪一层承载内容。配置键 `suirtall`。 */
    static volatile boolean suiRtAll = false;

    /**
     * ★ P147：**只对第 N 层**（`collectLeashes` 的顺序：0=Transition Root, 1=目标 Task, 2=桌面 Task, 3=壁纸）
     * 设 matrix，用来二分"哪一层只作用于目标应用窗口"。配置键 `suirtlayer`，默认 -1（关）。
     */
    static volatile int suiRtLayer = -1;

    /** ★ P149：真动画改用 `Transition Root` 那一层（实测**唯一**能响应几何的层）当动画目标。配置键 `suirtuseroot`。 */
    static volatile boolean suiRtUseRoot = false;

    /**
     * ★ P150：动画时长（ms）。默认 420（与官方一致）；验收/录屏时可临时调大（例如 3000）以便抽帧看清。
     * **只影响我们自己的动画**，不碰系统任何设置。配置键 `suirtdur`。
     */
    static volatile int suiRtDur = 420;

    /**
     * ★ P151：是否**同时把几何也写进 finishT**（收尾事务）。默认 **false**。
     * 实测教训（§FU）：加上它之后，3 秒慢动作动画"画面零变化"；去掉后动画可见
     * ⇒ finishT 是"最终状态"，每帧往它写当前值会把动画压平。
     */
    static volatile boolean suiRtWriteFinishT = false;

    /**
     * ★ P152：自建层可见性探针 —— 过渡期间由我们自己的 SurfaceControl 建层并画纯品红，
     * 验证"我们能不能在系统过渡之上放自己的层"（能 ⇒ 走桌面那条"快照层 + 应用窗口"两层结构）。
     * 配置键 suirownlayer，默认 false。
     */
    static volatile boolean suiOwnLayer = false;

    /**
     * ★ P158：**过渡分发只读探针**（默认 true）。hook `Transitions#dispatchTransition` ——
     * 那是**全局唯一**的过渡分发口（真值来自 Miui-WindowManager-Shell.jar 反汇编），
     * 每次过渡都打一行：type + 是否负一屏发起 + **哪个 handler 接走**。
     * 目的是用日志回答"为什么有些卡片启动没有动画"（只读，绝不接管）。
     */
    static volatile boolean suiDispatch = true;

    /**
     * ★ P159：**脱离系统过渡起手** —— SystemUI 侧轮询"负一屏刚发布的卡片快照"（PNG 写入 = 就绪信号），
     * 一发现新的就立即建层播放，**不依赖系统是否派发过渡**（§FZ3：热启动时系统零过渡）。
     * 配置键 `suipoll`，默认 false。
     */
    static volatile boolean suiPoll = false;

    /**
     * ★ P160：**自建覆盖窗口**播放动画（不借系统窗口当父层）。配置键 `suioverlay`，默认 false。
     * 动机见 {@link SuiOverlay}：§GA2 实测 SystemUI 没有可用的全屏父层，导致自建 SurfaceControl 画不出来。
     */
    static volatile boolean suiOverlay = false;

    /** 覆盖窗口的窗口类型（可试：2038=应用覆盖层 / 2019=导航栏 / 2000=状态栏 / 2032=??）。配置键 `suioverlaytype`。 */
    static volatile int suiOverlayType = 2038;

    /** 读 hvs_radius（只在解析时用 ✓）。 */
    static float hvsRadiusPx() {
        return hvsRadius;
    }

    /** 探针②：SlidingPaneStateManager#b(h,int,r)（goToState 总入口）。 */
    static volatile boolean stateManager = true;

    /** 探针③：AssistContentView 的 4 个应用层入口。 */
    static volatile boolean assist = true;

    /**
     * 探针④（P3 第一步）：桌面 → 负一屏 的 AIDL 并行通道
     * （{@code ILauncherOverlay#onTransact} + {@code BaseCallback/LauncherOverlayCallback#handleMessage}）。
     * <b>只读</b>；false = 只停这一个探针，不影响①②③。
     */
    static volatile boolean overlay = true;

    /**
     * 探针⑤（P4）：HyperViewScale <b>引擎兜底探针</b>（{@code k=engine}，三个落点，全部<b>只读</b>）。
     *
     * <p>目的：把"引擎在负一屏路径上零参与"从静态证据升级为运行时证据 ——
     * 「打开 → 返回」一整轮里 {@code k=engine} 应当是 <b>0 条</b>。
     * {@code false} = 只停这一个探针，不影响①②③④。
     */
    static volatile boolean engine = true;

    /** 事件行是否附带调用栈深度（{@code new Throwable().getStackTrace()} 有开销，默认关）。 */
    static volatile boolean stack = false;

    /** 内存环形缓冲上限。 */
    static volatile int buf = 8192;

    /**
     * ★ 日志级别（0/1/2，见 {@link #LOG_DISABLED}）。默认 <b>1（常规）</b>——与 HyperCeiler
     * {@code getDefaultLogLevel()} 同值。
     *
     * <p>作用于两处：①"是否把这一行<b>推给 App</b>"（{@link LogPush#wanted(int)}）；
     * ② App 侧设置页的三选一。**不影响** {@code ANIMV4-P1.txt} 的落盘行为。
     */
    static volatile int loglevel = LOG_GENERAL;

    // ------------------------------------------------ P2：返回方向曲线（照 v2 的 Cfg 风格）

    /**
     * 返回方向（负一屏 → 桌面）**生效**：P2 的唯一变量。
     * 置 false = 只停这一次的改动（探针/日志照旧），不用重装 APK。
     */
    static volatile boolean applyReturn = true;

    /**
     * 打开方向（桌面 → 负一屏）**默认不生效**（交回原厂）。
     *
     * <p>依据：2026-10-02 用户明确决定 —— "桌面到负一屏的动画不用调，官方效果就不错"。
     * false 时该方向<b>一行都不写</b>（{@link ReturnDrive} 在方向闸处就返回）。
     */
    static volatile boolean applyOpen = false;

    /**
     * 指数平滑系数 α ∈ [0.05, 1]。v2 真机验证过的默认值是 <b>0.62</b>。
     * α=1.0 等于完全跟随原厂（不改）。
     */
    static volatile float smooth = 0.62f;

    /**
     * ★ 可见性探针（2026-10-03）：非 0 时，pre-draw 里直接写 {@code 原厂tx + probeOffset}（像素）。
     * 用途：回答一个**决定性问题** —— 我们在 pre-draw 写 {@code translationX} 到底有没有到达屏幕。
     * 默认 0 = 关闭（不影响正常平滑）。改 {@code animv3.conf} 的 {@code probeOffset=300} 即开。
     */
    static volatile int probeOffset = 300;   // ★ P13 临时打开：300px 可见性探针（下一版恢复 0）

    /**
     * ★ LOW#4（2026-10-04）：<b>5 秒兜底心跳开关</b>（{@code animv3.conf} 的 {@code hb=on|off}，
     * 默认 <b>on</b>）。
     *
     * <p>{@code off} 时 {@code Bridge} 仍会建好那条推送线程与 {@link android.os.Handler}
     * （PING 应答、{@code onEnter/onLeave} 交互推送、级别回读都要用它），
     * 但<b>不排 5 秒的自我续期</b> ⇒ 周期定时器消失，与"探活由 App 侧发起"的文档一致。
     * 解析按 {@code !"off".equalsIgnoreCase(v)}：只有明确写 off 才关，其余值都当 on。
     */
    static volatile boolean hb = true;

    // ------------------------------------------------------------------ 读取

    private static volatile long sLastMtime = -1L;

    private AnimCfg() {
    }

    /** 冷路径：无条件读一次（onPackageReady 调用，见 {@code Entry#reloadCfgColdOnce}）。 */
    static void reloadNow() {
        try {
            File f = new File(PATH);
            long m = f.lastModified();
            if (m == 0L) {
                sLastMtime = 0L;
                resetDefault();
                Diag.ev("P1 配置：文件不存在，用默认值 " + describe());
                return;
            }
            // ★ LOW#2：只有"整份都解析成功"才记 mtime；有坏键 ⇒ 保留旧 mtime，下次还会重读。
            if (reload()) {
                sLastMtime = m;
                Diag.ev("P1 配置已加载：" + describe());
            } else {
                Diag.ev("P1 配置有坏键（坏键已忽略、其余已生效）⇒ 保留旧 mtime，下次仍会重读："
                        + describe());
            }
        } catch (Throwable t) {
            Diag.ev("P1 配置检查失败（用默认值）: " + t);
        }
    }

    /**
     * 定时轮询入口：**先 stat，只有文件真的变了才读**。
     * 由 {@code Entry} 的独立轮询线程（{@code animv3-cfg-poll}）每 3s 调用一次，
     * 所以既不进热路径，也不在宿主主线程上做文件 I/O。
     */
    static void reloadIfChanged() {
        try {
            File f = new File(PATH);
            long m = f.lastModified();
            if (m == 0L) {
                if (sLastMtime > 0L) {
                    sLastMtime = -1L;
                    resetDefault();
                    Diag.ev("P1 配置已删除，恢复默认：" + describe());
                }
                return;
            }
            if (m == sLastMtime) {
                return;                     // ★ 没变：一次 stat 就结束，不读盘
            }
            // ★ LOW#2：先 reload，成功才推进 sLastMtime。
            //   原来先写 sLastMtime 再 reload ⇒ 一个坏值抛出去被外层吞掉并跳出循环后，
            //   坏行之后的所有键静默丢失，而且 mtime 已更新 ⇒ 修好文件也不再重读。
            if (reload()) {
                sLastMtime = m;
                Diag.ev("P1 配置已加载：" + describe());
            } else {
                Diag.ev("P1 配置有坏键（坏键已忽略、其余已生效）⇒ 不推进 mtime，下次还会重读："
                        + describe());
            }
        } catch (Throwable t) {
            Diag.ev("P1 配置轮询失败（保持现值）: " + t);
        }
    }

    private static void resetDefault() {
        probe = true;
        write = true;
        logcat = true;
        pane = true;
        stateManager = true;
        assist = true;
        overlay = true;
        engine = true;
        stack = false;
        buf = 8192;
        applyReturn = true;
        applyOpen = false;
        smooth = 0.62f;
        probeOffset = 0;
        hb = true;                                  // ★ LOW#4：心跳默认 on
        // ★ 级别不走"写死默认值"：App 推过来的级别存盘后要能在宿主重启后继续生效。
        loglevel = readPersistedLevel();
    }

    /**
     * 读并应用整份配置。
     *
     * <p><b>★ LOW#2：每个可能抛的 case 各自 try/catch</b> —— 坏键只忽略该键并记一行，
     * 循环继续（原来 {@code Integer.parseInt/Float.parseFloat} 一抛就被外层
     * {@code catch(Throwable)} 吞掉并跳出 while ⇒ 坏行之后的所有键静默丢失）。
     *
     * @return 是否整份都解析成功；{@code false} ⇒ 调用方<b>不要</b>推进 {@link #sLastMtime}
     *         （这样修好文件后无需改动 mtime 也会被重读）
     */
    private static boolean reload() {
        File f = new File(PATH);
        if (!f.exists()) {
            return true;
        }
        // ★ 先取"App 推过来的持久级别"，随后若 conf 里显式写了 loglevel= 就压过它。
        int pushed = readPersistedLevel();
        loglevel = pushed;
        boolean parsed = true;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                // ★ 修复（2026-10-03 审计）：布尔键原来一律 `Boolean.parseBoolean(v)` ⇒ 拼错的值
                //   （`write=ture` / `hb=offf`）会被**静默**当成 false/true，且不置 parsed=false
                //   ⇒ "坏键下次重读"机制对布尔键**整体失效**。现在统一先走白名单校验：
                //   非法值 ⇒ 记坏键、置 parsed=false、**保留原值**并跳过这个键。
                if (isBoolKey(k) && boolKey(k, v) == null) {
                    parsed = false;
                    badKey(k, v, new IllegalArgumentException("布尔键只接受 true/false"
                            + ("hb".equals(k) ? "（hb 还接受 on/off）" : "")));
                    continue;
                }
                switch (k) {
                    case "probe":        probe = Boolean.parseBoolean(v); break;
                    case "closeanim":    closeAnim = Boolean.parseBoolean(v); break;
                    case "srcprobe":     srcProbe = Boolean.parseBoolean(v); break;
                    case "srcsnap":      srcSnap = Boolean.parseBoolean(v); break;
                    case "srcitems":     srcItems = Boolean.parseBoolean(v); break;
                    case "srcanim":      srcAnim = Boolean.parseBoolean(v); break;
                    case "srchide":      srcHide = Boolean.parseBoolean(v); break;
                    case "viewanim":     viewAnim = Boolean.parseBoolean(v); break;
                    case "winmatrix":    winMatrix = Boolean.parseBoolean(v); break;
                    case "springcurve":  springCurve = Boolean.parseBoolean(v); break;
                    case "itemcurve":    itemCurve = Boolean.parseBoolean(v); break;
                    case "itemscale":    itemScale = Boolean.parseBoolean(v); break;
                    case "cardprobe":    cardProbe = Boolean.parseBoolean(v); break;
                    case "cardopen":     cardOpen = Boolean.parseBoolean(v); break;
                    case "ownrt":        ownRt = Boolean.parseBoolean(v); break;
                    case "homert":       homeRt = Boolean.parseBoolean(v); break;
                    case "suiprobe":     suiProbe = Boolean.parseBoolean(v); break;
                    case "suirtakeover": suiRtTakeover = Boolean.parseBoolean(v); break;
                    case "suirtempty":  suiRtEmpty = Boolean.parseBoolean(v); break;
                    case "suirttest":   suiRtTest = Boolean.parseBoolean(v); break;
                    case "suirtalpha":  suiRtAlphaOnly = Boolean.parseBoolean(v); break;
                    case "suirtpos":    suiRtPosOnly = Boolean.parseBoolean(v); break;
                    case "suirtmatrix": suiRtMatrixOnly = Boolean.parseBoolean(v); break;
                    case "suirtall":    suiRtAll = Boolean.parseBoolean(v); break;
                    case "suirtuseroot": suiRtUseRoot = Boolean.parseBoolean(v); break;
                    case "suirtfinisht": suiRtWriteFinishT = Boolean.parseBoolean(v); break;
                    case "suirownlayer": suiOwnLayer = Boolean.parseBoolean(v); break;
                    case "suidispatch": suiDispatch = Boolean.parseBoolean(v); break;
                    case "suipoll": suiPoll = Boolean.parseBoolean(v); break;
                    case "suioverlay": suiOverlay = Boolean.parseBoolean(v); break;
                    case "suioverlaytype": {
                        try { suiOverlayType = Integer.parseInt(v.trim()); } catch (Throwable ignored) { }
                        break;
                    }
                    case "suirtdur": {
                        try { suiRtDur = Integer.parseInt(v.trim()); } catch (Throwable ignored) { }
                        break;
                    }
                    case "suirtlayer": {
                        try { suiRtLayer = Integer.parseInt(v.trim()); } catch (Throwable ignored) { }
                        break;
                    }
                    case "hvs_radius":   try { hvsRadius = Float.parseFloat(v); } catch (Throwable ignored) { } break;
                    case "hvs_splash":   hvsSplash = Boolean.parseBoolean(v); break;
                    case "hvs_anim_param":
                        try {
                            final String[] ps = v.split(",");
                            final float[] arr = new float[ps.length];
                            for (int i = 0; i < ps.length; i++) { arr[i] = Float.parseFloat(ps[i].trim()); }
                            hvsAnimParam = arr;
                        } catch (Throwable ignored) { }
                        break;
                    case "write":        write = Boolean.parseBoolean(v); break;
                    case "logcat":       logcat = Boolean.parseBoolean(v); break;
                    case "pane":         pane = Boolean.parseBoolean(v); break;
                    case "stateManager": stateManager = Boolean.parseBoolean(v); break;
                    case "assist":       assist = Boolean.parseBoolean(v); break;
                    case "overlay":      overlay = Boolean.parseBoolean(v); break;
                    case "engine":       engine = Boolean.parseBoolean(v); break;
                    case "stack":        stack = Boolean.parseBoolean(v); break;
                    case "applyReturn":  applyReturn = Boolean.parseBoolean(v); break;
                    case "applyOpen":    applyOpen = Boolean.parseBoolean(v); break;
                    case "hb":           hb = !"off".equalsIgnoreCase(v.trim()); break;
                    case "probeOffset": {
                        try {
                            probeOffset = Integer.parseInt(v.trim());
                        } catch (Throwable t) {
                            parsed = false;
                            badKey(k, v, t);
                        }
                        break;
                    }
                    case "loglevel": {
                        // ★ 显式写在 conf 里 ⇒ 压过"App 推来的持久级别"（给手工排查留一条稳的路）
                        try {
                            loglevel = clampLevel(Integer.parseInt(v.trim()));
                        } catch (Throwable t) {
                            parsed = false;
                            badKey(k, v, t);
                        }
                        break;
                    }
                    case "smooth": {
                        try {
                            float a = Float.parseFloat(v);
                            if (a < 0.05f) a = 0.05f;
                            if (a > 1f) a = 1f;
                            smooth = a;
                        } catch (Throwable t) {
                            parsed = false;
                            badKey(k, v, t);
                        }
                        break;
                    }
                    case "buf": {
                        try {
                            int n = Integer.parseInt(v);
                            if (n < 256) n = 256;
                            if (n > 262144) n = 262144;
                            buf = n;
                        } catch (Throwable t) {
                            parsed = false;
                            badKey(k, v, t);
                        }
                        break;
                    }
                    default: break;
                }
            }
        } catch (Throwable t) {
            parsed = false;
            Diag.ev("P1 配置读取失败（保持已生效的键）: " + t);
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return parsed;
    }

    /** ★ LOW#2：坏键只记一行，不打断后续键的解析。 */
    private static void badKey(String k, String v, Throwable t) {
        try {
            Diag.ev("P1 配置坏键（忽略该键，其余继续）: " + k + "=" + v + " : " + t);
        } catch (Throwable ignored) {
        }
    }

    static String describe() {
        return "probe=" + probe + " write=" + write + " logcat=" + logcat
                + " pane=" + pane + " stateManager=" + stateManager + " assist=" + assist
                + " overlay=" + overlay
                + " engine=" + engine
                + " stack=" + stack + " buf=" + buf
                + " | applyReturn=" + applyReturn + " applyOpen=" + applyOpen + " smooth=" + smooth
                + " | hb=" + (hb ? "on" : "off")
                + " | loglevel=" + loglevel + "(" + levelDesc(loglevel) + ")"
                + " viewanim=" + viewAnim + " winmatrix=" + winMatrix + " closeanim=" + closeAnim + " srcprobe=" + srcProbe + " srcitems=" + srcItems + " srcanim=" + srcAnim + " srchide=" + srcHide + " springcurve=" + springCurve + " itemcurve=" + itemCurve + " itemscale=" + itemScale + " cardprobe=" + cardProbe + " cardopen=" + cardOpen + " ownrt=" + ownRt + " homert=" + homeRt + " suiprobe=" + suiProbe + " suirtakeover=" + suiRtTakeover + " suirtempty=" + suiRtEmpty + " suirttest=" + suiRtTest + " suirtalpha=" + suiRtAlphaOnly + " suirtpos=" + suiRtPosOnly + " suirtmatrix=" + suiRtMatrixOnly + " suirtall=" + suiRtAll + " suirtlayer=" + suiRtLayer + " suirtuseroot=" + suiRtUseRoot + " suirtdur=" + suiRtDur + " suirownlayer=" + suiOwnLayer + " suidispatch=" + suiDispatch + " suipoll=" + suiPoll + " suioverlay=" + suiOverlay + " suioverlaytype=" + suiOverlayType + " suirtfinisht=" + suiRtWriteFinishT;
    }

    // ------------------------------------------------------------------ ★ 日志级别（0/1/2）

    /** 级别名，逐字照 HyperCeiler {@code LogLevelManager.logLevelDesc()}（LogLevelManager.kt:62-69）。 */
    static String levelDesc(int level) {
        switch (level) {
            case LOG_DISABLED: return "Disabled";
            case LOG_GENERAL:  return "General";
            case LOG_VERBOSE:  return "Detailed";
            default:           return "Unknown";
        }
    }

    /** 夹到 0..2（非法值一律当"常规"，与 HyperCeiler 的 {@code else -> getDefaultLogLevel()} 同策略）。 */
    static int clampLevel(int v) {
        if (v < LOG_DISABLED || v > LOG_VERBOSE) {
            return LOG_GENERAL;
        }
        return v;
    }

    /**
     * ★ App 侧推来的级别（{@code Bridge} 的 LEVEL 广播）：
     * <b>先改内存（立刻生效），再异步落盘</b>（宿主主线程上绝不碰文件，见 v1 冻结事故）。
     *
     * @return 实际生效的级别；非法值回落到 {@link #LOG_GENERAL}
     */
    static int setLoglevelFromPush(int v) {
        final int n = clampLevel(v);
        loglevel = n;
        try {
            // ★ 修复 #3：不再每次 new Thread。把"最新待落盘级别"投进邮箱并唤醒常驻线程
            //   （写线程自己合并：连推多次只会落最后一个值）。
            sPendingLevel.set(n);
            synchronized (LEVEL_LOCK) {
                if (!sLevelWriterStarted) {
                    sLevelWriterStarted = true;
                    Thread t = new Thread(AnimCfg::levelWriterLoop, "animv4-loglevel-write");
                    t.setDaemon(true);
                    t.start();
                }
                LEVEL_LOCK.notifyAll();
            }
        } catch (Throwable ignored) {
            // 落盘失败不影响"本次生效"；下次推来还会再试
        }
        return n;
    }

    /**
     * ★ 修复 #3：级别落盘的常驻守护线程（全进程只有一条）。
     * 邮箱里没有新值就在 {@link #LEVEL_LOCK} 上等（{@code setLoglevelFromPush} 会唤醒）。
     */
    private static void levelWriterLoop() {
        for (;;) {
            int v;
            try {
                synchronized (LEVEL_LOCK) {
                    v = sPendingLevel.getAndSet(Integer.MIN_VALUE);
                    while (v == Integer.MIN_VALUE) {
                        LEVEL_LOCK.wait(1000L);
                        v = sPendingLevel.getAndSet(Integer.MIN_VALUE);
                    }
                }
                persistLevel(v);
            } catch (Throwable t) {
                try {
                    Thread.sleep(500L);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 读 {@link #LEVEL_PATH}；读不到 ⇒ 默认 {@link #LOG_GENERAL}。 */
    static int readPersistedLevel() {
        BufferedReader r = null;
        try {
            File f = new File(LEVEL_PATH);
            if (!f.exists()) {
                return LOG_GENERAL;
            }
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line = r.readLine();
            if (line == null) {
                return LOG_GENERAL;
            }
            return clampLevel(Integer.parseInt(line.trim()));
        } catch (Throwable t) {
            return LOG_GENERAL;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 写 {@link #LEVEL_PATH}（只在推送线程/短命线程上调用）。 */
    private static void persistLevel(int n) {
        FileOutputStream fos = null;
        try {
            File f = new File(LEVEL_PATH);
            File dir = f.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return;
            }
            fos = new FileOutputStream(f, false);
            fos.write((n + "\n").getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable ignored) {
            // 落盘失败不影响功能
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
