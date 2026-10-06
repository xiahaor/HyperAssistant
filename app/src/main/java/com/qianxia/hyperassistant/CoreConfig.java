package com.qianxia.hyperassistant;

/**
 * 新核心（{@link AssistantAnimCore}）的�?关文件�??
 *
 * 设�?�原则�??*�?�? 4 �?�?�?*，�?�且彼�?�不重叠、不打架�?
 * 旧的 {@link Config} 里那十几�?HVS_* 实验�?关是近百�?试验堆出来的�?
 * 有些互相矛盾（例�?HVS_FORCE_REVERSE_ON_TOBACK �?HVS_SF_WHOLE 同时�?true 会两�?引擎�?
 * 同一�?leash）�?�新核心�?保留两条功能，因此只�?要四�?量�??
 *
 * <pre>
 *   ENABLED           总闸（false = 完全不安装任何钩子）
 *   ENABLE_OPEN_SF    (A) 打开段：复用旧版验证过的 SurfaceControl 原生 SF 动画
 *   ENABLE_BLUR_SWEEP (B) 缩回段收尾：动画停稳后调标准入口清模�?
 *   CLEAR_WINDOW_BLUR (B) 的附加项：顺带把 window backgroundBlurRadius �?0
 * </pre>
 *
 * 除这四个以�?�，下面的常量都�?**安全阈�??*（超时�?�节流），不�?功能�?关，
 * 调它�?不会改�??做什�?，只会改�?�?么时候做"�?
 */
public final class CoreConfig {

    private CoreConfig() {
    }

    // ------------------------------------------------------------------ 总闸

    /**
     * 总闸。false �?{@link AssistantAnimCore#install} 直接返回�?
     * �?�?钩子都不装，宿主行为与没装模块完全一致�??
     *
     * <p>�?�?9 �?�?**恢�?��??true**：本核心重新成为模块�?�?驱动者�??
     * 与之配�?�必须�??*旧模�?*关掉（{@code Config.ENABLE_HVS_DIAG = false}），
     * 否则两�?�实现会同时驱动同一�?leash（�?��??飘）�?
     */
    public static boolean ENABLED = true;

    // ------------------------------------------------------------------ (A) 打开�?

    /**
     * (A) 打开段：点负�?屏组�?�?应用铺满，由框架�?带�??SF 动画引擎驱动
     * （{@code SurfaceControl$Transaction.startSFAnimation}）�??
     *
     * 这是旧版�?�?�?�?真机验证有效的成果（重�?�帧 0.00�?14~119fps）�??
     * false = 完全不�?�打�?段，MIUI �?己的过渡原样�?�?
     */
    public static boolean ENABLE_OPEN_SF = false;

    /**
     * 动画期间�?�?"吞掉" MIUI �?己�?�同�?�?leash 的�?�帧写入
     * （setCrop/setScale/setPosition/setCornerRadius/setAlpha）�??
     *
     * **不建�?改�??false**：两�?引擎同时写一�?leash 会互相打架（画面飘）�?
     * 旧代码实测：吞写 + SF 引擎 = �?�?稳定的组合�??
     * 这个�?关留在这里只�?为了�?"回�??到不干�??"这件事可验证�?
     */
    public static boolean SWALLOW_MIUI_LEASH_WRITES = true;

    /**
     * 在动画回调里顺带清理残留的依�?�?
     *
     * {@link Config#HVS_BLUR_RESET}（旧模块的模糊归零）里有�?�?
     * "按层名拦�?view_scale_blur �?setAlpha 写入 �?改成 0"�?
     * 新核�?*明确不再做这件事**：把 blur 层的 alpha �?0 已经试过 117 次无�?
     * （那层没有自己的 buffer，模糊是它下面合成的 pass-blur），
     * 而且拦写入是"和系统抢"，违�?�?在停稳后调标准入�?"的�?��?��??
     * 这里保留这个常量�?为文档化该决定，不参与运行�??
     */
    @SuppressWarnings("unused")
    public static final boolean NEVER_TOUCH_BLUR_LAYER_ALPHA = false;

    // ------------------------------------------------------------------ (A2) leash 裁切(crop)
    //
    //  为什么需要它（�?�线读�??+ 真机实测，�?��?�负�?屏动�?图标裁切规格.md》）�?
    //  �?�? HVS �?HyperViewScaleAnimationImpl.updateLeashAnim �?*每帧**写四笔：
    //      setCrop(leash, calculateClipRect(w,h,ratio, w/画布W, h/画布H, 画布W, 画布H))
    //      setScale(leash, max(w/画布W, h/画布H), 同�??
    //      setPosition(...) + setCornerRadius(圆�?? / 缩放)
    //  我们在打�?段把这四笔一起吞掉，却只�?SF 引擎补几�?�?**裁切冻结** �?
    //  窗口在长、�?�切没�??�?"动画早期组件里显示着应用内�?�，随后才恢复�?�常"�?
    //  正确关系（真机三�?数据点全部�?�现）：
    //      s    = max(w/画布W, h/画布H)
    //      crop = (0, 0, min(w/s, 画布W), min(h/s, 画布H))     �?crop × s �?当前动画矩形
    //
    //  ★★ 互斥矩阵�?*�?次只动一�?变�??*，不要同时改两个）：
    //
    //   ENABLE_OWN_CROP | ALLOW_MIUI_CROP | 行为
    //   ----------------+-----------------+------------------------------------------------
    //   false (默�??)    | true  (当前状�?? | A0�?*放�??** MIUI �?setCrop（根�?�? hook 它），其余四笔照�?
    //   false           | false            | 基线/对照：吞�?MIUI �?setCrop 且不补（症状存在�?
    //   true            | 任意             | A1：自�?crop�?*setCrop 仍�??�?*（防 MIUI 覆盖我们�?
    //
    //  �?�?{@link #SWALLOW_MIUI_LEASH_WRITES} 的关系：它是总闸；它�?false 时五笔全放�?��??
    //    此时 ENABLE_OWN_CROP 会和 MIUI 抢同�?�?量（画面会�?�）�?用本功能必须保持它为 true�?

    /**
     * (A0) 放�?? MIUI �?己�??{@code Transaction.setCrop}（其余四笔照旧吞）�??
     *
     * <p>�?省事的�?�照实验：MIUI �?HVS 动画仍在跑，它算出的 crop 天然同比、随帧变化，
     * �?SF 驱动的几何只�?曲线（�??点相同），�??�?通常�?有数�?百分点�??
     * 实现方式 = <b>不注�?setCrop 钩子</b>（�?? {@code installTransactionHooks} �?watched 数组）�??
     *
     * <p>�?{@link #ENABLE_OWN_CROP} 互斥：A1 生效时本�?关�??忽略（crop �?能有�?�?写�?�）�?
     */
    public static boolean ALLOW_MIUI_CROP = true;

    /**
     * (A1) 打开段：动画期间�?*我们**每帧补写 leash 的�?�切�?
     *
     * <p><b>默�?? false</b>：等真机证据到位、且�?认�?�动这一�?变量时再�?用�??
     * �?用后的�?�为（落点�?? {@code AssistantAnimCore} �?startOwnCrop/endOwnCrop）：
     * <ol>
     *   <li>�?SF 动画�?*同一主线程回�?*内写首帧 crop（与几何同帧）；</li>
     *   <li>{@link #CROP_TICK_MS} 心跳，用�?SF 同参数的镜像弹簧�?(w,h)�?
     *       每笔**�?�? setCrop**，绝不写 position/scale/corner（那些归 SF 引擎）；</li>
     *   <li>结束（SF 回调 status!=1）时�?{@link #CROP_FINAL_SNAP} 复位成整幅画布；</li>
     *   <li>�?途打�?返回（交�?MIUI）时**立即停心跳�?�一�?crop 都不�?*�?/li>
     * </ol>
     */
    public static boolean ENABLE_OWN_CROP = true;

    /**
     * (A1) 结束（SF 回调 status!=1、或兜底 stop 时）�?crop 复位成整幅画�?
     * {@code (0,0,画布W,画布H)}，防止留�?内�?��??裁�??的残留（应用下半部空白）�?
     *
     * <p>默�?? true�?b>�?途打�?返回（交�?MIUI）时不�?��??/b>：那�?刻窗口可能已经很小，
     * 写整�?crop 会�?�成"整幅应用缩进组件"的一帧�??
     */
    public static final boolean CROP_FINAL_SNAP = true;

    /** (A1) crop 心跳周期（ms）�??ms 略快�?vsync，保证�?�切不落后几何一帧�??*/
    public static final long CROP_TICK_MS = 8L;

    /** (A1) crop 心跳的日志间隔：每这么�?�帧打一行（首帧/复位行不受它限制）�??*/
    public static final int CROP_LOG_EVERY_N_FRAMES = 30;

    /**
     * (A1) 画布�?高的**�?终兜�?*值（= leash �?�?坐标系的尺�?�，�?�?1200×2608）�??
     *
     * <p>取�?�优先级（代码实现，不写死）：① 真机学到�?{@code sLeashNorm}
     * （�??�?�?MIUI setCrop 的右边界，实�?1200）；�?�?标矩�?（ViewScaleAnimInfo �?
     * widthTo/heightTo，�?�常 1200×2608）；�?�?常量�?
     */
    public static final float CROP_CANVAS_FALLBACK_W = 1200f;

    /** (A1) 画布高的�?终兜底�?�（优先级�?? {@link #CROP_CANVAS_FALLBACK_W}）�??*/
    public static final float CROP_CANVAS_FALLBACK_H = 2608f;

    // ------------------------------------------------------------------ (B) 缩回段收�?

    /**
     * (B) 缩回段收尾：缩回动画**停稳�?*，调用负�?屏自己的标准入口
     * {@code y5.i.a(decorView)} + {@code decorView.setPassWindowBlurEnabled(false)}
     * �?pass-blur / blendColor / blurMode / blurRadius �?次清掉�??
     *
     * 注意：本功能**不接管动�?*�?*不改 MIUI 状�?�机参数**�?*不改 merge 参数**�?
     */
    public static final boolean ENABLE_BLUR_SWEEP = true;

    /**
     * (B) 的附加项：再调一�?{@code Window.setBackgroundBlurRadius(0)}�?
     *
     * 默�?? false。理由：那是**窗口�?*模糊（BLUR_WINDOW 状�?�用的）�?
     * �?pass-blur 残留"�?两条不同的路；乱压可能影响仍然可见的�?的模糊�??
     * �?有在�?认残留来�?window blur 时才打开�?
     */
    public static final boolean CLEAR_WINDOW_BLUR = false;

    /**
     * (B) 的�?? 3 步（�?选�?�更强）：拿�?{@code DeviceBlurBlendAdapter} �?
     * 反射�?{@code onScroll(0.0f)}�?
     *
     * 走的�?MIUI �?己�??{@code getRealProgress()} �?{@code mCurrentBlurRadius}
     * 更新链，�?BLUR_VIEW 下会�?�?走�??{@code blurBackground()} �?{@code y5.i.a(...)}�?
     * 而且它是**幂等**的（progress 0 时不会反复改状�?�）�?
     *
     * 默�?? true：它�?�?MIUI �?己的状�?�机把自己拉�?0"，比我们直接压属性更符合原意�?
     * 但�?�注意它�?�?*窗口不可�?*时调�?（�??{@link AssistantAnimCore} 的收尾守�?）�??
     */
    public static final boolean CALL_DEVICE_ON_SCROLL_ZERO = true;

    // ------------------------------------------------------------------ 黑白边修�?

    /**
     * �?黑白边修复（**逐字移�??**�?旧模块�??{@code HVS_FIX_MASK_BUFFER}）：
     * hook {@code HyperViewScaleRemoteManager.setMaskSurface}�?
     * �?onAfter 里拿返回�?SurfaceControl + 入参 Rect/ViewInfo **重画那块 mask buffer**
     * （清透明 + 位图等比铺满"视图矩形对应的可见区"）�??
     *
     * <p>不修的后果：官方把位图按原尺寸画�?(0,0)，buffer 其余部分�?*铺色** �?
     * 图标底色深就�?黑边、浅就是白边（用户反馈的"黑白�?）�??
     */
    /** A0/A1 见文件上方�??A2) leash 裁切(crop)」小节，�?关名�?{@code ALLOW_MIUI_CROP} / {@code ENABLE_OWN_CROP}�?*/
    public static boolean FIX_MASK_BUFFER = true;

    /**
     * �?�?"�?核心�?实接管了打开�?的过渡里�?mask 重画（默�?true），
     * 避免影响�?的启动路径�??
     */
    public static boolean ONLY_FIX_MASK_WHEN_WE_DRIVE = false;

    /**
     * �?�?13 �?（用户建�?）：**位图缺失时不要铺�?黑，改铺系统的高�?模糊**�?
     *
     * <p>官方 {@code drawViewOnCanvas} �?{@code ViewInfo.getBitmap() == null} 时走�?
     * <pre>
     * 78: canvas.drawColor(0xff000000)      // �?�?
     * 83: Slog.w("drawViewOnCanvas: no bitmap and no background color, using black")
     * </pre>
     * 真机表现 = 用户报的"**�?速切�?app 时闪�?*（基�?上只有这�?�?场景出现�?"—�??
     * 切换�?�?，快照来不及生成 �?官方铺黑 �?�?黑�??
     *
     * <p>�?�?关打�?时，我方�?位图缺失"这条�?�?**先把 mask 清�?�明、再挂系统背�?模�??*
     * （{@code Transaction.setBackgroundBlurRadius}），让下面的内�?��??系统模糊后�?�出�?—�??
     * 视�?�上�?"糊一�?而不�?黑一�?�?
     *
     * <p><b>安全顺序</b>：先�?认本机支持�?�模糊调�?�?**�?*清�?�明。否则一旦模糊挂不上�?
     * 我们就把 mask 弄成全�?�明了，而实�?�?�?已证�?全�?�明 �?应用实时内�?�到处�??�?
     * 那比�?黑更糟�?�拿不到能力�?*�?么都不做**（保持官方结果）�?
     */
    public static boolean MASK_NULL_USE_BLUR = false;

    /** 位图缺失时的模糊半径（本�?maxBlurRadius=180，取�?半）�?*/
    public static int MASK_NULL_BLUR_RADIUS = 90;

    /**
     * DEV ONLY：**闪黑取证探针**（纯只读，不改任何行为；发布前必须关）。
     *
     * <p>结论已定：闪黑**不是我们造成的** —— `FIX_MASK_BUFFER=false` 那一轮，日志里
     * `mask buffer`/`setMaskSurface` **0 条**，而闪黑仍在。⇒ 只能 hook MIUI 自己的路径来修。
     *
     * <p>本探针要钉死"闪黑是哪一层的哪一帧"，重点验证三个假设：
     * <ol>
     *   <li>官方 `drawViewOnCanvas` 用 `getViewDominantColor` **整屏铺色**；深色图标该值就是
     *       `0xff000000`（真机日志已见 `策略=5 铺色=0xff000000`），alpha 由
     *       {@code ViewScaleAnimInfo.maskAlphaFrom} 驱动 ⇒ 过渡最开始几帧若这块全屏 mask
     *       已以较大 alpha 出现，整屏就是主色 = **闪黑/闪白**。</li>
     *   <li>`setMaskSurface` 反汇编开头有 {@code removeSurface(mMaskSurface)} —— manager 只有
     *       **一个** `mMaskSurface` 字段；快速切换时后一次调用会**移除/替换前一次**的 mask，
     *       而前一条 leash 可能还在屏幕上动 ⇒ 那条 leash 的 mask 没了 ⇒ **黑**。</li>
     *   <li>并发：新 `startHyperViewScaleAnimation` 到来时是否已有动画在跑。</li>
     * </ol>
     *
     * <p>当前实现在 {@code AssistantAnimCore} 的 `setMaskSurface` onAfter 里打印
     * `【闪黑取证】新 mask 建立：主色=… 上一块 mask=★仍valid/已失效 距上次建 mask=…ms`。
     *
     * <p><b>第 1 轮实测（21:28–21:29，22 次建 mask）</b>：`上一块 mask` **22/22 全部已失效**
     * ⇒ <b>假设② 被证伪</b>（官方 `removeSurface(mMaskSurface)` 确实做了清理，不存在"旧 mask
     * 悬挂"）。主色里 `0xff000000` 出现 5+ 次 ⇒ <b>假设① 仍是首要嫌疑</b>：深色图标 = 纯黑铺色。
     *
     * <p><b>第 2 轮探针（只读）</b>：只差"这块 mask 的 alpha"一个量 ——
     * <ol>
     *   <li>建 mask 时打印 `maskAlphaFrom / maskAlphaTo / realScale`（取自
     *       {@code sLeashInfo} 那份 {@code ViewScaleAnimInfo}）；</li>
     *   <li>建 mask 后 800ms 内打印**非 leash 的 setAlpha**，标出目标是否为那块 mask。</li>
     * </ol>
     * 判读：`maskAlphaFrom≥0.3` 且 `目标=★mask` 的头几帧存在 ⇒ 闪黑机制定案，
     * 修法 = 让新建的 mask 从 alpha 0 起步（一处 hook、一个开关）。
     * 0 条 alpha 日志 ⇒ MIUI 不走 `Transaction.setAlpha` 给 mask 设 alpha，改查 initLeashChild。
     */
    public static boolean DEV_FLASH_PROBE = false;

    /**
     * ★★★ **闪黑修复（本轮唯一变量）**：把 {@code ViewScaleAnimInfo.maskAlphaFrom}
     * 由 **1.0 改成 0.0**（构造完成后改这一个字段，铺色/位图/曲线/时长一律不碰）。
     *
     * <p><b>证据链（shots/flash2_log.txt，第 2 轮探针，21:35–21:36）</b>：
     * <ol>
     *   <li>22/22 次 `maskAlphaFrom=1.0 maskAlphaTo=0.0`；</li>
     *   <li>逐帧 alpha：5ms=<b>1.0</b> → 43ms=0.373 → 60ms=0.186 → 168ms=0
     *       （<b>前 ~50ms 不透明度 &gt;0.3</b>）；</li>
     *   <li>mask buffer 是 `1200x2608` 全屏，`铺色=getViewDominantColor`，
     *       源组件位图只有 128x128（占 0.6%）⇒ **不透明时整屏 = 图标主色**；</li>
     *   <li>深色图标主色 = `0xff000000` ⇒ **开头 ~50ms 整屏纯黑 = 闪黑**；
     *       浅色图标 ⇒ 闪橙/闪白（不明显）—— 对上"有时候黑有时候没事"。</li>
     * </ol>
     *
     * <p><b>为什么归零就能修掉</b>：闪的必要条件是"alpha&gt;0 且内容是纯色"。
     * `maskAlphaFrom=0` ⇒ MIUI 自己的 1→0 淡出变成 0→0 ⇒ 全程 alpha=0 ⇒
     * 纯色覆盖被**构造性排除**（不是"可能好一点"）。
     *
     * <p><b>验证行</b>：{@code 【闪黑修复】maskAlphaFrom 1.0 → 0.0}，
     * 探针回读 {@code 【闪黑取证】新 mask 建立 … maskAlphaFrom=0.0}，
     * 以及首笔 {@code alpha 写入 a=0.0 目标=★mask 距建 mask=5ms}。
     *
     * <p><b>已知风险</b>：mask 的本职是"首帧前把源组件快照留在画面上"。
     * 若出现**空白窗/白窗**回归 ⇒ 说明它确实负责保活 ⇒ 改走方案乙
     * （铺色透明、保留位图快照），那是**另一个变量、下一轮再上**。
     *
     * <p><b>回滚</b>：本开关置 {@code false}（一行，其余全不动）。
     * 关掉后行为 = 官方原样（maskAlphaFrom 保持 1.0）。
     */
    public static boolean MASK_ALPHA_FROM_ZERO = true;

    /**
     * ★★★ **源快照底 / 挖洞法（本轮唯一变量，2026-09-30 23:1x 定案）**。
     *
     * <p>把官方那块 mask 变成"**负一屏整屏快照 + 在应用窗口当前位置挖一个透明洞**"：
     * <pre>
     *   mask(buffer 1200x2608, setScale(1/realScale) ⇒ 1:1 屏幕对齐, layer=MAX ⇒ 在应用窗口之上)
     *        = 负一屏快照（洞外）
     *        ⊕ 洞内 CLEAR 透明 ⇒ 露出下面的 leash（= 应用窗口，正常放大）
     * </pre>
     * ⇒ 洞外**不黑**（是负一屏画面）、洞内是应用窗口动画；窗口长满全屏时"洞"= 全屏
     * ⇒ mask 自己变全透明，收尾天然干净。
     *
     * <p><b>为什么是这一招（真机事实链，见交接文档 §4.14.7）</b>：
     * <ol>
     *   <li>SF 层表：过渡期间壁纸/桌面/负一屏**都不在合成里**，应用窗口之外 = **空 = 纯黑**；</li>
     *   <li>官方 mask 是**唯一**一块 1:1 整屏覆盖层，且它的 buffer **本来就是我们在重画**
     *       （{@link #FIX_MASK_BUFFER}）；</li>
     *   <li>官方 mask 的几何配方已实测：`setPosition(0,0)` + `setScale(1/realScale)=9.375`、
     *       **不裁切**、layer=MAX（`shots/geo_log.txt`）⇒ 它天生就是"整屏 1:1"的。</li>
     * </ol>
     *
     * <p><b>本开关的行为（只做这三件事）</b>：
     * ① `maskAlphaFrom/To` 强制 1.0（要它一直看得见；**优先级高于 `MASK_ALPHA_FROM_ZERO`**）；
     * ② mask buffer 画成"负一屏整屏快照"（替代官方的主色铺色 + 小快照）；
     * ③ 每帧按 leash 的 `setPosition/setScale/setCrop/setCornerRadius` 在 buffer 上 CLEAR 出窗口洞。
     *
     * <p><b>回滚</b>：置 false ⇒ 回到现在的行为（mask 仍归零不可见 + 黑底照旧）。
     */
    public static boolean MASK_AS_BACKDROP = false;

    /** 挖洞法的日志限流（自带预算，不占 LOG_BUDGET；**每次过渡复位**，一次过渡约 60~80 帧）。 */
    public static final int MASK_BACKDROP_LOG_BUDGET = 400;

    // ------------------------------------------------------------------ (C) 返回�?

    /**
     * (C) 返回段：收到缩回信号�?*立刻把控制权交回 MIUI**
     * —�??停掉我们�?己�??SF 动画 + 立即停�?��??leash 写入�?
     * �?MIUI �?己�??HVS 反向动画�?leash 送回源组件矩�?�?
     *
     * 这是"按源码标准用�?的那条路�?*不�?? merge 参数、不�?goToState �?标�??
     * 不�?�状态机**，只�?把属�? MIUI 的驱动权还回去�??
     * false = 保持旧�?�为（完全不管返回�?�）�?
     */
    public static boolean ENABLE_RETURN_HANDOFF = true;

    /**
     * 交回控制权后的�?�察窗口：这么久之内如�??leash 没有**朝源矩形收敛**�?
     * 就�?��??MIUI �?己不会把窗口送回去（它的 reverseAnimationWhenMerge
     * 在本�?TO_BACK 场景下两�?gate 都落空），于�?�?用�?��?�方�?(D)�?
     */
    public static final long RETURN_GUARD_MS = 300L;

    /**
     * (D) 备�?�方案：交回控制权失败时，用我们�?己�??SF 引擎�?�?�?
     * "全屏 �?源组件矩�?的返回动画�??
     *
     * <p><b>�?在前面的证据成立时才执�??/b>（�?? {@link #RETURN_GUARD_MS}），
     * 并且此时 MIUI 已经不在驱动这条 leash —�??**二�?�择�?，绝不抢**�?
     * false = 即使 MIUI 没�?�回也不动手（只打日志）�?
     */
    public static boolean ENABLE_RETURN_SF_FALLBACK = true;

    /**
     * ★★ �?13 �?�?**上滑返回动画的唯�?钥匙**（用户已批准）�??
     *
     * <p>�?题（源�??+ 真机 S4 双向取证）：上滑返回那条过渡�?{@code type=1021}
     * （{@code FIRST_CUSTOM+21}，{@code parallelCollectType=RECENTS}），而小米的两道反向闸门
     * 对它恒为 false�?
     * <pre>
     *   HyperViewScaleRemoteTransition.java:550-551
     *     mReverseToActivityA = info.getType() == 2 &amp;&amp; mOpenScene;   // 1021 �?2
     *     mReverseToActivityB = info.getType() == 1 &amp;&amp; !mOpenScene;  // 1021 �?1
     * </pre>
     * �?{@code reverseToStart=false} �?小米**根本不播**反向动画（用户看到的"上滑没有返回动画"）�??
     * 侧滑�?拖动�?径（应用�?SlidingPane �?己驱�?），�?以它有动画�??
     *
     * <p>�?�?关只做一件事：在负一屏进程里 hook
     * {@code HyperViewScaleRemoteTransition.handleMergeAnimation}，当
     * {@code info.getType()==1021 && mOpenScene==true && 我们没在驱动} 时，
     * �?{@code mReverseToActivityA} �?true，然后�??**小米�?己�??*
     * {@code mAnimationImpl.reverseAnimationWhenMerge(...)} 去播反向动画�?
     * 我们不写几何、不碰状态机、不驱动 leash�?
     *
     * <p>旧模块在这个位置改过同一�?字�?�，造成"系统回�?�面、动画却�?�?的�?��??—�??那是禁区�?
     * 现在之所以重新启�?，是因为对拉的另�?半已经结构�?�消失：
     * �?�?�? {@code swallowOn()==false} 时动手；�?打开段的方向闸保证我�?不去驱动返回段�??
     *
     * <p>false = 完全不动 MIUI 参数（回�?上滑无返回动�?的小米原状）�?
     */
    public static boolean FORCE_REVERSE_ON_MERGE_1021 = true;

    /**
     * ★★ �?13 �?（最终方案）�?*�?上滑返回"接入小米�?己那条带动画的关�?**�?
     *
     * <h4>真机取证（`shots/up_log.txt`，动作�?�齐后�??/h4>
     * 用户在负�?屏里上滑 20+ 次，25 秒内日志里只�?{@code lockToHideOverlay}�?
     * <b>{@code goToState：a} 零�?��?�{@code hideOverlay(flags=�?} 零�?��?�{@code 状�??�?EXITING} 零�??</b>
     * �?上滑这条关闭**根本不进 SlidingPane 状�?�机**（不�?animFlag �?设�??0"，是压根没调）�??
     * 而侧滑返回是 {@code hideOverlay(flags=1)} �?{@code 状�??OPENING �?EXITING} �?
     * {@code goToState：a �?animFlag = 1} �?有动画�??
     *
     * <h4>源码（`asst_full` �?{@code AssistantOverlayWindow.java}，两条路并排�?/h4>
     * <pre>
     * 797: public final void h() { super.h(); X(); }        // X() = lockToHideOverlay �?上滑走这�?
     * 802: public final void i() { super.i(); g(1); �?}     // g(1) �?侧滑走这条（带动画）
     * 754: public final void g(int i10) { �?super.g(i10); �?}
     * 553: public final void X() {                            // lockToHideOverlay �?�?
     *        if (!this.f11511v &amp;&amp; !this.Q) return;            // 空闲：什么都不做
     *        if (((p) T()).f11672n) { ((p) T()).h(); return; }// 拖动�?：收�?
     *        if (Q()) { R(); g(0); }                          // �?屏：无动画收�?
     *        else { f11510u.postDelayed(new bc.b(this, 5), 10L); }  // �?我们走这条：投�?�延迟任�?
     *      }
     * </pre>
     *
     * <h4>做法</h4>
     * hook {@code X()}（无参那�?）�??before：只�?
     * {@code f11511v==true（展�?�?�?&& Q()==false（屏幕亮�?&& 非拖动中} 时，
     * 反射�?{@code g(1)}�?*与侧滑同�?�?方�??*）并 {@code setResult(null)} 跳过原方法体
     * （避免它再投递那�?无动画的隐�??�?双重收起/�?�?下）�?
     * �?屏与拖动两条分�??*完全不�??**；任�?判据读不出来�?*不动�?*（保守）�?
     *
     * <p>false = 完全不动（回�?上滑无动�?的小米原状）�?
     */
    public static final boolean ROUTE_UP_SWIPE_TO_ANIMATED_CLOSE = false;

    // ------------------------------------------------------------------ 返回�?测的强确�?
    //
    // 真机教�??（�?? 3 �?）：�?�?�?笔写入的数�?�回�?就交回控制权�?
    // 结果打开动画刚起步（id=1 刚启�?）就�?�?己掐�? —�??
    // 日志里是 `setScale 明显回落�?200.0 �?0.5）`，那�?�? setCrop �?*像素宽度**
    // �?setScale �?*比例**混在�?�?量纲里比较的后果�?
    // 下面四条约束�?加�??写入判据"上（标准信号 hideOverlay/StateListener 不受影响）�??

    /**
     * 我们�?己�??SF 动画�?动后，至少经过这么久才允许�?�虑"返回"�?
     * 打开段本�?�? 900ms�?50ms 之前不可能是真的返回�?
     */
    public static final long RETURN_MIN_MS = 250L;

    /**
     * 连续多少笔位�?写入的方向与打开段相反，才算"位置方向反转"�?
     * 单笔抖动不再触发�?
     */
    public static int RETURN_REVERSE_WRITES = 3;

    /**
     * "尺�?�回�?"判据前必须先有�?�少笔几何写入（避免拿�??�?笔初始化值当参照）�??
     */
    public static final int RETURN_MIN_WRITES = 3;

    /**
     * 尺�?�相�?"打开段达到过的最大�?��??回落超过这个比例才算返回�?
     * 0.7 = 回落 30% 以上。setCrop(像素) �?setScale(比例) 都会**先换算成像素**
     * 再比较，绝不混量纲�??
     */
    public static float RETURN_SHRINK_RATIO = 0.7f;

    // ------------------------------------------------------------------ 延迟观测

    /**
     * 打开段的延迟拆解日志：打�?
     * {@code tap �?过渡�?�?�?拿到 leash �?startSFAnimation} 四个时刻的差值�??
     *
     * 用来�?点击到动画开始之间的延迟"归因到具体环节：
     * 如果 {@code 拿到leash �?startSFAnimation} �?有个位数�?秒，
     * 就�?�明延迟发生�? MIUI/应用起画�?之前，不�?�?核心造成的�??
     */
    public static boolean LOG_LATENCY_CHAIN = true;

    /**
     * leash 写入探针：把每一笔几何写入的**�?标层名�??*（前 60 字�?�）打出�?
     * （同�?�?层名�?记一次，�?�?{@link #LEASH_WRITE_PROBE_MAX} 条）�?
     *
     * 用�?�：判断真机�?{@code view-leash} 到底在�??几笔出现、名字是�?么形态，
     * 从�?�决�?严格判据"要不要放�?收紧。默认开，量很小�?
     */
    public static final boolean LOG_LEASH_WRITE_PROBE = true;

    /** 写入探针的条数上限�??*/
    public static final int LEASH_WRITE_PROBE_MAX = 40;

    /**
     * 还没认下 leash 时，�?多�?�察多少笔写入�??宽�?�兜底采�?"候�?��??
     *
     * 真机（�?? 4 �?）教�?：这�?数太小（当时�? 3）就会在 {@code view-leash} 出现之前
     * 先采�?{@code createViewLeash} 参数那个 Task 层�?�给足�?�察窗口�?
     * 让真正的 {@code view-leash} 有机会�??认下；万�?�?次都没出现，
     * 也仍然会在这么�?�笔之后兜底（并�? {@code sLeashLenient} 标�?�，后续�?�?升级替换）�??
     */
    public static final int LEASH_UNATTENDED_WRITES = 20;

    // ------------------------------------------------------------------ 安全阈�??

    /**
     * "停稳"判据①：距最后一�?leash 写入超过这么久，就�?�为动画停了�?
     * 旧代码里已经用过 1200ms 这个数，直接沿用�?
     */
    public static final long QUIET_WINDOW_MS = 1200L;

    /**
     * �?�?6 �?新�?�的闸：清模糊扫**�?�?"�?近这么久内发生过返回/关闭信号"时才允�?�执�?**�?
     *
     * <p>真机教�??：旧实现把看门狗 arm �?打开�?SF status=0"上，于是
     * "应用刚铺满�?�打�?动画的背�?模糊正在显�??时就跑了标准收尾 �?
     * 用户看到"背景模糊�?�?"。现�?：没有返回信号�?��?��??�?�?律拒绝执行�??
     */
    public static final long RETURN_SWEEP_MAX_MS = 3000L;

    /** "停稳"判据②：缩回信号出现后，�?多等这么久也必须收尾（兜底，防漏）�??*/
    public static final long SWEEP_DELAY_MS = 1200L;

    /** 兜底看门狗的采样周期�?*/
    public static final long WATCHDOG_TICK_MS = 300L;

    /**
     * 同一�?窗口上�??B) 的节流窗口：这么久之内只允�?�做�?次�??
     * 也是"防重复节�?的落点�??
     */
    public static final long SWEEP_COOLDOWN_MS = 1500L;

    /** SF 动画超过这么久没收到任何状�?�回调，就�?�为它卡住了，主�? stop�?*/
    public static final long SF_STALL_MS = 3000L;

    /** SF 动画的兜底停止时长：回调 status=0 缺失时（打断�?径不给）也�?�收手�??*/
    public static final long SF_FALLBACK_STOP_MS = 900L;

    // ------------------------------------------------------------------ 失败保险（硬不变量）

    /**
     * ★★ 失败保险�?*默�?? true，属于安全不变量，不建�??�?*�?
     *
     * <p>规则（�?? {@code AssistantAnimCore.armFirstCallbackFailsafe}）：
     * <ol>
     *   <li><b>首帧放�??</b>：{@code startSFAnimation} 之后**不立刻吞�?* —�??
     *       �?有收到�??�?�?SF 回调（status/progress）才�?{@code sDriverAlive} �?真�??
     *       吞写从那�?刻才生效�?/li>
     *   <li><b>无回调即时松�?/b>：{@link #SF_FIRST_CALLBACK_MS} 内一次回调都没有 �?
     *       立刻清掉吞写标志 + 尽力 stop，并把控制权交回 MIUI�?/li>
     *   <li>之后�?律不再吞写，直到下一�?{@code onMiuiTransitionStart} 重置�?/li>
     * </ol>
     *
     * <p>为什么必须有：真机出现过 "startSFAnimation id=2 已调�? �?*�?次回调都没来**
     * （开�?5 分钟、load 9.33），�?{@code stopSFAnimation} 还抛�?
     * {@code InvocationTargetException} �?我们�?不驱动�?�又还在�?MIUI 的写�?�?
     * 窗口停在半�??错位（用户看到的"卡住/乱掉"）�??
     */
    public static final boolean SF_FAILSAFE_RELEASE = true;

    /**
     * ★★ �?13 �?�?**首回调超时后，本次用户动作不再尝试接�?*（�??秒）�?
     *
     * <h4>真机证据（`shots/mergeguard_log.txt`，每次打�?都成对出现）</h4>
     * <pre>
     * 20:26:43.396  打开段：startSFAnimation id=6        �?�?1 次起
     * 20:26:43.547  SF 首回调超时（151ms）⇒ 已释放吞�?id=6
     * 20:26:43.548  打开段：startSFAnimation id=7        �?�?立刻又起�?次（�?leash、同源同�?标�??
     * 20:26:43.554  SF 首回调已到（7ms）status=0          �?7ms 就结�?
     * </pre>
     * 用户观感原话�?*"不是曲线抽搐，�?�是�?途打�?再出�?"** —�??就是这条重起造成的重�?�?
     * 打开动画�?到一半�??放弃、然后同�?�?leash 上从头又来一次�??
     *
     * <p>为什么会重起：超时只�?{@code sSfActive} 清了，�??MIUI 大约 150ms 后又会起�?条新过渡
     * （{@code onMiuiTransitionStart} 会�?��??�?周期已驱动�??）⇒ 下一�?leash 写入就再�?
     * 走到 {@code maybeStartOpenSf}�?
     *
     * <p>�?窗口�?**�?做一件事**：不重起。�?? MIUI �?己那条从头播完（�?"打断再重�?好得多）�?
     * 窗口�?2500ms：足以�?��??MIUI 同一动作的后�?过渡，又不会挡住用户的下�?次点击�??
     */
    public static final long SF_ABANDON_EPISODE_MS = 2500L;

    /**
     * 等�??�?�?SF 回调的时限�?�超过就认为"SF 没真的跑起来"，立刻释放吞写�??
     * 150ms 比一次�?��??SF 起跑�?lt;20ms）�?��?�得多，又远小于人眼�?感的卡顿�?
     */
    public static final long SF_FIRST_CALLBACK_MS = 150L;

    // ------------------------------------------------------------------ 与�?�面参�?�实现�?�齐（�??10 �?�?

    /**
     * �?对齐桌面 {@code SfAnimApiWrapper.java:53}：起 {@code startSFAnimation} 之前�?
     * 先在同一�?Transaction 里�?��?�层 {@code setVisibility(sc, true)}�?
     *
     * <p>真机现象�?SF 接受了调用但�?次回调都没有"。�?�面�?**每个 app/icon 层都先置�?�?**
     * �?start 的，我们以前没做这一步�?�默认开（�?�齐参�?�实现；失败�?�? verbose）�??
     */
    public static final boolean SF_SET_VISIBILITY_BEFORE_START = true;

    /**
     * �?{@code startSFAnimation} �?后一�?参数的取值模式（桌面�?{@code localSessionId}）�??
     *
     * <p>⚠️ <b>�?13 �?更�??/b>：这里的注释原来�?{@code sessionIdForNextAnim()} �?*实现�?反�??*�?
     * 真机日志（{@code sessionId=3,4,5,6,7,8�?} 逐个递�?�）证明当�??{@code =1} 跑的�?�?�?"�?
     * 下面�?b>实现</b>（不�?按原来的愿望）描述�??
     * <ul>
     *   <li>{@code 1} = <b>�?1 �?始�?��?�自�?</b>（现网实际�?�为，也�?桌面
     *       {@code SfAnim.java:425-428} 的做法）—�??日志里能看到 {@code sessionId} 递�?��??/li>
     *   <li>{@code 0} = 每个周期都传 0�?/li>
     *   <li>{@code 2} = <b>永远�?1</b>（旧模块"�?编�??1"的�?�为）�??/li>
     * </ul>
     * 也就�?�? {@code 1} �?{@code 2} 的�??义与�?文档旧版�?**互换**的�??
     * 要�?��??旧模块硬编码 1"时应该用 {@code 2}，不�?{@code 1}�?
     */
    public static final int SF_SESSION_ID_MODE = 1;

    /**
     * 打开段参数块�?*逐项明细**日志（默认关）�??
     * 打开后会�?8 �?start/end/damping/response 全打出来�?
     * 用于真机上核�?94 �?float 到底对不�?，�?�不�?�?看长度�??
     */
    public static boolean SF_VERBOSE_PARAM_DUMP = false;

    // ------------------------------------------------------------------ system_server �?读取证（DEV�?

    /**
     * �?DEV ONLY：system_server 侧�?�纯�?读�?�过渡取证探�?
     * （{@link SystemServerProbe}，实现测绘文�?
     * {@code D:\dsh\负一屏动�?system_server测绘.md} �?4 节的 S1~S5）�??
     *
     * <p><b>�?P1~P4 时把它改�?{@code true} 再构�?/b>；取证完必须改回 {@code false}
     * 再出 release 构建 —�??这是�?文件里唯�?影响 system_server 的开关�??
     *
     * <p>�?b>�?做三件�??/b>：�?�参数�?��?�字段�?�打日志（前�? {@code [SS]}）�??
     * 不改任何参数、不调任�?setter、不注册任何回调、不写文件�?�不起进程�??
     * 每一�?回调体�??try/catch 全包，异常绝不进 system_server�?
     *
     * <p>为什么单�?�?�?类�?�不放进 {@link AssistantAnimCore}：后者是**应用进程**
     * （负�?屏）的核心，�?system_server 里挂它的钩子没有意义；本探针�?�?
     * {@link MainHook} �?{@code "android"} 分支调用�?
     */
    public static final boolean DEV_SYSTEM_SERVER_PROBE = true;

    /**
     * ★★ DEV ONLY / L1 实验：把「上滑返回�?�那条过渡的 {@code type=1021} 窄归�?化成
     * {@code 2}（TRANSIT_CLOSE），让应用侧 {@code HyperViewScaleRemoteTransition}
     * �?己算�? {@code mReverseToActivityA=true}，走小米已有的反向动画分�?�?
     * 实现�?{@link SystemServerNormalize}；依�?�?测绘文档�?5 �?L1�?
     *
     * <p>⚠️ <b>真机取证（�?? 13 �?）证明这条收窄条件打错了�?�?/b>，因此开关已回到
     * {@code false}�?
     * <ul>
     *   <li>实测 5/5 次归�?化都�?进负�?屏（打开�?startSFAnimation�?相差 ±250ms 以内�?
     *       而唯�?�?次�??日志�?认的上滑返回（{@code hideOverlay(flags=1)}�?*没有�?归一�?*�?/li>
     *   <li>探针 S4 显示�?改的那条过渡�? {@code mode=4 leash=Task=2514#708}�?
     *       正是那一刻打�?段在驱动�?leash �?它其实是**应用�?动过�?**�?/li>
     *   <li>�?以把它的 type 改成 2（CLOSE）等于改变打�?段�??义（�?作用）�??
     *       而上滑返回压根没�?覆盖�?/li>
     * </ul>
     * 要再次使用必须先把收窄条件换�?�?实经过负�?�?的判�?—�??例�?�挂到更晚�??
     * {@code Transition.calculateTransitionInfo}（探�?S4 那一层，能看�?
     * {@code targets} �?{@code info.leashes}），而不�?�? {@code startMiuiNewTransition}
     * 这个"参与者还没收�?的时刻猜�?
     *
     * <p><b>默�?? {@code false}</b>，必须先拿到 {@code [SS]} �?P4 真机证据再打�?</b>
     * �?�?次只动一�?变�??）�?�打�?时�?�保�? {@link #DEV_SYSTEM_SERVER_PROBE} 也可见，
     * 以便�?改动时刻"�?S2/S3/S4 的�?�测对齐（�?�时 S1 会�?�位，改由旁观日志�?�录）�??
     *
     * <p>改动�?*�?有一�?*：{@code WindowOrganizerController.startMiuiNewTransition}
     * 的�??�?�?实�??{@code type}，且命中收窄条件时只改一次�??
     * �?b>�?/b>�?{@code Transition} 构�?��??b>�?/b>全局�?{@code mType}
     * —�??因为 {@code mType==1021} �?{@code TransitionControllerImpl
     * .shouldSkipSurfaceVisibilityRestore} 里也�?读（测绘文�??E-4）�??
     *
     * <p>⚠️ 这里**没有**「起始包 == com.miui.personalassistant」这条收窄，因为实测它在
     * 这个时刻**结构上不�?�?**（WCT 里没有包名字段；Binder 调用方是 Shell/SystemUI 那个
     * 系统进程，不�?负一屏；participant 列表要等方法返回后才�?充）。�?��??
     * {@code SystemServerNormalize#decide} 的注释�??
     * 实际生效的收窄条�?= {@code type==1021 && isRecentsTransition==true}
     * （�?��??同一 500ms bucket �?归一化一�?）�??
     */
    public static final boolean DEV_NORMALIZE_1021_TO_2 = false;

    /**
     * L1 的收窄条件之�?：�?��??{@code isRecentsTransition==true} 才归�?化�??
     *
     * <p>默�?? {@code true}。同�?�?{@code startMiuiNewTransition} 入口也承�?
     * "从�?��??�?近任务打�?应用"（Shell �?{@code LAUNCH_FROM_HOME}，�?�测绘文�? E-7），
     * 那时 {@code isRecentsTransition} �?false —�??这一条把那�?�情形排除掉�?
     * �?防�?��??伤别的过�?"的主要闸门�??
     */
    public static final boolean DEV_L1_REQUIRE_IS_RECENTS = true;

    /**
     * L1 �?选的更强收窄：�?��??{@code Binder.getCallingUid()} 等于�?进�??uid
     * （即调用方就�?托�??Shell Transitions 的那�?系统进程）�??
     *
     * <p>默�?? {@code false}：�?�常情况调用�?**�?就应�?**�?�?进程/shell uid�?
     * 但真机未验证前先不把它当�?约束，只�? uid/包名打进日志供人工核验；
     * �?�?{@code [SS] L1} �?{@code callerUid=} 与�?�期不�?�，把它打开即可收�?��??
     */
    public static final boolean DEV_L1_REQUIRE_UI_CALLER = false;

    // ------------------------------------------------------------------ 日志

    /** 详细日志：�?�帧写入（[层写�?]）只�?true 时打印�?�默认关，避免刷屏�??*/
    public static final boolean LOG_VERBOSE = false;

    /** �?通日志的每类上限条数。超过就�?�?"已限�?提示�?*/
    public static final int LOG_BUDGET = 60;
}
