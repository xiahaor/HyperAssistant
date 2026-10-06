package com.qianxia.hyperassistant;

import de.robv.android.xposed.XC_MethodHook;

import java.lang.reflect.Executable;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * v4：API102 ⟷ API82 垫片的**上下文桥**。
 *
 * <p>职责只有两件：
 * <ol>
 *   <li>持有当前进程的 {@link XposedInterface}（由 {@link Entry} 在 onModuleLoaded 时注入）；
 *   <li>把 API82 风格的 {@link XC_MethodHook} 适配成 API102 的
 *       {@link XposedInterface.Hooker}（before/after + setResult/returnEarly 语义对齐）。
 * </ol>
 * 有了它，主线版（v1）那 31 个文件**一行都不用改**。
 */
public final class XpBridge {

    private static volatile XposedInterface sXp;
    private static volatile String sProc = "?";
    private static final AtomicInteger CNT = new AtomicInteger();
    private static final AtomicInteger HOOK_FAIL = new AtomicInteger();

    private XpBridge() {
    }

    public static void set(XposedInterface xp) {
        sXp = xp;
    }

    public static XposedInterface xp() {
        return sXp;
    }

    public static void setProcess(String p) {
        if (p != null) {
            sProc = p;
        }
    }

    public static String process() {
        return sProc;
    }

    public static void log(String msg) {
        final XposedInterface xp = sXp;
        final String line = "[" + sProc + "] " + msg;
        if (xp != null) {
            try {
                xp.log(4, "V4", line);
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            android.util.Log.i("AssistantAnimV4", line);
        } catch (Throwable ignored) {
        }
    }

    public static void log(Throwable t) {
        log(String.valueOf(t));
    }

    /**
     * 把一次 API82 风格的挂钩，装成 API102 的 hook。
     *
     * @return 可用于解钩的 Unhook；装不上返回 null（并记一条失败日志，绝不静默）
     */
    public static XC_MethodHook.Unhook hookExecutable(final Executable ex, final XC_MethodHook cb) {
        final XposedInterface xp = sXp;
        if (xp == null) {
            log("hookExecutable: XposedInterface 还没注入 ⇒ 跳过 " + ex);
            return null;
        }
        if (ex == null || cb == null) {
            return null;
        }
        try {
            xp.hook(ex)
                    .setId("v4-" + CNT.incrementAndGet() + "-" + ex.getName())
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            return dispatch(chain, cb);
                        }
                    });
        } catch (Throwable t) {
            final int n = HOOK_FAIL.incrementAndGet();
            if (n <= 20) {
                log("挂钩失败(" + n + ") " + ex + " : " + t);
            }
            return null;
        }
        return cb.new Unhook(ex);
    }

    /** 单次调用的适配：before →（可能跳过原方法）→ proceed → after。 */
    private static Object dispatch(XposedInterface.Chain chain, XC_MethodHook cb) throws Throwable {
        final XC_MethodHook.MethodHookParam p = new XC_MethodHook.MethodHookParam();
        p.method = chain.getExecutable();
        p.thisObject = chain.getThisObject();
        final List<Object> in = chain.getArgs();
        p.args = (in == null) ? new Object[0] : in.toArray();

        try {
            cb.v4CallBefore(p);
        } catch (Throwable t) {
            log("before 抛异常 " + p.method + " : " + t);
        }
        if (p.returnEarly) {
            if (p.v4Throwable() != null) {
                throw p.v4Throwable();
            }
            return p.v4Result();
        }

        // 进入原方法前复位，便于区分 after 阶段的 setResult
        p.v4ClearEarly();

        final Object ret;
        try {
            ret = chain.proceed(p.args);
        } catch (Throwable t) {
            p.v4SetThrowable(t);
            try {
                cb.v4CallAfter(p);
            } catch (Throwable ignored) {
            }
            if (p.returnEarly) {
                if (p.v4Throwable() != null) {
                    throw p.v4Throwable();
                }
                return p.v4Result();
            }
            throw t;
        }

        p.v4SetResult(ret);
        try {
            cb.v4CallAfter(p);
        } catch (Throwable t) {
            log("after 抛异常 " + p.method + " : " + t);
        }
        if (p.returnEarly) {
            if (p.v4Throwable() != null) {
                throw p.v4Throwable();
            }
            return p.v4Result();
        }
        return ret;
    }
}
