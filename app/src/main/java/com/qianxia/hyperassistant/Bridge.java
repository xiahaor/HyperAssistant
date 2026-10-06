package com.qianxia.hyperassistant;

import android.app.Application;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.View;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * <b>探活桥（请求-应答）</b>：在负一屏进程里动态注册一个 {@code PING} 接收器，
 * 收到就<b>立刻</b>回一条带 {@code alive/pid/ts} 的应答。
 *
 * <h3>2026-10-03 修复：三个原因里有两个在这里</h3>
 * <table>
 *   <tr><th>原因</th><th>原来</th><th>现在</th></tr>
 *   <tr>
 *     <td><b>A. Context 来源</b></td>
 *     <td>{@link Reflect#currentApplication()} 是<b>主路径</b>（隐藏 API 一被挡就整桥失效）；
 *         备用路径挂在 {@code onAttachedToWindow}，但那条路径只在"钩子挂上之后 View 才 attach"时才有用</td>
 *     <td><b>主路径换成 {@code View.getContext()}</b>（公开 API、不需要权限、不需要反射）：
 *         {@code AssistContentView#onEnter(boolean)/#onLeave()/#onScroll(float)} 的
 *         {@code chain.getThisObject()} 就是一个 {@code View}（反编译已证：该类
 *         {@code extends FrameLayout}）⇒ 懒注册、只注册一次；
 *         {@code currentApplication()} <b>降级为备用</b>（成功/失败都记一行）</td>
 *   </tr>
 *   <tr>
 *     <td><b>B. 接收器 flag</b></td>
 *     <td>已显式给 {@code Context.RECEIVER_EXPORTED}（值已核实，见下），但整段代码从没被执行</td>
 *     <td>保持 {@code RECEIVER_EXPORTED}，并把"实际用的常量值 / 是否降级 / 异常原文"写进日志</td>
 *   </tr>
 * </table>
 * 第三个原因（<b>整座桥从来没被安装</b>）在 {@link Entry}：那里原来没有任何生命周期覆写。
 *
 * <h3>Context 为什么必须换（原因 A 的依据）</h3>
 * <pre>
 * .scan/asst_full/sources/com/miui/personalassistant/core/view/AssistContentView.java
 *   :89    public class AssistContentView extends FrameLayout implements n5.d, …   ← 它就是一个 View
 *   :1063  public void onAttachedToWindow()
 *   :1212  public void onEnter(boolean z8)     ← 我们的探针③正在挂
 *   :1276  public void onLeave()               ← 我们的探针③正在挂
 *   :1460  public void onScroll(float f7)      ← 我们的探针③正在挂
 * </pre>
 * {@code View.getContext()} 由框架在 View 构造时注入，是公开 API ⇒ 既不吃 hidden-API 限制，
 * 也不需要任何权限。{@code ActivityThread.currentApplication()} 是 {@code @hide} 反射，
 * 隐藏 API 策略一变（或 Application 尚未建好）就返回 null ⇒ 只配当备用。
 *
 * <h3>接收器 flag 的核实（不是猜）</h3>
 * <pre>
 * $ javap -constants -classpath .scan/androidlib/android.jar android.content.Context | grep RECEIVER
 *   public static final int RECEIVER_EXPORTED = 2;
 *   public static final int RECEIVER_NOT_EXPORTED = 4;
 * $ javap -classpath .scan/androidlib/android.jar android.content.Context | grep registerReceiver
 *   public abstract android.content.Intent registerReceiver(BroadcastReceiver, IntentFilter, int);
 *   public abstract android.content.Intent registerReceiver(BroadcastReceiver, IntentFilter, String, Handler, int);
 * </pre>
 * 接收器在<b>负一屏进程</b>里，发送方是<b>另一个 uid</b>（本模块的 App）⇒ Android 14+ 必须
 * {@code RECEIVER_EXPORTED}；用 {@code RECEIVER_NOT_EXPORTED} 时那条广播会被<b>直接丢弃</b>
 * （"Sent to a non-exported receiver"），App 侧就永远等不到 PONG。HMC 同款：
 * {@code /tmp/hmc/…/ProbeGuard.java:110  ctx.registerReceiver(open, filter, Context.RECEIVER_EXPORTED)}。
 *
 * <h3>★ 2026-10-03 第二个变量：把"检测"从"拉"改成"推优先"</h3>
 * 真机日志（{@code ANIMV4-P1.txt}，281 行，pid=18196）证明本类<b>活着</b>：接收器
 * {@code ✅ 已注册 flag=2(RECEIVER_EXPORTED)}、三个 View 入口全挂上，
 * 但整份日志里<b>没有一行"桥：收到 PING"</b> ⇒ App → 负一屏 的<b>拉取投递失败</b>
 * （负一屏在后台，MIUI/系统拦了跨应用广播）。修法：
 * <pre>
 *   ① 注册成功那一刻      ⇒ 立刻推一条 PONG（并启动 5s 兜底）
 *   ② 每次负一屏交互      ⇒ onEnter/onLeave 各一条；onScroll 节流 1000ms
 *   ③ 低频兜底            ⇒ 每 5000ms 一条（独立线程，进程死即停）
 * </pre>
 * 关键：PONG 的接收方是<b>前台的模块 App</b>，前台动态接收器不受后台广播限制
 * ⇒ 不再依赖"往后台进程投递"。拉取（PING→result extras）<b>原样保留</b>作为快速路径。
 *
 * <h3>★ 2026-10-03 第三个变量（本次）：把"注册时机"提前到<b>进程启动</b></h3>
 * <b>症状（真机截图 + 日志，铁证）</b>：App 卡片写
 * {@code 已发出探测（目标：负一屏）· 无应答（有回包但没有 alive：接收器不在/模块没注入）}。
 * 有序广播"空手返回"正是 HMC 自己写的判据（
 * {@code /tmp/hmc/HyperMusicCover-main/app/src/main/java/com/os4/musiccover/ModuleBridge.kt:672-676}：
 * "It comes back even when no receiver is registered — with the 0 it was sent with and no extras"）
 * ⇒ <b>那一刻负一屏进程里没有接收器</b>。
 *
 * <p><b>为什么没有</b>：{@code install()} 里 {@code ActivityThread.currentApplication()} 返回 null
 * （真机 {@code ANIMV4-P1.txt}：{@code 桥：备用路径 currentApplication() 返回 null}），
 * 于是 Context 只能等 {@code AssistContentView} 的 View 出现后从 {@code View.getContext()} 拿
 * —— 真机时间线：进程 {@code 10:26:26} 起，接收器 {@code 10:26:41} 才注册，
 * <b>中间 15 秒任何 PING 都必然"空手返回"</b>。
 *
 * <p><b>本轮的修法（只动"注册时机"这一个变量）</b>：挂三个"进程启动必然经过、
 * 且 this/参数就是 Application/Context"的早期点，拿到就立刻注册（注册本来就幂等：
 * {@link #REGISTERED} 是 CAS）：
 * <ol>
 *   <li>{@code android.app.Application#onCreate()} —— {@code this} 就是 Application ✅ 首选；</li>
 *   <li>{@code android.app.Application#attach(android.content.Context)} —— 比 onCreate 更早，
 *       {@code this} 是 Application、参数是本进程的 app ContextImpl；</li>
 *   <li>{@code android.app.Instrumentation#callApplicationOnCreate(android.app.Application)}
 *       —— 参数就是 Application（它就是上面 {@code onCreate} 的唯一框架调用者）。</li>
 * </ol>
 * ⚠️ 任务书列的 {@code Application#attachBaseContext(Context)} <b>在本 ROM 的 {@code Application}
 * 上不存在</b>（它是 {@code android.content.ContextWrapper} 声明的，见 {@code .scan/framework/framework.jar}
 * 反汇编），直接挂 {@code Application#attachBaseContext} 会 {@code NoSuchMethodException} ⇒
 * 用 ROM 里真实存在的 {@code Application#attach(Context)} 代替（同样 {@code this}=Application）。
 *
 * <p><b>为什么"装钩子的时候 Application#onCreate 还没跑"是<b>证过的</b>、不是猜的</b>（三条本地证据）：
 * <pre>
 * ① {@code ActivityThread.currentApplication()} 的字节码就是读 {@code mInitialApplication}
 *    （.scan/framework/framework.jar: currentApplication → currentActivityThread().mInitialApplication）
 * ② {@code ActivityThread.handleBindApplication} 的字节码次序（同一次反汇编）：
 *      code_off 1269  LoadedApk.makeApplicationInner(v0,v3,v2)      ← 建 Application（内部调 attach）
 *      code_off 1349  iput-object v2, v1, mInitialApplication       ← 到这里 currentApplication() 才非 null
 *      code_off 1492  Instrumentation.callApplicationOnCreate(v0,v2) ← onCreate 的唯一框架调用点
 *    ⇒ "currentApplication()==null" ⟺ 还没走到 1349 ⟹ 必然还没走到 1492 ⟹ onCreate 尚未执行
 * ③ 真机日志 10:26:26.122 就是 null（同一次 {@code install()}），10:26:41 的注册行 ctx=PAApplication
 *    ⇒ 我们是"在 Application 建好之前"进的场，钩 onCreate 必命中。
 * </pre>
 * 若某 ROM/某次 {@code onPackageReady} 晚于 {@code onCreate}（本机日志不支持，但保底）：
 * 三个早期钩子都不会触发 ⇒ <b>自动回落到原有全部兜底路径</b>（{@code View.getContext} 的
 * {@link #noteView}、注册即推 PONG、5 秒兜底推送、PING 接收器），行为与本轮之前完全一致 ⇒ 无回归。
 *
 * <h3>只读性 / 安全性</h3>
 * <ul>
 *   <li>不读也不写宿主任何状态：只在收到 PING 时回一个 {@code alive/pid/ts}；</li>
 *   <li>探活本身<b>完全由 App 侧发起</b>（PING → PONG）。注册成功后会额外起一条
 *       <b>默认 5 秒</b>的兜底 PONG 推送（见 {@link #startHeartbeat}），
 *       可用 {@code animv3.conf} 的 {@code hb=off} 关掉；关掉后 PING 应答与
 *       {@code onEnter/onLeave} 交互推送照旧。接收器<b>不依赖</b>这个定时器；</li>
 *   <li>导出接收器（{@code RECEIVER_EXPORTED}）<b>先校验发送方 uid</b>：
 *       只接受模块 App 的 uid（PING 与 LEVEL 都校验），解析不到模块 uid 时
 *       拒绝 LEVEL、只放行 PING（见 {@link #senderAllowed}）；</li>
 *   <li>所有异常一律吞掉并<b>显式记日志</b>，绝不影响负一屏进程。</li>
 * </ul>
 */
final class Bridge {

    /** 请求动作名。App 侧（{@code ModuleSettings.kt} 的 {@code ACTION_PING}）必须是同一个字面量。 */
    static final String ACTION_PING = "com.qianxia.HyperAssistant.PING";

    /**
     * 应答动作名（第二条腿）。
     *
     * <p>应答走两条腿：① 有序广播的 result extras（HMC 原样，
     * {@code ModuleBridge.kt:686-712}）；② 显式 PONG 广播直投模块 App。
     * ② 让"App 侧链路"可以用 {@code adb shell am broadcast} 单独注入验证（本次无 adb，仅备用）。
     */
    static final String ACTION_PONG = "com.qianxia.HyperAssistant.PONG";

    /** 模块 App 的包名（PONG 直投广播的目标包）。 */
    private static final String MODULE_PKG = "com.qianxia.HyperAssistant";

    /**
     * ★ 2026-10-04 日志功能：<b>级别设置（App → 负一屏）</b>。
     *
     * <p>App 侧设置页的三选一（关闭/常规/详细）用它推过来；收到就
     * {@link AnimCfg#setLoglevelFromPush(int)}（内存立刻生效 + 异步落盘到
     * {@code animv3.loglevel}，宿主重启后仍然有效）。字段名
     * {@link #KEY_LEVEL}。
     *
     * <p>为什么不用"App 直接改 animv3.conf"：本模块 App <b>没有存储权限</b>
     * （Android 11+ 分区存储），根本写不了 {@code /sdcard/Download/dsh-debuglog/}。
     * 所以级别只能走既有的桥。这条广播和 PING 一样是"App→后台宿主"，可能被拦；
     * 真正保底的是反向的 {@link #ACTION_QUERY_LEVEL}。
     */
    static final String ACTION_LOGLEVEL = "com.qianxia.HyperAssistant.LEVEL";

    /**
     * ★ 2026-10-04 日志功能：<b>级别回读（负一屏 → App）</b>。
     *
     * <p>方向与 {@link #ACTION_PONG} 相同（宿主推、App 收），所以投递成功率与推送主通道同级：
     * 宿主在<b>每次 onEnter/onLeave</b> 与<b>每 3 次心跳（≈15 秒）</b>问一次
     * "你期望的级别是多少？"（{@link #queryLevel(String)}），App 在有结果时用
     * {@code setResultExtras} 把 {@link #KEY_LEVEL} 回填。
     *
     * <p>为什么必须要有这条反向通道：App→宿主的级别设置在"宿主被冻结/在后台"时会被系统拦掉
     * （与 PING 判红的根因同源，见类注释），而宿主每次被用户拉起来（onEnter）时是活的
     * ⇒ 由宿主主动问，才能保证"设了级别就一定生效"。
     */
    static final String ACTION_QUERY_LEVEL = "com.qianxia.HyperAssistant.QUERY_LEVEL";

    /** ★ 日志级别字段名（{@link #ACTION_LOGLEVEL} 与 {@link #ACTION_QUERY_LEVEL} 共用）。 */
    static final String KEY_LEVEL = "level";

    /** 本模块唯一的作用域 / 桥所在进程的包名（PING 的目标包，App 侧必须发到这里）。 */
    static final String HOST_PKG = "com.miui.personalassistant";

    /** 应答字段（照 HMC {@code Main.java:2741} 的 {@code alive}）。 */
    static final String KEY_ALIVE = "alive";
    private static final String KEY_PID = "pid";
    private static final String KEY_TS = "ts";

    /**
     * ★ 2026-10-03 推送优先新增：推送"原因"字段（App 侧只用于卡片/日志区分触发点）。
     *
     * <p>取值：{@code 接收器注册成功} / {@code 交互:onEnter} / {@code 交互:onLeave} /
     * {@code 交互:onScroll} / {@code 心跳兜底} / {@code PING应答}。
     * 纯诊断字段，App 侧忽略也不影响判定。
     */
    static final String KEY_REASON = "reason";

    /** ★ onScroll 推送节流：最多 1000ms 一条（onScroll 是逐帧回调，不节流会刷屏）。 */
    private static final long SCROLL_PUSH_THROTTLE_MS = 1000L;

    /**
     * ★ 低频兜底推送周期：5 秒一条。
     *
     * <p>"只在负一屏进程存活时"是**结构性保证**：定时器跑在本进程的独立线程上，
     * 进程一死线程即消失，不需要任何存活探测。推送本身只做
     * {@code sendBroadcast + Diag.ev}，不读盘、不遍历、不持有宿主对象。
     */
    private static final long HEARTBEAT_MS = 5000L;

    /** 探针③已经确认存在、且本类自己声明了 {@code onAttachedToWindow()} 的类。 */
    private static final String CLS_ASSIST = HOST_PKG + ".core.view.AssistContentView";

    /** 只注册一次（HMC {@code Main.java:2078-2082} 的 {@code sReceiverRegistered}）。 */
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    /** "挂钩子"只做一次（{@link #install} 可能被 onPackageReady 多次调用）。 */
    private static final AtomicBoolean HOOKED = new AtomicBoolean();

    // ---------------------------------------------------------------- ★ 早期注册（本轮唯一变量）

    /**
     * 早期 Context 钩子只挂一次（{@link #installEarlyHooks} 可能被 onPackageReady 多次调用）。
     * 只有至少挂上一个才置位 ⇒ 全挂失败时下次 onPackageReady 还能再试。
     */
    private static final AtomicBoolean EARLY_HOOKED = new AtomicBoolean();

    /** "early 命中"只记一行日志（钩子每个进程只跑一次，这里只是防御性闸门）。 */
    private static final AtomicBoolean EARLY_HIT_LOGGED = new AtomicBoolean();

    /**
     * 早期来源标签。取值必须与任务书要求的日志逐字一致：
     * {@code 桥：✅ 早期注册成功（来源=Application#onCreate）}。
     */
    static final String SRC_APP_ONCREATE = "Application#onCreate";
    static final String SRC_APP_ATTACH = "Application#attach";
    static final String SRC_INSTR_ONCREATE = "Instrumentation#callApplicationOnCreate";

    /** 日志"只记一次"的闸门（不刷屏）。 */
    private static final AtomicBoolean HOOK_MISS_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean CTX_FAIL_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean FIRST_REPLY_LOGGED = new AtomicBoolean();

    /** 应答计数（只用于"前几条打日志"）。 */
    private static final AtomicInteger REPLIES = new AtomicInteger();

    /** ★ 推送计数（每条推送都会 Diag.ev，这个计数只进日志、不做门控）。 */
    private static final AtomicInteger PUSHES = new AtomicInteger();

    /** ★ onScroll 推送节流的上一次推送时刻（elapsedRealtime）。 */
    private static final AtomicLong sLastScrollPush = new AtomicLong(0L);

    /** ★ 兜底推送线程（懒启动，注册成功后才有；进程死 ⇒ 线程死）。 */
    private static volatile HandlerThread sBeatThread;
    private static volatile Handler sBeatHandler;
    private static volatile boolean sBeatStarted;

    private static volatile Context sApp;
    private static volatile String sSource = "";
    private static volatile XposedModule sMod;

    /**
     * ★ 修复 #2（2026-10-04）：<b>"接收器已注册"的独立闸门</b>。
     *
     * <p>原来两条懒注册入口（{@link #hitEarly}/{@link #noteView}）都以
     * {@code sApp != null} 当"已注册"，而 {@link #adopt} 是<b>先写 {@code sApp} 再 register</b>：
     * 一旦 register 抛异常（{@link #REGISTERED} 被复位），{@code sApp} 已经非 null
     * ⇒ 两条入口此后全部在第一行 return ⇒ <b>永久不再重试注册</b>。
     *
     * <p>语义拆分（本节的核心）：
     * <ul>
     *   <li>{@code sApp != null} ⇒ 只表示"<b>拿到了 ApplicationContext</b>"（推送/广播要用）；</li>
     *   <li>{@link #sRegistered} ⇒ 只有 {@code registerReceiver} <b>真的成功</b>才置 true；
     *       失败保持 false ⇒ 懒入口继续重试。</li>
     * </ul>
     */
    private static volatile boolean sRegistered;

    /**
     * ★ 修复 #3：<b>模块 App 的 uid</b>（注册时解析一次并缓存；{@code -1} = 没解析出来）。
     * 用途：校验导出接收器（{@code RECEIVER_EXPORTED}）的发送方，见 {@link #senderAllowed}。
     */
    private static volatile int sModuleUid = -1;

    /** ★ 修复 #3：被拒绝的广播计数（只用于"前几条打日志"，不刷屏）。 */
    private static final AtomicInteger REJECTS = new AtomicInteger();
    private static final int REJECT_LOG_MAX = 5;

    private Bridge() {
    }

    // ---------------------------------------------------------------- ★ 给日志推送用的只读通道

    /**
     * ★ 2026-10-04 日志推送：把本进程可用的 {@link Context} 借给 {@link LogPush}
     * （发广播用）。<b>只读、可能为 null</b>（桥还没注册）——调用方必须自己兜住。
     *
     * <p>不新增任何状态：拿到的就是 {@link #adopt} 早就存下来的那个 Application Context。
     */
    static Context appContext() {
        return sApp;
    }

    /** ★ 后台线程（心跳线程）的 Handler；没启动时返回 null（调用方自己判断）。 */
    static Handler bgHandler() {
        return sBeatHandler;
    }

    /**
     * 装桥。**只在负一屏进程调用**（由 {@link Entry#onPackageReady} / {@link Entry#startBridge} 把关）。
     * 任何失败都只记日志，绝不抛。
     *
     * @param mod 模块实例（挂钩子用）；为 null 时跳过挂钩、只记日志
     * @param cl  宿主包 ClassLoader
     */
    static void install(XposedModule mod, ClassLoader cl) {
        if (mod == null) {
            Diag.ev("桥：❌ install 收到空的模块实例 ⇒ 无法挂钩（这是 Entry 侧的错误）");
            return;
        }
        sMod = mod;
        Diag.key("桥：开始安装（包=" + HOST_PKG
                + " uid=" + android.os.Process.myUid()
                + " pid=" + android.os.Process.myPid()
                + " CL=" + (cl != null ? cl.getClass().getName() : "null") + "）");

        // ★★ 0. 第一件事：挂"早期 Context"钩子（本轮唯一变量）★★
        //   必须在"取 Context"之前做——因为下面 ①/② 都是"事后补救"，而 PING 可能在补救之前就到。
        installEarlyHooks(mod, cl);

        // ① 备用路径：ActivityThread.currentApplication()（@hide 反射；拿不到很正常，只记一行）
        try {
            Context c = Reflect.currentApplication();
            if (c != null) {
                Diag.key("桥：备用路径 currentApplication() 可用 包=" + safePkg(c));
                adopt(c, "ActivityThread.currentApplication");
            } else {
                // ★ 不再用一句"正常"盖过去：把"为什么 null"的整条链打出来，
                //   这样"隐藏 API 被挡"与"真的还没建好 Application"能一眼分开（不许猜）。
                Diag.key("桥：备用路径 currentApplication() 返回 null（诊断链：" + diagCurrentApp() + "）"
                        + " ⇒ 若 invoke=null，则此刻 handleBindApplication 还没给 mInitialApplication 赋值"
                        + "（code_off 1349），即仍在 Application#onCreate（code_off 1492）之前 ⇒ 早期钩子会命中");
            }
        } catch (Throwable t) {
            Diag.ev("桥：备用路径 currentApplication() 异常（已吞）: " + t.getClass().getName()
                    + ": " + t.getMessage());
        }

        // ② 主路径：探针③那四个入口的 thisObject 就是 View，但**不在本类挂钩**——
        //    由 ProbeAssist 在既有回调里调用 Bridge.noteView(chain.getThisObject())（避免重复挂钩）。
        //    本类只负责：把"挂钩失败"这件事也留一条证据。
        if (!HOOKED.get()) {
            boolean a = hookAssist(mod, cl, "onEnter");
            boolean b = hookAssist(mod, cl, "onLeave");
            boolean c = hookAssist(mod, cl, "onScroll");
            if (a || b || c) {
                HOOKED.set(true);
            } else if (HOOK_MISS_LOGGED.compareAndSet(false, true)) {
                Diag.ev("桥：❌ 三个 View 入口（onEnter/onLeave/onScroll）一个都没挂上"
                        + " ⇒ 只能靠 ① 备用路径；若 ① 也不可用 ⇒ Context 证据不足，App 侧会判红");
            }
        }
    }

    // ------------------------------------------------------------------ ★ 早期注册（本轮唯一变量）

    /**
     * 挂三个"进程启动必然经过"的早期点，拿到 Context/Application 就<b>立刻</b>注册接收器。
     *
     * <p><b>符号不是猜的</b>：
     * <pre>
     * $ javap -classpath .scan/androidlib/android.jar android.app.Application | grep -E 'onCreate|attach'
     *   public void onCreate();
     * $ javap -classpath .scan/androidlib/android.jar android.app.Instrumentation | grep callApplicationOnCreate
     *   public void callApplicationOnCreate(android.app.Application);
     * $ javap -classpath .scan/androidlib/android.jar android.content.ContextWrapper | grep attachBaseContext
     *   protected void attachBaseContext(android.content.Context);   ← 在 ContextWrapper，**不在 Application**
     * 设备真身（.scan/framework/framework.jar 反汇编）：
     *   Landroid/app/Application;      onCreate()V                     code_off=2161860
     *   Landroid/app/Application;      attach(Landroid/content/Context;)V code_off=2160316   ← 本 ROM 真实存在
     *   Landroid/app/Application;      attachBaseContext(…)            ← **没有**这条（继承自 ContextWrapper）
     *   Landroid/app/Instrumentation;  callApplicationOnCreate(Landroid/app/Application;)V code_off=2625936
     * 调用点唯一性（整份 framework 反汇编里 grep）：
     *   invoke-.*Application;.onCreate(   → 只在 ActivityThread.initializeSystemThread(system_server) 与
     *                                        Instrumentation.callApplicationOnCreate（app 进程走这条）
     *   invoke-.*Application;.attach(     → 只在 Instrumentation.newApplication(…) 的两条重载里
     * </pre>
     *
     * <p>三个钩子都<b>只读</b>：拿一个 Context 去注册一次接收器（幂等），其余原样
     * {@code chain.proceed()}，不改宿主任何状态。
     */
    static void installEarlyHooks(XposedModule mod, ClassLoader cl) {
        if (mod == null || EARLY_HOOKED.get()) {
            return;
        }
        // ① 首选：Application#onCreate()（this = Application；proceed 前注册 ⇒ 越早越好）
        boolean a = hookEarly(mod, Reflect.findMethod(Application.class, "onCreate", 0, null),
                SRC_APP_ONCREATE, -1, true);
        // ② 更早：Application#attach(Context)（this = Application；proceed 后再注册 ⇒ 此时 attachBaseContext 已跑）
        boolean b = hookEarly(mod, Reflect.findMethod(Application.class, "attach", 1, Context.class),
                SRC_APP_ATTACH, -1, false);
        // ③ 兜底：Instrumentation#callApplicationOnCreate(Application)（参数 = Application）
        boolean c = hookEarly(mod,
                Reflect.findMethod(Instrumentation.class, "callApplicationOnCreate", 1, Application.class),
                SRC_INSTR_ONCREATE, 0, true);
        Diag.key("桥：早期 Context 钩子安装结果：Application#onCreate=" + ok(a)
                + " Application#attach=" + ok(b)
                + " Instrumentation#callApplicationOnCreate=" + ok(c)
                + "（任一命中即立刻注册；注册幂等；全不命中则回落到原有 View.getContext 路径）");
        if (a || b || c) {
            EARLY_HOOKED.set(true);
        }
    }

    /**
     * 挂一个早期钩子。
     *
     * @param m        目标方法（{@link Reflect#findMethod} 找到的；null 表示本 ROM 没这个签名）
     * @param source   来源标签（写进 {@code 来源=} 与"早期注册成功"日志）
     * @param argIndex {@code <0} 取 {@code this}；否则取 {@code args[argIndex]}
     * @param before   {@code true} = 在 {@code chain.proceed()} 之前取 Context（更早）
     */
    private static boolean hookEarly(XposedModule mod, Method m, final String source,
                                     final int argIndex, final boolean before) {
        if (m == null) {
            Diag.key("桥：早期 Context 钩子未找到 " + source + "（本 ROM 无此签名）⇒ 跳过这一条，其余照挂");
            return false;
        }
        try {
            mod.hook(m).setId("animv4-bridge-early-" + source.replace('#', '-')).intercept(
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (before) {
                                hitEarly(chain, source, argIndex);
                                return chain.proceed();
                            }
                            Object r = chain.proceed();
                            hitEarly(chain, source, argIndex);
                            return r;
                        }
                    });
            Diag.key("桥：早期 Context 钩子已挂（只读） " + Reflect.describe(m)
                    + " 取值=" + (argIndex < 0 ? "this" : ("args[" + argIndex + "]"))
                    + " 时机=" + (before ? "proceed前" : "proceed后"));
            return true;
        } catch (Throwable t) {
            Diag.ev("桥：早期 Context 钩子 " + source + " 挂载失败（已吞，不影响宿主）: "
                    + t.getClass().getName() + ": " + t.getMessage());
            return false;
        }
    }

    /** 早期点命中：取出 Context/Application 并立刻（幂等地）注册。 */
    private static void hitEarly(XposedInterface.Chain chain, String source, int argIndex) {
        if (sRegistered) {
            return;                                      // ★ 修复 #2：只有"真的注册成功"才跳过重试
        }
        try {
            Object o = argIndex < 0 ? chain.getThisObject() : chain.getArgs().get(argIndex);
            if (!(o instanceof Context)) {
                Diag.key("桥：早期钩子 " + source + " 拿到的不是 Context（"
                        + (o == null ? "null" : o.getClass().getName()) + "）⇒ 跳过（不影响宿主）");
                return;
            }
            if (EARLY_HIT_LOGGED.compareAndSet(false, true)) {
                Diag.key("桥：early 命中（来源=" + source + " 对象=" + o.getClass().getName()
                        + "）⇒ 立刻注册接收器（注册幂等，谁先到用谁）");
            }
            adopt((Context) o, source);
        } catch (Throwable t) {
            Diag.ev("桥：早期钩子 " + source + " 取 Context 异常（已吞）: "
                    + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /** 这个来源是不是"早期注册"（用于打任务书要求的那行日志）。 */
    private static boolean isEarlySource(String s) {
        return SRC_APP_ONCREATE.equals(s) || SRC_APP_ATTACH.equals(s) || SRC_INSTR_ONCREATE.equals(s);
    }

    /** 只用于日志的 ✅/❌。 */
    private static String ok(boolean b) {
        return b ? "已挂" : "❌未挂";
    }

    /**
     * ★ "不许猜"落地：把 {@code ActivityThread.currentApplication()} 为什么返回 null 的一整条链查清。
     *
     * <p>{@link Reflect#currentApplication()} 会把所有异常吞掉统一返回 null，所以光看它<b>分不清</b>
     * "隐藏 API 被挡"和"Application 真的还没建好"。本方法逐步复现并回报每一步的结果：
     * {@code forName=OK getDeclaredMethod=OK setAccessible=OK invoke=null}
     * ⇒ 就是"真的还没建好"（⟹ 仍在 {@code Application#onCreate} 之前）。
     */
    private static String diagCurrentApp() {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            sb.append("forName=OK");
            Method m;
            try {
                m = at.getDeclaredMethod("currentApplication");
            } catch (Throwable t) {
                return sb.append(" getDeclaredMethod=❌").append(t.getClass().getSimpleName()).toString();
            }
            sb.append(" getDeclaredMethod=OK");
            try {
                m.setAccessible(true);
            } catch (Throwable t) {
                // 这一支才是"隐藏 API 被挡"
                return sb.append(" setAccessible=❌").append(t.getClass().getSimpleName())
                        .append("(hidden-API 限制)").toString();
            }
            sb.append(" setAccessible=OK");
            try {
                Object v = m.invoke(null);
                sb.append(" invoke=").append(v == null ? "null" : v.getClass().getName());
            } catch (Throwable t) {
                sb.append(" invoke=❌").append(t.getClass().getSimpleName());
            }
            return sb.toString();
        } catch (Throwable t) {
            return sb.append("forName=❌").append(t.getClass().getSimpleName()).toString();
        }
    }

    /**
     * ★ 原因 A 的核心：**从 View 拿 Context**（公开 API，无权限、无反射）。
     *
     * <p>由 {@link ProbeAssist} 在它**既有**的只读回调里调用（任务书允许"在其中读
     * {@code thisObject.getContext()} 做懒注册"，不许改探针语义）。本方法不读宿主状态，
     * 只做一件事：第一次拿到 Context 时把 PING 接收器注册上。
     *
     * @param self {@code chain.getThisObject()}；只有是 {@link View} 时才有意义
     */
    static void noteView(Object self) {
        if (sRegistered) {
            return;                                      // ★ 修复 #2：注册成功才走"零成本返回"
        }
        try {
            if (!(self instanceof View)) {
                return;
            }
            Context c = ((View) self).getContext();
            if (c == null) {
                if (CTX_FAIL_LOGGED.compareAndSet(false, true)) {
                    Diag.ev("桥：❌ View.getContext() 返回 null（不该发生）");
                }
                return;
            }
            adopt(c, "View.getContext");
        } catch (Throwable t) {
            if (CTX_FAIL_LOGGED.compareAndSet(false, true)) {
                Diag.ev("桥：❌ View.getContext() 异常（已吞）: " + t.getClass().getName()
                        + ": " + t.getMessage());
            }
        }
    }

    /**
     * 从被 hook 的 {@code AssistContentView} 自己的三个 View 入口取 Context。
     *
     * <p>只读；{@code chain.proceed()} 照常执行（探针语义不变）。返回是否挂上。
     */
    private static boolean hookAssist(XposedModule mod, ClassLoader cl, String name) {
        try {
            Class<?> cls = Class.forName(CLS_ASSIST, false, cl);
            Method m = Reflect.findMethod(cls, name, -1, null);
            if (m == null) {
                Diag.key("桥：未找到 " + name + "（本 ROM 签名不同）");
                return false;
            }
            mod.hook(m).setId("animv4-bridge-" + name).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        noteView(chain.getThisObject());
                        // ★ 2026-10-03 推送优先：每次负一屏交互顺带推一条 PONG（onScroll 已节流）。
                        //   只发广播，不改本探针任何观测语义、不读不写宿主状态。
                        noteInteraction(name);
                    } catch (Throwable t) {
                        Diag.ev("桥：挂钩回调取 Context 异常（已吞）: " + t.getClass().getName()
                                + ": " + t.getMessage());
                    }
                    return chain.proceed();
                }
            });
            Diag.key("桥：主路径已挂（只读） " + Reflect.describe(m));
            return true;
        } catch (Throwable t) {
            Diag.ev("桥：挂钩 " + name + " 失败（已吞）: " + t.getClass().getName()
                    + ": " + t.getMessage());
            return false;
        }
    }

    /** 拿到一个 Context ⇒ 存下来并试着注册接收器（注册是幂等的）。 */
    private static void adopt(Context ctx, String source) {
        try {
            if (ctx == null) {
                return;
            }
            // ★ 修复 #2：getApplicationContext() 可能抛。原来一抛就整段跳过（连"拿到了 Context"
            //   都不记）；这里降级为用参数 Context 本身，**不留半初始化状态**：
            //   要么 sApp 记上并尝试注册，要么一个都不动。
            Context app = null;
            try {
                app = ctx.getApplicationContext();
            } catch (Throwable t) {
                Diag.ev("桥：getApplicationContext() 异常（降级用参数 Context）: "
                        + t.getClass().getName() + ": " + t.getMessage());
            }
            if (app == null) {
                app = ctx;
            }
            // sApp 只表示"拿到了 ApplicationContext"；注册是否成功看 sRegistered（见其注释）。
            if (sApp == null) {
                sApp = app;
            }
            register(app, source);
        } catch (Throwable t) {
            Diag.ev("桥：adopt 异常（已吞）: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /**
     * 动态注册 {@code PING} 接收器。
     *
     * <p><b>flag 先核实再写</b>（{@code javap -constants android.content.Context}）：
     * {@code RECEIVER_EXPORTED = 2} / {@code RECEIVER_NOT_EXPORTED = 4}。
     * 本接收器要收<b>另一个 App（另一个 uid）</b>发来的广播 ⇒ 必须 {@code EXPORTED}
     * （HMC {@code ProbeGuard.java:110} 同款）。用 NOT_EXPORTED 时广播会被系统直接丢弃，
     * App 侧永远收不到 PONG（这就是原因 B）。
     */
    private static void register(Context app, String source) {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        final int flag = Context.RECEIVER_EXPORTED;   // = 2（已 javap 核实）
        try {
            // ★ 修复 #3：注册时解析一次"模块 App 的 uid"并缓存，供 Receiver 校验发送方。
            resolveModuleUid(app);
            BroadcastReceiver rx = new Receiver();
            // ★ 2026-10-04：过滤器加上"级别设置"（App → 负一屏）。只是多一个 action，
            //   接收器本体、注册时机、幂等闸门、回包格式全部不变 ⇒ 对既有 PING 路径零影响。
            IntentFilter filter = new IntentFilter(ACTION_PING);
            filter.addAction(ACTION_LOGLEVEL);
            filter.addAction(RtCfg.ACTION_SETCFG);      // ★ 阶段3：运行期配置（App → 负一屏）
            String how;
            try {
                app.registerReceiver(rx, filter, flag);
                how = "registerReceiver(rx,filter," + flag + "=RECEIVER_EXPORTED)";
            } catch (Throwable t) {
                // API < 33 没有带 flag 的重载；旧系统也用不着 flag。降级一次，再失败就上报。
                Diag.key("桥：带 flag 的 registerReceiver 失败（降级重试）: "
                        + t.getClass().getName() + ": " + t.getMessage());
                IntentFilter f2 = new IntentFilter(ACTION_PING);
                f2.addAction(ACTION_LOGLEVEL);
                f2.addAction(RtCfg.ACTION_SETCFG);      // ★ 阶段3：降级路径同样支持
                app.registerReceiver(rx, f2);
                how = "registerReceiver(rx,filter)【降级，无 flag】";
            }
            // ★ 修复 #2：注册**真的成功**才置位；失败分支保持 false ⇒ 懒入口会继续重试。
            sRegistered = true;
            sSource = source;
            Diag.key("桥：✅ PING 接收器已注册"
                    + " 动作=" + ACTION_PING
                    + " 来源=" + source
                    + " flag=" + flag + "(RECEIVER_EXPORTED)"
                    + " 调用=" + how
                    + " ctx=" + app.getClass().getName()
                    + " 包=" + app.getPackageName()
                    + " uid=" + android.os.Process.myUid()
                    + " pid=" + android.os.Process.myPid());

            // ★★ 本轮唯一变量：早期注册成功 ⇒ 打任务书要求的那一行 ★★
            //   `来源=Application#onCreate` 出现在这一行 = "进程一启动就有接收器"，不再是"滑出负一屏才有"。
            if (isEarlySource(source)) {
                Diag.key("桥：✅ 早期注册成功（来源=" + source + "）进程=" + android.os.Process.myPid()
                        + " uid=" + android.os.Process.myUid()
                        + " ⇒ 此后 App 的 PING 随时打进来都有接收器（不必先滑出负一屏）");
            }

            // ★★ 2026-10-03 推送优先（主通道）★★
            //   接收器注册成功 = 第一次拿到可用 Context ⇒ 立刻推一条 PONG，并启动 5s 兜底心跳。
            //   为什么这是"主通道"：PING 是 App → 负一屏（接收方在**后台**，MIUI/系统可能拦掉）；
            //   PONG 是 负一屏 → App（接收方在**前台**，前台动态接收器不受后台广播限制）。
            startHeartbeat();
            Entry.dumpVsInfoOnce();   // ★ P109：心跳里 dump（每 5 秒必触发 ✓）
            Diag.key("桥：✅ 注册成功 ⇒ 立刻推送 PONG（推送优先主通道）");
            pushAsync("接收器注册成功");
        } catch (Throwable t) {
            REGISTERED.set(false);
            sRegistered = false;                          // ★ 修复 #2：明确保持"未注册"
            Diag.ev("桥：❌ 注册失败（已吞，App 侧会超时判红）: "
                    + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ ★ 修复 #3：导出接收器的发送方校验

    /**
     * 解析并缓存模块 App 的 uid（{@code getPackageManager().getApplicationInfo(MODULE_PKG,0).uid}）。
     *
     * <p>解析失败 ⇒ {@link #sModuleUid} 保持 {@code -1}（记一行），此后
     * {@link #senderAllowed} 会<b>拒绝 LEVEL、只放行 PING</b>（任务书要求的行为）。
     */
    private static void resolveModuleUid(Context app) {
        if (sModuleUid >= 0) {
            return;                                      // 已解析（宿主进程内只需一次）
        }
        try {
            int uid = app.getPackageManager().getApplicationInfo(MODULE_PKG, 0).uid;
            if (uid >= 0) {
                sModuleUid = uid;
                Diag.key("桥：模块 App uid 已解析 " + MODULE_PKG + "=" + uid
                        + "（导出接收器此后只接受这个 uid 的 PING/LEVEL）");
                return;
            }
        } catch (Throwable t) {
            Diag.key("桥：❌ 模块 App uid 解析失败（" + t.getClass().getName() + ": "
                    + t.getMessage() + "）⇒ 导出接收器将【拒绝 LEVEL、只放行 PING】");
        }
        sModuleUid = -1;
    }

    /**
     * ★ 修复 #3：导出接收器的发送方校验（PING 与 LEVEL <b>都校验</b>）。
     *
     * <p>接收器是 {@code RECEIVER_EXPORTED}，任何应用都能发 {@code ACTION_PING/ACTION_LOGLEVEL}；
     * 原来只把 uid/pkg 写进日志、<b>从不比较</b> ⇒ 任意应用都能改我们的日志级别（LEVEL 会落盘）。
     *
     * <p>规则：
     * <ul>
     *   <li>模块 uid 已知 ⇒ 发送方 uid 必须相等，否则一律丢弃；</li>
     *   <li>模块 uid 未知（解析失败）⇒ 拒绝 LEVEL（有落盘副作用），只放行 PING
     *       （固定回包、无副作用），并在日志里写明。</li>
     * </ul>
     */
    private static boolean senderAllowed(int fromUid, String action, String fromPkg) {
        final int modUid = sModuleUid;
        // ★ 修复（2026-10-03 真机日志）：Android 对**普通 App** 发出的广播经常把
        //   `getSentFromUid()` 报成 **-1**（真机实测：我们自己的 App 发的 PING/LEVEL 全是 uid=-1）。
        //   原实现"uid 不等就丢弃"会把**自己的 PING 全拒掉** ⇒ 拉取通道死掉、只剩 5 秒心跳能点亮
        //   ⇒ 卡片表现为"绿↔红"**闪现**（用户实测症状）。
        //   新规则：能拿到 uid 就按 uid 严格校验；uid 不可用则退化为**包名**校验；
        //   两者都不可用时只放行 PING（仅影响"显示是否激活"，无副作用），仍拒绝 LEVEL（会落盘改配置）。
        final boolean uidKnown = fromUid >= 0;
        final boolean uidOk = uidKnown && modUid >= 0 && fromUid == modUid;
        //   ★ 包名由调用方传入：`getSentFromPackage()` 是 BroadcastReceiver 的**实例**方法，
        //     只在本类的 Receiver.onReceive 内有效 ⇒ 不能用静态辅助方法取（曾因此编译失败）。
        final String pkg = fromPkg;
        final boolean pkgOk = pkg != null && MODULE_PKG.equals(pkg);
        if (uidOk || pkgOk) {
            return true;
        }
        if (!uidKnown || modUid < 0) {
            if (ACTION_LOGLEVEL.equals(action)) {
                if (REJECTS.incrementAndGet() <= REJECT_LOG_MAX) {
                    Diag.key("桥：❌ 拒绝级别设置：发送方无法校验（来源uid=" + fromUid
                            + " pkg=" + pkg + "）⇒ 只放行 PING");
                }
                return false;
            }
            return true;                                 // PING：只回包，无副作用
        }
        if (REJECTS.incrementAndGet() <= REJECT_LOG_MAX) {
            Diag.key("桥：❌ 丢弃非模块 App 的广播 action=" + action + " 来源uid=" + fromUid
                    + " pkg=" + pkg + "（期望 uid=" + modUid + " / pkg=" + MODULE_PKG + "）");
        }
        return false;
    }

    // ------------------------------------------------------------------ 推送优先（主通道）

    /**
     * ★ 2026-10-03 推送优先：每次负一屏交互（{@code onEnter/onLeave/onScroll}）推一条 PONG。
     *
     * <p>由 {@link #hookAssist} 的既有只读回调调用（**不改探针语义**：只多发一条广播）。
     * {@code onScroll} 是逐帧回调，按 {@link #SCROLL_PUSH_THROTTLE_MS} 节流；
     * {@code onEnter/onLeave} 是回合边界，每次都推。
     *
     * <p>只读性：不读不写宿主状态，只发广播。注册成功前（{@code sApp == null}）直接返回。
     */
    static void noteInteraction(String where) {
        if (sApp == null) {
            return;                                      // 还没拿到 Context ⇒ 无广播可发
        }
        if ("onScroll".equals(where)) {
            long now = SystemClock.elapsedRealtime();
            long prev = sLastScrollPush.get();
            if (now - prev < SCROLL_PUSH_THROTTLE_MS) {
                return;                                  // 节流：1000ms 内最多一条
            }
            if (!sLastScrollPush.compareAndSet(prev, now)) {
                return;                                  // 并发时让别的线程发，避免成对重复
            }
        } else {
            // ★ 2026-10-04：回合边界（onEnter/onLeave）顺带回读一次"App 期望的日志级别"。
            //   只在这个方向（宿主活的、被用户拉到前台）才问得通，见 ACTION_QUERY_LEVEL 注释。
            //   onScroll 不问（那是逐帧回调，节流了也不该在这里发有序广播）。
            queryLevel("交互:" + where);
        }
        pushAsync("交互:" + where);
    }

    // ------------------------------------------------------------------ ★ 级别回读（2026-10-04）

    /** 最近一次级别回读时刻（elapsedRealtime）；用来给 onEnter/onLeave 与心跳做节流。 */
    private static final AtomicLong sLastLevelQuery = new AtomicLong(0L);

    /** 两次级别回读之间至少隔这么久（防止快速来回滑动时连续发有序广播）。 */
    private static final long LEVEL_QUERY_THROTTLE_MS = 2000L;

    /**
     * ★ 问一次 App："你期望的日志级别是多少？"（宿主 → App，有序广播）。
     *
     * <p>为什么是这个方向（关键，不是随便选的）：{@link ACTION_LOGLEVEL} 是
     * App → 后台宿主，实测这条方向的跨应用广播会被系统/MIUI 拦掉（PING 判红的根因，见类注释）；
     * 而本方法在<b>宿主被用户拉到前台</b>（onEnter/onLeave）或心跳仍在跑时发出，
     * 接收方是我们的 App（进程通常还活着），回来的是 {@code setResultExtras} 里的
     * {@link #KEY_LEVEL} ⇒ 级别设置**一定**能落到位。
     *
     * <p>收不到回应（App 没起过 / 没注册接收器）时有序广播会<b>立刻带着空 extras 返回</b>
     * （HMC {@code ModuleBridge.kt:672-676} 的原话），这里只记一行、保持现级别，绝不影响宿主。
     */
    static void queryLevel(final String reason) {
        final Context ctx = sApp;
        final Handler h = sBeatHandler;
        if (ctx == null || h == null) {
            return;                                      // 还没注册成功 ⇒ 没有可发的通道
        }
        long now = SystemClock.elapsedRealtime();
        long prev = sLastLevelQuery.get();
        if (now - prev < LEVEL_QUERY_THROTTLE_MS) {
            return;                                      // 节流
        }
        if (!sLastLevelQuery.compareAndSet(prev, now)) {
            return;                                      // 并发时只发一条
        }
        try {
            BroadcastReceiver rx = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        Bundle ex = getResultExtras(false);
                        if (ex == null || !ex.containsKey(KEY_LEVEL)) {
                            Diag.key("桥：级别回读无回应（原因=" + reason
                                    + "）⇒ 保持当前级别=" + AnimCfg.loglevel
                                    + "(" + AnimCfg.levelDesc(AnimCfg.loglevel)
                                    + ")，可能是 App 没启动过（不影响宿主）");
                            return;
                        }
                        int want = ex.getInt(KEY_LEVEL, AnimCfg.loglevel);
                        int applied = AnimCfg.setLoglevelFromPush(want);
                        Diag.key("桥：✅ 级别回读成功（原因=" + reason + "）App 期望=" + want
                                + " ⇒ 生效=" + applied + "(" + AnimCfg.levelDesc(applied) + ")"
                                + " 动作=" + ACTION_QUERY_LEVEL);
                        push("级别回读:" + applied);     // 顺手推一条 PONG：让 App 卡片保持新鲜
                    } catch (Throwable t) {
                        Diag.key("桥：级别回读处理异常（已吞，不影响宿主）: "
                                + t.getClass().getName() + ": " + t.getMessage());
                    }
                }
            };
            ctx.sendOrderedBroadcast(new Intent(ACTION_QUERY_LEVEL)
                            .setPackage(MODULE_PKG)                       // 只问模块 App
                            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                    null, rx, h, 0, null, null);
        } catch (Throwable t) {
            Diag.key("桥：级别回读发送失败（已吞，不影响宿主）: "
                    + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /** 把推送排到兜底线程上执行（**不占宿主主线程**；onScroll 是逐帧回调，必须便宜）。 */
    private static void pushAsync(final String reason) {
        Handler h = sBeatHandler;
        if (h == null) {
            return;                                      // 还没启动心跳线程 ⇒ 还没注册成功
        }
        try {
            h.post(new Runnable() {
                @Override
                public void run() {
                    push(reason);
                }
            });
        } catch (Throwable t) {
            Diag.ev("桥：推送排程失败（已吞，原因=" + reason + "）: "
                    + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /**
     * 发出**一条** PONG 广播（推送主通道的唯一出口）。
     *
     * <p>字段与收到 PING 时的应答完全一致（{@code alive/pid/ts/reason}），
     * 所以 App 侧一个接收器就能同时收推送与应答。
     * 任何异常只记日志，绝不抛给宿主。
     */
    private static void push(String reason) {
        Context ctx = sApp;
        if (ctx == null) {
            return;
        }
        final long ts = System.currentTimeMillis();
        final int pid = android.os.Process.myPid();
        try {
            Bundle b = new Bundle();
            b.putBoolean(KEY_ALIVE, true);
            b.putInt(KEY_PID, pid);
            b.putLong(KEY_TS, ts);
            b.putString(KEY_REASON, reason);
            ctx.sendBroadcast(new Intent(ACTION_PONG)
                    .setPackage(MODULE_PKG)                       // 显式投给模块 App
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)    // App 在前台 ⇒ 立即投递
                    .putExtras(b));
            int n = PUSHES.incrementAndGet();
            // ★ 每一次推送都留证据（原因 + 序号 + pid/ts + 动作名），App 侧对应 TAG_ALIVE
            Entry.dumpVsInfoOnce();   // ★ P111：挂在【实证每心跳必打】的那行前 ✓
            Diag.ev("桥：推送 PONG（原因=" + reason + "）⇒ 已发往 " + MODULE_PKG + " VER=" + AppVer.V
                    + " #" + n
                    + " alive=true pid=" + pid
                    + " ts=" + ts
                    + " 动作=" + ACTION_PONG
                    + " Context来源=" + sSource);
        } catch (Throwable t) {
            Diag.ev("桥：推送 PONG 失败（已吞，原因=" + reason + "）: "
                    + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /**
     * ★ 低频兜底推送：每 {@link #HEARTBEAT_MS}（5 秒）一条，独立线程。
     * <b>★ LOW#4：可用 {@code animv3.conf} 的 {@code hb=off} 关掉</b> ——
     * 关闭时仍会建好线程与 {@link Handler}（PING 应答、交互推送、级别回读都用它），
     * 但<b>不排 5 秒的自我续期</b>，因此不再有任何周期定时器。
     *
     * <p>"只在负一屏进程存活时"是结构性的：定时器是本进程的一个线程，
     * 进程被杀 ⇒ 线程消失 ⇒ 推送自然停止（App 侧随后按 10s 过期判红）。
     * 推送本身零文件 I/O、零视图遍历；是否落盘由 {@code animv3.conf} 的 {@code write} 决定。
     */
    private static void startHeartbeat() {
        if (sBeatStarted) {
            return;
        }
        sBeatStarted = true;
        try {
            HandlerThread t = new HandlerThread("animv4-bridge-beat");
            t.start();
            sBeatThread = t;
            final Handler h = new Handler(t.getLooper());
            sBeatHandler = h;

            // ★ LOW#4：hb=off ⇒ 只保留线程/Handler（推送与级别回读的通道），不排心跳。
            if (!AnimCfg.hb) {
                Diag.key("桥：心跳已按配置关闭（hb=off）⇒ 不起 5s 定时器；"
                        + "PING 应答与 onEnter/onLeave 交互推送照旧");
                return;
            }

            Diag.key("桥：兜底推送已启动（每 " + HEARTBEAT_MS + "ms 一条 PONG，独立线程，不落盘自身状态、不遍历）");
            h.postDelayed(new Runnable() {
                /** ★ 2026-10-04：每 3 次心跳（≈15 秒）回读一次 App 期望的日志级别。 */
                private int beats;

                @Override
                public void run() {
                    try {
                        push("心跳兜底");
                    } catch (Throwable ignored) {
                    }
                    try {
                        beats++;
                        if (beats % 3 == 0) {
                            queryLevel("心跳兜底");
                        }
                    } catch (Throwable ignored) {
                    }
                    if (sBeatHandler != null) {
                        h.postDelayed(this, HEARTBEAT_MS);
                    }
                }
            }, HEARTBEAT_MS);
        } catch (Throwable t) {
            sBeatStarted = false;
            Diag.ev("桥：兜底推送启动失败（已吞，不影响 PING 应答）: "
                    + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /** 收到 PING ⇒ 立刻回一条 PONG（① result extras + ② 显式广播直投模块 App）。 */
    private static final class Receiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context c, Intent i) {
            final int fromUid = safeUid();
            try {
                final String action = i != null ? i.getAction() : null;
                // ★ 修复 #3：导出接收器先校验发送方 uid（PING 与 LEVEL 都校验）。
                //   不是模块 App 的 uid 直接 return；模块 uid 解析失败时拒绝 LEVEL、放行 PING。
                if (!senderAllowed(fromUid, action, safeFromPkg())) {
                    return;
                }
                // ★ 2026-10-04：同一个接收器现在收两个 action（PING / LEVEL），先分流。
                //   导出型接收器必须显式校验 action（安全要求），未知 action 直接忽略。
                String reason = "PING应答";
                if (ACTION_LOGLEVEL.equals(action)) {
                    int want = i.getIntExtra(KEY_LEVEL, AnimCfg.loglevel);
                    int applied = AnimCfg.setLoglevelFromPush(want);
                    reason = "级别设置:" + applied;
                    // 级别行本身算"关键行"：级别 0 时它也不会被推给 App，但会照常进 P1 文件日志
                    Diag.key("桥：收到级别设置（App 推来）期望=" + want
                            + " ⇒ 生效=" + applied + "(" + AnimCfg.levelDesc(applied) + ")"
                            + " 来源uid=" + fromUid + " pkg=" + safeFromPkg()
                            + " 仅影响「推给 App」的内容，ANIMV4-P1.txt 落盘行为不变");
                } else if (RtCfg.ACTION_SETCFG.equals(action)) {
                    // ★ 阶段3：App 推来的运行期配置 —— 即时写进字段（下一次逻辑生效）+ 落盘 + 回带当前快照
                    final String ck = i.getStringExtra(RtCfg.KEY_CFG_KEY);
                    final String cv = i.getStringExtra(RtCfg.KEY_CFG_VAL);
                    final boolean ok = RtCfg.apply(ck, cv);
                    if (ok) {
                        RtCfg.persist();
                    }
                    reason = "配置:" + ck + "=" + cv + (ok ? "" : "(未知键)");
                    Diag.key("桥：收到配置设置 " + ck + "=" + cv + " ⇒ "
                            + (ok ? "已生效并落盘" : "未知键（已忽略）")
                            + " 来源uid=" + fromUid + " pkg=" + safeFromPkg()
                            + " | 当前=" + RtCfg.describe());
                } else if (!ACTION_PING.equals(action)) {
                    Diag.key("桥：收到未知 action（忽略，不影响宿主）: " + action);
                    return;
                }

                Bundle b = new Bundle();
                b.putBoolean(KEY_ALIVE, true);
                b.putInt(KEY_PID, android.os.Process.myPid());
                b.putLong(KEY_TS, System.currentTimeMillis());
                b.putString(KEY_REASON, reason);          // ★ 推送协议统一字段（第二条腿也带）
                b.putInt(KEY_LEVEL, AnimCfg.loglevel);    // ★ 顺带回带当前级别（App 侧可自证）

                // ① 有序广播的 result extras（HMC Main.java:2821 的做法）
                setResultExtras(b);

                // ② 显式 PONG 广播（同一份字段；给"adb 注入验证"和抗丢包用）
                String leg2 = "成功";
                try {
                    Context ctx = c != null ? c : sApp;
                    if (ctx != null) {
                        ctx.sendBroadcast(new Intent(ACTION_PONG)
                                .setPackage(MODULE_PKG)
                                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)   // ★ 与推送主通道同款
                                .putExtras(b));
                    } else {
                        leg2 = "跳过（ctx=null）";
                    }
                } catch (Throwable t) {
                    leg2 = "失败 " + t.getClass().getName() + ": " + t.getMessage();
                }

                int n = REPLIES.incrementAndGet();
                // ★ 每一次 PING 都记一行（探活节奏由 App 侧发起，正常也就 5 秒一条，不会刷屏）
                Diag.key("桥：收到 PING（来自 uid=" + fromUid
                        + " pkg=" + safeFromPkg() + "）⇒ 已回 PONG"
                        + " #" + n
                        + " alive=true pid=" + android.os.Process.myPid()
                        + " ts=" + b.getLong(KEY_TS)
                        + " 直投广播=" + leg2
                        + " Context来源=" + sSource);
                if (n == 1 && FIRST_REPLY_LOGGED.compareAndSet(false, true)) {
                    Diag.key("桥：首次应答完成（此后 App 卡片应为绿色）");
                }
            } catch (Throwable t) {
                Diag.ev("桥：回包异常（已吞）: " + t.getClass().getName() + ": " + t.getMessage());
            }
        }

        /** 只读诊断：谁发的（API 34+ 才有；取不到返回 -1）。 */
        private int safeUid() {
            try {
                return getSentFromUid();
            } catch (Throwable t) {
                return -1;
            }
        }

        private String safeFromPkg() {
            try {
                return String.valueOf(getSentFromPackage());
            } catch (Throwable t) {
                return "-";
            }
        }
    }

    private static String safePkg(Context c) {
        try {
            return String.valueOf(c.getPackageName());
        } catch (Throwable t) {
            return "-";
        }
    }
}
