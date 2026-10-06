package com.qianxia.hyperassistant;

import android.app.ActivityOptions;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;

import de.robv.android.xposed.XposedHelpers;

/**
 * 小部件/卡片点击 → 打开应用 的**启动动画**。
 *
 * ===== 要解决的问题 =====
 * 负一屏上点小部件打开应用时是"硬切"，没有系统桌面那种放大展开动画。
 * 逆向证据：`AppRoute$GeneralRoute.route()` 里就是裸的 `Context.startActivity(Intent)`，
 * 全程没有任何 `ActivityOptions`。
 *
 * ===== 关键发现：小米自己就有这个能力，只是没用 =====
 * `miuix.transition.ActivityOptionsHelper`（就在负一屏的 classes3.dex 里）提供了：
 * <pre>
 *   makeHyperViewScaleAnimation(View)                    -> ActivityOptions   HyperOS 专用
 *   makeScaleUpAnimationFromRoundedView(View, Rect, I, I)-> ActivityOptions   从圆角视图放大
 *   makeScaleUpAnim(View, Rect, I, I, I)                 -> ActivityOptions   通用放大
 *   captureSnapshot(View)                                -> Bitmap
 *   isSupportScaleUpDown() / isSupportMiuiClipAnimation() / isSupportMiuiRoundAnimation()
 * </pre>
 * 而它在**这个 APK 里外部零调用** —— 库装进来了，小部件点击却没走它。
 *
 * 所以我们不是在"发明"一个动画，而是**把小米自己的启动动画接上**，
 * 这样出来的观感天然跟系统一致。
 *
 * ===== 安全策略：先探测，后施加 =====
 * {@link Config#OPEN_ANIM_MODE} 默认 `"probe"`：只记录"如果接了会怎样"
 * （能力探测结果、工厂方法返回值、源视图位置），**不改任何行为**。
 * 确认工厂可用后再切到 `"apply"`。
 */
public final class WidgetOpenAnim {

    private WidgetOpenAnim() {
    }

    // ---- 点击源视图（从 AppGrid 的 onClick 记下）----
    private static volatile View sSourceView;
    private static volatile long sArmedAtMs;
    private static volatile long sLastTapAtMs;
    private static volatile String sArmedBy;

    /** 记下"用户刚点了哪张卡片"——它就是启动动画的起点。 */
    public static void arm(View v, String by) {
        if (v == null) {
            return;
        }
        sSourceView = v;
        sArmedAtMs = SystemClock.uptimeMillis();
        sLastTapAtMs = sArmedAtMs;
        sArmedBy = by;
        sArmCount++;
        // 第一次记源时顺便把能力探测补打一次 —— 安装期那次会被日志轮转冲掉
        if (!sCapLogged) {
            sCapLogged = true;
            logCapabilities(sClassLoader);
        }
        if (Config.LOG_OPEN_ANIM && sArmCount <= 200000) {
            int[] loc = new int[2];
            try {
                v.getLocationOnScreen(loc);
            } catch (Throwable ignored) {
            }
            L.i("[打开动画] 已记下点击源 #" + sArmCount + ": " + by
                    + "  " + fingerprint(v)
                    + "  pos=(" + loc[0] + "," + loc[1] + ")"
                    + "  size=" + v.getWidth() + "x" + v.getHeight()
                    + "  attached=" + v.isAttachedToWindow());
        }
        // ★ 只读：把点击源的**子层级**打出来。
        // 事实依据：快捷方式/大组件的点击源自带背景层
        // （图标由 miui.content.res.IconCustomizer 用 icon_background/border/mask 合成），
        // 整块截走就表现为"小组件内部颜色的矩形"。
        // 要改 pickSourceView 的策略，必须先看清里面到底有哪几层、各自多大。
        if (Config.LOG_SOURCE_TREE && sTreeCount < Config.LOG_SOURCE_TREE_MAX
                && v instanceof android.view.ViewGroup) {
            sTreeCount++;
            L.i("[源视图树] #" + sTreeCount + " 点击源是容器，打印子层级：");
            HookKit.dumpViewTree(v, "    ");
        }
        // ★ 源快照底：就在这一刻（UI 线程 + 负一屏还在屏幕上）把整屏拍下来。
        captureBackdrop(v);
    }

    private static int sTreeCount;

    // ======================================================================
    //  ★ 源快照底（挖洞法）：点击瞬间把**整屏负一屏**拍下来
    // ======================================================================
    //
    // 为什么必须在"点击瞬间"拍：
    //   · 这一刻 UI 线程就是当前线程（`arm()` 是从点击钩子进来的），`View.draw()` 合法；
    //   · 这一刻负一屏**还在屏幕上**，内容完整；
    //   · 只要晚到过渡开始，WM 就已经把壁纸/桌面/负一屏撤出合成 ⇒ 拍下来也是黑的。
    // 为什么是整屏：mask 那块 buffer 是 1200x2608 的 1:1 整屏层（见 CoreConfig.MASK_AS_BACKDROP）。

    private static volatile android.graphics.Bitmap sBackdrop;
    /** 快照的"底色"：不透明像素的均值色。用来在 mask buffer 里先铺一层**
     *  —— 因为 `DecorView.draw()` 画出来的负一屏**背景常常是透明的**（壁纸是另一个窗口的层，
     *  不在 DecorView 里）⇒ 不铺底就是"透明 mask"= 黑，正是 23:24 用户报的"还黑"。 */
    private static volatile int sBackdropFill = 0xff101418;

    /** 供 {@code AssistantAnimCore} 的挖洞法取"负一屏整屏快照"。 */
    public static android.graphics.Bitmap backdrop() {
        return sBackdrop;
    }

    /** 快照底色（不透明像素均值）。 */
    public static int backdropFill() {
        return sBackdropFill;
    }

    private static void captureBackdrop(View v) {
        if (!CoreConfig.MASK_AS_BACKDROP) {
            return;
        }
        try {
            View root = v.getRootView();
            if (root == null) {
                root = v;
            }
            int w = root.getWidth();
            int h = root.getHeight();
            if (w <= 0 || h <= 0) {
                L.w("[源快照底] 根视图尺寸为 0（" + w + "x" + h + "）⇒ 本轮不挖洞");
                return;
            }
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bmp);
            root.draw(c);
            android.graphics.Bitmap old = sBackdrop;
            sBackdrop = bmp;
            // ★ 统计"这张 DecorView 自绘到底透不透明" + 取不透明像素均值当底色。
            //   实测意义极大：如果透明占比高，说明负一屏背景（模糊壁纸）不在 DecorView 里，
            //   那 mask 直接画这张图 = 透明 mask = 黑（用户 23:24 报的"还黑"）。
            long sumR = 0, sumG = 0, sumB = 0;
            int opaque = 0, semi = 0, total = 0;
            for (int yy = 0; yy < h; yy += 16) {
                for (int xx = 0; xx < w; xx += 16) {
                    int px = bmp.getPixel(xx, yy);
                    total++;
                    int a = (px >>> 24) & 0xff;
                    if (a >= 250) {
                        opaque++;
                        sumR += (px >> 16) & 0xff;
                        sumG += (px >> 8) & 0xff;
                        sumB += px & 0xff;
                    } else if (a > 8) {
                        semi++;
                    }
                }
            }
            if (opaque > 0) {
                int fr = (int) (sumR / opaque), fg = (int) (sumG / opaque), fb = (int) (sumB / opaque);
                sBackdropFill = 0xff000000 | (fr << 16) | (fg << 8) | fb;
            }
            L.i("[源快照底] 已拍下负一屏整屏快照 " + w + "x" + h
                    + "（root=" + root.getClass().getName() + "）"
                    + "  不透明=" + opaque + "/" + total
                    + "(" + (total == 0 ? 0 : (100 * opaque / total)) + "%)"
                    + "  半透明=" + semi
                    + "  底色=0x" + Integer.toHexString(sBackdropFill));
            // ★ 绝不在这里 recycle(old)：mask 那边**每帧都在后台线程上**用这张位图画"底"，
            //   一 recycle 就会撞上 `Canvas: trying to use a recycled bitmap`（真机实测）。
            if (old != null) {
                old = null;                     // 只是断开本地引用，交给 GC
            }
        } catch (Throwable t) {
            L.w("[源快照底] 拍快照失败: " + t);
        }
    }

    /**
     * 视图"身份指纹"。
     *
     * 目的：小米那套动画靠 `android:activity.view.name` **按名字找源视图**。
     * 如果某个视图没有可用的名字（没有资源 id、没有 transitionName），
     * 系统定位不到它，只能退而求其次 —— 表现就是**黑边/白边/裁切**。
     *
     * 把这些属性打出来，就能对照"能正常动画的视图"和"边缘出问题的视图"有何差别。
     */
    public static String fingerprint(View v) {
        StringBuilder sb = new StringBuilder(v.getClass().getSimpleName());
        int id = v.getId();
        String idName = "NO_ID";
        if (id != View.NO_ID) {
            idName = "0x" + Integer.toHexString(id);
            try {
                idName = v.getResources().getResourceEntryName(id);
            } catch (Throwable ignored) {
            }
        }
        String tn = null;
        try {
            tn = v.getTransitionName();
        } catch (Throwable ignored) {
        }
        sb.append("  id=").append(idName);
        sb.append("  transName=").append(tn == null ? "null" : "'" + tn + "'");
        sb.append("  clickable=").append(v.isClickable());
        sb.append("  bg=").append(v.getBackground() != null);
        sb.append("  alpha=").append(L.f(v.getAlpha()));
        sb.append("  outline=").append(v.getOutlineProvider() != null
                ? v.getOutlineProvider().getClass().getSimpleName() : "null");
        return sb.toString();
    }

    private static volatile ClassLoader sClassLoader;
    private static volatile boolean sCapLogged;
    private static int sArmCount;

    public static void setClassLoader(ClassLoader cl) {
        sClassLoader = cl;
    }

    /**
     * 消费掉记源状态（一次性）。避免一次点击影响后面无关的启动。
     */
    public static View consumeArmed() {
        if (!isArmed()) {
            return null;
        }
        View v = sSourceView;
        sSourceView = null;
        sArmedAtMs = 0L;
        return v;
    }

    public static boolean isArmed() {
        return sSourceView != null
                && (SystemClock.uptimeMillis() - sArmedAtMs) < Config.SOURCE_VIEW_ARM_MS;
    }

    public static View sourceView() {
        return sSourceView;
    }

    /** 记源（=用户那一按）的时刻（uptimeMillis）。① 延迟量化要拿它当起点。 */
    public static long armedAtMs() {
        return sArmedAtMs;
    }

    /**
     * **最近一次用户点击**的时刻，与 `armedAtMs()` 不同 —— 它不会被 `consumeArmed()` 清掉。
     *
     * 为什么需要：`consumeArmed()` 在启动那一刻就把 `sArmedAtMs` 置 0 了，
     * 而"第一帧画面变更"发生在启动**之后**，于是延迟探针拿到的是 0 → 量不到。
     */
    public static long lastTapAtMs() {
        return sLastTapAtMs;
    }

    // ---------------------------------------------------------------- 能力探测

    public static void logCapabilities(ClassLoader cl) {
        Class<?> h = HookKit.findClass(cl, Targets.AOPTS_HELPER);
        if (h == null) {
            L.w("[打开动画] 找不到 " + Targets.AOPTS_HELPER + " —— 说明这个 ROM 没带这个库");
            return;
        }
        for (String m : new String[]{
                Targets.M.IS_SUPPORT_SCALE,
                Targets.M.IS_SUPPORT_CLIP,
                Targets.M.IS_SUPPORT_ROUND,
                Targets.M.IS_SUPPORT_UPDATE}) {
            try {
                Object r = XposedHelpers.callStaticMethod(h, m);
                L.i("[打开动画] 能力探测 " + m + "() = " + r);
            } catch (Throwable t) {
                L.i("[打开动画] 能力探测 " + m + "() 调用失败: " + t.getClass().getSimpleName());
            }
        }
    }

    // ---------------------------------------------------------------- 反射缓存

    /**
     * 反射结果缓存。
     *
     * 之前每次启动都调 `getDeclaredMethods()` 扫一遍（最多三次），
     * 这是在**点击的关键路径上**做重活。既然是固定不变的东西，就没必要每次重算。
     */
    private static volatile Class<?> sHelper;
    private static volatile java.lang.reflect.Method sMHyperScale;
    private static volatile java.lang.reflect.Method sMRounded;
    private static volatile java.lang.reflect.Method sMScaleUp;
    private static volatile java.lang.reflect.Method sMCapture;
    private static volatile boolean sResolved;

    private static synchronized void resolve(ClassLoader cl) {
        if (sResolved) {
            return;
        }
        sResolved = true;
        Class<?> h = HookKit.findClass(cl, Targets.AOPTS_HELPER);
        if (h == null) {
            return;
        }
        sHelper = h;
        for (java.lang.reflect.Method m : h.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            String n = m.getName();
            try {
                if (n.equals(Targets.M.MAKE_HYPER_SCALE) && ps.length == 1) {
                    m.setAccessible(true);
                    sMHyperScale = m;
                } else if (n.equals(Targets.M.MAKE_ROUNDED) && ps.length == 4
                        && ps[1] == android.graphics.Rect.class) {
                    m.setAccessible(true);
                    sMRounded = m;
                } else if (n.equals(Targets.M.MAKE_SCALEUP) && ps.length == 5
                        && ps[1] == android.graphics.Rect.class) {
                    m.setAccessible(true);
                    sMScaleUp = m;
                } else if (n.equals(Targets.M.CAPTURE_SNAPSHOT) && ps.length == 1
                        && ps[0] == View.class) {
                    m.setAccessible(true);
                    sMCapture = m;
                }
            } catch (Throwable ignored) {
            }
        }
        L.i("[打开动画] 工厂解析完成: hyper=" + (sMHyperScale != null)
                + " rounded=" + (sMRounded != null)
                + " scaleUp=" + (sMScaleUp != null)
                + " capture=" + (sMCapture != null));
    }

    /**
     * 从被点击的视图往上找"卡片容器"作为动画起点。
     *
     * 为什么需要：实测源视图是小图标时（`ImageView` 128×128），打开动画里
     * **图标带白边** —— 因为 MIUI 会对这个 View 截图再放大，而这个 View 的背景是白的；
     * 往上取一层"卡片容器"（带圆角背景的那层）观感才对。
     *
     * 规则保守：最多往上 2 层，且祖先面积不超过子视图的
     * {@link Config#CONTAINER_MAX_AREA_RATIO} 倍，避免爬到整页容器。
     */
    public static View pickSourceView(View v) {
        if (v == null || !"container".equalsIgnoreCase(Config.SOURCE_VIEW_MODE)) {
            return v;
        }
        View best = v;
        long childArea = (long) Math.max(1, v.getWidth()) * Math.max(1, v.getHeight());
        View cur = v;
        for (int level = 0; level < 2; level++) {
            android.view.ViewParent p = cur.getParent();
            if (!(p instanceof View)) {
                break;
            }
            View pv = (View) p;
            int w = pv.getWidth();
            int h = pv.getHeight();
            if (w <= 0 || h <= 0) {
                break;
            }
            if ((long) w * h > childArea * Config.CONTAINER_MAX_AREA_RATIO) {
                break;
            }
            best = pv;
            cur = pv;
        }
        if (best != v && Config.LOG_OPEN_ANIM) {
            L.i("[打开动画] 源视图上提: " + v.getClass().getSimpleName()
                    + " " + v.getWidth() + "x" + v.getHeight()
                    + "  ->  " + best.getClass().getSimpleName()
                    + " " + best.getWidth() + "x" + best.getHeight()
                    + "  bg=" + (best.getBackground() != null));
        }
        return best;
    }

    // ---------------------------------------------------------------- 构造选项

    /**
     * 用**小米自己的工厂**构造 ActivityOptions。
     *
     * 按"参数最明确 → 最不明确"的顺序尝试，每一步都记日志，
     * 这样一次真机测试就能知道哪个工厂可用。
     */
    public static ActivityOptions buildOptions(ClassLoader cl, View src) {
        if (src == null) {
            return null;
        }
        resolve(cl);
        Class<?> h = sHelper;
        if (h == null) {
            return null;
        }

        int[] loc = new int[2];
        int w = 0;
        int hh = 0;
        try {
            src.getLocationOnScreen(loc);
            w = src.getWidth();
            hh = src.getHeight();
        } catch (Throwable ignored) {
        }

        // 1) makeHyperViewScaleAnimation(View) —— 参数最明确，优先试
        ActivityOptions o = callHyperScale(src);
        if (o != null) {
            L.i("[打开动画] 用 makeHyperViewScaleAnimation(View) 成功");
            return o;
        }

        // 2) makeScaleUpAnimationFromRoundedView(View, Rect, int, int)
        try {
            java.lang.reflect.Method m = sMRounded;
            if (m != null) {
                android.graphics.Rect r =
                        new android.graphics.Rect(loc[0], loc[1], loc[0] + w, loc[1] + hh);
                Object res = m.invoke(null, src, r, w, hh);
                L.i("[打开动画] makeScaleUpAnimationFromRoundedView -> " + res);
                if (res instanceof ActivityOptions) {
                    return (ActivityOptions) res;
                }
            }
        } catch (Throwable t) {
            L.w("[打开动画] rounded 工厂失败: " + t);
        }

        // 3) makeScaleUpAnim(View, Rect, int, int, int)
        try {
            java.lang.reflect.Method m = sMScaleUp;
            if (m != null) {
                android.graphics.Rect r =
                        new android.graphics.Rect(loc[0], loc[1], loc[0] + w, loc[1] + hh);
                Object res = m.invoke(null, src, r, w, hh, 0);
                L.i("[打开动画] makeScaleUpAnim -> " + res);
                if (res instanceof ActivityOptions) {
                    return (ActivityOptions) res;
                }
            }
        } catch (Throwable t) {
            L.w("[打开动画] makeScaleUpAnim 失败: " + t);
        }

        L.w("[打开动画] 三个工厂都没产出可用 ActivityOptions");
        return null;
    }

    /** 用缓存的方法调 `makeHyperViewScaleAnimation(View)`。 */
    private static ActivityOptions callHyperScale(View src) {
        java.lang.reflect.Method m = sMHyperScale;
        if (m == null) {
            return null;
        }
        try {
            Object r = m.invoke(null, src);
            L.i("[打开动画] makeHyperViewScaleAnimation(View) -> " + r);
            return r instanceof ActivityOptions ? (ActivityOptions) r : null;
        } catch (Throwable t) {
            L.w("[打开动画] makeHyperViewScaleAnimation 失败: " + t);
            return null;
        }
    }

    public static Bundle optionsBundle(ClassLoader cl, View src) {
        ActivityOptions o = buildOptions(cl, src);
        return o == null ? null : o.toBundle();
    }

    /**
     * 远程动画的 key。**必须摘掉。**
     *
     * 真机实测：带着它启动会被系统拒绝 ——
     * <pre>
     * java.lang.SecurityException: Permission Denial: starting Intent {...}
     *     from ProcessRecord{... com.miui.personalassistant/u0a143} with remoteTransition
     * </pre>
     * "with remoteTransition" 的意思是系统把它当作**远程动画**，而远程动画需要签名级权限
     * `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS` —— 只有桌面 / SystemUI 持有，
     * 负一屏没有。所以原调用（不带 options）能过，我们带的就被拒。
     *
     * 小米那个工厂会把 abort listener 塞进去（它是给桌面用的），我们摘掉它，
     * 但**保留 `android:activity.animType` 与 `android:activity.view.name`** ——
     * 动画本身还在，只是不再需要特权。
     */
    private static final String KEY_ANIM_ABORT = "android:activity.animAbortListener";

    /** 取小米的 options 并摘掉需要特权的远程动画 key。 */
    public static Bundle cleanedOptionsBundle(ClassLoader cl, View src) {
        ActivityOptions o = buildOptions(cl, src);
        if (o == null) {
            return null;
        }
        Bundle b = o.toBundle();
        b.remove(KEY_ANIM_ABORT);
        return b;
    }

    /**
     * 逐级剥离顺序 —— 用来**自动二分**出"到底是哪个 key 触发了远程动画权限检查"。
     *
     * 实测已知：
     *   · 原样（含 animAbortListener）        → SecurityException "with remoteTransition"
     *   · 只摘 animAbortListener              → 仍然 SecurityException
     * 所以还有别的 key 在触发。与其猜，不如一次点击里逐级试，日志会直接告诉我们是哪一组。
     *
     * 顺序按"最可能触发权限检查"排前面。
     */
    public static final String[] DROP_ORDER = {
            KEY_ANIM_ABORT,                       // android:activity.animAbortListener
            "miui:activity.options.injector",     // MIUI 自己的注入器，嫌疑最大
            "key_miui_config_flag",
            "android.activity.splashScreenStyle",
            "android:activity.view.name",
    };

    /** 未做任何清理的原始 options（供 OpenAnimHooks 逐级剥离）。 */
    public static Bundle rawOptionsBundle(ClassLoader cl, View src) {
        ActivityOptions o = buildOptions(cl, src);
        if (o == null) {
            return null;
        }
        Bundle b = o.toBundle();
        L.i("[打开动画] 原始 options keys=" + b.keySet());
        return b;
    }

    /**
     * 框架原生方案（备选）：`ActivityOptions.makeScaleUpAnimation(View,int,int,int,int)`。
     *
     * API 35 里仍然存在（已用 javap 在 android.jar 上确认）。它**不涉及远程动画**，
     * 所以一定能过权限检查；代价是观感可能不如小米那套精细。
     */
    public static Bundle frameworkScaleUpBundle(View src) {
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            if (w <= 0 || h <= 0) {
                w = 200;
                h = 200;
            }
            ActivityOptions o = ActivityOptions.makeScaleUpAnimation(src, 0, 0, w, h);
            Bundle b = o.toBundle();
            L.i("[打开动画] 框架 makeScaleUpAnimation -> keys=" + b.keySet());
            return b;
        } catch (Throwable t) {
            L.w("[打开动画] 框架 makeScaleUpAnimation 失败: " + t);
            return null;
        }
    }

    /** 供探测日志用。 */
    public static String describe(Context ctx, android.content.Intent intent) {
        return "context=" + (ctx == null ? "null" : ctx.getClass().getName())
                + " intent=" + (intent == null ? "null" : intent.toString())
                + " armed=" + isArmed()
                + (sArmedBy == null ? "" : " by=" + sArmedBy);
    }
}
