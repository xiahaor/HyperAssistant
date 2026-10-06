package com.qianxia.hyperassistant;

import android.content.Context;
import android.content.Intent;

import de.robv.android.xposed.XposedHelpers;

/**
 * 快捷方式探针：判断微信/支付宝扫码快捷方式**实际走哪条启动分支**。
 *
 * ===== 为什么需要它 =====
 * 从《智能助理》APK 静态读出，快捷方式有四条启动分支
 * （`com.miui.personalassistant.service.shortcut.utils.m`，源文件 `ShortcutItems.kt`）：
 * <pre>
 *   ① shortcut.getXspace() → 反射 PendingIntent.getActivityAsUser → pi.send()
 *        走 system_server 代发，**不经过我们 hook 的 ContextImpl.startActivity**
 *   ② 微信小程序 → WXAPIFactory.createWXAPI(...).sendReq(WXLaunchMiniProgram.Req)
 *        **根本不是 startActivity**，原理上没法加打开动画
 *   ③ 语音 → miuix.voicesdk 的 uf.b.c(...)
 *   ④ 普通 → ctx.startActivity(...)   ← 只有这条能被我们 hook
 * </pre>
 *
 * ===== 纪律 =====
 * **这一版是纯只读**：只打点，绝不注入任何 options、绝不改任何行为。
 * 2026-09-26 的事故就是从"无门控地往别人的 Intent 里塞 options"开始的，
 * 判断清楚分支之前不许动手。
 */
public final class ShortcutProbe {

    private ShortcutProbe() {
    }

    private static int sLog;

    public static void install(ClassLoader cl) {
        if (!Config.ENABLE_SHORTCUT_PROBE) {
            L.w("[快捷方式探针] 已禁用（ENABLE_SHORTCUT_PROBE=false）");
            return;
        }
        L.i("################################################");
        L.i("# 快捷方式探针安装（只读，判断走哪条启动分支）");
        L.i("################################################");

        // 入口 1：m.b(Context, int, Shortcut, boolean) -> Z  （ShortcutItems.getAppIntent 那条）
        HookKit.hookAll(cl, "com.miui.personalassistant.service.shortcut.utils.m", "b",
                new HookKit.Cb("快捷方式探针.m.b") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        if (sLog++ > 60) {
                            return;
                        }
                        L.i("[快捷方式探针] ★ 进入 m.b  快捷方式=" + describeShortcut(p.args)
                                + "  参数=" + describeArgs(p.args));
                    }
                });

        // 入口 2：m.g(Context, int, Shortcut, boolean, Intent) -> V
        //   反汇编里明确有：xspace 为真 → 反射拿 getActivityAsUser → pi.send()；
        //   否则 log 'xspace pendingIntent is null, fallback to normal start'
        HookKit.hookAll(cl, "com.miui.personalassistant.service.shortcut.utils.m", "g",
                new HookKit.Cb("快捷方式探针.m.g") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        if (sLog++ > 60) {
                            return;
                        }
                        L.i("[快捷方式探针] ★ 进入 m.g（PendingIntent 那条）  快捷方式="
                                + describeShortcut(p.args) + "  参数=" + describeArgs(p.args));
                    }
                });

        // 分支①：PendingIntent.send —— 走 system_server 代发，我们的 startActivity hook 看不见
        HookKit.hookAll(cl, "android.app.PendingIntent", "send",
                new HookKit.Cb("快捷方式探针.PendingIntent.send") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        if (sLog++ > 60) {
                            return;
                        }
                        Intent it = null;
                        if (p.args != null) {
                            for (Object a : p.args) {
                                if (a instanceof Intent) {
                                    it = (Intent) a;
                                    break;
                                }
                            }
                        }
                        L.i("[快捷方式探针] ★★ 分支① PendingIntent.send  intent="
                                + (it == null ? "null" : it.toString()));
                    }
                });

        // 分支②：微信小程序 —— 用 OpResult 判断，不碰业务逻辑
        HookKit.hookAll(cl, "com.tencent.mm.opensdk.openapi.BaseWXApiImplV10", "sendReq",
                new HookKit.Cb("快捷方式探针.WXAPI.sendReq") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        L.i("[快捷方式探针] ★★ 分支② 微信 SDK sendReq（小程序路径，"
                                + "原理上没有 startActivity 动画可用）  req="
                                + (p.args != null && p.args.length > 0 ? p.args[0] : "null"));
                    }
                });

        // 分支③：语音 SDK（混淆类名 uf.b）
        HookKit.hookAll(cl, "uf.b", "c", new HookKit.Cb("快捷方式探针.voicesdk") {
            @Override
            protected void onBefore(MethodHookParam p) {
                L.i("[快捷方式探针] ★★ 分支③ 语音 SDK uf.b.c  参数=" + describeArgs(p.args));
            }
        });

        L.i("[快捷方式探针] 安装完毕 —— 请点一次微信/支付宝的扫码快捷方式");
    }

    // ------------------------------------------------------------------ 工具

    /** 从参数里挑出 Shortcut 对象，读它的 packageName / className。 */
    private static String describeShortcut(Object[] args) {
        if (args == null) {
            return "?";
        }
        for (Object a : args) {
            if (a == null) {
                continue;
            }
            String n = a.getClass().getName();
            if (!n.contains("shortcut")) {
                continue;
            }
            StringBuilder sb = new StringBuilder(n.substring(n.lastIndexOf('.') + 1));
            for (String g : new String[]{"getPackageName", "getClassName", "getId", "getXspace"}) {
                try {
                    sb.append(' ').append(g).append('=').append(XposedHelpers.callMethod(a, g));
                } catch (Throwable ignored) {
                }
            }
            return sb.toString();
        }
        return "(参数里没有 Shortcut 对象)";
    }

    private static String describeArgs(Object[] args) {
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
            } else if (a instanceof Intent) {
                sb.append("Intent{").append(a).append('}');
            } else if (a instanceof Context) {
                sb.append(a.getClass().getSimpleName());
            } else {
                String s = String.valueOf(a);
                sb.append(s.length() > 100 ? s.substring(0, 100) + "…" : s);
            }
        }
        return sb.append(']').toString();
    }
}
