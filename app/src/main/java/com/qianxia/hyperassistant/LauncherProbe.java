package com.qianxia.hyperassistant;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import de.robv.android.xposed.XposedHelpers;

/**
 * 桌面侧探针：抓"桌面到底怎么打开应用的"——**真实 options 配方**。
 *
 * ===== 为什么要在桌面进程里挂 =====
 * 桌面自己的代码是 Rust/Flutter 原生库（APK 里没有 dex，挂不到），
 * **但桌面进程里的 framework Java 代码可以挂**，而且实测模块确实注入进桌面了
 * （日志：`模块在【桌面进程 com.miui.home】里被加载了`）。
 * 而我们要看清的，正是这些 Java 调用。
 *
 * 之前我靠"在 .so 里搜字符串"下过一个结论——`view_scale` / `makeHyperViewScaleAnimation`
 * 都是 0 次，于是说"桌面不走这套 Java 子系统"。**那个结论是错的**：
 * JNI 调 Java 时方法名字符串可能被混淆或分段，字符串缺失证明不了没调用。
 * 正确做法是在进程里直接看。
 *
 * ===== 要抓什么 =====
 * 1. `ActivityOptions.makeHyperViewScaleAnimation(View)` —— 桌面用不用 MIUI 的 Java HVS
 * 2. `HyperViewScaleRemoteStub` 那 5 个方法 —— **谁在调它们**
 *    （我搜遍 framework.jar / services.jar / 两个 hyperviewscale jar，
 *      调用次数全是 0，怀疑调用者就是桌面）
 * 3. `ContextImpl.startActivity` / `Instrumentation.execStartActivity` ——
 *    桌面点图标时 options 里**全部的 key 和 animType**
 * 4. `PendingIntent.send(...)` —— 桌面点**小部件 / 快捷方式**走的路
 *
 * 全部只读，不改任何行为。抓到之后就是"照抄"：
 * 桌面用什么 options、什么 animType、走不走 performClick，
 * 我们在负一屏里一比一复制 —— 这就是"和桌面一样"最直接的实现途径。
 */
public final class LauncherProbe {

    private LauncherProbe() {
    }

    private static int sStartActivityLog;
    private static int sPendingIntentLog;
    private static int sClickLog;

    public static void install(ClassLoader cl) {
        if (!Config.ENABLE_LAUNCHER_PROBE) {
            L.w("[桌面探针] 已禁用（ENABLE_LAUNCHER_PROBE=false）");
            return;
        }
        L.i("################################################");
        L.i("# 桌面侧探针安装（抓真实启动配方，只读不改行为）");
        L.i("################################################");

        // 1) 桌面用不用 MIUI 的 Java HVS —— 一挂就知道
        HookKit.hook(cl, "android.app.ActivityOptions", "makeHyperViewScaleAnimation",
                View.class, new HookKit.Cb("桌面探针.makeHyperViewScaleAnimation") {
                    @Override
                    protected void onAfter(MethodHookParam p) {
                        L.i("[桌面探针] ★★ 桌面调用了 ActivityOptions.makeHyperViewScaleAnimation -> "
                                + p.getResult()
                                + "   options=" + dumpOpts(p.getResult() instanceof android.app.ActivityOptions
                                ? ((android.app.ActivityOptions) p.getResult()).toBundle() : null));
                    }
                });

        // 2) HyperViewScaleRemoteStub 的 5 个方法 —— 看谁在调
        for (final String m : new String[]{"performClick", "setSystemView", "startActivity",
                "handleBindApplication", "onDestroy"}) {
            HookKit.hookAll(cl, "android.app.hyperviewscale.HyperViewScaleRemoteStub", m,
                    new HookKit.Cb("桌面探针.stub." + m) {
                        @Override
                        protected void onBefore(MethodHookParam p) {
                            L.i("[桌面探针] ★★ 桌面调用了 HyperViewScaleRemoteStub." + m
                                    + "  args=" + describe(p.args));
                        }

                        @Override
                        protected void onAfter(MethodHookParam p) {
                            if (p.getResult() instanceof Bundle) {
                                L.i("[桌面探针]    返回 bundle: " + dumpOpts(p.getResult()));
                            }
                        }
                    });
        }

        // 3) 桌面启动应用的入口 —— 抓 options 全量 key 与 animType
        HookKit.hookAll(cl, "android.content.ContextImpl", "startActivity",
                new HookKit.Cb("桌面探针.ContextImpl.startActivity") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        if (sStartActivityLog++ > 40) {
                            return;
                        }
                        Intent it = null;
                        Bundle opts = null;
                        if (p.args != null) {
                            for (Object a : p.args) {
                                if (a instanceof Intent && it == null) {
                                    it = (Intent) a;
                                } else if (a instanceof Bundle && opts == null) {
                                    opts = (Bundle) a;
                                }
                            }
                        }
                        L.i("[桌面探针] 桌面 startActivity: " + shortIntent(it)
                                + "   options=" + dumpOpts(opts));
                    }
                });

        HookKit.hookAll(cl, "android.app.Instrumentation", "execStartActivity",
                new HookKit.Cb("桌面探针.execStartActivity") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        if (sStartActivityLog++ > 40) {
                            return;
                        }
                        Intent it = null;
                        Bundle opts = null;
                        if (p.args != null) {
                            for (Object a : p.args) {
                                if (a instanceof Intent && it == null) {
                                    it = (Intent) a;
                                } else if (a instanceof Bundle && opts == null) {
                                    opts = (Bundle) a;
                                }
                            }
                        }
                        L.i("[桌面探针] execStartActivity: " + shortIntent(it)
                                + "   options=" + dumpOpts(opts));
                    }
                });

        // 4) 小部件 / 快捷方式走的是 PendingIntent —— 这正是负一屏那几种"没动画"的对应路径
        HookKit.hookAll(cl, "android.app.PendingIntent", "send",
                new HookKit.Cb("桌面探针.PendingIntent.send") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        if (sPendingIntentLog++ > 40) {
                            return;
                        }
                        Intent it = null;
                        Bundle opts = null;
                        if (p.args != null) {
                            for (Object a : p.args) {
                                if (a instanceof Intent && it == null) {
                                    it = (Intent) a;
                                } else if (a instanceof Bundle && opts == null) {
                                    opts = (Bundle) a;
                                }
                            }
                        }
                        L.i("[桌面探针] ★ 桌面 PendingIntent.send: " + shortIntent(it)
                                + "   options=" + dumpOpts(opts));
                    }
                });

        // 5) 桌面记源用的哪个方法（对应我们在负一屏里的 arm）
        HookKit.hook(cl, "android.view.View", "performClick", new HookKit.Cb("桌面探针.performClick") {
            @Override
            protected void onAfter(MethodHookParam p) {
                if (sClickLog++ > 25 && sClickLog % 25 != 0) {
                    return;
                }
                Object self = p.thisObject;
                String desc = self instanceof View
                        ? (((View) self).getClass().getName()
                        + " " + ((View) self).getWidth() + "x" + ((View) self).getHeight())
                        : String.valueOf(self);
                L.i("[桌面探针] View.performClick " + desc + "  第 " + sClickLog + " 次");
            }
        });

        L.i("[桌面探针] 安装完毕 —— 现在请在【桌面】上："
                + "① 点一个应用图标 ② 点一个小部件 ③ 点一个快捷方式");
    }

    // ------------------------------------------------------------------ 工具

    private static String shortIntent(Intent it) {
        if (it == null) {
            return "null";
        }
        return it.getComponent() != null ? it.getComponent().flattenToShortString()
                : String.valueOf(it.getAction());
    }

    /** 把 options 的关键信息压成一行：**全部 key** + animType + viewName + 有无 remoteTransition。 */
    private static String dumpOpts(Bundle b) {
        if (b == null) {
            return "null（没有带 options）";
        }
        int animType = -1;
        try {
            animType = b.getInt("android:activity.animType", -1);
        } catch (Throwable ignored) {
        }
        String viewName = null;
        try {
            viewName = b.getString("android:activity.view.name");
        } catch (Throwable ignored) {
        }
        return "keys=" + b.keySet()
                + "  animType=" + animType
                + "  viewName=" + viewName
                + "  remoteTransition=" + b.containsKey("android:activity.remoteTransition")
                + "  animAbortListener=" + b.containsKey("android:activity.animAbortListener");
    }

    private static String dumpOpts(Object opts) {
        if (opts instanceof Bundle) {
            return dumpOpts((Bundle) opts);
        }
        return String.valueOf(opts);
    }

    private static String describe(Object[] args) {
        if (args == null) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (i > 0) {
                sb.append(", ");
            }
            if (a == null) {
                sb.append("null");
            } else if (a instanceof View) {
                View v = (View) a;
                sb.append(v.getClass().getName()).append(' ')
                        .append(v.getWidth()).append('x').append(v.getHeight());
            } else if (a instanceof Intent) {
                sb.append(shortIntent((Intent) a));
            } else if (a instanceof Bundle) {
                sb.append(dumpOpts((Bundle) a));
            } else {
                String s = String.valueOf(a);
                sb.append(s.length() > 120 ? s.substring(0, 120) + "…" : s);
            }
        }
        return sb.append(']').toString();
    }
}
