package com.qianxia.hyperassistant;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.ArrayDeque;

/**
 * <b>日志推送桥（hook 侧 → 模块 App）</b>：把 {@link Diag} 已经拼好的一行，
 * 按级别批量推给模块 App（App 侧落到自己的 {@code filesDir/logs/*.log}）。
 *
 * <h3>为什么需要它（与 HyperCeiler 的分工不同，原因见下）</h3>
 * HyperCeiler 的 App 有存储权限（还能 root）⇒ 它直接 {@code LogManager}/{@code XposedLogLoader}
 * 去读 {@code /data} 或共享目录里的 {@code modules_*.log / verbose_*.log}
 * （{@code LoggerHealthChecker.kt:216}）。本模块的 App <b>没有存储权限、不走 root</b>，
 * 读不到 hook 进程写的那份 {@code /sdcard/Download/dsh-debuglog/ANIMV4-P1.txt}，
 * 所以照本模块既有的"推送桥"（{@link Bridge}）思路：hook 侧<b>推</b>，App 侧收。
 *
 * <h3>批量（照任务书的 500ms / 20 行）</h3>
 * <ul>
 *   <li>攒够 {@link #BATCH_LINES} 行，或距最早一行满 {@link #FLUSH_MS} 毫秒 ⇒ 发一条广播；</li>
 *   <li>两个级别<b>各一个队列</b>（常规 1 / 详细 2），所以一条广播里的行级别一致，
 *       App 侧直接按级别落文件；</li>
 *   <li>队列上限 {@link #MAX_PENDING} 行，超了<b>丢最旧</b>并计数：宁可丢日志也不涨内存；</li>
 *   <li>拿不到 Context（桥还没注册）时整批丢弃并计数 —— <b>不缓存、不等待</b>，绝不拖住宿主。</li>
 * </ul>
 *
 * <h3>硬约束</h3>
 * <ul>
 *   <li>{@link #offer} 在<b>钩子回调里</b>被调用（可能是逐帧）：只有一次 {@code synchronized}
 *       和一次 {@code addLast}，<b>零 I/O、零 new Thread</b>（线程只懒启动一次）；</li>
 *   <li>发送全在独立守护线程上；任何异常只记一行 logcat（直接用 {@code Log.i}，
 *       <b>不走 {@link Diag}</b>，避免"日志失败再写日志"的递归）；</li>
 *   <li>该线程<b>只在负一屏进程里</b>存在（本类只在 uid != 1000 时被调用，且进程死线程即消失）。</li>
 * </ul>
 */
final class LogPush {

    /**
     * 推送动作名。App 侧（{@code LogStore.ACTION_LOG}）必须是<b>同一个字面量</b>。
     * 命名风格与既有的 {@code com.qianxia.hyperassistant.PING / .PONG} 一致。
     */
    static final String ACTION_LOG = "com.qianxia.HyperAssistant.LOG";

    /** 模块 App 的包名（与 {@link Bridge} 的 MODULE_PKG 同值：推送目标只有一个）。 */
    private static final String MODULE_PKG = "com.qianxia.HyperAssistant";

    /** 额外数据键（App 侧 {@code LogStore} 必须用同一批字面量）。 */
    static final String KEY_LEVEL = "level";   // int：1=常规 / 2=详细（一行一批，同批同级）
    static final String KEY_LINES = "lines";   // String：若干整行（已含时间戳与 pid，UTF-8 文本）
    static final String KEY_PID = "pid";
    static final String KEY_TS = "ts";

    /** 攒够这么多行就发（与 500ms 两个条件先到先发）。 */
    private static final int BATCH_LINES = 20;

    /** 距最早一行超过这么久也发（避免低频日志永远不发）。 */
    private static final long FLUSH_MS = 500L;

    /** 单个级别队列的积压上限（超了丢最旧的）。 */
    private static final int MAX_PENDING = 400;

    private static final String TAG = "AnimV4Probe";

    private static final Object LOCK = new Object();

    /** 级别 1 的待发队列（只在 LOCK 内读写）。 */
    private static final ArrayDeque<String> Q1 = new ArrayDeque<String>();
    /** 级别 2 的待发队列（只在 LOCK 内读写）。 */
    private static final ArrayDeque<String> Q2 = new ArrayDeque<String>();

    private static boolean started;
    private static long dropped;
    private static long sent;

    /** 每个级别队列"第一批"的入队时刻（elapsedRealtime；0 = 该队列为空）。 */
    private static long start1;
    private static long start2;

    private LogPush() {
    }

    /**
     * ★ 级别闸门：<b>逐字照 HyperCeiler {@code LoggerUtils.shouldLog}
     * （{@code LoggerUtils.kt:4-10}）</b>。
     *
     * <pre>
     *   loglevel=0(Disabled) ⇒ 一条都不推
     *   loglevel=1(General)  ⇒ 只推 required==1 的行（"只关键行"）
     *   loglevel=2(Detailed) ⇒ required==1 与 required==2 都推（"全部事件"）
     * </pre>
     *
     * @param required 这一行要求的级别（{@link Diag#ev} = 2 / {@link Diag#key} = 1）
     */
    static boolean wanted(int required) {
        final int l = AnimCfg.loglevel;
        if (l == AnimCfg.LOG_DISABLED) {
            return false;
        }
        if (l == AnimCfg.LOG_GENERAL) {
            return required == AnimCfg.LOG_GENERAL;
        }
        return required == AnimCfg.LOG_GENERAL || required == AnimCfg.LOG_VERBOSE;
    }

    static long dropped() {
        return dropped;
    }

    static long sent() {
        return sent;
    }

    /**
     * 入队一行（<b>热路径</b>：调用方可能是逐帧钩子回调）。
     *
     * <p>只做：加锁 + 入队 + （首次）启动发送线程 + notify。没有 I/O、没有分配线程、
     * 没有格式化（行文本是 {@link Diag} 那边拼好的）。
     */
    static void offer(String row, int level) {
        if (row == null || row.isEmpty()) {
            return;
        }
        try {
            synchronized (LOCK) {
                final boolean general = level == AnimCfg.LOG_GENERAL;
                ArrayDeque<String> q = general ? Q1 : Q2;
                if (q.size() >= MAX_PENDING) {
                    q.pollFirst();
                    dropped++;
                }
                if (q.isEmpty()) {
                    // 该级别"这一批"的起点：用于 500ms 到期判定
                    if (general) {
                        start1 = android.os.SystemClock.elapsedRealtime();
                    } else {
                        start2 = android.os.SystemClock.elapsedRealtime();
                    }
                }
                q.addLast(row);
                if (!started) {
                    started = true;
                    Thread t = new Thread(LogPush::loop, "animv4-logpush");
                    t.setDaemon(true);
                    t.start();
                }
                LOCK.notifyAll();
            }
        } catch (Throwable ignored) {
            // 入队失败绝不影响宿主
        }
    }

    /** 发送线程：等到"攒够 20 行"或"距该批最早一行 500ms"，把到期的队列各发一条广播。 */
    private static void loop() {
        while (true) {
            try {
                int lvl = -1;
                String text = null;
                synchronized (LOCK) {
                    // ★ 两种"不能发"必须分开（否则会空转烧 CPU）：
                    //   wait < 0 ⇒ 两个队列都空 ⇒ 无限等（offer 会 notifyAll）；
                    //   wait > 0 ⇒ 还没到期 ⇒ 定时等；
                    //   wait = 0 ⇒ 现在就有队列可发 ⇒ 不等待，直接下去发。
                    long wait = nextWaitLocked();
                    if (wait < 0L) {
                        LOCK.wait();
                    } else if (wait > 0L) {
                        LOCK.wait(wait);
                    }
                    if (readyLocked(true)) {
                        lvl = AnimCfg.LOG_GENERAL;
                        text = drainLocked(Q1);
                        start1 = 0L;
                    } else if (readyLocked(false)) {
                        lvl = AnimCfg.LOG_VERBOSE;
                        text = drainLocked(Q2);
                        start2 = 0L;
                    }
                }
                if (text != null) {
                    send(lvl, text);
                }
            } catch (Throwable t) {
                try {
                    Log.i(TAG, "日志推送线程异常（已吞，不影响宿主）: " + t);
                } catch (Throwable ignored) {
                }
                try {
                    Thread.sleep(500L);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 还需要等多久（<b>{@code -1} = 两个队列都空 ⇒ 无限等</b>；{@code 0} = 现在就能发；
     * {@code >0} = 到期还剩这么多毫秒）。
     *
     * <p>规则（照任务书）：任一级别队列攒够 {@link #BATCH_LINES} 行 ⇒ 立刻发；
     * 否则等到该批最早一行满 {@link #FLUSH_MS}。
     *
     * <p>⚠️ 这里**必须**把"空"与"可以立刻发"区分开：两者都返回 0 的话，
     * 队列一空（最常见的状态）发送线程就会变成 `while(true){...}` 的**纯空转**，
     * 在宿主进程里白烧一个核。已修。
     */
    private static long nextWaitLocked() {
        long best = Long.MAX_VALUE;
        if (!Q1.isEmpty()) {
            if (Q1.size() >= BATCH_LINES) {
                return 0L;
            }
            best = Math.min(best, start1 + FLUSH_MS);
        }
        if (!Q2.isEmpty()) {
            if (Q2.size() >= BATCH_LINES) {
                return 0L;
            }
            best = Math.min(best, start2 + FLUSH_MS);
        }
        if (best == Long.MAX_VALUE) {
            return -1L;                                 // 都空：无限等，offer 会 notifyAll
        }
        long left = best - android.os.SystemClock.elapsedRealtime();
        return left > 0 ? left : 0L;
    }

    /** 这一级别的队列现在可发吗（攒够 20 行，或距该批起点满 500ms）。 */
    private static boolean readyLocked(boolean general) {
        ArrayDeque<String> q = general ? Q1 : Q2;
        if (q.isEmpty()) {
            return false;
        }
        if (q.size() >= BATCH_LINES) {
            return true;
        }
        long start = general ? start1 : start2;
        if (start == 0L) {
            // 防御（正常到不了）：队列非空却没有批次起点 ⇒ 直接发，保证永远向前推进，绝不空转。
            return true;
        }
        return android.os.SystemClock.elapsedRealtime() - start >= FLUSH_MS;
    }

    /** 取走队列里的全部行并拼成一段文本（在 LOCK 内调用）。 */
    private static String drainLocked(ArrayDeque<String> q) {
        if (q.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(q.size() * 96);
        while (!q.isEmpty()) {
            sb.append(q.pollFirst());
        }
        return sb.toString();
    }

    /**
     * 发一条广播（在推送线程上）。字段与 App 侧 {@code LogStore} 的读取逐字对应。
     *
     * <p>拿不到 Context ⇒ 整批丢弃（只计数，**刻意不打日志**：这时每 500ms 一条会刷屏）：
     * 这是<b>刻意的</b>——桥还没注册说明负一屏进程刚起，这时缓存只会白涨内存。
     */
    private static void send(int level, String lines) {
        Context ctx = Bridge.appContext();
        if (ctx == null) {
            dropped++;
            return;
        }
        try {
            Intent i = new Intent(ACTION_LOG)
                    .setPackage(MODULE_PKG)                       // 显式投给模块 App
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)    // App 在前台 ⇒ 立即投递
                    .putExtra(KEY_LEVEL, level)
                    .putExtra(KEY_LINES, lines)
                    .putExtra(KEY_PID, android.os.Process.myPid())
                    .putExtra(KEY_TS, System.currentTimeMillis());
            ctx.sendBroadcast(i);
            sent++;
        } catch (Throwable t) {
            dropped++;
            try {
                Log.i(TAG, "日志推送失败（已吞，不影响宿主）: "
                        + t.getClass().getName() + ": " + t.getMessage());
            } catch (Throwable ignored) {
            }
        }
    }
}
