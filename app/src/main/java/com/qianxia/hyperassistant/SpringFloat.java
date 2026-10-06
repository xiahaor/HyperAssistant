package com.qianxia.hyperassistant;

/**
 * 可打断的弹簧标量 —— 带**速度衔接**的临界阻尼弹簧。
 *
 * 这是"可并行打断"里唯一真正需要小心处理的部分：
 * 如果我们自己驱动一条时间轴，用户中途再摸一下，就必须做到
 *   1) 从**当前位置**续接（不是从动画起点或终点跳）
 *   2) 继承**当前速度**（否则会有一顿）
 *
 * {@link #retarget} 就是干这个的：它保留当前 value 与 velocity，只换目标。
 * 这正是 miuix Folme 的 SpringForce 在系统侧做的事，我们这里自己实现一份，
 * 这样即使系统的信号不是逐帧的，我们的并行属性也不会僵住。
 *
 * 公式：临界阻尼弹簧的标准半隐式欧拉积分
 *   a = -k*(x - target) - c*v
 *   v += a*dt ; x += v*dt
 */
public final class SpringFloat {

    private float value;
    private float velocity;
    private float target;
    private long lastNanos;

    /** 刚度：越大跟得越紧。默认取实测的小米弹簧参数。 */
    private float stiffness = Config.stiffness();

    /** 阻尼比：1.0 = 临界阻尼（不过冲）；小于 1 会过冲（Q弹）。 */
    private float dampingRatio = Config.damping();

    public SpringFloat(float initial) {
        this.value = initial;
        this.target = initial;
    }

    public float getValue() {
        return value;
    }

    public float getVelocity() {
        return velocity;
    }

    public float getTarget() {
        return target;
    }

    /** 直接落值并清速度（用于初始化 / 在静止状态下重新捕获基线）。 */
    public void snapTo(float v) {
        this.value = v;
        this.target = v;
        this.velocity = 0f;
        this.lastNanos = 0L;
    }

    /** 用测得的真实速度起手 —— 这是速度衔接的关键一步。 */
    public void setVelocity(float v) {
        this.velocity = v;
    }

    /**
     * AOSP 式的速度衔接（见 Launcher3 `AnimatorPlaybackController.startWithVelocity`）：
     * 先按"当前速度 × 一帧时长"把起点往前推一帧，再交给弹簧。
     *
     * 原注释：{@code nextFrameProgress = current + velocityProgressPerMs * oneFrameMs}
     * 这样即使上一帧到今天之间有间隙，位置也是连续的，不会有一顿。
     *
     * @param newTarget            新目标
     * @param velocityPerMs        当前速度（单位：每秒的值 / 1000）
     * @param oneFrameMs           一帧时长（约 16.7ms）
     */
    public void retargetWithVelocity(float newTarget, float velocityPerMs, float oneFrameMs) {
        // 先把位置前推一帧，再设定目标；速度保留，实现真正的续接
        float advanced = value + velocityPerMs * oneFrameMs;
        this.value = advanced;
        this.target = newTarget;
        // 速度方向与新目标相反时衰减，避免被惯性拽着走
        float delta = newTarget - advanced;
        if (velocity != 0f && delta != 0f && (velocity > 0f) != (delta > 0f)) {
            velocity *= 0.35f;
        }
    }

    /**
     * 距离目标足够近就直接吸附，避免末尾那一小段拖沓。
     *
     * AOSP 的做法是 {@code ANIMATION_COMPLETE_THRESHOLD = 0.95f}：
     * 已经走了 95% 就 {@code end()} 掉。这里等价的判据是"剩余距离 ≤ 容差"。
     *
     * @param tolerance 容差（进度单位）。用 {@code 1 - FORCE_FINISH_THRESHOLD} 即可。
     */
    public boolean forceFinishIfClose(float tolerance) {
        if (Math.abs(target - value) <= tolerance) {
            snapTo(target);
            return true;
        }
        return false;
    }

    /**
     * 换目标，**保留当前 value 与 velocity** —— 这就是打断续接。
     */
    public void retarget(float newTarget) {
        this.target = newTarget;
    }

    /**
     * 换目标；若速度方向与新目标相反（用户反向拖拽），把速度衰减掉一部分，
     * 避免"反向时被惯性拽着走"的粘滞感。
     */
    public void retargetSoft(float newTarget) {
        float delta = newTarget - value;
        if (velocity != 0f && delta != 0f && (velocity > 0f) != (delta > 0f)) {
            velocity *= 0.35f;
        }
        this.target = newTarget;
    }

    /** 推进一帧。返回是否仍在运动。 */
    public boolean update(long nowNanos) {
        if (lastNanos == 0L) {
            lastNanos = nowNanos;
            return true;
        }
        float dt = (nowNanos - lastNanos) / 1_000_000_000f;
        lastNanos = nowNanos;
        if (dt <= 0f) {
            return true;
        }
        if (dt > 0.064f) {
            dt = 0.064f;        // 掉帧保护，避免大步长发散
        }

        float k = stiffness;
        float c = 2f * (float) Math.sqrt(stiffness) * dampingRatio;   // 临界阻尼 c = 2*sqrt(k)

        float a = -k * (value - target) - c * velocity;
        velocity += a * dt;
        value += velocity * dt;

        // 静止判定
        if (Math.abs(velocity) < 0.01f && Math.abs(value - target) < 0.0005f) {
            value = target;
            velocity = 0f;
            return false;
        }
        return true;
    }

    /** 调试用：当前是否处于"被打断"的不稳定状态。 */
    public boolean isInFlight() {
        return Math.abs(value - target) > 0.001f || Math.abs(velocity) > 0.01f;
    }
}
