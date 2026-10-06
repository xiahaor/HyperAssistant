package com.qianxia.hyperassistant;

import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 安全 hook 包装。
 *
 * 两条铁律：
 *   1. 找不到类/方法只记日志，不抛异常（换个 ROM 版本不至于让宿主出问题）。
 *   2. 每个回调体都包 try/catch，hook 内部出错绝不影响宿主逻辑。
 */
public final class HookKit {

    private HookKit() {
    }

    /** 带标签的回调基类：自动 try/catch，异常只记日志。 */
    public abstract static class Cb extends XC_MethodHook {
        public final String label;

        public Cb(String label) {
            this.label = label;
        }

        @Override
        protected final void beforeHookedMethod(MethodHookParam param) {
            try {
                onBefore(param);
            } catch (Throwable t) {
                L.e("before " + label, t);
            }
        }

        @Override
        protected final void afterHookedMethod(MethodHookParam param) {
            try {
                onAfter(param);
            } catch (Throwable t) {
                L.e("after " + label, t);
            }
        }

        protected void onBefore(MethodHookParam param) throws Throwable {
        }

        protected void onAfter(MethodHookParam param) throws Throwable {
        }
    }

    /** 按精确签名 hook。paramTypesAndCallback 的最后一项必须是 XC_MethodHook。 */
    public static boolean hook(ClassLoader cl, String className, String methodName,
                               Object... paramTypesAndCallback) {
        String shortName = shortName(className);
        try {
            Class<?> c = XposedHelpers.findClass(className, cl);
            XposedHelpers.findAndHookMethod(c, methodName, paramTypesAndCallback);
            L.i("hook OK    " + shortName + "." + methodName);
            return true;
        } catch (Throwable t) {
            L.w("hook FAIL  " + shortName + "." + methodName + "  (" + t + ")");
            return false;
        }
    }

    /** hook 该名字的所有重载。用于签名不确定的方法。 */
    public static boolean hookAll(ClassLoader cl, String className, String methodName, XC_MethodHook cb) {
        String shortName = shortName(className);
        try {
            Class<?> c = XposedHelpers.findClass(className, cl);
            XposedBridge.hookAllMethods(c, methodName, cb);
            L.i("hookAll OK " + shortName + "." + methodName);
            return true;
        } catch (Throwable t) {
            L.w("hookAll FAIL " + shortName + "." + methodName + "  (" + t + ")");
            return false;
        }
    }

    /** 直接对已拿到的 Class 操作。 */
    public static boolean hookAll(Class<?> c, String methodName, XC_MethodHook cb) {
        try {
            XposedBridge.hookAllMethods(c, methodName, cb);
            L.i("hookAll OK " + c.getSimpleName() + "." + methodName);
            return true;
        } catch (Throwable t) {
            L.w("hookAll FAIL " + c.getSimpleName() + "." + methodName + "  (" + t + ")");
            return false;
        }
    }

    public static Class<?> findClass(ClassLoader cl, String name) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            L.w("findClass FAIL " + name + "  (" + t + ")");
            return null;
        }
    }

    /** 取第一个参数并当作 float；不是 float 则返回 NaN。 */
    public static float firstFloat(XC_MethodHook.MethodHookParam p) {
        if (p.args == null || p.args.length == 0) {
            return Float.NaN;
        }
        Object a = p.args[0];
        if (a instanceof Float) {
            return (Float) a;
        }
        if (a instanceof Number) {
            return ((Number) a).floatValue();
        }
        return Float.NaN;
    }

    public static View firstView(XC_MethodHook.MethodHookParam p) {
        if (p.args == null) {
            return null;
        }
        for (Object a : p.args) {
            if (a instanceof View) {
                return (View) a;
            }
        }
        return null;
    }

    public static String shortName(String className) {
        int i = className.lastIndexOf('.');
        return i < 0 ? className : className.substring(i + 1);
    }

    /** 打印 View 树，只调用一次。 */
    public static void dumpViewTree(View v, String prefix) {
        if (v == null) {
            L.i(prefix + "(null view)");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(prefix)
                .append(v.getClass().getName())
                .append(" id=").append(idName(v))
                .append(" size=").append(v.getWidth()).append("x").append(v.getHeight())
                .append(" alpha=").append(L.f(v.getAlpha()))
                .append(" scale=").append(L.f(v.getScaleX()))
                .append(" transX=").append(L.f(v.getTranslationX()))
                .append(" vis=").append(v.getVisibility());
        L.i(sb.toString());
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount() && i < 12; i++) {
                dumpViewTree(g.getChildAt(i), prefix + "  ");
            }
        }
    }

    private static String idName(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) {
                return "-";
            }
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "?";
        }
    }
}
