package com.qianxia.hyperassistant;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.os.SystemClock;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 第一阶段：只打日志，不改行为。
 *
 * 目的——用真实调用数据回答四个问题：
 *   Q1  D(float) / F(float) 是逐帧高频调用，还是只在起止各调一次？
 *   Q2  进度值域是多少？0..1 还是像素？方向是哪个？
 *   Q3  J(View) 拿到的到底是不是内容根 View？
 *   Q4  SlidingPaneWindow 和 AssistantOverlayWindow 谁的方法真正被调用？
 *
 * 拿到日志后按 Q2 填 Config.PROGRESS_MIN/MAX_HINT，再打开 ENABLE_ANIM。
 */
public final class ProbeHooks {

    private ProbeHooks() {
    }

    // ---------------------------------------------------------------- 统计

    static final class Stats {
        final String name;
        long windowStart = SystemClock.uptimeMillis();
        int windowCount;
        int total;
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        float last = Float.NaN;

        Stats(String name) {
            this.name = name;
        }

        void feed(float v) {
            if (Float.isNaN(v)) {
                return;
            }
            total++;
            windowCount++;
            if (v < min) {
                min = v;
            }
            if (v > max) {
                max = v;
            }
            last = v;
            maybeReport();
        }

        private void maybeReport() {
            long now = SystemClock.uptimeMillis();
            long dt = now - windowStart;
            if (dt < Config.PROBE_STATS_MS) {
                return;
            }
            float hz = windowCount * 1000f / dt;
            L.i(String.format(java.util.Locale.US,
                    "[统计] %s  调用 %d 次 / %.0fms  (%.1f 次每秒)  值域 %.4f..%.4f  末值 %.4f  累计 %d",
                    name, windowCount, (float) dt, hz, min, max, last, total));
            windowStart = now;
            windowCount = 0;
        }

        String brief() {
            return "range[" + L.f(min) + ".." + L.f(max) + "] n=" + total;
        }
    }

    static final class Throttle {
        private final String key;
        private int seen;
        private long lastLog;

        Throttle(String key) {
            this.key = key;
        }

        boolean allow() {
            seen++;
            if (seen <= Config.PROBE_FIRST_N) {
                lastLog = SystemClock.uptimeMillis();
                return true;
            }
            long now = SystemClock.uptimeMillis();
            if (now - lastLog >= Config.PROBE_THROTTLE_MS) {
                lastLog = now;
                return true;
            }
            return false;
        }
    }

    private static final Map<String, Stats> STATS = new HashMap<>();
    private static final Map<String, Throttle> THROTTLES = new HashMap<>();

    // ---- 打断检测状态 ----
    private static volatile float sLastRaw = Float.NaN;
    private static volatile long sLastChangeMs;
    private static int sInterrupts;

    static void noteProgress(float raw) {
        if (Float.isNaN(raw)) {
            return;
        }
        if (Float.isNaN(sLastRaw) || Math.abs(raw - sLastRaw) > 1e-6f) {
            sLastRaw = raw;
            sLastChangeMs = SystemClock.uptimeMillis();
        }
    }

    /**
     * 打断检测：一次新的交互开始时，进度是否停在中间？
     * 停在中间 = 上一次动画还没跑完就被接管 = 一次打断。
     */
    private static void checkInterrupt(String label, String why, float raw) {
        float rawOrLast = Float.isNaN(raw) ? sLastRaw : raw;
        float t = Easing.normalize(rawOrLast);
        long since = SystemClock.uptimeMillis() - sLastChangeMs;
        boolean midFlight = !Float.isNaN(t) && t > 0.004f && t < 0.996f && since < 600L;
        if (midFlight) {
            sInterrupts++;
            L.i("[打断] " + label + " " + why + " —— 在动画中途接管: p=" + L.f(t)
                    + ", 距上次进度变化 " + since + "ms   (累计 " + sInterrupts + " 次)");
        } else {
            L.i("[打断] " + label + " " + why + " —— 发生在静止/端点: p=" + L.f(t)
                    + ", 距上次变化 " + since + "ms");
        }
    }

    private static void interruptProbe(ClassLoader cl, String cls, String label) {
        HookKit.hook(cl, cls, Targets.M.SCROLL_START, float.class,
                new HookKit.Cb(label + ".H [interrupt]") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        checkInterrupt(label, "overlayScrollStart", HookKit.firstFloat(p));
                    }
                });
        HookKit.hook(cl, cls, Targets.M.DRAG_START, new HookKit.Cb(label + ".A [interrupt]") {
            @Override
            protected void onBefore(MethodHookParam p) {
                checkInterrupt(label, "onDragStart", Float.NaN);
            }
        });
    }

    private static Stats stats(String key) {
        Stats s = STATS.get(key);
        if (s == null) {
            s = new Stats(key);
            STATS.put(key, s);
        }
        return s;
    }

    private static Throttle throttle(String key) {
        Throttle t = THROTTLES.get(key);
        if (t == null) {
            t = new Throttle(key);
            THROTTLES.put(key, t);
        }
        return t;
    }

    // ---------------------------------------------------------------- 安装

    /**
     * 桌面进程诊断 —— 回答"桌面到底能不能 hook"这个关键问题。
     *
     * 之前的静态分析结论是：`com.miui.home` 的两个 APK 里**一个 dex 都没有**，
     * 它跑在小米自研的 HYOS Rust 运行时上，所以 LSPosed（只能 hook ART/Java）够不着。
     *
     * 但这个结论值得用真机验证一次，因为用户明确说"要抓得去系统桌面抓"。
     * 诊断会回答三件事：
     *   1. LSPosed 到底能不能在这个进程里加载我们的模块？（能加载 ⇒ 它是 ART 进程）
     *   2. 桌面自己的类（如 Launcher Activity）在不在？
     *   3. 这个 ClassLoader 上到底挂了哪些 dex？
     */
    public static void launcherDiagnostic(ClassLoader cl) {
        L.i("################################################");
        L.i("# 桌面进程诊断 (com.miui.home)");
        L.i("################################################");
        L.i("process = " + processName());
        L.i("sdk     = " + android.os.Build.VERSION.SDK_INT);
        L.i("classLoader = " + (cl == null ? "null" : cl.getClass().getName()));

        String[] own = {
                "com.miui.home.launcher.Launcher",
                "com.miui.home.launcher.Application",
                "com.miui.home.settings.IconCustomizeActivity",
                "com.miui.home.launcher.allapps.settings.CategorySettingActivity",
        };
        for (String c : own) {
            try {
                Class<?> k = XposedHelpers.findClass(c, cl);
                L.i("  [桌面自身类] 找到   " + c + "   -> " + k.getClassLoader());
            } catch (Throwable t) {
                L.i("  [桌面自身类] 找不到 " + c + "   (" + t.getClass().getSimpleName() + ")");
            }
        }

        for (String c : new String[]{"android.view.View", "android.app.Activity",
                "android.view.WindowManager", "android.animation.ValueAnimator"}) {
            try {
                XposedHelpers.findClass(c, cl);
                L.i("  [框架类] 找到   " + c);
            } catch (Throwable t) {
                L.i("  [框架类] 找不到 " + c);
            }
        }

        for (String c : new String[]{Targets.MIUIX_EASE_MANAGER, Targets.PANE_WINDOW,
                Targets.ASSISTANT_WINDOW, Targets.AIDL_CALLBACK}) {
            try {
                XposedHelpers.findClass(c, cl);
                L.i("  [目标类] 找到   " + c);
            } catch (Throwable t) {
                L.i("  [目标类] 找不到 " + c);
            }
        }

        // 枚举这个 ClassLoader 上挂的 dex —— 这是"有没有 Java 代码"的决定性证据
        try {
            Object pathList = XposedHelpers.getObjectField(cl, "pathList");
            Object dexElements = XposedHelpers.getObjectField(pathList, "dexElements");
            int n = java.lang.reflect.Array.getLength(dexElements);
            L.i("  dexElements 数量 = " + n + "  ← 0 或极少说明这个进程几乎没有自己的 Java 代码");
            for (int i = 0; i < Math.min(n, 12); i++) {
                Object el = java.lang.reflect.Array.get(dexElements, i);
                try {
                    Object dexFile = XposedHelpers.getObjectField(el, "dexFile");
                    if (dexFile != null) {
                        L.i("    [" + i + "] " + XposedHelpers.callMethod(dexFile, "getName"));
                    } else {
                        L.i("    [" + i + "] <dexFile=null>");
                    }
                } catch (Throwable t2) {
                    L.i("    [" + i + "] <读取失败 " + t2.getClass().getSimpleName() + ">");
                }
            }
        } catch (Throwable t) {
            L.i("  无法枚举 dexElements: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
        L.i("################################################");
    }

    /** 进程名。用公开 API，避免依赖 @hide 的 ActivityThread。 */
    private static String processName() {
        try {
            String n = android.app.Application.getProcessName();
            if (n != null) {
                return n;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream("/proc/self/cmdline")));
            try {
                String s = r.readLine();
                return s == null ? "?" : s.trim();
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * 记录澎湃OS 4 里与本模块相关的系统特性开关，让探测日志自带上下文。
     *
     * 这些 prop 是从设备上实测发现的，属于 OS4 的新动效/运行时相关项。
     */
    private static void logOs4Features() {
        String[][] props = {
                {"ro.mi.os.version.name", "系统版本"},
                {"ro.mi.os.version.incremental", "ROM 构建"},
                {"rust.runtime_active", "HYOS Rust 运行时是否激活"},
                {"rust.runtime_version", "HYOS Rust 运行时版本"},
                {"persist.sys.hyper_transition", "hyper 过渡框架"},
                {"persist.sys.hyper_transition_v", "hyper 过渡框架版本"},
                {"persist.sys.hyper_view_scale", "hyper 视图缩放（可能已对内容做缩放，注意别叠加）"},
                {"persist.hyper.barfollow_anim", ""},
                {"ro.hyper_compiler.rulebased.jit_enabled", "hyper 规则化 JIT"},
        };
        for (String[] p : props) {
            String v = getProp(p[0]);
            if (v != null && !v.isEmpty()) {
                L.i("  prop " + p[0] + " = " + v + (p[1].isEmpty() ? "" : "   (" + p[1] + ")"));
            }
        }
        String rt = getProp("rust.runtime_active");
        if ("1".equals(rt)) {
            L.i("  注意: 本机 HYOS Rust 运行时处于激活状态。桌面/相册/天气可能已迁入该运行时，"
                    + "但负一屏（本进程）是标准 ART 应用 —— 这正是本模块能工作的前提。");
        }
    }

    private static String getProp(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object v = c.getMethod("get", String.class).invoke(null, key);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public static void install(ClassLoader cl) {        L.i("========== 探测模式安装 ==========");
        L.i("宿主 process=" + processName()
                + "  sdk=" + android.os.Build.VERSION.SDK_INT);
        logOs4Features();
        paneWindow(cl, Targets.PANE_WINDOW, "SlidingPaneWindow");
        paneWindow(cl, Targets.ASSISTANT_WINDOW, "AssistantOverlayWindow");
        transitionController(cl);
        stateManager(cl);
        animListeners(cl);
        playbackController(cl);
        overlayWindow(cl);
        miuixEase(cl);
        interruptProbe(cl, Targets.PANE_WINDOW, "SlidingPaneWindow");
        interruptProbe(cl, Targets.ASSISTANT_WINDOW, "AssistantOverlayWindow");
        L.i("========== 探测安装完毕（只读，不改行为）==========");
    }

    /**
     * 小米真正的曲线入口 —— 这是把「小米到底用哪条曲线」实测出来的关键 hook。
     *
     * 静态分析定不了 styleId（它是运行时算出来的，见 XiaomiCurves 的说明），
     * 所以只能在这里打点：把 styleId、还原出的名字、以及弹簧参数全部打出来。
     *
     * 日志形如：
     *   EaseManager.getStyle(6 (CUBIC_OUT), params=[])
     *   EaseManager.getStyle(0 (SPRING), params=[0.9000, 0.3000])
     */
    private static void miuixEase(ClassLoader cl) {
        HookKit.hookAll(cl, Targets.MIUIX_EASE_MANAGER, Targets.M.GET_STYLE,
                new HookKit.Cb("EaseManager.getStyle") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        Object a0 = (p.args != null && p.args.length > 0) ? p.args[0] : null;
                        Object a1 = (p.args != null && p.args.length > 1) ? p.args[1] : null;
                        StringBuilder sb = new StringBuilder("EaseManager.getStyle(");
                        if (a0 instanceof Integer) {
                            sb.append(XiaomiCurves.describe((Integer) a0));
                        } else {
                            sb.append(a0);
                        }
                        sb.append(", params=");
                        if (a1 instanceof float[]) {
                            float[] fs = (float[]) a1;
                            sb.append('[');
                            for (int i = 0; i < fs.length; i++) {
                                if (i > 0) {
                                    sb.append(", ");
                                }
                                sb.append(L.f(fs[i]));
                            }
                            sb.append(']');
                        } else {
                            sb.append(a1);
                        }
                        sb.append(')');
                        L.i(sb.toString());
                    }
                });

        HookKit.hookAll(cl, Targets.MIUIX_ANIM_CONFIG, Targets.M.SET_EASE,
                new HookKit.Cb("AnimConfig.setEase") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        L.i("AnimConfig.setEase(style="
                                + ((p.args != null && p.args.length > 0) ? p.args[0] : null)
                                + ", args=" + (p.args == null ? 0 : p.args.length) + ")");
                    }
                });
    }

    // ---------------------------------------------------------------- 各类

    /** SlidingPaneWindow / AssistantOverlayWindow —— 进度主入口。 */
    private static void paneWindow(ClassLoader cl, String cls, String label) {
        probeFloat(cl, cls, Targets.M.PROGRESS_1, label + ".D(float)");
        probeFloat(cl, cls, Targets.M.PROGRESS_2, label + ".F(float)");
        probeFloat(cl, cls, Targets.M.SCROLL_START, label + ".H(float)  [scrollStart]");
        probeFloat(cl, cls, Targets.M.SCROLL_END, label + ".G(float)  [scrollEnd]");

        // w() -> float : 当前进度
        HookKit.hook(cl, cls, Targets.M.GET_PROGRESS, new HookKit.Cb(label + ".w()") {
            @Override
            protected void onAfter(MethodHookParam p) {
                Object r = p.getResult();
                if (r instanceof Float) {
                    float v = (Float) r;
                    stats(label + ".w()").feed(v);
                    if (throttle(label + ".w()").allow()) {
                        L.i(label + ".w() = " + L.f(v));
                    }
                }
            }
        });

        // J(View) : 内容视图注入点 —— 关键时刻
        final boolean[] dumped = {false};
        HookKit.hook(cl, cls, Targets.M.SET_CONTENT, View.class, new HookKit.Cb(label + ".J(View)") {
            @Override
            protected void onBefore(MethodHookParam p) {
                View v = HookKit.firstView(p);
                L.i(label + ".J(View)  args[0] = "
                        + (v == null ? "null" : v.getClass().getName()));
                if (!dumped[0] && v != null) {
                    dumped[0] = true;
                    L.i("---- 开始打印视图树 ----");
                    HookKit.dumpViewTree(v, "  ");
                    L.i("---- 视图树结束 ----");
                }
            }
        });

        // K(boolean) : setVisible + targetAlpha
        HookKit.hook(cl, cls, Targets.M.SET_VISIBLE, boolean.class,
                new HookKit.Cb(label + ".K(boolean) [setVisible]") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        L.i(label + ".K(boolean) = " + p.args[0]);
                    }
                });

        // A() : onDragStart
        HookKit.hook(cl, cls, Targets.M.DRAG_START, new HookKit.Cb(label + ".A() [onDragStart]") {
            @Override
            protected void onBefore(MethodHookParam p) {
                L.i(label + ".A() [onDragStart]");
            }
        });

        // I() : onSurfaceReady
        HookKit.hook(cl, cls, Targets.M.SURFACE_READY, new HookKit.Cb(label + ".I() [onSurfaceReady]") {
            @Override
            protected void onBefore(MethodHookParam p) {
                L.i(label + ".I() [onSurfaceReady]");
            }
        });

        // M(int) : showOverlay
        HookKit.hook(cl, cls, Targets.M.SHOW_OVERLAY, int.class,
                new HookKit.Cb(label + ".M(int) [showOverlay]") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        L.i(label + ".M(int) [showOverlay] = " + p.args[0]);
                    }
                });

        // N(long) : startScroll
        HookKit.hook(cl, cls, Targets.M.START_SCROLL, long.class,
                new HookKit.Cb(label + ".N(long) [startScroll]") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        L.i(label + ".N(long) [startScroll] = " + p.args[0]);
                    }
                });
    }

    private static void probeFloat(ClassLoader cl, String cls, String method, String tag) {
        final Stats st = stats(tag);
        final Throttle th = throttle(tag);
        HookKit.hook(cl, cls, method, float.class, new HookKit.Cb(tag) {
            @Override
            protected void onBefore(MethodHookParam p) {
                float v = HookKit.firstFloat(p);
                st.feed(v);
                noteProgress(v);
                if (th.allow()) {
                    L.i(tag + " = " + L.f(v) + "   " + st.brief());
                }
            }
        });
    }

    /** SlidingPaneTransitionController.e(float) —— 过渡进度 setter。 */
    private static void transitionController(ClassLoader cl) {
        probeFloat(cl, Targets.PANE_TRANSITION, Targets.M.SET_PROGRESS,
                "TransitionController.e(float)");
    }

    /** SlidingPaneStateManager.a(State, AnimatorSetBuilder, r) -> AnimatorSet */
    private static void stateManager(ClassLoader cl) {
        final HookKit.Cb cb = new HookKit.Cb("SlidingPaneStateManager.a(...) [buildAnimator]") {
            @Override
            protected void onAfter(MethodHookParam p) {
                Object r = p.getResult();
                if (r instanceof AnimatorSet) {
                    AnimatorSet set = (AnimatorSet) r;
                    L.i("buildAnimator -> AnimatorSet  childCount=" + set.getChildAnimations().size()
                            + "  duration=" + set.getDuration()
                            + "  state=" + p.args[0]);
                    for (Animator a : set.getChildAnimations()) {
                        L.i("    child: " + a.getClass().getSimpleName()
                                + " duration=" + a.getDuration()
                                + " interpolator=" + a.getInterpolator());
                    }
                } else {
                    L.i("buildAnimator -> " + r);
                }
            }
        };

        Class<?> cState = HookKit.findClass(cl, Targets.PANE_STATE);
        Class<?> cBuilder = HookKit.findClass(cl, "com.miui.launcher.overlay.server.pane.d");
        Class<?> cR = HookKit.findClass(cl, "com.miui.launcher.overlay.server.pane.r");
        if (cState != null && cBuilder != null && cR != null) {
            HookKit.hook(cl, Targets.PANE_STATE_MGR, Targets.M.BUILD_ANIMATOR,
                    cState, cBuilder, cR, cb);
        } else {
            L.w("签名类未找全，退回 hookAll");
            HookKit.hookAll(cl, Targets.PANE_STATE_MGR, Targets.M.BUILD_ANIMATOR, cb);
        }

        // SlidingPaneState.a() -> float ：该状态的目标进度
        for (String sub : new String[]{
                Targets.PANE_STATE,
                Targets.PANE_STATE + "$a",
                Targets.PANE_STATE + "$b",
                Targets.PANE_STATE + "$c"}) {
            HookKit.hook(cl, sub, Targets.M.STATE_TARGET, new HookKit.Cb(
                    HookKit.shortName(sub) + ".a() [stateTarget]") {
                @Override
                protected void onAfter(MethodHookParam p) {
                    Object r = p.getResult();
                    L.i(HookKit.shortName(p.method.getDeclaringClass().getName())
                            + ".a() -> " + (r instanceof Float ? L.f((Float) r) : String.valueOf(r)));
                }
            });
        }
    }

    /** AnimationListener.b(float) 的两个实现。 */
    private static void animListeners(ClassLoader cl) {
        probeFloat(cl, Targets.PANE_ANIM_IMPL_N, Targets.M.ANIM_PROGRESS,
                "AnimListenerImpl(n).b(float)");
        probeFloat(cl, Targets.PANE_ANIM_IMPL_Q, Targets.M.ANIM_PROGRESS,
                "AnimListenerImpl(q).b(float)");
    }

    /** AnimatorPlaybackController：可拖拽播放控制器。 */
    private static void playbackController(ClassLoader cl) {
        probeFloat(cl, Targets.PANE_PLAYBACK, Targets.M.SET_PROGRESS,
                "PlaybackController.e(float)");

        HookKit.hook(cl, Targets.PANE_PLAYBACK, Targets.M.ON_ANIM_UPDATE, ValueAnimator.class,
                new HookKit.Cb("PlaybackController.onAnimationUpdate") {
                    private final Throttle th = throttle("PlaybackController.onAnimationUpdate");

                    @Override
                    protected void onBefore(MethodHookParam p) {
                        if (!th.allow()) {
                            return;
                        }
                        Object a = p.args[0];
                        if (a instanceof ValueAnimator) {
                            Object v = ((ValueAnimator) a).getAnimatedValue();
                            L.i("onAnimationUpdate animatedValue=" + v);
                        }
                    }
                });
    }

    /** 窗口层：拿到 window / decor view 的时机。 */
    private static void overlayWindow(ClassLoader cl) {
        HookKit.hook(cl, Targets.OVERLAY_WINDOW, "onAttachedToWindow",
                new HookKit.Cb("LauncherOverlayWindow(c5.e).onAttachedToWindow") {
                    @Override
                    protected void onAfter(MethodHookParam p) {
                        Object self = p.thisObject;
                        L.i("c5.e.onAttachedToWindow  this=" + self);
                        dumpWindowIfPossible(self);
                    }
                });

        HookKit.hook(cl, Targets.ASSISTANT_WINDOW, "onAttachedToWindow",
                new HookKit.Cb("AssistantOverlayWindow.onAttachedToWindow") {
                    @Override
                    protected void onAfter(MethodHookParam p) {
                        Object self = p.thisObject;
                        L.i("AssistantOverlayWindow.onAttachedToWindow  this=" + self);
                        dumpWindowIfPossible(self);
                    }
                });

        probeFloat(cl, Targets.OVERLAY_WINDOW, "r", "LauncherOverlayWindow(c5.e).r(float)");
        probeFloat(cl, Targets.ASSISTANT_WINDOW, "r", "AssistantOverlayWindow.r(float)");
    }

    /** 通过反射拿到 Window 的 decorView（如果有）。 */
    private static void dumpWindowIfPossible(Object self) {
        try {
            Object win = XposedHelpers.getObjectField(self, "mWindow");
            if (win == null) {
                for (java.lang.reflect.Field f : self.getClass().getSuperclass() != null
                        ? self.getClass().getSuperclass().getDeclaredFields()
                        : new java.lang.reflect.Field[0]) {
                    if (android.view.Window.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        win = f.get(self);
                        break;
                    }
                }
            }
            if (win instanceof android.view.Window) {
                View decor = ((android.view.Window) win).peekDecorView();
                L.i("  找到 Window，decorView = " + decor);
                if (decor != null) {
                    HookKit.dumpViewTree(decor, "    ");
                }
            } else {
                L.i("  未找到 mWindow 字段（字段名可能是别的，需另找）");
            }
        } catch (Throwable t) {
            L.w("dumpWindowIfPossible 失败: " + t);
        }
    }
}
