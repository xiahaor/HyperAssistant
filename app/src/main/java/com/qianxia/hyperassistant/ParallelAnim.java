package com.qianxia.hyperassistant;

import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

/**
 * 并行动画执行器 —— **纯函数，不持有任何时间轴**。
 *
 * ===== 为什么这样设计（"可并行打断"的核心）=====
 *
 * 只要我们自己持有 Animator/时间轴，用户中途再摸一下就会出现：
 *   · 从错误的位置续接（跳变）
 *   · 速度不衔接（一顿）
 *   · 多个属性各自为战（不同步，"并行"变成"打架"）
 *
 * 所以这里的做法是：**所有属性都是同一个进度 p 的纯函数**。
 *   scale = f(p)   alpha = g(p)   blur = h(p)
 * p 由系统实时给出（或由 {@link ProgressSampler} 每帧读活值），
 * 于是"打断"根本不需要我们处理 —— p 一变，所有属性**同一帧、同一个 p** 一起变。
 * 并行是结构上保证的，不是靠对齐时间轴凑出来的。
 *
 * 唯一需要时间轴的地方（可选平滑）交给 {@link SpringFloat}，它带速度衔接。
 *
 * ===== 与系统动画的关系：乘法叠加，不抢所有权 =====
 *
 * 系统自己可能也在动这个 View 的 scale/alpha。如果我们直接 setScaleX()，
 * 就把系统的动画覆盖掉了 —— 那才是真正的"打架"。
 * 所以这里采用**基线 + 乘法**：
 *   · 在进度静止（≈0 或 ≈1）时，把 View 当前的值捕获为基线；
 *   · 运动过程中施加 实际值 = 基线 × 我们的系数。
 * 这样系统的动画仍然生效，我们只是在其之上叠加，互不破坏，也不会跳变。
 */
public final class ParallelAnim {

    private ParallelAnim() {
    }

    private static volatile View sView;

    // ---- 基线（静止时捕获）----
    private static float sBaseScaleX = 1f;
    private static float sBaseScaleY = 1f;
    private static float sBaseAlpha = 1f;
    private static boolean sBaseCaptured;
    private static long sBaseAtMs;

    private static float sLastBlur = -1f;

    /** 静止判定阈值：进度落在这个范围就当"停住了"，此时可以安全地重新捕获基线。 */
    private static final float REST_EPS = 0.004f;

    public static void setView(View v) {
        if (v == null) {
            return;
        }
        sView = v;
        sBaseCaptured = false;      // 换了 View，基线作废
        sLastBlur = -1f;
        L.i("并行动画目标视图: " + v.getClass().getName());
    }

    public static View getView() {
        return sView;
    }

    /** 是否已具备施加条件。 */
    public static boolean ready() {
        return sView != null;
    }

    /**
     * 用进度 p∈[0,1] 驱动全部并行属性。
     * 约定 p=1 表示负一屏完全展开（真机实测确认：p=0 时系统会调 setVisible(false)）。
     *
     * ⚠️ 修复的一个真实 bug：以前 p≈0（负一屏已关闭）时也会施加 0.94 缩放，
     * 而目标视图如果是个**常驻可见的背景层**，就会永远卡在缩小状态。
     * 现在两端（完全关闭 / 完全展开）都**还原基线**，效果只出现在过渡过程中。
     */
    public static void apply(float p) {
        final View v = sView;
        if (v == null) {
            return;
        }
        float t = Easing.clamp01(p);

        // 完全关闭：把视图还原成基线，绝不留残影
        if (t <= REST_EPS) {
            restoreBaseline(v);
            return;
        }
        // 完全展开：同样是基线（此时不该有任何视觉改动）
        if (t >= (1f - REST_EPS)) {
            captureBaseline(v);
            restoreBaseline(v);
            return;
        }

        if (!sBaseCaptured) {
            captureBaseline(v);
        }

        // scale
        if (Config.SCALE_MIN != 1f) {
            float f = Config.SCALE_MIN + (1f - Config.SCALE_MIN) * t;
            v.setScaleX(sBaseScaleX * f);
            v.setScaleY(sBaseScaleY * f);
        }

        // alpha
        if (Config.ALPHA_MIN != 1f) {
            float f = Config.ALPHA_MIN + (1f - Config.ALPHA_MIN) * t;
            v.setAlpha(sBaseAlpha * f);
        }

        // blur
        if (Config.BLUR_MAX_PX > 0f) {
            setBlur(v, Config.BLUR_MAX_PX * (1f - t));
        }
    }

    /** 还原到基线（scale/alpha/模糊全部复位）。 */
    public static void restoreBaseline(View v) {
        if (v == null) {
            return;
        }
        if (Config.SCALE_MIN != 1f) {
            v.setScaleX(sBaseScaleX);
            v.setScaleY(sBaseScaleY);
        }
        if (Config.ALPHA_MIN != 1f) {
            v.setAlpha(sBaseAlpha);
        }
        setBlur(v, 0f);
    }

    /** 模块被禁用或视图要换掉时，把当前视图恢复原样。 */
    public static void release() {
        restoreBaseline(sView);
        sView = null;
        sBaseCaptured = false;
    }

    /**
     * 在静止状态下把 View 当前值记为基线。
     *
     * 限制重捕获频率，避免系统动画刚好路过静止点附近时被我们误判成基线。
     */
    private static void captureBaseline(View v) {
        long now = android.os.SystemClock.uptimeMillis();
        if (sBaseCaptured && now - sBaseAtMs < 250L) {
            return;
        }
        sBaseScaleX = v.getScaleX();
        sBaseScaleY = v.getScaleY();
        sBaseAlpha = v.getAlpha();
        sBaseCaptured = true;
        sBaseAtMs = now;
    }

    private static void setBlur(View v, float radius) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        if (Math.abs(radius - sLastBlur) < 0.5f) {
            return;
        }
        sLastBlur = radius;
        try {
            v.setRenderEffect(radius <= 0.5f
                    ? null
                    : RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        } catch (Throwable t) {
            L.w("setRenderEffect 失败: " + t);
        }
    }

    /** 调试：当前基线。 */
    public static String baselineInfo() {
        return "base[scaleX=" + L.f(sBaseScaleX) + " scaleY=" + L.f(sBaseScaleY)
                + " alpha=" + L.f(sBaseAlpha) + " captured=" + sBaseCaptured + "]";
    }
}
