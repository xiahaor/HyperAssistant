package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

/** v4 兼容垫片：API82 的 LoadPackageParam（字段与真 API82 同名，宿主代码无需改）。 */
public final class XC_LoadPackage {
    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
        public ApplicationInfo appInfo;

        public LoadPackageParam() {
        }

        public LoadPackageParam(String packageName, String processName, ClassLoader classLoader) {
            this.packageName = packageName;
            this.processName = processName;
            this.classLoader = classLoader;
            this.isFirstApplication = true;
        }
    }

    private XC_LoadPackage() {
    }
}
