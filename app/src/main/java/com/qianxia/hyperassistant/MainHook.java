package com.qianxia.hyperassistant;

import android.content.Context;
import android.content.pm.PackageInfo;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口（由 assets/xposed_init 指定）。
 *
 * 只作用于 com.miui.personalassistant —— 不需要桌面，不需要系统框架。
 */
public class MainHook implements IXposedHookLoadPackage {

    /** 已知的适配基线版本。版本变了只警告，不阻断（hook 本身已做安全降级）。 */
    private static final String KNOWN_VERSION_PREFIX = "25.40.";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // ---- system_server：提权（放行远程动画权限）----
        if (Targets.SYSTEM_SERVER_PKG.equals(lpparam.packageName)) {
            try {
                SystemServerHooks.install(lpparam.classLoader);
            } catch (Throwable t) {
                L.e("system_server 侧安装失败", t);
            }
            // 把负一屏正式加进小米的 HyperViewScale 配置表：
            // uid 自动进官方白名单（权限问题从根上消失）+ 应用侧拿到 corner/bgColor 配置
            try {
                HvsConfigInjector.install(lpparam.classLoader);
            } catch (Throwable t) {
                L.e("HVS 配置注入安装失败", t);
            }
            // 盯住关闭过渡（退出动画）的真实实现 HyperViewScaleTransitionImpl
            try {
                HvsServerDiagnostics.install(lpparam.classLoader);
            } catch (Throwable t) {
                L.e("HVS 服务端打点安装失败", t);
            }
            // ★ DEV ONLY：纯只读过渡取证探针（测绘文档第 4 节 S1~S5）。
            //   默认关（CoreConfig.DEV_SYSTEM_SERVER_PROBE=false）→ 一个钩子都不装。
            //   与上面三个安装器完全独立，互不影响；只读、无副作用。
            try {
                SystemServerProbe.install(lpparam.classLoader);
            } catch (Throwable t) {
                L.e("只读过渡探针安装失败", t);
            }
            // ★★ DEV ONLY / L1 实验：把上滑那条过渡的 type 1021 窄归一化成 2。
            //   默认关（CoreConfig.DEV_NORMALIZE_1021_TO_2=false）→ 一个实参都不改。
            //   只改 WindowOrganizerController.startMiuiNewTransition 的第一个实参。
            try {
                SystemServerNormalize.install(lpparam.classLoader);
            } catch (Throwable t) {
                L.e("L1 归一化安装失败", t);
            }
            return;
        }

        // ---- 桌面进程：只做诊断，验证"桌面到底能不能 hook" ----
        if (Targets.LAUNCHER_PKG.equals(lpparam.packageName)) {
            L.i(">>> 模块在【桌面进程 com.miui.home】里被加载了。");
            L.i(">>> 这本身就说明桌面进程是 ART 进程（否则 LSPosed 无法注入）。");
            final ClassLoader cl = lpparam.classLoader;
            try {
                ProbeHooks.launcherDiagnostic(cl);
            } catch (Throwable t) {
                L.e("桌面诊断失败", t);
            }
            // ★ 桌面侧探针：抓桌面打开应用的真实 options 配方（纯只读）
            //   桌面自己的代码是 Rust/Flutter 原生库挂不到，但进程里的 framework Java 可以挂
            try {
                LauncherProbe.install(cl);
            } catch (Throwable t) {
                L.e("桌面探针安装失败", t);
            }
            // 桌面在开机很早就启动，第一次输出会被日志轮转冲掉 —— 排几次延迟重打
            try {
                final android.os.Handler h =
                        new android.os.Handler(android.os.Looper.getMainLooper());
                for (final long delay : new long[]{60_000L, 180_000L, 600_000L}) {
                    h.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                ProbeHooks.launcherDiagnostic(cl);
                            } catch (Throwable t) {
                                L.e("桌面诊断(延迟)失败", t);
                            }
                        }
                    }, delay);
                }
                L.i(">>> 已安排 60s / 180s / 600s 后重打桌面诊断");
            } catch (Throwable t) {
                L.w("安排延迟诊断失败: " + t);
            }
            return;
        }

        if (!Targets.PKG.equals(lpparam.packageName)) {
            return;
        }

        L.i("################################################");
        L.i("# HyperOS4 负一屏动画模块 已载入");
        L.i("# 包名: " + lpparam.packageName);
        L.i("# 进程: " + lpparam.processName);
        L.i("# SDK : " + android.os.Build.VERSION.SDK_INT);
        L.i("################################################");

        versionGuard(lpparam.classLoader);
        AssistantAnimCore.install(lpparam.classLoader);

        // 注意：这里必须把 ENABLE_OPEN_ANIM 也算进去。
        // 之前只判断 ENABLE_PROBE / ENABLE_ANIM，导致"关掉探测刷屏"之后
        // 三个开关里有两个是 false，就直接 return 了 —— 打开动画模块根本没装上。
        if (!Config.ENABLE_PROBE && !Config.ENABLE_ANIM && !Config.ENABLE_OPEN_ANIM
                && !Config.ENABLE_HVS_DIAG) {
            L.w("ENABLE_PROBE / ENABLE_ANIM / ENABLE_OPEN_ANIM / ENABLE_HVS_DIAG 全是 false —— 什么都不会做。");
            return;
        }

        if (Config.ENABLE_PROBE) {
            try {
                ProbeHooks.install(lpparam.classLoader);
            } catch (Throwable t) {
                L.e("探测模块安装失败", t);
            }
        }

        if (Config.ENABLE_ANIM) {
            try {
                AnimHooks.install(lpparam.classLoader);
            } catch (Throwable t) {
                L.e("动画模块安装失败", t);
            }
        }

        // 打开动画（点小部件 → 系统桌面那种全动画）—— 这是主功能
        if (Config.ENABLE_OPEN_ANIM) {
            try {
                OpenAnimHooks.install(lpparam.classLoader);
            } catch (Throwable t) {
                L.e("打开动画模块安装失败", t);
            }
        }

        // HyperViewScale 内部打点 + 用小米自己的配置补齐 bundle（白边/黑边、圆角、关闭动画）
        if (Config.ENABLE_HVS_DIAG) {
            try {
                HvsDiagnostics.install(lpparam.classLoader);
            } catch (Throwable t) {
                L.e("HVS 打点安装失败", t);
            }
        }

        // 快捷方式探针（只读）：判断微信/支付宝扫码快捷方式走哪条启动分支
        if (Config.ENABLE_SHORTCUT_PROBE) {
            try {
                ShortcutProbe.install(lpparam.classLoader);
            } catch (Throwable t) {
                L.e("快捷方式探针安装失败", t);
            }
        }

        // 测试触发器（只有测试脚手架用途）：adb shell touch 一个标记文件 → 我们在进程内
        // 对负一屏的图标/小部件 performClick()，走完全真实的启动链路。
        // 因为 `input tap` / `sendevent` 在这台机器上都被拒绝（INJECT_EVENTS / SELinux），
        // 否则每一轮验证都得等人工点。
        if (Config.ENABLE_TEST_TRIGGER) {
            try {
                HookKit.hook(lpparam.classLoader, "android.app.Activity", "onResume",
                        new HookKit.Cb("测试.onResume") {
                            @Override
                            protected void onAfter(de.robv.android.xposed.XC_MethodHook.MethodHookParam p) {
                                if (p.thisObject instanceof android.app.Activity) {
                                    TestTrigger.onActivityResumed((android.app.Activity) p.thisObject);
                                }
                            }
                        });
                // 负一屏其实是 WindowManager 覆盖窗口（不一定有 Activity），
                // 所以从 ViewRootImpl.setView 直接抓根视图更可靠。
                HookKit.hook(lpparam.classLoader, "android.view.ViewRootImpl", "setView",
                        android.view.View.class,
                        android.view.WindowManager.LayoutParams.class,
                        android.view.View.class,
                        new HookKit.Cb("测试.setView") {
                            @Override
                            protected void onBefore(de.robv.android.xposed.XC_MethodHook.MethodHookParam p) {
                                try {
                                    android.view.View v = (android.view.View) p.args[0];
                                    android.view.WindowManager.LayoutParams lp =
                                            (android.view.WindowManager.LayoutParams) p.args[1];
                                    TestTrigger.onWindowAdded(v,
                                            lp == null ? null : String.valueOf(lp.getTitle()));
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                TestTrigger.start();
            } catch (Throwable t) {
                L.e("测试触发器安装失败", t);
            }
        }
    }

    /**
     * 版本守卫。类名混淆会随 ROM 更新变化，这里只做提示，
     * 因为每个 hook 都已经单独做了失败降级。
     */
    private void versionGuard(ClassLoader cl) {
        try {
            Class<?> at = HookKit.findClass(cl, "android.app.ActivityThread");
            if (at == null) {
                return;
            }
            Object app = XposedHelpers.callStaticMethod(at, "currentApplication");
            if (!(app instanceof Context)) {
                return;
            }
            PackageInfo pi = ((Context) app).getPackageManager()
                    .getPackageInfo(Targets.PKG, 0);
            String v = pi.versionName;
            L.i("宿主版本: " + v + "  (code=" + pi.getLongVersionCode() + ")");
            if (v == null || !v.startsWith(KNOWN_VERSION_PREFIX)) {
                L.w("!! 版本 " + v + " 不在已验证的 " + KNOWN_VERSION_PREFIX + "* 范围内，"
                        + "类名/方法名可能已变，请核对 Targets.java 里的常量。");
            }
        } catch (Throwable t) {
            L.w("版本检测失败（不影响运行）: " + t);
        }
    }
}
