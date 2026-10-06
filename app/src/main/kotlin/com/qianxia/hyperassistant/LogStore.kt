package com.qianxia.hyperassistant

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import java.io.File

/**
 * App 侧日志存储（**照 HyperCeiler 的双文件思路，但落到我们自己的私有目录**）。
 *
 * <h3>为什么不是 HyperCeiler 那套</h3>
 * HyperCeiler 的日志页直接读它自己的 {@code /data} 目录/共享目录（还能 root：
 * `LogListFragment.java:178-182` 里 `ShellUtils.checkRootPermission()`），
 * `LoggerHealthChecker.kt:216` 认的是 `modules_*.log / verbose_*.log`。
 * 本模块 App **没有存储权限、不走 root**（硬约束），读不到 hook 进程写的
 * `/sdcard/Download/dsh-debuglog/ANIMV3-P1.txt` ⇒ 只能沿用本模块既有的**推送桥**：
 * hook 侧（负一屏进程）把日志行按级别**推**过来（{@code LogPush.java}），
 * 这里落到 App 私有目录。
 *
 * <h3>两个文件 = 两个级别（照 HyperCeiler 的常规档 / 详细档双文件分法）</h3>
 * <pre>
 *   filesDir/logs/general.log    级别 1「常规」：桥 / 探针挂载 / 错误 / 状态变化
 *   filesDir/logs/detailed.log   级别 2「详细」：全部事件（**包含**全部常规行）
 * </pre>
 * 落在 `filesDir` 下 ⇒ **不需要任何权限**（HyperCeiler 需要存储权限/root 才能读日志）。
 *
 * <h3>滚动上限（防止无限增长）</h3>
 * <pre>
 *   每文件最多 {@link #MAX_LINES} 行（2000；到 {@link #TRIM_SLACK} 余量后重写成最新 2000 行）
 *   每行最多 {@link #MAX_LINE_CHARS} 字符（500，超长截断，免得一行动辄几 KB）
 * </pre>
 * HyperCeiler 对应的是"列表 `LIMIT 1000`（LogDao.java:50,86）+ 库里 autoTrim 保留 5 万条
 * （LogDao.java:62-63）"；我们的上限按任务书取 2000 行/文件。
 *
 * <h3>接收器（与 hook 侧字面量必须一致）</h3>
 * <pre>
 *   ACTION_LOG         = "com.qianxia.HyperAssistant.LOG"          ← hook 推日志（LogPush.ACTION_LOG）
 *   ACTION_QUERY_LEVEL = "com.qianxia.HyperAssistant.QUERY_LEVEL"  ← 宿主问"你期望的级别？"
 *   KEY_LEVEL / KEY_LINES / KEY_PID / KEY_TS
 * </pre>
 * 注册用 {@code RECEIVER_EXPORTED}（发送方是负一屏进程，**另一个 uid**；与既有 PONG
 * 接收器同理，见 `ModuleSettings.kt:338-345` 的注释）。注册在 {@link App#onCreate()} 里、
 * 用独立 HandlerThread 调度 ⇒ App 进程活着就一直收，不依赖哪个页面可见。
 */
object LogStore {

    // ------------------------------------------------------------------ 协议（与 hook 侧同字面量）

    /** 推送动作名 —— **必须与 hook 侧 `LogPush.ACTION_LOG` 完全一致**。 */
    const val ACTION_LOG = "com.qianxia.HyperAssistant.LOG"

    /** 级别回读动作名 —— **必须与 hook 侧 `Bridge.ACTION_QUERY_LEVEL` 完全一致**。 */
    const val ACTION_QUERY_LEVEL = "com.qianxia.HyperAssistant.QUERY_LEVEL"

    /** 级别设置动作名 —— **必须与 hook 侧 `Bridge.ACTION_LOGLEVEL` 完全一致**（App → 负一屏）。 */
    const val ACTION_LOGLEVEL = "com.qianxia.HyperAssistant.LEVEL"

    const val KEY_LEVEL = "level"
    const val KEY_LINES = "lines"

    // ------------------------------------------------------------------ 级别（照 HyperCeiler）

    /** 0 = 关闭（一条都不推）。 */
    const val LEVEL_DISABLED = 0

    /** 1 = 常规（`LogLevelManager.LEVEL_ERROR_ONLY`，显示名 "General"）。 */
    const val LEVEL_GENERAL = 1

    /** 2 = 详细（`LogLevelManager.LEVEL_VERBOSE`，显示名 "Detailed"）。 */
    const val LEVEL_VERBOSE = 2

    /** 显示名，逐字照 `LogLevelManager.logLevelDesc()`（LogLevelManager.kt:62-69）的中文对照。 */
    fun levelTitle(level: Int): String = when (level) {
        LEVEL_DISABLED -> "关闭"
        LEVEL_GENERAL -> "常规"
        LEVEL_VERBOSE -> "详细"
        else -> "未知"
    }

    // ------------------------------------------------------------------ 上限（任务书：2000 行 / 500 字符）

    const val MAX_LINES = 2000
    const val MAX_LINE_CHARS = 500

    /**
     * 裁剪余量：文件超过 {@code MAX_LINES + TRIM_SLACK} 行才真正重写一次（重写后正好 MAX_LINES 行）。
     *
     * <p>为什么不"每超一行就重写"：那样在「详细」档（每 500ms 一批）会变成
     * **每批一次整文件读 + 整文件写**（~200KB/批）。留 400 行余量后，重写频率降到
     * 每 400 行一次 ⇒ 上限不变、I/O 降两个数量级。
     */
    private const val TRIM_SLACK = 400

    private const val TAG = "AnimV4Log"
    private const val DIR = "logs"
    private const val GENERAL_FILE = "general.log"
    private const val DETAILED_FILE = "detailed.log"

    /**
     * Compose 可观察的"内容版本号"：每收到一批日志 / 清空一次就 +1。
     * 日志页 `LaunchedEffect(version)` 跟着重新读文件（**不做自动滚底**，
     * 见 `LogPage` 注释：HyperCeiler 是倒序列表 + 手动滚动）。
     */
    val version = mutableIntStateOf(0)

    /** 累计收到多少行（只用于空态提示/自证，不落盘）。 */
    @Volatile
    var received: Long = 0L
        private set

    /** 丢弃计数（App 侧不做丢弃；这里恒为 0，保留字段只为日志里能对齐 hook 侧口径）。 */
    @Volatile
    var dropped: Long = 0L
        private set

    @Volatile
    private var installed = false

    // ------------------------------------------------------------------ 安装（进程级）

    private var thread: HandlerThread? = null

    @Volatile
    private var handler: Handler? = null

    private var rx: BroadcastReceiver? = null

    /**
     * 注册日志接收器（幂等）。**只在 `App.onCreate()` 调一次**。
     *
     * <p>失败（极少数 ROM 限制）只记 logcat，绝不影响 App 启动。
     */
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        try {
            val t = HandlerThread("animv4-logstore")
            t.start()
            thread = t
            val h = Handler(t.looper)
            handler = h
            val app = ctx.applicationContext
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    try {
                        when (i?.action) {
                            ACTION_LOG -> {
                                val level = i.getIntExtra(KEY_LEVEL, LEVEL_VERBOSE)
                                val lines = i.getStringExtra(KEY_LINES) ?: return
                                append(app, level, lines)
                            }
                            ACTION_QUERY_LEVEL -> {
                                // ★ 宿主问"你期望的级别？"⇒ 用 result extras 回填（有序广播）。
                                val ex = Bundle()
                                ex.putInt(KEY_LEVEL, readLevel(app))
                                setResultExtras(ex)
                            }
                            else -> Log.i(TAG, "收到未知 action（忽略）: " + i?.action)
                        }
                    } catch (t2: Throwable) {
                        Log.i(TAG, "接收处理异常（已吞）: " + t2)
                    }
                }
            }
            rx = r
            val f = IntentFilter(ACTION_LOG)
            f.addAction(ACTION_QUERY_LEVEL)
            app.registerReceiver(r, f, null, h, Context.RECEIVER_EXPORTED)
            Log.i(TAG, "日志接收器已注册 动作=$ACTION_LOG / $ACTION_QUERY_LEVEL 线程=animv4-logstore 目录=${dir(app).absolutePath}")
        } catch (t: Throwable) {
            installed = false
            Log.i(TAG, "日志接收器注册失败（已吞，不影响 App）: $t")
        }
    }

    // ------------------------------------------------------------------ 路径 / 读 / 写 / 清

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR)

    fun file(ctx: Context, level: Int): File =
        File(dir(ctx), if (level == LEVEL_GENERAL) GENERAL_FILE else DETAILED_FILE)

    /**
     * 落盘一批（**在接收器线程上调用**，不在主线程）。
     *
     * <p>路由（照"详细是常规的超集"）：
     * <pre>
     *   level == 1（常规） ⇒ general.log **与** detailed.log 都写
     *   level == 2（详细） ⇒ 只写 detailed.log
     * </pre>
     */
    fun append(ctx: Context, level: Int, text: String) {
        try {
            val lines = text.split('\n')
                .map { it.trimEnd('\r') }
                .filter { it.isNotEmpty() }
                .map { if (it.length > MAX_LINE_CHARS) it.substring(0, MAX_LINE_CHARS) + "…" else it }
            if (lines.isEmpty()) return
            val payload = lines.joinToString("\n") + "\n"
            if (level == LEVEL_GENERAL) {
                writeAppend(file(ctx, LEVEL_GENERAL), payload, lines.size)
                writeAppend(file(ctx, LEVEL_VERBOSE), payload, lines.size)
            } else {
                writeAppend(file(ctx, LEVEL_VERBOSE), payload, lines.size)
            }
            received += lines.size
            Log.i(TAG, "收到日志 ${lines.size} 行 level=$level 累计=$received")
            bump()
        } catch (t: Throwable) {
            Log.i(TAG, "落盘失败（已吞）: $t")
        }
    }

    private fun writeAppend(f: File, payload: String, added: Int) {
        try {
            val d = f.parentFile
            if (d != null && !d.exists() && !d.mkdirs()) return
            f.appendText(payload, Charsets.UTF_8)
            trimIfNeeded(f, added)
        } catch (t: Throwable) {
            Log.i(TAG, "写 ${f.name} 失败（已吞）: $t")
        }
    }

    // ---------------------------------------------------------------- 行数计数（只为少读几次文件）

    /** 文件名 → 行数（进程内缓存；只在 countLock 内读写）。 */
    private val counts = HashMap<String, Int>()
    private val countLock = Any()

    /** 先加后判：返回"加完之后的估算行数"；第一次会真读一次文件把基数搞准。 */
    private fun bumpCount(f: File, added: Int): Int = synchronized(countLock) {
        val cur = counts[f.name] ?: try {
            if (f.exists()) f.readLines().size else 0
        } catch (t: Throwable) {
            0
        }
        val next = cur + added
        counts[f.name] = next
        next
    }

    private fun resetCount(f: File) = synchronized(countLock) {
        counts[f.name] = 0
    }

    private fun setCount(f: File, n: Int) = synchronized(countLock) {
        counts[f.name] = n
    }

    /** 超过 {@code MAX_LINES + TRIM_SLACK} 行才重写，重写后保留**最新**的 {@link #MAX_LINES} 行。 */
    private fun trimIfNeeded(f: File, added: Int) {
        try {
            val n = bumpCount(f, added)
            if (n <= MAX_LINES + TRIM_SLACK) return
            val all = f.readLines().filter { it.isNotEmpty() }
            if (all.size <= MAX_LINES) {
                setCount(f, all.size)
                return
            }
            val keep = all.subList(all.size - MAX_LINES, all.size)
            f.writeText(keep.joinToString("\n") + "\n", Charsets.UTF_8)
            setCount(f, keep.size)
            Log.i(TAG, "裁剪 ${f.name}：${all.size} → ${keep.size} 行")
        } catch (t: Throwable) {
            Log.i(TAG, "trim ${f.name} 失败（已吞）: $t")
        }
    }

    /**
     * 读某个级别当前的行（**倒序：新→旧**，与 HyperCeiler 列表一致 ——
     * `LogDao.java:50,86` 的 `ORDER BY timestamp DESC LIMIT 1000`）。
     *
     * <p>最多返回 {@link #MAX_LINES} 行（文件可能因 {@link #TRIM_SLACK} 短暂多出一些）。
     */
    fun read(ctx: Context, level: Int): List<String> {
        return try {
            val f = file(ctx, level)
            if (!f.exists()) emptyList()
            else {
                val all = f.readLines().filter { it.isNotEmpty() }
                val tail = if (all.size > MAX_LINES) all.subList(all.size - MAX_LINES, all.size) else all
                setCount(f, all.size)
                tail.asReversed()
            }
        } catch (t: Throwable) {
            Log.i(TAG, "读日志失败（已吞）: $t")
            emptyList()
        }
    }

    /** 清空**当前级别**的那一个文件（照 HyperCeiler 的"清空当前日志"，`LogViewerActivity.java:280-301`）。 */
    fun clear(ctx: Context, level: Int): Boolean {
        return try {
            val f = file(ctx, level)
            if (f.exists()) f.writeText("", Charsets.UTF_8)
            resetCount(f)
            Log.i(TAG, "已清空 level=$level 文件=${f.name}")
            bump()
            true
        } catch (t: Throwable) {
            Log.i(TAG, "清空失败（已吞）: $t")
            false
        }
    }

    /** 导出到剪贴板（**代替 HyperCeiler 的 zip 导出/分享**：我们无存储权限、也不加依赖）。 */
    /**
     * ★ 一键导出：把日志写进系统「下载」目录（`Download/dsh-debuglog/<name>`）。
     *
     * <p>走 MediaStore.Downloads ⇒ **不需要任何存储权限**（Android 10+ 的推荐做法），
     * 用户在文件管理器/下载里直接能看到，不用 root 也不用 adb。
     *
     * @return 成功时返回可读路径（给 Toast 显示），失败返回 null
     */
    fun exportToDownloads(ctx: Context, text: String, name: String): String? = try {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(
                android.provider.MediaStore.Downloads.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOWNLOADS + "/dsh-debuglog"
            )
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        "Download/dsh-debuglog/$name"
    } catch (t: Throwable) {
        android.util.Log.w("AnimV4Log", "导出失败: $t")
        null
    }

    fun copyToClipboard(ctx: Context, text: String): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("负一屏动画 v4 日志", text))
            true
        } catch (t: Throwable) {
            Log.i(TAG, "复制失败（已吞）: $t")
            false
        }
    }

    // ------------------------------------------------------------------ 级别：本地持久 + 推给宿主

    /** App 侧持久文件（与设置页其它开关同一个 `v3.conf`，**只读这一项**）。 */
    private fun cfgFile(ctx: Context): File = File(ctx.filesDir, "v4.conf")

    /** 读 App 侧保存的级别（读不到 = 1「常规」，与 hook 侧默认值一致）。 */
    fun readLevel(ctx: Context): Int {
        return try {
            val f = cfgFile(ctx)
            if (!f.exists()) return LEVEL_GENERAL
            val v = f.readLines()
                .firstOrNull { it.startsWith("loglevel=") }
                ?.substringAfter('=')
                ?.trim()
                ?.toIntOrNull()
            when (v) {
                LEVEL_DISABLED, LEVEL_GENERAL, LEVEL_VERBOSE -> v
                else -> LEVEL_GENERAL
            }
        } catch (t: Throwable) {
            LEVEL_GENERAL
        }
    }

    /**
     * 把级别**推**给 hook 侧（App → 负一屏，尽力而为）。
     *
     * <p>这条方向的广播在宿主处于后台时会被系统/MIUI 拦掉（与 PING 判红同源），
     * 所以它只是"快路径"；保底路径是宿主主动回读（{@link #ACTION_QUERY_LEVEL}）。
     * 发送失败一律吞掉。
     */
    fun pushLevel(ctx: Context, target: String, level: Int) {
        try {
            val i = Intent(ACTION_LOGLEVEL)
                .setPackage(target)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(KEY_LEVEL, level)
            ctx.sendBroadcast(i)
            Log.i(TAG, "级别设置已发出（App→负一屏）level=$level 目标=$target")
        } catch (t: Throwable) {
            Log.i(TAG, "级别设置发送失败（已吞，宿主稍后会回读）: $t")
        }
    }

    private fun bump() {
        try {
            // 写 Compose state 在任意线程都安全；这里统一回主线程，避免快照竞态。
            Handler(Looper.getMainLooper()).post { version.intValue = version.intValue + 1 }
        } catch (t: Throwable) {
            version.intValue = version.intValue + 1
        }
    }
}
