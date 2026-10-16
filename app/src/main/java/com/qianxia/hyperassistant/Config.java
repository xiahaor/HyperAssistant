package com.qianxia.hyperassistant;

/**
 * 全部可调参数集中在这里。
 *
 * 第一阶段（现在）：只开 ENABLE_PROBE，采集真实调用数据。
 * 第二阶段：拿到探测日志后，按实际进度值域校准 PROGRESS_MIN/MAX_HINT，
 *          再把 ENABLE_ANIM 打开。
 */
public final class Config {

    private Config() {
    }

    // ===================== 总开关 =====================

    /**
     * 探测模式：只打日志，不改任何行为。
     *
     * ⚠️ **现在默认关掉了。** 原因：它每帧打一条 `F(float)`，我们的 tag 几秒钟就把
     * logcat 缓冲区刷满一圈，导致真正要看的日志（打开动画）还没读就被冲走 ——
     * 上一轮就是这么丢掉数据的。
     *
     * 探测已经拿到了它该给的东西，所以现在关掉：
     *   · D(float) 从不触发、F(float) 才是逐帧进度（约 60 次/段）
     *   · w() 全程 0 次调用（不能当进度源）
     *   · 进度值域就是 0..1
     *   · 打断实测成立（p=0.73 / 0.87 处接管）
     *   · 小米曲线 = SPRING_PHY，参数化 [ζ, 周期T]
     * 需要重新探测时再打开。
     */
    public static final boolean ENABLE_PROBE = false;

    /** 动画模式：真正接管插值与并行动画。校准之后再打开。 */
    public static final boolean ENABLE_ANIM = false;

    // ===================== 打开动画（点小部件 → 系统桌面那种全动画）=====================

    /**
     * 这个才是用户真正要的功能：**点负一屏的小部件打开应用时，要有系统桌面那种放大展开动画**。
     *
     * 逆向结论：`AppRoute$GeneralRoute.route()` 里是裸的 `Context.startActivity(Intent)`，
     * 全程没有 `ActivityOptions` —— 所以是硬切。
     * 而小米自己的 `miuix.transition.ActivityOptionsHelper` 就装在负一屏的 dex 里，
     * 外部零调用。我们把它接上。
     */
    public static final boolean ENABLE_OPEN_ANIM = true;

    /**
     * OS4.0.2.0 起 SystemUI 的 {@code RemoteTransitionHandlerStubImpl.shouldAbortDispatch}
     * 对 Task 级过渡一律跳过 HyperViewScale 远程过渡（"Skip handler ... for task anim"），
     * 负一屏打开应用因此退化成硬切。true = 在 SystemUI 进程里对 HyperViewScale 放行。
     * 详见 {@link SystemUIHooks}。
     */
    public static final boolean ENABLE_SYSTEMUI_ABORT_BYPASS = true;

    /** SystemUI 绕过每次生效都打一行日志（前 N 次）。 */
    public static final boolean SYSTEMUI_HOOK_VERBOSE = true;

    /**
     * `"overlay"` —— **默认**：我们自己画浮层动画（不碰启动调用，零权限依赖）
     * `"probe"`   —— 只记录工厂返回值，不改行为
     * `"apply"`   —— 用 ActivityOptions 替换启动（**已证实走不通**，留作参考）
     *
     * 为什么 apply 走不通（真机实测）：小米那套全动画是**远程动画**，
     * 控制通道是 `android:activity.animAbortListener`，该 key 需要签名级权限
     * `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS`；逐级剥离验证「只有它被拒」，
     * 去掉它启动就放行但没有动画（退化成普通启动）。负一屏没有该权限（桌面有）。
     */
    public static final String OPEN_ANIM_MODE = "apply";

    /** 浮层"卡片展开"动画总时长（毫秒）。 */
    public static final int OVERLAY_DURATION_MS = 260;

    /** 末段淡出占比（0.2 = 最后 20% 的时间用来淡出，交接给应用窗口）。 */
    public static final float OVERLAY_FADE_RATIO = 0.22f;

    /**
     * 放大倍数上限。
     *
     * 不直接放大到全屏 —— 负一屏卡片大多是 128×128 的图标，
     * 硬拉到 1080×2400 会糊得很难看。控制在 2~3 倍，
     * 配合"向屏幕中心靠拢 + 淡出"，观感是"卡片向你抬起，应用随之出现"，
     * 这更接近桌面那种感觉，也不会有糊的拉伸。
     */
    public static final float OVERLAY_MAX_SCALE = 2.6f;

    // ===================== 提权（system_server 侧）=====================

    /**
     * 是否在 system_server 里放行"远程动画"那道权限闸门。
     *
     * 逆向依据：`services.jar` 里 `SafeActivityOptions.checkPermissions` 会调
     * **小米自己加的** `HyperViewScaleTransitionStub.isHyperViewScaleRemote(...)`，
     * 命中就要求 `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS` —— 桌面有、负一屏没有。
     *
     * ⚠️ 这是**唯一能拿到与桌面完全一致动画**的路，但也是本项目里风险最高的一处：
     *    system_server 里出错可能导致软重启。放行条件已收窄到
     *    「一个权限名 + 一个 appId」，其余一律不碰。
     *    如果开机异常，去 LSPosed 里取消勾选本模块（或取消 `android` 作用域）即可恢复。
     */
    public static boolean ENABLE_SYSTEM_SERVER_HOOK = true;

    /** system_server 侧的放行方式：`permission`（主）、`predicate`（备）。 */
    public static final String SS_BYPASS_MODE = "permission";

    /** 要放行的权限名。 */
    public static final String REMOTE_ANIM_PERMISSION =
            "android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS";

    /** 被放行的应用的 appId（负一屏 = 10143，从 `u0a143` 得来）。 */
    public static final int TARGET_APP_ID = 10143;

    // ===================== HyperViewScale 官方配置注入 =====================

    /**
     * 是否把负一屏**正式加进小米的 HyperViewScale 配置表**（system_server 侧）。
     *
     * 这是本轮深挖最重要的发现：官方 `HyperViewScaleConfigManager.preBindApplication()`
     * 会给配置表里的包做两件事 ——
     *   1. `mAllowedUids.add(uid)` → `isHyperViewScaleAllowedUid()` 放行
     *      → **远程动画权限检查从根上消失**，不再需要 {@link #ENABLE_SYSTEM_SERVER_HOOK}
     *   2. `appInfo.metaData["HyperViewScale"] = pkg` → 应用侧
     *      `HyperViewScaleManager` 拿到 corner / bgColor / matchParent / 支持的视图类
     *      → **白边黑边、大卡片不生效** 的根因
     *
     * 本地出厂配置只列了 5 个第三方包（bilibili/爱奇艺/拼多多/小红书/腾讯视频），
     * 负一屏不在里面 —— 这就是它一直没有动画的制度性原因。
     * 详情见 `D:\dsh\HyperOS4-负一屏-深挖-HyperViewScale.md`。
     */
    public static final boolean ENABLE_HVS_CONFIG_INJECT = true;

    /**
     * 是否安装 HyperViewScale 内部打点（负一屏进程侧）。
     *
     * 覆盖三件事：
     *   · `parseBundle` 前后的 bundle 内容 + 用小米自己的配置补齐缺失键
     *   · `startAnimation` / `mergeAnimation` / `onFinish` —— 判断**关闭动画**有没有被调用
     *   · 源视图看护 —— 关闭动画要求源视图一直 attached，被回收就只能 alpha 淡出
     */
    public static final boolean ENABLE_HVS_DIAG = false;

    /** 是否打印每次 `parseBundle` 的完整 bundle（内容较多，排障时开）。 */
    public static final boolean LOG_HVS_VERBOSE = false;

    /**
     * 是否调用**小米自己的启动桥**
     * `HyperViewScaleRemoteStub.startActivity(Activity, Context, Bundle, Intent) -> Bundle`。
     *
     * 这是"退出时没有过渡动画"的解药。原因：
     *   · 关闭过渡由 system_server 的
     *     `ActivityRecord.finishIfPossible() / handleAppDied()`
     *     → `HyperViewScaleTransitionStub.requestViewScaleRemoteClose(TransitionController, Intent, WindowContainer)`
     *     发起，它读的是**目标 Activity 自己 Intent 上的 `view_scale_open` extra**；
     *   · 而这个 extra 只有 `HyperViewScaleRemoteManager.modifyNextIntent()` 会写，
     *     它只被 `startActivity(...)` 调用；
     *   · **framework.jar 里没有任何地方调用它**（含 range 形式的 invoke 全量扫过）。
     * 所以那个 extra 一直没人写 → 系统不知道怎么"飞回去" → 退出是硬切。
     *
     * 关掉它不会有副作用（只是回到"只有打开动画"的现状），所以是安全开关。
     */
    public static final boolean USE_MIUI_START_ACTIVITY_BRIDGE = true;

    /**
     * 填充底色策略，用于兜住"查不到配置"的情况。
     *
     * `HyperViewScaleRemoteManager.getBackgroundColor(view, fillColorType)` 只认两种：
     *   `1` = `getColorWithFallback`：父容器 → DecorView，并且要求颜色**和深浅色模式匹配**
     *         （`isColorMatchUiMode`，不匹配就记 "mismatch ui mode" 再换）
     *   `2` = `getColorDirectOrFallback`：父容器 → DecorView，不查深浅色
     *
     * 实测：点击时 `ctx` 不是 Activity，`getBgColor()` 恒返回 0，而 0 会让官方走
     * "invalid fillColorType" 分支 → **截图后面垫的颜色不对 = 白边/黑边**。
     * 所以这里给一个一定会被接受的类型。默认 `2`（不挑深浅色，最不容易选错）。
     */
    public static final int HVS_DEFAULT_BG_COLOR_TYPE = 2;

    /**
     * 是否安装 system_server 侧的关闭过渡打点。
     *
     * 盯 `com.android.server.wm.HyperViewScaleTransitionImpl`：
     *   · `isHyperViewScaleRemote` —— 官方白名单到底认没认负一屏（权限问题的最终判据）
     *   · `requestViewScaleRemoteClose` —— 退出动画入口有没有被 `ActivityRecord.finishIfPossible()` 调到
     *   · `requestCloseTransitionIfNeeded` —— 有没有被 `tc.isCollecting()` 挡掉
     */
    public static final boolean ENABLE_HVS_SERVER_DIAG = true;

    /**
     * 是否把官方算出来的"填充底色"强制改成透明。
     *
     * 实测（13/13 次）：`HyperViewScaleRemoteManager.getBackgroundColor(view, 2)`
     * 对负一屏的小部件返回 **纯黑不透明 `#FF000000`** —— 因为点击源视图 `bg=false`，
     * 往上找父容器也没有背景，官方兜底给 `Color.BLACK`。
     * 这个颜色会在 `drawViewOnCanvas` 里 `canvas.drawColor(...)` 铺满遮罩层，
     * 位图画上去之后**圆角外那一圈就露出填充色**：
     *   · 解析成黑 → 黑边
     *   · 解析成白 → 白边
     * 改成 0（全透明）后 `drawColor(0)` 是空操作，圆角外直接透出负一屏自己的背景。
     *
     * 位图缺失时这个颜色是"纯色占位"，但实测 13/13 位图都拿到了，所以无风险。
     */
    public static final boolean HVS_FORCE_TRANSPARENT_FILL = false;

    /**
     * ★★ 重画遮罩层 buffer —— 白框/黑屏的**真正修法**。
     *
     * 实测几何（图标场景，ViewScaleAnimInfo 打印出来的真值）：
     * <pre>
     *   viewRect   = Rect(629,1320 - 757,1448)   图标 128x128
     *   realScale  = 0.10666667 = 128/1200
     *   clipRect   = Rect(0,0 - 1200,1200)       = viewRect 尺寸 / realScale
     *   setMaskSurface 的 rect = Rect(0,0 - 1200,2608)   ← buffer 是整屏
     * </pre>
     * 遮罩层在 `initLeashChild` 里 `setPosition(0,0)` + `setScale(realScale)`，
     * 所以 buffer 里对应视图矩形的那块可见区是 `(0,0)-(1200,1200)`（除以 realScale
     * 正好落到图标的 128x128）。而 `drawViewOnCanvas` 是
     * `drawBitmap(bmp, 0, 0, paint)` —— **按原尺寸画在左上角**，
     * 128x128 的位图塞进 1200x1200 的可见区，**剩下 1072x1200 全是铺色**：
     *   · 铺浅色 = 白框/矩形白边
     *   · 铺深色（深色图标边缘色实测 #FF0B0B16，官方主色甚至直接 #FF000000）= 黑屏+只剩图标
     * 所以"改铺色"永远治不好，白色黑色都试过了 —— 必须让位图**铺满**那块可见区。
     *
     * 打开后：hook `setMaskSurface`，原始画完再自己重画一遍
     * （清透明 + 位图等比铺满）。图标四角本来就透明（实测四角=[0,0,0,0]），
     * 圆角外自然什么都没有。
     */
    public static final boolean HVS_FIX_MASK_BUFFER = true;

    /**
     * ★ 拔掉 `_letterbox-bg` 整屏色块。
     *
     * `initLeashChild` 里建了这么一层：
     * <pre>
     *   if (letterboxBounds 非空 && backgroundColor != 0 && otherLeash.isValid()) {
     *       mLetterboxBgLayer = ...setEffectLayer().setParent(leash).setHidden(false).build();
     *       setColor(mLetterboxBgLayer, ARGB(backgroundColor));
     *       setLayer(mLetterboxBgLayer, -1);   // 压到最底
     *       show(mLetterboxBgLayer);
     *   }
     * </pre>
     * 颜色来自 `extractLetterboxColor(change)` = **应用自己的 letterbox 底色**。
     * 它是整屏一层不透明色块，压在应用窗口下面；动画期间应用窗口还没铺满屏，
     * 露出来的就是它 —— 深色应用 = 黑屏。
     * 关键是那个 `backgroundColor != 0` 判断：让它返回 0，这层**根本不会被创建**。
     */
    public static final boolean HVS_LETTERBOX_NO_BG = true;

    /**
     * ★★ 方案 B 第一步：用我们自己的 `RemoteTransition` 替换 MIUI 的。
     *
     * 通过 `OwnRemoteTransition.build(...)` 造一个
     * `android.window.RemoteTransition(我们自己的 IRemoteTransition)`，
     * 塞回 `view_scale_open` extra + options 的 `android:activity.remoteTransition`。
     *
     * 本步**只替换、不改行为**：四个方法（startAnimation / mergeAnimation /
     * onTransitionConsumed / takeOverAnimation）全部原样转发给 MIUI 的原对象，只加日志。
     * 验收标准：开/关动画与替换前完全一致，且日志里出现
     * `[自有过渡] ★ 系统调我们的 startAnimation`。
     * 证明之后：B2 换掉 startAnimation（不再建 mask/letterbox/blur，自己驱动 leash），
     * B3 换掉 mergeAnimation（真正的并行可打断 + 上滑返回）。
     */
    public static final boolean HVS_OWN_REMOTE_TRANSITION = true;

    /**
     * ★★ 方案 B 第二步（B2）：**自己驱动 leash**，MIUI 的 mask / letterbox / blur 一律不建。
     *
     * ⚠ 2026-09-26 16:15 起**默认关闭**：60fps 逐帧实测证明它「没有视觉效果」——
     * 应用窗口是一帧内突变、没有 355ms 渐变（源视图矩形是对的：Rect(629,1469-757,1597)，
     * 起始缩放 0.1067 = 实测 realScale，全都对，就是不动）。
     * 原因：MIUI 那条路会先用 `handleAnimLeash` +
     * `HyperViewScaleRemoteManager.setLeash(info, animLeash, txn, animInfo, ...)`
     * 把 leash 建出来、挂对父层、设好裁剪；我们只对 `parseTransitionChanges` 给的
     * 原始 `animLeash` 写 transform，而那一层**不是被合成的那一层** → 写了等于没写。
     * 要真正接管画面，得先把 `ViewScaleAnimInfo` 构造器（第 4 个参数）逆向出来，
     * 再复刻 `handleAnimLeash` 的语义；在那之前让 MIUI 驱动动画（观感正常），
     * 我们只负责返回/打断路径（B1 的代理已经能收到 `mergeAnimation`）。
     *
     * 打开：`scale = viewRect.w / endBounds.w → 1.0`（统一缩放）、中心从源视图中心
     * 移到窗口中心、圆角 `viewInfo.getRadius() → 窗口圆角`，用
     * `OPEN_POS_EASE = spring(0.229, 1.10)`。
     * 合并/打断：`mergeAnimation` 一起接管（MIUI 的状态机在我们接管 startAnimation 后
     * 是空的，转发过去不会收尾 → 会卡过渡），有 TO_BACK 就用
     * `CLOSE_POS_EASE = spring(0.29, 0.95)` 从当前进度退回源视图处。
     *
     * 接管失败（拿不到 leash/矩形等）会自动**回退转发给 MIUI**，功能不会退化；
     * 另有 3 秒超时强制收尾，避免任何情况下卡住转场。
     */
    public static final boolean OUR_START_ANIMATION = false;

    /**
     * ⚠ 测试脚手架：源视图在屏幕外时伪造一个屏幕内的起点矩形。
     *
     * 负一屏被 launcher 停在旁边时它的内容整体在屏幕外，`ViewScaleAnimInfo.isValid=false`，
     * MIUI 直接走纯 alpha 降级（不建遮罩、不做缩放动画）→ 全自动测试**永远测不到真动画**
     * （实测 8 次触发全 false，`[HVS遮罩] 重画`/`强制反向` 一次没触发）。
     * 打开后 MIUI 会走真实路径，于是白框/黑屏、动画形状、反向都能自动验证。
     *
     * 代价：起点是编的，只能验「机制与观感缺陷」，不能验「图标位置对不对」。
     * 交付前要关掉。
     */
    public static final boolean HVS_FAKE_ON_SCREEN_SOURCE = false;

    /**
     * 测试触发器（**只是测试脚手架**）。
     *
     * 这台机器上 `adb shell input tap` 被拒（`SecurityException: requires INJECT_EVENTS`），
     * `sendevent` 也被拒（SELinux enforcing，adb shell 没有 su），所以点不了屏幕，
     * 每一轮验证都得等人手点。打开这个开关后：负一屏进程里起一个守护线程，
     * 看到 `files/hvs/click` 这类标记文件就对图标/小部件 `performClick()` ——
     * 走的是完全真实的点击链路（`AppGrid$2.onClick` → `AppRoute.route` → 官方 HVS 桥）。
     *
     * <pre>
     *   D=/sdcard/Android/data/com.miui.personalassistant/files/hvs
     *   adb shell touch $D/click          # 点第一个图标
     *   adb shell touch $D/click_widget   # 点第一个小部件
     *   adb shell touch $D/seq2           # 点一个 → 600ms 后再点一个
     *   adb shell touch $D/click:2        # 点第 3 个候选
     * </pre>
     */
    public static final boolean ENABLE_TEST_TRIGGER = false;

    /**
     * 打断守卫窗口（毫秒）。
     *
     * 实测打断场景的时序（15:32:53）：
     * <pre>
     *   53.815 finishCurrentAnimation()      ← 点了新图标，MIUI 收尾正在跑的动画
     *   53.851 mergeAnimation type=1 [TO_FRONT,TO_BACK]
     * </pre>
     * 而真的上滑是反过来的：合并先来、finish 随后。形状完全一样，只有时序能分开。
     * 没这道守卫时，打断会被误判成"上滑回桌面"→ 多推一次反向 + 多播一次浮层。
     */
    public static final long HVS_INTERRUPT_GUARD_MS = 250L;

    /**
     * 遮罩层铺底改用**位图自己的边缘色**（在重画没生效时作为退路）。
     *
     * 为什么"改透明"是错的（实测反馈）：透明以后那块区域不是"透出负一屏背景"，
     * 而是**露出后面的黑底** —— 用户看到的就是"动画有时候黑屏，只显示打开应用的图标"。
     * 白色和黑色是同一个坑（`drawViewOnCanvas` 先铺一个颜色、再把位图画在 (0,0)，
     * 位图是圆角方图盖不满整块 buffer）的两种颜色，不是两个问题。
     *
     * 取位图四条边的众数色：图标边框什么颜色就铺什么颜色，圆角外和多余部分自然接上，
     * 既不白也不黑。取不到（位图缺失/全透明）时不改，保持官方值。
     */
    public static final boolean HVS_FILL_FROM_BITMAP_EDGE = true;

    /**
     * 是否把遮罩层的"主色"也强制改成透明（**矩形白边的真凶**）。
     *
     * `drawViewOnCanvas` 的顺序是：
     * <pre>
     *   c.drawColor(vi.getViewBackgroundColor());          // 第 5 步已改透明
     *   c.drawColor(getViewDominantColor(vi), SRC_OVER);   // ← 这一层没堵住，就是白边来源
     *   c.drawBitmap(bitmap, 0, 0, paint);
     * </pre>
     * `getViewDominantColor` 在位图存在、且 `ViewInfo.viewColor` 没被设置时，
     * 会走 `ColorPickerUtils.pickColorFromBitmap` 取**图标主色**。
     * 实测拉下来的 8 张位图里，`bitmap_8` 有 72.9% 的不透明像素接近纯白、
     * `bitmap_6` 12.6% —— 这些图标的主色就是白色，
     * 于是遮罩层被整块铺成**白色矩形**，位图圆角之外露出白色 = 「矩形白边」。
     *
     * 只在位图存在时改；位图缺失时这个颜色是唯一可见填充，不能动。
     */
    public static final boolean HVS_FORCE_TRANSPARENT_DOMINANT = false;

    /**
     * `bundle_view_visible`：动画期间要不要把源视图隐藏。
     *
     * 官方 `performClick` 路径会写这个键，而 `setSystemView` 不写 →
     * `parseBundle` 里 `getBoolean(..., 默认)` 落到 false。
     * 实测我们的 `ViewInfo.isViewVisible()` 一直是 **false**。
     *
     * 为什么在意：源视图如果不隐藏，小部件在动画全程都留在原地，
     * **退出时"飞回去"这个动作就看不出来**（目的地本来就已经在那儿了）。
     * 桌面之所以看起来对，源视图是被藏起来的。
     */
    public static final boolean HVS_VIEW_VISIBLE = true;

    // ------------------------------------------------------------------ 第二轮修复（P0-1 / P2-7）
    //
    // 下面两个开关是把"每次启动都跑"的诊断/旧触发路径彻底关掉。
    // 已经在 2026-09-26 17:09 一起关掉的还有：HVS_SAVE_BITMAPS（每次启动把官方截的
    // 小部件位图**存成 PNG 到 /sdcard**，是启动路径上的磁盘 IO）、LOG_SOURCE_TREE
    // （每次点击打印源视图子树）、LOG_HVS_VERBOSE（全量 HVS 日志）。

    /** 每次启动挂 5 个定时回调（每个都带反射调用）只为打日志 —— 关掉。 */
    public static final boolean HVS_WATCH_SOURCE = false;

    /**
     * 旧的返场触发（窗口可见性 + 焦点监听）。
     *
     * 信号本身是抖的：实测焦点在应用盖上来时 400~1700ms 反复抖，
     * 窗口可见性只抖 250~1250ms，两者都区分不出"用户真的回来了"。
     * 现在返场只由**一条真信号**触发：官方反向失败（`reverseAnimationWhenMerge`
     * 返回 false 且那一次合并是我们判定的"上滑回桌面"）。
     */
    public static final boolean HVS_RETURN_LEGACY_TRIGGERS = false;

    /**
     * `bundle_list_cover` = [topCover, bottomCover]，按比例内缩起始矩形。
     *
     * 官方配置里第三方应用都给了具体值（如 `topCover=0.16, bottomCover=0.08`）。
     * 我们从 `getBgColor`/`getListCover` 查不到（因为点击时的 context 不是 Activity，
     * 官方 `getActivityName()` 返回空串），拿到的是 null ——
     * **null 会让起始矩形不被内缩，leash 起手就比图标大一圈**，
     * 用户看到的"图标外面套一圈框、框和图标之间有空隙"正是这个形状。
     * 所以这里显式给 [0,0]（我们的点击源就是内容本身，不需要 cover）。
     */
    public static final float[] HVS_LIST_COVER = {0f, 0f};

    /**
     * ★ 是否让 **TO_BACK（上滑回桌面 / 进最近任务）** 也反向飞回去。
     *
     * `HyperViewScaleRemoteTransition.handleMergeAnimation` 里：
     * <pre>
     *   int type = info.getType();                 // 1=OPEN 2=CLOSE 3=TO_FRONT
     *   mReverseToActivityA = &lt;拿 type 和 1/2/3 比出来的&gt;
     *   mReverseToActivityB = &lt;同上&gt;
     *   mAnimationImpl.reverseAnimationWhenMerge(binder,
     *           mReverseToActivityA, mReverseToActivityB, view, debugId);
     * </pre>
     * 实测：
     *   · 侧划返回 → merge 的 type=2    → 两个都是 true  → 正常飞回去 ✔
     *   · 上滑回桌面 → merge 的 type=1021 → 两个都是 false → 不反向 ✘
     * 而这两个值就是**调用参数**，所以直接在调用点改参数即可，完全不碰系统代码。
     */
    public static final boolean HVS_FORCE_REVERSE_ON_TOBACK = true;

    /** B1 实验：动画开始后 250ms 由我们**抢写一帧**（position(150,150)/scale 0.4/圆角 0），
     *  用来判断两件事：① 我们的写入能否生效（画面是否按我们的值变）；
     *  ② MIUI 下一帧是否会立刻覆盖我们（决定后续是每帧抢写、还是必须先停掉它的驱动）。
     *  仅测试用，平时必须 false。 */
    public static final boolean HVS_B1_TEST_WRITE = false;

    /** 第 18 轮：退出段改用【原生 SF 动画】（澎湃桌面同款引擎）。 */
    public static final boolean HVS_USE_SF_ANIM = false;

    /** B：整条过渡（打开+缩回）都由 SF 原生引擎驱动。 */
    public static final boolean HVS_SF_WHOLE = true;

    /** 缩回段是否交给 SF（false = 交回 MIUI，避免四周常黑）。 */
    public static final boolean HVS_SF_EXIT = false;

    /** 拦 goToState 把目标状态改成 OpenState（保持负一屏展开）。默认关，等探针数据确认后再开。 */
    public static final boolean HVS_KEEP_ASSIST_OPEN = false;

    /** 上滑后 700ms 把 blur 层压 0（治“一层模糊盖着”）。默认关。 */
    public static final boolean HVS_BLUR_RESET = true;

    /** 切到 OpenState 后 300ms 让负一屏内容重新可见并重绘。 */
    public static final boolean HVS_CONTENT_REDRAW = true;

    /** 仅自测：打开动画起 400ms 后自动触发缩回（模拟中途上滑）。 */
    public static final boolean HVS_TEST_AUTO_REVERSE = false;

    /** 第 112 轮：退出段（缩回）由我们驱动：280ms + smoothstep，圆角 174→36。 */
    public static final boolean HVS_OWN_EXIT = false;

    /** 动画期间把 mask 层 alpha 压在 0（不让遮罩现形）。 */
    public static final boolean HVS_MASK_ZERO = false;

    /** 动画期间把 mask/blur 保持不透明（让源组件快照盖住背后的黑）。 */
    public static final boolean HVS_KEEP_MASK = false;

    /** B 迭代：值跳变限速（测试）。 */
    public static final boolean HVS_B_CLAMP = false;

    /**
     * ★ 第 93 轮准备：**压掉这次过渡的全屏模糊**（默认 false = 保持现状，不动观感）。
     *
     * 为什么怀疑它（实测，见交接文档 6.8 节）：
     *   · 负一屏 → 应用：重复帧比例 0.37、整机采集率 21.3fps；
     *   · 桌面图标 → 应用：重复帧 0.00、采集率 58.6fps；
     *   · 负一屏进程自身 gfxinfo 中位 6ms、janky 0.86%（应用侧不慢）；
     *   · leash 写入 ~120 帧/秒、连续无空档（逻辑不慢）；
     *   · 两条路径层数增量相当（不是"层多"）。
     * 剩下唯一"每帧都很贵"的东西 = 这条路径独有的**全屏模糊效果层**（view_scale_blur，
     * blurRadius 0→200、blurAlpha 0→0.2）。
     *
     * 打开它 = 把 blurRadiusTo / blurAlphaTo / blurRatioTo 压成 0 → 过渡背景由模糊变清晰，
     * 用来验证"模糊是否拖垮合成"（判据：同一把尺子下重复帧比例 0.37 → ?）。
     * **注意：打开会改变观感 → 默认 false，必须用户点头才开。**
     */
    public static final boolean HVS_DISABLE_TRANSITION_BLUR = false;
    // ★ 2026-09-26 19:5x 关掉的依据：把用户**自己的手法**（点组件/图标 → 0.4~0.9 秒内上滑，
    //   连做几十次）录下来逐帧看，150 秒里出现 **51 段异常**（全黑 / 空白大圆角窗口 /
    //   组件位置显示应用内容），而且每一轮都伴随一次我们的「强制反向」。
    //   所以要干净地判定"这些症状是不是强制反向造成的" —— 关掉它，让用户按同一手法再抖一遍，
    //   对比异常段数。若是元凶，就重新设计"返回"，不再拿现在这个实现硬撑。

    /**
     * ★ 把 MIUI 自己的"自动收尾"推后多少毫秒（0 = 不动它）。
     *
     * 事实（栈，2026-09-26 17:43）：MIUI 在 `onFinish` 里用 Handler postDelayed ~2000ms
     * 再回调一次 `onFinish`
     * （`...$$ExternalSyntheticLambda8.run ← lambda$onFinish$4 ← onFinish`）来收尾。
     * 这次收尾一发生，系统**就不再给我们 `mergeAnimation`**：
     *   上滑延迟 1.0s → 有 merge（type=1021）→ 原生反向 ✓
     *   上滑延迟 2.0/3.0/4.0s → **完全没有 merge** → 系统当普通关闭走 ✗
     * 把它推后 → 过渡继续活着 → "动画播完后上滑"也能走小米自己的原生反向（缩回图标），
     * 而不是我们的 Tier-2 浮层（用户明确不要浮层）。
     *
     * 为什么是 8000：够覆盖"打开看一眼再上滑"的常见节奏，又不至于让一个已经收尾的
     * 过渡留太久。若发现新启动被老过渡影响（`[HVS续命] 期间已经又开了一次应用`），
     * 说明这个值太大，往下调。
     *
     * ★★ 2026-09-26 17:45 实测结论：**这条路没用，已经关掉（=0）**。
     *   打开 8000 后复验：`[HVS续命] 把 +1956ms 的自动收尾推后 8000ms` 确实生效、
     *   8 秒后也确实补上了收尾，但**点击后 3.0s 上滑时系统依然一个 `mergeAnimation`
     *   都没给我们** —— 也就是说"窗口只有 1~2 秒"**不是** MIUI 那个 onFinish 决定的，
     *   而是**系统侧那条开启动画过渡本身的生命周期**（应用画完 + 系统过渡收尾）决定的。
     *
     *   这条结论很值钱：它说明**选项 Y 也救不了"窗口外上滑"**（Y 造出来的同样是一条
     *   系统过渡，生命周期一样），所以别为了这个去重写 leash 生命周期。
     */
    public static final long HVS_HOLD_TRANSITION_MS = 0L;

    /**
     * 临时诊断：打印 `View.dispatchTouchEvent` 收到的触摸（类名+动作+坐标）。
     *
     * 为什么需要：`MaMlHostView.dispatchTouchEvent` 的记源钩子装上了却一次没触发
     * （真手指点 204x204 那个小部件 → 起了微信扫码、但 armed 仍是 false、没有动画），
     * 得先看清这次触摸到底落在哪个 View 上。查完就关掉（热路径）。
     */
    public static final boolean LOG_TOUCH_TREE = false;
    public static final int LOG_TOUCH_TREE_MAX = 80;

    /**
     * 强制反向的防误伤窗口（毫秒）。
     *
     * 实测刚打开后约 300ms，系统自己会有一次 `type=1021` 的合并
     * （那是"应用画完了"的收尾合并，不是用户返回）。
     * 距离开启动不足这个时长就一律不动，避免刚打开就自己飞回去。
     */
    public static final long HVS_REVERSE_MIN_SINCE_OPEN_MS = 1200L;

    // ===================== 返场动画（自己画的那一段） =====================

    /**
     * ★ 是否启用"从哪里来回哪里去"的返场动画。
     *
     * 为什么必须自己画：实测 **TO_BACK（上滑回桌面）系统根本不给应用侧任何回调**
     * （只有打开后约 346ms 一次自动合并），而桌面之所以能飞回图标，
     * 是因为那段动画是**桌面自己实现的**，它知道图标在哪；HyperViewScale 的关闭过渡
     * 只在 `ActivityRecord.finishIfPossible()`（真的结束 Activity）时才发起。
     * 负一屏的小部件属于 personalassistant，桌面那套逻辑覆盖不到。
     *
     * 所以：点击时留住官方截的**小部件位图 + 起点矩形**，
     * 等源视图的**窗口焦点回来**（用户回到负一屏）时，自己播一段"从全屏缩回小部件"。
     * 这条路不碰系统、不要权限，而且同时覆盖 2x2 / 4x4 / 快捷方式。
     */
    public static final boolean HVS_RETURN_FLIGHT = false;

    /** 返场动画时长（毫秒）。弹簧参数 ζ=0.95 / T=0.29s，实际收敛约 200ms。 */
    public static final int HVS_RETURN_DURATION_MS = 320;

    /**
     * 离开负一屏多久之后才允许播返场动画（毫秒）。
     *
     * 实测教训：原来设 250ms，结果**每次应用刚打开时**焦点都会抖一下回来
     * （日志里 `离开 572ms` / `离开 924ms`），于是返场动画在应用**背后**播掉了，
     * 用户什么都看不到。真正回到负一屏那次是 `离开 7580ms`。
     * 所以门槛要抬到"人不可能这么快操作完"的量级。
     */
    public static final long HVS_RETURN_MIN_AGE_MS = 2500L;

    /**
     * 是否启用 system_server 侧的中继信号（`debug.hvs.return_at`）。
     *
     * ⚠️ **默认关闭。实测有害。**
     * 当前实现是"只要焦点落在负一屏窗口就写一次属性"，**没有状态机**：
     * 打开应用的转场过程中焦点会反复经过负一屏窗口，于是每次打开都会连写好几次，
     * 实测一轮 40 次、间隔 600ms，甚至出现"距点击 -114ms"（属性时间戳早于点击）。
     * 结果就是返场动画在应用**打开过程中**就播 → 你看到的"错时""根本不同步"
     * "返回过程中硬生生把 app 内容切到小组件再卡 0.5s"。
     *
     * 要重新启用，必须先加状态机：只有"曾经有过非负一屏窗口获得焦点（=应用真的上来了）"，
     * 之后焦点再回到负一屏窗口，才算"用户回来了"。那一步没做之前不要打开。
     */
    public static final boolean HVS_ENABLE_RETURN_RELAY = false;

    // ===================== 桌面侧探针 =====================

    /**
     * ★ 是否在**桌面进程**（`com.miui.home`）里抓"桌面是怎么打开应用的"真实 options 配方。
     *
     * 桌面自己的代码是 Rust/Flutter 原生库（APK 无 dex，挂不到），
     * 但**桌面进程里的 framework Java 代码可以挂**，而且实测模块已注入成功。
     *
     * 抓四个点：
     *   1. `ActivityOptions.makeHyperViewScaleAnimation(View)` —— 桌面用不用 MIUI 的 Java HVS
     *   2. `HyperViewScaleRemoteStub` 的 5 个方法 —— 谁在调它们
     *      （我搜遍 framework.jar / services.jar / 两个 hyperviewscale jar，调用次数全是 0）
     *   3. `ContextImpl.startActivity` / `Instrumentation.execStartActivity` —— options 全量 key 与 animType
     *   4. `PendingIntent.send(...)` —— 小部件 / 快捷方式走的路
     *
     * **纯只读，不改任何行为。** 抓到之后照抄到负一屏即可。
     */
    public static final boolean ENABLE_LAUNCHER_PROBE = false;

    /**
     * ★ 是否在负一屏进程里判断"快捷方式实际走哪条启动分支"（**纯只读**）。
     *
     * APK 静态读出有四条分支（`service.shortcut.utils.m`）：
     *   ① xspace → 反射 PendingIntent.getActivityAsUser → `pi.send()`
     *   ② 微信小程序 → `IWXAPI.sendReq(...)`
     *   ③ 语音 → `miuix.voicesdk` 的 `uf.b.c(...)`
     *   ④ 普通 → `ctx.startActivity(...)`
     * 只有 ④ 能被我们的 `ContextImpl.startActivity` hook 到。
     *
     * ⚠️ **这一版只打点，绝不注入 options。** 2026-09-26 的事故就是从这个位置开始的
     * （无门控地把负一屏的 RemoteTransition 塞进任意应用的 finish intent，把酷安的
     * 侧滑返回动画搞坏了）。判断清楚分支之前不许动手。
     */
    public static final boolean ENABLE_SHORTCUT_PROBE = false;

    /**
     * ★ 只读：点击源是容器时，打印它的子层级（类名/尺寸/背景）。
     *
     * 为第 7 项服务：事实 #18 表明快捷方式/大组件的点击源自带背景层
     * （图标由 `IconCustomizer` 用 `icon_background/border/mask` 合成），整块截走
     * 就表现为"小组件内部颜色的矩形"。要改 `pickSourceView` 的策略，
     * 必须先看清里面到底有哪几层、各自多大 —— **先有数据再改代码**。
     */
    public static final boolean LOG_SOURCE_TREE = false;

    /** 最多打几棵树，避免刷屏。 */
    public static final int LOG_SOURCE_TREE_MAX = 12;

    /**
     * ★★ 把 `RemoteTransition.exitToHome` 置为 true —— "上滑回桌面飞回去"的第一步试探。
     *
     * 依据：MIUI 给 `RemoteTransition` 加了 `mExitToHome` / `mHyperRemoteTransition` 两个字段，
     * 而"回桌面"整条路在 system_server 的
     * `ActivityStarterImpl.setHyperTransition()` / `notifyHomeStartActivityFinish()`
     * 与 `WindowOrganizerControllerImpl.maybeMarkSysBarExitToHome()`
     * —— 它们读的正是这两个字段。桌面的原生库里就是
     * `IRemoteTransitionImpl` + `IHyperRemoteTransitionImpl`，**桌面的飞回图标就是 hyper transition**。
     *
     * 先只设 `exitToHome`（5 行）；若无效果，再实现完整的 `IHyperRemoteTransition`
     * （只有 2 个方法，但要走 Binder.onTransact + Proxy，因为公开 android.jar 没有这个私有接口）。
     */
    public static final boolean HVS_SET_EXIT_TO_HOME = true;

    /**
     * ★★ 是否把自实现的 `IHyperRemoteTransition` 塞进 `RemoteTransition.hyperRemoteTransition`。
     *
     * 实测：只设 `exitToHome=true` **没有效果**（13 次全设成功，上滑回桌面仍是"直接消失"）。
     * 真正被调用的是 hyper 槽 —— MIUI 在 system_server 里
     * `ActivityStarterImpl.notifyHomeStartActivityFinish()` 会 `getHyperRemoteTransition()`
     * 然后调 `startActivityFinished(ActivityStartInfo, callback)`。桌面的原生库里正是
     * `IHyperRemoteTransitionImpl`，这就是它的"飞回图标"。
     *
     * 实现见 {@link HyperTransition}（只有 2 个方法，走 Binder.onTransact + Proxy）。
     */
    public static final boolean HVS_USE_HYPER_TRANSITION = true;


    /**
     * 返场动画播放后的冷却时间（毫秒）。
     *
     * 实测 bug：加浮层/移浮层本身会让窗口焦点抖一下，
     * 于是"播一次 → 抖一次（被判成失焦）→ 焦点回来 → 再播一次"，
     * 每 ~600ms 一轮无限循环。冷却期内所有焦点事件一律忽略，用来打断这个循环。
     */
    public static final long HVS_RETURN_COOLDOWN_MS = 2000L;

    /** 点击后轮询窗口可见性的最长时间（毫秒）。超过就停，避免常驻轮询。 */
    public static final long HVS_RETURN_POLL_WINDOW_MS = 120_000L;

    /** 是否把官方截的位图存到外部目录（用来肉眼确认白边到底是什么）。 */
    public static final boolean HVS_SAVE_BITMAPS = false;

    /** 最多存几张。 */
    public static final int HVS_SAVE_BITMAP_MAX = 8;

    /**
     * 是否在 system_server 侧补写 `view_scale_open` extra。
     *
     * 实测：`requestViewScaleRemoteClose` 被调用时，那个 Intent 上
     * `hasExtra("view_scale_open") == false` —— 尽管应用侧启动时确实写进去了
     * （应用侧日志 13/13 次都能读到 `RemoteTransition`）。
     * 系统拿不到过渡对象 → `requestCloseTransitionIfNeeded` 拿 null → 关闭过渡建不起来
     * → **退出就是硬切**。
     *
     * 这里在 system_server 侧把最近一次见过的 RemoteTransition 缓存下来，
     * 在关闭入口发现 extra 缺失时补写进去。只对 10143 这一个 uid 的过渡生效。
     */
    public static final boolean HVS_INJECT_CLOSE_EXTRA = false;

    /** 缓存的 RemoteTransition 有效期（毫秒）—— 超过这么久就不认，避免误伤别的应用。 */
    public static final long HVS_CLOSE_EXTRA_TTL_MS = 120_000L;

    /**
     * 是否在关闭那一次调用期间，把 `TransitionController.isCollecting()` 强制成 false。
     *
     * `HyperViewScaleTransitionImpl.requestCloseTransitionIfNeeded()` 的**唯一**提前返回是：
     * <pre>
     *   if (tc.isCollecting()) { log("Skip request close transition, isCollecting: "); return null; }
     * </pre>
     * 实测按返回键时它**就是 true** → 官方自建的 `TRANSIT_CLOSE(2)` 过渡被跳过。
     * 剩下只能走 `mergeAnimation`，而那条路上 `mReverseToActivityA/B` 是拿
     * `TransitionInfo.getType()` 跟 1/2/3 比出来的 —— 实测到达时是 `1021`，
     * 两个都 false → `reverseAnimationWhenMerge` 返回 false → **退出硬切**。
     *
     * 放开这一处，就是让官方**自己设计好的那条关闭过渡**建起来
     * （`createTransition(2,0)` + `requestStartTransition(t, task, rt, null)`）。
     * 只在"缓存里的 RemoteTransition 是新鲜的"这一次关闭期间生效，其它时候一律不碰。
     */
    public static final boolean HVS_FORCE_CLOSE_TRANSITION = true;

    // ===================== 动画起点视图的选取 =====================

    /**
     * 动画起点用哪个 View：
     *   `"clicked"`   —— 被点击的那个 View 本身（**默认，已改回**）
     *   `"container"` —— 往上提一层到"卡片容器"（**实测是错的，别开**）
     *
     * 为什么改回：实测日志显示"上提"把单个图标提到了
     * `ImageView 128x128 -> LinearLayout 466x128` —— **一整行四个图标**的容器，
     * 结果点一个图标另外三个跟着被裁切。而且那些容器 `bg=false`（没有背景），
     * 提上去也修不了白边。所以这条思路作废。
     */
    public static final String SOURCE_VIEW_MODE = "clicked";

    /** 上提时允许的祖先/子视图面积比上限（防止一路爬到整页容器）。 */
    public static final float CONTAINER_MAX_AREA_RATIO = 4.0f;

    /**
     * 是否跳过"目标就是本进程内部 Activity"的启动（各种内部弹窗）。
     *
     * 默认 **false**：先不做过滤，让所有记源后的启动都带上动画，由你判断观感。
     * 如果发现内部弹窗（如 BusinessDialogActivity）从卡片放大看着不对，改成 true。
     */
    public static final boolean SKIP_SAME_PACKAGE = false;

    /**
     * 点击源视图的记源有效期（毫秒）。
     *
     * 实测教训：原本设 1200ms，结果五次真实的小部件启动**全部 armed=false** ——
     * 点击到 startActivity 之间的延迟比它长。现在放宽到 2500ms，
     * 并且改成**一次性消费**（一次点击只影响紧接着的那一次启动），
     * 所以放宽窗口不会误伤无关启动。
     */
    public static final long SOURCE_VIEW_ARM_MS = 2500L;

    /** 打开动画的详细日志（建议开着，第一次跑要靠它判断哪个工厂可用）。 */
    public static final boolean LOG_OPEN_ANIM = true;

    // ===================== 日志节流 =====================

    /** 高频方法（逐帧）最多每 N 毫秒打一条。 */
    public static final long PROBE_THROTTLE_MS = 250;

    /** 高频方法开头无条件打印的前 N 次，用于看清调用形态。 */
    public static final int PROBE_FIRST_N = 25;

    /** 每 N 毫秒输出一次统计摘要（调用频率 / 最小最大值）。 */
    public static final long PROBE_STATS_MS = 2000;

    // ===================== 拖拽阶段插值 =====================

    /**
     * 手指按着时的进度映射曲线。
     *
     * **默认 `"system"` = 不映射，原样放行 —— 也就是用小米自己的曲线。**
     * 逆向发现系统的曲线由运行时 `EaseManager.getStyle(styleId, params)` 决定，
     * 而 styleId 不是编译期常量，静态分析抄不到，所以最忠实的做法就是不覆盖。
     *
     * 想微调的话，只能从**小米自己的单调曲线族**里选（见 {@link XiaomiCurves}）：
     *   system / linear / quadOut / cubicOut / quartOut / quintOut / sineOut
     *   / expoOut / decelerate
     * 这些都是不过冲的，适合"手指停哪画面停哪"。
     */
    public static final String DRAG_CURVE = "system";

    /** 拖拽曲线强度，1.0 = 完整套用；调小则趋近线性。仅在非 system 时有效。 */
    public static final float DRAG_STRENGTH = 1.0f;

    /** 是否重映射拖拽进度。默认关（用系统的）。 */
    public static final boolean REMAP_PROGRESS = false;

    /**
     * 逐个方法的开关。
     *
     * ⚠️ 真机实测修正：`D(float)` **从不触发**，真正逐帧携带进度的是 `F(float)`
     * （约 60 次/段，值域 -0.0059..1.0000，带轻微过冲）。
     * 所以默认改成了 F。
     */
    public static final boolean REMAP_D = false;
    public static final boolean REMAP_F = true;
    public static final boolean REMAP_H = false;   // scrollStart
    public static final boolean REMAP_G = false;   // scrollEnd

    /**
     * 并行动画由 hook 直接驱动（默认开）。
     *
     * 为什么不能靠采样器读 `w()`：实测 `w()` **全程 0 次调用**，
     * 早期实现靠它当进度源，结果拿不到值、并行动画一次都不执行 —— 表现就是"装了模块但没效果"。
     * 现在直接用 `F(float)` 的入参驱动，不依赖任何 getter。
     */
    public static final boolean DRIVE_PARALLEL_FROM_HOOK = true;

    /** 是否额外开逐帧采样器兜底（`w()` 可用时才有意义）。默认关。 */
    public static final boolean SAMPLE_ON_FRAME = false;

    // ===================== 提交阶段动画 =====================

    /**
     * 松手后的收敛/开合动画曲线。
     *
     * **默认 `"system"` = 不改，用系统自己的。**
     * 系统的提交动画走 miuix Folme，曲线由 `EaseManager.getStyle(styleId, params)` 给出；
     * 想要"Q弹"（小米的动效语言），最正确的做法就是**让小米自己那条曲线生效**。
     *
     * 想换的话从 {@link XiaomiCurves} 的表里选名字，例如：
     *   system / spring / cubicOut / expoOut / decelerate
     *   / bounceEaseOut（带回弹）
     */
    public static final String COMMIT_CURVE = "system";

    /** 提交曲线强度。0 = 线性（等于不改），1 = 完整套用。 */
    public static final float COMMIT_STRENGTH = 1.0f;

    /**
     * 是否**替换系统真实使用的 EaseStyle**。
     *
     * 这是"要小米的曲线"最忠诚的开关：开启后 hook `AnimConfig.setEase(...)`，
     * 用 `XiaomiCurves.getSystemStyle()` 调系统自己的工厂拿到原厂 EaseStyle 对象再放进去 ——
     * 拿到的是小米原厂对象，不存在公式重写对不齐的问题。
     *
     * 关闭时什么都不做（= 用系统原本那条曲线）。
     */
    public static final boolean OVERRIDE_EASE_STYLE = false;

    /** 替换用的 styleId，取自 {@link XiaomiCurves} 的常量。 */
    public static final int EASE_STYLE_ID = XiaomiCurves.SPRING;

    /** 是否改写系统构建的 AnimatorSet（换插值器 + 改时长）。与 Folme 路径无关。 */
    public static final boolean RETUNE_COMMIT_ANIM = false;

    /** 提交动画时长倍率。1.0 = 保持系统原时长。 */
    public static final float COMMIT_DURATION_SCALE = 1.0f;

    // ===================== 并行动画 =====================

    /** 缩放：进度 0 时的最小缩放。1.0 表示不缩放。 */
    public static final float SCALE_MIN = 0.94f;

    /** 内容透明度：进度 0 时的最低透明度。1.0 表示不透明。 */
    public static final float ALPHA_MIN = 0.60f;

    /**
     * 实时模糊：最大模糊半径（像素）。0 表示不模糊。
     *
     * ⚠️ 默认设为 0（关闭），原因见 README：澎湃OS 4 的新材质「柔光玻璃」是
     * 折射/反光效果，**不是毛玻璃**。我们这里只能做 RenderEffect 的高斯模糊，
     * 那是"毛玻璃"，跟 OS4 的设计语言不是一回事，硬加会显得廉价、互相打架。
     * 想要虚化请先看负一屏本身有没有用系统材质，再决定要不要叠。
     */
    public static final float BLUR_MAX_PX = 0f;

    // ===================== 可打断 / 平滑 =====================

    /**
     * 是否用带速度衔接的弹簧对进度做平滑。
     *
     * 默认 **false** —— 系统自己的动画已经是弹簧（miuix Folme），
     * 再叠一层平滑等于串两个弹簧，手感会变糊。
     *
     * 只有确认系统的进度信号**不是逐帧**、或有明显台阶感时才打开。
     * 打开后由 {@link SpringFloat} 负责速度衔接，打断时不会一顿。
     */
    public static final boolean SMOOTH_WITH_SPRING = false;

    /** 平滑弹簧刚度（越大跟得越紧）。仅在 USE_XIAOMI_SPRING=false 时使用。 */
    public static final float SPRING_STIFFNESS = 320f;

    /** 平滑弹簧阻尼比：1.0 = 临界阻尼（不过冲）；小于 1 = 会过冲（Q弹）。 */
    public static final float SPRING_DAMPING_RATIO = 1.0f;

    // ---- 实测的小米弹簧参数（推荐，默认启用）----

    /**
     * 用**从设备实测解码出来的**小米弹簧参数，而不是上面自己拍的值。
     *
     * 实测证据与解码过程见 {@link XiaomiCurves.Spring}：
     * 小米用 [阻尼比 ζ, 周期 T 秒] 参数化，k = (2π/T)²。
     */
    public static final boolean USE_XIAOMI_SPRING = true;

    /**
     * 用哪一组实测样本：
     *   false = 样本 A（周期 1.276s，ζ=0.95）—— 慢而软，有极轻微过冲
     *   true  = 样本 B（周期 0.3s，ζ=1.0）  —— 快而脆，不过冲
     *
     * 样本 B 更接近常规过渡动画的手感，所以默认用它；
     * 想体验小米那条慢弹簧就把这里改成 false。
     */
    public static final boolean USE_XIAOMI_SPRING_FAST = true;

    /** 取刚度（按上两个开关决定用实测值还是自拍值）。 */
    public static float stiffness() {
        if (!USE_XIAOMI_SPRING) {
            return SPRING_STIFFNESS;
        }
        return XiaomiCurves.Spring.stiffnessOf(period());
    }

    /** 取阻尼比。 */
    public static float damping() {
        if (!USE_XIAOMI_SPRING) {
            return SPRING_DAMPING_RATIO;
        }
        return USE_XIAOMI_SPRING_FAST
                ? XiaomiCurves.Spring.B_ZETA : XiaomiCurves.Spring.A_ZETA;
    }

    /** 取周期（秒）。 */
    public static float period() {
        return USE_XIAOMI_SPRING_FAST
                ? XiaomiCurves.Spring.B_PERIOD_S : XiaomiCurves.Spring.A_PERIOD_S;
    }

    /**
     * 弹簧"最小可见变化"——决定什么时候算停下来。
     *
     * AOSP 里这个量是像素（minVisibleChange），内部再乘 0.65 作为位置阈值，
     * 并由它推出速度阈值（走完阈值需要 ≥1 帧才算合理速度）。
     * 我们的进度是归一化的 0..1，所以用 0.001（千分之一行程）。
     *
     * 它同时决定 {@link AnalyticSpring} 算出来的时长，进而决定 `retuneCommit` 的动画时长。
     */
    public static final float SPRING_MIN_VISIBLE_CHANGE = 0.001f;

    /**
     * 解析解弹簧算时长时假设的帧长（毫秒）。
     *
     * AOSP 用 {@code RefreshRateTracker.getSingleFrameMs(context)} 取实际刷新率，
     * 我们需要 Context 才有刷新率，所以这里给一个可配置值：60Hz = 16.67，120Hz = 8.33。
     * 探测日志里会打印实测帧长，拿到后改这里。
     */
    public static final float ASSUMED_FRAME_MS = 1000f / 60f;

    // ===================== 速度驱动（AOSP 技法）=====================

    /**
     * 是否按**松手速度**选择提交曲线。
     *
     * 来自 AOSP Launcher3 `Interpolators.scrollInterpolatorForVelocity`：
     *   |速度| &gt; 10 px/ms → quintic ease-out（小米 QUINT_OUT）
     *   否则              → cubic  ease-out（小米 CUBIC_OUT）
     * 快甩和慢放用不同曲线，这是"手感对"的关键之一。
     */
    public static final boolean VELOCITY_AWARE = true;

    /** 快甩判定阈值（px/ms）。AOSP 用的是 10。 */
    public static final float FAST_FLING_PX_PER_MS = 10f;

    /** 按速度缩放时的过冲上限（AOSP 用 min(|v|, 3)）。 */
    public static final float OVERSHOOT_MAX = 3f;

    /**
     * "快到位就直接吸附"阈值 —— AOSP 的 `ANIMATION_COMPLETE_THRESHOLD = 0.95f`
     * 配合 `forceFinishIfCloseToEnd()`：已经走了 95% 就别再慢吞吞收尾。
     */
    public static final float FORCE_FINISH_THRESHOLD = 0.95f;

    // ===================== 进度归一化（待校准）=====================

    /**
     * 探测阶段确认进度值域后填这里。
     * 若探测结果是 0..1，保持默认即可；
     * 若是像素值（例如 0..1200），把 MAX 改成 1200。
     */
    public static final float PROGRESS_MIN_HINT = 0f;
    public static final float PROGRESS_MAX_HINT = 1f;

    /** 进度方向：某些实现里 0 = 完全打开，1 = 完全关闭。探测后若反了就置 true。 */
    public static final boolean INVERT_PROGRESS = false;

    /** 动画模式下逐帧打印 原始值/归一化/映射后 的对照，用于对齐曲线。调试完请关掉。 */
    public static final boolean LOG_EVERY_FRAME = false;
}
