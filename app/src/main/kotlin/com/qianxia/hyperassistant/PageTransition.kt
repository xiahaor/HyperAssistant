package com.qianxia.hyperassistant

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

/**
 * ★★★ 统一页面转场 —— **本文件是"任何页面进入/退出都必须有动画"这条规则的唯一实现处** ★★★
 *
 * <h3>规则（用户 2026-10-03 原话："怎么进入页面没有动画，以后每个都要有动画"）</h3>
 * <ul>
 *   <li><b>任何</b>会切换整屏内容的页面（子页 / 详情页 / 二级页 / 全屏浮层），
 *       进入与退出都**必须**有转场动画；</li>
 *   <li>默认（且目前唯一）的动作 = <b>推入</b>：新页从**右侧屏外**（整屏宽）滑入 + 淡入，
 *       旧页**按同样距离向左滑出**（整屏宽）⇒ 两页像一整屏被推走；</li>
 *   <li><b>禁止</b>再写 `if (xxx) { SomePage(); return }` 这类"裸切换"（瞬间替换整屏）；
 *       一律用下面的 [AnimatedPageEnter]；</li>
 *   <li>规格只有一份（[PageTransitionSpec]），任何页面不许自己另造时长/曲线/方向。</li>
 * </ul>
 *
 * <h3>为什么是 tween(300ms, FastOutSlowInEasing) 而不是复用 [PagerNavigationSpringSpec]</h3>
 * 项目里既有的唯一动画规格是 `PagerNavigationSpringSpec`（`PagerNavigationSpring.kt:20-24`）：
 * `SpringSpec<Float>`、`stiffness=322.2`、`visibilityThreshold=0.5f`，它是**距离驱动**的
 * ——"翻得远就快、翻得近就慢"，这是底栏 pager 的正确语义，也是**硬约束里不许动的**那一个。
 * 页面推入需要的是**时间驱动**的转场：进出两条动画必须同时开始、同时结束（否则会露出底页
 * 与子页之间的缝），而 spring 的时长由位移距离决定 ⇒ 进出不可能对齐，且把它从
 * `SpringSpec<Float>` 变成 `SpringSpec<IntOffset>` 本身就等于新造一个规格。
 * 所以这里用一份共享的 tween：`300ms`（任务书给的 280–320ms 区间取中值）+
 * `FastOutSlowInEasing`（任务书明确允许的标准快出慢入曲线，与 MIUIx/HMC 观感一致）。
 *
 * <h3>怎么用（以后每个新页面都照这个抄）</h3>
 * ```kotlin
 * AnimatedPageEnter(
 *     pageKey = if (showDetail) "page:detail" else null,   // null = 根页；非 null = 子页 key
 *     modifier = Modifier.fillMaxSize(),
 *     root = { RootPage() },                                // 底页（tab 页 / 主页）
 *     page = { key -> DetailPage() },                       // 子页（key 用来区分多个子页）
 * )
 * ```
 * 子页**不需要**自己写任何动画；进出动画、时长、曲线、返回键联动都在这里。
 *
 * @see AnimatedPageEnter
 */
internal object PageTransitionSpec {

    /**
     * 转场时长（毫秒）。进出**共用同一个值** ⇒ 两条动画同起同止，切到一半不会露出背景。
     * 任务书给的区间是 280–320ms；2026-10-04 从 280 调到 **300**（区间中值），
     * 让整屏宽的位移在肉眼上"看得出是在滑"，而不是一帧到位。
     */
    const val DurationMs = 300

    /** 转场曲线：标准快出慢入（任务书允许；MIUI/HyperOS 的推入也是这个形状）。 */
    val Easing: Easing = FastOutSlowInEasing

    /**
     * 底页（root）在子页推入时的**反向位移比例**：**1.0 = 整屏宽**。
     *
     * ★ 2026-10-04 用户第二问："这不还是硬切吗，没有滑动动画"。
     *   核实结论：包装器 `AnimatedContent(targetState = pageKey)` 是**真驱动**的
     *   （见下面 [AnimatedPageEnter] 的 `targetState`），转场确实在播；
     *   但底页只退 **1/4** 宽、子页又是**淡入**的，位移量小 ⇒ 观感接近"淡入盖住"。
     *   ⇒ 把底页的反向位移加到**整屏宽**：底页左移多少、子页就从右边顶上来多少，
     *     两页像一块整屏被整体推走，是"一眼就能看出在滑"的推入。
     *
     * ★ 几何自证（不会露背景，且比例取 1.0 时**既不重叠也不留缝**）：
     *   设进度 p∈[0,1]，底页可见到 `1 - p·f`，子页左边缘在 `1 - p`。
     *   `f = 1` ⇒ 两者**恒等** ⇒ 两页始终严丝合缝地拼满整屏（临界推入）。
     *   `1 ≥ f ≥ 0` 的任何取值都满足 `1 - p·f ≥ 1 - p`（并集铺满、只可能重叠、不可能露背景）。
     */
    const val RootShiftFraction = 1.0f

    /** 位移用的动画规格（IntOffset）；进出、底页/子页全部共用这一份。 */
    fun slide(): TweenSpec<IntOffset> = tween(durationMillis = DurationMs, easing = Easing)

    /** 透明度用的动画规格（Float）；同样只有一份。 */
    fun fade(): TweenSpec<Float> = tween(durationMillis = DurationMs, easing = Easing)

    /** 子页进入：从右边界外（整屏宽）滑到 0 + 淡入。 */
    fun enterPage(): EnterTransition =
        slideInHorizontally(animationSpec = slide()) { fullWidth -> fullWidth } +
            fadeIn(animationSpec = fade())

    /** 子页退出（返回）：滑回右边界外 + 淡出。 */
    fun exitPage(): ExitTransition =
        slideOutHorizontally(animationSpec = slide()) { fullWidth -> fullWidth } +
            fadeOut(animationSpec = fade())

    /** 底页被推走：向左滑出**整屏宽**（不淡出——它只是"被推"，淡出会露背景）。 */
    fun exitRoot(): ExitTransition =
        slideOutHorizontally(animationSpec = slide()) { fullWidth ->
            (-(fullWidth * RootShiftFraction)).toInt()
        }

    /** 返回时底页滑回：从 -整屏宽 回到 0（不淡入，保持不透明）。 */
    fun enterRoot(): EnterTransition =
        slideInHorizontally(animationSpec = slide()) { fullWidth ->
            (-(fullWidth * RootShiftFraction)).toInt()
        }
}

/**
 * `AnimatedContent` 的"根页"哨兵 key。
 *
 * ★ 刻意**不用 `null`** 当 key：`AnimatedContent` 内部拿 state 当 content key 做 map 索引，
 * 用一个私有对象比 `null` 更没有歧义（对外仍然可以用 `pageKey = null` 表示根页）。
 */
private val RootPageKey = Any()

/**
 * ★ 统一推入式页面宿主：**整屏切换只许走这里**。
 *
 * - `pageKey == null`：只显示 [root]（底页）；
 * - `pageKey != null`：显示 [page]`(pageKey)`，并按 [PageTransitionSpec] 的"推入"动作进出；
 * - 进入：子页从右侧屏外（整屏宽）滑入 + 淡入，同时底页**左退整屏宽**（推入观感）；
 * - 退出：子页向右滑出 + 淡出，同时底页滑回 0（返回手势 / 返回键同样触发这条退出动画）；
 * - 转场期间**两个页面都保持组合**（否则动画中途底页会消失、露出窗口背景），
 *   转场结束后旧页才被移除 —— 因此子页全屏时底页**不在组合里**（与改动前一致：
 *   底页不可见时就不会误吃触摸、不白渲染）。
 *
 * <h3>★ "转场到底有没有被真驱动"的核实（2026-10-04，用户质疑"还是硬切"）</h3>
 * 结论：**是真驱动**，不是"包装器存在但 targetState 没接上"。
 * 证据链（三个环节都在同一份源码里，可逐行核对）：
 * <ol>
 *   <li>状态源：`ModuleSettings.kt:689` `var showLog by remember { mutableStateOf(false) }`；
 *       唯一入口 = 主页日志卡 `onOpenLog = { showLog = true }`（`ModuleSettings.kt:857`），
 *       退出 = `showLog = false`（`ModuleSettings.kt:938` 的 `BackHandler` / 「‹ 返回」）；</li>
 *   <li>key 派生：`ModuleSettings.kt:932` `pageKey = if (showLog) LOG_PAGE_KEY else null`
 *       —— `showLog` 是 `mutableStateOf`，读它就订阅了重组；</li>
 *   <li>真参与动画：本文件 `targetState = pageKey ?: RootPageKey`（PageTransition.kt:163） ——
 *       `pageKey` 直接就是 `AnimatedContent` 的 **targetState 参数本身**，
 *       不是只把内容包在外层（那种写法才会退化成硬切）。</li>
 * </ol>
 * ⇒ `showLog` 一变，`AnimatedContent` 的目标状态就变，`transitionSpec` 必被调用，
 * 转场必然播放。用户看到"像硬切"的**唯一可归因差异是位移量**（底页只退 1/4 宽 + 子页淡入），
 * 本轮已把位移改成整屏宽（见 [PageTransitionSpec.RootShiftFraction]）。
 *
 * @param pageKey 子页标识（例如 `"page:logs"`）；`null` = 根页。同时用于区分多个子页。
 * @param modifier 宿主修饰符（整屏切换通常传 `Modifier.fillMaxSize()`）。
 * @param root 底页内容（底栏 tab 页 / 主页）。
 * @param page 子页内容；参数是当前的 [pageKey]（只有一个子页时可以忽略）。
 */
@Composable
internal fun AnimatedPageEnter(
    pageKey: Any?,
    modifier: Modifier = Modifier,
    root: @Composable () -> Unit,
    page: @Composable (key: Any) -> Unit,
) {
    AnimatedContent(
        targetState = pageKey ?: RootPageKey,
        modifier = modifier,
        transitionSpec = {
            // targetState = "要去的那一页"：不是哨兵 ⇒ 正在推入子页；是哨兵 ⇒ 正在返回底页。
            if (targetState !== RootPageKey) {
                PageTransitionSpec.enterPage() togetherWith PageTransitionSpec.exitRoot()
            } else {
                PageTransitionSpec.enterRoot() togetherWith PageTransitionSpec.exitPage()
            }
        },
        label = "页面转场",
    ) { key ->
        if (key === RootPageKey) root() else page(key)
    }
}
