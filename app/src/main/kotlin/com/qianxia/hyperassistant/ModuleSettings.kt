package com.qianxia.hyperassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianxia.hyperassistant.liquid.IosLiquidGlassNavigationBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块设置界面。
 *
 * 底栏玻璃来自 **HyperNavBar / HyperMusicCover** 的 liquid 组件（per-file Apache-2.0），
 * 见 `com.qianxia.hyperassistant.liquid`，头部许可声明原样保留。
 *
 * ★ 页面切换动画照搬 HyperMusicCover：`HorizontalPager` + `PagerNavigationSpringSpec` + `goToPage`。
 * ★ "高斯模糊"开关只控制 `blurEnabled`（只去掉 blur()），玻璃本体（折射/高光/阴影）仍在。
 * ★ 被采样层在"高斯模糊关闭"时不再录（HyperMusicCover MainActivity.kt:352-355 的原话：
 *   "Recording a layer is a second render of everything inside it"）。
 */
private class Cfg(ctx: Context) {
    private val f = File(ctx.filesDir, "v4.conf")
    private val map = HashMap<String, String>()

    init {
        runCatching {
            if (f.exists()) {
                f.readLines().forEach { l ->
                    l.split('=', limit = 2).takeIf { it.size == 2 }?.let { map[it[0]] = it[1] }
                }
            }
        }
    }

    fun bool(k: String, d: Boolean) = map[k]?.toBooleanStrictOrNull() ?: d
    fun float(k: String, d: Float) = map[k]?.toFloatOrNull() ?: d

    /** ★ 2026-10-04 日志级别（0/1/2）用；读不到就用默认值。 */
    fun int(k: String, d: Int) = map[k]?.trim()?.toIntOrNull() ?: d
    fun set(k: String, v: Any) {
        map[k] = v.toString()
        runCatching { f.writeText(map.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
    }
}

// ★ 与 app/build.gradle.kts 的 versionName 保持一致（不用 BuildConfig：会触发 android-37 平台包的 JdkImageTransform 报错）
// ★ 2026-10-04 修卡片配色 + 转场位移这一版把卡片上的版本号也推一格：
//   r0.3-P1 的 APK 装到手机上后卡片显示的还是 r0.2（对不上，无法判断"装的到底是哪一版"）。
//   现在卡片版本号 = 本轮的交付标识（gradle 的 versionName **一行未动**——那是禁改文件）。
// ★ P3（非玻璃底栏"通栏 + 恒不透明"）再抬一格，方便一眼确认装的是本版。
// ★ P4（P9 审计修复：配置先于装钩、桥重试、接收器 uid 校验、逐帧限流、落盘上限…）再抬一格。
//   本行是**唯一**改动：只改版本号常量，UI/逻辑一行未动（build.gradle.kts 是禁改文件）。
private const val APP_VERSION = "4.0.0-alpha7"

// ------------------------------------------------------------------ 真实激活状态（请求-应答）

/** 请求动作名 —— **必须与 hook 侧 `Bridge.ACTION_PING` 完全一致**。 */
private const val ACTION_PING = "com.qianxia.HyperAssistant.PING"

/**
 * PING 的目标包：**必须是"接收器所在的那个包"（负一屏），不是本 App**。
 *
 * 照 HyperMusicCover `ModuleBridge.kt:34-36,232-236`：`TARGET = "com.android.systemui"` 且
 * `Intent(ACTION).setPackage(TARGET)` —— 它把请求显式发给 **hook 所在的宿主进程**，
 * 回包走有序广播的 result extras（`ModuleBridge.kt:677-712`）。
 * 我们的宿主是负一屏；这里优先读本模块自带的 `R.array.xposedscope`（与 `scope.list` 同一份，
 * 见 `res/values/arrays.xml`），读不到才退回常量。
 */
private const val TARGET_PKG = "com.miui.personalassistant"

/** 应答里表示"活着"的字段名 —— 与 hook 侧 `Bridge.KEY_ALIVE` 同一字面量（HMC `Main.java:2741`）。 */
private const val KEY_ALIVE = "alive"

/**
 * 应答动作名（**第二条腿**）。
 *
 * hook 侧收到 PING 后做两件事：① 把 `alive/pid/ts` 写进有序广播的 result extras（HMC 原样，
 * `ModuleBridge.kt:686-712` 读的就是它）；② 再显式回一条本动作的 `PONG` 广播。
 * 本 App 两条都收，**任一条到达即判绿**；② 同时给了 adb 注入验证的入口
 * （`adb shell am broadcast -a com.qianxia.hyperassistant.PONG -p com.qianxia.hyperassistant --ei pid 1`）。
 */
private const val ACTION_PONG = "com.qianxia.HyperAssistant.PONG"

/**
 * 一次 PING→PONG 的等待上限。
 *
 * HMC 用的是 `QUERY_TIMEOUT_MS = 1500L`（`ModuleBridge.kt:36`）。本任务要求落在 300–1000ms，
 * 取 **800ms**：同一量级、给"宿主刚起来/主线程正忙"留余量，同时把首次判红压进 1 秒。
 *
 * ★ 关键（也是"快"的另一半）：**没有接收器时，有序广播会立刻带着空 extras 回来**
 * （HMC `ModuleBridge.kt:672-676` 原话："It comes back even when no receiver is registered -
 * with the 0 it was sent with and no extras - so null here means only that the timeout ran out
 * first."）⇒ 模块没注入时**不必等满超时**，毫秒级就能判红。
 */
private const val PING_TIMEOUT_MS = 800L

/**
 * ★ 2026-10-03 推送优先：PING 超时后**不立刻判红**，再等 [PUSH_GRACE_MS] 毫秒看有没有推送。
 *
 * 依据（真机 `ANIMV4-P1.txt` 281 行）：hook 侧接收器已注册、三个 View 入口全挂上，
 * 但整份日志里**没有一行"桥：收到 PING"** ⇒ App → 负一屏 的广播**投递失败**
 * （负一屏在后台，MIUI/系统拦了）⇒ 只靠拉取必然永远判红。
 * 现在：hook 侧主动把 PONG **推**给前台的 App（前台动态接收器不受后台广播限制），
 * 所以 PING 超时只说明"拉取失败"，还不能说明"模块不在"。
 */
private const val PUSH_GRACE_MS = 2_000L

/** 已绿时的复检间隔：让"最后一次应答"保持新鲜，[EXPIRY_MS] 才有意义。 */
private const val ACTIVE_POLL_MS = 5_000L

/** 过期阈值：已绿、但这么久没再收到任何应答 ⇒ 自动变红（模块被关 / 负一屏被杀）。 */
private const val EXPIRY_MS = 10_000L

/** 未绿时的重试间隔阶梯 —— 照抄 HMC `ModuleBridge.kt:518` 的 `RETRY_GAPS_MS`。 */
private val RETRY_GAPS_MS = longArrayOf(1000L, 2000L, 4000L, 8000L)

/**
 * 阶梯走完之后的复检间隔。HMC 到这个点就"安顿"在未安装状态（`ModuleBridge.kt:492-497`），
 * 我们的界面**没有**"重试"按钮 ⇒ 留一条低频自愈路径（30 秒一次，只在卡片可见时）。
 */
private const val SETTLED_RECHECK_MS = 30_000L

/** App 侧诊断日志 tag（hook 侧是 `AnimV3Probe`，两侧分开 grep）。 */
private const val TAG_ALIVE = "AnimV4Alive"

/**
 * ★ 2026-10-03 卡片文案精简（用户要求："只保留状态标题 / 版本号 / 模块名"）。
 *
 * 原来这里有六条面向**卡片**的提示文案常量（`HINT_INACTIVE` / `HINT_INACTIVE_NO_RX` /
 * `HINT_DETECTING` / `HINT_WAIT_PUSH` / `HINT_ACTIVE` / `HINT_TAP_RETRY`），
 * 连同卡片上那行"最后一次探测"诊断文案，**已全部删除**（逐字清单见
 * 项目文档）。
 * 探活判定逻辑一行未动；"为什么判红"的诊断信息仍照常写 logcat（TAG = [TAG_ALIVE]）。
 */

/**
 * ★ 2026-10-03 诊断（**只进 logcat，不上卡片**）：两种"没有 alive 回包"的原因。
 *
 * 仅用于拼 [AliveDetector] 的 `lastDetail` 文本，最终出现在 `Log.i(TAG_ALIVE, …)` 里。
 */
private const val ALIVE_DETAIL_NO_REPLY = "已发出探测（目标：负一屏）· 无应答"
private const val ALIVE_DETAIL_SEND_FAIL = "探测未发出（发送即失败："

/**
 * 四种状态：
 * - `DETECTING`   刚进 App，第一轮 PING 还在飞（尚未判定）；
 * - `WAITING_PUSH`★ PING 已超时（拉取失败），但还在 [PUSH_GRACE_MS] 宽限期内等推送 —— **不是红**；
 * - `ACTIVE`      收到过 PONG（推送或 PING 应答）且未过期 ⇒ 绿；
 * - `INACTIVE`    宽限期内也无推送，或绿了以后 [EXPIRY_MS] 无任何 PONG/PING 应答 ⇒ 红。
 */
private enum class AliveState { DETECTING, WAITING_PUSH, ACTIVE, INACTIVE }

/**
 * 一次探测的结果（**两条腿共用**：PING 的应答 / hook 侧主动推来的 PONG）。
 *
 * @param alive     回包/推送里 `alive=true`
 * @param pid       回包里的宿主 pid（没有就是 -1）
 * @param sentOk    PING **是否真的发出去了**（`sendOrderedBroadcast` 没抛异常）
 * @param sendErr   发送失败时的原因（`类名: 消息`）；`sentOk=false` 才有值
 * @param rttMs     从发到回（或到超时）的毫秒数
 * @param reason    来源标签（诊断用）：`有序广播` / `PING应答` / `接收器注册成功` / `交互:onEnter` / `心跳兜底` …
 */
private class Pong(
    val alive: Boolean,
    val pid: Int,
    val sentOk: Boolean = true,
    val sendErr: String = "",
    val rttMs: Long = -1L,
    val reason: String = "",
)

/**
 * 探活器：**推送优先** —— App 主动 PING 只是快速路径，判定主要依据 hook 侧**推**来的 PONG。
 *
 * <h3>为什么（不是猜，有真机日志）</h3>
 * `ANIMV4-P1.txt`（281 行，负一屏 pid=18196）证明 hook 侧**活着**：
 * `✅ PING 接收器已注册 flag=2(RECEIVER_EXPORTED)`、`onEnter/onLeave/onScroll` 三个 View 入口全挂上；
 * 但整份日志里**一行"桥：收到 PING"都没有** ⇒ App → 负一屏（接收方在**后台**）的广播**投递失败**
 * （MIUI/系统拦了跨应用广播）。⇒ 原来的"只靠拉取、超时即红"在任何情况下都会**永远红**。
 * 反过来，PONG 的接收方是本 App（在**前台**），前台动态接收器不受后台广播限制。
 *
 * <h3>状态机（判定的唯一出口 = [markAlive] / [finishRed]）</h3>
 * <ol>
 *   <li>收到任意一条 PONG（推送 或 PING 应答）⇒ **绿**；</li>
 *   <li>发出 PING，[PING_TIMEOUT_MS]（800ms）无应答 ⇒ **不判红**，转 [AliveState.WAITING_PUSH]，
 *       再等 [PUSH_GRACE_MS]（2000ms）看有没有推送；宽限期内来推送 ⇒ 绿；</li>
 *   <li>800ms 无应答 **且** 2000ms 内也无推送 ⇒ 红（提示"模块未响应（PING 未送达且无推送）"）；</li>
 *   <li>已经绿了：每 [ACTIVE_POLL_MS]（5s）复检一次，只要 [EXPIRY_MS]（10s）内收到过任何
 *       PONG/PING 应答就保持绿；超过 ⇒ 红（模块被关 / 负一屏被杀）；</li>
 *   <li>红着的时候按 [RETRY_GAPS_MS] 加宽重试（HMC 同款），走完改成 [SETTLED_RECHECK_MS] 低频复检。</li>
 * </ol>
 *
 * <h3>诊断（用户没有 adb）</h3>
 * 每一轮的完整结果都：① 回给卡片（[onState] **只有一个参数**：状态 —— 卡片不再显示详情/提示文案）；
 * ② 写进 App 自己的 logcat（TAG = [TAG_ALIVE]，即现成的 `AnimV3Alive`）。
 */
private class AliveDetector(
    private val ctx: Context,
    private val target: String,
    private val onState: (AliveState) -> Unit,
) {
    /** 本探活器诞生时刻（= 进入本页那一刻），日志里的"距进入本页"以它为基准。 */
    private val startedAt = SystemClock.elapsedRealtime()

    /** 最后一次探测的墙钟时间（★ 只进 logcat 的 `详情=` 文本，卡片已不显示）；0 = 还没探测过。 */
    @Volatile
    private var lastProbeWall = 0L

    /** 最后一轮的判定详情（★ 只进 logcat 的 `详情=` 字段；形如 `12:03:41 · 第3轮 · pid=1234 · 推送(心跳兜底) ⇒ 已激活`）。 */
    // ★ 初值留空：四个上报点（markAlive / enterPushGrace / finishRed / onPingResult）都是
    //   **先给它赋值、再调 report()**，所以这个初值永远不会被写进日志；它原来那句中文字面量
    //   是卡片文案的一部分，已随卡片文案一并删除（见 项目文档
    @Volatile
    private var lastDetail = ""

    /**
     * 探活**全部在这个后台线程上跑**（收发广播、判定、定时都在它上面）。
     *
     * <p>为什么不能待在主线程（本机实测，同一台机器）：
     * <pre>
     *   进程起到首帧（Displayed）        = 1.42–1.48s
     *   主线程 Handler 上的回包回调      = 2.19s（即 PING 往返被测成 2190ms！）
     * </pre>
     * 主线程冷启动时被首帧占满一两秒，回包消息只能排在它后面 ⇒ 判定被拖到 2.4s。
     * 挪到后台线程后，回包在**首帧之前**就落地，UI 第一帧就是最终状态。
     *
     * <p>线程只在卡片可见期间活着（[start]/[stop] 由 `DisposableEffect` 配对调用）。
     */
    private val thread = HandlerThread("animv4-alive")

    /** 后台 handler；[stop] 后在主线程置 null ⇒ 所有后台回调都不再续期（@Volatile 保可见性）。 */
    @Volatile
    private var handler: Handler? = null

    /** 以下字段**只在后台线程上**读写（start/stop 除外），所以不需要同步。 */
    private var pong: BroadcastReceiver? = null

    /** 最后一次收到 PONG 的 elapsedRealtime；0 = 从未收到。 */
    private var lastPong = 0L

    /** 最后一次收到 PONG 的来源标签（进日志 / 详情行）。 */
    private var lastPongWhy = ""

    private var shown: AliveState? = null
    private var round = 0
    private var tries = 0

    /** 累计收到的推送/应答条数（只进日志：用来确认"推送通道真的通了"）。 */
    private var pushes = 0

    /**
     * ★ 单一待执行动作：**任一时刻只有一个**（下一轮 PING、宽限到期、或下一轮复检）。
     * 这样"收到推送 ⇒ 立刻绿"可以顺手把"待判红"的动作取消掉，绝不会误判。
     */
    private var pending: Runnable? = null

    /** 开始探活：注册 PONG 接收器 + 立刻打第一枪（非挂起，抢在主线程忙首帧之前）。 */
    fun start() {
        if (handler != null) return
        thread.start()
        val h = Handler(thread.looper)
        handler = h
        h.post {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    val pid = i?.getIntExtra("pid", -1) ?: -1
                    val why = i?.getStringExtra("reason") ?: "PONG 直投广播"
                    markAlive(pid, "推送(" + why + ")")
                }
            }
            // ★ 必须显式给 scheduler = 本后台 handler：不给的话动态接收器会跑在**主线程**上，
            //   与后台线程上的判定并发改 lastPong/shown/tries ⇒ 丢更新（状态抖动）。
            pong = if (runCatching {
                    ctx.registerReceiver(
                        r, IntentFilter(ACTION_PONG), null, h, Context.RECEIVER_EXPORTED
                    )
                }.isSuccess
            ) r else null
            Log.i(
                TAG_ALIVE,
                "开始探活（推送优先）：目标=" + target +
                    " PING超时=" + PING_TIMEOUT_MS + "ms" +
                    " 推送宽限=" + PUSH_GRACE_MS + "ms" +
                    " 过期=" + EXPIRY_MS + "ms" +
                    " PONG接收器=" + (if (pong != null) "已注册(RECEIVER_EXPORTED)" else "❌注册失败") +
                    " uptime=" + SystemClock.elapsedRealtime()
            )
            if (pong == null) {
                Log.i(TAG_ALIVE, "❌ PONG 接收器注册失败 ⇒ 推送通道不可用，本轮只能靠 PING 拉取（会判红）")
            }
            roundOnce()
        }
    }

    /** 停止探活：注销接收器 + 取消待执行动作 + 结束线程（离开主页/界面销毁时调用）。 */
    fun stop() {
        val h = handler ?: return
        handler = null
        runCatching {
            h.post {
                // 注意：不能用 cancelPending()（它依赖 handler，而此时 handler 已置 null）
                pending?.let { h.removeCallbacks(it) }
                pending = null
                pong?.let { runCatching { ctx.unregisterReceiver(it) } }
                pong = null
            }
        }
        runCatching { thread.quitSafely() }
    }

    // ------------------------------------------------------------------ 排程（单一 pending）

    /** 排定 [delayMs] 后执行 [r]；**会取消上一个**待执行动作（状态机单一出口的前提）。 */
    private fun post(delayMs: Long, r: Runnable) {
        val h = handler ?: return
        pending?.let { h.removeCallbacks(it) }
        pending = r
        h.postDelayed(r, delayMs)
    }

    /** 取消待执行动作（收到推送时调用：宽限期立刻结束，不再判红）。 */
    private fun cancelPending() {
        val h = handler ?: return
        pending?.let { h.removeCallbacks(it) }
        pending = null
    }

    // ------------------------------------------------------------------ 拉取（快速路径，保留）

    /** 一轮：发 PING（有序广播）→ 回包或 [PING_TIMEOUT_MS] 超时都进 [onPingResult]。 */
    private fun roundOnce() {
        val h = handler ?: return
        round++
        lastProbeWall = System.currentTimeMillis()
        val t0 = SystemClock.elapsedRealtime()
        var finished = false
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (finished) return
                finished = true
                cancelPending()
                val ex = runCatching { getResultExtras(false) }.getOrNull()
                onPingResult(
                    Pong(
                        ex?.getBoolean(KEY_ALIVE, false) == true,
                        ex?.getInt("pid", -1) ?: -1,
                        sentOk = true,
                        rttMs = SystemClock.elapsedRealtime() - t0,
                        reason = "有序广播",
                    ),
                    t0
                )
            }
        }
        post(PING_TIMEOUT_MS) {
            if (!finished) {
                finished = true
                onPingResult(null, t0)              // null = 超时（连"空回包"都没有）
            }
        }
        try {
            val it = Intent(ACTION_PING)
                .setPackage(target)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)   // HMC ModuleBridge.kt:234 同款
            // 结果接收器也用后台 handler 调度（照 HMC ModuleBridge.kt:684 的写法，只换 Looper）
            ctx.sendOrderedBroadcast(it, null, rx, h, 0, null, null)
            Log.i(
                TAG_ALIVE,
                "第" + round + "轮 PING 已发出 目标=" + target + " uptime=" + t0 +
                    "（" + PING_TIMEOUT_MS + "ms 无应答则转 " + PUSH_GRACE_MS + "ms 等待推送）"
            )
        } catch (t: Throwable) {
            Log.i(TAG_ALIVE, "第" + round + "轮 PING **发送即失败** 目标=" + target + " : " + t)
            if (!finished) {
                finished = true
                cancelPending()
                onPingResult(
                    Pong(
                        alive = false,
                        pid = -1,
                        sentOk = false,
                        sendErr = t.javaClass.simpleName + ": " + (t.message ?: ""),
                        rttMs = SystemClock.elapsedRealtime() - t0,
                        reason = "发送异常",
                    ),
                    t0
                )
            }
        }
    }

    // ------------------------------------------------------------------ 判定（唯一出口）

    /**
     * PING 这一腿的结果（**推送优先的核心**，三种走向）：
     * ① 有 alive ⇒ 立刻绿；
     * ② 从没绿过且这一腿失败 ⇒ 转"等待推送"宽限（**不判红**）；
     * ③ 绿过 ⇒ 按 [EXPIRY_MS] 判过期。
     */
    private fun onPingResult(r: Pong?, t0: Long) {
        val now = SystemClock.elapsedRealtime()
        if (r != null && r.alive) {
            markAlive(r.pid, "PING应答")
            return
        }
        if (lastPong == 0L) {
            enterPushGrace(r, t0)
            return
        }
        val silent = now - lastPong
        if (silent >= EXPIRY_MS) {
            lastPongWhy = "静默 " + silent + "ms 无任何 PONG/PING 应答"
            lastDetail = stamp() + " · 第" + round + "轮 · 已绿但 " + silent + "ms 无 PONG/PING 应答 ⇒ 判红"
            Log.i(TAG_ALIVE, "第" + round + "轮 判定=未激活（绿后过期）静默=" + silent + "ms 目标=" + target)
            report(AliveState.INACTIVE)
            scheduleRetry()
        } else {
            lastDetail = stamp() + " · 第" + round + "轮 · 本轮 PING 未应答，距上次 PONG 仅 " + silent + "ms（仍绿）"
            report(AliveState.ACTIVE)
            tries = 0
            post(ACTIVE_POLL_MS) { roundOnce() }
        }
    }

    /** ② 从没绿过 + 这一腿失败 ⇒ **不判红**：转 [AliveState.WAITING_PUSH]（卡片上与"模块未激活"**同一套红底+大红叉**，见 HomePage），[PUSH_GRACE_MS] 后仍无推送才红。 */
    private fun enterPushGrace(r: Pong?, t0: Long) {
        val rtt = SystemClock.elapsedRealtime() - t0
        val why = if (r == null) {
            ALIVE_DETAIL_NO_REPLY + "（" + PING_TIMEOUT_MS + "ms 超时）"
        } else if (!r.sentOk) {
            ALIVE_DETAIL_SEND_FAIL + r.sendErr + "）"
        } else {
            ALIVE_DETAIL_NO_REPLY + "（有回包但没有 alive：接收器不在/模块没注入）"
        }
        lastDetail = stamp() + " · 第" + round + "轮 · " + why + " · " + rtt + "ms"
        report(AliveState.WAITING_PUSH)
        Log.i(
            TAG_ALIVE,
            "第" + round + "轮 判定=等待推送（拉取失败，未判红）原因=" + why +
                " PING往返=" + rtt + "ms" +
                " 宽限=" + PUSH_GRACE_MS + "ms" +
                " 距进入本页=" + (SystemClock.elapsedRealtime() - startedAt) + "ms" +
                " 目标=" + target
        )
        // 宽限期内只要来一条推送，markAlive 会把这里排的动作取消 ⇒ 不会误判红
        post(PUSH_GRACE_MS) { finishRed(why, rtt) }
    }

    /** ③ 宽限期内也没有推送 ⇒ 判红（原因只进 logcat）。 */
    private fun finishRed(why: String, rtt: Long) {
        lastPongWhy = "PING 未送达且 " + PUSH_GRACE_MS + "ms 内无推送"
        lastDetail = stamp() + " · 第" + round + "轮 · " + why +
            "，且 " + PUSH_GRACE_MS + "ms 内无推送 ⇒ 未激活 · " + rtt + "ms"
        Log.i(
            TAG_ALIVE,
            "第" + round + "轮 判定=未激活（红）" + why + " 且宽限期内无推送" +
                " 距进入本页=" + (SystemClock.elapsedRealtime() - startedAt) + "ms" +
                " 目标=" + target
        )
        report(AliveState.INACTIVE)
        scheduleRetry()
    }

    /** 红/过期后的下一次探测间隔：按 [RETRY_GAPS_MS] 加宽，走完改成 [SETTLED_RECHECK_MS]。 */
    private fun scheduleRetry() {
        tries += 1
        val gap = RETRY_GAPS_MS.getOrNull(tries - 1) ?: SETTLED_RECHECK_MS
        post(gap) { roundOnce() }
    }

    /**
     * 收到一条 PONG（**推送** 或 **PING 应答**）⇒ 立刻绿，并取消"等待推送"的判红动作。
     *
     * <p>这里跑在后台线程上；写 Compose state 是跨线程安全的（快照写入，重组由主线程调度）。
     */
    private fun markAlive(pid: Int, why: String) {
        cancelPending()
        val now = SystemClock.elapsedRealtime()
        val first = lastPong == 0L
        lastPong = now
        lastPongWhy = why
        pushes++
        lastDetail = stamp() + " · 第" + round + "轮 · pid=" + pid + " · " + why + " ⇒ 已激活"
        Log.i(
            TAG_ALIVE,
            "收到应答（" + why + " pid=" + pid + "）⇒ 判绿" +
                (if (first) " ★ 首次应答" else "") +
                " 累计推送/应答=" + pushes +
                " 距进入本页=" + (now - startedAt) + "ms uptime=" + now
        )
        report(AliveState.ACTIVE)
        tries = 0
        post(ACTIVE_POLL_MS) { roundOnce() }
    }

    /** 最后一次探测的墙钟时间戳（HH:mm:ss）。 */
    private fun stamp(): String = runCatching {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(lastProbeWall))
    }.getOrDefault("--:--:--")

    /**
     * 状态上报（后台线程）。**每一轮都做两件事**：
     * ① 把完整结果写进 App 自己的 logcat（TAG=[TAG_ALIVE]，无 adb 时唯一的机器可读证据）；
     * ② 把**状态**回给卡片（★ 卡片不再显示详情/提示文案，所以只有这一个参数）。
     *
     * <p>★ 闪烁修复：`onState` 的调用方**只在状态值真的变化时**才写 Compose state，
     * 因此这里"每轮都上报"不会引起周期性重组。
     */
    private fun report(s: AliveState) {
        val changed = shown != s
        shown = s
        Log.i(
            TAG_ALIVE,
            "探测结果 状态=" + s +
                " 详情=" + lastDetail +
                " 距进入本页=" + (SystemClock.elapsedRealtime() - startedAt) + "ms" +
                " 目标=" + target
        )
        if (changed) {
            Log.i(TAG_ALIVE, "状态变化 ⇒ " + s + "（来源=" + lastPongWhy + "）")
        }
        onState(s)
    }
}

/** PING 目标：优先用本模块自己的作用域资源（`arrays.xml` 的 `xposedscope`），读不到退回常量。 */
private fun pingTarget(ctx: Context): String {
    val scoped = runCatching { ctx.resources.getStringArray(R.array.xposedscope) }.getOrNull()
    return scoped?.firstOrNull { !it.isNullOrBlank() } ?: TARGET_PKG
}

/**
 * 设置页的一个开关分组（照 HMC `settings` 页风格：**小标题 + 白卡分组**）。
 *
 * <p>★ 分组只是"换个位置显示"：[items] 里的三元组仍然原样是
 * `(键名, 显示名, 默认值)`，`cfg.set(键名, 值)` 的持久化逻辑一行未改，
 * 键名与默认值**逐字未动**（这些键只写本 App 的 `filesDir/v3.conf`，
 * hook 侧读的是另一份 `/sdcard/Download/dsh-debuglog/animv3.conf`，键名不同、互不影响）。
 */
private class SwitchGroup(val title: String, val items: List<Triple<String, String, Boolean>>)

/**
 * ★ 2026-10-03 设置页分类（用户要求"把设置中的内容分类"）。
 *
 * 归类依据是**每个开关的实际语义**（先读原 `SWITCHES` 表再定的组），三组：
 * 1. 「底栏外观」——只影响底栏本身长什么样；
 * 2. 「动画行为」——直接影响负一屏打开/返回的运动；
 * 3. 「遮罩与修复」——过渡里的 mask/leash/闩锁等机制级修复开关
 *    （原来没有「诊断」类开关：日志/观测开关在 hook 侧的 `animv3.conf` 里，App 界面不暴露）。
 *
 * ★ 组内顺序 = 原 `SWITCHES` 表顺序；键名/默认值与改动前**逐个一致**（只搬位置）。
 */
private val SWITCH_GROUPS = listOf(
    SwitchGroup(
        "总开关",
        listOf(
            Triple("ENABLED", "启用模块（总开关）", true)
        )
    ),
    SwitchGroup(
        "打开段",
        listOf(
            Triple("ENABLE_OPEN_SF", "自己驱动打开段 SF 动画", false),
            Triple("SWALLOW_MIUI_LEASH_WRITES", "吞掉 MIUI 的 leash 写入", true)
        )
    ),
    SwitchGroup(
        "裁剪 crop",
        listOf(
            Triple("ALLOW_MIUI_CROP", "允许 MIUI 自己写 crop", true),
            Triple("ENABLE_OWN_CROP", "我们也补 crop", true)
        )
    ),
    SwitchGroup(
        "遮罩 mask",
        listOf(
            Triple("FIX_MASK_BUFFER", "重画 mask buffer", true),
            Triple("MASK_ALPHA_FROM_ZERO", "遮罩起始 alpha 归零（本版关键）", true),
            Triple("MASK_NULL_USE_BLUR", "位图缺失时走模糊兜底", false),
            Triple("MASK_AS_BACKDROP", "挖洞法（实验）", false),
            Triple("ONLY_FIX_MASK_WHEN_WE_DRIVE", "仅在我们驱动时修 mask", false)
        )
    ),
    SwitchGroup(
        "返回段",
        listOf(
            Triple("ENABLE_RETURN_HANDOFF", "返回段交回 MIUI", true),
            Triple("ENABLE_RETURN_SF_FALLBACK", "返回 SF 兜底", true),
            Triple("FORCE_REVERSE_ON_MERGE_1021", "1021 合并时强制反向", true)
        )
    ),
    SwitchGroup(
        "诊断",
        listOf(
            Triple("LOG_LATENCY_CHAIN", "记录延迟链日志", true),
            Triple("SF_VERBOSE_PARAM_DUMP", "打印 SF 参数（很吵）", false),
            Triple("DEV_FLASH_PROBE", "闪黑定位探针", false)
        )
    ),
    SwitchGroup(
        "系统",
        listOf(
            Triple("ENABLE_SYSTEM_SERVER_HOOK", "system_server 放行远程动画权限", true)
        )
    )
)

/** 展平后的全部开关（只用于初始化 [flags] 快照；键集与改动前的 `SWITCHES` 完全相同）。 */
private val ALL_SWITCHES: List<Triple<String, String, Boolean>> = SWITCH_GROUPS.flatMap { it.items }

/**
 * ★ 子页面的 key（`AnimatedPageEnter` 用）。
 *
 * 规则：**以后每加一个子页面，在这里加一条 key**，不要在调用点直接写字面量 ——
 * 这样"哪些子页面存在"一眼可查，也不会出现两个页面撞 key（撞 key 会导致转场不播、
 * 直接被当成同一页替换）。
 */
private const val LOG_PAGE_KEY = "page:logs"

@Composable
fun ModuleSettings() {
    val ctx = LocalContext.current
    val cfg = remember { Cfg(ctx) }
    // ★ 修复（2026-10-03 用户报"开深色模式看不见字"）：原来这里是
    //   `if (!isSystemInDarkTheme()) Color.Black else Color.White` —— 深色模式下 fg 变**白字**，
    //   而本 App 的卡片/页面底色**全部是硬编码浅色**（`Color.White` 10 处 + 浅灰底）⇒ **白字白底 = 看不见**。
    //   本 App 的配色体系是"固定浅色"，所以文字色也必须固定深色（不再跟随系统深色）。
    val fg = fgColor()

    val pagerState = rememberPagerState(pageCount = { 4 })
    var selectedIndex by remember { mutableIntStateOf(0) }
    var isNavigating by remember { mutableStateOf(false) }
    var navJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    // ★ 阶段4：把 v3 那个无实现的 smooth 换成主线**真实可调**的三个参数（改完即时推给负一屏）
    var pShrink by remember { mutableFloatStateOf(cfg.float("RETURN_SHRINK_RATIO", 0.7f)) }
    var pBlur by remember { mutableIntStateOf(cfg.int("MASK_NULL_BLUR_RADIUS", 90)) }
    var pRevWrites by remember { mutableIntStateOf(cfg.int("RETURN_REVERSE_WRITES", 3)) }
    var liquidGlass by remember { mutableStateOf(cfg.bool("liquidGlass", true)) }
    var gaussBlur by remember { mutableStateOf(cfg.bool("gaussBlur", true)) }
    var flags by remember { mutableStateOf(ALL_SWITCHES.associate { it.first to cfg.bool(it.first, it.third) }) }

    // ★ 2026-10-04 日志功能：
    //   logLevel = 三选一（0 关闭 / 1 常规 / 2 详细，照 HyperCeiler 的 LogLevelManager.kt:29-31）；
    //   showLog  = 是否打开日志页（**不是第 5 个底栏页**：底栏仍是原来的 4 页，一行都不动）。
    var logLevel by remember { mutableIntStateOf(cfg.int("loglevel", LogStore.LEVEL_GENERAL)) }
    // ★ 关于页的滚动进度（0=在顶部 / 1=已滑过头部区）：用来把流光背景渐隐到页面底色（"过渡到白色"）
    val aboutListState = rememberLazyListState()
    val aboutScroll by remember {
        derivedStateOf {
            if (aboutListState.firstVisibleItemIndex > 0) 1f
            else (aboutListState.firstVisibleItemScrollOffset / 420f).coerceIn(0f, 1f)
        }
    }
    var showLog by remember { mutableStateOf(false) }

    // ★ 真实激活状态（2026-10-03 **推送优先**）：App 发 PING 只是快速路径；
    //   hook 侧在"注册成功 / 每次交互 / 每 5 秒兜底"主动推 PONG，App 收到即绿。
    //   探活器只在"主页"这张卡片可见时活着；点卡片（probeTick 变化）会重建一个 ⇒ 手动重试一次。
    //
    // ★★ 闪烁修复（关键）：**状态只在"值真的变了"时才写 Compose state**。
    //   探活器每一轮（绿了以后每 ACTIVE_POLL_MS=5s 一轮）都会回调 onState，但绝大多数轮次
    //   状态是同一个值；若无条件 `alive = s`，Compose 会每 5 秒判一次"状态对象变了"而重组整张卡
    //   （这是"看着一直在闪"的一半根因）。详情/提示两条 state 已随卡片文案一起删除
    //   —— 卡片不再显示它们，就不该再被它们驱动重组。
    var alive by remember { mutableStateOf(AliveState.DETECTING) }
    var probeTick by remember { mutableIntStateOf(0) }
    val target = remember(ctx) { pingTarget(ctx) }

    // ★ 2026-10-04 日志功能：App 一起来就把本地级别**推**给 hook 侧（快路径；被拦也不要紧，
    //   宿主每次 onEnter/onLeave 与每 3 次心跳会主动回读 —— 见 Bridge.ACTION_QUERY_LEVEL）。
    LaunchedEffect(Unit) {
        LogStore.pushLevel(ctx, target, logLevel)
    }
    DisposableEffect(selectedIndex, target, probeTick) {
        if (selectedIndex != 0) {
            onDispose { }
        } else {
            val detector = AliveDetector(ctx, target) { s ->
                // 只有跨状态（检测中/等待推送 → 已激活/未激活）才写 state ⇒ 其余轮次零重组。
                if (alive != s) alive = s
            }
            detector.start()
            onDispose { detector.stop() }
        }
    }

    // ★ 阶段4：原 v3 的 smooth（v4 无对应实现）已换成下面三个真实参数；
    //   各自变化时写本地缓存（推给负一屏由各自的 onXxx 负责）。
    LaunchedEffect(pShrink) { delay(350); cfg.set("RETURN_SHRINK_RATIO", pShrink) }
    LaunchedEffect(pBlur) { delay(350); cfg.set("MASK_NULL_BLUR_RADIUS", pBlur) }
    LaunchedEffect(pRevWrites) { delay(350); cfg.set("RETURN_REVERSE_WRITES", pRevWrites) }

    val titles = listOf("主页", "配置", "设置", "关于")

    // ↓↓↓ 逐字照搬 HyperMusicCover MainActivity.kt:294-340
    fun goToPage(index: Int) {
        if (index == selectedIndex) return
        navJob?.cancel()
        selectedIndex = index
        isNavigating = true
        navJob = scope.launch {
            val myJob = coroutineContext.job
            try {
                pagerState.scroll(MutatePriority.UserInput) {
                    val layoutInfo = pagerState.layoutInfo
                    val pageSize = layoutInfo.pageSize + layoutInfo.pageSpacing
                    val currentDistanceInPages =
                        index - pagerState.currentPage - pagerState.currentPageOffsetFraction
                    val scrollPixels = currentDistanceInPages * pageSize
                    var previousValue = 0f
                    animate(
                        initialValue = 0f,
                        targetValue = scrollPixels,
                        animationSpec = PagerNavigationSpringSpec,
                    ) { currentValue, _ ->
                        previousValue += scrollBy(currentValue - previousValue)
                    }
                }
                if (pagerState.currentPage != index) {
                    pagerState.scrollToPage(index)
                }
            } finally {
                if (navJob == myJob) {
                    isNavigating = false
                    if (pagerState.currentPage != index) {
                        selectedIndex = pagerState.currentPage
                    }
                }
            }
        }
    }

    BackHandler(enabled = selectedIndex != 0) { goToPage(0) }

    LaunchedEffect(pagerState.currentPage) {
        if (!isNavigating && selectedIndex != pagerState.currentPage) {
            selectedIndex = pagerState.currentPage
        }
    }

    // ★★ 2026-10-03 页面转场（用户："怎么进入页面没有动画，以后每个都要有动画"）★★
    //   改动前这里是：`if (showLog) { BackHandler…; LogPage(…); return }`
    //   ⇒ 日志页是**整页瞬时替换**（裸切换）、零转场动画 —— 这就是用户看到的"进入页面没有动画"
    //     （证据：改动前 ModuleSettings.kt:767-771 的提前 return）。
    //   现在改走统一包装器 `AnimatedPageEnter`（PageTransition.kt）：日志页从右侧屏外（整屏宽）
    //   滑入 + 淡入，底页同时**左退整屏宽**；返回（返回键 / 手势 / 页内「‹ 返回」）向右滑出 + 淡出、
    //   底页滑回。规格 **300ms / FastOutSlowInEasing / 位移整屏宽**，只有一份（PageTransitionSpec）。
    //   ★ "真驱动"核实：本行下面 `pageKey = if (showLog) …` 直接就是 `AnimatedContent` 的
    //     `targetState` 参数（PageTransition.kt:146），不是"只把内容包在外层"那种假包装；
    //     用户仍觉得像硬切的差异只在**位移量**（原为底页 1/4 宽），本轮已加到整屏宽。
    //   ★ 规则：**任何**整屏切换都必须走这个包装器，不许再写 `if (x) { …Page(); return }`。
    //   ★ 底页（4 个底栏 tab + pager + PagerNavigationSpringSpec + 玻璃底栏 + 状态卡）
    //     与日志页交互**一行都没改**：只是被包进下面这个 lambda，好在转场期间保持组合。
    val rootPage: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            val backdrop = rememberLayerBackdrop()

            // ★ 这一层**必须始终录**：
            //   底栏在"高斯模糊关掉"时**仍然采样这一层**（isBlurActive 恒 true，只把 blur() 摘掉），
            //   若此时不录层 ⇒ 采到空层 ⇒ 底栏画成**黑色**（已踩：用户报"关模糊变黑"）。
            //   HyperMusicCover 的门控是"不录"与"不采样"**一起关**（isBlurActive = isBlurEnabled），
            //   我们只关 blur() 一项，所以不能照抄那个门控。
            // ★ 录层的绑定规则（两个约束必须同时满足）：
            //   1) 玻璃底栏**开着**时：无论模糊开关如何都要录（关模糊时底栏仍在采样，不录 ⇒ 黑，已踩）；
            //   2) 玻璃底栏**关着**时（走 MIUIx 默认底栏）：**一点都不录**（没人读这层，白渲染整页）。
            Box(Modifier.fillMaxSize().then(if (liquidGlass) Modifier.layerBackdrop(backdrop) else Modifier)) {
                Box(Modifier.fillMaxSize().background(pageBg()))
                // ============ 流光溢彩背景（MIUIx RuntimeShader / compose-miuix-ui，Apache-2.0）============
                //   画在**根布局**：pager 页内的坐标系带横向平移，着色器画在页内会完全看不见（真机实测）。
                //   透明度按"离关于页的距离"渐入渐出；动画只在关于页可见时跑（着色器每帧重绘，省电）。
                val aboutDistance = kotlin.math.abs(
                    pagerState.currentPage + pagerState.currentPageOffsetFraction - 3f
                )
                val aboutAlpha = (1f - aboutDistance).coerceIn(0f, 1f)
                com.qianxia.hyperassistant.effect.BgEffectBackground(
                    dynamicBackground = aboutAlpha > 0.01f && aboutScroll < 0.99f,
                    isFullSize = true,
                    modifier = Modifier.fillMaxSize(),
                    // ★ 下滑时渐隐到页面底色（就是用户要的"过渡到白色"）；离开关于页也渐隐
                    alpha = { aboutAlpha * (1f - aboutScroll) }
                ) { }
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    overscrollEffect = null,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    // ★★ DSH 修复 U-3a（照 HyperMusicCover 抄，只改"滚动容器"这一个变量）★★
                    //   原来这里是**裸 `Column(verticalScroll)`**——底层的 `Modifier.verticalScroll` 只做
                    //   边界钳制，没有阻尼/弹簧 ⇒ 症状就是"上下滑不动、滑到顶/底没有回弹"。
                    //   HMC 每个页面的列表是（`ui/util/PageScaffold.kt:128-142`）：
                    //     LazyColumn(overscrollEffect = null,
                    //                modifier = Modifier.fillMaxSize().pageScrollModifiers(showTopAppBar = true,
                    //                                                                         topAppBarScrollBehavior = scrollBehavior),
                    //                contentPadding = contentPadding, content = content)
                    //   其中 `pageScrollModifiers` 定义在 `ui/util/BlurUtils.kt:130-136`：
                    //     fun Modifier.pageScrollModifiers(showTopAppBar: Boolean, topAppBarScrollBehavior: ScrollBehavior): Modifier =
                    //         this.scrollEndHaptic().overScrollVertical()
                    //             .then(if (showTopAppBar) Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection) else Modifier)
                    //   **产生回弹的只有 `overScrollVertical()`**：它是 MIUIx 的阻尼 overscroll
                    //   （`top.yukonga.miuix.kmp.utils`，0.9.4 的 `OverscrollKt.overScrollVertical(Modifier,
                    //   nestedScrollToParent: Boolean = true, isEnabled: () -> Boolean = { true })`；
                    //   实现类 `OverscrollNode` 里有 `SpringEngine` / `offset` / `startSpringAnimation`，
                    //   在 `onPostScroll` 里吃掉边界处未被消费的位移并做弹簧回弹翻译）。
                    //   ★ `overscrollEffect = null` 是这条配方的必要部分（HMC BlurUtils.kt:126-128 原话：
                    //     "overScrollVertical is miuix's damped overscroll and it REPLACES Compose's own —
                    //      every caller must also pass overscrollEffect = null to the list, or the stretch
                    //      runs on top of the damping and the two fight at the ends"）。
                    //   ★ 第三段（TopAppBar 折叠用的 nestedScroll）没有移植：我们的页面**没有** MIUIx
                    //     `TopAppBar`（标题是列表内的 `BasicText`），而 HMC 那段是给它的 `TopAppBar(
                    //     scrollBehavior = ...)` 折叠用的（PageScaffold.kt:83-91）；没有对象可驱动，
                    //     硬挂一个 `nestedScroll` 只是白挂一条永不消费的连接。见 `pageScrollModifiers` 注释。
                    LazyColumn(
                        // ★ 修「下拉没回弹」：这里原来跟着 HMC 写了 `overscrollEffect = null`
                        //   （HMC 用 MIUIx 的阻尼节点顶替），但实测在本层级（list 在 HorizontalPager 里）
                        //   MIUIx 的 `overScrollVertical()` 没产生任何位移 ⇒ 两头都空。
                        //   现在**交回 Compose 自带的拉伸回弹**（真机实测有位移、松手回弹）。
                        state = if (page == 3) aboutListState else rememberLazyListState(),
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp)
                            .pageScrollModifiers(),
                        // ★ U-3c 的"给底栏让位"不许弄丢：旧链里 `.padding(bottom = 96.dp)` 写在
                        //   `.verticalScroll(...)` **之后** ⇒ 那是**滚动内容里**的 96dp 内缩（不是把滚动
                        //   视口砍掉 96dp）。LazyColumn 的等价位置就是 `contentPadding.bottom`
                        //   （HMC 也是放这里：PageScaffold.kt:97-104 / AboutPage.kt:254-262）。
                        //   若改成挂在 modifier 上，滚动视口会矮 96dp、内容再也滑不到底栏下面 ⇒ 玻璃
                        //   底栏只剩纯背景可采，是**另一个变量**，不动。
                        contentPadding = PaddingValues(bottom = 110.dp)
                    ) {
                        // ★ 关于页（page==3）**不画大标题**，并且上方留出 HMC 那样的呼吸空间
                        //   （HMC 的 AboutPage：logo 区在 `top + 52dp`、其 `extraTop = 40dp`；
                        //    我们把标题让出的高度补给间距，观感一致）。
                        item { Spacer(Modifier.height(if (page == 3) 140.dp else 24.dp)) }
                        if (page != 3) {
                            item { BasicText(titles[page], style = TextStyle(fg, 34.sp)) }
                            item { Spacer(Modifier.height(16.dp)) }
                        }
                        item {
                            when (page) {
                                0 -> HomePage(alive, fg, {
                                    // ★ 点卡片 = 重新检测：重建探活器（DisposableEffect 的 key 变了）
                                    alive = AliveState.DETECTING
                                    probeTick++
                                }, onOpenLog = { showLog = true })
                                1 -> ConfigPage(
                                    { pShrink }, { pShrink = it; sendSetCfgValue(ctx, "RETURN_SHRINK_RATIO", it) },
                                    { pBlur }, { pBlur = it; sendSetCfgValue(ctx, "MASK_NULL_BLUR_RADIUS", it) },
                                    { pRevWrites }, { pRevWrites = it; sendSetCfgValue(ctx, "RETURN_REVERSE_WRITES", it) },
                                    fg
                                )
                                2 -> SettingsPage(
                                    liquidGlass = { liquidGlass },
                                    onLiquidGlass = { liquidGlass = it; cfg.set("liquidGlass", it) },
                                    gaussBlur = { gaussBlur },
                                    onGaussBlur = { gaussBlur = it; cfg.set("gaussBlur", it) },
                                    flags = { flags },
                                    onFlag = { k, v ->
                                        flags = flags + (k to v)
                                        cfg.set(k, v)          // 本 App 的本地缓存（用于下次打开时回显）
                                        sendSetCfg(ctx, k, v)  // ★ 阶段3：推给 hook 侧 ⇒ 立即生效
                                    },
                                    logLevel = { logLevel },
                                    onLogLevel = { n ->
                                        // ★ 三选一：① 本地立即生效（写 App 自己的 v3.conf）
                                        //          ② 推给 hook 侧（快路径）
                                        //          ③ hook 侧还会主动回读（保底，见 Bridge.ACTION_QUERY_LEVEL）
                                        logLevel = n
                                        cfg.set("loglevel", n)
                                        LogStore.pushLevel(ctx, target, n)
                                    },
                                    fg = fg
                                )
                                else -> AboutPage(fg, { selectedIndex == 3 }, 1f)
                            }
                        }
                        // 旧链最后一个 Spacer（内容内缩进），保持不动。
                        // ★ 这里原来还有一个 `Spacer(160.dp)`（"给底栏让位"的旧遗留）。
                        //   它和下面的 `contentPadding.bottom = 96.dp` **叠加** ⇒ 底部共留 256dp，
                        //   用户报"底部空缺太大、不紧凑"。已删除；底部只保留 contentPadding 这一份（110dp，
                        //   刚好让开悬浮胶囊且不浪费空间）。
                    }
                }
            }

            // ★ 底栏（在层外）：两种主题二选一 + 高斯模糊开关
            MiuixTheme {
                val navItems = listOf(
                    NavigationItem("主页", MiuixIcons.Home),
                    NavigationItem("配置", MiuixIcons.ListView),
                    NavigationItem("设置", MiuixIcons.Settings),
                    NavigationItem("关于", MiuixIcons.Info)
                )
                if (liquidGlass) {
                    IosLiquidGlassNavigationBar(
                        items = navItems,
                        selectedIndex = selectedIndex,
                        onItemClick = { goToPage(it) },
                        backdrop = backdrop,
                        isBlurActive = true,
                        blurEnabled = gaussBlur,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                    )
                } else {
                    // ★★ DSH 修复 P6c：非玻璃底栏 = **自绘通栏 + 恒不透明**（照 HMC 的普通模式抄）★★
                    //
                    // 【缺陷 1 · 透明】改动前这里是 MIUIx `NavigationBar(color = if (gaussBlur) Transparent
                    //   else surface)`（照 HMC `MainActivity.kt:460` 的 `barColor` 抄的）。但 HMC 那一行
                    //   **只有在它外面还套了 `textureBlur(...)` 时**才是对的（HMC MainActivity.kt:463-478：
                    //   透明是留给 textureBlur 的模糊面透出来的）；MIUIx 的 `NavigationBar` **没有任何
                    //   模糊能力**，把 `Color.Transparent` 交给它就等于直接露出下面的页面内容 ⇒ 用户看到的
                    //   "底栏透明、图标浮在内容上"。
                    // 【缺陷 2 · 左右留边】MIUIx `NavigationBar` 是"自己的背景画在自己身上"
                    //   （0.9.4 反汇编：`Modifier.fillMaxWidth().background(color)` 在它**内部**），
                    //   所以只要外面套过任何横向内缩（12:43 那版是我们自己加的 `.padding(horizontal = 44.dp)`），
                    //   连背景一起被缩进去 ⇒ 看着就是左右留边的"浮动胶囊"。
                    // ⇒ 修法：容器自己画（`Box + fillMaxWidth + background(不透明色)`，**零横向内缩**），
                    //   里面自己排 4 个 tab —— 通栏是结构保证的，不依赖任何库的内部布局。
                    //   tab 项仍用 MIUIx `NavigationBarItem`（同库公开组件）：它自带 `weight(1f, fill=true)`
                    //   + `height(ItemHeight)` + `Role.Tab` 语义（0.9.4 `NavigationBarKt` 反汇编已核实），
                    //   ⇒ 图标/文案/选中态配色/字重/点击区域与改动前**逐项相同**，只是容器换成了通栏。
                    //   ★ 与模糊开关彻底解耦：本分支不再读 `gaussBlur`（它只作用于玻璃底栏，见上分支）。
                    val barColor = MiuixTheme.colorScheme.surface.copy(alpha = 1f)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()          // 通栏：左右**不留任何间隙**（连到屏幕两边）
                            .background(barColor)    // 恒不透明（alpha 强制 1f，绝不 Transparent）
                            // 照 HMC MainActivity.kt:479-484：整条底栏吃掉点击（空 onClick），
                            // 手势条那一条的触摸不再漏给下面的页面（与 MIUIx 原来那个 spacer 的行为一致）。
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 安全区：**背景已经铺到屏幕底**（含系统手势条区域，因为背景在 Row 外层的 Box 上），
                                // 内容按导航栏底部 inset 让开 —— 与 MIUIx `NavigationBar` 内部
                                // `WindowInsets.navigationBars` 底部内缩（0.9.4 NavigationBarKt 反汇编）等价。
                                .navigationBarsPadding()
                        ) {
                            // 4 个 tab：顺序、图标、文案与改动前完全一致（选中态由 NavigationBarItem 决定）。
                            navItems.forEachIndexed { index, item ->
                                NavigationBarItem(
                                    selected = selectedIndex == index,
                                    onClick = { goToPage(index) },
                                    icon = item.icon,
                                    label = item.label,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    AnimatedPageEnter(
        pageKey = if (showLog) LOG_PAGE_KEY else null,
        modifier = Modifier.fillMaxSize(),
        root = rootPage,
        page = {
            // 返回键 / 返回手势：注册在 pager 那个 BackHandler 之后 ⇒ 优先关日志页。
            // enabled = showLog（不是恒 true）：退出动画播放期间不再吞掉第二次返回。
            BackHandler(enabled = showLog) { showLog = false }
            LogPage(logLevel = logLevel, fg = fg, onBack = { showLog = false })
        }
    )
}

/**
 * ★ DSH 照 HyperMusicCover 抄：`ui/util/BlurUtils.kt:130-136` 的 `pageScrollModifiers`，
 * 修饰符**顺序一致**（先 `scrollEndHaptic`、再 `overScrollVertical`）。
 *
 * 两处刻意的差异（都已在调用点注释里说明理由）：
 * 1. 这里不接受 `showTopAppBar` / `topAppBarScrollBehavior`：我们的页面**没有** MIUIx
 *    `TopAppBar`（标题是列表内的 `BasicText`），HMC 那段 `nestedScroll(...)` 是给它自己的
 *    `TopAppBar(scrollBehavior = scrollBehavior)` 折叠用的，我们无对象可驱动。
 *    ★ 证据（0.9.4 库内签名，`javap -p`）：`top.yukonga.miuix.kmp.basic.TopAppBarKt`
 *      里确有 `public static final ScrollBehavior MiuixScrollBehavior(TopAppBarState,
 *      Function0<Boolean>, AnimationSpec<Float>, DecayAnimationSpec<Float>, Composer, int, int)`
 *      ——即 `MiuixScrollBehavior` 是 `TopAppBar.kt` 里的一个 `@Composable` **函数**（不是类），
 *      且它是配合 `TopAppBar(scrollBehavior = …)` 使用的。没有 TopAppBar 时挂它只是白挂。
 * 2. 直接依赖 `miuix-ui` 的 `top.yukonga.miuix.kmp.utils` 两个函数（0.9.4 已确认存在，见下），
 *    不新增任何依赖（`app/build.gradle.kts` 不动）。
 *
 * ★ 库内证据（`top.yukonga.miuix.kmp:miuix-ui-android:0.9.4`，本机 Gradle 缓存内 AAR）：
 *   - `top/yukonga/miuix/kmp/utils/OverscrollKt.class`
 *     `public static final Modifier overScrollVertical(Modifier, boolean, Function0<Boolean>)`
 *     `public static Modifier overScrollVertical$default(Modifier, boolean, Function0, int, Object)`
 *     ⇒ Kotlin 侧 `Modifier.overScrollVertical(nestedScrollToParent: Boolean = true,
 *       isEnabled: () -> Boolean = { true })`
 *   - `top/yukonga/miuix/kmp/utils/ScrollEndHapticKt.class`
 *     `public static final Modifier scrollEndHaptic-zg-ukT8(Modifier, int)`
 *     ⇒ Kotlin 侧 `Modifier.scrollEndHaptic(hapticFeedbackType: HapticFeedbackType =
 *       HapticFeedbackType.TextHandleMove)`（`-zg-ukT8` 是 value class 参数的名称修饰）
 *   - 回弹本体：`OverscrollNode` 里有字段 `SpringEngine springEngine` / `float offset` /
 *     `Job animationJob` 与方法 `startSpringAnimation(float)`、`setOffset` 里调
 *     `LayoutModifierNodeKt.invalidatePlacement`（即"把内容按 offset 平移出去再弹回"）。
 */
private fun Modifier.pageScrollModifiers(): Modifier = this
    // ★ 2026-10-07：这里曾按 HMC 的配方挂 MIUIx `overScrollVertical()`（阻尼回弹），
    //   但真机实测**没有任何位移**（list 在 HorizontalPager 里，节点不生效）⇒ 已撤掉，
    //   改用 Compose 自带的拉伸回弹（列表不再传 `overscrollEffect = null`）。
    //   若日后要换回阻尼手感，需先单独验证"MIUIx 阻尼节点在本层级是否生效"。
    .scrollEndHaptic()
    .overScrollVertical()

@Composable
private fun Card2(color: Color, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(color)
            // ★ 只有主页那张状态卡传 onClick（卡片上点一下 = 重新检测）；其余卡片行为不变。
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) { content() }
}

@Composable
private fun SwitchRow(label: String, selected: () -> Boolean, onSelect: (Boolean) -> Unit, fg: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, style = TextStyle(fg, 16.sp))
        Spacer(Modifier.weight(1f))
        Switch(checked = selected(), onCheckedChange = onSelect)
    }
}

/** 读系统属性（HyperOS 版本、机型市场名）——用反射，避免编译期依赖隐藏 API。 */
private fun sysProp(key: String): String = runCatching {
    val cls = Class.forName("android.os.SystemProperties")
    val get = cls.getMethod("get", String::class.java)
    (get.invoke(null, key) as? String).orEmpty()
}.getOrDefault("")

/**
 * 大号圆环 + 里面对勾/叉——Canvas 自绘，**不依赖 material-icons**（`app/build.gradle.kts` 一行未动）。
 *
 * 这就是 HMC 那两个 Material 图标的等价物：
 * `Icons.Rounded.CheckCircleOutline` / `Icons.Rounded.ErrorOutline`（外圆环 + 内对勾/叉，同一构图），
 * 只是换成 Canvas 画，颜色/尺寸由调用方给 —— 见 [HomePage] 里 110dp / offset(27,31) / accent 的用法。
 *
 * @param ok `true` 画对勾、`false` 画叉。
 *           ★ 2026-10-04 起**没有"只留圆环"的中性态**：判定未到（DETECTING / WAITING_PUSH）
 *           与判定为"未激活"（INACTIVE）**共用同一套红底 + 大红叉**（见 [HomePage]）——
 *           用户明确不要"白底 + 灰环 + 灰叉"那套灰白中性视觉。
 */
@Composable
private fun StatusGlyph(ok: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = size.minDimension * 0.40f
        val c = Offset(size.width / 2f, size.height / 2f)
        val w = r * 0.22f
        drawCircle(color = tint, radius = r, center = c, style = Stroke(width = w))
        if (ok) {
            val p = Path().apply {
                moveTo(size.width * 0.28f, size.height * 0.52f)
                lineTo(size.width * 0.45f, size.height * 0.68f)
                lineTo(size.width * 0.74f, size.height * 0.34f)
            }
            drawPath(p, color = tint, style = Stroke(width = w, cap = StrokeCap.Round))
        } else {
            val p = Path().apply {
                moveTo(size.width * 0.32f, size.height * 0.32f)
                lineTo(size.width * 0.68f, size.height * 0.68f)
                moveTo(size.width * 0.68f, size.height * 0.32f)
                lineTo(size.width * 0.32f, size.height * 0.68f)
            }
            drawPath(p, color = tint, style = Stroke(width = w, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun HomePage(alive: AliveState, fg: Color, onRetry: () -> Unit, onOpenLog: () -> Unit) {
    // ★ 真实激活状态（2026-10-03 **推送优先**）：
    //   ACTIVE       = 收到 hook 侧推来的 PONG（或 PING 应答）
    //   DETECTING    = 刚进 App、第一轮 PING 还在飞
    //   WAITING_PUSH = PING 已超时（拉取失败），还在 2000ms 宽限期内等推送
    //   INACTIVE     = 宽限期内也无推送，或已绿但 10 秒没再收到任何 PONG/PING 应答
    //
    // ★★ 2026-10-04 状态卡恢复 HMC 观感（用户原话："对号的卡片还是像 hmc 一样带符号好看"）★★
    //   逐条照抄 HyperMusicCover `ui/screen/home/HomePage.kt` 的**浅色主题**分支
    //   （下面 `HMC:` 前缀的都是那份文件的行号，不是本文件的行号）：
    //     HMC:138-150  statusColor = if (ok) … else …（浅色分支 = HMC:142 0xFFDFFAE4 / HMC:148 0xFFFDE8E8）
    //     HMC:151-157  iconTint    = if (ok) … else …（浅色分支 = HMC:153 0xFF36D167 / HMC:156 0xFFDC3545）
    //     HMC:204-210  Card(colors = CardDefaults.defaultColors(color = statusColor), …)
    //     HMC:212-224  右侧大符号：外 Box `fillMaxSize().offset(27.dp, 31.dp)` + `BottomEnd`
    //                  （HMC:214-216），里面 `Icon(Modifier.size(110.dp))`（HMC:219），
    //                  tint = iconTint（HMC:222），图形 = CheckCircleOutline / ErrorOutline（HMC:220-221）
    //   这里**复用本文件既有的 `StatusGlyph`**（Canvas 画的圆环 + 对勾/叉，与上面两个 Material
    //   图标同一构图）—— 项目里没有 material-icons 依赖，且 `app/build.gradle.kts` 禁改，
    //   所以不新增任何依赖。
    //
    // ★★ 消闪（保留），但**中性灰白态已删除**（2026-10-04 用户第二问：
    //   "这不还是硬切吗…我不是说不要这样的吗"，截图正是"白底 + 灰环灰叉 + 检测中…"）★★
    //   ① 状态只在**真变化**时写 Compose state（见上面 `if (alive != s) alive = s`）；
    //   ② **整张卡只剩两套配色**：
    //        · 红套（HMC 浅色分支的"未激活"）：卡底 0xFFFDE8E8 + 大叉 0xFFDC3545
    //          —— `DETECTING` / `WAITING_PUSH` / `INACTIVE` **三者共用，字节级完全相同**；
    //        · 绿套（HMC 浅色分支的"已激活"）：卡底 0xFFDFFAE4 + 大对勾 0xFF36D167；
    //   ③ 探测会周期性重来（每轮 PING 800ms → 宽限 2000ms），每轮都可能把状态在
    //      DETECTING ⇄ WAITING_PUSH ⇄ INACTIVE 之间推来推去 —— 因为这三者**映射到同一套视觉**
    //      （颜色 + 符号 + 标题三者都不变），所以**看着一次都不闪**；
    //      标题也**固定**为「模块未激活」（不再随 DETECTING 变成"检测中…"），
    //      ⇒ 从进 App 到拿到首次判定，整段路上卡片**一个像素都不变**，判定到达时**最多变一次**（红→绿）。
    //   ④ 卡片高度固定（heightIn(min = 150.dp)）、三行文字位置固定 ⇒ 切换时只有颜色变，布局不抖。
    val active = alive == AliveState.ACTIVE
    val statusColor = if (active) okBg() else badBg()
    val accent = if (active) Color(0xFF36D167) else Color(0xFFDC3545)
    // 只有"已激活"画对勾，其余一律画叉（不再有"只留圆环"的中性态）。
    val glyph: Boolean = active
    val title = if (active) "模块已激活" else "模块未激活"

    // ★ 点卡片 = 重新检测（无 adb 时的第一手手段）；卡片上**不再显示**任何重试/操作提示字样。
    Card2(color = statusColor, onClick = onRetry) {
        // 固定最小高度：卡片始终三行文字，高度不再随状态变。
        Box(Modifier.fillMaxWidth().heightIn(min = 150.dp)) {
            Column(Modifier.padding(16.dp)) {
                BasicText(title, style = TextStyle(fg, 22.sp))
                Spacer(Modifier.height(6.dp))
                BasicText(APP_VERSION, style = TextStyle(fg, 16.sp))
                Spacer(Modifier.height(4.dp))
                BasicText("HyperAssistant", style = TextStyle(fg, 14.sp))
            }
            // ★ 右侧大符号，照 HMC `HomePage.kt:212-224`：110dp + offset(27dp, 31dp) + 贴右下角
            //   ⇒ 溢出卡片右/下缘，被卡片的 24dp 圆角裁掉一部分（HMC 原话 "the oversized icon
            //   bleeds off the bottom-right corner"）。颜色 = accent：**只有绿或红两种**。
            StatusGlyph(
                ok = glyph,
                tint = accent,
                modifier = Modifier
                    .size(110.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 27.dp, y = 31.dp)
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Card2(color = cardBg()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            InfoRow("设备名称", sysProp("ro.product.marketname").ifEmpty { Build.MODEL }, fg)
            InfoRow("设备型号", Build.MODEL, fg)
            InfoRow("HyperOS 版本", sysProp("ro.mi.os.version.incremental").ifEmpty { Build.DISPLAY }, fg)
            InfoRow("Android 版本", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})", fg)
            InfoRow("模块版本", APP_VERSION, fg)
        }
    }
    Spacer(Modifier.height(16.dp))
    Card2(color = cardBg()) {
        Column(Modifier.padding(16.dp)) {
            BasicText("说明", style = TextStyle(fg, 18.sp))
            Spacer(Modifier.height(6.dp))
            BasicText(
                // ★ 2026-10-03 修正：原文案是"v3 尚无 hook 侧，设置仅持久化到 v3.conf" ——
                //   这句话**早就过期了**（hook 侧现在有探针、真实激活探测与日志推送）⇒ 改成事实描述。
                "开关在「设置」页。本卡状态为真实探测（hook 侧 PING 应答 + 5 秒心跳），"
                    + "变更后立即生效（hook 侧配置每 3 秒轮询一次）；详细日志见首页「日志」。",
                style = TextStyle(fg.copy(alpha = 0.6f), 13.sp)
            )
        }
    }
    // ★ 2026-10-04 日志功能：首页的「日志」入口（一行卡，风格与上面两张卡完全一致）。
    //   它**不是**新的底栏页：底栏仍是 主页/配置/设置/关于 四页，一行都没动。
    Spacer(Modifier.height(16.dp))
    Card2(color = cardBg(), onClick = onOpenLog) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                BasicText("日志", style = TextStyle(fg, 18.sp))
                Spacer(Modifier.height(4.dp))
                BasicText(
                    "常规 / 详细两档 · 一键导出 / 复制 / 清空",
                    style = TextStyle(fg.copy(alpha = 0.6f), 13.sp)
                )
            }
            BasicText("›", style = TextStyle(fg.copy(alpha = 0.6f), 22.sp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, fg: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        BasicText(label, style = TextStyle(fg, 15.sp))
        BasicText(value.ifBlank { "—" }, style = TextStyle(fg.copy(alpha = 0.6f), 14.sp))
    }
}

@Composable
private fun ConfigPage(
    shrink: () -> Float, onShrink: (Float) -> Unit,
    blur: () -> Int, onBlur: (Int) -> Unit,
    revWrites: () -> Int, onRevWrites: (Int) -> Unit,
    fg: Color
) {
    GroupTitle("返回段参数", fg)
    Card2(color = cardBg()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            StepRowF("返回缩回比例", shrink(), 0.30f, 1.00f, 0.05f, onShrink, fg)
            StepRowI("缺位图模糊半径", blur(), 0, 180, 10, onBlur, fg)
            StepRowI("反向写入笔数", revWrites(), 0, 10, 1, onRevWrites, fg)
        }
    }
    Spacer(Modifier.height(6.dp))
    BasicText(
        "改完**立即生效**：推给负一屏进程，不用重装、不用重启。"
            + "（滑块属玻璃控件、放进被采样层会成环 ⇒ 这里用步进按钮，避免那个坑）",
        modifier = Modifier.padding(horizontal = 8.dp),
        style = TextStyle(fg.copy(alpha = 0.6f), 12.sp)
    )
}

@Composable
private fun StepRowF(
    label: String, value: Float, min: Float, max: Float, step: Float,
    onChange: (Float) -> Unit, fg: Color
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(label, style = TextStyle(fg, 16.sp))
            Spacer(Modifier.height(2.dp))
            BasicText("当前：%.2f".format(value), style = TextStyle(fg.copy(alpha = 0.6f), 13.sp))
        }
        StepButton("−") { onChange((value - step).coerceIn(min, max)) }
        Spacer(Modifier.width(10.dp))
        StepButton("+") { onChange((value + step).coerceIn(min, max)) }
    }
}

@Composable
private fun StepRowI(
    label: String, value: Int, min: Int, max: Int, step: Int,
    onChange: (Int) -> Unit, fg: Color
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(label, style = TextStyle(fg, 16.sp))
            Spacer(Modifier.height(2.dp))
            BasicText("当前：$value", style = TextStyle(fg.copy(alpha = 0.6f), 13.sp))
        }
        StepButton("−") { onChange((value - step).coerceIn(min, max)) }
        Spacer(Modifier.width(10.dp))
        StepButton("+") { onChange((value + step).coerceIn(min, max)) }
    }
}

@Composable
private fun StepButton(glyph: String, onClick: () -> Unit) {
    // ★ 修排版：这里**不能**用 Card2 —— Card2 内部是 fillMaxWidth()，
    //   放进 Row 会把标签挤成一列字（用户截图实测）。改成固定尺寸的小按钮。
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(pageBg())
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(glyph, style = TextStyle(fgColor(), 18.sp))
    }
}

@Composable
private fun GroupTitle(text: String, fg: Color) {
    BasicText(
        text,
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp, bottom = 6.dp),
        style = TextStyle(fg.copy(alpha = 0.6f), 15.sp)
    )
}

@Composable
private fun SettingsPage(
    liquidGlass: () -> Boolean,
    onLiquidGlass: (Boolean) -> Unit,
    gaussBlur: () -> Boolean,
    onGaussBlur: (Boolean) -> Unit,
    flags: () -> Map<String, Boolean>,
    onFlag: (String, Boolean) -> Unit,
    logLevel: () -> Int,
    onLogLevel: (Int) -> Unit,
    fg: Color
) {
    // ===== 组①：底栏外观（只影响底栏本身，与负一屏动画无关）=====
    GroupTitle("底栏外观", fg)
    Card2(color = cardBg()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            SwitchRow("液态玻璃底栏", liquidGlass, onLiquidGlass, fg)
            SwitchRow("高斯模糊", gaussBlur, onGaussBlur, fg)
        }
    }
    Spacer(Modifier.height(6.dp))
    BasicText(
        "关掉液态玻璃底栏 ⇒ 换回 MIUIx 默认底栏；关掉高斯模糊 ⇒ 底栏不再模糊背后内容（变一层轻白），" +
            "但折射/高光/阴影等液态玻璃效果仍在。",
        modifier = Modifier.padding(horizontal = 8.dp),
        style = TextStyle(fg.copy(alpha = 0.6f), 12.sp)
    )

    // ===== 组②③：动画行为 / 遮罩与修复 =====
    //   开关本身一个字都没改：`flags()` 读快照、`onFlag(key, v)` 内部照旧 `cfg.set(key, v)`。
    //   每组之间 20dp 间距（组标题由 GroupTitle 统一给 8dp 左缩进 + 上下留白）。
    SWITCH_GROUPS.forEach { group ->
        Spacer(Modifier.height(20.dp))
        GroupTitle(group.title, fg)
        Card2(color = cardBg()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                group.items.forEach { (key, title, _) ->
                    SwitchRow(title, { flags()[key] ?: false }, { v -> onFlag(key, v) }, fg)
                }
            }
        }
    }

    // ===== 组④：日志（★ 2026-10-04 新增）=====
    //   三选一，级别语义与显示名照 HyperCeiler：
    //     LogLevelManager.kt:29-31  LEVEL_DISABLED=0 / LEVEL_ERROR_ONLY=1("General") / LEVEL_VERBOSE=2("Detailed")
    //     LogLevelManager.kt:62-69  logLevelDesc() 的三个名字
    //   ★ 它只决定"hook 侧要不要把日志**推给 App**"；hook 侧文件日志 ANIMV4-P1.txt 不受影响。
    Spacer(Modifier.height(20.dp))
    GroupTitle("日志", fg)
    Card2(color = cardBg()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            ChoiceRow("关闭（不推送日志）", logLevel() == LogStore.LEVEL_DISABLED, { onLogLevel(LogStore.LEVEL_DISABLED) }, fg)
            ChoiceRow("常规（只关键行）", logLevel() == LogStore.LEVEL_GENERAL, { onLogLevel(LogStore.LEVEL_GENERAL) }, fg)
            ChoiceRow("详细（全部事件）", logLevel() == LogStore.LEVEL_VERBOSE, { onLogLevel(LogStore.LEVEL_VERBOSE) }, fg)
        }
    }
    Spacer(Modifier.height(6.dp))
    BasicText(
        "当前级别：${LogStore.levelTitle(logLevel())}。" +
            "级别只影响「推给 App 的日志」（本机不需要存储权限，日志走推送桥落到 App 私有目录 " +
            "filesDir/logs/）；hook 侧文件 ANIMV4-P1.txt 的落盘行为与此无关。" +
            "关闭级别后 App 里两档日志都会停止增长。",
        modifier = Modifier.padding(horizontal = 8.dp),
        style = TextStyle(fg.copy(alpha = 0.6f), 12.sp)
    )
}

/**
 * 三选一的一行（照 HyperCeiler 设置里的级别单选）。
 *
 * <p>不用 material 的 RadioButton（本模块**不加依赖**，硬约束），用一个文本勾号；
 * 与主页卡片"只有一个色点会变"的克制风格一致。
 */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit, fg: Color) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(label, style = TextStyle(fg, 16.sp))
        Spacer(Modifier.weight(1f))
        BasicText(
            if (selected) "✓ 已选" else "",
            style = TextStyle(fg.copy(alpha = 0.7f), 14.sp)
        )
    }
}

/**
 * ★ 2026-10-04 日志页（照 HyperCeiler 的 `LogViewerActivity` + `LogPagerAdapter` + `LogListFragment`）。
 *
 * <h3>照抄了什么</h3>
 * <ul>
 *   <li><b>两个页签</b>：`LogPagerAdapter.java:41-44` 的 `getItemCount()==2` ⇒ 我们也是两档
 *       （常规 / 详细）。⚠️ 差异：HyperCeiler 那两个页签是「App 日志 / Xposed 日志」
 *       （ `LogViewerActivity.java:196` `module = (position == 0) ? "App" : "Xposed"`），
 *       **级别**是另一个筛选器（`LogLevelFilter`）。我们的数据源只有一条（推送桥）
 *       ⇒ 按 Lead 的设计把两个页签定为**两个级别**，与两档文件一一对应。</li>
 *   <li><b>倒序列表 + 手动滚动（不做自动滚底）</b>：证据是 `LogDao.java:50,86` 的
 *       `ORDER BY timestamp DESC LIMIT 1000`（新→旧），且整个 log 目录
 *       `grep -r "scrollToPosition|smoothScroll|stackFromEnd|reverseLayout"` **零命中**
 *       ⇒ HyperCeiler 根本没有自动滚底，它是"最新在最上面"。我们照抄倒序。
 *       <br>⚠️ <b>2026-10-03 已被下方 LSPosed 一节取代</b>：现在是**正序**（最旧在上、最新在下）。</li>
 *   <li><b>清空当前档</b>：`LogViewerActivity.java:280-301` 的 `clearCurrentLogs()`（带确认）。
 *       我们无 material 依赖 ⇒ 用"再点一次确认"代替 `AlertDialog`。
 *       <br>⚠️ <b>2026-10-03 已被下方 LSPosed 一节取代</b>：现在是**点一下立即清空**（无确认）。</li>
 *   <li><b>导出</b>：HyperCeiler 走 SAF 存 zip + 分享（`LogViewerActivity.java:304-374`）；
 *       我们**没有存储权限、也不加依赖** ⇒ 按 Lead 的设计"复制到剪贴板"。
 *       复制顺序照它的**导出**顺序（`LogDao.java:89-90` `ORDER BY timestamp ASC`，时间正序），
 *       而不是列表的倒序。</li>
 *   <li><b>空态提示</b>：`LogListFragment.java:215-235` 的 `showState(STATE_EMPTY)` + 文案分支。</li>
 * </ul>
 *
 * <h3>★ 2026-10-03 改照 <b>LSPosed</b>（用户要「LSP 同款」的这三个操作）</h3>
 * 源码已 clone 到 `/tmp/lsp`（`git clone --depth 1 https://github.com/LSPosed/LSPosed.git`），
 * 日志页 = `app/src/main/java/org/lsposed/manager/ui/fragment/LogsFragment.java`。逐条：
 * <ul>
 *   <li><b>列表顺序</b>：`LogFragment.LogAdaptor.fullRefresh()`（`LogsFragment.java:214-226`）只是
 *       `br.lines().collect(toList())` —— **文件原序 = 时间正序（最旧在上、最新在下）**。
 *       ⇒ 我们把呈现层改为 `lines.asReversed()`（`LogStore.read` 给的是倒序）。
 *       ⚠️ **只改呈现**，`LogStore` 存储/裁剪/接收一行不动。</li>
 *   <li><b>滚到顶部 / 滚到底部</b>：`LogsFragment.java:273-290`，两段式 —— 离目标**超过**
 *       `SCROLL_THRESHOLD = 500`（`:179`）就**瞬移**（`scrollToPosition`），否则**平滑滚**
 *       （`smoothScrollToPosition`）。到顶 = 索引 `0`；到底 = `max(itemCount - 1, 0)`。
 *       我们照抄这个 500 阈值（见 `LOG_SCROLL_THRESHOLD` / `LazyListState.lsposedScroll`）。</li>
 *   <li><b>入口形态（⚠️ 与用户口述不同，如实记录）</b>：LSPosed 用的是**顶栏溢出菜单**，
 *       **不是浮动按钮** —— `app/src/main/res/menu/menu_logs.xml:22-45` 里
 *       `menu_scroll_top` / `menu_scroll_down` / `menu_clear` 三条全是 `showAsAction="never"`
 *       （进溢出菜单）、且**没有 icon**；`:297-313` 的 `setOptionsItemSelectListener` 分发。
 *       ⇒ 本模块没有菜单/溢出栏可挂（顶栏只有「‹ 返回 / 立即清空 / 复制」三个文字动作），
 *       按用户明确要求做成**右下角竖向叠放的两个圆形浮动按钮**（↑ 到顶 / ↓ 到底）。</li>
 *   <li><b>清空：点一下立即生效，无二次确认</b>：`LogsFragment.java:303-311` —— 点 `menu_clear`
 *       直接 `ConfigManager.clearLogs(verbose)`，成功就 `showHint(R.string.logs_cleared)`
 *       （`strings.xml:96-98`：`menuClearLog`="Clear log now"、`logs_cleared`="Log successfully cleared."）
 *       + `adaptor.fullRefresh()`。**全文件没有 AlertDialog / 任何确认框**（`grep -n "AlertDialog\|Dialog\|confirm"`
 *       零命中）⇒ 我们把原来的「再点一次确认」删掉，点一下立即清空，并照它给一个 Toast 提示。</li>
 *   <li><b>自动跟随（tail）⚠️ 源码里没有</b>：`LogsFragment` 的刷新只有三处 —— 建视图时一次
 *       （`:269`）、下拉刷新（`setOnRefreshListener(adaptor::fullRefresh)`，`:262`）、清空后一次（`:306`）。
 *       它是**纯手动**视图，没有任何"新日志到达 ⇒ 滚到底"的代码，也没有 FAB/贴底判定。
 *       ⇒ 用户明确要求的"贴底才跟随、上翻就不跟随"**按已知行为自行实现**（不是抄来的），
 *       判据：`autoFollow` + `atBottom`（见下）。</li>
 * </ul>
 */
@Composable
private fun LogPage(logLevel: Int, fg: Color, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(LogStore.LEVEL_GENERAL) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // ★ 读 LogStore.version（Compose state）⇒ 每收到一批推送 / 每清空一次都会自动重读文件。
    val ver = LogStore.version.intValue

    // ===== ★ 2026-10-03 LSPosed 同款交互所需的四个状态 =====
    // ① 呈现顺序 = **时间正序**（最旧在上、最新在下），照 LSPosed `LogsFragment.java:214-226` 的文件原序。
    //    ⚠️ `LogStore.read` 给的是**倒序**（`LogStore.kt:280-293`，照 HyperCeiler 的 ORDER BY DESC），
    //    这里只在呈现层反转一次；`LogStore` 的存储/裁剪/接收逻辑**一行不动**。
    val display = remember(lines) { lines.asReversed() }
    // ② 列表状态：两个滚动按钮与「自动跟随」都要它。
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // ③ autoFollow = 用户当前是否「贴底」。贴底时新日志到达保持贴底；用户往上翻就停止自动跳。
    var autoFollow by remember { mutableStateOf(true) }
    // ④ programmatic：区分「我们自己的滚动」与「用户手势」，否则自动跟随会把自己关掉。
    var programmatic by remember { mutableStateOf(false) }
    //    上一轮读的是哪个页签（用来判断"切页签 ⇒ 无条件回到底部"）。
    var shownTab by remember { mutableIntStateOf(tab) }

    LaunchedEffect(tab, ver) {
        loading = true
        val loaded = withContext(Dispatchers.IO) { LogStore.read(ctx, tab) }
        lines = loaded
        loading = false
        val tabChanged = tab != shownTab
        shownTab = tab
        if (loaded.isEmpty()) {
            // 空档 / 刚清空 ⇒ 回到「跟随」状态，下一批日志照样贴底。
            autoFollow = true
        } else if (tabChanged || autoFollow) {
            // 初始位置 = **底部（最新）**：打开日志页、切页签、以及「在跟随中」收到新日志时。
            programmatic = true
            listState.scrollToItem(loaded.size - 1)
            programmatic = false
            autoFollow = true
        }
    }

    // ★ 「自动跟随」的开关：贴底 ⇒ 跟随；用户滚动且不在底部 ⇒ 停止跟随。
    //   atBottom 从布局信息推导（列表正序 ⇒ 底部 = 最后一项可见且贴住视口下沿）。
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || (last.index == info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset)
        }
    }
    LaunchedEffect(listState) {
        // 注意：只在 scrolling 时才因"不在底部"关闭跟随 —— 新日志把列表撑长时不带 scrolling，
        // 所以「内容变长」本身不会误关掉跟随。
        snapshotFlow { Triple(listState.isScrollInProgress, atBottom, programmatic) }
            .collect { (scrolling, bottom, prog) ->
                if (prog) return@collect
                if (bottom) autoFollow = true
                else if (scrolling) autoFollow = false
            }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(pageBg())   // ★ 与其它页同一个底色（根 Box 里那层），别让日志页露系统背景
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        // ===== 顶部工具条：返回 / 清空当前档 / 复制 =====
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "‹ 返回",
                modifier = Modifier.clickable { onBack() }.padding(vertical = 6.dp, horizontal = 2.dp),
                style = TextStyle(fg, 16.sp)
            )
            Spacer(Modifier.weight(1f))
            BasicText(
                "立即清空",
                modifier = Modifier
                    .clickable {
                        // ★ 2026-10-03 LSPosed 同款：**点一下立即清空当前页签**，没有二次确认。
                        //   依据 `LogsFragment.java:303-311`：点 menu_clear 直接 `clearLogs(verbose)`，
                        //   成功给一个 hint（`strings.xml:96-98` "Clear log now" / "Log successfully cleared."）
                        //   然后 `fullRefresh()`；LSPosed 日志页**全文件没有任何 AlertDialog/确认框**。
                        //   `LogStore.clear` 内部会 bump 版本 ⇒ 上面的 LaunchedEffect 重读 ⇒ 列表变空态。
                        val ok = LogStore.clear(ctx, tab)
                        android.widget.Toast.makeText(
                            ctx,
                            if (ok) "已清空「${LogStore.levelTitle(tab)}」日志" else "清空失败",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    .padding(vertical = 6.dp, horizontal = 8.dp),
                style = TextStyle(fg, 15.sp)
            )
            BasicText(
                "导出",
                modifier = Modifier
                    .clickable {
                        val text = lines.asReversed().joinToString("\n")
                        val ts = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
                            .format(java.util.Date())
                        val name = "ANIMV4-" + LogStore.levelTitle(tab) + "-" + ts + ".txt"
                        val where = if (text.isEmpty()) null else LogStore.exportToDownloads(ctx, text, name)
                        android.widget.Toast.makeText(
                            ctx,
                            if (where != null) "已导出 ${lines.size} 行 ⇒ $where" else "没有可导出的日志",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    .padding(vertical = 6.dp, horizontal = 8.dp),
                style = TextStyle(fg, 15.sp)
            )
            BasicText(
                "复制",
                modifier = Modifier
                    .clickable {
                        // 时间正序导出（照 HyperCeiler 的导出顺序 LogDao.java:89-90）
                        val text = lines.asReversed().joinToString("\n")
                        val ok = text.isNotEmpty() && LogStore.copyToClipboard(ctx, text)
                        android.widget.Toast.makeText(
                            ctx,
                            if (ok) "已复制 ${lines.size} 行（时间正序）" else "没有可复制的日志",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    .padding(vertical = 6.dp, horizontal = 8.dp),
                style = TextStyle(fg, 15.sp)
            )
        }

        Spacer(Modifier.height(6.dp))
        BasicText("日志", style = TextStyle(fg, 34.sp))
        Spacer(Modifier.height(10.dp))

        // ===== 两个页签：常规 / 详细（对应级别 1 / 2）=====
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TabText("常规", tab == LogStore.LEVEL_GENERAL, fg) { tab = LogStore.LEVEL_GENERAL }
            Spacer(Modifier.width(18.dp))
            TabText("详细", tab == LogStore.LEVEL_VERBOSE, fg) { tab = LogStore.LEVEL_VERBOSE }
            Spacer(Modifier.weight(1f))
            BasicText(
                if (loading) "读取中…" else "${lines.size} 行",
                style = TextStyle(fg.copy(alpha = 0.6f), 13.sp)
            )
        }
        Spacer(Modifier.height(4.dp))
        BasicText(
            "当前级别：${LogStore.levelTitle(logLevel)}（在「设置 → 日志」里改）",
            style = TextStyle(fg.copy(alpha = 0.6f), 12.sp)
        )
        Spacer(Modifier.height(10.dp))

        // ★ 2026-10-03：列表区改成 Box，好在右下角叠两个浮动按钮（列表本体一行结构没变）。
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (display.isEmpty()) {
                // 空态：照 LogListFragment.java:220-233 的三分支思路，这里给两句最可能的原因。
                // ⚠️ 文案风格原样保留（本次只动交互，不动空态）。
                BasicText(
                    if (tab == LogStore.LEVEL_VERBOSE && logLevel != LogStore.LEVEL_VERBOSE) {
                        "详细档还没有内容。\n" +
                            "「详细」收集全部事件，需要先把日志级别设为「详细（全部事件）」（设置 → 日志）；" +
                            "当前级别是「${LogStore.levelTitle(logLevel)}」，只推关键行。"
                    } else if (logLevel == LogStore.LEVEL_DISABLED) {
                        "日志级别当前是「关闭」⇒ hook 侧一条都不推。\n" +
                            "请到「设置 → 日志」选「常规」或「详细」，然后滑出/返回一次负一屏再回来看。"
                    } else {
                        "这一档还没有日志。\n" +
                            "日志由负一屏进程推来：滑出一次负一屏、再返回桌面，就会产生桥/状态行；" +
                            "主页卡片是绿的说明推送通道是通的（回到主页点一下卡片可重新检测）。"
                    },
                    style = TextStyle(fg.copy(alpha = 0.6f), 13.sp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    overscrollEffect = null,
                    modifier = Modifier.fillMaxSize().pageScrollModifiers(),
                    // 底部留出两个浮动按钮的高度，免得最后一行被按钮压住。
                    contentPadding = PaddingValues(bottom = 136.dp)
                ) {
                    // ★ 正序：最旧在最上面、最新在最下面（= LSPosed 的呈现方式，
                    //   `LogsFragment.java:214-226` 读文件原序；"到底部"= 最新一条）。
                    items(display) { line ->
                        BasicText(
                            line,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            style = TextStyle(
                                fg.copy(alpha = 0.9f),
                                12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }
            }

            // ★★ 右下角两个圆形浮动按钮（竖向叠放）：↑ 滚到顶部（最旧） / ↓ 滚到底部（最新）。
            //    LSPosed 本体是顶栏溢出菜单（menu_logs.xml:22-45，无 icon、showAsAction="never"），
            //    我们没有菜单栏可挂 ⇒ 按用户要求做成 FAB。两条**常显**（菜单项本来也没有显隐条件）。
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.End
            ) {
                LogFab("↑", fg) {
                    scope.launch {
                        programmatic = true
                        try {
                            // 到顶 = 索引 0；距离判据照 `LogsFragment.java:275`（firstVisibleItemPosition）。
                            listState.lsposedScroll(0, listState.firstVisibleItemIndex)
                        } finally {
                            programmatic = false
                        }
                    }
                }
                LogFab("↓", fg) {
                    scope.launch {
                        programmatic = true
                        try {
                            // 到底 = max(itemCount-1, 0)；距离判据照 `LogsFragment.java:285`
                            //（itemCount - findLastVisibleItemPosition()）。
                            val end = display.size - 1
                            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                            listState.lsposedScroll(end, end - lastVisible)
                        } finally {
                            programmatic = false
                        }
                    }
                }
            }
        }
    }
}

/** ★ 2026-10-03 照 LSPosed `LogsFragment.java:179` 的 `SCROLL_THRESHOLD = 500`。 */
private const val LOG_SCROLL_THRESHOLD = 500

/**
 * ★ 2026-10-03 LSPosed 同款两段式滚动（`LogsFragment.java:273-290`）。
 *
 * LSPosed：离目标**超过** `SCROLL_THRESHOLD`（500）个 item 就 `scrollToPosition`（瞬移，不滚过
 * 上千行），否则 `smoothScrollToPosition`（平滑）。这里一一对应
 * `scrollToItem` / `animateScrollToItem`。
 *
 * @param target   目标 item 索引
 * @param distance 当前可见端到目标的**项数**距离（调用点自己算，与 LSPosed 两个调用点一致）
 */
private suspend fun LazyListState.lsposedScroll(target: Int, distance: Int) {
    if (target < 0) return
    if (distance > LOG_SCROLL_THRESHOLD) scrollToItem(target) else animateScrollToItem(target)
}

/** ★ 2026-10-03 日志页右下角的圆形浮动按钮（无新依赖：箭头用 ↑ / ↓ 字形画，不引图标库）。 */
@Composable
private fun LogFab(glyph: String, fg: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .shadow(3.dp, CircleShape)
            .clip(CircleShape)
            .background(cardBg())
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(glyph, style = TextStyle(fg.copy(alpha = 0.85f), 22.sp))
    }
}

/** 日志页的一个页签（选中 = 实心色 + 下划线）。 */
@Composable
private fun TabText(text: String, selected: Boolean, fg: Color, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        BasicText(
            text,
            style = TextStyle(
                if (selected) fg else fg.copy(alpha = 0.45f),
                if (selected) 18.sp else 16.sp
            )
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .width(if (selected) 28.dp else 0.dp)
                .height(2.dp)
                .background(if (selected) fg else Color.Transparent)
        )
    }
}

@Composable
private fun AboutPage(fg: Color, isCurrent: () -> Boolean = { true }, headerAlpha: Float = 1f) {
    val ctx = LocalContext.current

    // 流光溢彩背景**不在这里画**：它是全屏效果，画在 pager 页内会被 pager 的横向平移
    // 影响（真机实测：页内画出来完全看不见，根布局画就正常）。改由根布局统一绘制，
    // 见 `ModuleSettings()` 里按"离关于页的距离"做 alpha 渐入渐出的那一层。
    // ★ 这里必须 `fillMaxWidth()`（按内容高）而**不能** `fillMaxSize()`：
    //   本页内容是 LazyColumn 里的**单个 item**，用 fillMaxSize 会把该 item 撑满整个视口
    //   ⇒ 卡片其实只占上半部分、下面全是"被撑高"留下的空白，正是用户说的"底部空缺太大、不紧凑"。
    Column(Modifier.fillMaxWidth()) {
    // ★ 头部**不再淡出**（headerAlpha 恒为 1）：淡出后它的高度仍占着 ⇒ 滚动时出现一大片空白，
    //   用户报"空隙过大"。现在它随内容正常滚走，没有空白；**流光本身的渐隐保留**（那是用户要的）。
    Column {
    // ============ 顶部：图标 + 名称 + 版本号（构图照 HyperMusicCover 的 AboutPage）============
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.ic_about_logo),
            contentDescription = null,
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(26.dp))
        )
    }
    Spacer(Modifier.height(12.dp))
    BasicText(
        "HyperAssistant",
        modifier = Modifier.fillMaxWidth(),
        style = TextStyle(fg, 30.sp, FontWeight.Bold, textAlign = TextAlign.Center)
    )
    Spacer(Modifier.height(4.dp))
    BasicText(
        APP_VERSION,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                android.widget.Toast.makeText(
                    ctx,
                    "HyperAssistant $APP_VERSION\nlibxposed API 102 · 作用域：负一屏/桌面/SystemUI/system",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            .padding(vertical = 4.dp),
        style = TextStyle(
            fg.copy(alpha = 0.6f), 13.sp, textAlign = TextAlign.Center
        )
    )
    }   // ← 头部 Column 到此结束（图标 / 名称 / 版本）

    // ============ 卡片组（照 HMC：一组一组的信息卡，每行带 ›）============
    Spacer(Modifier.height(14.dp))
    GroupTitle("作者", fg)
    AuthorCard(fg)

    Spacer(Modifier.height(14.dp))
    GroupTitle("模块", fg)
    Card2(color = cardBg()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            AboutRow("运行机制", "打开/返回过渡交给系统 HyperViewScale 引擎，复用它的曲线与圆角", null, fg)
            AboutRow("宿主", "负一屏（com.miui.personalassistant）· system_server 放行权限", null, fg)
            AboutRow("配置", "设置页改完即时生效（广播推给负一屏，不用重装/重启）", null, fg)
        }
    }

    Spacer(Modifier.height(14.dp))
    GroupTitle("开源组件", fg)
    Card2(color = cardBg()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            AboutRow("libxposed API 102", "模块框架接口", null, fg)
            AboutRow("Jetpack Compose", "界面（AndroidX, Apache-2.0）", null, fg)
            AboutRow("MIUIx (compose-miuix-ui)", "底栏/图标控件（Apache-2.0）", null, fg)
            AboutRow("Backdrop / Shapes (Kyant0)", "液态玻璃与连续曲率圆角（Apache-2.0）", null, fg)
        }
    }

    Spacer(Modifier.height(14.dp))
    GroupTitle("致谢", fg)
    Card2(color = cardBg()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            AboutRow("HyperMusicCover", "关于页构图参考（zyl6932, AGPL-3.0）", null, fg)
            AboutRow("HyperNavBar", "底栏组件上游（Apache-2.0）", null, fg)
        }
    }
    Spacer(Modifier.height(12.dp))
    }
}

/**
 * 关于页的一行：标题 + 说明 + 右侧 `›`。
 * 有 [onClick] 时可点（照 HMC 的 `ArrowPreference` 观感，但用本项目自己的组件）。
 */
@Composable
private fun AboutRow(title: String, summary: String, onClick: (() -> Unit)?, fg: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = TextStyle(fg, 16.sp))
            Spacer(Modifier.height(2.dp))
            BasicText(summary, style = TextStyle(fg.copy(alpha = 0.6f), 13.sp))
        }
        if (onClick != null) {
            BasicText("›", style = TextStyle(fg.copy(alpha = 0.6f), 22.sp))
        }
    }
}

private fun sendSetCfg(ctx: android.content.Context, key: String, value: Boolean) {
    runCatching {
        val i = android.content.Intent("com.qianxia.HyperAssistant.SETCFG")
            .setPackage(pingTarget(ctx))
            .putExtra("k", key)
            .putExtra("v", value.toString())
        ctx.sendBroadcast(i)
    }.onFailure { /* 发不出去也不崩；hook 侧仍可用落盘值 */ }
}

/** ★ 阶段3：数值参数（int/float）推给 hook 侧 —— 与布尔开关同一条 SETCFG 通道。 */
private fun sendSetCfgValue(ctx: android.content.Context, key: String, value: Any) {
    runCatching {
        val i = android.content.Intent("com.qianxia.HyperAssistant.SETCFG")
            .setPackage(pingTarget(ctx))
            .putExtra("k", key)
            .putExtra("v", value.toString())
        ctx.sendBroadcast(i)
    }.onFailure { }
}

/** 作者 GitHub（地址是常量，改这一行即可）。 */
private const val AUTHOR_LOGIN = "xiahaor"
private const val AUTHOR_URL = "https://github.com/xiahaor"

/**
 * 「作者」卡：照 HyperCeiler 关于页的做法 —— **头像 + 名字 + `›`，点一下用系统浏览器打开 GitHub**。
 *
 * <p>头像走 `https://github.com/<login>.png?size=256`、显示名走 `api.github.com/users/<login>`；
 * 两者都在 IO 线程拉、**失败一律降级**（不显示头像、名字退回 login），绝不阻塞或让页面报错。
 */
@Composable
private fun AuthorCard(fg: Color) {
    val ctx = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val display = AUTHOR_LOGIN

    // ★ 头像用本地图（用户直接给的），不再联网拉：既避开 GitHub 404，也省掉网络与权限
    val localAvatar = painterResource(R.drawable.author_avatar)

    Card2(color = cardBg(), onClick = { uriHandler.openUri(AUTHOR_URL) }) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = localAvatar,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                BasicText(display, style = TextStyle(fg, 18.sp))
                Spacer(Modifier.height(4.dp))
                BasicText("github.com/$AUTHOR_LOGIN", style = TextStyle(fg.copy(alpha = 0.6f), 13.sp))
            }
            BasicText("›", style = TextStyle(fg.copy(alpha = 0.6f), 22.sp))
        }
    }
}
