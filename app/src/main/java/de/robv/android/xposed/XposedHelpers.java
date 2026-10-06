package de.robv.android.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * v4 兼容垫片：API82 的 {@code XposedHelpers}。
 * 只实现宿主真正用到的方法：findClass / callMethod / callStaticMethod / getObjectField / findAndHookMethod。
 * 纯反射实现（不依赖任何 Xposed 运行期），因此行为可控、可日志化。
 */
public final class XposedHelpers {

    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader cl) throws ClassNotFoundException {
        if (cl != null) {
            return Class.forName(className, false, cl);
        }
        return Class.forName(className);
    }

    public static Class<?> findClass(String className) throws ClassNotFoundException {
        return Class.forName(className);
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        if (obj == null) {
            throw new NullPointerException("callMethod: obj == null, method=" + methodName);
        }
        final Method m = findMethodBestMatch(obj.getClass(), methodName, args);
        if (m == null) {
            throw new NoSuchMethodError(obj.getClass().getName() + "." + methodName
                    + "(" + describe(args) + ")");
        }
        try {
            m.setAccessible(true);
            return m.invoke(obj, args);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (Throwable t) {
            throw new RuntimeException("callMethod failed: " + m, t);
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        final Method m = findMethodBestMatch(clazz, methodName, args);
        if (m == null) {
            throw new NoSuchMethodError(clazz.getName() + "." + methodName + "(" + describe(args) + ")");
        }
        try {
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (Throwable t) {
            throw new RuntimeException("callStaticMethod failed: " + m, t);
        }
    }

    public static Object getObjectField(Object obj, String fieldName) {
        if (obj == null) {
            throw new NullPointerException("getObjectField: obj == null, field=" + fieldName);
        }
        final Field f = findField(obj.getClass(), fieldName);
        if (f == null) {
            throw new NoSuchFieldError(obj.getClass().getName() + "." + fieldName);
        }
        try {
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) {
            throw new RuntimeException("getObjectField failed: " + f, t);
        }
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        final Object v = getObjectField(obj, fieldName);
        return (v instanceof Boolean) && (Boolean) v;
    }

    public static int getIntField(Object obj, String fieldName) {
        final Object v = getObjectField(obj, fieldName);
        return (v instanceof Number) ? ((Number) v).intValue() : 0;
    }

    public static long getLongField(Object obj, String fieldName) {
        final Object v = getObjectField(obj, fieldName);
        return (v instanceof Number) ? ((Number) v).longValue() : 0L;
    }

    public static float getFloatField(Object obj, String fieldName) {
        final Object v = getObjectField(obj, fieldName);
        return (v instanceof Number) ? ((Number) v).floatValue() : 0f;
    }

    public static void setFloatField(Object obj, String fieldName, float value) {
        setFieldValue(obj, fieldName, Float.valueOf(value));
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        setFieldValue(obj, fieldName, Integer.valueOf(value));
    }

    public static void setBooleanField(Object obj, String fieldName, boolean value) {
        setFieldValue(obj, fieldName, Boolean.valueOf(value));
    }

    public static void setLongField(Object obj, String fieldName, long value) {
        setFieldValue(obj, fieldName, Long.valueOf(value));
    }

    private static void setFieldValue(Object obj, String fieldName, Object value) {
        if (obj == null) {
            throw new NullPointerException("set " + fieldName + ": obj == null");
        }
        final Field f = findField(obj.getClass(), fieldName);
        if (f == null) {
            throw new NoSuchFieldError(obj.getClass().getName() + "." + fieldName);
        }
        try {
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Throwable t) {
            throw new RuntimeException("set " + fieldName + " failed", t);
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        final Field f = findField(obj.getClass(), fieldName);
        if (f == null) {
            throw new NoSuchFieldError(obj.getClass().getName() + "." + fieldName);
        }
        try {
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Throwable t) {
            throw new RuntimeException("setObjectField failed: " + f, t);
        }
    }

    /** 形如 (Class<?> clazz, String name, Class<?> p1, Class<?> p2, XC_MethodHook cb)。 */
    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName,
            Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("no callback passed to findAndHookMethod");
        }
        final XC_MethodHook cb =
                (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        final Class<?>[] types = new Class<?>[parameterTypesAndCallback.length - 1];
        for (int i = 0; i < types.length; i++) {
            types[i] = (Class<?>) parameterTypesAndCallback[i];
        }
        Method target = null;
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                target = c.getDeclaredMethod(methodName, types);
                break;
            } catch (NoSuchMethodException ignored) {
            }
        }
        if (target == null) {
            throw new NoSuchMethodError(clazz.getName() + "." + methodName);
        }
        return com.qianxia.hyperassistant.XpBridge.hookExecutable(target, cb);
    }

    // ---------------------------------------------------------------- 内部

    private static Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /**
     * 按"参数个数 + 可赋值性"挑最匹配的方法（Xposed 的语义），
     * 另外兼容 Xposed 的"传 Class 当显式参数类型"用法。
     */
    private static Method findMethodBestMatch(Class<?> clazz, String name, Object[] args) {
        final int n = (args == null) ? 0 : args.length;
        Method best = null;
        int bestScore = -1;
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.isSynthetic() || !m.getName().equals(name)) {
                    continue;
                }
                if (m.getParameterTypes().length != n) {
                    continue;
                }
                final int score = score(m.getParameterTypes(), args);
                if (score > bestScore) {
                    bestScore = score;
                    best = m;
                }
            }
        }
        return best;
    }

    private static int score(Class<?>[] types, Object[] args) {
        int s = 0;
        for (int i = 0; i < types.length; i++) {
            final Class<?> t = types[i];
            final Object a = (args == null) ? null : args[i];
            if (a == null) {
                if (t.isPrimitive()) {
                    return -1;      // null 不能喂给基本类型
                }
                s += 1;
            } else if (a instanceof Class && !t.isInstance(a)) {
                // Xposed 允许用 Class 指定参数类型
                if (t == a) {
                    s += 4;
                } else {
                    return -1;
                }
            } else if (t.isInstance(a)) {
                s += 3;
            } else if (isBoxedMatch(t, a.getClass())) {
                s += 3;
            } else if (t.isPrimitive() || Number.class.isAssignableFrom(t)) {
                s += 0;             // 数值类可能被强转，弱匹配
            } else {
                return -1;
            }
        }
        return s;
    }

    private static boolean isBoxedMatch(Class<?> prim, Class<?> boxed) {
        return (prim == int.class && boxed == Integer.class)
                || (prim == long.class && boxed == Long.class)
                || (prim == boolean.class && boxed == Boolean.class)
                || (prim == float.class && boxed == Float.class)
                || (prim == double.class && boxed == Double.class)
                || (prim == short.class && boxed == Short.class)
                || (prim == byte.class && boxed == Byte.class)
                || (prim == char.class && boxed == Character.class);
    }

    private static String describe(Object[] args) {
        if (args == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
        }
        return sb.toString();
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException) {
            return (RuntimeException) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        return new RuntimeException(t);
    }

    /** 供宿主直接改字段（原 API82 也有）。 */
    public static void setAdditionalInstanceField(Object obj, String key, Object value) {
    }

    public static Object getAdditionalInstanceField(Object obj, String key) {
        return null;
    }

    public static boolean isStatic(Method m) {
        return Modifier.isStatic(m.getModifiers());
    }
}
