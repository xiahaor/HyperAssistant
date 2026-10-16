package com.qianxia.hyperassistant;

import de.robv.android.xposed.XposedHelpers;

/**
 * SystemUI 进程侧 hook —— 绕过 OS4.0.2.0 新增的"Task 级过渡跳过 HyperViewScale"规则。
 *
 * <p><b>根因（OS4.0.2.0 vs OS4.0.0.33 反编译对照，附录 §HW9）：</b><br>
 * SystemUI 的 {@code Miui-WindowManager-Shell.jar}（MD5 c0cf7694…）里
 * {@code RemoteTransitionHandlerStubImpl.shouldAbortDispatch} 新增逻辑
 * （新 jar 字节码 37-51，旧 jar 无此段）：
 * <pre>
 * for (Change change : info.getChanges()) {
 *     if (change.getTaskInfo() != null) {  // Task 级过渡
 *         Log.d("Skip handler HyperViewScaleRemoteTransition for task anim");
 *         return true;  // 中止远程过渡
 *     }
 * }
 * </pre>
 * 负一屏打开应用是 Task 级过渡 ⇒ 被拦截 ⇒ system_server 不回调 setMaskSurface
 * ⇒ 模块拿不到官方引擎数据 ⇒ 打开动画失效（真机日志见 §HW2：Skip … for task anim ×2）。
 *
 * <p><b>修复策略：</b>hook {@code shouldAbortDispatch}，当且仅当
 * {@code RemoteTransition.getDebugName() == "view_scale_remote_transition"}
 * （该常量已从新旧两版 dex 的 const-string 指令实证，§HW9）
 * <b>且</b> TransitionInfo 里确实存在 Task 级 change 时，返回 false（放行）。
 * 其余情况一律交给原逻辑 —— 不影响任何其它远程过渡。
 *
 * <p><b>兼容性：</b>用 {@link HookKit#hookAll}，找不到类只记 WARN 不抛异常；
 * 旧版系统（OS4.0.0.33）该类/方法不存在 ⇒ hook FAIL 日志 + 无副作用。
 *
 * @since 2026-10-16 为 OS4.0.2.0 新增（附录 §HW10）
 */
public final class SystemUIHooks {

    private SystemUIHooks() {
    }

    private static final String TAG = "SystemUIHooks";
    private static final String CLS =
            "com.android.wm.shell.common.transition.RemoteTransitionHandlerStubImpl";
    private static final String METHOD = "shouldAbortDispatch";
    /** 与新旧两版 dex 里 const-string 常量一致（§HW9 实证）。 */
    private static final String DEBUG_NAME = "view_scale_remote_transition";

    /**
     * 仅在 {@code com.android.systemui} 进程调用（MainHook 分发）。
     */
    public static void install(ClassLoader cl) {
        if (!Config.ENABLE_SYSTEMUI_ABORT_BYPASS) {
            L.i(TAG + ": ENABLE_SYSTEMUI_ABORT_BYPASS=false，不安装");
            return;
        }
        HookKit.hookAll(cl, CLS, METHOD, new HookKit.Cb("systemui." + METHOD) {
            @Override
            protected void onBefore(de.robv.android.xposed.XC_MethodHook.MethodHookParam p) {
                try {
                    if (p.args == null || p.args.length < 2) {
                        return;
                    }
                    Object info = p.args[0];
                    Object rt = p.args[1];
                    if (info == null || rt == null) {
                        return;
                    }
                    String dn = (String) XposedHelpers.callMethod(rt, "getDebugName");
                    if (!DEBUG_NAME.equals(dn) || !hasTaskChange(info)) {
                        return;
                    }
                    p.setResult(Boolean.FALSE);
                    if (Config.SYSTEMUI_HOOK_VERBOSE) {
                        L.i(TAG + ": [绕过新规则] " + METHOD + " → false（放行 Task 级 HyperViewScale）");
                    }
                } catch (Throwable t) {
                    L.e(TAG + " onBefore 异常（已捕获，走原逻辑）", t);
                }
            }
        });
    }

    /** TransitionInfo 里有没有带 taskInfo 的 change（= 会被新规则拦的那类过渡）。 */
    private static boolean hasTaskChange(Object info) {
        java.util.List<?> changes = (java.util.List<?>) XposedHelpers.callMethod(info, "getChanges");
        if (changes == null) {
            return false;
        }
        for (Object change : changes) {
            if (XposedHelpers.callMethod(change, "getTaskInfo") != null) {
                return true;
            }
        }
        return false;
    }
}
