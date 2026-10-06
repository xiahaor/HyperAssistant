package com.qianxia.hyperassistant;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * v4 入口（libxposed API 102）。
 *
 * <p>本类的职责**只有转发**：
 * <ul>
 *   <li>{@code onModuleLoaded} 注入 {@link XpBridge} 的上下文（API102 的 XposedInterface + 进程名）；</li>
 *   <li>{@code onSystemServerStarting} / {@code onPackageLoaded} 合成一个 API82 风格的
 *       {@link XC_LoadPackage.LoadPackageParam}，交给主线版的 {@link MainHook} ——
 *       于是 v1 那套逻辑（含 system_server 提权钩子）原样复用。</li>
 * </ul>
 * 真正干活的是 {@link MainHook} 及其调用的 AssistantAnimCore/HvsDiagnostics/SystemServerHooks。
 */
public class Entry extends XposedModule {

    /** 同一个 ClassLoader 只分发一次（不同 API 回调可能重复触发）。 */
    private static final Set<ClassLoader> DONE =
            Collections.newSetFromMap(new ConcurrentHashMap<ClassLoader, Boolean>());

    private static volatile String sProc = "?";

    /** 本进程的模块实例（Bridge 需要它来打日志/注册接收器）。 */
    private static volatile io.github.libxposed.api.XposedModule sSelf;

    /** 桥只在"接收器所在的那个包"里启动（与模块作用域一致）。 */
    private static final String HOST_PKG = "com.miui.personalassistant";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        XpBridge.set(this);
        sSelf = this;
        try {
            sProc = param.getProcessName();
            XpBridge.setProcess(sProc);
            XpBridge.log("V4 模块已加载 · process=" + sProc + " · isSystemServer=" + param.isSystemServer());
            // ★ 阶段3：先按落盘配置恢复一次（没有文件则保持源码默认值）
            RtCfg.loadIfDue();
            XpBridge.log("V4 运行期配置 · " + RtCfg.describe());
        } catch (Throwable t) {
            XpBridge.log("onModuleLoaded 异常: " + t);
        }
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        XpBridge.set(this);
        try {
            dispatch(Targets.SYSTEM_SERVER_PKG, param.getClassLoader(), sProc);
        } catch (Throwable t) {
            XpBridge.log("onSystemServerStarting 异常: " + t);
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        XpBridge.set(this);
        try {
            dispatch(param.getPackageName(), param.getDefaultClassLoader(), sProc);
            // ★ 激活状态链路：负一屏进程里启动 PING/PONG 桥（App 侧据此判绿/判红）
            startBridge(param.getDefaultClassLoader(), param.getPackageName());
        } catch (Throwable t) {
            XpBridge.log("onPackageLoaded 异常: " + t);
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        XpBridge.set(this);
        try {
            // 早到路径：首次 onPackageLoaded 时 Application 可能还没建好 ⇒ 这里再试一次（Bridge 内部幂等）
            startBridge(param.getClassLoader(), param.getPackageName());
        } catch (Throwable t) {
            XpBridge.log("onPackageReady 异常: " + t);
        }
    }

    /**
     * 启动激活状态桥（v3 同款链路）：注册 PING 接收器、收到就回 PONG + alive/pid/ts。
     *
     * <p>只在 {@code com.miui.personalassistant} 且非 uid 1000 时启动 —— 与 v3 的
     * {@code Entry.startBridge} 判据一致（system_server 里不启动，避免无谓的接收器）。
     */
    private static void startBridge(ClassLoader cl, String pkg) {
        try {
            if (!HOST_PKG.equals(pkg)) {
                return;
            }
            if (android.os.Process.myUid() == 1000) {
                XpBridge.log("桥：uid=1000 ⇒ 不启动");
                return;
            }
            final io.github.libxposed.api.XposedModule mod = sSelf;
            if (mod == null) {
                XpBridge.log("桥：模块实例为空 ⇒ 跳过");
                return;
            }
            Bridge.install(mod, cl);
        } catch (Throwable t) {
            XpBridge.log("桥：启动失败（已吞）: " + t);
        }
    }

    /**
     * v4 占位：v3 的 {@code Bridge} 心跳里会调它（原本用于 dump ViewScale 信息）。
     * 该诊断属于"要删掉的探针"，这里先留空实现保证编译与调用链完整，清理阶段一并移除。
     */
    public static void dumpVsInfoOnce() {
    }

    private static void dispatch(String pkg, ClassLoader cl, String proc) {
        if (pkg == null || cl == null) {
            return;
        }
        if (!DONE.add(cl)) {
            XpBridge.log("V4 该 ClassLoader 已分发过 ⇒ 跳过: " + pkg);
            return;
        }
        try {
            final XC_LoadPackage.LoadPackageParam lp = new XC_LoadPackage.LoadPackageParam(pkg, proc, cl);
            new MainHook().handleLoadPackage(lp);
            XpBridge.log("V4 已分发: pkg=" + pkg + " proc=" + proc);
        } catch (Throwable t) {
            XpBridge.log("V4 分发失败 " + pkg + " : " + t);
        }
    }
}
