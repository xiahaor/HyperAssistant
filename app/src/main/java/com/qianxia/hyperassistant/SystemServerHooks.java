package com.qianxia.hyperassistant;

/**
 * system_server 侧：把小米给"Hyper 视图缩放过渡"加的那道权限闸门，对负一屏打开。
 *
 * ===== 为什么必须动 system_server =====
 * 逆向 `services.jar` 找到了确切的检查点：
 * <pre>
 *   com.android.server.wm.SafeActivityOptions.checkPermissions(Intent, ActivityInfo,
 *           WindowProcessController, ActivityTaskSupervisor, ActivityOptions, int, int)
 *     └─ HyperViewScaleTransitionStub.getInstance()
 *        HyperViewScaleTransitionStub.isHyperViewScaleRemote(RemoteTransition, int)
 *        ActivityTaskManagerService.checkPermission(String, int, int)
 *        ... new SecurityException("... with remoteTransition")
 * </pre>
 * `HyperViewScaleTransitionStub` 是**小米自己往框架里加的类** —— 也就是说
 * 小米在框架层专门给这个动画加了一道权限闸门，桌面持有
 * `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS` 所以能过，负一屏连声明都没有。
 *
 * 常规提权手段都堵死：`pm grant` 只对 runtime 权限有效；
 * 改 privapp 白名单也没用，因为权限必须在应用自己的 manifest 里声明过，
 * 而负一屏的 manifest 改不了（会破坏系统签名）。
 * 所以只剩在 system_server 里放行这一条路。
 *
 * ===== 安全性设计 =====
 * 放行条件**极窄**，三个条件同时满足才生效：
 *   1. 权限名精确等于 {@link Config#REMOTE_ANIM_PERMISSION}
 *   2. 调用方 appId 等于 {@link Config#TARGET_APP_ID}（负一屏，默认 10143）
 *   3. 开关 {@link Config#ENABLE_SYSTEM_SERVER_HOOK} 打开
 * 其余任何权限检查一律原样放行，绝不干预。
 *
 * ⚠️ 风险提示：system_server 里出错的后果比应用进程严重得多（可能软重启）。
 *    所以这里全程 try/catch，且只做 `setResult(0)` 这一件事。
 */
public final class SystemServerHooks {

    private SystemServerHooks() {
    }

    private static volatile int logCount;

    public static void install(ClassLoader cl) {
        L.i("################################################");
        L.i("# system_server 侧安装（放行远程动画权限）");
        L.i("# 目标权限: " + Config.REMOTE_ANIM_PERMISSION);
        L.i("# 目标 appId: " + Config.TARGET_APP_ID);
        L.i("################################################");

        if (!Config.ENABLE_SYSTEM_SERVER_HOOK) {
            L.w("ENABLE_SYSTEM_SERVER_HOOK=false，不做任何事");
            return;
        }

        installPermissionBypass(cl);
        installPredicateFallback(cl);
    }

    /** 主手段：放行那一个权限检查。 */
    private static void installPermissionBypass(ClassLoader cl) {
        Class<?> atms = HookKit.findClass(cl, Targets.ATMS);
        if (atms == null) {
            L.w("[提权] 找不到 " + Targets.ATMS + "，主手段不可用");
            return;
        }
        HookKit.hookAll(atms, Targets.M.CHECK_PERMISSION, new HookKit.Cb("ATMS.checkPermission [提权]") {
            @Override
            protected void onBefore(MethodHookParam p) {
                if (p.args == null || p.args.length < 3) {
                    return;
                }
                Object perm = p.args[0];
                if (!(perm instanceof String)
                        || !Config.REMOTE_ANIM_PERMISSION.equals(perm)) {
                    return;     // 不是我们要放的那个权限，一律不管
                }
                Object uidObj = p.args[2];
                if (!(uidObj instanceof Integer)) {
                    return;
                }
                int uid = (Integer) uidObj;
                // appId = uid % PER_USER_RANGE(100000)。
                // 注意：UserHandle.getAppId() 是 @hide，公开 SDK 里用不了，所以自己算。
                if ((uid % 100000) != Config.TARGET_APP_ID) {
                    return;     // 不是负一屏，不管
                }
                // PERMISSION_GRANTED = 0
                p.setResult(0);
                if (logCount++ < 60) {
                    L.i("[提权] ✔ 已对 uid=" + uid + " 放行 " + perm
                            + "（第 " + logCount + " 次）");
                }
            }
        });
    }

    /**
     * 备选手段：让小米自己的判定 `isHyperViewScaleRemote` 返回 false，
     * 这样它连权限检查都不会走。
     *
     * 默认**不开** —— 因为这个判定可能同时被用来决定"要不要渲染这个动画"，
     * 返回 false 有把动画本身也关掉的风险。主手段失效时再试它。
     */
    private static void installPredicateFallback(ClassLoader cl) {
        if (!"predicate".equalsIgnoreCase(Config.SS_BYPASS_MODE)) {
            return;
        }
        Class<?> stub = HookKit.findClass(cl, Targets.HYPER_SCALE_STUB);
        if (stub == null) {
            L.w("[提权] 找不到 " + Targets.HYPER_SCALE_STUB);
            return;
        }
        HookKit.hookAll(stub, Targets.M.IS_HYPER_SCALE_REMOTE,
                new HookKit.Cb("HyperViewScaleTransitionStub.isHyperViewScaleRemote [提权]") {
                    @Override
                    protected void onAfter(MethodHookParam p) {
                        p.setResult(Boolean.FALSE);
                    }
                });
    }
}
