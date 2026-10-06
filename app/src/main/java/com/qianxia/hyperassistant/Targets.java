package com.qianxia.hyperassistant;

/**
 * Hook 点清单 —— 全部来自对 com.miui.personalassistant 25.40.34.00 的 DEX 逆向。
 *
 * ⚠️ 这是内测版 ROM（OS4.0.0.33，beta=true publish=false），类名/方法名随版本会变。
 *    所有 hook 都走 HookKit 的安全包装：找不到方法只记日志，绝不影响宿主。
 *
 * 名称还原依据：DEX 的 SourceFile 属性保留了原文件名，而类名被 R8 混淆成单字母。
 *
 * 原类名                                → 实际类 / 方法
 * ---------------------------------------------------------------------------
 * SlidingPaneWindow                     → com.miui.launcher.overlay.server.pane.SlidingPaneWindow
 *   A()            日志 onDragStart open =
 *   D(float)       纯计算，高频          ← 进度更新（插值主入口）
 *   F(float)       纯计算，高频          ← 进度更新（插值主入口）
 *   G(float)       日志 callback overlayScrollEnd progress：
 *   H(float)       日志 callback overlayScrollStart
 *   I()            日志 onSurfaceReady
 *   J(View)        无字符串              ← 推测为内容视图注入点
 *   K(boolean)     日志 setVisible targetAlpha =
 *   M(int)         日志 showOverlay:
 *   N(long)        日志 startScroll:
 *   w() -> float   当前进度 getter
 *
 * SlidingPaneTransitionController       → ...pane.p
 *   e(float)       设置过渡进度
 * SlidingPaneStateManager               → ...pane.SlidingPaneStateManager
 *   a(State, AnimatorSetBuilder, r) -> AnimatorSet   ← 开/关动画本体
 * SlidingPaneState                      → ...pane.h（子类 h$a / h$b / h$c）
 *   a() -> float   该状态的目标进度
 * AnimationListener                     → ...pane.AnimationListener
 *   b(float)       动画进度回调（实现：pane.n / pane.q）
 * AnimatorPlaybackController            → ...pane.b
 *   onAnimationUpdate(ValueAnimator)
 * Interpolators                         → ...pane.f（静态插值器字段）
 *
 * LauncherOverlayWindow                 → c5.e            （46 方法）
 * LauncherOverlayCallback               → c5.d
 * ILauncherOverlay            (AIDL)    → b5.a
 * ILauncherOverlayCallback    (AIDL)    → b5.b   D(float) / f(float) / s(float)
 * AssistantOverlayWindow                → com.miui.personalassistant.core.overlay.AssistantOverlayWindow
 * AssistantOverlayService               → com.miui.personalassistant.core.overlay.AssistantOverlayService
 */
public final class Targets {

    private Targets() {
    }

    public static final String PKG = "com.miui.personalassistant";

    /** 系统桌面。静态分析显示它没有 dex（跑在 HYOS Rust 运行时上），这里放一个诊断入口去真机验证。 */
    public static final String LAUNCHER_PKG = "com.miui.home";

    // ---- 动画核心 ----
    public static final String PANE_WINDOW = "com.miui.launcher.overlay.server.pane.SlidingPaneWindow";
    public static final String PANE_TRANSITION = "com.miui.launcher.overlay.server.pane.p";
    public static final String PANE_STATE_MGR = "com.miui.launcher.overlay.server.pane.SlidingPaneStateManager";
    public static final String PANE_STATE = "com.miui.launcher.overlay.server.pane.h";
    public static final String PANE_ANIM_LISTENER = "com.miui.launcher.overlay.server.pane.AnimationListener";
    public static final String PANE_ANIM_IMPL_N = "com.miui.launcher.overlay.server.pane.n";
    public static final String PANE_ANIM_IMPL_Q = "com.miui.launcher.overlay.server.pane.q";
    public static final String PANE_PLAYBACK = "com.miui.launcher.overlay.server.pane.b";
    public static final String PANE_INTERPOLATORS = "com.miui.launcher.overlay.server.pane.f";

    // ---- 窗口 / 跨进程 ----
    public static final String OVERLAY_WINDOW = "c5.e";
    public static final String OVERLAY_CALLBACK = "c5.d";
    public static final String AIDL_OVERLAY = "b5.a";
    public static final String AIDL_CALLBACK = "b5.b";
    public static final String ASSISTANT_WINDOW =
            "com.miui.personalassistant.core.overlay.AssistantOverlayWindow";
    public static final String ASSISTANT_SERVICE =
            "com.miui.personalassistant.core.overlay.AssistantOverlayService";

    // ---- 小米自研动画引擎 miuix.animation (Folme) ----
    // 负一屏的过渡动画实际走这条链路：
    //   AnimState/ValueProperty -> Folme.useValue() -> AnimConfig.setEase(
    //       EaseManager.getStyle(styleId, params)) -> FolmeStyle.to(target, config)
    // 所以"小米的曲线"= EaseManager 里那张 EaseStyleDef 表（见 XiaomiCurves）。
    public static final String MIUIX_EASE_MANAGER = "miuix.animation.utils.EaseManager";
    public static final String MIUIX_ANIM_CONFIG = "miuix.animation.base.AnimConfig";
    public static final String MIUIX_FOLME_EASE = "miuix.animation.FolmeEase";
    public static final String MIUIX_FOLME = "miuix.animation.Folme";

    // ---- 小部件点击 → 打开应用（"全动画"的挂点）----
    // 链路：AppGrid$2.onClick(View) → AppRoute.route → GeneralRoute.route
    //       → Context.startActivity(Intent)   ← 这里没有 ActivityOptions，就是硬切
    /** 负一屏服务卡片网格的点击监听器（全进程唯一调用 AppRoute.route 的地方）。 */
    public static final String APP_GRID_LISTENER =
            "com.miui.miuiwidget.servicedelivery.appitem.AppGrid$2";
    public static final String APP_ROUTE =
            "com.miui.miuiwidget.servicedelivery.appitem.AppRoute";
    public static final String APP_ROUTE_GENERAL =
            "com.miui.miuiwidget.servicedelivery.appitem.AppRoute$GeneralRoute";
    public static final String APP_ROUTE_NATIVE =
            "com.miui.miuiwidget.servicedelivery.appitem.AppRoute$NativeRoute";
    public static final String APP_ITEM =
            "com.miui.miuiwidget.servicedelivery.model.AppItem";

    /** 小米自己的启动动画工厂 —— 就装在负一屏的 classes3.dex 里，但外部零调用。 */
    public static final String AOPTS_HELPER = "miuix.transition.ActivityOptionsHelper";

    /** Activity 启动的真正实现点。 */
    public static final String CONTEXT_IMPL = "android.app.ContextImpl";

    /**
     * MAML 小组件的外层宿主（负一屏自己的类，来自视图树 dump：
     * `WidgetCardView → MaMlHostView → LayerView → InnerView`）。
     *
     * 为什么要它：MAML 小部件的点击**不调 `View.performClick`**、也不走
     * `AppGrid$2.onClick`，所以那两条记源钩子都命中不了 → `armed=false` →
     * 起应用时没有 HVS 的 options，动画整个没有（实测 204x204 那个小部件：
     * startActivity=1 次、动画=0 次）。在它的 `dispatchTouchEvent` 上补记源。
     */
    public static final String MAML_HOST_VIEW =
            "com.miui.personalassistant.core.view.MaMlHostView";

    /**
     * MAML 引擎**真正接收触摸**的那几层（实测，真手指点 204x204 小部件时打印出来的）：
     * `com.miui.maml.elements.LayerScreenElement$LayerView`、
     * `com.miui.maml.component.MamlView$InnerView`。
     *
     * 宿主 `MaMlHostView` 的 dispatchTouchEvent **收不到**（引擎直接派发进元素树），
     * 所以记源钩子必须挂在这两个类上。
     */
    public static final String[] MAML_TOUCH_VIEWS = {
            "com.miui.maml.elements.LayerScreenElement$LayerView",
            "com.miui.maml.component.MamlView$InnerView",
            MAML_HOST_VIEW,
    };

    // ---- system_server 侧（提权用）----
    public static final String SYSTEM_SERVER_PKG = "android";
    /** 权限检查的所在类。 */
    public static final String ATMS = "com.android.server.wm.ActivityTaskManagerService";
    /** 小米往框架里加的"Hyper 视图缩放过渡"判定类。 */
    public static final String HYPER_SCALE_STUB =
            "com.android.server.wm.HyperViewScaleTransitionStub";

    /** 方法名常量：混淆名集中在这里，将来适配新 ROM 只改这一处。 */
    public static final class M {
        private M() {
        }

        // SlidingPaneWindow
        public static final String DRAG_START = "A";       // onDragStart(open)
        public static final String PROGRESS_1 = "D";       // float
        public static final String PROGRESS_2 = "F";       // float
        public static final String SCROLL_END = "G";       // float
        public static final String SCROLL_START = "H";     // float
        public static final String SURFACE_READY = "I";
        public static final String SET_CONTENT = "J";      // (View)
        public static final String SET_VISIBLE = "K";      // (boolean)
        public static final String SHOW_OVERLAY = "M";     // (int)
        public static final String START_SCROLL = "N";     // (long)
        public static final String GET_PROGRESS = "w";     // () -> float

        // 其它
        public static final String SET_PROGRESS = "e";     // pane.p / pane.b : (float)
        public static final String BUILD_ANIMATOR = "a";   // SlidingPaneStateManager
        public static final String STATE_TARGET = "a";     // pane.h : () -> float
        public static final String ANIM_PROGRESS = "b";    // AnimationListener / pane.n / pane.q : (float)
        public static final String ON_ANIM_UPDATE = "onAnimationUpdate";

        // miuix.animation (Folme) —— 小米真正的曲线入口
        public static final String GET_STYLE = "getStyle";   // EaseManager: (int, float...) -> EaseStyle
        public static final String SET_EASE = "setEase";     // AnimConfig: (EaseStyle, ...) 
        public static final String FOLME_TO = "to";          // FolmeStyle: (target, AnimConfig...)

        // 打开动画相关
        public static final String ON_CLICK = "onClick";
        public static final String ROUTE = "route";
        public static final String START_ACTIVITY = "startActivity";
        public static final String MAKE_HYPER_SCALE = "makeHyperViewScaleAnimation";
        public static final String MAKE_ROUNDED = "makeScaleUpAnimationFromRoundedView";
        public static final String MAKE_SCALEUP = "makeScaleUpAnim";
        public static final String CAPTURE_SNAPSHOT = "captureSnapshot";
        public static final String IS_SUPPORT_SCALE = "isSupportScaleUpDown";
        public static final String IS_SUPPORT_CLIP = "isSupportMiuiClipAnimation";
        public static final String IS_SUPPORT_ROUND = "isSupportMiuiRoundAnimation";
        public static final String IS_SUPPORT_UPDATE = "isSupportUpdateScaleUpDownData";

        // system_server 侧
        public static final String CHECK_PERMISSION = "checkPermission";
        public static final String IS_HYPER_SCALE_REMOTE = "isHyperViewScaleRemote";
    }
}
