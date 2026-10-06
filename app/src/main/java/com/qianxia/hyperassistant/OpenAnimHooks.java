package com.qianxia.hyperassistant;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

/**
 * 把"小部件点击 → 打开应用"这条链路上的启动动画接上。
 *
 * 链路（逆向确认）：
 * <pre>
 *   用户点负一屏服务卡片
 *     → AppGrid$2.onClick(View)                  ← 记下点击源视图（动画起点）
 *       → AppRoute.route(ctx, AppItem)           ← 全进程唯一的调用点
 *         → AppRoute$GeneralRoute.route(...)     ← 这里是裸的 Context.startActivity(Intent)，无 options
 *           → Context.startActivity(Intent)      ← 我们在这里补上 ActivityOptions
 * </pre>
 *
 * 两种模式（{@link Config#OPEN_ANIM_MODE}）：
 *   "probe" —— 只记录工厂返回值与 capability，**不改行为**（默认）
 *   "apply" —— 真正替换启动调用，加上小米自己的 ActivityOptions
 */
public final class OpenAnimHooks {

    private OpenAnimHooks() {
    }

    private static volatile ClassLoader sCl;

    /** 最近一次被接受的启动：源视图 + 目标，供 HyperViewScale 看护用（关闭动画的前提）。 */
    private static volatile View sLastSrc;
    private static volatile String sLastTarget;

    public static void install(ClassLoader cl) {
        sCl = cl;
        WidgetOpenAnim.setClassLoader(cl);
        L.i("========== 打开动画安装 ==========");
        L.i("模式 = " + Config.OPEN_ANIM_MODE
                + "   记源窗口 = " + Config.SOURCE_VIEW_ARM_MS + "ms");

        WidgetOpenAnim.logCapabilities(cl);

        // 1) 记下点击源。
        //    ★ 主入口用框架的 View.performClick() —— 一次点击必然走到这里，
        //      不依赖具体是哪个监听器（上次实测发现 AppGrid$2.onClick 覆盖面不够，
        //      导致 armed 一直是 false）。
        Class<?> viewCls = HookKit.findClass(cl, "android.view.View");
        if (viewCls != null) {
            HookKit.hookAll(viewCls, "performClick", new HookKit.Cb("View.performClick [记源]") {
                @Override
                protected void onAfter(MethodHookParam p) {
                    Object self = p.thisObject;
                    if (self instanceof View) {
                        WidgetOpenAnim.arm((View) self, "View.performClick");
                    }
                }
            });
        } else {
            L.w("[打开动画] 找不到 android.view.View，无法用 performClick 记源");
        }

        // 2) 更精确的信号：服务卡片网格的点击监听器
        HookKit.hook(cl, Targets.APP_GRID_LISTENER, Targets.M.ON_CLICK, View.class,
                new HookKit.Cb("AppGrid$2.onClick(View) [记源]") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        WidgetOpenAnim.arm(HookKit.firstView(p), "AppGrid$2.onClick");
                    }
                });
        HookKit.hookAll(cl, Targets.APP_GRID_LISTENER, Targets.M.ON_CLICK,
                new HookKit.Cb("AppGrid$2.onClick(*) [记源 兜底]") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        View v = HookKit.firstView(p);
                        if (v != null) {
                            WidgetOpenAnim.arm(v, "AppGrid$2.onClick(重载)");
                        }
                    }
                });

        // 2.5) ★ MAML 小组件的**兜底记源**（2026-09-26 实测新增）。
        //
        // 事实：负一屏上那个 204x204 的小部件，用**真手指**点它（`input tap 177 546`）：
        //     startActivity = 1 次，HVS 动画 = 0 次  ← 完全没有动画
        // 完整日志里只有一行 `[打开动画] 拦截到 startActivity ... armed=false`：
        // 它是走 MAML 引擎的，**不调 `View.performClick`**，也不走 `AppGrid$2.onClick`
        // → 两条记源钩子都没命中 → armed=false → 我们直接放行 → 起应用时没有
        // `view_scale_open` 那一套 options。这就是"小组件的动画必须全部支持"里漏掉的一类。
        //
        // ★ 触摸落在哪（先只钩宿主，一次没触发，于是开了临时诊断，真手指点 177,546）：
        //     action=DOWN  com.miui.maml.component.MamlView$InnerView          204x204
        //     action=DOWN  com.miui.maml.elements.LayerScreenElement$LayerView 204x204
        //     action=UP    com.miui.maml.elements.LayerScreenElement$LayerView 204x204
        //     → 紧接着才是 `拦截到 startActivity`
        //   MAML 引擎把触摸**直接派发进元素树**，根本不经过宿主 `MaMlHostView` 的
        //   dispatchTouchEvent —— 所以要钩的是元素树这两层。
        for (final String cls : Targets.MAML_TOUCH_VIEWS) {
            HookKit.hookAll(cl, cls, "dispatchTouchEvent",
                    new HookKit.Cb(cls.substring(cls.lastIndexOf('.') + 1) + ".touch [记源]") {
                        @Override
                        protected void onBefore(MethodHookParam p) {
                            try {
                                if (!(p.thisObject instanceof View)) {
                                    return;
                                }
                                Object a0 = p.args != null && p.args.length > 0 ? p.args[0] : null;
                                if (!(a0 instanceof android.view.MotionEvent)) {
                                    return;
                                }
                                int act = ((android.view.MotionEvent) a0).getActionMasked();
                                // DOWN 也记（有的 MAML 点击在 DOWN 就处理），UP 作为更准的信号
                                if (act == android.view.MotionEvent.ACTION_UP
                                        || act == android.view.MotionEvent.ACTION_DOWN) {
                                    WidgetOpenAnim.arm((View) p.thisObject,
                                            cls.substring(cls.lastIndexOf('.') + 1) + ".touch");
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        }

        // 2.6) ★ MAML 记源：**只能挂在框架的 `View.dispatchTouchEvent` 上**。
        //
        // 为什么：按类名去 hook `com.miui.maml.elements.LayerScreenElement$LayerView` /
        // `com.miui.maml.component.MamlView$InnerView` **没成功** —— 安装日志里没有它们的
        // `hookAll OK`（说明这两个类不在我们拿到的那个 ClassLoader 里，`findClass` 拿不到）。
        // 但挂在框架 `View.dispatchTouchEvent` 上是有效的（诊断那次就打印出来了，
        // 因为 LayerView/InnerView 都是 View 的子类、方法声明在框架类上）。
        //
        // 代价：这是热路径。所以过滤做得极便宜 —— 只比类名前缀，命中才做后续动作；
        // 日志另外受 `LOG_TOUCH_TREE` 限制。
        final Class<?> diagView = HookKit.findClass(cl, "android.view.View");
        if (diagView != null) {
            HookKit.hookAll(diagView, "dispatchTouchEvent",
                    new HookKit.Cb("View.touch [MAML 记源]") {
                        @Override
                        protected void onBefore(MethodHookParam p) {
                            try {
                                if (!(p.thisObject instanceof View)) {
                                    return;
                                }
                                Object a0 = p.args != null && p.args.length > 0 ? p.args[0] : null;
                                if (!(a0 instanceof android.view.MotionEvent)) {
                                    return;
                                }
                                View v = (View) p.thisObject;
                                String cn = v.getClass().getName();
                                if (!(cn.startsWith("com.miui.maml.")
                                        || cn.startsWith("com.miui.personalassistant.core.view.")
                                        || cn.endsWith("LayerView")
                                        || cn.endsWith("InnerView"))) {
                                    return;
                                }
                                android.view.MotionEvent e = (android.view.MotionEvent) a0;
                                int act = e.getActionMasked();
                                // 记源：DOWN 与 UP 都记（MAML 有点击在 DOWN 处理的）
                                if (act == android.view.MotionEvent.ACTION_UP
                                        || act == android.view.MotionEvent.ACTION_DOWN) {
                                    WidgetOpenAnim.arm(v, "touch:" + cn);
                                }
                                if (Config.LOG_TOUCH_TREE
                                        && sTouchDiagLog++ < Config.LOG_TOUCH_TREE_MAX) {
                                    L.i("[触摸诊断] action=" + act
                                            + " raw=(" + (int) e.getRawX() + ","
                                            + (int) e.getRawY() + ") " + cn
                                            + " " + v.getWidth() + "x" + v.getHeight());
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        }

        // 3) 在真正的启动点补 options
        installLaunchHook(cl);
        installRouteProbes(cl);

        L.i("========== 打开动画安装完毕 ==========");
    }

    /** 真正的启动点：ContextImpl.startActivity —— 所有 Activity 启动最终都走这里。 */
    private static void installLaunchHook(ClassLoader cl) {
        Class<?> impl = HookKit.findClass(cl, Targets.CONTEXT_IMPL);
        if (impl == null) {
            L.w("[打开动画] 找不到 " + Targets.CONTEXT_IMPL + "，无法在启动点补 options");
            return;
        }
        HookKit.hookAll(impl, Targets.M.START_ACTIVITY,
                new HookKit.Cb("ContextImpl.startActivity [打开动画]") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        handleLaunch(p);
                    }
                });
    }

    private static int sTouchDiagLog;

    private static void handleLaunch(de.robv.android.xposed.XC_MethodHook.MethodHookParam p) {
        if (!Config.ENABLE_OPEN_ANIM) {
            return;
        }
        Intent intent = null;
        for (Object a : p.args) {
            if (a instanceof Intent) {
                intent = (Intent) a;
                break;
            }
        }
        if (intent == null) {
            return;
        }
        boolean armed = WidgetOpenAnim.isArmed();
        if (Config.LOG_OPEN_ANIM) {
            L.i("[打开动画] 拦截到 startActivity: " + WidgetOpenAnim.describe(
                    (Context) p.thisObject, intent));
        }
        if (!armed) {
            return;     // 没记下点击源就不碰，避免影响无关启动
        }

        final Context ctx = (Context) p.thisObject;
        final Intent it = intent;
        final ClassLoader cl = sCl;
        // 一次性消费：一次点击只影响紧接着的那一次启动
        final View rawSrc = WidgetOpenAnim.consumeArmed();
        if (cl == null || rawSrc == null) {
            return;
        }
        // 动画起点：默认往上提一层到卡片容器（修「图标白边」）
        final View src = WidgetOpenAnim.pickSourceView(rawSrc);
        sLastSrc = src;
        sLastTarget = (it.getComponent() == null
                ? String.valueOf(it.getPackage()) : it.getComponent().getClassName());

        if (Config.LOG_OPEN_ANIM) {
            L.i("[打开动画] armed=true 目标=" + (it.getComponent() == null
                    ? it.getPackage() : it.getComponent().getClassName()));
        }

        // ================= 默认路线：自己画浮层 =================
        // 注意：这条路线**完全不碰启动调用** —— 下面 return 之后原 startActivity 照常执行。
        // 所以我们只是在旁边加一层视觉，应用永远能正常打开。
        if (Config.OPEN_ANIM_MODE.equalsIgnoreCase("overlay")) {
            try {
                OpenAnimOverlay.play(ctx, src);
            } catch (Throwable t) {
                L.e("[打开动画] 浮层播放失败（不影响应用打开）", t);
            }
            return;
        }

        Bundle base = WidgetOpenAnim.rawOptionsBundle(cl, src);
        if (base == null) {
            L.w("[打开动画] 工厂没产出 options，放行原调用");
            return;
        }
        if (Config.OPEN_ANIM_MODE.equalsIgnoreCase("probe")) {
            L.i("[打开动画/probe] 会的做法: startActivity(intent, opts)"
                    + "  options=" + base.keySet());
            return;     // 探测模式：不改行为
        }

        // apply 模式：**逐级剥离**，自动找出哪一组 key 能过权限检查。
        // 每次 startActivity 若抛 SecurityException 就是没启动成功，所以可以安全地继续下一级。
        String[] order = WidgetOpenAnim.DROP_ORDER;
        for (int level = 0; level <= order.length; level++) {
            Bundle b = new Bundle(base);
            for (int i = 0; i < level; i++) {
                b.remove(order[i]);
            }
            if (tryLaunch(ctx, it, b, "剥离级别 " + level + "/" + order.length
                    + " 剩余key=" + b.keySet())) {
                p.setResult(null);
                L.i("[打开动画] >>> 结论：剥离到第 " + level + " 级才被允许。"
                        + (level == 0 ? "（不需要剥离）" : "被剥离的是 " + order[level - 1]));
                return;
            }
        }

        // 最后退到框架原生（注意：这条在 Android 17 上会被接受但**不渲染动画**）
        Bundle fw = WidgetOpenAnim.frameworkScaleUpBundle(src);
        if (fw != null && tryLaunch(ctx, it, fw, "框架 makeScaleUpAnimation（预期无可见效果）")) {
            p.setResult(null);
            return;
        }
        L.w("[打开动画] 全部路线失败，放行原调用（应用仍会正常打开，只是没有动画）");
    }

    /**
     * 尝试用带 options 的方式启动。
     *
     * 注意：`ctx.startActivity` 会**重新进入**我们自己的 hook —— 此时记源状态已被消费，
     * 所以内层会直接放行原调用，不会无限递归。
     */
    private static boolean tryLaunch(Context ctx, Intent it, Bundle opts, String tag) {
        try {
            // 关键：把 options 交给小米自己的启动桥做一次富化。
            // 它会写 intent 的 "view_scale_open" extra（system_server 侧关闭过渡的触发条件）
            // 并把带回 RemoteTransition 的 bundle 还给我们。
            Bundle enriched = HvsDiagnostics.bridgeToMiui(ctx, opts, it);
            ctx.startActivity(it, enriched == null ? opts : enriched);
            L.i("[打开动画] ✔ 启动被接受 (" + tag + ")");
            // 启动被接受 → 开始看护源视图。关闭动画要求它一直 attached
            // （HyperViewScaleRemoteTransition.startAnimation 里要 getBoundsOnScreen +
            //  RenderNode.addPositionUpdateListener），被回收就只能 alpha 淡出。
            HvsDiagnostics.watchSource(sLastSrc, sLastTarget);
            return true;
        } catch (Throwable t) {
            // 不再截断 —— 之前截到 120 字符，把 "with remoteTransition"
            // 这个关键后缀弄丢了，导致分不清是不是同一个原因
            String msg = String.valueOf(t.getMessage());
            boolean rt = msg.contains("remoteTransition");
            L.w("[打开动画] ✘ 被拒 (" + tag + ") "
                    + t.getClass().getName()
                    + "  remoteTransition=" + rt
                    + "\n        " + msg);
            return false;
        }
    }

    /** 记录 AppRoute 这条链路真的被走到过（确认覆盖面）。 */
    private static void installRouteProbes(ClassLoader cl) {
        HookKit.hook(cl, Targets.APP_ROUTE_GENERAL, Targets.M.ROUTE,
                Context.class, appItemClass(cl),
                new HookKit.Cb("GeneralRoute.route [打开动画]") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        if (Config.LOG_OPEN_ANIM) {
                            L.i("[打开动画] GeneralRoute.route 被调用 armed="
                                    + WidgetOpenAnim.isArmed());
                        }
                    }
                });
        HookKit.hook(cl, Targets.APP_ROUTE_NATIVE, Targets.M.ROUTE,
                Context.class, appItemClass(cl),
                new HookKit.Cb("NativeRoute.route [打开动画]") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        if (Config.LOG_OPEN_ANIM) {
                            L.i("[打开动画] NativeRoute.route 被调用 armed="
                                    + WidgetOpenAnim.isArmed());
                        }
                    }
                });
    }

    private static Class<?> appItemClass(ClassLoader cl) {
        Class<?> c = HookKit.findClass(cl,
                "com.miui.miuiwidget.servicedelivery.model.AppItem");
        return c == null ? Object.class : c;
    }
}
