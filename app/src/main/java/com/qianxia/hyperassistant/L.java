package com.qianxia.hyperassistant;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

/**
 * 日志出口。同时写 logcat 和 Xposed 日志，方便两种抓取方式。
 *
 * 抓取：
 *   adb logcat -s HyperAssistantAnim
 */
public final class L {
    public static final String TAG = "HyperAssistantAnim";

    private L() {
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }

    public static void w(String msg) {
        Log.w(TAG, msg);
        XposedBridge.log(TAG + " [W]: " + msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        XposedBridge.log(TAG + " [E]: " + msg);
        XposedBridge.log(t);
    }

    /** 浮点格式化，固定 4 位小数，方便肉眼比对曲线。 */
    public static String f(float v) {
        return String.format(java.util.Locale.US, "%.4f", v);
    }
}
