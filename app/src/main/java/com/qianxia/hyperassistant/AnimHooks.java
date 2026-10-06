package com.qianxia.hyperassistant;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.view.View;

/**
 * 第二阶段：真正接管动画。
 *
 * ===== 设计要点：可并行打断 =====
 *
 * 1. **我们自己不持有任何时间轴。**
 *    并行属性（scale / alpha / blur）全部是"系统活进度"的纯函数，见 {@link ParallelAnim}。
 *    进度由 {@link ProgressSampler} 每帧从系统实时读取。
 *    → 用户中途打断时，系统自己完成速度衔接，我们下一帧读到的就是衔接后的值，
 *      **打断对我们来说是免费的**，不需要任何续接逻辑。
 *
 * 2. **所有属性同一个 p。**
 *    并行同步是结构保证的，不是靠对齐多个动画的时间轴凑出来的 ——
 *    所以"并行"和"打断"是同一件事的两面，不会互相打架。
 *
 * 3. **乘法叠加，不抢所有权。**
 *    系统自己可能也在动 scale/alpha。我们捕获静止时的基线，只在其上叠乘系数，
 *    这样既不破坏系统动画，也不会在打断时跳变。
 *
 * 4. **曲线默认不动。**
 *    逆向结论是系统的曲线走 miuix Folme 的 EaseStyle（见 {@link XiaomiCurves}），
 *    `styleId` 是运行时算的、抄不到，所以默认 `system` = 不覆盖 = 就是小米自己的曲线。
 *    要换只从米家自己的表里换，并且优先用 {@link XiaomiCurves#getSystemStyle} 拿原厂对象。
 */
public final class AnimHooks {

    private AnimHooks() {
    }

    /** 宿主 ClassLoader，供静态调用使用。 */
    private static volatile ClassLoader sCl;
    private static volatile boolean sContentLogged;

    public static void install(ClassLoader cl) {
        sCl = cl;
        L.i("========== 动画模式安装 ==========");
        L.i("拖拽曲线=" + Config.DRAG_CURVE + "(" + Config.DRAG_STRENGTH + ")"
                + "  提交曲线=" + Config.COMMIT_CURVE + "(" + Config.COMMIT_STRENGTH + ")"
                + "  值域=[" + Config.PROGRESS_MIN_HINT + ".." + Config.PROGRESS_MAX_HINT + "]"
                + (Config.INVERT_PROGRESS ? " 反向" : ""));
        L.i("并行属性: scale=" + (Config.SCALE_MIN != 1f ? L.f(Config.SCALE_MIN) : "关")
                + " alpha=" + (Config.ALPHA_MIN != 1f ? L.f(Config.ALPHA_MIN) : "关")
                + " blur=" + (Config.BLUR_MAX_PX > 0f ? L.f(Config.BLUR_MAX_PX) + "px" : "关")
                + "  平滑弹簧=" + Config.SMOOTH_WITH_SPRING);
        L.i("可打断机制: 纯函数驱动 + 每帧读取系统活进度（不持有时间轴）");

        captureContent(cl, Targets.PANE_WINDOW, "SlidingPaneWindow");
        captureContent(cl, Targets.ASSISTANT_WINDOW, "AssistantOverlayWindow");
        interactionEdges(cl, Targets.PANE_WINDOW, "SlidingPaneWindow");
        interactionEdges(cl, Targets.ASSISTANT_WINDOW, "AssistantOverlayWindow");
        remap(cl, Targets.PANE_WINDOW, "SlidingPaneWindow");
        remap(cl, Targets.ASSISTANT_WINDOW, "AssistantOverlayWindow");
        retuneCommit(cl);
        overrideEaseStyle(cl);

        L.i("========== 动画模式安装完毕 ==========");
    }

    // ---------------------------------------------------------------- 内容视图

    private static void captureContent(ClassLoader cl, String cls, String label) {
        HookKit.hook(cl, cls, Targets.M.SET_CONTENT, View.class, new HookKit.Cb(label + ".J(View)") {
            @Override
            protected void onBefore(MethodHookParam p) {
                View v = HookKit.firstView(p);
                if (v == null) {
                    return;
                }
                ParallelAnim.setView(v);
                if (!sContentLogged) {
                    sContentLogged = true;
                    L.i("已捕获内容视图: " + v.getClass().getName()
                            + "  " + ParallelAnim.baselineInfo());
                    HookKit.dumpViewTree(v, "  ");
                }
            }
        });
    }

    // ---------------------------------------------------------------- 交互边界

    /**
     * 交互起止 —— 这是"可打断"能成立的关键接线：
     *   · 一开始交互就唤醒采样器（并检测这次是不是打断了上一段动画）
     *   · 松手后继续采样，覆盖整个收敛过程，直到进度停住自动停止
     */
    private static void interactionEdges(ClassLoader cl, String cls, String label) {
        HookKit.hook(cl, cls, Targets.M.DRAG_START, new HookKit.Cb(label + ".A() [onDragStart]") {
            @Override
            protected void onBefore(MethodHookParam p) {
                tryCaptureFromWindow(p.thisObject);
                if (Config.SAMPLE_ON_FRAME) {
                    ProgressSampler.get().attach(p.thisObject);
                    ProgressSampler.get().onInteractionStart("onDragStart");
                }
            }
        });

        HookKit.hook(cl, cls, Targets.M.SCROLL_START, float.class,
                new HookKit.Cb(label + ".H(float) [scrollStart]") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        tryCaptureFromWindow(p.thisObject);
                        if (Config.SAMPLE_ON_FRAME) {
                            ProgressSampler.get().attach(p.thisObject);
                            ProgressSampler.get().onInteractionStart("overlayScrollStart");
                        }
                    }
                });

        HookKit.hook(cl, cls, Targets.M.SCROLL_END, float.class,
                new HookKit.Cb(label + ".G(float) [scrollEnd]") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        float raw = HookKit.firstFloat(p);
                        L.i(label + " 松手 p=" + L.f(Easing.normalize(raw))
                                + " —— 之后由 F(float) 继续逐帧驱动并行动画");
                        tryCaptureFromWindow(p.thisObject);
                        if (Config.SAMPLE_ON_FRAME) {
                            ProgressSampler.get().attach(p.thisObject);
                            ProgressSampler.get().start();
                        }
                    }
                });

        // 窗口挂载时也抓一次
        HookKit.hook(cl, cls, "onAttachedToWindow", new HookKit.Cb(label + ".onAttachedToWindow") {
            @Override
            protected void onAfter(MethodHookParam p) {
                tryCaptureFromWindow(p.thisObject);
            }
        });
    }

    /**
     * 兜底抓取目标视图。
     *
     * 正常情况下内容视图由 `J(View)` 给出；但如果那个 hook 没命中（或者视图被回收），
     * 并行动画就会完全失效 —— 表现就是"装了模块但什么都没变"。
     * 所以这里再从窗口对象上找 decorView 兜底。
     */
    private static void tryCaptureFromWindow(Object target) {
        if (ParallelAnim.ready() || target == null) {
            return;
        }
        try {
            Object win = null;
            try {
                win = de.robv.android.xposed.XposedHelpers.callMethod(target, "getWindow");
            } catch (Throwable ignored) {
                // 没有 getWindow 就往下走反射字段
            }
            if (win == null) {
                Class<?> c = target.getClass();
                while (c != null && win == null) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (android.view.Window.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            win = f.get(target);
                            break;
                        }
                    }
                    c = c.getSuperclass();
                }
            }
            if (win instanceof android.view.Window) {
                View decor = ((android.view.Window) win).peekDecorView();
                if (decor != null) {
                    ParallelAnim.setView(decor);
                    L.i("兜底捕获成功: decorView = " + decor.getClass().getName());
                }
            } else {
                L.w("兜底捕获: " + (target.getClass().getName()) + " 上没找到 Window 字段");
            }
        } catch (Throwable t) {
            L.w("兜底捕获失败: " + t);
        }
    }

    // ---------------------------------------------------------------- 曲线重映射

    private static void remap(ClassLoader cl, String cls, String label) {
        if (Easing.SYSTEM.equalsIgnoreCase(Config.DRAG_CURVE)) {
            L.i("拖拽曲线 = system（不重映射；但并行动画仍然由 F(float) 逐帧驱动）");
        }
        progressTap(cl, cls, label);
    }

    /**
     * 进度入口 —— 一边（可选）重映射曲线，一边**直接驱动并行动画**。
     *
     * ⚠️ 实测结论（真机日志）：
     *   · `F(float)` 是**逐帧**调用、携带真实进度（值域 -0.0059..1.0000，带轻微过冲）
     *   · `D(float)` **从不触发** —— 所以默认目标是 F 而不是 D
     *   · `w()` 全程 0 次调用 —— 所以**不能靠它当进度源**，
     *     早起版本的采样器就是栽在这里（拿不到进度 → 并行动画一次都不执行）
     */
    private static void progressTap(ClassLoader cl, String cls, String label) {
        boolean remapD = Config.REMAP_D;
        boolean remapF = Config.REMAP_F;

        if (remapD) {
            HookKit.hook(cl, cls, Targets.M.PROGRESS_1, float.class,
                    tapCb(label + ".D(float)", true));
        }
        if (remapF) {
            HookKit.hook(cl, cls, Targets.M.PROGRESS_2, float.class,
                    tapCb(label + ".F(float)", true));
        }
        // 即使不做曲线重映射，也要挂一个只驱动并行动画的 tap
        if (!remapD) {
            HookKit.hook(cl, cls, Targets.M.PROGRESS_1, float.class,
                    tapCb(label + ".D(float)", false));
        }
        if (!remapF) {
            HookKit.hook(cl, cls, Targets.M.PROGRESS_2, float.class,
                    tapCb(label + ".F(float)", false));
        }
    }

    private static HookKit.Cb tapCb(final String tag, final boolean doRemap) {
        return new HookKit.Cb(tag + (doRemap ? " [remap+parallel]" : " [parallel]")) {
            @Override
            protected void onBefore(MethodHookParam p) {
                float raw = HookKit.firstFloat(p);
                if (Float.isNaN(raw)) {
                    return;
                }
                ProgressSampler.get().attach(p.thisObject);

                float t = Easing.normalize(raw);

                if (doRemap && Config.REMAP_PROGRESS) {
                    float mapped = Easing.mapDrag(t);
                    p.args[0] = Easing.denormalize(mapped);
                    t = mapped;
                }

                if (Config.LOG_EVERY_FRAME) {
                    L.i(tag + " raw=" + L.f(raw) + " t=" + L.f(t));
                }

                // 直接驱动 —— 不依赖任何 getter，也就不会因为 w() 不存在而失效
                if (Config.DRIVE_PARALLEL_FROM_HOOK) {
                    ParallelAnim.apply(t);
                }
            }
        };
    }

    // ---------------------------------------------------------------- 提交动画

    /**
     * 提交阶段：改写系统构建的 AnimatorSet（仅覆盖 AnimatorSet 路径，与 Folme 主路径无关）。
     */
    private static void retuneCommit(ClassLoader cl) {
        if (!Config.RETUNE_COMMIT_ANIM) {
            return;
        }
        HookKit.Cb cb = new HookKit.Cb("SlidingPaneStateManager.a(...) [retune]") {
            @Override
            protected void onAfter(MethodHookParam p) {
                Object r = p.getResult();
                if (!(r instanceof AnimatorSet)) {
                    return;
                }
                AnimatorSet set = (AnimatorSet) r;
                for (Animator a : set.getChildAnimations()) {
                    a.setInterpolator(Easing.commitInterpolator());
                    long d = a.getDuration();
                    if (d > 0 && Config.COMMIT_DURATION_SCALE != 1f) {
                        a.setDuration((long) (d * Config.COMMIT_DURATION_SCALE));
                    }
                }
                long sd = set.getDuration();
                if (sd > 0 && Config.COMMIT_DURATION_SCALE != 1f) {
                    set.setDuration((long) (sd * Config.COMMIT_DURATION_SCALE));
                }
                L.i("已改写提交动画: children=" + set.getChildAnimations().size()
                        + " curve=" + Config.COMMIT_CURVE);
            }
        };

        Class<?> cState = HookKit.findClass(cl, Targets.PANE_STATE);
        Class<?> cBuilder = HookKit.findClass(cl, "com.miui.launcher.overlay.server.pane.d");
        Class<?> cR = HookKit.findClass(cl, "com.miui.launcher.overlay.server.pane.r");
        if (cState != null && cBuilder != null && cR != null) {
            HookKit.hook(cl, Targets.PANE_STATE_MGR, Targets.M.BUILD_ANIMATOR,
                    cState, cBuilder, cR, cb);
        } else {
            L.w("提交动画改写: 签名类未找全，退回 hookAll");
            HookKit.hookAll(cl, Targets.PANE_STATE_MGR, Targets.M.BUILD_ANIMATOR, cb);
        }
    }

    /**
     * 可选：把系统真实使用的 EaseStyle 换成另一条**小米自己的**曲线。
     *
     * 关键：不是重写公式，而是调系统自己的 {@code EaseManager.getStyle()} 工厂拿
     * **原厂 EaseStyle 对象**再塞回去 —— 100% 一致，不存在公式对不齐。
     */
    private static void overrideEaseStyle(ClassLoader cl) {
        if (!Config.OVERRIDE_EASE_STYLE) {
            L.i("EaseStyle 覆盖: 关闭（使用系统原本的曲线）");
            return;
        }
        L.i("EaseStyle 覆盖: 开启 -> " + XiaomiCurves.describe(Config.EASE_STYLE_ID));
        HookKit.hookAll(cl, Targets.MIUIX_ANIM_CONFIG, Targets.M.SET_EASE,
                new HookKit.Cb("AnimConfig.setEase [override]") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        if (p.args == null || p.args.length == 0) {
                            return;
                        }
                        ClassLoader cl2 = sCl;
                        if (cl2 == null) {
                            return;
                        }
                        Object replacement = XiaomiCurves.getSystemStyle(
                                cl2, Config.EASE_STYLE_ID, null);
                        if (replacement != null) {
                            p.args[0] = replacement;
                        }
                    }
                });
    }
}
