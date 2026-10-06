package com.qianxia.hyperassistant;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试触发器：让**我自己**能触发一次真实的负一屏启动，从而不用等人点。
 *
 * ===== 为什么需要 =====
 * `adb shell input tap` → `SecurityException: requires INJECT_EVENTS`；
 * `sendevent /dev/input/event6` → `Permission denied`（SELinux enforcing，adb shell 没有 su）。
 * 所以点不了屏幕。但我们的代码**跑在负一屏进程里**，能直接拿到它的 View 树 ——
 * 于是：看到一个标记文件就 `performClick()` 那个图标，走的是**完全真实**的点击链路
 * （`AppGrid$2.onClick` → `AppRoute.route` → 官方 HVS 桥）。
 *
 * ===== 用法（adb 侧）=====
 * <pre>
 *   D=/sdcard/Android/data/com.miui.personalassistant/files/hvs
 *   adb shell touch $D/click          # 点第一个图标（128x128 那种）
 *   adb shell touch $D/click_widget   # 点第一个小部件（宽>400 那种）
 *   adb shell touch $D/seq2           # 点一个 → 600ms 后再点一个（打断式连点）
 *   adb shell touch $D/click:2        # 点第 3 个候选（0 起算）
 * </pre>
 * 标记文件看一次就删掉，所以可以反复触发。手机会一直停在负一屏上等着。
 *
 * 注意：这只是测试脚手架，不影响正常使用（没有标记文件就什么都不做）。
 */
public final class TestTrigger {

    private TestTrigger() {
    }

    private static final String DIR =
            "/sdcard/Android/data/com.miui.personalassistant/files/hvs";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile View sDecor;
    private static volatile boolean sStarted;
    private static int sLogCount;

    /** 抓到负一屏的 DecorView —— 有它才能遍历视图树找可点的图标。 */
    public static void onActivityResumed(Activity a) {
        try {
            if (a == null || !Config.ENABLE_TEST_TRIGGER) {
                return;
            }
            CharSequence pkg = a.getPackageName();
            if (pkg == null || !"com.miui.personalassistant".contentEquals(pkg)) {
                return;
            }
            // 小部件选择器之类的子页面不要抢走根视图（实测点到过 picker_entry_container，
            // 结果只是打开了选择器页面，反而把状态搞乱）。
            String cn = a.getClass().getName();
            if (cn.contains(".picker.") || cn.contains("Picker")) {
                L.i("[测试] 忽略子页面 " + a.getClass().getSimpleName());
                return;
            }
            View d = a.getWindow() == null ? null : a.getWindow().getDecorView();
            if (d != null && d != sDecor) {
                sDecor = d;
                L.i("[测试] 已抓到负一屏 DecorView: " + a.getClass().getSimpleName());
            }
        } catch (Throwable t) {
            L.w("[测试] 抓 DecorView 失败: " + t);
        }
    }

    public static synchronized void start() {
        if (sStarted || !Config.ENABLE_TEST_TRIGGER) {
            return;
        }
        sStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                L.i("[测试] 触发器已启动，标记目录 = " + DIR);
                while (true) {
                    try {
                        step();
                        Thread.sleep(600L);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable e) {
                        L.w("[测试] 轮询出错: " + e);
                    }
                }
            }
        }, "hvs-test-trigger");
        t.setDaemon(true);
        t.start();
    }

    /** 找一个代表性的内容子视图，**不打任何日志**（给 600ms 轮询用）。 */
    private static View quietProbe(View root) {
        try {
            if (root instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) root;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c.getWidth() > 0 && c.getHeight() > 0) {
                        View deeper = quietProbe(c);
                        return deeper != null ? deeper : c;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static volatile int sLastOnScreen = -1;

    /**
     * ★ 被动上报"负一屏是不是真的显示在屏幕上"，**只在状态变化时打一行**。
     *
     * 为什么需要：所有关于**观感**的验证都要求负一屏真的在屏幕上
     * （停旁边时 launcher 把窗口 alpha 设 0、内嵌表面滚到屏幕外，
     * `ViewScaleAnimInfo.isValid=false` → MIUI 走纯 alpha 降级）。
     * 而"滑到负一屏"只有用户能做、时机不可预测 —— 有了这行日志，
     * 外面的看门狗脚本就能**在用户滑过去的那一刻自动开录**，不用等他说。
     *
     * 判据用视图自己的屏幕坐标（不是 `getWindowVisibility()`，那个停旁边时仍是 VISIBLE）。
     */
    private static void logOnScreenState() {
        try {
            View d = sDecor;
            if (d == null) {
                d = rootViewOfOurProcess();
                if (d != null) {
                    sDecor = d;
                }
            }
            if (d == null) {
                return;
            }
            // ★ 必须用**内容子视图**，不能用 DecorView：DecorView 是窗口根，永远报 (0,0)；
            //   而 launcher 滚的是内嵌的那层 surface，只有子视图的坐标会跟着变
            //   （实测停旁边时图标 screen=-271,1314，滑出来后 629,1314）。
            //   注意用一个**不打日志**的遍历 —— 这个轮询每 600ms 跑一次，
            //   要是顺手把候选清单也打出来会把日志刷爆（试过，确实刷爆了）。
            View probe = quietProbe(d);
            if (probe == null) {
                probe = d;
            }
            int[] loc = new int[2];
            probe.getLocationOnScreen(loc);
            int cx = loc[0] + probe.getWidth() / 2;
            int cy = loc[1] + probe.getHeight() / 2;
            int state = (cx >= 0 && cy >= 0 && cx <= 1200 && cy <= 2608) ? 1 : 0;
            if (state != sLastOnScreen) {
                sLastOnScreen = state;
                L.i("[测试] 负一屏状态: "
                        + (state == 1 ? "★★★ 在屏幕上（观感可测）" : "停旁边（只能测机制）")
                        + "  " + desc(probe) + " screen=" + loc[0] + "," + loc[1]);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void step() {
        logOnScreenState();
        File dir = new File(DIR);
        if (!dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith("tier2")) {
                if (!f.delete()) {
                    continue;
                }
                L.i("[测试] ★ 收到触发标记: " + n + " → 直接调 Tier-2 兜底返场");
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        ReturnFlight.playWhenWindowVisible();
                    }
                });
                continue;
            }
            if (n.startsWith("onscreen") || n.startsWith("park")) {
                if (!f.delete()) {
                    continue;
                }
                final boolean on = n.startsWith("onscreen");
                L.i("[测试] ★ 收到触发标记: " + n + " → "
                        + (on ? "把负一屏**真的摆到屏幕上**" : "恢复停旁边"));
                final boolean keep = n.startsWith("onscreen_keep");
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        setOnScreen(on, keep);
                    }
                });
                continue;
            }
            if (n.startsWith("tree")) {
                if (!f.delete()) {
                    continue;
                }
                L.i("[测试] ★ 收到触发标记: " + n + " → 打印本进程所有窗口与视图树");
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        dumpWindows();
                    }
                });
                continue;
            }
            // ★ 第 50 轮新增：`seq_pkg:<包名>:<次数>:<间隔ms>` —— 同一个包**在进程内**连点 N 次。
            //   为什么需要它：真手势点第二个目标会被"应用一开就接管触摸"挡住；两次 click_pkg
            //   标记也只有第一次生效（应用开后视点不到）→ 导致"白底应用"这类样本**根本造不出来**。
            //   这个标记由触发器自己完成连点，不看前台是谁，所以必定产生打断。
            if (n.startsWith("seq_pkg:")) {
                String[] parts = n.substring("seq_pkg:".length()).split(":");
                final String want = parts.length > 0 ? parts[0].trim() : "";
                int cnt = 2;
                long gap = 600L;
                try {
                    if (parts.length > 1) {
                        cnt = Integer.parseInt(parts[1].trim());
                    }
                } catch (Throwable ignored) {
                }
                try {
                    if (parts.length > 2) {
                        gap = Long.parseLong(parts[2].trim());
                    }
                } catch (Throwable ignored) {
                }
                if (!f.delete()) {
                    continue;
                }
                L.i("[测试] ★ 收到触发标记: " + n + " → " + want + " 连点 " + cnt + " 次，间隔 " + gap + "ms");
                final int c = cnt;
                final long g = gap;
                MAIN.postDelayed(new Runnable() {
                    private int i;

                    @Override
                    public void run() {
                        if (i >= c) {
                            return;
                        }
                        i++;
                        L.i("[测试] seq_pkg 第 " + i + "/" + c + " 次 → " + want);
                        clickByPackage(want);
                        if (i < c) {
                            MAIN.postDelayed(this, g);
                        }
                    }
                }, 0L);
                continue;
            }
            if (n.startsWith("click_pkg:")) {
                final String want = n.substring("click_pkg:".length()).trim();
                if (!f.delete()) {
                    continue;
                }
                L.i("[测试] ★ 收到触发标记: " + n + " → 找包名 " + want + " 的图标");
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        clickByPackage(want);
                    }
                });
                continue;
            }
            if (!n.startsWith("click") && !n.startsWith("seq2")) {
                continue;
            }
            if (!f.delete()) {
                continue;                       // 删不掉就下一轮再说，避免重复触发
            }
            L.i("[测试] ★ 收到触发标记: " + n);
            if (n.startsWith("seq2")) {
                seq2();
            } else {
                int want = 0;
                int colon = n.indexOf(':');
                if (colon > 0) {
                    try {
                        want = Integer.parseInt(n.substring(colon + 1).trim());
                    } catch (Throwable ignored) {
                    }
                }
                click(n.contains("widget"), want, n);
            }
        }
    }

    private static void click(final boolean widget, final int index, final String tag) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    View v = pick(widget, index);
                    if (v == null) {
                        L.w("[测试] 找不到可点的" + (widget ? "小部件" : "图标") + "，放弃");
                        return;
                    }
                    L.i("[测试] 点 " + tag + " → " + desc(v) + "  clickable=" + v.isClickable());
                    if (widget) {
                        // ★ MAML 小部件的点击**不经 View.OnClickListener**
                        //（反汇编：`com.miui.maml.ActionCommand$IntentCommand.onResolveResult`
                        // / `Utils.startActivityBg`），所以 `performClick()` 返回 false、点了等于没点。
                        // 但我们在这个进程里，可以**直接合成触摸事件派发给视图树** ——
                        // 不需要 INJECT_EVENTS（那不是跨进程注入，是本进程 dispatch）。
                        sendTouch(v);
                    } else {
                        boolean ok = v.performClick();
                        L.i("[测试] performClick 返回 " + ok);
                    }
                } catch (Throwable t) {
                    L.e("[测试] 点击失败", t);
                }
            }
        });
    }

    /** 合成 DOWN → UP 派发给目标视图（给小部件用）。 */
    private static void sendTouch(final View v) {
        try {
            final long t0 = android.os.SystemClock.uptimeMillis();
            final float x = v.getWidth() / 2f;
            final float y = v.getHeight() / 2f;
            android.view.MotionEvent down = android.view.MotionEvent.obtain(
                    t0, t0, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
            boolean r1 = v.dispatchTouchEvent(down);
            down.recycle();
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        long t1 = android.os.SystemClock.uptimeMillis();
                        android.view.MotionEvent up = android.view.MotionEvent.obtain(
                                t0, t1, android.view.MotionEvent.ACTION_UP, x, y, 0);
                        boolean r2 = v.dispatchTouchEvent(up);
                        up.recycle();
                        L.i("[测试] 触摸派发完成 down=" + touchOk + " up=" + r2
                                + "（给 MaMlHostView 这类 MAML 小部件）");
                    } catch (Throwable t) {
                        L.w("[测试] UP 派发失败: " + t);
                    }
                }
            }, 60L);
            touchOk = r1;
        } catch (Throwable t) {
            L.w("[测试] 触摸派发失败: " + t);
        }
    }

    /**
     * 按**包名**点图标 —— 比"离屏幕最近的候选"可控得多（实测那个会点开 `com.miui.miservice`，
     * 运气不好就是系统反馈页之类的东西，用户在用自己的手机时很唐突）。
     *
     * 包名从视图的 `getTag()` 里挖（`AppGrid$2.onClick` 就是拿 `v.getTag()` 当 `AppItem` 用的），
     * 但 `AppItem` 是 MIUI 内部类，所以把常见的几种取法都试一遍，失败就把看到的都打出来。
     */
    private static void clickByPackage(final String want) {
        try {
            View decor = sDecor != null ? sDecor : rootViewOfOurProcess();
            if (decor == null) {
                L.w("[测试] click_pkg：还没有根视图");
                return;
            }
            List<View> cands = new ArrayList<>();
            collect(decor, false, cands);
            StringBuilder seen = new StringBuilder();
            View hit = null;
            for (View v : cands) {
                String p = pkgOf(v);
                if (p != null) {
                    seen.append(seen.length() == 0 ? "" : ",").append(p);
                    if (p.equals(want) && hit == null) {
                        hit = v;
                    }
                }
            }
            if (hit == null) {
                L.w("[测试] click_pkg：没找到 " + want + "；看到的包名 = " + seen);
                return;
            }
            L.i("[测试] click_pkg 命中 " + want + " → " + desc(hit)
                    + "  clickable=" + hit.isClickable());
            if (hit.performClick()) {
                L.i("[测试] performClick 返回 true");
            } else {
                sendTouch(hit);
            }
        } catch (Throwable t) {
            L.e("[测试] click_pkg 失败", t);
        }
    }

    /** 从图标的 tag（MIUI 的 AppItem）里尽量挖出包名。 */
    private static String pkgOf(View v) {
        Object tag = v.getTag();
        if (tag == null) {
            return null;
        }
        for (String g : new String[]{"getPackageName", "getPkgName"}) {
            Object r = call0(tag, g);
            if (r instanceof String && ((String) r).contains(".")) {
                return (String) r;
            }
        }
        Object cn = call0(tag, "getComponentName");
        if (cn != null) {
            Object r = call0(cn, "getPackageName");
            if (r instanceof String) {
                return (String) r;
            }
        }
        Object it = call0(tag, "getIntent");
        if (it instanceof android.content.Intent) {
            android.content.Intent i = (android.content.Intent) it;
            if (i.getComponent() != null) {
                return i.getComponent().getPackageName();
            }
            if (i.getPackage() != null) {
                return i.getPackage();
            }
        }
        for (String f : new String[]{"mPackageName", "packageName", "mPkg"}) {
            Object r = fieldOf(tag, f);
            if (r instanceof String && ((String) r).contains(".")) {
                return (String) r;
            }
        }
        return null;
    }

    private static Object call0(Object obj, String name) {
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object fieldOf(Object obj, String name) {
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

    private static volatile boolean touchOk;

    /** 点一个，等 600ms 再点下一个 —— 复现"启动过程中被打断"。 */
    private static void seq2() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                View a = pick(false, 0);
                View b = pick(false, 1);
                if (a == null || b == null) {
                    L.w("[测试] seq2：候选不够");
                    return;
                }
                L.i("[测试] seq2 第一步 → " + desc(a));
                a.performClick();
                MAIN.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        L.i("[测试] seq2 第二步（打断）→ " + desc(b));
                        b.performClick();
                    }
                }, 600L);
            }
        });
    }

    /** 在负一屏视图树里挑一个可点的源视图。 */
    private static View pick(boolean widget, int index) {
        View decor = sDecor;
        if (decor == null) {
            decor = rootViewOfOurProcess();
            if (decor != null) {
                sDecor = decor;
                L.i("[测试] 由 WindowManagerGlobal 拿到根视图: " + decor.getClass().getName());
            }
        }
        if (decor == null) {
            return null;
        }
        List<View> cands = new ArrayList<>();
        collect(decor, widget, cands);
        // ★ 必须只用**真的在屏幕上**的：负一屏是多页容器，很多卡片布局在屏幕外
        //   （实测点到过 pos=(-843,...) 的，performClick 返回 false，什么都没发生）。
        List<View> onScreen = new ArrayList<>();
        int[] loc = new int[2];
        int bestArea = -1;
        View best = null;
        for (View v : cands) {
            try {
                v.getLocationOnScreen(loc);
                int cx = loc[0] + v.getWidth() / 2;
                int cy = loc[1] + v.getHeight() / 2;
                if (cx < 0 || cy < 0 || cx > 1200 || cy > 2608) {
                    continue;
                }
                onScreen.add(v);
                int a = area(v);
                boolean better = widget ? a > bestArea : (best == null || a < bestArea);
                if (better) {
                    bestArea = a;
                    best = v;
                }
            } catch (Throwable ignored) {
            }
        }
        List<View> onScreen2 = new ArrayList<>();
        for (View v : onScreen) {
            // 图标模式只留 ImageView：负一屏的图标是 ImageView + AppGrid 的 OnClickListener，
            // 而 MaMlHostView 之类的内部视图 performClick 返回 false（实测），点了等于没点。
            if (widget || v instanceof ImageView) {
                onScreen2.add(v);
            }
        }
        if (!onScreen2.isEmpty()) {
            onScreen = onScreen2;
        }
        if (sLogCount++ < 30) {
            StringBuilder sb = new StringBuilder("[测试] 候选(" + (widget ? "部件" : "图标")
                    + ") 屏上 " + onScreen.size() + "/" + cands.size() + ": ");
            for (int i = 0; i < onScreen.size() && i < 8; i++) {
                sb.append(i).append('=').append(desc(onScreen.get(i))).append("  ");
            }
            L.i(sb.toString());
        }
        if (onScreen.isEmpty()) {            // 兜底：负一屏被"停在旁边"时（用户在桌面），它的窗口内容会整体被平移到屏幕外
            // （实测 screen x 全是 -1125…-219），此时没有"屏上"候选。
            // 仍然挑一个**离屏幕最近**的可点视图 —— 动画观感会是错的，但调用链是真的，
            // 用来验证机制足够。
            View nearest = null;
            int bestDist = Integer.MAX_VALUE;
            int[] l2 = new int[2];
            // 按"离屏幕的距离"排序，这样才能用 index 选到**不同**的图标
            // （之前 index 在兜底路径里被忽略，结果一直点同一个 app）。
            java.util.List<View> ranked = new ArrayList<>();
            for (View v : cands) {
                try {
                    v.getLocationOnScreen(l2);
                    int dx = l2[0] < 0 ? -l2[0] : (l2[0] > 1200 ? l2[0] - 1200 : 0);
                    int dy = l2[1] < 0 ? -l2[1] : (l2[1] > 2608 ? l2[1] - 2608 : 0);
                    int d = dx + dy;
                    // 图标模式下强烈偏好 ImageView：负一屏的图标是 ImageView + AppGrid 的
                    // OnClickListener（performClick 能真的启动）；而 MaMlHostView 小部件
                    // 的点击在 MAML 内部走 IntentCommand，performClick 返回 false（实测）。
                    if (!widget && !(v instanceof ImageView)) {
                        d += 5000;
                    }
                    if (d < bestDist) {
                        bestDist = d;
                        nearest = v;
                    }
                    ranked.add(v);
                } catch (Throwable ignored) {
                }
            }
            final int[] order = new int[0];
            java.util.Collections.sort(ranked, new java.util.Comparator<View>() {
                @Override
                public int compare(View a, View b) {
                    return score(a) - score(b);
                }
            });
            if (!ranked.isEmpty() && index < ranked.size()) {
                nearest = ranked.get(index);
                L.w("[测试] 没有屏上候选，按距离排序取第 " + index + " 个 dist=" + score(nearest)
                        + " " + desc(nearest) + "（观感会不对，只验机制）");
            } else if (nearest != null) {
                L.w("[测试] 没有屏上候选（负一屏被停在旁边），退而用最近的一个 dist="
                        + bestDist + " " + desc(nearest) + "（观感会不对，只验机制）");
            }
            return nearest;
        }
        return index < onScreen.size() ? onScreen.get(index) : best;
    }

    private static int score(View v) {
        try {
            int[] l = new int[2];
            v.getLocationOnScreen(l);
            int dx = l[0] < 0 ? -l[0] : (l[0] > 1200 ? l[0] - 1200 : 0);
            int dy = l[1] < 0 ? -l[1] : (l[1] > 2608 ? l[1] - 2608 : 0);
            int d = dx + dy;
            if (!(v instanceof ImageView)) {
                d += 5000;
            }
            return d;
        } catch (Throwable t) {
            return Integer.MAX_VALUE / 2;
        }
    }

    private static void collect(View v, boolean widget, List<View> out) {
        if (v == null) {
            return;
        }
        try {
            int w = v.getWidth();
            int h = v.getHeight();
            boolean shown = v.isShown();
            if (shown && v.isAttachedToWindow()) {
                if (v.isClickable()) {
                    int[] l = new int[2];
                    v.getLocationOnScreen(l);
                    if (sTreeLog < 90) {
                        sTreeLog++;
                        L.i("[测试] 可点: " + v.getClass().getSimpleName() + " " + w + "x" + h
                                + " screen=" + l[0] + "," + l[1]
                                + " clickable=true" + (v instanceof ImageView ? " IMG" : ""));
                    }
                }
                if (v.isClickable()) {
                    if (widget) {
                        if (w >= 400 && h >= 200) {
                            out.add(v);
                        }
                    } else if (w >= 80 && h >= 80 && w <= 320 && h <= 320) {
                        out.add(v);
                    }
                }
            }
            if (v instanceof ViewGroup && out.size() < 40) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    collect(g.getChildAt(i), widget, out);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static int sTreeLog;

    private static String desc(View v) {
        return v.getClass().getSimpleName() + "[" + v.getWidth() + "x" + v.getHeight()
                + "@" + v.getLeft() + "," + v.getTop() + "]";
    }

    private static volatile boolean sOnScreen;
    private static volatile boolean sOnScreenKeep;
    private static volatile boolean sOnScreenLoop;
    private static volatile View sShifted;
    private static volatile float sShiftOrigTr = Float.NaN;
    private static volatile float sAlphaOrig = Float.NaN;

    /**
     * 把负一屏**真的摆到屏幕上**（测试脚手架，只有标记文件能开）。
     *
     * ===== 为什么这是关键 =====
     * 负一屏是 `AssistantOverlayService` 建的覆盖窗口，停旁边时：
     *   · 窗口 `LayoutParams.alpha = 0.0`（`pkg=com.miui.home`，title 是 LauncherOverlayWindow）
     *   · 内容 `AssistContentView` 在 `SlidingPaneLayout` 里被摆到 `left=-1200`（整整一屏外）
     * 于是源视图 `getLocationOnScreen()` 是负数 → MIUI 判定 `ViewScaleAnimInfo(isValid=false)`
     * → 整个 HVS 缩放动画降级成**纯 alpha**。
     * 也就是说：不把内容摆回屏幕内，量到的永远不是用户看到的那套动画。
     *
     * 做法只动两个**真实存在**的量：窗口 alpha、以及把那一层平移回屏幕内 ——
     * 平移量正好等于它自己的负坐标，所以最终几何**和用户滑到负一屏时一模一样**
     * （内容 LT=0,0，坐标不再是伪造的）。
     */
    private static void setOnScreen(boolean on) {
        setOnScreen(on, false);
    }

    private static void setOnScreen(boolean on, boolean keep) {
        sOnScreen = on;
        sOnScreenKeep = on && keep;
        apply(on, false);
        if (sOnScreenKeep && !sOnScreenLoop) {
            sOnScreenLoop = true;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (sOnScreenLoop) {
                        try {
                            Thread.sleep(400L);
                        } catch (InterruptedException e) {
                            return;
                        }
                        if (sOnScreen && sOnScreenKeep) {
                            MAIN.post(new Runnable() {
                                @Override
                                public void run() {
                                    apply(true, true);
                                }
                            });
                        }
                    }
                }
            }, "hvs-onscreen");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void apply(boolean on, boolean quiet) {
        try {
            List<?> roots = windowRoots();
            if (roots == null || roots.isEmpty()) {
                L.w("[摆屏] 还没有根视图");
                return;
            }
            for (Object o : roots) {
                if (!(o instanceof View)) {
                    continue;
                }
                View root = (View) o;
                android.view.WindowManager.LayoutParams p = null;
                try {
                    p = (android.view.WindowManager.LayoutParams) root.getLayoutParams();
                } catch (Throwable t) {
                    L.w("[摆屏] 取 LayoutParams 失败: " + t);
                }
                if (p != null) {
                    // ★ 只改我们自己动过的量：先记下原值，park 时原样还回去
                    if (on && Float.isNaN(sAlphaOrig)) {
                        sAlphaOrig = p.alpha;
                    }
                    float want = on ? 1f : (Float.isNaN(sAlphaOrig) ? p.alpha : sAlphaOrig);
                    if (p.alpha != want) {
                        try {
                            p.alpha = want;
                            android.view.WindowManager wm = (android.view.WindowManager)
                                    root.getContext().getSystemService(
                                            android.content.Context.WINDOW_SERVICE);
                            wm.updateViewLayout(root, p);
                            L.i("[摆屏] 窗口 alpha → " + L.f(want));
                        } catch (Throwable t) {
                            L.w("[摆屏] 改窗口 alpha 失败: " + t);
                        }
                    }
                }
                View off = on ? findOffscreen(root, 0) : (sShifted != null ? sShifted : null);
                if (off == null) {
                    if (on && !quiet) {
                        L.i("[摆屏] 没找到屏幕外的那一层（可能本来就在屏幕上）");
                    }
                    continue;
                }
                if (on) {
                    if (sShifted == null) {
                        sShifted = off;
                        sShiftOrigTr = off.getTranslationX();
                    }
                    int[] loc = new int[2];
                    off.getLocationOnScreen(loc);
                    float shift = off.getTranslationX() - loc[0];
                    if (Math.abs(off.getTranslationX() - shift) > 1f) {
                        off.setTranslationX(shift);
                        L.i("[摆屏] 把 " + off.getClass().getSimpleName() + " 平移 "
                                + L.f(shift) + " → 屏幕坐标 x 由 " + loc[0] + " 变成 "
                                + (loc[0] + (int) shift));
                        off.invalidate();
                    }
                } else {
                    float back = Float.isNaN(sShiftOrigTr) ? 0f : sShiftOrigTr;
                    if (off.getTranslationX() != back) {
                        off.setTranslationX(back);
                        L.i("[摆屏] 已把 " + off.getClass().getSimpleName()
                                + " 的平移还原成 " + L.f(back));
                        off.invalidate();
                    }
                    sShifted = null;
                    sShiftOrigTr = Float.NaN;
                    sAlphaOrig = Float.NaN;
                }
            }
        } catch (Throwable t) {
            L.e("[摆屏] 失败", t);
        }
    }

    /** 视图树里**最靠上**的那一层屏幕外节点（负一屏就是 `AssistContentView`，left=-1200）。 */
    private static View findOffscreen(View v, int depth) {
        try {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            if (loc[0] < 0 || loc[1] < 0) {
                return v;
            }
            if (depth >= 8 || !(v instanceof ViewGroup)) {
                return null;
            }
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findOffscreen(g.getChildAt(i), depth + 1);
                if (r != null) {
                    return r;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 打印本进程**所有窗口**以及每个窗口的视图树（带几何量）。
     *
     * 为什么必须做：负一屏不是 Activity，而是 `AssistantOverlayService` 建的一个
     * **覆盖窗口**（`LauncherOverlayWindow:com.miui.personalassistant`，frame=[0,0][1200,2608]，
     * launcher 把它停在屏幕左边：窗口 alpha=0.0、内嵌内容被挪到屏幕外，x 为负）。
     * 停旁边时 MIUI 走的是 `isValid=false` 的纯 alpha 降级路径 —— 也就是说**之前所有
     * "观感"结论都是在降级路径上量的**。要看真实动画，就得先知道是谁把内容挪出去的。
     */
    private static void dumpWindows() {
        try {
            List<?> roots = windowRoots();
            L.i("[窗口] ---- 本进程根视图数 = " + (roots == null ? -1 : roots.size()) + " ----");
            if (roots == null) {
                return;
            }
            int i = 0;
            for (Object o : roots) {
                if (!(o instanceof View)) {
                    continue;
                }
                View v = (View) o;
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                L.i("[窗口] #" + i + " " + v.getClass().getName()
                        + "  " + v.getWidth() + "x" + v.getHeight()
                        + "  screen=" + loc[0] + "," + loc[1]
                        + "  winVis=" + v.getWindowVisibility()
                        + "  attached=" + v.isAttachedToWindow());
                try {
                    android.view.WindowManager.LayoutParams p =
                            (android.view.WindowManager.LayoutParams) v.getLayoutParams();
                    L.i("[窗口] #" + i + " lp: alpha=" + L.f(p.alpha)
                            + " type=" + p.type
                            + " flags=0x" + Integer.toHexString(p.flags)
                            + " xy=" + p.x + "," + p.y
                            + " wh=" + p.width + "x" + p.height
                            + " fmt=" + p.format
                            + " pkg=" + p.packageName
                            + " title=" + p.getTitle());
                } catch (Throwable t) {
                    L.w("[窗口] #" + i + " 取 LayoutParams 失败: " + t);
                }
                sNodeBudget = 400;
                dumpNode(v, 1, 14);
                i++;
            }
            L.i("[窗口] ---- 结束 ----");
        } catch (Throwable t) {
            L.e("[窗口] 打印失败", t);
        }
    }

    private static int sNodeBudget;

    private static void dumpNode(View v, int depth, int maxDepth) {
        if (depth > maxDepth || sNodeBudget <= 0) {
            return;
        }
        sNodeBudget--;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            b.append("  ");
        }
        b.append(depth).append(' ').append(v.getClass().getSimpleName());
        b.append(" id=").append(idName(v));
        b.append(" LT=").append(v.getLeft()).append(',').append(v.getTop());
        b.append(" wh=").append(v.getWidth()).append('x').append(v.getHeight());
        b.append(" scr=").append(loc[0]).append(',').append(loc[1]);
        b.append(" tr=").append(L.f(v.getTranslationX())).append(',').append(L.f(v.getTranslationY()));
        b.append(" sc=").append(v.getScrollX()).append(',').append(v.getScrollY());
        b.append(" a=").append(L.f(v.getAlpha()));
        b.append(" v=").append(v.getVisibility());
        if (v.isClickable()) {
            b.append(" clickable");
        }
        L.i("[视图树] " + b);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dumpNode(g.getChildAt(i), depth + 1, maxDepth);
            }
        }
    }

    private static String idName(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) {
                return "NO_ID";
            }
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(v.getId());
        }
    }

    /** 本进程所有窗口的根视图（这代 ROM 上 `getRootViews()` 没了，只剩 `getWindowViews()`）。 */
    private static List<?> windowRoots() {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object inst = wmg.getMethod("getInstance").invoke(null);
            for (Method cand : wmg.getDeclaredMethods()) {
                if (cand.getName().equals("getWindowViews") && cand.getParameterCount() == 0) {
                    cand.setAccessible(true);
                    Object r = cand.invoke(inst);
                    if (r instanceof List) {
                        return (List<?>) r;
                    }
                }
            }
        } catch (Throwable t) {
            L.w("[窗口] windowRoots 失败: " + t);
        }
        return null;
    }

    /**
     * 兜底拿根视图：负一屏其实是 WindowManager 里的**覆盖窗口**，不一定有 Activity，
     * 所以 `Activity.onResume` 那条路可能一直抓不到。`WindowManagerGlobal.getRootViews()`
     * 是本进程所有窗口的根视图，按包名挑出我们自己的那个。
     */
    private static View rootViewOfOurProcess() {
        // 这代 ROM 上 `getRootViews()` 已经没有了：只剩 `getWindowViews()`（无参，返回
        // ArrayList<View>）和 `getRootViews(IBinder)` / `getRootView(String)`。
        for (String name : new String[]{"getWindowViews", "getRootViews"}) {
            try {
                Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
                Object inst = wmg.getMethod("getInstance").invoke(null);
                Method m = null;
                for (Method cand : wmg.getDeclaredMethods()) {
                    if (cand.getName().equals(name) && cand.getParameterCount() == 0) {
                        m = cand;
                        break;
                    }
                }
                if (m == null) {
                    continue;
                }
                m.setAccessible(true);
                Object roots = m.invoke(inst);
                View best = pickBest(roots, name);
                if (best != null) {
                    return best;
                }
            } catch (Throwable t) {
                L.w("[测试] " + name + " 兜底失败: " + t);
            }
        }
        return null;
    }

    private static View pickBest(Object roots, String via) {
        if (!(roots instanceof List)) {
            return null;
        }
        View best = null;
        for (Object o : (List<?>) roots) {
            if (!(o instanceof View)) {
                continue;
            }
            View v = (View) o;
            android.content.Context c = v.getContext();
            String pkg = c == null ? null : String.valueOf(c.getPackageName());
            L.i("[测试] " + via + " 候选: " + v.getClass().getSimpleName()
                    + " pkg=" + pkg + " vis=" + v.getWindowVisibility()
                    + " " + v.getWidth() + "x" + v.getHeight());
            if (pkg != null && pkg.contains("personalassistant")
                    && (best == null || area(v) > area(best))) {
                best = v;
            }        }
        return best;
    }

    private static int area(View v) {
        return Math.max(0, v.getWidth()) * Math.max(0, v.getHeight());
    }

    /**
     * 由 `ViewRootImpl.setView` 直接抓窗口根视图 —— 比反射 WindowManagerGlobal 稳，
     * 因为 window 一建好就会调到这里。
     */
    public static void onWindowAdded(View view, String title) {
        try {
            if (view == null || !Config.ENABLE_TEST_TRIGGER) {
                return;
            }
            android.content.Context c = view.getContext();
            String pkg = c == null ? null : String.valueOf(c.getPackageName());
            L.i("[测试] 窗口建立: title=" + title + " pkg=" + pkg
                    + " " + view.getClass().getSimpleName());
            if (pkg != null && pkg.contains("personalassistant")) {
                sDecor = view;
            }
        } catch (Throwable ignored) {
        }
    }
}
