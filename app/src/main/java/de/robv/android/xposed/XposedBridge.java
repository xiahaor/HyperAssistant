package de.robv.android.xposed;

import com.qianxia.hyperassistant.XpBridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

/** v4 兼容垫片：API82 的 {@code XposedBridge} 静态面（只实现宿主真正用到的那几个）。 */
public final class XposedBridge {

    private XposedBridge() {
    }

    public static void log(String text) {
        XpBridge.log(text);
    }

    public static void log(Throwable t) {
        XpBridge.log(t);
    }

    public static XC_MethodHook.Unhook hookMethod(Member m, XC_MethodHook cb) {
        return XpBridge.hookExecutable((java.lang.reflect.Executable) m, cb);
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook cb) {
        final Set<XC_MethodHook.Unhook> out = new LinkedHashSet<XC_MethodHook.Unhook>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.isSynthetic() || !m.getName().equals(methodName)) {
                    continue;
                }
                final XC_MethodHook.Unhook u = XpBridge.hookExecutable(m, cb);
                if (u != null) {
                    out.add(u);
                }
            }
        }
        return out;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> clazz, XC_MethodHook cb) {
        final Set<XC_MethodHook.Unhook> out = new LinkedHashSet<XC_MethodHook.Unhook>();
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            final XC_MethodHook.Unhook u = XpBridge.hookExecutable(ctor, cb);
            if (u != null) {
                out.add(u);
            }
        }
        return out;
    }
}
