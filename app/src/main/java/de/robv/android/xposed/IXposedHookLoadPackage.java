package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** v4 兼容垫片：API82 的入口接口（由 Entry 在 API102 之上驱动）。 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
