package com.qianxia.hyperassistant;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * ★★ 方案 B 第一步：把官方桥返回的 {@code view_scale_open} 换成**我们自己的**
 * {@code android.window.RemoteTransition}，四个方法全部转发给 MIUI 的原对象。
 *
 * ===== 为什么换（方案 B 的动机）=====
 * 白框 / 黑屏 / 图标残留这一族问题的根因全在 MIUI 的遮罩管线上
 * （`drawViewOnCanvas` 把位图按原尺寸画在整块整屏 buffer 的 (0,0)、
 * `initLeashChild` 建 `_letterbox-bg` 整屏色块、`setLayer(MAX)` 把遮罩压在最上层、
 * 收尾时 `reset()` 释放 leash 导致之后无法反向……）。每修一层就露出下一层。
 * 与其继续在外面打补丁，不如**自己当那个 RemoteTransition**：
 * 遮罩 / letterbox / blur 一概不建，leash 的生命周期和打断决策都归我们。
 *
 * ===== 契约（反汇编 framework.jar 得到，逐条核对过）=====
 * <pre>
 *   interface IRemoteTransition {                       // 4 个方法，事务码 1~4
 *       void startAnimation(IBinder, TransitionInfo, SurfaceControl.Transaction,
 *                           IRemoteTransitionFinishedCallback);                     // code 1
 *       void mergeAnimation(IBinder, TransitionInfo, SurfaceControl.Transaction,
 *                           IBinder mergeTarget, IRemoteTransitionFinishedCallback); // code 2
 *       void onTransitionConsumed(IBinder, boolean);                                // code 3
 *       void takeOverAnimation(IBinder, TransitionInfo, SurfaceControl.Transaction,
 *                           IRemoteTransitionFinishedCallback, WindowAnimationState[]); // code 4
 *   }
 *   // IRemoteTransition$Stub.onTransact 的读序：
 *   //   code 1: readStrongBinder, readTypedObject(TransitionInfo.CREATOR),
 *   //           readTypedObject(SurfaceControl$Transaction.CREATOR), readStrongBinder -> cb
 *   //   code 2: 同上，但 cb 之前多一个 readStrongBinder = mergeTarget
 *   //   code 3: readStrongBinder, readBoolean
 *   //   code 4: 同 code 1，末尾多一个 createTypedArray(WindowAnimationState.CREATOR)
 *   //   descriptor = "android.window.IRemoteTransition"
 * </pre>
 *
 * ===== 为什么全用反射 =====
 * `IRemoteTransition` / `TransitionInfo` 都是 **hidden 类**，公开 android.jar 里没有
 * （编译期引用直接报 cannot find symbol，这是踩过的坑）。所以本类**只依赖公开类型**
 * （`IBinder` / `Parcel` / `Parcelable` / `Object`），其余全部按名字反射。
 * 实现方式：自己写 `Binder.onTransact` 干活，再用 `java.lang.reflect.Proxy`
 * 造一个接口实例去满足 `RemoteTransition(IRemoteTransition)` 的类型要求；
 * 跨进程后系统拿到我们 binder 的 `Stub$Proxy`，调用会走回我们的 `onTransact`。
 * 同一套手法在 {@link HyperTransition} 上已验证可用。
 *
 * ===== 本步（B1）只做替换、不改行为 =====
 * 四个方法**原样转发**给 MIUI 的原 `IRemoteTransition`，只加日志。
 * 验收：开/关动画与替换前完全一致 + 日志里出现 `[自有过渡] ★ 系统调我们的 startAnimation`。
 * 之后 B2 换 `startAnimation`（不建 mask/letterbox/blur，自己驱动 leash），
 * B3 换 `mergeAnimation`（真正的并行可打断 + 上滑返回）。
 */
public final class OwnRemoteTransition {

    private OwnRemoteTransition() {
    }

    static final String IFACE = "android.window.IRemoteTransition";
    static final String DESC = "android.window.IRemoteTransition";
    static final String CB_STUB = "android.window.IRemoteTransitionFinishedCallback$Stub";
    static final String RT_CLASS = "android.window.RemoteTransition";
    static final String INFO_CLASS = "android.window.TransitionInfo";
    static final String TXN_CLASS = "android.view.SurfaceControl$Transaction";

    private static final int CODE_START_ANIMATION = 1;
    private static final int CODE_MERGE_ANIMATION = 2;
    private static final int CODE_ON_TRANSITION_CONSUMED = 3;
    private static final int CODE_TAKE_OVER_ANIMATION = 4;

    /** MIUI 的原 `IRemoteTransition`（同进程对象），我们转发给它。 */
    private static volatile Object sTarget;
    private static volatile Binder sBinder;
    private static volatile Object sProxy;
    private static int sLogCount;

    /**
     * 紧急开关（不用重新打包就能关掉方案 B）：
     * <pre>
     *   adb shell touch /sdcard/Android/data/com.miui.personalassistant/files/hvs/own_rt_off
     * </pre>
     * 文件存在 → 不做替换，一切照 MIUI 原样。删掉文件 → 恢复。
     * 负一屏进程每次启动时检查，所以配合 force-stop 即可生效。
     */
    private static final String OFF_MARKER =
            "/sdcard/Android/data/com.miui.personalassistant/files/hvs/own_rt_off";

    private static boolean disabled() {
        try {
            return new java.io.File(OFF_MARKER).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 用 MIUI 的原 `RemoteTransition` 造一个我们自己的同款对象。
     *
     * @return 我们自己的 `RemoteTransition`；造不出来返回 null（调用方保持原对象，功能不受影响）
     */
    public static Object build(ClassLoader cl, Object originalRt) {
        try {
            if (disabled()) {
                L.w("[自有过渡] 检测到关闭标记 " + OFF_MARKER + " → 不替换，照 MIUI 原样跑");
                return null;
            }
            Object target = call(originalRt, "getRemoteTransition");
            if (target == null) {
                L.w("[自有过渡] 拿不到原 IRemoteTransition，放弃替换");
                return null;
            }
            sTarget = target;
            sBinder = new RtBinder();
            final Class<?> iface = Class.forName(IFACE, false, cl);
            sProxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object p, Method m, Object[] a) {
                            // 系统只需要 asBinder() 拿去跨进程；真正干活的是 onTransact
                            if ("asBinder".equals(m.getName())) {
                                return sBinder;
                            }
                            if ("toString".equals(m.getName())) {
                                return "OwnRemoteTransition(proxy)";
                            }
                            L.i("[自有过渡] 代理方法被直接调用: " + m.getName());
                            return null;
                        }
                    });

            Class<?> rtClass = Class.forName(RT_CLASS, false, cl);
            Object ours = rtClass.getConstructor(iface).newInstance(sProxy);

            // 字段照抄 MIUI 的原对象 —— 系统侧的门槛（debugName）和回桌面分支（exitToHome）都读这些
            copy(originalRt, ours, "getDebugName", "setDebugName");
            copy(originalRt, ours, "getAppThread", "setAppThread");
            copy(originalRt, ours, "getHyperRemoteTransition", "setHyperRemoteTransition");
            Object token = call(originalRt, "getHyperToken");
            if (token instanceof Long && ((Long) token) != 0L) {
                call1(ours, "setHyperToken", token);
            }
            call1(ours, "setExitToHome", Boolean.TRUE);

            L.i("[自有过渡] ★ 已造出我们自己的 RemoteTransition"
                    + "  debugName=" + call(ours, "getDebugName")
                    + "  exitToHome=" + call(ours, "isExitToHome")
                    + "  appThread=" + (call(ours, "getAppThread") != null)
                    + "  转发目标=" + target.getClass().getName());
            return ours;
        } catch (Throwable t) {
            L.e("[自有过渡] 构造失败（回退用 MIUI 原对象）", t);
            return null;
        }
    }

    /** Binder：系统对 `IRemoteTransition` 的四次调用都从这里进来。 */
    private static final class RtBinder extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                switch (code) {
                    case CODE_START_ANIMATION: {
                        data.enforceInterface(DESC);
                        IBinder transition = data.readStrongBinder();
                        Object info = readTyped(data, INFO_CLASS);
                        Object txn = readTyped(data, TXN_CLASS);
                        Object cb = readCallback(data);
                        if (reply != null) {
                            reply.writeNoException();
                        }
                        logCall("startAnimation", info, null, cb);
                        // B2：自己驱动 leash（不建 mask/letterbox/blur）。
                        // 接管失败就原样转发给 MIUI（保证功能不退化）。
                        if (Config.OUR_START_ANIMATION && ourStart(info, txn, cb)) {
                            return true;
                        }
                        forward("startAnimation", 4, transition, info, txn, cb);
                        return true;
                    }
                    case CODE_MERGE_ANIMATION: {
                        data.enforceInterface(DESC);
                        IBinder transition = data.readStrongBinder();
                        Object info = readTyped(data, INFO_CLASS);
                        Object txn = readTyped(data, TXN_CLASS);
                        IBinder mergeTarget = data.readStrongBinder();
                        Object cb = readCallback(data);
                        if (reply != null) {
                            reply.writeNoException();
                        }
                        logCall("mergeAnimation", info, mergeTarget, cb);
                        // B3：合并/打断自己处理（能反向就反向，否则立刻收尾）。
                        // 因为 startAnimation 由我们接管时 MIUI 那套状态机是没有状态的，
                        // 转发过去它也不会收尾 → 会卡过渡。
                        if (Config.OUR_START_ANIMATION && OurAnim.busy()
                                && OurAnim.mergeOrFinish(info, cb)) {
                            return true;
                        }
                        forward("mergeAnimation", 5, transition, info, txn, mergeTarget, cb);
                        return true;
                    }
                    case CODE_ON_TRANSITION_CONSUMED: {
                        data.enforceInterface(DESC);
                        IBinder transition = data.readStrongBinder();
                        boolean aborted = data.readBoolean();
                        if (reply != null) {
                            reply.writeNoException();
                        }
                        if (sLogCount++ < 200000) {
                            L.i("[自有过渡] onTransitionConsumed(aborted=" + aborted + ")");
                        }
                        forward("onTransitionConsumed", 2, transition, aborted);
                        return true;
                    }
                    case CODE_TAKE_OVER_ANIMATION: {
                        data.enforceInterface(DESC);
                        IBinder transition = data.readStrongBinder();
                        Object info = readTyped(data, INFO_CLASS);
                        Object txn = readTyped(data, TXN_CLASS);
                        Object cb = readCallback(data);
                        if (reply != null) {
                            reply.writeNoException();
                        }
                        // MIUI 自己的 takeOverAnimation 只有一行日志、什么都不做（反汇编确认），
                        // 所以这里也只记一笔、不转发（forward 找不到 5 参重载也无所谓）。
                        L.i("[自有过渡] takeOverAnimation 被调用（MIUI 实现本身也是空操作）"
                                + describe(info));
                        return true;
                    }
                    default:
                        return false;
                }
            } catch (Throwable t) {
                L.e("[自有过渡] onTransact 出错 code=" + code, t);
                return false;
            }
        }
    }

    /** 按名字读一个 hidden 的 Parcelable（用它的 public static CREATOR）。 */
    private static Object readTyped(Parcel data, String className) {
        try {
            Class<?> c = Class.forName(className);
            Object creator = c.getField("CREATOR").get(null);
            return data.readTypedObject((Parcelable.Creator<?>) creator);
        } catch (Throwable t) {
            L.w("[自有过渡] 读 " + className + " 失败: " + t);
            return null;
        }
    }

    /**
     * B2 的入口：把 MIUI 原对象上的 `mViewInfo`（官方桥在 `parseBundle` 里已经建好，
     * 含源视图、位图、半径）交给我们自己的动画。
     *
     * 为什么不自己建 `ViewInfo`：那需要复刻 `parseBundle` + `ViewInfo` 的一堆字段语义，
     * 而 MIUI 的对象就在手边（我们持有它并转发），直接读字段最省事也最准。
     */
    private static boolean ourStart(Object info, Object txn, Object cb) {
        try {
            Object viewInfo = fieldOf(sTarget, "mViewInfo");
            if (viewInfo == null) {
                L.w("[我们动画] 原对象上没有 mViewInfo，交回 MIUI");
                return false;
            }
            boolean ok = OurAnim.start(viewInfo, info, txn, cb);
            if (ok) {
                OurAnim.watchDog();
            }
            return ok;
        } catch (Throwable t) {
            L.e("[我们动画] 启动失败，交回 MIUI", t);
            return false;
        }
    }

    private static Object fieldOf(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** 转发给 MIUI 的原 `IRemoteTransition`（同进程对象，直接反射调）。 */    private static void forward(String name, int argc, Object... args) {
        Object target = sTarget;
        if (target == null) {
            L.w("[自有过渡] 没有转发目标，" + name + " 被丢弃");
            return;
        }
        try {
            Method m = findMethod(target.getClass(), name, argc);
            if (m == null) {
                L.w("[自有过渡] 原对象上找不到 " + name + "/" + argc);
                return;
            }
            m.setAccessible(true);
            m.invoke(target, args);
        } catch (Throwable t) {
            L.e("[自有过渡] 转发 " + name + " 失败", t);
        }
    }

    /**
     * 读回调：`IRemoteTransitionFinishedCallback` 是 hidden 接口，
     * 但它的 `$Stub.asInterface(IBinder)` 是 public static —— 反射调用即可。
     */
    private static Object readCallback(Parcel data) {
        try {
            IBinder b = data.readStrongBinder();
            Class<?> stub = Class.forName(CB_STUB);
            Method m = stub.getMethod("asInterface", IBinder.class);
            return m.invoke(null, b);
        } catch (Throwable t) {
            L.w("[自有过渡] 读回调失败: " + t);
            return null;
        }
    }

    private static String describe(Object info) {
        if (info == null) {
            return " info=null";
        }
        try {
            StringBuilder sb = new StringBuilder("  type=").append(call(info, "getType"))
                    .append(" id=").append(call(info, "getDebugId")).append(" [");
            Object changes = call(info, "getChanges");
            if (changes instanceof List) {
                for (Object c : (List<?>) changes) {
                    sb.append(call(c, "getMode")).append(' ');
                }
            }
            return sb.append(']').toString();
        } catch (Throwable t) {
            return " <?>";
        }
    }

    private static void logCall(String name, Object info, IBinder mergeTarget, Object cb) {
        if (sLogCount++ > 200000) {
            return;
        }
        L.i("[自有过渡] ★ 系统调我们的 " + name + describe(info)
                + (mergeTarget == null ? "" : "  mergeTarget=" + mergeTarget)
                + "  cb=" + (cb != null));
    }

    // ------------------------------------------------------------------ 反射小工具

    private static Object call(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        try {
            Method m = findMethod(obj.getClass(), name, 0);
            if (m == null) {
                return null;
            }
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object call1(Object obj, String name, Object arg) {
        if (obj == null) {
            return null;
        }
        try {
            Method m = findMethod(obj.getClass(), name, 1);
            if (m == null) {
                return null;
            }
            m.setAccessible(true);
            return m.invoke(obj, arg);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void copy(Object from, Object to, String getter, String setter) {
        Object v = call(from, getter);
        if (v != null) {
            call1(to, setter, v);
        }
    }

    private static Method findMethod(Class<?> c, String name, int argc) {
        Class<?> k = c;
        while (k != null) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argc) {
                    return m;
                }
            }
            k = k.getSuperclass();
        }
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == argc) {
                return m;
            }
        }
        return null;
    }
}
