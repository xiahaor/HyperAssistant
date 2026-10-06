package com.qianxia.hyperassistant

import android.app.Application
import android.util.Log
import java.io.File

/**
 * 崩溃自证：设备从服务器不可达（取不到 logcat）⇒ 让 App **自己把崩溃栈写到内部存储**，
 * 并在下次启动时**显示在“关于”页**（可直接截图给我）。
 *
 * 同时具备**防崩溃循环**：只要求职到上次有崩溃记录，本次启动就自动进“安全模式”
 * （关掉液态玻璃），保证 App 至少能打开、并能把栈显示出来。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // ★ 2026-10-04 日志功能：日志接收器注册在 Application 上（**不依赖哪个页面可见**），
        //   这样"用户设的级别被宿主回读 / 宿主推来日志"在 App 进程活着时一直有效。
        //   注册失败只记 logcat，绝不影响 App 启动（见 LogStore.install）。
        LogStore.install(this)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, CRASH_FILE).writeText(
                    "time=" + System.currentTimeMillis() + "\n" +
                        "thread=" + t.name + "\n" +
                        "device=" + android.os.Build.MODEL + " sdk=" + android.os.Build.VERSION.SDK_INT + "\n\n" +
                        Log.getStackTraceString(e)
                )
            } catch (ignored: Throwable) {
            }
            Log.e(TAG, "App 崩溃，已写 " + CRASH_FILE, e)
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        const val TAG = "ANIMV4"
        const val CRASH_FILE = "last_crash.txt"
    }
}
