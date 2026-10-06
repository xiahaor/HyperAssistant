package com.qianxia.hyperassistant;

import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v3 诊断出口：<b>内存环形缓冲（热路径零 I/O）+ 独立线程批量落盘</b>。
 *
 * <h3>为什么是环形缓冲 + 异步（沿用 v2 的真机实测结论）</h3>
 * v2 曾经每条日志在主线程做一次 {@code open+write+flush+close}，真机数据：
 * <pre>
 *   帧 720→840 窗口触发 179 次同步写盘
 *   ⇒ 单帧均值从 7.3µs 暴涨到 469µs
 * </pre>
 * FUSE 上一次文件打开是毫秒级，放在钩子回调里等于白烧几毫秒。
 * ⇒ 现在钩子回调里只做「组字符串 + 塞进环形缓冲 + notify」，文件写入交给独立写线程。
 *
 * <h3>硬约束</h3>
 * <ul>
 *   <li><b>uid==1000（system_server）一律不落盘</b>：v1 曾因 system_server 在 WM 全局锁内做文件 I/O
 *       导致 4 次整机冻结（{@code .scan/reports/hang-2026-10-01-mtdoops.md} §7），这里挡在最前面。</li>
 *   <li>{@link #ev} 里没有任何文件 I/O，也没有 {@code new SimpleDateFormat}（用 ThreadLocal 复用）。</li>
 *   <li>环形缓冲满了<b>丢最旧的</b>并计数：宁可丢日志，也不阻塞、不涨内存。</li>
 * </ul>
 *
 * <p><b>落盘默认关闭</b>：{@code AnimCfg.write=false}（见 animv3.conf）。默认只走 logcat；
 * 用户把 {@code write=true} 写进 animv3.conf 后 3 秒内生效，不用重装。
 *
 * <h3>★ 2026-10-04 新增：级别（照 HyperCeiler）</h3>
 * <ul>
 *   <li>{@link #ev(String)} = 级别 2「详细」：全部事件（探针逐帧行等）；</li>
 *   <li>{@link #key(String)} = 级别 1「常规」：桥 / 探针挂载 / 错误 / 状态变化；</li>
 *   <li>两条通路互不干扰：<b>文件日志照旧</b>（只看 {@code write}），
 *       级别只管"要不要把这一行<b>推给 App</b>"（{@link LogPush}）。</li>
 * </ul>
 */
final class Diag {

    /** ★ §DZ：供 Hooks 在 system_server 里打印"日志将写到哪" ✓。 */
    static String logDirHint() {
        return SVC_DIR;
    }

    private static final String TAG = "AnimV4Probe";
    private static final String FILE_NAME = "ANIMV4-P1.txt";
    /** ★ system_server(uid 1000) 用独立文件（避免与负一屏进程抢同一文件 ✓）。 */
    private static final String FILE_NAME_SVC = "ANIMV4-SVC.txt";
    private static final String FILE_OLD = "ANIMV4-P1.old.txt";

    /** ★ system_server(uid 1000) 绝不落盘（只走 logcat）。 */
    private static final boolean SYSTEM_UID =
            android.os.Process.myUid() == android.os.Process.SYSTEM_UID;

    /** 多落点依次尝试（只要任一成功，用户就能看到）。 */
    private static final String[] DIRS = {
            "/sdcard/Download/dsh-debuglog",
            "/storage/emulated/0/Download/dsh-debuglog",
            "/sdcard/Android/media/com.qianxia.hyperassistant",
            "/data/local/tmp",
    };

    /**
     * ★ 2026-10-04 §DZ：**system_server(uid=1000) 专用落点顺序** ✓
     * <p>真机现象：system 对 `/sdcard/Download/...` 无权创建目录 ✗ ⇒ 会一路 fallback 到用户看不到的
     * `/data/local/tmp` ✗ ⇒ 用户以为"没写" ✓。改为：**优先 app 专属 media 目录**（system 可写 ✓
     * 且**文件管理器/电脑都能看到** ✓），其次 `/data/local/tmp` ✓。
     */
    private static final String[] DIRS_SVC = {
            "/sdcard/Android/media/com.qianxia.hyperassistant",
            "/storage/emulated/0/Android/media/com.qianxia.hyperassistant",
            "/sdcard/Download/dsh-debuglog",
            "/data/local/tmp",
    };

    /** 按进程选目录表（system_server 用 DIRS_SVC ✓）。 */
    private static String[] dirs() {
        return SYSTEM_UID ? DIRS_SVC : DIRS;
    }

    /** ★ §DZ：当前实际落点目录（供日志打印 ✓，"到底写到哪了"一眼可见 ✓）。 */
    private static volatile String SVC_DIR = "(未落盘)";

    /** 每批最多落盘多少条（批量，摊薄 open/close 成本）。 */
    private static final int BATCH = 512;

    /**
     * ★ LOW#1 修复：写线程的<b>短合并窗口</b>（毫秒）。
     *
     * <p>原来 {@code writerLoop} 只要 {@code count > 0} 就立刻 drain：在"低频、零散"的时段
     * （例如每 3 秒一条配置行）会退化成"每行一次 open/write/flush/close"。
     * 现在不足 {@link #BATCH} 时会再多等最多这么久，把窗口内到达的行合并成一批。
     * 最长只增加 {@code MERGE_MS} 的落盘延迟（守护线程，不影响宿主）。
     */
    private static final long MERGE_MS = 50L;

    /**
     * ★ 修复 #5：单文件上限 —— 超过 {@link #MAX_FILE_BYTES} <b>或</b> {@link #MAX_FILE_LINES}
     * 就把当前文件改名成 {@code ANIMV3-P1.old.txt}（覆盖旧的）后重开。
     * 判定用的是<b>实际成功落地的那个目录</b>里的那个文件（见 {@link #appendBatch}）。
     */
    private static final long MAX_FILE_BYTES = 4L * 1024L * 1024L;   // 4 MB
    private static final long MAX_FILE_LINES = 50000L;               // 5 万行

    /** 逐帧行 logcat 限流（★ 修复 #4）：最多每 100ms 一条（落盘/推送不受影响）。 */
    private static final long FRAME_LOGCAT_MS = 100L;
    private static final AtomicLong sLastFrameLogcat = new AtomicLong(0L);

    private static final Object LOCK = new Object();

    // ---- 环形缓冲（只在 LOCK 内读写）----
    private static String[] ring;
    private static int head;
    private static int count;
    private static long dropped;
    private static long written;
    private static boolean writerStarted;

    /**
     * 本进程写入"当前落地文件"的行数（<b>只在写线程访问</b>）。
     * 进程内首批落盘时 {@link #startFreshOnce()} 已把旧文件改名 ⇒ 起点必然是 0。
     */
    private static long sLinesInFile;

    private static volatile String sWhere = "";
    private static volatile String sErr = "";

    private Diag() {
    }

    static String where() {
        return sWhere;
    }

    static String error() {
        return sErr;
    }

    static long written() {
        return written;
    }

    static long dropped() {
        return dropped;
    }

    /**
     * ★ 时间戳（毫秒）用 ThreadLocal 复用 formatter —— v2 每条 {@code new SimpleDateFormat}
     * 是热路径上最大的一笔分配，这里省掉。
     */
    private static final ThreadLocal<SimpleDateFormat> SDF =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
                }
            };

    /**
     * ★ 级别 2（详细 = 全部事件）的出口。<b>语义与行为跟改动前完全一致</b>：
     * logcat 立即 + 环形缓冲（{@code write=true} 时）+ <b>新增</b>按级别推给 App。
     *
     * <p>级别打标照 HyperCeiler 的双层写法（{@code XposedLog.kt:45-151}：{@code d/i/w} 要求
     * {@code LEVEL_VERBOSE}、{@code e} 要求 {@code LEVEL_ERROR_ONLY}），本模块把"要求的级别"
     * 从"日志优先级"改成"重要度"：{@code ev()} = 2（全部事件），{@link #key(String)} = 1（关键行）。
     */
    static void ev(String msg) {
        emit(msg, looksLikeError(msg) ? AnimCfg.LOG_GENERAL : AnimCfg.LOG_VERBOSE, false);
    }

    /**
     * ★ 修复 #4：<b>逐帧行</b>专用出口（目前只有 {@code ac.onScroll} 走这里）。
     *
     * <p>与 {@link #ev} 的差别只有两点，<b>输出行格式一字不改</b>：
     * <ol>
     *   <li>只在 {@code loglevel==2（详细）}时产出：常规/关闭级别下整行丢弃
     *       （调用方 {@code ProbeAssist} 在<b>格式化之前</b>就返回了，这里只是双保险）；</li>
     *   <li>logcat 限流：最多每 {@link #FRAME_LOGCAT_MS} 一条（逐帧 {@code Log.i} 会刷屏），
     *       <b>落盘与推送不受限流影响</b>。</li>
     * </ol>
     */
    static void frame(String msg) {
        if (AnimCfg.loglevel != AnimCfg.LOG_VERBOSE) {
            return;                                       // 非详细级别：逐帧行不产
        }
        emit(msg, AnimCfg.LOG_VERBOSE, true);
    }

    /**
     * ★ "错误 ⇒ 关键行"的自动升格（**唯一的口径，写在这里便于核对**）。
     *
     * <p>任务书对级别 1「常规」的定义是"只关键行：<b>桥 / 探针挂载 / 错误 / 状态变化</b>"。
     * 桥与挂载在各自调用点显式用 {@link #key(String)}；"错误"则用这条规则统一判定 ——
     * 文本里带 {@code ❌} 或 {@code 失败} 的一律按关键行处理。
     * 这样不必去逐个改 120 个调用点，也不会漏掉某个失败分支。
     */
    private static boolean looksLikeError(String msg) {
        return msg != null && (msg.indexOf('❌') >= 0 || msg.indexOf("失败") >= 0);
    }

    /**
     * ★ 级别 1（常规 = 只关键行）的出口：桥 / 探针挂载 / 错误 / 状态变化。
     *
     * <p>级别 1 的行<b>同时</b>进详细（App 侧 detailed.log）与常规（general.log）；
     * 级别 2 的行只进详细。这与 HyperCeiler 的 {@code modules_*（常规）/verbose_*（详细）}
     * 双文件思路一致（{@code LoggerHealthChecker.kt:216}）：详细是常规的超集。
     */
    static void key(String msg) {
        emit(msg, AnimCfg.LOG_GENERAL, false);
    }

    /**
     * 统一出口：logcat 立即（廉价）+ 环形缓冲入队（**零文件 I/O**）+ 按级别推给 App。
     *
     * <p><b>三条通路互相独立</b>（这是不破坏现状的关键）：
     * <ul>
     *   <li>logcat：只看 {@code AnimCfg.logcat}；</li>
     *   <li>文件 {@code ANIMV4-P1.txt}：只看 {@code AnimCfg.write}，<b>与级别无关</b>
     *       —— 级别关到 0 也照样写文件（那是我们排查的主通道）。
     *       <b>唯一例外是逐帧行</b>（{@link #frame}，即 {@code ac.onScroll}）：
     *       它只在 {@code loglevel==2（详细）}产出（★ 修复 #4 的按级别限流），
     *       0/1 级别下不落盘也不 logcat；{@code onEnter/onLeave/onScrollEnd} 等回合边界行不受影响；</li>
     *   <li>推送桥：只看 {@code AnimCfg.loglevel}（{@link LogPush#wanted(int)}），与 write 无关。</li>
     * </ul>
     *
     * @param required 这一行"要求的级别"：1 = 关键行，2 = 详细行
     * @param throttleLogcat ★ 修复 #4：是否对 logcat 限流（逐帧行 true，其余 false）
     */
    private static void emit(String msg, int required, boolean throttleLogcat) {
        if (AnimCfg.logcat && logcatAllowed(throttleLogcat)) {
            try {
                Log.i(TAG, msg);
            } catch (Throwable ignored) {
                // logcat 失败不影响功能
            }
        }
        // ★ 2026-10-04 §DW：原来这里 `if (SYSTEM_UID) return;` 把 system_server 的日志**全丢了** ✗
        //   现在：**文件照写**（仍走异步环形缓冲+写线程 ✓ 绝不在锁内 I/O ✓），**推送仍然禁用** ✗
        final boolean wantFile = AnimCfg.write;
        final boolean wantPush = !SYSTEM_UID && LogPush.wanted(required);
        if (!wantFile && !wantPush) {
            return;
        }
        String row;
        try {
            row = SDF.get().format(new Date())
                    + " pid=" + android.os.Process.myPid()
                    + " " + msg + "\n";
        } catch (Throwable t) {
            return;                                   // 拼行失败：不落盘也不推送
        }
        if (wantFile) {
            enqueueRow(row);
        }
        if (wantPush) {
            LogPush.offer(row, required);
        }
    }

    /** ★ 修复 #4：逐帧行的 logcat 限流闸门（false=不限流）。 */
    private static boolean logcatAllowed(boolean throttle) {
        if (!throttle) {
            return true;
        }
        try {
            long now = SystemClock.elapsedRealtime();
            long prev = sLastFrameLogcat.get();
            if (now - prev < FRAME_LOGCAT_MS) {
                return false;
            }
            return sLastFrameLogcat.compareAndSet(prev, now);
        } catch (Throwable t) {
            return true;                              // 限流出错时宁可多打，不少打
        }
    }

    // ------------------------------------------------------------------ 入队（热路径，无 I/O）

    private static void enqueueRow(String row) {
        try {
            synchronized (LOCK) {
                if (ring == null) {
                    int cap = AnimCfg.buf;
                    if (cap < 256) {
                        cap = 256;
                    }
                    ring = new String[cap];
                }
                if (count == ring.length) {
                    // 环形缓冲满：丢最旧的
                    ring[head] = null;
                    head = (head + 1) % ring.length;
                    count--;
                    dropped++;
                }
                ring[(head + count) % ring.length] = row;
                count++;
                if (!writerStarted) {
                    writerStarted = true;
                    Thread t = new Thread(Diag::writerLoop, "animv4-log");
                    t.setDaemon(true);
                    t.start();
                }
                LOCK.notifyAll();
            }
        } catch (Throwable ignored) {
            // 入队失败也不影响功能
        }
    }

    /** 在 LOCK 内调用：取走最多 max 条并拼成一批。 */
    private static String drainLocked(int max) {
        if (count == 0) {
            return null;
        }
        int k = Math.min(count, max);
        StringBuilder sb = new StringBuilder(k * 96);
        for (int i = 0; i < k; i++) {
            sb.append(ring[head]);
            ring[head] = null;
            head = (head + 1) % ring.length;
        }
        count -= k;
        return sb.toString();
    }

    // ------------------------------------------------------------------ 落盘（独立线程，批量）

    private static void writerLoop() {
        Boolean freshDone = null;                 // 首次落盘时把旧文件改名（只做一次）
        while (true) {
            String batch;
            try {
                synchronized (LOCK) {
                    // ★ LOW#1 修复：不足一批时不立刻 drain，先等一个最多 MERGE_MS 的合并窗口，
                    //   把窗口内到达的行攒成一批（避免"每行一次 open/write/flush/close"）。
                    //   count>=BATCH ⇒ 不等；count==0 ⇒ 长等（offer 会 notifyAll）。
                    long deadline = System.currentTimeMillis() + MERGE_MS;
                    for (;;) {
                        if (count >= BATCH) {
                            break;
                        }
                        if (count > 0) {
                            long left = deadline - System.currentTimeMillis();
                            if (left <= 0L) {
                                break;
                            }
                            LOCK.wait(left);
                        } else {
                            LOCK.wait(1000L);
                            deadline = System.currentTimeMillis() + MERGE_MS;  // 新的一批重新计时
                        }
                    }
                    batch = drainLocked(BATCH);
                }
                if (batch == null) {
                    continue;
                }
                if (freshDone == null) {
                    freshDone = Boolean.TRUE;
                    startFreshOnce();
                }
                if (appendBatch(batch)) {
                    written++;
                }
            } catch (Throwable t) {
                sErr = String.valueOf(t);
                try {
                    Thread.sleep(500);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 进程内第一次落盘：把旧日志改名成 .old（只留一份），保证"只有最新一次"。 */
    private static void startFreshOnce() {
        for (String d : dirs()) {
            try {
                File dir = new File(d);
                if (!dir.exists() && !dir.mkdirs()) {
                    continue;
                }
                File f = new File(dir, SYSTEM_UID ? FILE_NAME_SVC : FILE_NAME);
                SVC_DIR = d;                       // ★ 记下落点（供日志打印 ✓）
                if (f.exists() && f.length() > 0) {
                    File old = new File(dir, FILE_OLD);
                    if (old.exists()) {
                        old.delete();
                    }
                    if (!f.renameTo(old)) {
                        new FileOutputStream(f, false).close();
                    }
                }
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    /** 批量追加；成功返回 true。失败时轮换落点。 */
    private static boolean appendBatch(String text) {
        byte[] bytes;
        try {
            bytes = text.getBytes("UTF-8");
        } catch (Throwable t) {
            return false;
        }
        for (String d : dirs()) {
            FileOutputStream fos = null;
            try {
                File dir = new File(d);
                if (!dir.exists() && !dir.mkdirs()) {
                    continue;
                }
                File f = new File(dir, SYSTEM_UID ? FILE_NAME_SVC : FILE_NAME);
                if (!d.equals(SVC_DIR)) { SVC_DIR = d; }   // ★ 记下落点 ✓
                // ★ 修复 #5：写入前先按"这个目录里的这个文件"判上限并轮转（按实际落地目录判断）。
                rotateIfTooBig(f);
                fos = new FileOutputStream(f, true);
                fos.write(bytes);
                fos.flush();
                sLinesInFile += countLines(text);
                sWhere = d;
                return true;
            } catch (Throwable t) {
                sErr = d + ":" + t.getClass().getSimpleName();
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return false;
    }

    /**
     * ★ 修复 #5：单文件上限检查 —— 文件超过 {@link #MAX_FILE_BYTES}（4MB）
     * <b>或</b>本进程已写入超过 {@link #MAX_FILE_LINES}（5 万行）行，
     * 就把它改名成 {@code ANIMV3-P1.old.txt}（<b>覆盖旧的</b>）后重开一个新文件。
     *
     * <p>只在写线程、写入前调用；判断的是"即将写入的那个目录里的那个文件"
     * （即 {@link #appendBatch} 当前正在尝试的落点），所以不会出现
     * "按 A 目录的尺寸去轮转 B 目录的文件"这种错位。
     *
     * <p>行数用 {@link #sLinesInFile}（进程内计数）判定：进程内首批写盘时
     * {@link #startFreshOnce()} 已经把旧文件改名，所以起点必然是 0；
     * 即使那次改名失败，还有 {@link #MAX_FILE_BYTES} 这条与文件实际大小绑定的硬闸门兜底。
     */
    private static void rotateIfTooBig(File f) {
        try {
            if (!f.exists() || f.length() == 0L) {
                return;
            }
            long lines = sLinesInFile;
            if (f.length() <= MAX_FILE_BYTES && lines <= MAX_FILE_LINES) {
                return;
            }
            long len = f.length();
            File old = new File(f.getParentFile(), FILE_OLD);
            if (old.exists()) {
                old.delete();
            }
            boolean moved = f.renameTo(old);
            if (!moved) {
                // 改名失败（极少见）：退而清空，保证本次写入仍从一个受控大小的文件开始
                new FileOutputStream(f, false).close();
            }
            sLinesInFile = 0L;
            try {
                Log.i(TAG, "日志上限轮转：len=" + len + " lines=" + lines + " moved=" + moved
                        + " ⇒ " + FILE_OLD);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            sErr = "rotate:" + t.getClass().getSimpleName();
        }
    }

    /** 统计一批文本里的换行数（每行都以 {@code \n} 结尾 ⇒ 换行数就是行数）。 */
    private static int countLines(String text) {
        if (text == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }
}
