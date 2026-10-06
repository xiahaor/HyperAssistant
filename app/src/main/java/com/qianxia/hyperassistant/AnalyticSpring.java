package com.qianxia.hyperassistant;

import android.view.animation.Interpolator;

/**
 * 解析解弹簧 —— 闭式求值 + 可算时长 + 可直接当 Interpolator 用。
 *
 * ===== 为什么不用数值积分 =====
 * {@link SpringFloat} 是半隐式欧拉积分，优点是可以逐帧改目标；
 * 但它有积分误差、而且结果**依赖帧率**（120Hz 和 60Hz 会跑出不同轨迹）。
 *
 * 这个类是**解析解**，数学上与 AOSP Launcher3 的
 * {@code com.android.launcher3.anim.SpringAnimationBuilder} 一致：
 *
 * <pre>
 *   beta  = 2 * zeta * omega_n          omega_n = sqrt(stiffness)
 *   gamma = omega_n * sqrt(1 - zeta^2)  阻尼振荡频率
 *   a = x0 - x_end
 *   b = beta * a / (2 * gamma) + v0 / gamma
 *
 *   x(t) = e^(-beta*t/2) * (a*cos(gamma*t) + b*sin(gamma*t)) + x_end
 *   v(t) = e^(-beta*t/2) * (va*cos(gamma*t) + vb*sin(gamma*t))
 *          va = a*beta/2 - b*gamma,  vb = a*gamma + beta*b/2
 * </pre>
 *
 * 它能做三件 SpringFloat 做不到的事：
 *   1. **任意时刻闭式求值** —— 与帧率无关，掉帧也不会跑偏；
 *   2. **算得出时长** —— 所以可以喂给 AnimatorSet，或者用来判断"何时该停"；
 *   3. **能当 Interpolator** —— 于是"小米的 SPRING(id 0) + 测出的刚度和阻尼比"
 *      可以直接变成一条可用的曲线，不需要去猜贝塞尔。
 *
 * 临界阻尼（zeta = 1）是退化情形，gamma → 0 会让上式除零，所以单独走另一组公式。
 */
public final class AnalyticSpring {

    // ---- 输入 ----
    private float startValue;
    private float endValue;
    private float startVelocity;
    private float stiffness = Config.stiffness();
    private float dampingRatio = Config.damping();
    private float minVisibleChange = Config.SPRING_MIN_VISIBLE_CHANGE;
    private float singleFrameMs = 1000f / 60f;

    // ---- 中间量 ----
    private double beta;
    private double gamma;
    private double a;
    private double b;
    private double va;
    private double vb;
    private boolean critical;
    private double omega;

    private double valueThreshold;
    private double velocityThreshold;
    private double durationSec;

    /** AOSP 同款阈值系数：value threshold = minVisibleChange * 0.65 */
    private static final double THRESHOLD_MULTIPLIER = 0.65;

    /** 时长上限，防止参数异常时死循环。 */
    private static final double MAX_DURATION_SEC = 5.0;

    public AnalyticSpring setStartValue(float v) {
        this.startValue = v;
        return this;
    }

    public AnalyticSpring setEndValue(float v) {
        this.endValue = v;
        return this;
    }

    public AnalyticSpring setStartVelocity(float v) {
        this.startVelocity = v;
        return this;
    }

    public AnalyticSpring setStiffness(float s) {
        this.stiffness = s <= 0f ? 1f : s;
        return this;
    }

    /**
     * 按小米的参数化设置弹簧：给**周期 T（秒）**，内部换算 k = (2π/T)²。
     *
     * 这是"要小米的曲线"最直接的入口 —— 实测表明小米就是用 [阻尼比, 周期] 参数化的。
     */
    public AnalyticSpring setResponsePeriod(float periodSec) {
        return setStiffness(XiaomiCurves.Spring.stiffnessOf(periodSec));
    }

    public AnalyticSpring setDampingRatio(float r) {
        // AOSP 的闭式解只对 0 < zeta < 1 成立；=1 走临界阻尼分支
        this.dampingRatio = r < 0.01f ? 0.01f : (r > 2f ? 2f : r);
        return this;
    }

    public AnalyticSpring setMinimumVisibleChange(float m) {
        this.minVisibleChange = m <= 0f ? 1e-4f : m;
        return this;
    }

    public AnalyticSpring setSingleFrameMs(float ms) {
        this.singleFrameMs = ms <= 0f ? (1000f / 60f) : ms;
        return this;
    }

    /** 计算全部中间量与时长。必须在取值前调用。 */
    public AnalyticSpring computeParams() {
        omega = Math.sqrt(stiffness);
        beta = 2.0 * dampingRatio * omega;

        a = startValue - endValue;              // 把稳定点平移到 0
        valueThreshold = minVisibleChange * THRESHOLD_MULTIPLIER;
        // 若走完阈值所需时间 >= 1 帧，则这个速度算"合理阈值"
        velocityThreshold = valueThreshold * 1000.0 / singleFrameMs;

        if (Math.abs(dampingRatio - 1.0) < 1e-3) {
            // ---- 临界阻尼：x(t) = (a + (v0 + omega*a) t) e^(-omega t) ----
            critical = true;
            gamma = 0;
            b = startVelocity + omega * a;
            va = startVelocity;
            vb = -omega * (startVelocity + omega * a);
            durationSec = scanDurationCritical();
        } else {
            critical = false;
            gamma = omega * Math.sqrt(1.0 - dampingRatio * dampingRatio);
            b = beta * a / (2.0 * gamma) + startVelocity / gamma;

            // ⚠️ 符号说明（容易踩的坑）：
            // AOSP 里写的是 va = a*beta/2 - b*gamma、vb = a*gamma + beta*b/2，
            // 但那整体等于 **-x'(t)**（即真正的速度取反）。
            // AOSP 只把 cosSinV 用在 Math.abs(...) 里比阈值，所以符号无所谓；
            // 我们这里把它当真实速度暴露出去，就必须用正确的导数：
            //   x'(t) = e^(-βt/2)·[(bγ - βa/2)·cos(γt) + (-βb/2 - aγ)·sin(γt)]
            va = b * gamma - a * beta / 2.0;
            vb = -a * gamma - beta * b / 2.0;
            durationSec = computeDurationUnderdamped();
        }
        return this;
    }

    // ---------------------------------------------------------------- 求值

    /** t 单位：秒。返回绝对位置（含 endValue）。 */
    public float getValueAtTime(double t) {
        if (critical) {
            double e = Math.exp(-omega * t);
            return (float) ((a + b * t) * e) + endValue;
        }
        double e = Math.exp(-beta * t / 2.0);
        double ang = t * gamma;
        return (float) (e * (a * Math.cos(ang) + b * Math.sin(ang))) + endValue;
    }

    /** t 单位：秒。返回速度。 */
    public double getVelocityAtTime(double t) {
        if (critical) {
            double e = Math.exp(-omega * t);
            return (va + vb * t) * e;
        }
        double e = Math.exp(-beta * t / 2.0);
        double ang = t * gamma;
        return e * (va * Math.cos(ang) + vb * Math.sin(ang));
    }

    /** fraction ∈ [0,1] → 位置。 */
    public float getValueAtFraction(float fraction) {
        double t = durationSec * clamp01(fraction);
        return getValueAtTime(t);
    }

    /** 时长（毫秒）。 */
    public long getDurationMs() {
        return (long) (1000.0 * durationSec);
    }

    /**
     * 当成 Interpolator 用：把 0..1 映射到 0..1（相对于 start→end 的行程）。
     *
     * 这样"小米的 SPRING + 刚度/阻尼"就能直接喂给 AnimatorSet 或任何需要曲线的地方，
     * 不需要去猜一条贝塞尔近似它。
     */
    public Interpolator asInterpolator() {
        final float span = endValue - startValue;
        if (Math.abs(span) < 1e-6f) {
            return new Interpolator() {
                @Override
                public float getInterpolation(float input) {
                    return input;
                }
            };
        }
        return new Interpolator() {
            @Override
            public float getInterpolation(float input) {
                float v = getValueAtFraction(input);
                return (v - startValue) / span;
            }
        };
    }

    /** 调试用摘要。 */
    public String describe() {
        return "spring{stiffness=" + L.f(stiffness)
                + " zeta=" + L.f(dampingRatio)
                + (critical ? " [临界阻尼]" : " gamma=" + L.f((float) gamma))
                + " 时长=" + getDurationMs() + "ms"
                + " 起点=" + L.f(startValue) + " 终点=" + L.f(endValue)
                + " 初速=" + L.f(startVelocity) + "}";
    }

    // ---------------------------------------------------------------- 时长求解

    /** AOSP 的算法：从 x=0 的位置出发，按半周期步进直到速度低于阈值，再二分收窄。 */
    private double computeDurationUnderdamped() {
        // 稳定点平移到 0 后，x(t)=0 的解析解
        double duration = Math.atan2(-a, b) / gamma;
        double piByG = Math.PI / gamma;
        int guard = 0;
        while ((duration < 0 || !velocityBelowThreshold(duration)) && guard++ < 10000) {
            duration += piByG;
            if (duration > MAX_DURATION_SEC) {
                return MAX_DURATION_SEC;
            }
        }

        // 二分找最短满足平衡的时间
        double edgeTime = Math.max(0, duration - piByG / 2);
        double minDiff = singleFrameMs / 2000.0;      // 半帧（秒）
        int guard2 = 0;
        while (guard2++ < 200) {
            if ((duration - edgeTime) < minDiff) {
                break;
            }
            double mid = (edgeTime + duration) / 2.0;
            if (isAtEquilibrium(mid)) {
                duration = mid;
            } else {
                edgeTime = mid;
            }
        }
        return duration;
    }

    private boolean velocityBelowThreshold(double t) {
        double e = Math.exp(-beta * t / 2.0);
        double ang = t * gamma;
        return Math.abs(e * (va * Math.cos(ang) + vb * Math.sin(ang))) < velocityThreshold;
    }

    /** 临界阻尼分支：单调包络，直接按半帧步进扫到平衡即可。 */
    private double scanDurationCritical() {
        double step = singleFrameMs / 2000.0;   // 半帧（秒）
        double t = 0.0;
        while (t < MAX_DURATION_SEC) {
            t += step;
            if (isAtEquilibrium(t)) {
                return t;
            }
        }
        return MAX_DURATION_SEC;
    }

    private boolean isAtEquilibrium(double t) {
        double e = critical ? Math.exp(-omega * t) : Math.exp(-beta * t / 2.0);
        double ang = t * gamma;

        double x = critical
                ? (a + b * t) * e
                : e * (a * Math.cos(ang) + b * Math.sin(ang));
        if (Math.abs(x) >= valueThreshold) {
            return false;
        }
        double v = critical
                ? (va + vb * t) * e
                : e * (va * Math.cos(ang) + vb * Math.sin(ang));
        return Math.abs(v) < velocityThreshold;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
