package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * v4 兼容垫片：API82 的 {@code XC_MethodHook}。
 * 语义与真 API82 对齐：before 里 setResult 则跳过原方法；after 里 setResult 可改返回值。
 */
public abstract class XC_MethodHook {

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    /** 与 API82 同名同义。 */
    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        public boolean returnEarly;

        public Object getResult() {
            return result;
        }

        public void setResult(Object r) {
            result = r;
            returnEarly = true;
        }

        public void setResultAndSkip(Object r) {
            setResult(r);
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable t) {
            throwable = t;
            returnEarly = true;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public Object getObjectExtra(String key) {
            return null;
        }

        // ---- v4：跨包适配器用的公开存取 ----
        public Object v4Result() {
            return result;
        }

        public void v4SetResult(Object r) {
            result = r;
        }

        public Throwable v4Throwable() {
            return throwable;
        }

        public void v4SetThrowable(Throwable t) {
            throwable = t;
        }

        public void v4ClearEarly() {
            returnEarly = false;
            result = null;
            throwable = null;
        }
    }

    // ---- v4：给不同包的适配器（XpBridge）用的公开入口（真 API82 里由同包的 XposedBridge 直接调 protected）----
    public void v4CallBefore(MethodHookParam p) throws Throwable {
        beforeHookedMethod(p);
    }

    public void v4CallAfter(MethodHookParam p) throws Throwable {
        afterHookedMethod(p);
    }

    /** 与 API82 同名（仅作返回类型）。 */
    public class Unhook {
        private final java.lang.reflect.Executable target;

        public Unhook(java.lang.reflect.Executable target) {
            this.target = target;
        }

        public java.lang.reflect.Member getHookedMethod() {
            return target;
        }
    }
}
