package com.qianxia.hyperassistant;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 事件行发射器：把「一次探针命中」拼成<b>一行、可 grep、带毫秒时间戳</b>的日志。
 *
 * <p>时间戳（毫秒）由 {@link Diag} 在入队时补在最前面，形如
 * {@code 10-02 23:41:12.345 pid=12345 ...}，所以本类只拼事件体。
 *
 * <h3>固定字段（契约）</h3>
 * <pre>
 *   P1E#&lt;序号&gt;     事件锚点，grep 用：  adb logcat -d | grep 'P1E#'
 *   k=&lt;探针键&gt;     pane.p#f / sm.b / ac.onScroll / ac.onScrollEnd / ac.onEnter / ac.onLeave
 *   st=&lt;阶段&gt;      打开 / 返回 / 上滑 / 未知
 *   tgt=&lt;目标状态&gt;  原始类名[dex真名,prog=x]（h 系列状态；AssistContentView 探针里是实参）
 *   tx=&lt;值&gt;        translationX 当前值；有两处读数时为 before-&gt;after
 *   a=&lt;值&gt;         alpha（读不到为 n/a）
 *   thr=&lt;线程名&gt;
 *   cnt=&lt;计数&gt;      本探针在本进程内的第几次命中
 *   src=&lt;来源标记&gt;   声明类#方法(形参)   ← 满足"调用栈深度**或**来源标记"
 *   d=&lt;深度&gt;       仅当 animv3.conf 里 stack=true 时出现（默认不出，省开销）
 * </pre>
 *
 * <p>后面再跟各探针自己的键值对（extra）。<b>全部字段都是读出来的值，本类只做字符串拼接。</b>
 */
final class Ev {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private Ev() {
    }

    /**
     * 发射一行事件。
     *
     * @param key     探针键
     * @param stage   阶段（用 {@link Stage} 的常量）
     * @param target  目标状态描述
     * @param tx      translationX 描述（如 {@code 1183.2100->0.0000}）；无读数传 {@code "n/a"}
     * @param alpha   alpha 描述
     * @param src     来源标记（{@link Reflect#describe(java.lang.reflect.Method)}）
     * @param count   本探针命中计数
     * @param extra   额外键值对（可为 null）
     */
    static void line(String key, String stage, String target, String tx, String alpha,
                     String src, int count, String extra) {
        try {
            StringBuilder sb = new StringBuilder(192);
            sb.append("P1E#").append(SEQ.incrementAndGet())
                    .append(" k=").append(key)
                    .append(" st=").append(stage)
                    .append(" tgt=").append(target)
                    .append(" tx=").append(tx)
                    .append(" a=").append(alpha)
                    .append(" thr=").append(Thread.currentThread().getName())
                    .append(" cnt=").append(count);
            if (AnimCfg.stack) {
                sb.append(" d=").append(Reflect.depth());
            }
            if (extra != null && !extra.isEmpty()) {
                sb.append(' ').append(extra);
            }
            sb.append(" src=").append(src);
            emit(sb.toString(), key);
        } catch (Throwable t) {
            // 探针永远不能因为日志失败而影响宿主
            try {
                Diag.ev("P1E 组装失败: " + t);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * ★ 2026-10-04 级别打标（照任务书对级别 1「常规」的定义："桥 / 探针挂载 / 错误 / 状态变化"）。
     *
     * <p>探针里的<b>状态变化</b>就是回合边界 {@code ac.onEnter} / {@code ac.onLeave}
     * （进/出负一屏），它们进「常规」；其余逐帧观测行（{@code onScroll} 等）留在「详细」。
     * 判定只在这里做一次，避免去改 {@link #line} 的 20 多个调用点。
     */
    private static boolean isBoundary(String key) {
        return "ac.onEnter".equals(key) || "ac.onLeave".equals(key);
    }

    /**
     * ★ 修复 #4：<b>逐帧键</b>（只有 {@code ac.onScroll}）走 {@link Diag#frame}：
     * 级别 2（详细）才产出、logcat 限流每 100ms 一条。行格式一字不改。
     */
    private static boolean isFrame(String key) {
        return "ac.onScroll".equals(key);
    }

    /** 按级别出口：关键行走 {@link Diag#key}，逐帧行走 {@link Diag#frame}，其余走 {@link Diag#ev}。 */
    private static void emit(String row, String key) {
        if (isBoundary(key)) {
            Diag.key(row);
        } else if (isFrame(key)) {
            Diag.frame(row);
        } else {
            Diag.ev(row);
        }
    }

    /**
     * 通用事件行（P2 的 {@code k=apply} 用它：字段名与探针不同，不能套 {@link #line}）。
     *
     * <p>前缀仍是 {@code P1E#<序号>}（同一个计数器），所以：
     * ① {@code adb logcat -d | grep 'P1E#'} 一条命令能同时看到探针行与写入行；
     * ② 排序键 {@code (时间戳, P1E#序号)} 与探针行同源，逐帧对齐不会错位。
     *
     * <p>输出形如：{@code P1E#12 k=apply st=返回 prog=.. orig=.. ours=.. thr=.. cnt=.. src=..}
     */
    static void raw(String key, String stage, String extra, String src, int count) {
        try {
            StringBuilder sb = new StringBuilder(256);
            sb.append("P1E#").append(SEQ.incrementAndGet())
                    .append(" k=").append(key)
                    .append(" st=").append(stage);
            if (extra != null && !extra.isEmpty()) {
                sb.append(' ').append(extra);
            }
            sb.append(" thr=").append(Thread.currentThread().getName())
                    .append(" cnt=").append(count);
            if (AnimCfg.stack) {
                sb.append(" d=").append(Reflect.depth());
            }
            sb.append(" src=").append(src);
            Diag.ev(sb.toString());
        } catch (Throwable t) {
            try {
                Diag.ev("P1E 组装失败: " + t);
            } catch (Throwable ignored) {
            }
        }
    }

    /** {@code before->after} 形式的 tx 描述。 */
    static String txPair(float before, float after) {
        return L.f(before) + "->" + L.f(after);
    }
}
