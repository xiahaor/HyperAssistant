package com.qianxia.hyperassistant;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;

import de.robv.android.xposed.XposedHelpers;

/**
 * ★ "从哪里来回哪里去" —— 自己画的返场动画。
 *
 * ===== 为什么必须自己画 =====
 * 实测（见 `[HVS反向]` 那批日志）：
 * <pre>
 *   侧划返回（Activity 真的 finish）
 *       → mergeAnimation type=2  changeModes=[2,3,3]
 *       → reverseAnimationWhenMerge(binder, true, true, view, id) → true   ✔ 系统帮我们飞回去
 *
 *   上滑回桌面（TO_BACK）
 *       → **没有任何回调**（只有打开后约 346ms 一次系统自动合并）
 *       → 窗口直接消失                                                      ✘
 * </pre>
 * 也就是说 TO_BACK 这条**系统根本不叫我们**，在应用侧调参数是调不出来的。
 *
 * 而且桌面之所以能"飞回图标"，是因为那段动画**是桌面自己实现的**
 * （`com.miui.home` 的返回/最近任务动画，它知道每个图标在哪）；
 * HyperViewScale 的关闭过渡只在 `ActivityRecord.finishIfPossible()` 时才发起。
 * 负一屏的小部件属于 `com.miui.personalassistant`，桌面那套逻辑里没有它们的位置。
 *
 * ===== 做法 =====
 * 点击时我们已经拿到了官方截的**小部件位图**和它的**屏幕矩形**（`ViewInfo`），
 * 于是：
 *   1. 把这些记下来（{@link #arm}）
 *   2. 给源视图挂一个**窗口焦点**监听：应用盖住负一屏时焦点会丢，用户回来时焦点会回来
 *   3. 焦点回来的那一刻，在我们自己的窗口上盖一层浮层，
 *      把位图从"铺满全屏"缩回小部件矩形（{@link #play}）
 *
 * 好处：不碰系统、不要权限，而且**同时覆盖 2x2 / 4x4 / 微信支付宝快捷方式**，
 * 因为不再依赖系统的过渡回调。
 *
 * 曲线用小米的关闭弹簧参数：ζ=0.95、T=0.29s（取自
 * `HyperViewScaleAnimationImpl` 的 `CLOSE_POS_EASE = FolmeEase.spring(0.29, 0.95)`，
 * 按 miuix 的 `spring(阻尼比, 周期秒)` 解释）。
 */
public final class ReturnFlight {

    private ReturnFlight() {
    }

    private static volatile Bitmap sBitmap;
    private static volatile Rect sRect;
    private static volatile int sRadius;
    private static volatile long sArmedAt;
    /** 负一屏是否**曾经失去过焦点**（用来区分"刚打开"和"真的回来了"）。 */
    private static volatile boolean sLostFocus;
    /** 失焦时刻 —— "离开多久"必须从这个时刻算，不能用点击时刻。 */
    private static volatile long sLostAt;
    /** 播放期间/播放后的冷却截止时刻，避免自己造成的焦点抖动触发自我循环。 */
    private static volatile long sCooldownUntil;
    private static volatile boolean sPlaying;
    private static volatile View sAnchor;
    private static volatile boolean sListenerAttached;

    /**
     * 记下这次打开动画用的位图和起点矩形。
     *
     * @param anchor 源视图（用来找窗口、匹配 ViewRootImpl）
     */
    public static void arm(ClassLoader cl, View anchor, Bitmap bmp, Rect rect, float radius) {
        if (!Config.HVS_RETURN_FLIGHT || anchor == null || bmp == null || rect == null
                || rect.isEmpty()) {
            return;
        }
        sAnchor = anchor;
        sBitmap = bmp;
        sRect = new Rect(rect);
        sRadius = Math.max(0, Math.round(radius));
        sArmedAt = android.os.SystemClock.uptimeMillis();
        sLostFocus = false;
        sHiddenAt = 0L;
        if (Config.HVS_RETURN_LEGACY_TRIGGERS) {
            attachFocusListener(anchor);
            installVisibilityHook(cl, anchor);
            // ★ 这个轮询是"每 250ms 一次、最长 120 秒"，而且它只是旧触发路径的观测手段
            //   （真正的返场触发已经改成"官方反向失败"）。每次启动都挂 2 分钟的定时器
            //   是不必要的开销 —— 归到同一个开关里。
            startPolling(anchor);
        }
        L.i("[返场] 已备好返场素材: 位图=" + bmp.getWidth() + "x" + bmp.getHeight()
                + " 起点矩形=" + rect + " 圆角=" + sRadius);
    }

    // ------------------------------------------------------------------ 轮询（不依赖隐藏 API）

    private static volatile boolean sPolling;
    private static volatile int sLastWindowVis = Integer.MIN_VALUE;

    /**
     * 轮询源视图的**窗口可见性**（`View.getWindowVisibility()`，公开 API）。
     *
     * 为什么不用 `ViewRootImpl.setWindowVisibility`：实测钩子挂上了，但**这个方法从没被调用**
     * （日志里 `窗口可见性 ->` 一条都没有），说明负一屏的窗口不是走那条路隐藏的。
     *
     * 轮询的好处：不依赖任何隐藏 API、不需要知道系统走哪条路，
     * 而且**两个方向都能观测到**（变不可见 / 恢复可见）。
     * 250ms 一次，开销可以忽略，而且只在"刚点开过应用"之后才跑。
     */
    private static void startPolling(final View anchor) {
        if (sPolling) {
            return;
        }
        sPolling = true;
        sLastWindowVis = Integer.MIN_VALUE;
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (android.os.SystemClock.uptimeMillis() - sArmedAt
                            > Config.HVS_RETURN_POLL_WINDOW_MS) {
                        sPolling = false;
                        L.i("[返场] 轮询结束（超过 " + Config.HVS_RETURN_POLL_WINDOW_MS + "ms）");
                        return;
                    }
                    int vis = anchor.getWindowVisibility();
                    if (vis != sLastWindowVis) {
                        sLastWindowVis = vis;
                        L.i("[返场] （参考）窗口可见性变化: " + visName(vis)
                                + "（VISIBLE=0 INVISIBLE=4 GONE=8）");
                    }
                    pollServerSignal();
                    h.postDelayed(this, 250L);
                } catch (Throwable t) {
                    sPolling = false;
                    L.w("[返场] 轮询出错: " + t);
                }
            }
        });
        L.i("[返场] 已开始轮询窗口可见性（每 250ms，最多 "
                + Config.HVS_RETURN_POLL_WINDOW_MS + "ms）");
    }

    private static String visName(int v) {
        switch (v) {
            case View.VISIBLE: return "VISIBLE";
            case View.INVISIBLE: return "INVISIBLE";
            case View.GONE: return "GONE";
            default: return String.valueOf(v);
        }
    }

    // ------------------------------------------------------------------ 主信号：system_server 中继

    private static volatile long sLastServerSignal;

    /**
     * ★ 真正的主信号：轮询 system_server 写下的系统属性 `debug.hvs.return_at`。
     *
     * 为什么必须这么做（实测三条路全废）：
     * <pre>
     *   窗口焦点     应用打开转场时会抖回来，间隔 400~1700ms，跟真实返回区分不开
     *   窗口可见性   被遮挡时**不会**变 GONE（Android 不因遮挡改可见性），
     *                实测只在转场时抖 250~1250ms，之后一直是 VISIBLE
     *   isVisibleToUser  一直是 true
     * </pre>
     * 应用侧没有任何 API 能知道"应用是不是盖在最上面"，只有 system_server 知道。
     * 那边（{@link HvsServerDiagnostics#installFocusRelay}）在焦点落到负一屏窗口
     * （`LauncherOverlayWindow:com.miui.personalassistant`）时写一个时间戳，
     * 这里轮询发现时间戳变了就播返场。
     */
    private static void pollServerSignal() {
        try {
            if (sSystemProperties == null) {
                sSystemProperties = Class.forName("android.os.SystemProperties");
            }
            Object v = XposedHelpers.callStaticMethod(sSystemProperties, "get",
                    HvsServerDiagnostics.PROP_RETURN, "");
            if (!(v instanceof String)) {
                return;
            }
            String s = (String) v;
            if (s.isEmpty()) {
                return;
            }
            long ts = Long.parseLong(s.trim());
            if (ts == sLastServerSignal) {
                return;
            }
            long first = sLastServerSignal;
            sLastServerSignal = ts;
            if (first == 0L) {
                L.i("[返场] 已接上 system_server 中继信号（" + HvsServerDiagnostics.PROP_RETURN
                        + "=" + ts + "），等下一次变化");
                return;
            }
            long now = android.os.SystemClock.uptimeMillis();
            long away = ts - sArmedAt;
            L.i("[返场] ★★ system_server 报：焦点回到负一屏（距点击 " + away + "ms）");
            if (now < sCooldownUntil || sPlaying) {
                return;
            }
            if (away < Config.HVS_RETURN_MIN_AGE_MS) {
                L.i("[返场] 但只过了 " + away + "ms，太短，不播");
                return;
            }
            Bitmap b = sBitmap;
            Rect r = sRect;
            if (b == null || r == null || b.isRecycled()) {
                return;
            }
            L.i("[返场] ★★ 播返场动画（由 system_server 中继触发）");
            play(sAnchor, b, r);
        } catch (Throwable t) {
            if (sRelayErr++ < 3) {
                L.w("[返场] 读中继信号失败: " + t);
            }
        }
    }

    private static volatile Class<?> sSystemProperties;
    private static int sRelayErr;

    // ------------------------------------------------------------------ 主信号：窗口可见性

    private static volatile boolean sVisibilityHookInstalled;
    private static volatile long sHiddenAt;

    /**
     * ★ 主信号：`ViewRootImpl.setWindowVisibility(boolean)`。
     *
     * 为什么换掉窗口焦点：实测焦点在应用打开转场时会**抖回来**，
     * 间隔全是 400~1700ms（400/460/505/568/696/757/949/1238/1274/1315/1449/1572/1690），
     * 跟"用户真的回到负一屏"完全区分不开：
     *   · 门槛设 250ms  → 抖动被当成回来了 → 错时播放
     *   · 门槛设 2500ms → 真实返回也被挡掉 → 4x2 那种"小白条有返回动画"就没了
     * 两个方向都是错的，问题在信号本身。
     *
     * 应用盖住负一屏时，负一屏的**窗口是真的被隐藏**（visibility 变 false），
     * 用户回来时才重新变 true —— 这才是"用户真的回来了"。
     *
     * 只认目标窗口：`ViewRootImpl.getView()` 必须等于源视图的 `getRootView()`，
     * 免得被负一屏里别的窗口（弹窗之类）干扰。
     */
    private static void installVisibilityHook(ClassLoader cl, final View anchor) {
        if (sVisibilityHookInstalled) {
            return;
        }
        Class<?> vr = HookKit.findClass(cl, "android.view.ViewRootImpl");
        if (vr == null) {
            L.w("[返场] 找不到 ViewRootImpl，窗口可见性信号不可用（回退到焦点）");
            return;
        }
        sVisibilityHookInstalled = true;
        HookKit.hookAll(vr, "setWindowVisibility", new HookKit.Cb("返场.窗口可见性") {
            @Override
            protected void onBefore(MethodHookParam p) {
                boolean visible = p.args != null && p.args.length > 0
                        && Boolean.TRUE.equals(p.args[0]);
                // 只认负一屏那个窗口
                try {
                    Object decor = XposedHelpers.callMethod(p.thisObject, "getView");
                    if (!(decor instanceof View) || decor != anchor.getRootView()) {
                        return;
                    }
                } catch (Throwable t) {
                    return;
                }
                L.i("[返场] 窗口可见性 -> " + visible);
                if (visible) {
                    onWindowVisible();
                } else {
                    onWindowHidden();
                }
            }
        });
        L.i("[返场] 窗口可见性监听已挂上（ViewRootImpl.setWindowVisibility）");
    }

    private static void onWindowHidden() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now < sCooldownUntil) {
            return;
        }
        sHiddenAt = now;
    }

    private static void onWindowVisible() {
        long now = android.os.SystemClock.uptimeMillis();
        if (sHiddenAt == 0L || now < sCooldownUntil || sPlaying) {
            return;
        }
        long away = now - sHiddenAt;
        if (away < Config.HVS_RETURN_MIN_AGE_MS) {
            L.i("[返场] 窗口回来了但只隐藏了 " + away + "ms，太短，不播");
            return;
        }
        Bitmap b = sBitmap;
        Rect r = sRect;
        if (b == null || r == null || b.isRecycled()) {
            return;
        }
        sHiddenAt = 0L;
        L.i("[返场] ★ 负一屏窗口回来了（隐藏了 " + away + "ms）→ 播返场动画");
        play(sAnchor, b, r);
    }

    /**
     * 挂窗口焦点监听。
     *
     * 为什么用焦点而不是"视图可见性"：实测源视图在应用盖住负一屏时
     * `isVisibleToUser()` 仍然是 true（+1000ms 就恢复了），根本区分不出来；
     * 而**窗口焦点**在应用盖上来时会丢、用户回来时会回来，是干净的信号。
     */
    private static void attachFocusListener(final View anchor) {
        if (sListenerAttached) {
            return;
        }
        try {
            anchor.getViewTreeObserver().addOnWindowFocusChangeListener(
                    new ViewTreeObserver.OnWindowFocusChangeListener() {
                        @Override
                        public void onWindowFocusChanged(boolean hasFocus) {
                            try {
                                onFocus(anchor, hasFocus);
                            } catch (Throwable t) {
                                L.w("[返场] 焦点回调出错: " + t);
                            }
                        }
                    });
            sListenerAttached = true;
            L.i("[返场] 窗口焦点监听已挂上");
        } catch (Throwable t) {
            L.w("[返场] 挂焦点监听失败: " + t);
        }
    }

    private static void onFocus(View anchor, boolean hasFocus) {
        // ★ 只记录，不再触发。
        //
        // 触发已改由 onWindowVisible（窗口可见性）负责。保留焦点日志纯粹是为了
        // 在同一轮测试里对比两个信号 —— 实测焦点信号不可用（转场时会抖回来，
        // 间隔 400~1700ms，跟真实返回区分不开），见 installVisibilityHook 的注释。
        long now = android.os.SystemClock.uptimeMillis();
        sLastFocusAt = now;
        L.i("[返场] （参考）焦点事件 hasFocus=" + hasFocus
                + (hasFocus ? "" : "  ← 应用盖住负一屏") + "  [仅记录，触发看窗口可见性]");
    }

    private static volatile long sLastFocusAt;

    // ------------------------------------------------------------------ 播放

    /**
     * ★ 供 system_server 的 hyper 通道调用：用**最后记下的素材**播一段返场动画。
     *
     * 与 {@link #onWindowVisible()} 那条路不同：那条靠猜信号，这条是
     * MIUI 的 `IHyperRemoteTransition.startActivityFinished(...)` **主动来叫我们**
     * （见 {@link HyperTransition}）。播完会执行 {@code onEnd}，用来回调
     * `onTransitionFinished` 让系统收尾。
     *
     * @return true 表示已经开播（onEnd 会被调用）；false 表示没有素材/正在播
     */
    public static boolean playNow(Runnable onEnd) {
        Bitmap b = sBitmap;
        Rect r = sRect;
        View a = sAnchor;
        if (b == null || r == null || a == null || b.isRecycled()) {
            L.w("[返场] playNow：没有可用素材（还没点过小部件？）");
            return false;
        }
        if (sPlaying) {
            L.w("[返场] playNow：上一次还在播，直接返回并立刻回调");
            if (onEnd != null) {
                onEnd.run();
            }
            return true;
        }
        L.i("[返场] ★★ playNow（由 system_server 的 hyper 通道触发）矩形=" + r
                + " 位图=" + b.getWidth() + "x" + b.getHeight());
        play(a, b, r, onEnd);
        return true;
    }

    /**
     * 已备好的素材位图的**边缘色**，给遮罩层铺底用。
     *
     * 为什么需要：`drawViewOnCanvas` 先 `drawColor(fill)` 再 `drawBitmap(bmp,0,0)`，
     * 位图（圆角方图）没盖住的地方就露出 fill。官方 fill = 图标主色（负一屏图标算出来
     * 是白色 → 白框），改成透明又露出黑底（黑屏）。用位图自己的边缘色，
     * 圆角外就跟图标接上了。
     */
    public static int armedEdgeColor() {
        Bitmap b = sBitmap;
        if (b == null || b.isRecycled()) {
            return 0;
        }
        int cachedFor = sEdgeColorFor;
        if (cachedFor == System.identityHashCode(b)) {
            return sEdgeColor;
        }
        int c = edgeColorOf(b);
        sEdgeColor = c;
        sEdgeColorFor = System.identityHashCode(b);
        L.i("[返场] 位图边缘色 = #" + String.format("%08X", c)
                + "（位图 " + b.getWidth() + "x" + b.getHeight() + "）");
        return c;
    }

    private static volatile int sEdgeColor;
    private static volatile int sEdgeColorFor = -1;

    /** 沿四条边采样，取出现最多的不透明色。 */
    private static int edgeColorOf(Bitmap bmp) {
        try {
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            if (w < 4 || h < 4) {
                return 0;
            }
            java.util.HashMap<Integer, Integer> hist = new java.util.HashMap<>();
            int best = 0;
            int bestCount = 0;
            int n = 24;
            for (int i = 0; i < n; i++) {
                int x = 1 + (w - 3) * i / (n - 1);
                int y = 1 + (h - 3) * i / (n - 1);
                int[] samples = {
                        bmp.getPixel(x, 1),
                        bmp.getPixel(x, h - 2),
                        bmp.getPixel(1, y),
                        bmp.getPixel(w - 2, y),
                };
                for (int c : samples) {
                    if ((c >>> 24) < 0xF0) {
                        continue;
                    }
                    Integer k = c;
                    Integer cnt = hist.get(k);
                    int v = cnt == null ? 1 : cnt + 1;
                    hist.put(k, v);
                    if (v > bestCount) {
                        bestCount = v;
                        best = c;
                    }
                }
            }
            return best;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void play(View anchor, Bitmap bmp, Rect rect) {
        play(anchor, bmp, rect, null);
    }

    /**
     * ★★ 上滑回桌面、但**开启动画已经播完**时走这条。
     *
     * 为什么需要它：`HyperViewScaleAnimationImpl.reverseAnimationWhenMerge`
     * 的第一个 gate 就是 `if (!mIsRunning) return false;`，而开启动画一收尾
     * `postFinishAndReset() → reset()` 就把 leash `SurfaceControl.release()` 掉了。
     * 实测：动画还在播时反向返回 true（飞回去，用户确认）；动画播完再返回
     * 一律返回 false → 系统 `finishAnimation()` 硬切 = "从中间消失"。
     * 也就是说官方反向那条路在动画结束后**物理上不存在了**，只能我们自己补。
     *
     * 为什么不能立刻播：应用盖住负一屏时，负一屏窗口是 `GONE`
     * （实测 `窗口可见性变化: GONE` 在应用打开后约 +0.5s，`VISIBLE` 在返回时），
     * 窗口 GONE 的时候往里面加浮层是**看不见的**。所以先等窗口真的可见。
     */
    public static void playWhenWindowVisible() {
        final View a = sAnchor;
        if (a == null) {
            L.w("[返场] 兜底：没有 anchor，放弃");
            return;
        }
        final long deadline = android.os.SystemClock.uptimeMillis() + 2000L;
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            @Override
            public void run() {
                if (sPlaying) {
                    return;
                }
                long now = android.os.SystemClock.uptimeMillis();
                boolean shown = onScreenNow(a);
                if (shown) {
                    L.i("[返场] 兜底：负一屏确实在屏幕上（等了 "
                            + (2000L - (deadline - now)) + "ms），开始播返场浮层"
                            + "  " + locStr(a));
                    playNow(null);
                } else if (now >= deadline) {
                    L.w("[返场] 兜底：等负一屏回到屏幕上超时，还是播一把（可能看不见）"
                            + "  " + locStr(a));
                    playNow(null);
                } else {
                    h.postDelayed(this, 30L);
                }
            }
        });
    }

    /**
     * ★ 负一屏是否**真的显示在屏幕上**。
     *
     * 坑：`View.getWindowVisibility()` 在"负一屏被 launcher 停在旁边"时**仍然是 VISIBLE** ——
     * 那种状态下 launcher 把窗口 `alpha` 设成 0、并把内嵌表面整体滚到屏幕外
     * （实测 `getLocationOnScreen` 给出 x = -1125…-219）。所以只看窗口可见性会把浮层
     * 播进一个看不见的窗口里（用户什么都看不到）。
     *
     * 正确的判据是**这个视图自己的屏幕坐标**（这也是 `TestTrigger` 判断
     * "候选 屏上 N/M" 用的同一个信号，实测停旁边时确实是 0/N）。
     */
    private static boolean onScreenNow(View v) {
        try {
            if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) {
                return false;
            }
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            int cx = loc[0] + v.getWidth() / 2;
            int cy = loc[1] + v.getHeight() / 2;
            return cx >= 0 && cy >= 0 && cx <= 1200 && cy <= 2608;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String locStr(View v) {
        try {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            return "screen=" + loc[0] + "," + loc[1]
                    + " size=" + v.getWidth() + "x" + v.getHeight()
                    + " winVis=" + visName(v.getWindowVisibility());
        } catch (Throwable t) {
            return "loc?";
        }
    }

    private static void play(View anchor, Bitmap bmp, Rect rect, final Runnable onEnd) {
        ViewGroup root = rootOf(anchor);
        if (root == null) {
            L.w("[返场] 找不到根 ViewGroup，放弃");
            if (onEnd != null) {
                onEnd.run();
            }
            return;
        }
        sPlaying = true;
        // 冷却：从现在起到动画结束再往后一段时间里，忽略所有焦点事件。
        // 因为"加浮层 / 移浮层"本身会让窗口焦点抖一下，不挡住就会变成
        // 播一次→抖一次→再播一次的无限循环（实测每 ~600ms 一轮）。
        sCooldownUntil = android.os.SystemClock.uptimeMillis()
                + Config.HVS_RETURN_DURATION_MS + Config.HVS_RETURN_COOLDOWN_MS;
        try {
            final FrameLayout overlay = new FrameLayout(root.getContext());
            overlay.setClipChildren(false);
            // 浮层不吃触摸、不抢焦点，避免干扰底下的负一屏
            overlay.setClickable(false);
            overlay.setFocusable(false);
            final ImageView iv = new ImageView(root.getContext());
            iv.setImageBitmap(bmp);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            // 位图会被放大很多倍，开双线性插值，别出硬锯齿
            // （setFilterBitmap 在 Drawable 上，不在 ImageView 上）
            if (iv.getDrawable() != null) {
                iv.getDrawable().setFilterBitmap(true);
            }
            iv.setPivotX(bmp.getWidth() / 2f);
            iv.setPivotY(bmp.getHeight() / 2f);

            // 起始状态：把位图放到小部件原来的位置，再整体放大到铺满屏幕
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    rect.width(), rect.height());
            lp.leftMargin = rect.left;
            lp.topMargin = rect.top;
            overlay.addView(iv, lp);
            root.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            int screenH = root.getHeight() > 0 ? root.getHeight() : 2608;
            int screenW = root.getWidth() > 0 ? root.getWidth() : 1200;
            final float from = Math.max(screenH / (float) Math.max(1, rect.height()),
                    screenW / (float) Math.max(1, rect.width()));
            final float startTx = screenW / 2f - (rect.left + rect.width() / 2f);
            final float startTy = screenH / 2f - (rect.top + rect.height() / 2f);

            iv.setScaleX(from);
            iv.setScaleY(from);
            iv.setTranslationX(startTx);
            iv.setTranslationY(startTy);
            iv.setAlpha(1f);

            L.i("[返场] 播放: 矩形=" + rect + " 放大倍数=" + L.f(from)
                    + " 时长=" + Config.HVS_RETURN_DURATION_MS + "ms");

            // 单驱动：一个 ValueAnimator + 小米关闭弹簧，统一算 scale / 位移 / 透明度，
            // 避免多个 ObjectAnimator 各自插值打架。
            final TimeInterpolator ease = new XiaomiCloseSpring();
            final int durationMs = XiaomiCloseSpring.FIRST_CROSS_MS;
            L.i("[返场] 曲线: 官方 CLOSE_POS_EASE ζ=" + XiaomiCloseSpring.ZETA
                    + " T=" + XiaomiCloseSpring.PERIOD + "s  → 时长 "
                    + durationMs + "ms（首次到达目标的时刻，解析算出）");
            ValueAnimator driver = ValueAnimator.ofFloat(0f, 1f);
            driver.setDuration(durationMs);
            driver.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    float p = ease.getInterpolation(a.getAnimatedFraction());
                    float sc = from + (1f - from) * p;
                    iv.setScaleX(sc);
                    iv.setScaleY(sc);
                    iv.setTranslationX(startTx * (1f - p));
                    iv.setTranslationY(startTy * (1f - p));
                    iv.setAlpha(p < 0.75f ? 1f : (1f - p) / 0.25f);
                }
            });
            driver.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator a) {
                    finish(root, overlay, onEnd);
                }
            });
            driver.start();
        } catch (Throwable t) {
            L.e("[返场] 播放失败", t);
            sPlaying = false;
            if (onEnd != null) {
                onEnd.run();
            }
        }
    }

    private static void finish(ViewGroup root, View overlay, Runnable onEnd) {
        try {
            root.removeView(overlay);
        } catch (Throwable ignored) {
        }
        sPlaying = false;
        L.i("[返场] 播放结束，浮层已移除");
        if (onEnd != null) {
            try {
                onEnd.run();
            } catch (Throwable t) {
                L.e("[返场] 播完回调出错", t);
            }
        }
    }

    /**
     * 找浮层的宿主：**必须是窗口级的容器**（DecorView / android.R.id.content），
     * 不能随便拿一个祖先 ViewGroup。
     *
     * 踩过的坑：原来从源视图往上找"第一个有尺寸的 ViewGroup"，
     * 而点击源是 `ImageView`，它的父容器正好是**那一行图标的 LinearLayout**，
     * 结果浮层 MATCH_PARENT 加到了一行上，把 128x128 的位图拉伸铺满整行 ——
     * 表现就是"一行应用上严重锯齿/闪"。
     */
    private static ViewGroup rootOf(View anchor) {
        try {
            View root = anchor.getRootView();
            if (root instanceof ViewGroup) {
                ViewGroup decor = (ViewGroup) root;
                // 优先用 content 容器（尺寸稳定、不掺系统栏）
                View content = decor.findViewById(android.R.id.content);
                if (content instanceof ViewGroup
                        && ((ViewGroup) content).getWidth() > 0
                        && ((ViewGroup) content).getHeight() > 0) {
                    return (ViewGroup) content;
                }
                if (decor.getWidth() > 0 && decor.getHeight() > 0) {
                    return decor;
                }
            }
        } catch (Throwable t) {
            L.w("[返场] 找窗口容器失败: " + t);
        }
        return null;
    }

    // ------------------------------------------------------------------ 曲线

    /**
     * 小米的关闭弹簧：**直接取自官方常量**，不再自己拍。
     *
     * `HyperViewScaleAnimationImpl.<clinit>`：
     * <pre>
     *   CLOSE_POS_EASE  = FolmeEase.spring(0.29f, 0.95f)
     *   CLOSE_SIZE_EASE = FolmeEase.spring(0.29f, 0.95f)
     *   CLOSE_RATIO_EASE= FolmeEase.spring(0.29f, 1.00f)
     *   CLOSE_CORNER_EASE = FolmeEase.spring(0.20f, 1.00f)
     *   FULL_WIDTH_CLOSE_* = FolmeEase.spring(0.30f, 0.955f)
     * </pre>
     *
     * ===== 形参顺序（这次是**有字段名铁证**的，不是猜）=====
     * `miuix.animation.physics.SpringForce`：
     * <pre>
     *   setDampingRatio(F) → mDampingRatio      // 阻尼比
     *   setResponse(F)     → mNaturalFreq       // 周期/频率
     *   默认 mDampingRatio = 0x3FE00000 = 0.5f  （= AOSP DAMPING_RATIO_MEDIUM_BOUNCY）
     * </pre>
     * 而 `EaseManager.createTimeInterpolator` 的 SPRING 分支是
     * `new SpringInterpolator(); setDamping(params[0]); setResponse(params[1]);`
     * ⇒ **`spring(a, b)` 中 a = 阻尼比 ζ，b = 周期 T（秒）**。
     *
     * 所以真实关闭曲线是 ζ=0.29、T=0.95，**比我上一版用的 ζ=0.95/T=0.29 更弹、更长**。
     * 自检：ζ=0.229/T=1.10（开启）时 ω_d = ω√(1−ζ²) = 5.55，第一个波峰在 π/ω_d ≈ 0.57 s，
     * 与肉眼看到的"约 0.5 s 完成"对得上 —— 支持这个读法。
     *
     * 结束时刻不手写常量：按 miuix 自己的收敛判据
     * （`IAnimTarget.setMinVisibleChange`：每帧变化小于阈值即认为结束）
     * 算出"剩余偏移 < 1%"的时刻作为时长。
     */
    private static final class XiaomiCloseSpring implements TimeInterpolator {
        static final float ZETA = 0.29f;                 // CLOSE_POS_EASE 的阻尼比
        static final float PERIOD = 0.95f;               // CLOSE_POS_EASE 的周期（秒）
        static final float OMEGA = (float) (2 * Math.PI / PERIOD);
        static final float WD = (float) (OMEGA * Math.sqrt(1 - ZETA * ZETA));

        /**
         * 动画时长 = **首次到达目标**的时刻（不是"残余衰减到 1%"）。
         *
         * 为什么：ζ=0.29/T=0.95 的残余衰减到 1% 要 ~2.4 s，那显然不是观感时长。
         * 解析解 x(t) = 1 − A·e^(−ζωt)·cos(ω_d t − φ)，
         * A = 1/√(1−ζ²) = 1.045、ζω = 1.918、ω_d = 6.33、φ = 0.294，
         * 首次 x = 1 出现在 cos(·) = 0，即 t = (π/2 + φ)/ω_d ≈ **0.295 s**。
         * 在那之前 x 是单调逼近 1、没有回弹；回弹全在之后（被我们截掉）。
         * 这也解释得通：官方打开动画 ζ=0.229/T=1.10 的第一个波峰在 ~0.57 s，
         * 与肉眼看到的"约 0.5 s 完成"一致。
         */
        static final int FIRST_CROSS_MS = computeFirstCrossMs();

        private static int computeFirstCrossMs() {
            for (int ms = 60; ms <= 3000; ms++) {
                float t = ms / 1000f;
                if (Math.abs(1f - raw(t)) < 0.004f) {
                    return ms + 30;      // 留一点点尾巴，收尾不突兀
                }
            }
            return 320;
        }

        private static float raw(float t) {
            float e = (float) Math.exp(-ZETA * OMEGA * t);
            return 1f - e * (float) (Math.cos(WD * t)
                    + (ZETA / Math.sqrt(1 - ZETA * ZETA)) * Math.sin(WD * t));
        }

        @Override
        public float getInterpolation(float fraction) {
            if (fraction >= 0.999f) {
                return 1f;
            }
            return Math.max(0f, Math.min(1.05f, raw(fraction * FIRST_CROSS_MS / 1000f)));
        }
    }
}
