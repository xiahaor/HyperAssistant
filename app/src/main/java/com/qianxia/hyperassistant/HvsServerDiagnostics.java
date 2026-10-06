package com.qianxia.hyperassistant;

import android.content.Intent;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * system_server 侧：盯住 HyperViewScale 的**关闭过渡**这条路。
 *
 * ===== 为什么是这个类 =====
 * `services.jar` 里的 `com.android.server.wm.HyperViewScaleTransitionStub` 是**纯空壳**：
 * <pre>
 *   isHyperViewScaleRemote(RemoteTransition, I) -> Z      指令数=1   const 0   ← 恒 false
 *   requestViewScaleRemoteClose(...) -> Transition        指令数=1   const 0   ← 恒 null
 *   requestStartTransition(...) -> V                      指令数=0   ← 空
 * </pre>
 * 真身是 `miui-services.jar` 里的
 * `com.android.server.wm.HyperViewScaleTransitionImpl`（`Provider.provideSingleton()` 拿 `INSTANCE`），
 * 通过 `MiuiStubUtil.getInstance(HyperViewScaleTransitionStub.class)` 换成它。
 *
 * 反汇编出来的两段关键逻辑：
 * <pre>
 *   // 权限闸门 —— 只有 debugName 是 "view_scale_remote_transition" 的过渡才走这一套，
 *   //            并且放行与否**完全取决于官方 uid 白名单**
 *   boolean isHyperViewScaleRemote(RemoteTransition rt, int uid) {
 *       return TextUtils.equals(rt.getDebugName(), "view_scale_remote_transition")
 *           && HyperViewScaleConfigManagerStub.get().isHyperViewScaleAllowedUid(uid);
 *   }
 *
 *   // 退出动画的入口 —— 靠 Intent 上的 view_scale_open extra
 *   Transition requestViewScaleRemoteClose(TransitionController tc, Intent intent, WindowContainer wc) {
 *       if (!isFeatureEnable()) return null;
 *       RemoteTransition rt = intent.getExtras().getParcelable("view_scale_open", RemoteTransition.class);
 *       if (wc.asActivityRecord() == null) { log("Request remote close transition fail, ar is null"); return null; }
 *       return requestCloseTransitionIfNeeded(tc, wc, rt);
 *   }
 *
 *   Transition requestCloseTransitionIfNeeded(TransitionController tc, WindowContainer wc, RemoteTransition rt) {
 *       if (tc.isCollecting()) { log("Skip request close transition, isCollecting: "); return null; }  // ← 会被这里挡掉
 *       Transition t = tc.createTransition(2, 0);        // 2 = TRANSIT_CLOSE
 *       return tc.requestStartTransition(t, wc.asTask(), rt, null);
 *   }
 * </pre>
 *
 * 调用者是 `ActivityRecord.finishIfPossible()` 和 `ActivityRecord.handleAppDied()`
 * —— 也就是说**退出动画是 system_server 主动发起的**，应用侧只要把那个 extra 写好就行。
 * 实测「没有退出动画」有两种可能，靠这里的日志区分：
 *   1. `requestViewScaleRemoteClose` 压根没被调用（那就与动画无关，是没走到）
 *   2. 被调用了但返回 null —— 要么 extra 是 null，要么 `isCollecting()` 挡掉了
 *
 * ⚠️ 和 impl jar 一个坑：这个类也在**子 ClassLoader** 里（`MiuiStubUtil` 代理进来的），
 *    必须用 `HyperViewScaleTransitionStub.getInstance().getClass()` 反推。
 */
public final class HvsServerDiagnostics {

    private HvsServerDiagnostics() {
    }

    private static final String STUB = "com.android.server.wm.HyperViewScaleTransitionStub";
    private static final String IMPL = "com.android.server.wm.HyperViewScaleTransitionImpl";

    private static volatile boolean sInstalled;
    private static int sLogCount;
    private static int sRemoteCheckCount;

    public static void install(ClassLoader cl) {
        if (sInstalled) {
            return;
        }
        if (!Config.ENABLE_HVS_SERVER_DIAG) {
            return;
        }
        final ClassLoader fcl = cl;
        if (installNow(fcl)) {
            return;
        }
        final int[] delays = {500, 2000, 6000, 15000, 30000};
        for (final int d : delays) {
            post(new Runnable() {
                @Override
                public void run() {
                    if (!sInstalled) {
                        installNow(fcl);
                    }
                }
            }, d);
        }
    }

    private static synchronized boolean installNow(ClassLoader cl) {
        if (sInstalled) {
            return true;
        }
        Class<?> stub;
        Class<?> impl;
        try {
            stub = XposedHelpers.findClass(STUB, cl);
        } catch (Throwable t) {
            return false;
        }
        try {
            // 关键：用 getInstance() 反推实现类，别按名字找 —— 它在子 ClassLoader 里
            Object inst = XposedHelpers.callStaticMethod(stub, "getInstance");
            if (inst == null) {
                return false;
            }
            impl = inst.getClass();
        } catch (Throwable t) {
            return false;
        }
        sInstalled = true;
        L.i("################################################");
        L.i("# [HVS服务端] 找到实现类 " + impl.getName());
        L.i("################################################");

        // 权限闸门的实际判定 —— 直接看出"官方白名单认没认负一屏"。
        // 顺便把**属于负一屏的那个 RemoteTransition 对象缓存下来**，
        // 关闭过渡那一步要用（见下面的补写逻辑）。
        HookKit.hookAll(impl, "isHyperViewScaleRemote", new HookKit.Cb("HVS.isHyperViewScaleRemote") {
            @Override
            protected void onAfter(MethodHookParam p) {
                if (p.args != null && p.args.length > 1 && p.args[1] instanceof Integer) {
                    int uid = (Integer) p.args[1];
                    if ((uid % 100000) == Config.TARGET_APP_ID) {
                        rememberRemoteTransition(p.args[0]);
                    }
                }
                if (sRemoteCheckCount++ < 40) {
                    Object rt = (p.args != null && p.args.length > 0) ? p.args[0] : null;
                    Object uid = (p.args != null && p.args.length > 1) ? p.args[1] : null;
                    L.i("[HVS服务端] isHyperViewScaleRemote(debugName=" + debugName(rt)
                            + ", uid=" + uid + ") -> " + p.getResult());
                }
            }
        });

        // 退出动画入口
        HookKit.hookAll(impl, "requestViewScaleRemoteClose",
                new HookKit.Cb("HVS.requestViewScaleRemoteClose") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        Intent it = null;
                        if (p.args != null) {
                            for (Object a : p.args) {
                                if (a instanceof Intent) {
                                    it = (Intent) a;
                                    break;
                                }
                            }
                        }
                        Object extra = null;
                        boolean has = false;
                        if (it != null) {
                            has = it.hasExtra("view_scale_open");
                            try {
                                extra = it.getParcelableExtra("view_scale_open");
                            } catch (Throwable ignored) {
                            }
                        }
                        L.i("[HVS服务端] ★ 退出动画入口被调用！intent=" + (it == null ? "null" : "有")
                                + "  component=" + (it == null ? "-" : it.getComponent())
                                + "  extras=" + (it == null || it.getExtras() == null
                                ? "null" : it.getExtras().keySet())
                                + "  view_scale_open: has=" + has + " 值=" + extra
                                + (extra == null ? "   ← 值为 null，退出会退化成硬切" : ""));

                        // 补写：系统手上这个 Intent 没有 view_scale_open（实测 has=false），
                        // 于是 requestCloseTransitionIfNeeded 拿到 null，关闭过渡建不起来。
                        if (Config.HVS_INJECT_CLOSE_EXTRA && it != null && extra == null) {
                            Object rt = freshRemoteTransition();
                            if (rt instanceof android.os.Parcelable) {
                                try {
                                    it.putExtra("view_scale_open", (android.os.Parcelable) rt);
                                    L.i("[HVS服务端] ★★ 已把最近的 RemoteTransition 补写进该 Intent"
                                            + "（debugName=" + debugName(rt) + "），关闭过渡应该能建起来了");
                                } catch (Throwable t) {
                                    L.w("[HVS服务端] 补写 view_scale_open 失败: " + t);
                                }
                            } else {
                                L.w("[HVS服务端] 缓存里没有可用的 RemoteTransition"
                                        + (sLastRt == null ? "（压根没见过）" : "（已过期 " +
                                        (android.os.SystemClock.uptimeMillis() - sLastRtAt) + "ms）"));
                            }
                        }
                    }

                    @Override
                    protected void onAfter(MethodHookParam p) {
                        L.i("[HVS服务端] ★ requestViewScaleRemoteClose 返回=" + p.getResult()
                                + (p.getResult() == null ? "（null = 没建关闭过渡）" : "（已建关闭过渡）"));
                    }
                });

        // 被 isCollecting 挡掉的那一步
        HookKit.hookAll(impl, "requestCloseTransitionIfNeeded",
                new HookKit.Cb("HVS.requestCloseTransitionIfNeeded") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        Object rt = null;
                        if (p.args != null && p.args.length > 2) {
                            rt = p.args[2];
                        }
                        boolean ours = freshRemoteTransition() != null;
                        L.i("[HVS服务端] requestCloseTransitionIfNeeded 进入, rt=" + debugName(rt)
                                + "  是否我们的过渡=" + ours);
                        if (Config.HVS_FORCE_CLOSE_TRANSITION && ours && rt != null) {
                            // 反汇编出来这里只有一个提前返回：
                            //   if (tc.isCollecting()) { log("Skip request close transition, isCollecting: "); return null; }
                            // 实测返回键时它确实为 true → 官方自建的 TRANSIT_CLOSE 过渡被跳过
                            // → 只能走 mergeAnimation，而那条路又因为 type 不等于 1/2/3 不反向
                            // → 退出变成硬切。
                            // 所以在这一个调用期间把 isCollecting() 强制成 false，
                            // 让官方**设计好的那条关闭过渡**建起来。
                            installCollectingOverride(p);
                            sForceNotCollecting = true;
                        }
                    }

                    @Override
                    protected void onAfter(MethodHookParam p) {
                        sForceNotCollecting = false;
                        L.i("[HVS服务端] requestCloseTransitionIfNeeded 返回=" + p.getResult()
                                + (p.getResult() == null ? "  ← 仍然 null" : "  ✔ 关闭过渡已建立"));
                    }
                });

        // ★ 返场信号中继：焦点落到负一屏窗口时，写一个系统属性通知负一屏进程。
        //
        // 为什么必须在 system_server 做：实测应用侧**没有任何 API** 能知道
        // "应用是不是盖在最上面"——
        //   · 窗口焦点：应用打开转场时会抖回来（间隔 400~1700ms），区分不开
        //   · 窗口可见性：被遮挡时**不会**变成 GONE，只在转场时抖一下
        //   · View.isVisibleToUser()：一直是 true
        // 因为 Android 不会因为窗口被遮挡就改它的可见性。
        //
        // 而 system_server 知道谁是当前焦点窗口，且负一屏窗口的名字是唯一的：
        //   mCurrentFocus=Window{... LauncherOverlayWindow:com.miui.personalassistant}
        // 所以只要焦点落到这个窗口，就说明用户回到负一屏了。
        //
        // 跨进程通道用系统属性：system_server(uid 1000) 能写 debug.*，
        // 负一屏进程能读任意属性，不用权限、不用 Binder 注册。
        installFocusRelay(cl);

        // 打开时的 flag 设置（常量 1048576）
        HookKit.hookAll(impl, "requestStartTransition",
                new HookKit.Cb("HVS.requestStartTransition(server)") {
                    @Override
                    protected void onBefore(MethodHookParam p) {
                        if (sLogCount++ < 30) {
                            Object rt = p.args != null ? p.args[0] : null;
                            Object name = null;
                            try {
                                name = XposedHelpers.callMethod(rt, "getRemoteTransition");
                            } catch (Throwable ignored) {
                            }
                            L.i("[HVS服务端] requestStartTransition, info.remoteTransition="
                                    + debugName(name));
                        }
                    }
                });
        return true;
    }

    // ------------------------------------------------------------------ 返场信号中继

    /** 负一屏窗口的名字（`dumpsys window` 里的 mCurrentFocus 就是这个前缀）。 */
    private static final String OVERLAY_WINDOW = "LauncherOverlayWindow:com.miui.personalassistant";
    /** 中继用的系统属性键。system_server 写，负一屏进程轮询读。 */
    public static final String PROP_RETURN = "debug.hvs.return_at";

    private static volatile boolean sFocusRelayInstalled;
    private static volatile int sRelayLogCount;
    private static volatile long sLastRelayAt;

    private static void installFocusRelay(ClassLoader cl) {
        if (sFocusRelayInstalled) {
            return;
        }
        if (!Config.HVS_ENABLE_RETURN_RELAY) {
            L.w("[返场中继] 已禁用（HVS_ENABLE_RETURN_RELAY=false）——"
                    + "当前实现无状态机、会大范围误触发，见 Config 注释");
            return;
        }
        Class<?> wms = null;
        try {
            wms = XposedHelpers.findClass("com.android.server.wm.WindowManagerService", cl);
        } catch (Throwable ignored) {
        }
        if (wms == null) {
            L.w("[返场中继] 找不到 WindowManagerService，返场信号发不出去");
            return;
        }
        sFocusRelayInstalled = true;
        HookKit.hookAll(wms, "updateFocusedWindowLocked", new HookKit.Cb("返场中继.updateFocused") {
            @Override
            protected void onAfter(MethodHookParam p) {
                try {
                    Object focus = XposedHelpers.callMethod(p.thisObject, "getFocusedWindow");
                    if (focus == null) {
                        return;
                    }
                    Object nameObj = XposedHelpers.callMethod(focus, "getName");
                    String name = String.valueOf(nameObj);
                    if (!name.contains(OVERLAY_WINDOW)) {
                        return;
                    }
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - sLastRelayAt < 400) {
                        return;     // 去抖
                    }
                    sLastRelayAt = now;
                    Class<?> sp = XposedHelpers.findClass("android.os.SystemProperties", cl);
                    XposedHelpers.callStaticMethod(sp, "set", PROP_RETURN, String.valueOf(now));
                    if (sRelayLogCount++ < 40) {
                        L.i("[返场中继] ★ 焦点回到负一屏窗口 → 已写入 " + PROP_RETURN
                                + "=" + now + "   (window=" + name + ")");
                    }
                } catch (Throwable t) {
                    if (sRelayLogCount++ < 5) {
                        L.w("[返场中继] 写属性失败: " + t);
                    }
                }
            }
        });
        L.i("[返场中继] 已挂 WindowManagerService.updateFocusedWindowLocked（返场信号）");
    }

    // ------------------------------------------------------------------ 强制放行关闭过渡

    private static volatile boolean sForceNotCollecting;
    private static volatile boolean sCollectingHookInstalled;

    /**
     * 在 `requestCloseTransitionIfNeeded` 这次调用期间，让
     * `TransitionController.isCollecting()` 返回 false。
     *
     * 为什么：反汇编出来的实现是
     * <pre>
     *   Transition requestCloseTransitionIfNeeded(TransitionController tc, WindowContainer wc,
     *                                             RemoteTransition rt) {
     *       if (tc.isCollecting()) {                  // ← 实测返回键时为 true
     *           Slog.d("HyperViewScaleTransition", "Skip request close transition, isCollecting: ");
     *           return null;
     *       }
     *       Transition t = tc.createTransition(2, 0);  // 2 = TRANSIT_CLOSE
     *       return tc.requestStartTransition(t, wc.asTask(), rt, null);
     *   }
     * </pre>
     * 一旦被跳过，就只剩 `mergeAnimation` 那条路，而那里
     * `mReverseToActivityA/B` 是拿 `TransitionInfo.getType()` 跟 1/2/3 比出来的，
     * 实测到达时 type=1021 → 两个都 false → 不反向 → **退出硬切**。
     *
     * 只在**我们自己那一次关闭**期间生效（要求缓存里的 RemoteTransition 是新鲜的），
     * 其它任何时候都不碰 `isCollecting()`。
     */
    private static void installCollectingOverride(XC_MethodHook.MethodHookParam p) {
        if (sCollectingHookInstalled) {
            return;
        }
        try {
            Class<?> tcCls = null;
            if (p.args != null) {
                for (Object a : p.args) {
                    if (a != null && a.getClass().getName()
                            .equals("com.android.server.wm.TransitionController")) {
                        tcCls = a.getClass();
                        break;
                    }
                }
            }
            if (tcCls == null) {
                return;
            }
            sCollectingHookInstalled = true;
            final Class<?> c = tcCls;
            HookKit.hookAll(c, "isCollecting", new HookKit.Cb("HVS.isCollecting") {
                @Override
                protected void onAfter(MethodHookParam q) {
                    Object orig = q.getResult();
                    if (sForceNotCollecting) {
                        q.setResult(Boolean.FALSE);
                        if (sCollectingLogCount++ < 20) {
                            L.i("[HVS服务端] isCollecting 原值=" + orig
                                    + " → 本次关闭期间强制 false（否则官方会跳过关闭过渡）");
                        }
                    }
                }
            });
        } catch (Throwable t) {
            L.w("[HVS服务端] 安装 isCollecting 钩子失败: " + t);
        }
    }

    private static int sCollectingLogCount;

    // ------------------------------------------------------------------ 缓存 RemoteTransition

    private static volatile Object sLastRt;
    private static volatile long sLastRtAt;

    /**
     * 记住"属于负一屏的那个 RemoteTransition"。
     *
     * 为什么要缓存：实测 `requestViewScaleRemoteClose` 拿到的 Intent 上
     * `hasExtra("view_scale_open") == false`（应用侧明明写了），
     * 于是 `requestCloseTransitionIfNeeded(tc, wc, null)` 建不出关闭过渡 → 退出硬切。
     * 我们在 system_server 里已经见过这个对象（`isHyperViewScaleRemote` 每次启动都会拿到），
     * 留着就能补回去。
     */
    private static void rememberRemoteTransition(Object rt) {
        if (rt == null) {
            return;
        }
        if (sLastRt != rt) {
            sLastRt = rt;
            L.i("[HVS服务端] 已缓存负一屏的 RemoteTransition: " + debugName(rt));
        }
        sLastRtAt = android.os.SystemClock.uptimeMillis();
    }

    private static Object freshRemoteTransition() {
        Object rt = sLastRt;
        if (rt == null) {
            return null;
        }
        long age = android.os.SystemClock.uptimeMillis() - sLastRtAt;
        return age <= Config.HVS_CLOSE_EXTRA_TTL_MS ? rt : null;
    }

    private static String debugName(Object rt) {        if (rt == null) {
            return "null";
        }
        try {
            return String.valueOf(XposedHelpers.callMethod(rt, "getDebugName"));
        } catch (Throwable t) {
            return "<?>";
        }
    }

    private static void post(Runnable r, long delayMs) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(r, delayMs);
        } catch (Throwable t) {
            L.w("[HVS服务端] post 失败: " + t);
        }
    }
}
