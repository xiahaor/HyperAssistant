package com.qianxia.hyperassistant;

/**
 * 进度映射。
 *
 * ⚠️ 默认是 `"system"` —— **不映射，原样放行，让系统用自己那条曲线**。
 *
 * 为什么默认不动：
 *   逆向发现负一屏的过渡动画走的是小米自研的 miuix.animation (Folme) 弹簧引擎，
 *   曲线由运行时的 `EaseManager.getStyle(styleId, params)` 决定，而且 styleId **不是编译期常量**
 *   （由方法返回值算出）。也就是说我们无法静态地"抄"到那条曲线。
 *   最忠实的做法就是**别覆盖它** —— 不覆盖，用的就必然是小米自己的曲线。
 *
 * 这里的选项是给你**可选**微调用的，而且刻意只提供小米自己的曲线族
 * （见 {@link XiaomiCurves}），不再使用任何外部风格的曲线。
 *
 * 拖拽 / 提交两个阶段必须分开：
 *   - 拖拽：手指按着，**绝不能过冲**。只能用单调的 *_IN / *_OUT / *_INOUT / LINEAR 族。
 *   - 提交：松手后的收敛，可以用 BOUNCE / ELASTIC 这类带回弹的（小米的「Q弹」就在这里）。
 */
public final class Easing {

    private Easing() {
    }

    /** 表示"不映射，用系统自己的曲线"。 */
    public static final String SYSTEM = "system";

    // ================================================================ 拖拽阶段

    /**
     * 跟手映射。默认 SYSTEM = 原样返回。
     *
     * 曲线名取自 {@link XiaomiCurves}（小米自己的 EaseStyleDef），
     * 且只接受单调不过冲的那几个：linear / quadOut / cubicOut / quartOut /
     * quintOut / sineOut / expoOut / decelerate。
     */
    public static float mapDrag(float t) {
        float x = clamp01(t);
        if (SYSTEM.equalsIgnoreCase(Config.DRAG_CURVE)) {
            return x;
        }
        int id = styleIdForDrag(Config.DRAG_CURVE);
        if (id < 0) {
            return x;
        }
        float r = curveValue(id, x);
        return blend(x, r, Config.DRAG_STRENGTH);
    }

    // ================================================================ 提交阶段

    /** 提交动画曲线。默认 SYSTEM = 不改（系统自己做）。 */
    public static float mapCommit(float t) {
        float x = clamp01(t);
        if (SYSTEM.equalsIgnoreCase(Config.COMMIT_CURVE)) {
            return x;
        }
        Integer id = XiaomiCurves.idOf(Config.COMMIT_CURVE);
        if (id == null) {
            return x;
        }
        float r = curveValue(id, x);
        return blend(x, r, Config.COMMIT_STRENGTH);
    }

    private static float blend(float x, float target, float strength) {
        if (strength == 1f) {
            return target;
        }
        return x + (target - x) * strength;
    }

    /** 拖拽曲线名 → 小米 styleId；只允许单调不过冲的族。 */
    private static int styleIdForDrag(String name) {
        if (name == null) {
            return -1;
        }
        switch (name.toLowerCase()) {
            case "linear":
                return XiaomiCurves.LINEAR;
            case "quadout":
                return XiaomiCurves.QUAD_OUT;
            case "cubicout":
                return XiaomiCurves.CUBIC_OUT;
            case "quartout":
                return XiaomiCurves.QUART_OUT;
            case "quintout":
                return XiaomiCurves.QUINT_OUT;
            case "sineout":
                return XiaomiCurves.SINE_OUT;
            case "expoout":
                return XiaomiCurves.EXPO_OUT;
            case "decelerate":
                return XiaomiCurves.DECELERATE;
            default:
                L.w("拖拽曲线 '" + name + "' 不在允许的单调族里，已按 system 处理");
                return -1;
        }
    }

    /**
     * 按小米的 EaseStyleDef 语义求值。
     *
     * 说明：这里是按标准 easing 公式实现的（与 miui 的命名语义一致），
     * 用于**可选的手感微调**；若要求与系统 100% 一致，应该走
     * {@link XiaomiCurves#getSystemStyle} 直接拿系统原厂对象（见 AnimHooks 的 ease 覆盖）。
     */
    public static float curveValue(int id, float t) {
        float x = clamp01(t);
        switch (id) {
            case XiaomiCurves.LINEAR:
                return x;
            case XiaomiCurves.QUAD_IN:
                return x * x;
            case XiaomiCurves.QUAD_OUT:
                return 1f - (1f - x) * (1f - x);
            case XiaomiCurves.QUAD_INOUT:
                return x < 0.5f ? 2f * x * x : 1f - 2f * (1f - x) * (1f - x);
            case XiaomiCurves.CUBIC_IN:
                return x * x * x;
            case XiaomiCurves.CUBIC_OUT:
                return 1f - pow3(1f - x);
            case XiaomiCurves.CUBIC_INOUT:
                return x < 0.5f ? 4f * x * x * x : 1f - pow3(-2f * x + 2f) / 2f;
            case XiaomiCurves.QUART_IN:
                return x * x * x * x;
            case XiaomiCurves.QUART_OUT:
                return 1f - powN(1f - x, 4);
            case XiaomiCurves.QUART_INOUT:
                return x < 0.5f ? 8f * powN(x, 4) : 1f - powN(-2f * x + 2f, 4) / 2f;
            case XiaomiCurves.QUINT_IN:
                return powN(x, 5);
            case XiaomiCurves.QUINT_OUT:
                return 1f - powN(1f - x, 5);
            case XiaomiCurves.QUINT_INOUT:
                return x < 0.5f ? 16f * powN(x, 5) : 1f - powN(-2f * x + 2f, 5) / 2f;
            case XiaomiCurves.SINE_IN:
                return 1f - (float) Math.cos(x * Math.PI / 2.0);
            case XiaomiCurves.SINE_OUT:
                return (float) Math.sin(x * Math.PI / 2.0);
            case XiaomiCurves.SINE_INOUT:
                return (float) (-(Math.cos(Math.PI * x) - 1.0) / 2.0);
            case XiaomiCurves.EXPO_IN:
                return x <= 0f ? 0f : (float) Math.pow(2.0, 10.0 * x - 10.0);
            case XiaomiCurves.EXPO_OUT:
                return x >= 1f ? 1f : 1f - (float) Math.pow(2.0, -10.0 * x);
            case XiaomiCurves.EXPO_INOUT:
                if (x <= 0f) {
                    return 0f;
                }
                if (x >= 1f) {
                    return 1f;
                }
                return x < 0.5f
                        ? (float) (Math.pow(2.0, 20.0 * x - 10.0) / 2.0)
                        : (float) ((2.0 - Math.pow(2.0, -20.0 * x + 10.0)) / 2.0);
            case XiaomiCurves.DECELERATE:
                return 1f - (1f - x) * (1f - x);
            case XiaomiCurves.ACCELERATE_DECELERATE:
            case XiaomiCurves.ACCELERATE_INTERPOLATOR:
                return (float) (-(Math.cos(Math.PI * x) - 1.0) / 2.0);
            case XiaomiCurves.BOUNCE_EASE_IN:
                return 1f - bounceOut(1f - x);
            case XiaomiCurves.BOUNCE_EASE_OUT:
            case XiaomiCurves.BOUNCE:
                return bounceOut(x);
            case XiaomiCurves.BOUNCE_EASE_INOUT:
                return x < 0.5f
                        ? (1f - bounceOut(1f - 2f * x)) / 2f
                        : (1f + bounceOut(2f * x - 1f)) / 2f;
            default:
                // SPRING / SPRING_PHY / FRICTION / REBOUND / PERLIN 等是物理或特殊风格，
                // 用标准公式表达不出来 —— 必须走 getSystemStyle 拿系统原厂对象。
                L.w("曲线 " + XiaomiCurves.describe(id)
                        + " 无法用公式表达，请改用 XiaomiCurves.getSystemStyle()");
                return x;
        }
    }

    // ================================================================ 插值器

    /**
     * 提交动画用的 Interpolator，按 {@link Config#COMMIT_CURVE} 走小米自己的曲线族。
     *
     * 若选的是 **弹簧类**（spring / springPhy / springFunction / bounce），
     * 则用 {@link AnalyticSpring} 的**解析解**：由刚度 + 阻尼比闭式算出轨迹与时长，
     * 这样"小米的 SPRING(id 0) + 测出的刚度/阻尼"可以直接变成一条真曲线，
     * 而不是拿贝塞尔去近似它。
     *
     * 注意：这只对 **AnimatorSet 路径**有效。负一屏的主路径走 miuix Folme，
     * 那条路要用 {@link XiaomiCurves#getSystemStyle} 覆盖 EaseStyle（见 AnimHooks）。
     */
    public static android.view.animation.Interpolator commitInterpolator() {
        Integer id = XiaomiCurves.idOf(Config.COMMIT_CURVE);
        if (id != null && isSpringFamily(id)) {
            AnalyticSpring s = new AnalyticSpring()
                    .setResponsePeriod(Config.period())
                    .setDampingRatio(Config.damping())
                    .setMinimumVisibleChange(Config.SPRING_MIN_VISIBLE_CHANGE)
                    .setSingleFrameMs(Config.ASSUMED_FRAME_MS)
                    .setStartValue(0f)
                    .setEndValue(1f)
                    .setStartVelocity(0f)
                    .computeParams();
            L.i("提交动画用解析解弹簧（小米参数化 ζ/周期）: " + s.describe());
            return s.asInterpolator();
        }
        return new android.view.animation.Interpolator() {
            @Override
            public float getInterpolation(float t) {
                return mapCommit(t);
            }
        };
    }

    /** 小米表里属于"物理弹簧"族的 styleId。 */
    public static boolean isSpringFamily(int styleId) {
        switch (styleId) {
            case XiaomiCurves.SPRING:
            case XiaomiCurves.SPRING_PHY:
            case XiaomiCurves.SPRING_GRAVITY:
            case XiaomiCurves.SPRING_FUNCTION:
            case XiaomiCurves.BOUNCE:
            case XiaomiCurves.BOUNCE_EASE_IN:
            case XiaomiCurves.BOUNCE_EASE_OUT:
            case XiaomiCurves.BOUNCE_EASE_INOUT:
            case XiaomiCurves.REBOUND:
                return true;
            default:
                return false;
        }
    }

    // ================================================================ 速度驱动（AOSP 技法）

    /*
     * 技法来源：AOSP Launcher3 的 com.android.app.animation.Interpolators
     *
     *     private static final float FAST_FLING_PX_MS = 10;
     *     scrollInterpolatorForVelocity(v) = |v| > 10px/ms ? SCROLL : SCROLL_CUBIC;
     *     overshootInterpolatorForVelocity(v) = OvershootInterpolator(min(|v|, 3));
     *     reverse(i) = t -> 1 - i.getInterpolation(1 - t);
     *
     * 其中 SCROLL 就是 quintic ease-out（t^5），SCROLL_CUBIC 就是 cubic ease-out（t^3）——
     * **正好等于小米 EaseStyleDef 表里的 QUINT_OUT 与 CUBIC_OUT**。
     * 所以这套速度驱动规则可以用小米自己的常量原样表达出来，不需要引入外来曲线。
     */

    /** 按松手速度选提交曲线，返回小米 styleId。 */
    public static int commitStyleForVelocity(float pxPerMs) {
        return Math.abs(pxPerMs) > Config.FAST_FLING_PX_PER_MS
                ? XiaomiCurves.QUINT_OUT     // == AOSP SCROLL（快甩：起步猛、尾巴长）
                : XiaomiCurves.CUBIC_OUT;    // == AOSP SCROLL_CUBIC（慢放：收敛更柔）
    }

    /** 按速度求值提交曲线。 */
    public static float mapCommitForVelocity(float t, float pxPerMs) {
        if (!Config.VELOCITY_AWARE) {
            return mapCommit(t);
        }
        return curveValue(commitStyleForVelocity(pxPerMs), clamp01(t));
    }

    /**
     * 反向曲线：g(x) = 1 - f(1 - x)。
     * AOSP 对反向动画用独立曲线表达同一件事（例如 FAST_OUT_SLOW_IN_REVERSE），
     * 这里直接用它的 reverse 公式。
     */
    public static float reverse(int styleId, float t) {
        return 1f - curveValue(styleId, 1f - clamp01(t));
    }

    /** 过冲量按速度缩放，上限 max（AOSP 的 overshootInterpolatorForVelocity 做法）。 */
    public static float overshootAmountForVelocity(float pxPerMs) {
        return Math.min(Math.abs(pxPerMs), Config.OVERSHOOT_MAX);
    }

    // ================================================================ 工具

    public static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float pow3(float v) {
        return v * v * v;
    }

    private static float powN(float v, int n) {
        float r = 1f;
        for (int i = 0; i < n; i++) {
            r *= v;
        }
        return r;
    }

    private static float bounceOut(float x) {
        final float n1 = 7.5625f;
        final float d1 = 2.75f;
        if (x < 1f / d1) {
            return n1 * x * x;
        } else if (x < 2f / d1) {
            float u = x - 1.5f / d1;
            return n1 * u * u + 0.75f;
        } else if (x < 2.5f / d1) {
            float u = x - 2.25f / d1;
            return n1 * u * u + 0.9375f;
        } else {
            float u = x - 2.625f / d1;
            return n1 * u * u + 0.984375f;
        }
    }

    // ================================================================ 归一化

    public static float normalize(float raw) {
        float lo = Config.PROGRESS_MIN_HINT;
        float hi = Config.PROGRESS_MAX_HINT;
        if (hi == lo) {
            return 0f;
        }
        float t = clamp01((raw - lo) / (hi - lo));
        return Config.INVERT_PROGRESS ? (1f - t) : t;
    }

    public static float denormalize(float t) {
        float lo = Config.PROGRESS_MIN_HINT;
        float hi = Config.PROGRESS_MAX_HINT;
        float x = clamp01(t);
        return Config.INVERT_PROGRESS ? lo + (1f - x) * (hi - lo) : lo + x * (hi - lo);
    }
}
