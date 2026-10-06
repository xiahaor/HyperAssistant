package com.qianxia.hyperassistant;

/**
 * 阶段判定（**只读**）。四个取值固定为：{@code 打开 / 返回 / 上滑 / 未知}。
 *
 * <h3>依据（反编译树，不是猜的）</h3>
 * <pre>
 * SlidingPaneStateManager.java:131  构造里 mCurrentState/mCurrentStableState = h.f11155c
 * h.java:11-13                       h$a = CloseState     a()=0.0f
 *                                    h$b = DragCloseState a()=0.0f
 *                                    h$c = OpenState      a()=1.0f
 * h.java:36-37                       public static final c f11154b = new c();   // OpenState 单例
 * </pre>
 * 所以 h$c = 负一屏打开态（"打开"），h$a = 关闭态（"返回"），h$b = 拖动关闭（"上滑"）。
 *
 * <p>判定优先级：<b>类名后缀（dex 真名）→ 其次才是 {@code a()} 进度值</b>。
 * 两者都会写进日志（{@code tgt=} 里带原始类名与 prog），所以映射一旦不符，
 * 用户从日志里一眼能看出来 —— 不允许"静默猜错"。
 */
final class Stage {

    static final String OPEN = "打开";
    static final String RETURN = "返回";
    static final String SWIPE = "上滑";
    static final String UNKNOWN = "未知";

    /** 三个非空阶段（供日志/文档列出）。 */
    static final String[] ALL = {OPEN, RETURN, SWIPE, UNKNOWN};

    private Stage() {
    }

    /**
     * 由 {@code com.miui.launcher.overlay.server.pane.h} 的实例判阶段。
     *
     * @param state 目标状态对象（可能是 h$a / h$b / h$c 三者之一，或 null）
     */
    static String ofState(Object state) {
        if (state == null) {
            return UNKNOWN;
        }
        String tail = tail(state.getClass());
        switch (tail) {
            case "c":                       // OpenState
                return OPEN;
            case "b":                       // DragCloseState
                return SWIPE;
            case "a":                       // CloseState
                return RETURN;
            default:
                break;
        }
        // 兜底：类名对不上时用进度值判（只区分 打开 / 返回）
        final float p = progress(state);
        // ★ 修复（2026-10-03 审计）：原来用 `p == 1.0f / p == 0.0f` **精确比较浮点** ——
        //   进度插值算出的 0.9999999 / 1.0E-7 会落进 UNKNOWN（阶段判错）。改成容差比较。
        //   （读不到进度时 progress() 返回 NaN，而 NaN 与任何数比较都是 false ⇒ 仍正确落 UNKNOWN。）
        if (Math.abs(p - 1f) < 1e-4f) {
            return OPEN;
        }
        if (Math.abs(p) < 1e-4f) {
            return RETURN;
        }
        return UNKNOWN;
    }

    /** 目标状态描述：原始类名 + dex 真名后缀 + 进度，供人工核对映射。 */
    static String label(Object state) {
        if (state == null) {
            return "null";
        }
        String n = state.getClass().getName();
        return n + "[" + tail(state.getClass()) + ",prog=" + L.f(progress(state)) + "]";
    }

    /**
     * 不借用 {@code h} 类引用，直接调它自己的 {@code a()}（反编译树 {@code h.java:81}
     * {@code public abstract float a();}，三个子类都 override）。
     * 读不到返回 NaN。
     */
    static float progress(Object state) {
        Object r = Reflect.callNoArg(state, "a");
        if (r instanceof Number) {
            return ((Number) r).floatValue();
        }
        return Float.NaN;
    }

    /** dex 真名后缀：{@code ...pane.h$c} → {@code c}；{@code ...pane.h} → {@code h}。 */
    static String tail(Class<?> c) {
        if (c == null) {
            return "?";
        }
        String n = c.getName();
        int i = Math.max(n.lastIndexOf('$'), n.lastIndexOf('.'));
        return i < 0 ? n : n.substring(i + 1);
    }
}
