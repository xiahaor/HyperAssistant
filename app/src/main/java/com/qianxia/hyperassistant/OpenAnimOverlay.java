package com.qianxia.hyperassistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import de.robv.android.xposed.XposedHelpers;

/**
 * 自己的"卡片展开"启动动画 —— **完全不依赖任何特权**。
 *
 * ===== 为什么必须自己做 =====
 * 真机实测结论：
 *   · 桌面那套全动画是**远程动画**，控制通道是 `android:activity.animAbortListener`；
 *   · 该 key 需要签名级权限 `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS`；
 *   · 逐级剥离验证：**只有这个 key 被拒**，去掉它启动就放行 ——
 *     但去掉它之后系统没有动画控制器了，于是退化成普通启动（"没生效"）。
 *   · 负一屏**没有**这个权限（桌面有），所以永远调不动同一套机制。
 *
 * ===== 我们怎么做 =====
 * 负一屏持有 `SYSTEM_ALERT_WINDOW` + `START_ANY_ACTIVITY`，所以可以：
 *   1. 点卡片时截下这张卡片的画面
 *   2. 用一个全屏悬浮窗把截图**原地放在卡片位置**
 *   3. 自己驱动它放大铺满屏幕（用还原出来的小米弹簧参数）
 *   4. **同时让原始 startActivity 照常执行**（我们完全不碰它 → 零风险）
 *   5. 应用窗口出现后淡出浮层
 *
 * 关键取舍：**我们不替换启动调用**，只是在旁边加一层视觉。
 * 所以即使动画有问题，应用也永远能正常打开。
 */
public final class OpenAnimOverlay {

    private OpenAnimOverlay() {
    }

    private static volatile View sCurrent;
    private static volatile WindowManager sWm;

    /** 当前是否有浮层在播。 */
    public static boolean isPlaying() {
        return sCurrent != null;
    }

    /** 立刻撤掉浮层（被打断 / 新一次点击 / 出错时都要撤）。 */
    public static void cancel() {
        final View v = sCurrent;
        sCurrent = null;
        if (v == null) {
            return;
        }
        try {
            if (sWm != null) {
                sWm.removeViewImmediate(v);
            }
        } catch (Throwable t) {
            L.w("[浮层] 移除失败: " + t);
        }
    }

    /**
     * 播一次"卡片展开"。
     *
     * @param src 被点击的卡片视图（动画起点）
     */
    public static void play(final Context ctx, final View src) {
        if (src == null || ctx == null) {
            return;
        }
        if (!src.isAttachedToWindow()) {
            L.i("[浮层] 源视图已不在窗口上，跳过");
            return;
        }

        final int[] loc = new int[2];
        final int w = src.getWidth();
        final int h = src.getHeight();
        try {
            src.getLocationOnScreen(loc);
        } catch (Throwable t) {
            L.w("[浮层] 取位置失败: " + t);
            return;
        }
        if (w <= 0 || h <= 0) {
            L.i("[浮层] 源视图尺寸为 0（" + w + "x" + h + "），跳过");
            return;
        }

        final Bitmap shot = capture(src, w, h);
        if (shot == null) {
            L.w("[浮层] 截图失败，跳过动画");
            return;
        }

        // 新的一次点击：先把上一次的撤掉（这就是"打断"）
        if (isPlaying()) {
            L.i("[浮层] 上一次还在播，先撤掉（打断）");
            cancel();
        }

        final WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            L.w("[浮层] 拿不到 WindowManager");
            return;
        }

        final ImageView iv = new ImageView(ctx);
        iv.setImageBitmap(shot);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = loc[0];
        lp.y = loc[1];

        try {
            wm.addView(iv, lp);
        } catch (Throwable t) {
            L.w("[浮层] addView 失败: " + t);
            return;
        }
        sWm = wm;
        sCurrent = iv;

        L.i("[浮层] 开始播放: 起点=(" + loc[0] + "," + loc[1] + ") 尺寸=" + w + "x" + h
                + "  时长=" + Config.OVERLAY_DURATION_MS + "ms");

        // 屏幕尺寸：用来算放大终点与中心
        final int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        final int screenH = ctx.getResources().getDisplayMetrics().heightPixels;

        // 放大终点：受 OVERLAY_MAX_SCALE 限制，**不拉满全屏**（否则小图标会糊）
        float targetW = Math.min(screenW, w * Config.OVERLAY_MAX_SCALE);
        float targetH = Math.min(screenH, h * Config.OVERLAY_MAX_SCALE);
        // 但要保证看得见的变化量
        targetW = Math.max(targetW, w * 1.6f);
        targetH = Math.max(targetH, h * 1.6f);

        final float startCx = loc[0] + w / 2f;
        final float startCy = loc[1] + h / 2f;
        final float endCx = screenW / 2f;
        final float endCy = screenH / 2f;

        final float tW = targetW;
        final float tH = targetH;

        final android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(Config.OVERLAY_DURATION_MS);
        anim.setInterpolator(Easing.commitInterpolator());   // 小米弹簧参数
        anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                if (sCurrent != iv) {
                    a.cancel();
                    return;
                }
                float t = (Float) a.getAnimatedValue();
                float cw = w + (tW - w) * t;
                float ch = h + (tH - h) * t;
                float cx = startCx + (endCx - startCx) * t;
                float cy = startCy + (endCy - startCy) * t;
                lp.width = (int) cw;
                lp.height = (int) ch;
                lp.x = (int) (cx - cw / 2f);
                lp.y = (int) (cy - ch / 2f);
                // 末段淡出，交接给应用窗口
                float fadeStart = 1f - Config.OVERLAY_FADE_RATIO;
                iv.setAlpha(t <= fadeStart ? 1f
                        : Math.max(0f, 1f - (t - fadeStart) / Config.OVERLAY_FADE_RATIO));
                try {
                    wm.updateViewLayout(iv, lp);
                } catch (Throwable t2) {
                    a.cancel();
                }
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (sCurrent == iv) {
                    cancel();
                    L.i("[浮层] 播放结束，已移除");
                }
            }
        });
        anim.start();
    }

    /** 截取卡片的画面。优先用小米自己的 `captureSnapshot`，失败再退回 `View.draw`。 */
    private static Bitmap capture(View src, int w, int h) {
        // 1) 小米自带
        try {
            Class<?> helper = Class.forName(Targets.AOPTS_HELPER, false, src.getClass().getClassLoader());
            for (java.lang.reflect.Method m : helper.getDeclaredMethods()) {
                if (!m.getName().equals(Targets.M.CAPTURE_SNAPSHOT)) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0] == View.class) {
                    m.setAccessible(true);
                    Object r = m.invoke(null, src);
                    if (r instanceof Bitmap) {
                        L.i("[浮层] 用小米 captureSnapshot 截图成功 "
                                + ((Bitmap) r).getWidth() + "x" + ((Bitmap) r).getHeight());
                        return (Bitmap) r;
                    }
                }
            }
        } catch (Throwable t) {
            L.w("[浮层] captureSnapshot 不可用: " + t.getClass().getSimpleName());
        }
        // 2) 退回自己画
        try {
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            src.draw(c);
            L.i("[浮层] 用 View.draw 截图成功");
            return bmp;
        } catch (Throwable t) {
            L.w("[浮层] View.draw 截图失败: " + t);
            return null;
        }
    }
}
