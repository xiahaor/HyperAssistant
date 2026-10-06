package com.qianxia.hyperassistant;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import de.robv.android.xposed.XposedHelpers;

/**
 * 逐帧采样系统**活进度**，用它驱动全部并行属性。
 *
 * ===== 为什么需要一个采样器（而不是只在 hook D/F 时施加）=====
 *
 * 因为 D(float)/F(float) 到底是"逐帧调用"还是"只在起止各调一次"还没实测确认（探测的 Q1）。
 * 如果只在 hook 里施加，一旦系统不是逐帧调用，我们的并行属性就会**只被设一次然后僵住**，
 * 松手后的收敛、以及中途打断后的续接全都做不到。
 *
 * 采样器每帧直接向系统要**当前**进度（`SlidingPaneWindow.w() -> float` 这个 getter，
 * 前面的逆向已确认它是"当前进度"），于是：
 *   · 无论系统内部是 Folme 弹簧、AnimatorSet 还是别的，我们读到的都是真实位置；
 *   · 用户中途打断时，系统自己完成速度衔接，我们下一帧读到的就是衔接后的值
 *     —— **我们天然可打断，因为我们从不持有时间轴**；
 *   · 所有属性来自同一个 p，**并行同步是结构保证的**。
 *
 * ===== 开销控制 =====
 * 只在"负一屏处于活动状态"时采样（scrollStart / onDragStart 唤醒），
 * 进度停住一段时间后自动停止，避免持续占用渲染帧。
 */
public final class ProgressSampler implements Choreographer.FrameCallback {

    private static final ProgressSampler INSTANCE = new ProgressSampler();

    /** 进度多久没变化就自动停止采样。 */
    private static final long IDLE_STOP_MS = 500L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 目标窗口对象（SlidingPaneWindow 或 AssistantOverlayWindow）。 */
    private volatile Object target;
    private volatile boolean running;
    private volatile boolean callbackPosted;

    private float lastRaw = Float.NaN;
    private float lastP = Float.NaN;
    private long lastChangeMs;
    private long startedMs;
    private static volatile boolean sRangeWarned;

    // 测速（AOSP 的速度驱动逻辑需要）
    private float lastFrameP = Float.NaN;
    private long lastFrameNanos;
    private volatile float lastVelocityPerMs;
    private volatile float lastVelocityPxPerMs;

    /** 一帧时长的初值（60Hz）；实际会用实测帧间隔自适应修正。 */
    private static final float ONE_FRAME_MS = 1000f / 60f;

    /** 实测帧长的指数滑动平均，替代 AOSP 的 getSingleFrameMs(context)。 */
    private float frameMsEma = ONE_FRAME_MS;

    // 统计 + 打断检测
    private int frames;
    private int interrupts;

    /** 可选平滑：带速度衔接的弹簧（打断时保留速度）。 */
    private final SpringFloat spring = new SpringFloat(1f);
    private boolean springPrimed;

    private ProgressSampler() {
    }

    public static ProgressSampler get() {
        return INSTANCE;
    }

    public void attach(Object paneWindow) {
        if (paneWindow != null) {
            this.target = paneWindow;
        }
    }

    /**
     * 一段交互开始（滑动开始 / 按下）。
     * 若此时上一段动画还没停，就记为一次**打断**。
     */
    public void onInteractionStart(String why) {
        if (running && lastP == lastP && lastP > 0.004f && lastP < 0.996f) {
            interrupts++;
            L.i("[打断] " + why + " 在上一段动画未结束时接管: 当前 p=" + L.f(lastP)
                    + "  (累计打断 " + interrupts + " 次)");
        }
        startedMs = SystemClock.uptimeMillis();
        lastChangeMs = startedMs;
        // 新一段交互：清掉测速历史，否则第一帧会算出跨段的假速度
        lastFrameP = Float.NaN;
        lastFrameNanos = 0L;
        lastVelocityPerMs = 0f;
        lastVelocityPxPerMs = 0f;
        start();
    }

    public float lastVelocityPerMs() {
        return lastVelocityPerMs;
    }

    /** 松手速度（px/ms）—— 对应 AOSP 里决定用 QUINT_OUT 还是 CUBIC_OUT 的那个量。 */
    public float lastVelocityPxPerMs() {
        return lastVelocityPxPerMs;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        frames = 0;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (!callbackPosted) {
                    callbackPosted = true;
                    Choreographer.getInstance().postFrameCallback(ProgressSampler.this);
                }
            }
        });
    }

    public void stop() {
        running = false;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        callbackPosted = false;
        if (!running) {
            L.i("采样停止: 共 " + frames + " 帧, 打断 " + interrupts + " 次");
            return;
        }

        float raw = readProgress();
        if (!Float.isNaN(raw)) {
            // 安全网：值域还没校准就直接开动画的话，归一化会被 clamp 死，
            // 表现是"属性一动不动"。这里明确喊出来，省得你以为是模块没生效。
            if (raw > 1.5f && Config.PROGRESS_MAX_HINT <= 1f && !sRangeWarned) {
                sRangeWarned = true;
                L.w("!! 进度原始值 " + L.f(raw) + " 超出默认值域 [0..1] —— 值域尚未校准。");
                L.w("   请先开 ENABLE_PROBE 跑一轮，把 Config.PROGRESS_MAX_HINT 改成实测最大值，");
                L.w("   否则归一化会被钳制在 1，并行动画看起来会像没生效。");
            }
            if (Float.isNaN(lastRaw) || Math.abs(raw - lastRaw) > 1e-6f) {
                lastChangeMs = SystemClock.uptimeMillis();
                lastRaw = raw;
            }
            float p = Easing.normalize(raw);

            // ---- 测速：AOSP 的"按松手速度选曲线 / 传速度给弹簧"都依赖它 ----
            float velPerMs = 0f;
            if (!Float.isNaN(lastFrameP) && lastFrameNanos > 0L) {
                float dtMs = (frameTimeNanos - lastFrameNanos) / 1_000_000f;
                if (dtMs > 0.1f && dtMs < 100f) {
                    velPerMs = (p - lastFrameP) / dtMs;
                    // 自适应帧长：AOSP 用 getSingleFrameMs(context) 取实际刷新率，
                    // 我们直接从实测帧间隔估计，120Hz 屏上会自动减半。
                    frameMsEma = frameMsEma * 0.9f + dtMs * 0.1f;
                }
            }
            lastFrameP = p;
            lastFrameNanos = frameTimeNanos;
            lastVelocityPerMs = velPerMs;
            // 换算成 px/ms —— AOSP 的阈值（10）就是 px/ms 单位
            View contentView = ParallelAnim.getView();
            float width = contentView == null ? 0f : contentView.getWidth();
            lastVelocityPxPerMs = width > 0f ? velPerMs * width : velPerMs;

            if (Config.SMOOTH_WITH_SPRING) {
                if (!springPrimed) {
                    spring.snapTo(p);
                    spring.setVelocity(velPerMs);      // 用真实速度起手
                    springPrimed = true;
                } else {
                    // AOSP 式衔接：先把起点按当前速度前推一帧，再换目标，速度保留
                    spring.retargetWithVelocity(p, velPerMs, frameMsEma);
                    spring.update(frameTimeNanos);
                }
                // 快到位就吸附，避免尾巴拖沓（AOSP 的 forceFinishIfCloseToEnd 思路）
                spring.forceFinishIfClose(1f - Config.FORCE_FINISH_THRESHOLD);
                p = spring.getValue();
            }

            lastP = p;
            ParallelAnim.apply(p);
            frames++;
        }

        // 停住一段时间就收工
        if (SystemClock.uptimeMillis() - lastChangeMs > IDLE_STOP_MS) {
            running = false;
            boolean stillMoving = Config.SMOOTH_WITH_SPRING && spring.isInFlight();
            if (stillMoving) {
                running = true;     // 弹簧还没停，继续推到停为止
            } else {
                L.i("采样自动停止: 帧数 " + frames
                        + ", 耗时 " + (SystemClock.uptimeMillis() - startedMs) + "ms"
                        + ", 打断 " + interrupts + " 次"
                        + ", 松手速度 " + L.f(lastVelocityPxPerMs) + " px/ms"
                        + " → 曲线 " + XiaomiCurves.nameOf(
                                Easing.commitStyleForVelocity(lastVelocityPxPerMs)));
                L.i("  " + ParallelAnim.baselineInfo());
                return;
            }
        }

        Choreographer.getInstance().postFrameCallback(this);
        callbackPosted = true;
    }

    /** 向系统要当前进度。失败返回 NaN。 */
    private float readProgress() {
        Object t = target;
        if (t == null) {
            return Float.NaN;
        }
        try {
            Object r = XposedHelpers.callMethod(t, Targets.M.GET_PROGRESS);
            if (r instanceof Float) {
                return (Float) r;
            }
            if (r instanceof Number) {
                return ((Number) r).floatValue();
            }
        } catch (Throwable ignored) {
            // 方法不存在或调用失败：交给上层判断
        }
        return Float.NaN;
    }

    public boolean isRunning() {
        return running;
    }

    public float lastProgress() {
        return lastP;
    }
}
