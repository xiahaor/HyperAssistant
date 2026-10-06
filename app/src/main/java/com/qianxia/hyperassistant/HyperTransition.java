package com.qianxia.hyperassistant;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XposedHelpers;

/**
 * ★★ 实现 MIUI 的 {@code android.window.IHyperRemoteTransition} —— "上滑回桌面飞回去"的正式通道。
 *
 * ===== 为什么必须走这条路 =====
 * 实测：`RemoteTransition.exitToHome` 设成 true **没有效果**（13 次全设成功，用户看到的
 * 仍是"从中间直接消失"）。真正被调用的只有 `hyperRemoteTransition` 那个槽 ——
 * MIUI 在 system_server 里这么用（`miui-services.jar`）：
 * <pre>
 *   WindowOrganizerControllerImpl.interceptKeyEventToLauncher()
 *     → markSysBarExitToHomeOnIntercept() → maybeMarkSysBarExitToHome()
 *   ActivityStarterImpl.setHyperTransition()            → RemoteTransition.getHyperRemoteTransition()
 *   ActivityStarterImpl.notifyHomeStartActivityFinish()  → getHyperRemoteTransition()
 *                                                          + 调它的 startActivityFinished(...)
 *   TransitionImpl.notifyHomeTransitionAbortIfNeeded()/abort() → getHyperRemoteTransition()
 * </pre>
 * 桌面的原生库里正是 `IRemoteTransitionImpl` + `IHyperRemoteTransitionImpl`
 * + `handleClosingRemoteTransitionInAppPage` / `takeOverFinishRemoteTransition`
 * —— **桌面的飞回图标就是它的 hyper transition**。
 *
 * ===== 接口本身极小（反汇编 framework.jar 确认）=====
 * <pre>
 *   interface IHyperRemoteTransition {                    // 只有 2 个方法
 *       void onTransitionAbort(int);
 *       void startActivityFinished(ActivityStartInfo, IHyperRemoteTransitionFinishedCallback);
 *   }
 *   interface IHyperRemoteTransitionFinishedCallback {
 *       void onTransitionFinished(int, boolean);
 *   }
 *   // IHyperRemoteTransition$Stub.onTransact 的读序：
 *   //   code 1 → onTransitionAbort(data.readInt())
 *   //   code 2 → startActivityFinished(data.readTypedObject(ActivityStartInfo.CREATOR),
 *   //                                  Stub.asInterface(data.readStrongBinder()))
 * </pre>
 *
 * ===== 为什么不直接 implements =====
 * `IHyperRemoteTransition` 是 MIUI 私有接口，公开 `android.jar` 里没有；
 * 设备上的 `framework.jar` 是 **dex 不是 class**（javac 编译不了）。
 * 所以：**自己写 `Binder.onTransact` 干活**，再用 `java.lang.reflect.Proxy`
 * 造一个接口实例去满足 `setHyperRemoteTransition(IHyperRemoteTransition)` 的类型要求。
 * 跨进程后系统拿到的是我们 binder 的 `Stub$Proxy`，调用会走回我们的 `onTransact`。
 */
public final class HyperTransition {

    private HyperTransition() {
    }

    static final String IFACE = "android.window.IHyperRemoteTransition";
    static final String DESC = "android.window.IHyperRemoteTransition";
    static final String CALLBACK_STUB =
            "android.window.IHyperRemoteTransitionFinishedCallback$Stub";
    static final String ACTIVITY_START_INFO = "android.window.ActivityStartInfo";

    private static final int CODE_ON_TRANSITION_ABORT = 1;
    private static final int CODE_START_ACTIVITY_FINISHED = 2;

    private static volatile Binder sBinder;
    private static volatile Object sProxy;
    private static int sLogCount;

    /** 造出可以塞进 `RemoteTransition.setHyperRemoteTransition(...)` 的接口实例。 */
    public static synchronized Object proxy(ClassLoader cl) {
        if (sProxy != null) {
            return sProxy;
        }
        try {
            final Class<?> iface = Class.forName(IFACE, false, cl);
            sBinder = new HyperBinder();
            sProxy = Proxy.newProxyInstance(cl, new Class<?>[]{iface}, new InvocationHandler() {
                @Override
                public Object invoke(Object p, Method m, Object[] a) {
                    // 系统只需要 asBinder() 拿去跨进程；真正干活的是 onTransact
                    if ("asBinder".equals(m.getName())) {
                        return sBinder;
                    }
                    if ("toString".equals(m.getName())) {
                        return "HyperTransition(proxy)";
                    }
                    L.i("[hyper过渡] 代理方法被直接调用: " + m.getName());
                    return null;
                }
            });
            L.i("[hyper过渡] 已创建 IHyperRemoteTransition 代理 + Binder");
            return sProxy;
        } catch (Throwable t) {
            L.e("[hyper过渡] 创建代理失败", t);
            return null;
        }
    }

    /** Binder：MIUI 在"回桌面"时会通过这里来叫我们。 */
    private static final class HyperBinder extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                if (code == CODE_ON_TRANSITION_ABORT) {
                    data.enforceInterface(DESC);
                    int arg = data.readInt();
                    L.i("[hyper过渡] ★ onTransitionAbort(" + arg + ") —— 过渡被中止");
                    return true;
                }
                if (code == CODE_START_ACTIVITY_FINISHED) {
                    data.enforceInterface(DESC);
                    Object info = readActivityStartInfo(data);
                    IBinder cbBinder = data.readStrongBinder();
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    L.i("[hyper过渡] ★★★ startActivityFinished 被调用！"
                            + " info=" + describe(info)
                            + "  callback=" + cbBinder);
                    onStartActivityFinished(info, cbBinder);
                    return true;
                }
            } catch (Throwable t) {
                L.e("[hyper过渡] onTransact 出错 code=" + code, t);
            }
            return false;
        }
    }

    /**
     * 读 `ActivityStartInfo`：它是 hidden 类，但 `CREATOR` 是 public static，
     * 而 `Parcel.readTypedObject(Creator)` 是公开 API —— 所以不用编译期依赖。
     */
    private static Object readActivityStartInfo(Parcel data) {
        try {
            Class<?> c = Class.forName(ACTIVITY_START_INFO);
            Object creator = c.getField("CREATOR").get(null);
            return data.readTypedObject((Parcelable.Creator<?>) creator);
        } catch (Throwable t) {
            L.w("[hyper过渡] 读 ActivityStartInfo 失败: " + t);
            return null;
        }
    }

    private static String describe(Object info) {
        if (info == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("{");
        for (String g : new String[]{"getPackageName", "isLaunchFromHome", "isLaunchSuccess",
                "getBounds", "getTaskId", "getHomeActivityLeash", "getHomeTaskLeash"}) {
            try {
                sb.append(g).append('=')
                        .append(XposedHelpers.callMethod(info, g)).append(' ');
            } catch (Throwable ignored) {
            }
        }
        return sb.append('}').toString();
    }

    /** 系统在"回桌面"完成时叫我们：播返场动画，播完再回调让它收尾。 */
    private static void onStartActivityFinished(Object info, IBinder cbBinder) {
        final Object cb = wrapCallback(cbBinder);
        // 用最后记下的"小部件位图 + 起点矩形"播一段缩回去的动画
        boolean started = ReturnFlight.playNow(new Runnable() {
            @Override
            public void run() {
                finishCallback(cb);
            }
        });
        if (!started) {
            L.w("[hyper过渡] 没有可用的返场素材（还没点过小部件？），立刻回调收尾");
            finishCallback(cb);
        }
    }

    private static Object wrapCallback(IBinder binder) {
        if (binder == null) {
            return null;
        }
        try {
            Class<?> stub = Class.forName(CALLBACK_STUB);
            return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        } catch (Throwable t) {
            L.w("[hyper过渡] 包装回调失败: " + t);
            return null;
        }
    }

    private static void finishCallback(Object cb) {
        if (cb == null) {
            L.w("[hyper过渡] 没有回调对象，无法通知系统收尾");
            return;
        }
        try {
            Method m = cb.getClass().getMethod("onTransitionFinished", int.class, boolean.class);
            m.setAccessible(true);
            m.invoke(cb, 0, Boolean.TRUE);
            L.i("[hyper过渡] 已回调 onTransitionFinished(0, true)");
        } catch (Throwable t) {
            L.e("[hyper过渡] 回调 onTransitionFinished 失败", t);
        }
    }
}
