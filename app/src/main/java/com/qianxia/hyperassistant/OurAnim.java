package com.qianxia.hyperassistant;

import android.animation.ValueAnimator;
import android.animation.TimeInterpolator;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.SurfaceControl;

import java.lang.reflect.Method;
import java.util.List;

/**
 * ★★ 方案 B 第二步：**我们自己驱动应用窗口的 leash**，不再建 MIUI 的
 * mask / letterbox / blur 三层。
 *
 * ===== 为什么（白框 / 黑屏 / 图标残留的根源）=====
 * MIUI 的 `HyperViewScaleRemoteTransition.startAnimation` 会：
 * <pre>
 *   setMaskSurface(整屏 buffer, viewInfo)   // drawViewOnCanvas 把位图按原尺寸画在 (0,0)，
 *                                           // 没盖住的地方就是铺色 → 白框／黑屏
 *   setBlurLeash(...)                       // BLUR_COLOR 层
 *   initLeashChild(...)                     // 建 _letterbox-bg 整屏色块 + 把 mask
 *                                           // setLayer(MAX) 压在最上层
 *   new HyperViewScaleAnimationImpl(...)    // 用 Folme 驱动
 * </pre>
 * 这些层互相叠加、颜色随图标深浅变化，所以"改铺色"永远治不好（白/黑/透明都试过了）。
 * 这里**一层都不建**：只拿系统给的 leash，自己按小米的曲线改它的
 * 位置 / 缩放 / 圆角，然后回调收尾。
 *
 * ===== 几何（全部来自实测的 ViewScaleAnimInfo，不是猜的）=====
 * <pre>
 *   viewRect = Rect(629,1320 - 757,1448)     源视图在屏上的矩形（图标 128x128）
 *   endBounds = Rect(0,0 - 1200,2608)        应用窗口的最终矩形（整屏）
 *   scale:  viewRect.w / endBounds.w  →  1.0  **统一缩放**
 *   中心:  (viewRect.centerX/Y)       →  (endBounds.centerX/Y)
 * </pre>
 * 用"中心 + 统一缩放"而不是各自拉 x/y，是为了**不把窗口非等比拉变形**
 * （MIUI 的 `ratioFrom/ratioTo` 就是干这个的）。
 *
 * 曲线用小米官方弹簧：打开 `OPEN_POS = spring(0.229, 1.10)`、
 * 返回/关闭 `CLOSE_POS = spring(0.29, 0.95)`（都是从 `EaseManager.createTimeInterpolator`
 * 的 SPRING 分支反汇编出来的参数，顺序：setDamping(ζ) / setResponse(T)）。
 *
 * ===== 与 B3 的耦合 =====
 * 一旦 `startAnimation` 由我们接管，MIUI 自己那套合并/打断状态机就**没有状态了**
 * （它的 `mAnimationImpl` 从没被创建），所以 `mergeAnimation` 必须一起接管，
 * 否则过渡永远等不到 `onTransitionFinished` → 会卡死。所以本类同时提供
 * `mergeOrFinish`：能反向就反向（上滑回桌面），否则立刻收尾。
 */
public final class OurAnim {

    private OurAnim() {
    }

    // ------------------------------------------------------------------ 小米官方弹簧

    /** 打开：`OPEN_POS_EASE = spring(0.229, 1.10)`。 */
    static final float OPEN_ZETA = 0.229f;
    static final float OPEN_PERIOD = 1.10f;
    /** 关闭/返回：`CLOSE_POS_EASE = spring(0.29, 0.95)`。 */
    static final float CLOSE_ZETA = 0.29f;
    static final float CLOSE_PERIOD = 0.95f;

    /** 阻尼弹簧：`x(t) = 1 - e^(-ζωt)(cos(ω_d t) + ζ/√(1-ζ²) sin(ω_d t))`。 */
    static final class Spring implements TimeInterpolator {
        final float zeta;
        final float period;
        final float omega;
        final float wd;
        final int durationMs;

        Spring(float zeta, float period) {
            this.zeta = zeta;
            this.period = period;
            this.omega = (float) (2 * Math.PI / period);
            this.wd = (float) (omega * Math.sqrt(1 - zeta * zeta));
            this.durationMs = firstCrossMs();
        }

        float raw(float t) {
            float e = (float) Math.exp(-zeta * omega * t);
            return 1f - e * (float) (Math.cos(wd * t)
                    + (zeta / Math.sqrt(1 - zeta * zeta)) * Math.sin(wd * t));
        }

        /**
         * 时长 = **首次到达目标**的时刻（和 {@link ReturnFlight} 里同一套判据）。
         * 残余衰减到 1% 要 2s 以上，那不是观感时长。
         */
        private int firstCrossMs() {
            for (int ms = 60; ms <= 3000; ms += 5) {
                if (Math.abs(1f - raw(ms / 1000f)) < 0.004f) {
                    return ms + 30;
                }
            }
            return 400;
        }

        @Override
        public float getInterpolation(float input) {
            float p = raw(input * durationMs / 1000f);
            return p < 0f ? 0f : (p > 1.3f ? 1.3f : p);     // 允许一点点回弹余量
        }
    }

    // ------------------------------------------------------------------ 状态

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile SurfaceControl sLeash;
    private static volatile Rect sWindow;          // 应用窗口的最终矩形（endBounds）
    private static volatile Rect sFrom;            // 源视图矩形
    private static volatile float sFromScale = 1f;
    private static volatile float sCornerFrom;
    private static volatile float sCornerTo;
    private static volatile Object sOpenCb;        // startAnimation 的回调
    private static volatile Object sMergeCb;       // mergeAnimation 的回调（合并后要收尾的那个）
    private static volatile ValueAnimator sAnim;
    private static volatile float sProgress;       // 0=在源视图处，1=整屏
    private static volatile boolean sRunning;
    private static volatile long sStartedAt;
    private static int sLog;

    /** 过渡是否由我们接管中（merge/打断要用）。 */
    static boolean busy() {
        return sRunning;
    }

    // ------------------------------------------------------------------ 打开动画

    /**
     * @return true 表示已经接管（调用方不要再转发给 MIUI）
     */
    static boolean start(final Object viewInfo, final Object info, final Object txn,
                         final Object cb) {
        try {
            Object view = call(viewInfo, "getView");
            if (!(view instanceof android.view.View)) {
                return false;
            }
            android.view.View v = (android.view.View) view;
            int type = intOf(call(info, "getType"), 1);
            boolean openScene = (type == 1);

            Object parse = callStaticOn(rtClassOf(viewInfo), "parseTransitionChanges", info, openScene);
            SurfaceControl leash = (SurfaceControl) field(parse, "animLeash");
            Rect end = (Rect) field(parse, "endBounds");
            float winCorner = floatOf(field(parse, "windowCornerRadius"), 0f);
            if (leash == null || end == null || end.width() <= 0 || end.height() <= 0) {
                L.w("[我们动画] 拿不到 leash/endBounds（leash=" + leash + " end=" + end + "），交回 MIUI");
                return false;
            }
            Rect viewRect = new Rect();
            try {
                Method m = android.view.View.class.getMethod("getBoundsOnScreen", Rect.class);
                m.setAccessible(true);
                m.invoke(v, viewRect);
            } catch (Throwable t) {
                L.w("[我们动画] getBoundsOnScreen 失败: " + t);
                return false;
            }
            if (viewRect.width() <= 0 || viewRect.height() <= 0) {
                L.w("[我们动画] 源视图矩形无效 " + viewRect + "，交回 MIUI");
                return false;
            }

            sLeash = leash;
            sWindow = new Rect(end);
            sFrom = new Rect(viewRect);
            sFromScale = viewRect.width() / (float) end.width();
            sCornerFrom = floatOf(call(viewInfo, "getRadius"), 0f);
            // 目标圆角：优先用 ViewInfo 里的"显示圆角"（MIUI 自己算好的，实测 174），
            // 其次用窗口圆角，最后退回源视图半径（至少不会更差）。
            int displayCorner = intOf(call(viewInfo, "getDisplayCornerRadius"), 0);
            sCornerTo = displayCorner > 0 ? displayCorner
                    : (winCorner > 0 ? winCorner : sCornerFrom);
            sOpenCb = cb;
            sMergeCb = null;
            sProgress = 0f;
            sRunning = true;
            sStartedAt = android.os.SystemClock.uptimeMillis();

            if (sLog++ < 40) {
                L.i("[我们动画] ★ 接管打开动画：type=" + type
                        + " 窗口=" + end + " 源视图=" + viewRect
                        + " 起始缩放=" + L.f(sFromScale)
                        + " 圆角 " + sCornerFrom + "→" + sCornerTo
                        + " 弹簧 ζ=" + OPEN_ZETA + " T=" + OPEN_PERIOD
                        + "s → " + new Spring(OPEN_ZETA, OPEN_PERIOD).durationMs + "ms");
            }
            apply(0f);
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    runOpen();
                }
            });
            return true;
        } catch (Throwable t) {
            L.e("[我们动画] 接管失败，交回 MIUI", t);
            sRunning = false;
            return false;
        }
    }

    private static void runOpen() {
        final Spring spring = new Spring(OPEN_ZETA, OPEN_PERIOD);
        if (sAnim != null) {
            sAnim.cancel();
        }
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(spring.durationMs);
        a.setInterpolator(spring);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator anim) {
                apply(anim.getAnimatedFraction());
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator anim) {
                if (sProgress >= 1f) {
                    L.i("[我们动画] 打开动画结束（已到整屏）");
                    finishAll(null);
                }
            }
        });
        sAnim = a;
        a.start();
    }

    /** 按进度 p（0=源视图处，1=整屏）写入 leash 的变换。 */
    private static void apply(float fraction) {
        try {
            SurfaceControl leash = sLeash;
            Rect win = sWindow;
            Rect from = sFrom;
            if (leash == null || win == null || from == null) {
                return;
            }
            float p = fraction;
            sProgress = p;
            float s = sFromScale + (1f - sFromScale) * p;
            float w = win.width() * s;
            float h = win.height() * s;
            // 中心对齐：从源视图中心 → 窗口中心
            float cx = from.centerX() + (win.centerX() - from.centerX()) * p;
            float cy = from.centerY() + (win.centerY() - from.centerY()) * p;
            float corner = sCornerFrom + (sCornerTo - sCornerFrom) * p;
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            t.setPosition(leash, cx - w / 2f, cy - h / 2f);
            t.setScale(leash, s, s);
            // `setCornerRadius` / `show` 这两个在公开 android.jar 里没有（@hide），
            // 编译期直接引用会 "cannot find symbol" —— 按名字反射调。
            txnCall(t, "setCornerRadius", leash, corner);
            t.setAlpha(leash, 1f);
            txnCall(t, "show", leash);
            t.apply();
        } catch (Throwable t) {
            L.w("[我们动画] 应用变换失败: " + t);
        }
    }

    // ------------------------------------------------------------------ 合并 / 打断

    /**
     * `mergeAnimation`：能反向就反向（上滑回桌面），否则立刻收尾。
     *
     * @return true 表示已接管（不要再转发给 MIUI）
     */
    static boolean mergeOrFinish(final Object info, final Object cb) {
        if (!sRunning) {
            return false;              // 没有在跑：交给调用方处理
        }
        try {
            int type = intOf(call(info, "getType"), -1);
            boolean toBack = isToBack(info);
            if (sLog++ < 60) {
                L.i("[我们动画] 合并：type=" + type + " toBack=" + toBack
                        + " 当前进度=" + L.f(sProgress)
                        + "（0=源视图处 1=整屏）");
            }
            sMergeCb = cb;
            if (toBack) {
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        runReverse();
                    }
                });
            }
            // 不是回桌面（例如应用画完的收尾合并）：什么都不做，
            // 打开动画跑完时会在 finishAll 里一并把 merge 回调也收尾。
            return true;
        } catch (Throwable t) {
            L.e("[我们动画] 合并处理失败", t);
            return false;
        }
    }

    /** 上滑回桌面：从**当前进度**用 CLOSE 弹簧退回源视图处。 */
    private static void runReverse() {
        final Spring spring = new Spring(CLOSE_ZETA, CLOSE_PERIOD);
        final float startAt = sProgress;
        L.i("[我们动画] ★ 反向（回源视图）：从进度 " + L.f(startAt) + " 退回，"
                + "弹簧 ζ=" + CLOSE_ZETA + " T=" + CLOSE_PERIOD + "s → " + spring.durationMs + "ms");
        if (sAnim != null) {
            sAnim.cancel();
        }
        ValueAnimator a = ValueAnimator.ofFloat(startAt, 0f);
        // 用"剩余距离"归一化，让回弹形状与小米关闭曲线一致
        a.setDuration((long) Math.max(80, spring.durationMs * Math.max(0.25f, startAt)));
        a.setInterpolator(spring);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator anim) {
                float k = anim.getAnimatedFraction();
                float p = startAt * (1f - k) + spring.getInterpolation(k) * 0f;
                apply(Math.max(0f, p));
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator anim) {
                L.i("[我们动画] 反向结束（回到源视图处）");
                apply(0f);
                finishAll(null);
            }
        });
        sAnim = a;
        a.start();
    }

    /**
     * 收尾：**无论如何都要回调系统**，否则过渡会一直等（会卡住整个转场）。
     * 同时把 merge 的回调也一起收掉（合并时系统给的是新的回调）。
     */
    static void finishAll(String why) {
        try {
            if (sAnim != null) {
                sAnim.cancel();
                sAnim = null;
            }
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            sRunning = false;
            Object mergeCb = sMergeCb;
            Object openCb = sOpenCb;
            sMergeCb = null;
            sOpenCb = null;
            if (mergeCb != null) {
                callback(mergeCb, t);
            }
            if (openCb != null) {
                callback(openCb, t);
            }
            if (sLog++ < 60) {
                L.i("[我们动画] 收尾完成" + (why == null ? "" : "（" + why + "）")
                        + " mergeCb=" + (mergeCb != null) + " openCb=" + (openCb != null));
            }
        } catch (Throwable t) {
            L.e("[我们动画] 收尾出错", t);
        }
    }

    /** 保险：万一动画卡住，也要在超时后收尾。 */
    static void watchDog() {
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sRunning
                        && android.os.SystemClock.uptimeMillis() - sStartedAt > 3000L) {
                    L.w("[我们动画] 超时 3s 仍未收尾 → 强制收尾（防卡过渡）");
                    finishAll("超时");
                }
                if (sRunning) {
                    watchDog();
                }
            }
        }, 1000L);
    }

    private static void callback(Object cb, SurfaceControl.Transaction t) {
        if (cb == null) {
            return;
        }
        try {
            for (Method m : cb.getClass().getMethods()) {
                if (!m.getName().equals("onTransitionFinished") || m.getParameterCount() != 2) {
                    continue;
                }
                Class<?> p0 = m.getParameterTypes()[0];
                // ★★ 实测（从 logcat 完整栈里读出来的硬事实）：
                //   java.lang.IllegalArgumentException: method ...onTransitionFinished
                //     argument 1 has type android.window.WindowContainerTransaction,
                //     got android.view.SurfaceControl$Transaction
                // 这代（Android 17 / HyperOS 4）的第一个参数是 **WindowContainerTransaction**，
                // 不是 AOSP 老签名里的 SurfaceControl.Transaction。
                Object arg0 = null;
                if (p0.isInstance(t)) {
                    arg0 = t;                                   // 老签名
                } else if ("android.window.WindowContainerTransaction".equals(p0.getName())) {
                    arg0 = newWindowContainerTransaction();      // 新签名
                }
                m.setAccessible(true);
                m.invoke(cb, arg0, null);
                if (sLog++ < 80) {
                    L.i("[我们动画] 已回调 onTransitionFinished(" + p0.getSimpleName()
                            + (arg0 == null ? "=null" : "") + ", null) 让系统收尾");
                }
                return;
            }
            L.w("[我们动画] 回调对象 " + cb.getClass().getName() + " 上没有 onTransitionFinished/2");
        } catch (Throwable t2) {
            Throwable c = t2;
            while (c.getCause() != null && c.getCause() != c) {
                c = c.getCause();
            }
            L.e("[我们动画] 回调失败: " + t2.getClass().getSimpleName()
                    + " → 根因 " + c.getClass().getName() + ": " + c.getMessage()
                    + "  cbClass=" + cb.getClass().getName(), t2);
        }
    }

    /** `android.window.WindowContainerTransaction`（可能不在公开 android.jar 里，反射造）。 */
    private static Object newWindowContainerTransaction() {
        try {
            Class<?> c = Class.forName("android.window.WindowContainerTransaction");
            return c.getConstructor().newInstance();
        } catch (Throwable t) {
            return null;      // 传 null 也可以：系统只是要一个"我完成了"的信号
        }
    }

    // ------------------------------------------------------------------ 判定/反射小工具

    static final String CLASS_RT = "android.app.hyperviewscale.HyperViewScaleRemoteTransition";

    /** 有 TO_BACK(4) 且没有 OPENING(1) → 用户上滑回桌面（和 HvsDiagnostics 同一套判据）。 */
    private static boolean isToBack(Object info) {
        boolean hasToBack = false;
        boolean hasOpening = false;
        Object changes = call(info, "getChanges");
        if (changes instanceof List) {
            for (Object c : (List<?>) changes) {
                int mode = intOf(call(c, "getMode"), -1);
                if (mode == 4) {
                    hasToBack = true;
                }
                if (mode == 1) {
                    hasOpening = true;
                }
            }
        }
        return hasToBack && !hasOpening;
    }

    private static Object field(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static Object call(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        try {
            Method m = findMethod(obj.getClass(), name, 0);
            if (m == null) {
                return null;
            }
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object callStatic(String className, String name, Object... args) {
        return callStaticOn(null, className, name, args);
    }

    /**
     * ★ 必须用一个**真实的类**去调静态方法，不能 `Class.forName(名字)` ——
     * hyperviewscale 的类在一个**子 ClassLoader** 里（miui-framework.hyperviewscale.jar），
     * 用默认 loader 会 `ClassNotFoundException`（这轮实测踩到，B2 因此没接管成功）。
     * 所以从手上已有的对象（`mViewInfo`）拿它的 ClassLoader。
     */
    private static Class<?> rtClassOf(Object anyFromChildLoader) {
        ClassLoader cl = anyFromChildLoader == null ? null
                : anyFromChildLoader.getClass().getClassLoader();
        try {
            return Class.forName(CLASS_RT, false, cl);
        } catch (Throwable t) {
            L.w("[我们动画] 子 ClassLoader 里找不到 " + CLASS_RT + ": " + t);
            try {
                return Class.forName(CLASS_RT);
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    private static Object callStaticOn(Class<?> cls, String name, Object... args) {
        if (cls == null) {
            return null;
        }
        try {
            Method m = findMethod(cls, name, args.length);
            if (m == null) {
                L.w("[我们动画] " + cls.getSimpleName() + " 上没有 " + name + "/" + args.length);
                return null;
            }
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Throwable t) {
            L.w("[我们动画] 调 " + cls.getSimpleName() + "." + name + " 失败: " + t);
            return null;
        }
    }

    /** 反射调 `SurfaceControl.Transaction` 上的 @hide 方法（setCornerRadius / show 等）。 */
    private static void txnCall(Object txn, String name, Object... args) {
        try {
            Method m = findMethod(txn.getClass(), name, args.length);
            if (m == null) {
                return;
            }
            m.setAccessible(true);
            m.invoke(txn, args);
        } catch (Throwable t) {
            L.w("[我们动画] txn." + name + " 失败: " + t);
        }
    }

    private static Method findMethod(Class<?> c, String name, int argc) {        Class<?> k = c;
        while (k != null) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argc) {
                    return m;
                }
            }
            k = k.getSuperclass();
        }
        return null;
    }

    static int intOf(Object o, int def) {
        return o instanceof Number ? ((Number) o).intValue() : def;
    }

    static float floatOf(Object o, float def) {
        return o instanceof Number ? ((Number) o).floatValue() : def;
    }

    /** 打点用：当前是否接管中。 */
    static String state() {
        return "running=" + sRunning + " progress=" + L.f(sProgress)
                + " leash=" + sLeash + " window=" + sWindow;
    }

    /** 给 B3 用：过渡绑定的 leash（可能为 null）。 */
    static SurfaceControl leash() {
        return sLeash;
    }

    /** 给 B3 用：打断标记（未使用，占位以免误用）。 */
    static IBinder unused() {
        return null;
    }
}
