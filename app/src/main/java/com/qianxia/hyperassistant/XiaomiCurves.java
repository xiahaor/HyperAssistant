package com.qianxia.hyperassistant;

import java.util.HashMap;
import java.util.Map;

/**
 * 小米自己的曲线体系。
 *
 * 这张表是从固件里还原出来的 —— `miuix.animation.utils.EaseManager$EaseStyleDef`
 * 的 42 个静态常量（直接读 DEX 静态字段初值，不涉及控制流，因此是权威的）。
 *
 * ===== 为什么必须用这张表，而不是自己造贝塞尔 =====
 *
 * 逆向结论：负一屏的过渡动画**不走传统 Interpolator**，而是走小米自研的
 * miuix.animation (Folme) 引擎，链路是：
 *
 *     AnimState / ValueProperty
 *       → Folme.useValue(...)
 *       → AnimConfig().setEase(EaseManager.getStyle(styleId, params))
 *       → AnimConfig.enableStartImmediately(...)
 *       → FolmeStyle.to(target, config)
 *
 * 其中 `pane/f`（Interpolators.java）整个类只有一个 `LinearInterpolator` 字段 ——
 * 也就是说，传统插值器在这个场景里几乎不参与。
 *
 * 所以「要小米的曲线」的正确做法**不是**去猜一条贝塞尔，而是：
 *   1. 默认**不要覆盖** —— 系统用自己哪条曲线，就用哪条（这就是最忠实的小米曲线）；
 *   2. 要调的话，改 `styleId`，而 styleId 从下面这张小米自己的表里选；
 *   3. 更要紧的是：{@link #getSystemStyle} 直接调用系统自己的
 *      `EaseManager.getStyle(...)` 工厂来拿 `EaseStyle` 对象 ——
 *      这样拿到的是**系统原厂对象**，100% 一致，不存在"重写公式对不齐"的问题。
 *
 * ⚠️ 关于 styleId 的一个重要事实：它**不是编译期常量**，是运行时算出来的
 *    （SwipeDetector 里由方法返回值决定）。静态分析定不了，必须靠探测日志实测。
 *    所以 {@link ProbeHooks} 会 hook `EaseManager.getStyle` 把真实 id 和参数打出来。
 */
public final class XiaomiCurves {

    private XiaomiCurves() {
    }

    // ===================== EaseStyleDef 常量（固件还原，权威）=====================

    public static final int SPRING = 0;
    public static final int LINEAR = 1;
    public static final int QUAD_IN = 2;
    public static final int QUAD_OUT = 3;
    public static final int QUAD_INOUT = 4;
    public static final int CUBIC_IN = 5;
    public static final int CUBIC_OUT = 6;
    public static final int CUBIC_INOUT = 7;
    public static final int QUART_IN = 8;
    public static final int QUART_OUT = 9;
    public static final int QUART_INOUT = 10;
    public static final int QUINT_IN = 11;
    public static final int QUINT_OUT = 12;
    public static final int QUINT_INOUT = 13;
    public static final int SINE_IN = 14;
    public static final int SINE_OUT = 15;
    public static final int SINE_INOUT = 16;
    public static final int EXPO_IN = 17;
    public static final int EXPO_OUT = 18;
    public static final int EXPO_INOUT = 19;
    public static final int DECELERATE = 20;
    public static final int ACCELERATE_DECELERATE = 21;
    public static final int ACCELERATE_INTERPOLATOR = 22;
    public static final int BOUNCE = 23;
    public static final int BOUNCE_EASE_IN = 24;
    public static final int BOUNCE_EASE_OUT = 25;
    public static final int BOUNCE_EASE_INOUT = 26;
    public static final int BEZIER = 100;
    public static final int SPRING_GRAVITY = 101;
    public static final int SPRING_FUNCTION = 102;
    public static final int DAMPING = 103;
    public static final int PERLIN2 = 200;
    public static final int PERLIN = 201;
    public static final int DURATION = -1;
    public static final int SPRING_PHY = -2;
    public static final int ACCELERATE = -3;
    public static final int FRICTION = -4;
    public static final int STOP = -5;
    public static final int REBOUND = -6;

    private static final Map<Integer, String> NAMES = new HashMap<>();

    static {
        NAMES.put(SPRING, "SPRING");
        NAMES.put(LINEAR, "LINEAR");
        NAMES.put(QUAD_IN, "QUAD_IN");
        NAMES.put(QUAD_OUT, "QUAD_OUT");
        NAMES.put(QUAD_INOUT, "QUAD_INOUT");
        NAMES.put(CUBIC_IN, "CUBIC_IN");
        NAMES.put(CUBIC_OUT, "CUBIC_OUT");
        NAMES.put(CUBIC_INOUT, "CUBIC_INOUT");
        NAMES.put(QUART_IN, "QUART_IN");
        NAMES.put(QUART_OUT, "QUART_OUT");
        NAMES.put(QUART_INOUT, "QUART_INOUT");
        NAMES.put(QUINT_IN, "QUINT_IN");
        NAMES.put(QUINT_OUT, "QUINT_OUT");
        NAMES.put(QUINT_INOUT, "QUINT_INOUT");
        NAMES.put(SINE_IN, "SINE_IN");
        NAMES.put(SINE_OUT, "SINE_OUT");
        NAMES.put(SINE_INOUT, "SINE_INOUT");
        NAMES.put(EXPO_IN, "EXPO_IN");
        NAMES.put(EXPO_OUT, "EXPO_OUT");
        NAMES.put(EXPO_INOUT, "EXPO_INOUT");
        NAMES.put(DECELERATE, "DECELERATE");
        NAMES.put(ACCELERATE_DECELERATE, "ACCELERATE_DECELERATE");
        NAMES.put(ACCELERATE_INTERPOLATOR, "ACCELERATE_INTERPOLATOR");
        NAMES.put(BOUNCE, "BOUNCE");
        NAMES.put(BOUNCE_EASE_IN, "BOUNCE_EASE_IN");
        NAMES.put(BOUNCE_EASE_OUT, "BOUNCE_EASE_OUT");
        NAMES.put(BOUNCE_EASE_INOUT, "BOUNCE_EASE_INOUT");
        NAMES.put(BEZIER, "BEZIER");
        NAMES.put(SPRING_GRAVITY, "SPRING_GRAVITY");
        NAMES.put(SPRING_FUNCTION, "SPRING_FUNCTION");
        NAMES.put(DAMPING, "DAMPING");
        NAMES.put(PERLIN2, "PERLIN2");
        NAMES.put(PERLIN, "PERLIN");
        NAMES.put(DURATION, "DURATION");
        NAMES.put(SPRING_PHY, "SPRING_PHY");
        NAMES.put(ACCELERATE, "ACCELERATE");
        NAMES.put(FRICTION, "FRICTION");
        NAMES.put(STOP, "STOP");
        NAMES.put(REBOUND, "REBOUND");
    }

    /** 名字 → id，供按名字配置。 */
    public static Integer idOf(String name) {
        for (Map.Entry<Integer, String> e : NAMES.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) {
                return e.getKey();
            }
        }
        return null;
    }

    public static String nameOf(int id) {
        String n = NAMES.get(id);
        return n == null ? ("UNKNOWN_" + id) : n;
    }

    public static String describe(int id) {
        return id + " (" + nameOf(id) + ")";
    }

    // ================================================================ 实测解码

    /**
     * **从设备实测解码出来的小米弹簧参数** —— 这是"小米的曲线"的最终答案。
     *
     * 探针在真机上抓到的原始证据（`EaseManager.getStyle` / `AnimConfig.setEase`）：
     *
     * <pre>
     * EaseManager.getStyle(-2 (SPRING_PHY), params=[0.9500, 1.2764])
     * EaseManager.getStyle(-2 (SPRING_PHY), params=[1.0000, 0.3000])
     *
     * AnimConfig.setEase(style=StepPhyEase{style=-2,
     *     factors=[0.95, 1.2763862609863281],
     *     parameters=[24.232347880030595, 9.353008802063778, 1.537092862243516]})
     * AnimConfig.setEase(style=StepPhyEase{style=-2,
     *     factors=[1.0, 0.30000001192092896],
     *     parameters=[438.64904963216526, 41.88790038338829, 0.0]})
     * </pre>
     *
     * <b>解码结论：小米用「[阻尼比 ζ, 周期 T(秒)]」来参数化弹簧</b>，内部再换算成
     * 刚度与阻尼系数：
     *
     * <pre>
     *   stiffness          = (2π / T)²
     *   dampingCoefficient = 2ζ√k
     * </pre>
     *
     * 两组实测数据都能对上（这就是解码成立的证据）：
     *
     * <pre>
     *   T=1.2763862609863281 → (2π/T)²        = 24.2326   vs parameters[0]=24.232347880030595  ✓
     *   ζ=0.95               → 2×0.95×√24.2323 = 9.3530   vs parameters[1]=9.353008802063778   ✓
     *   T=0.3                → (2π/0.3)²      = 438.649  vs parameters[0]=438.64904963216526  ✓
     *   ζ=1.0                → 2×1.0×√438.649 = 41.8879  vs parameters[1]=41.88790038338829   ✓
     * </pre>
     *
     * 另外注意：**小米用的是 `SPRING_PHY`（id −2），不是 `SPRING`（id 0）**，
     * 而且 curve family 是物理弹簧（`StepPhyEase`），再次印证"不是贝塞尔"。
     */
    public static final class Spring {

        private Spring() {
        }

        /** 实测样本 A：慢而软 —— 周期约 1.276s，阻尼比 0.95（有极轻微过冲）。 */
        public static final float A_ZETA = 0.95f;
        public static final float A_PERIOD_S = 1.2763862609863281f;

        /** 实测样本 B：快而脆 —— 周期 0.3s，临界阻尼（不过冲）。 */
        public static final float B_ZETA = 1.0f;
        public static final float B_PERIOD_S = 0.3f;

        /** 小米的参数化 → 刚度：k = (2π/T)² */
        public static float stiffnessOf(float periodSec) {
            if (periodSec <= 0f) {
                return 1f;
            }
            float w = (float) (2.0 * Math.PI / periodSec);
            return w * w;
        }

        /** 小米的参数化 → 阻尼系数：c = 2ζ√k */
        public static float dampingCoefficientOf(float zeta, float periodSec) {
            return 2f * zeta * (float) Math.sqrt(stiffnessOf(periodSec));
        }
    }

    /**
     * 拿系统原厂的 EaseStyle 对象。
     *
     * 这是"要小米的曲线"最忠诚的做法：直接调系统自己的工厂，
     * 拿回来的就是小米原厂对象，不存在公式重写对不齐的问题。
     *
     * @param cl     宿主 ClassLoader
     * @param styleId 取自本类的常量
     * @param params  弹簧参数（只有 SPRING 类需要），可为 null
     */
    public static Object getSystemStyle(ClassLoader cl, int styleId, float[] params) {
        try {
            Class<?> c = HookKit.findClass(cl, Targets.MIUIX_EASE_MANAGER);
            if (c == null) {
                return null;
            }
            return de.robv.android.xposed.XposedHelpers.callStaticMethod(
                    c, "getStyle", styleId, params == null ? new float[0] : params);
        } catch (Throwable t) {
            L.w("调用系统 EaseManager.getStyle 失败: " + t);
            return null;
        }
    }
}
