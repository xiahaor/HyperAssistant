package com.qianxia.hyperassistant;

import android.content.Context;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反射工具：<b>只读</b>。
 *
 * <p>铁律「不许猜」的落地方式：目标包的类被 R8/JADX 改过名（例如
 * {@code com.miui.launcher.overlay.server.pane.p} 里的字段在 dex 里就叫 {@code a}/{@code b}/{@code c}），
 * 所以这里<b>一律按类型找、不按字段名猜</b>；方法名只在反编译树里读到之后才用。
 */
final class Reflect {

    /**
     * 允许向下搜索的类名前缀（只搜负一屏自己的类，绝不进 android./java./androidx. 的对象图，
     * 以免碰到 Context/Handler 这类大对象）。
     */
    private static final String[] ALLOW = {
            "com.miui.launcher.overlay.server.pane",
            "com.miui.personalassistant",
    };

    /** 一次 BFS 最多看多少个对象（防止意外大图）。 */
    private static final int BUDGET = 256;
    private static final int MAX_DEPTH = 4;

    private Reflect() {
    }

    // ------------------------------------------------------------------ 描述

    /**
     * ★ 修复 #4：方法签名描述串缓存。
     *
     * <p>{@link #describe(Method)} 原来在<b>逐帧</b>回调里被调用（{@code ProbeAssist.java:175}），
     * 每次都要 {@code getParameterTypes()} + 拼串。同一个被 hook 的方法在整段动画里是同一个
     * {@link Method} 对象 ⇒ 结果恒定，只解析一次即可。<b>输出字符串逐字不变。</b>
     */
    private static final ConcurrentHashMap<Method, String> DESCRIBE_CACHE =
            new ConcurrentHashMap<Method, String>();

    /** 方法签名描述，用于日志里的 {@code src=} 来源标记。 */
    static String describe(Method m) {
        if (m == null) {
            return "?";
        }
        String cached = DESCRIBE_CACHE.get(m);
        if (cached != null) {
            return cached;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(m.getDeclaringClass().getName()).append('#').append(m.getName()).append('(');
        Class<?>[] pt = m.getParameterTypes();
        for (int i = 0; i < pt.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(simple(pt[i]));
        }
        String s = sb.append(')').toString();
        try {
            DESCRIBE_CACHE.put(m, s);
        } catch (Throwable ignored) {
            // 缓存失败不影响返回值
        }
        return s;
    }

    static String simple(Class<?> c) {
        if (c == null) {
            return "?";
        }
        if (c.isArray()) {
            return simple(c.getComponentType()) + "[]";
        }
        if (c.isPrimitive()) {
            return c.getName();
        }
        String n = c.getName();
        int i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(i + 1);
    }

    static String types(Class<?>[] pt) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pt.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(simple(pt[i]));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 取字段

    /** 在 obj 自身与其父类里按**精确类型**找第一个实例字段；找不到返回 null。 */
    static Field fieldOfType(Object obj, Class<?> type) {
        if (obj == null) {
            return null;
        }
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    if (!type.equals(f.getType())) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                    } catch (Throwable ignored) {
                    }
                    return f;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static Object get(Field f, Object obj) {
        if (f == null || obj == null) {
            return null;
        }
        try {
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 只读：列出 obj 自己/父类里所有「声明类型 == type」的实例字段的**非空值**。
     * 用于"{@code SlidingPaneStateManager} 里两个 h 字段分别是什么"这种对照，
     * 不依赖（也猜不到）混淆后的字段名。
     */
    static java.util.List<Object> valuesOfType(Object obj, Class<?> type) {
        java.util.ArrayList<Object> out = new java.util.ArrayList<>(4);
        if (obj == null || type == null) {
            return out;
        }
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || !type.equals(f.getType())) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v != null) {
                            out.add(v);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 读一个 float 字段（按名字；读不到返回 NaN）。 */
    static float readFloat(Object obj, String name) {
        if (obj == null) {
            return Float.NaN;
        }
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.getFloat(obj);
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return Float.NaN;
    }

    /**
     * ★ 修复 #4：把"按名字找字段"单独抽出来，调用方可以<b>只解析一次并缓存 Field</b>
     * （热路径上就只剩 {@code Field.getFloat}，不再有 {@code getDeclaredField + setAccessible}）。
     *
     * <p>只读：返回的 {@link Field} 已 {@code setAccessible(true)}；找不到返回 null。
     */
    static Field findField(Object obj, String name) {
        if (obj == null || name == null) {
            return null;
        }
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 读一个 boolean 字段（按名字；读不到返回 null）。 */
    static Boolean readBoolean(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.getBoolean(obj);
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 按「名字 + 形参数 + 第 0 个形参类型」找方法（**不猜完整签名**，兼容 ROM 差异）。
     *
     * @param argc   -1 表示任意形参数
     * @param param0 null 表示不检查第 0 个形参类型
     * @return 第一个匹配的方法；找不到返回 null
     */
    static Method findMethod(Class<?> cls, String name, int argc, Class<?> param0) {
        if (cls == null || name == null) {
            return null;
        }
        try {
            for (Method m : cls.getDeclaredMethods()) {
                if (!name.equals(m.getName())) {
                    continue;
                }
                Class<?>[] pt = m.getParameterTypes();
                if (argc >= 0 && pt.length != argc) {
                    continue;
                }
                if (param0 != null && (pt.length == 0 || !param0.equals(pt[0]))) {
                    continue;
                }
                return m;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ 调方法（只读）

    /** 调一个无参 public 方法（按名字，例如混淆后的 {@code h#a()}）；失败返回 null。 */
    static Object callNoArg(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        try {
            Method m = obj.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 找 View

    /**
     * 在 obj 的对象图里找一个 {@link View}（**只读**，BFS，按字段类型/运行期类型，不猜字段名）。
     *
     * <p>为什么需要它：{@code pane.p} 的字段名在 dex 里是 {@code a}（JADX 显示成 f11183a），
     * 猜名字必然踩空；而"它持有一个被 setTranslationX 的 View"是反编译树里读到的确定事实
     * （{@code p.java:144} 唯一写入点 {@code this.f11183a.setTranslationX(f9)}）。
     */
    static View findView(Object root) {
        if (root == null) {
            return null;
        }
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<Node> q = new ArrayDeque<>();
        seen.put(root, Boolean.TRUE);
        q.add(new Node(root, 0));
        int budget = BUDGET;
        while (!q.isEmpty() && budget-- > 0) {
            Node n = q.pollFirst();
            Object o = n.value;
            if (o == null) {
                continue;
            }
            if (o instanceof View) {
                return (View) o;
            }
            Class<?> c = o.getClass();
            if (c.isArray()) {
                try {
                    for (Object e : (Object[]) o) {
                        if (e != null && seen.put(e, Boolean.TRUE) == null) {
                            q.add(new Node(e, n.depth + 1));
                        }
                    }
                } catch (Throwable ignored) {
                }
                continue;
            }
            if (n.depth >= MAX_DEPTH || !allowed(c)) {
                continue;
            }
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                Field[] fs;
                try {
                    fs = k.getDeclaredFields();
                } catch (Throwable t) {
                    continue;
                }
                for (Field f : fs) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    Object v;
                    try {
                        f.setAccessible(true);
                        v = f.get(o);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (v == null || seen.put(v, Boolean.TRUE) != null) {
                        continue;
                    }
                    if (v instanceof View) {
                        return (View) v;                 // ★ 直接命中
                    }
                    if (allowed(v.getClass())) {
                        q.add(new Node(v, n.depth + 1));
                    }
                }
            }
        }
        return null;
    }

    /** BFS 节点（对象 + 已展开深度）。 */
    private static final class Node {
        final Object value;
        final int depth;

        Node(Object value, int depth) {
            this.value = value;
            this.depth = depth;
        }
    }

    private static boolean allowed(Class<?> c) {
        if (c == null) {
            return false;
        }
        String n = c.getName();
        for (String p : ALLOW) {
            if (n.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 进程级 Context（心跳用）

    /**
     * 反射 {@code android.app.ActivityThread.currentApplication()}，拿"本进程的 Application"。
     *
     * <p><b>符号已核实（不是猜）</b>：
     * <ul>
     *   <li>公开 {@code android.jar}（android-37）里<b>没有</b>这个类（{@code ActivityThread} 是 @hide）⇒
     *       所以只能反射，源码里也绝不能 import；</li>
     *   <li>设备 framework 的真身里<b>存在</b>该静态方法：
     *       {@code .scan/framework/framework.jar!classes.dex} 反汇编
     *       {@code android.app.ActivityThread.currentApplication:()Landroid/app/Application;}；</li>
     *   <li>同工作区的第三方模块（{@code .scan/thirdparty/swipegate/.../SystemUiBridgeModule.java:244-254}）
     *       用的就是这一条反射路径。</li>
     * </ul>
     *
     * @return 本进程的 Application；<b>取不到一律返回 null</b>（Application 还没建好、
     *         或隐藏 API 限制）。调用方必须容忍 null 并降级，<b>不许抛</b>。
     */
    static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Object v = m.invoke(null);
            return v instanceof Context ? (Context) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 调用栈

    /** 调用栈深度（只在 {@code AnimCfg.stack=true} 时调用，有开销）。 */
    static int depth() {
        try {
            return new Throwable().getStackTrace().length;
        } catch (Throwable t) {
            return -1;
        }
    }
}
