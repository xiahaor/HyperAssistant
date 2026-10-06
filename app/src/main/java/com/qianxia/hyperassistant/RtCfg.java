package com.qianxia.hyperassistant;

import android.content.Intent;

/**
 * v4：**运行期配置**（阶段 3）。
 *
 * <p>为什么必须这么做：{@link CoreConfig} / {@link Config} 里的开关原本是
 * {@code public static final} ⇒ **编译期常量会被内联进调用点**，App 在运行期根本改不动它们。
 * 本类把这批"要接管的开关"改成可写字段（非 final），并负责：
 * <ol>
 *   <li>{@link #apply(String, String)} —— 收到 App 的广播后**即时写进字段**（下一次逻辑生效）；</li>
 *   <li>{@link #persist()} / {@link #loadIfDue()} —— 落盘与冷启动恢复（节流 stat，热路径几乎零成本）；</li>
 *   <li>{@link #describe()} —— 一条可读的当前配置快照（日志用，便于"只看日志判断"）。</li>
 * </ol>
 *
 * <p>通道沿用 {@link Bridge} 已验证的那条（App → 广播 → 负一屏进程接收器），
 * **不依赖 /sdcard 写权限**；落盘路径是负一屏本来就在写的目录。
 */
final class RtCfg {

    /** App → hook：设置一个配置项。 */
    static final String ACTION_SETCFG = "com.qianxia.HyperAssistant.SETCFG";
    /** 键 / 值（值统一用字符串，解析失败即忽略并留痕）。 */
    static final String KEY_CFG_KEY = "k";
    static final String KEY_CFG_VAL = "v";

    /** 落盘路径：负一屏进程本来就在此目录写日志 ⇒ 无需额外权限。 */
    private static final String PATH = "/sdcard/Download/dsh-debuglog/animv4.conf";

    private static volatile long sLastStat;
    private static volatile long sLastMtime = -1L;

    private RtCfg() {
    }

    /** 被接管的键（App 侧清单应与之一致）。 */
    static final String[] KEYS = {
            "ENABLED", "ENABLE_OPEN_SF", "SWALLOW_MIUI_LEASH_WRITES",
            "ALLOW_MIUI_CROP", "ENABLE_OWN_CROP",
            "FIX_MASK_BUFFER", "MASK_ALPHA_FROM_ZERO", "MASK_NULL_USE_BLUR",
            "MASK_AS_BACKDROP", "ONLY_FIX_MASK_WHEN_WE_DRIVE", "MASK_NULL_BLUR_RADIUS",
            "ENABLE_RETURN_HANDOFF", "ENABLE_RETURN_SF_FALLBACK",
            "FORCE_REVERSE_ON_MERGE_1021", "RETURN_REVERSE_WRITES", "RETURN_SHRINK_RATIO",
            "LOG_LATENCY_CHAIN", "SF_VERBOSE_PARAM_DUMP", "DEV_FLASH_PROBE",
            "ENABLE_SYSTEM_SERVER_HOOK", "loglevel",
    };

    private static boolean asBool(String v, boolean dft) {
        if (v == null) {
            return dft;
        }
        final String s = v.trim();
        if ("1".equals(s) || "true".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s)) {
            return true;
        }
        if ("0".equals(s) || "false".equalsIgnoreCase(s) || "off".equalsIgnoreCase(s)) {
            return false;
        }
        return dft;
    }

    private static int asInt(String v, int dft) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return dft;
        }
    }

    private static float asFloat(String v, float dft) {
        try {
            return Float.parseFloat(v.trim());
        } catch (Throwable t) {
            return dft;
        }
    }

    /**
     * 应用一个配置项。
     *
     * @return true = 该键被接管且已写入；false = 未知键（调用方应留痕，不要静默）
     */
    static boolean apply(String key, String val) {
        if (key == null) {
            return false;
        }
        final String k = key.trim();
        try {
            if ("ENABLED".equals(k)) {
                CoreConfig.ENABLED = asBool(val, CoreConfig.ENABLED);
            } else if ("ENABLE_OPEN_SF".equals(k)) {
                CoreConfig.ENABLE_OPEN_SF = asBool(val, CoreConfig.ENABLE_OPEN_SF);
            } else if ("SWALLOW_MIUI_LEASH_WRITES".equals(k)) {
                CoreConfig.SWALLOW_MIUI_LEASH_WRITES = asBool(val, CoreConfig.SWALLOW_MIUI_LEASH_WRITES);
            } else if ("ALLOW_MIUI_CROP".equals(k)) {
                CoreConfig.ALLOW_MIUI_CROP = asBool(val, CoreConfig.ALLOW_MIUI_CROP);
            } else if ("ENABLE_OWN_CROP".equals(k)) {
                CoreConfig.ENABLE_OWN_CROP = asBool(val, CoreConfig.ENABLE_OWN_CROP);
            } else if ("FIX_MASK_BUFFER".equals(k)) {
                CoreConfig.FIX_MASK_BUFFER = asBool(val, CoreConfig.FIX_MASK_BUFFER);
            } else if ("MASK_ALPHA_FROM_ZERO".equals(k)) {
                CoreConfig.MASK_ALPHA_FROM_ZERO = asBool(val, CoreConfig.MASK_ALPHA_FROM_ZERO);
            } else if ("MASK_NULL_USE_BLUR".equals(k)) {
                CoreConfig.MASK_NULL_USE_BLUR = asBool(val, CoreConfig.MASK_NULL_USE_BLUR);
            } else if ("MASK_AS_BACKDROP".equals(k)) {
                CoreConfig.MASK_AS_BACKDROP = asBool(val, CoreConfig.MASK_AS_BACKDROP);
            } else if ("ONLY_FIX_MASK_WHEN_WE_DRIVE".equals(k)) {
                CoreConfig.ONLY_FIX_MASK_WHEN_WE_DRIVE = asBool(val, CoreConfig.ONLY_FIX_MASK_WHEN_WE_DRIVE);
            } else if ("MASK_NULL_BLUR_RADIUS".equals(k)) {
                CoreConfig.MASK_NULL_BLUR_RADIUS = asInt(val, CoreConfig.MASK_NULL_BLUR_RADIUS);
            } else if ("ENABLE_RETURN_HANDOFF".equals(k)) {
                CoreConfig.ENABLE_RETURN_HANDOFF = asBool(val, CoreConfig.ENABLE_RETURN_HANDOFF);
            } else if ("ENABLE_RETURN_SF_FALLBACK".equals(k)) {
                CoreConfig.ENABLE_RETURN_SF_FALLBACK = asBool(val, CoreConfig.ENABLE_RETURN_SF_FALLBACK);
            } else if ("FORCE_REVERSE_ON_MERGE_1021".equals(k)) {
                CoreConfig.FORCE_REVERSE_ON_MERGE_1021 = asBool(val, CoreConfig.FORCE_REVERSE_ON_MERGE_1021);
            } else if ("RETURN_REVERSE_WRITES".equals(k)) {
                CoreConfig.RETURN_REVERSE_WRITES = asInt(val, CoreConfig.RETURN_REVERSE_WRITES);
            } else if ("RETURN_SHRINK_RATIO".equals(k)) {
                CoreConfig.RETURN_SHRINK_RATIO = asFloat(val, CoreConfig.RETURN_SHRINK_RATIO);
            } else if ("LOG_LATENCY_CHAIN".equals(k)) {
                CoreConfig.LOG_LATENCY_CHAIN = asBool(val, CoreConfig.LOG_LATENCY_CHAIN);
            } else if ("SF_VERBOSE_PARAM_DUMP".equals(k)) {
                CoreConfig.SF_VERBOSE_PARAM_DUMP = asBool(val, CoreConfig.SF_VERBOSE_PARAM_DUMP);
            } else if ("DEV_FLASH_PROBE".equals(k)) {
                CoreConfig.DEV_FLASH_PROBE = asBool(val, CoreConfig.DEV_FLASH_PROBE);
            } else if ("ENABLE_SYSTEM_SERVER_HOOK".equals(k)) {
                Config.ENABLE_SYSTEM_SERVER_HOOK = asBool(val, Config.ENABLE_SYSTEM_SERVER_HOOK);
            } else if ("loglevel".equals(k)) {
                AnimCfg.setLoglevelFromPush(asInt(val, AnimCfg.loglevel));
            } else {
                return false;
            }
            return true;
        } catch (Throwable t) {
            Diag.key("运行期配置：应用 " + k + "=" + val + " 失败（已吞）: " + t);
            return false;
        }
    }

    /** 当前配置快照（一行，便于只看日志判断）。 */
    static String describe() {
        return "ENABLED=" + CoreConfig.ENABLED
                + " OPEN_SF=" + CoreConfig.ENABLE_OPEN_SF
                + " SWALLOW=" + CoreConfig.SWALLOW_MIUI_LEASH_WRITES
                + " MIUI_CROP=" + CoreConfig.ALLOW_MIUI_CROP
                + " OWN_CROP=" + CoreConfig.ENABLE_OWN_CROP
                + " FIX_MASK=" + CoreConfig.FIX_MASK_BUFFER
                + " ALPHA0=" + CoreConfig.MASK_ALPHA_FROM_ZERO
                + " MASK_BLUR=" + CoreConfig.MASK_NULL_USE_BLUR
                + " BACKDROP=" + CoreConfig.MASK_AS_BACKDROP
                + " RET_HANDOFF=" + CoreConfig.ENABLE_RETURN_HANDOFF
                + " RET_SF=" + CoreConfig.ENABLE_RETURN_SF_FALLBACK
                + " REV_1021=" + CoreConfig.FORCE_REVERSE_ON_MERGE_1021
                + " REV_WRITES=" + CoreConfig.RETURN_REVERSE_WRITES
                + " SHRINK=" + CoreConfig.RETURN_SHRINK_RATIO
                + " SYS_HOOK=" + Config.ENABLE_SYSTEM_SERVER_HOOK
                + " loglevel=" + AnimCfg.loglevel;
    }

    /** 把当前值落盘（App 侧下次打开即可显示设备真值）。 */
    static void persist() {
        try {
            final StringBuilder sb = new StringBuilder();
            sb.append("# AssistantAnim v4 运行期配置（hook 侧落盘；App 也可读）\n");
            sb.append("ENABLED=").append(CoreConfig.ENABLED).append('\n');
            sb.append("ENABLE_OPEN_SF=").append(CoreConfig.ENABLE_OPEN_SF).append('\n');
            sb.append("SWALLOW_MIUI_LEASH_WRITES=").append(CoreConfig.SWALLOW_MIUI_LEASH_WRITES).append('\n');
            sb.append("ALLOW_MIUI_CROP=").append(CoreConfig.ALLOW_MIUI_CROP).append('\n');
            sb.append("ENABLE_OWN_CROP=").append(CoreConfig.ENABLE_OWN_CROP).append('\n');
            sb.append("FIX_MASK_BUFFER=").append(CoreConfig.FIX_MASK_BUFFER).append('\n');
            sb.append("MASK_ALPHA_FROM_ZERO=").append(CoreConfig.MASK_ALPHA_FROM_ZERO).append('\n');
            sb.append("MASK_NULL_USE_BLUR=").append(CoreConfig.MASK_NULL_USE_BLUR).append('\n');
            sb.append("MASK_AS_BACKDROP=").append(CoreConfig.MASK_AS_BACKDROP).append('\n');
            sb.append("ONLY_FIX_MASK_WHEN_WE_DRIVE=").append(CoreConfig.ONLY_FIX_MASK_WHEN_WE_DRIVE).append('\n');
            sb.append("MASK_NULL_BLUR_RADIUS=").append(CoreConfig.MASK_NULL_BLUR_RADIUS).append('\n');
            sb.append("ENABLE_RETURN_HANDOFF=").append(CoreConfig.ENABLE_RETURN_HANDOFF).append('\n');
            sb.append("ENABLE_RETURN_SF_FALLBACK=").append(CoreConfig.ENABLE_RETURN_SF_FALLBACK).append('\n');
            sb.append("FORCE_REVERSE_ON_MERGE_1021=").append(CoreConfig.FORCE_REVERSE_ON_MERGE_1021).append('\n');
            sb.append("RETURN_REVERSE_WRITES=").append(CoreConfig.RETURN_REVERSE_WRITES).append('\n');
            sb.append("RETURN_SHRINK_RATIO=").append(CoreConfig.RETURN_SHRINK_RATIO).append('\n');
            sb.append("LOG_LATENCY_CHAIN=").append(CoreConfig.LOG_LATENCY_CHAIN).append('\n');
            sb.append("SF_VERBOSE_PARAM_DUMP=").append(CoreConfig.SF_VERBOSE_PARAM_DUMP).append('\n');
            sb.append("DEV_FLASH_PROBE=").append(CoreConfig.DEV_FLASH_PROBE).append('\n');
            sb.append("ENABLE_SYSTEM_SERVER_HOOK=").append(Config.ENABLE_SYSTEM_SERVER_HOOK).append('\n');
            sb.append("loglevel=").append(AnimCfg.loglevel).append('\n');
            final java.io.FileOutputStream fos = new java.io.FileOutputStream(PATH);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {
            Diag.key("运行期配置：落盘失败（已吞）: " + t);
        }
    }

    /** 冷启动/周期性恢复：文件变了才读（节流 500ms，热路径一次 stat）。 */
    static void loadIfDue() {
        try {
            final long now = System.currentTimeMillis();
            if (now - sLastStat < 500L) {
                return;
            }
            sLastStat = now;
            final java.io.File f = new java.io.File(PATH);
            if (!f.exists()) {
                return;
            }
            final long m = f.lastModified();
            if (m == sLastMtime) {
                return;
            }
            sLastMtime = m;
            final java.io.BufferedReader r =
                    new java.io.BufferedReader(new java.io.InputStreamReader(
                            new java.io.FileInputStream(f), "UTF-8"));
            String line;
            int n = 0;
            while ((line = r.readLine()) != null) {
                final int eq = line.indexOf('=');
                if (eq <= 0 || line.startsWith("#")) {
                    continue;
                }
                if (apply(line.substring(0, eq), line.substring(eq + 1))) {
                    n++;
                }
            }
            r.close();
            Diag.key("运行期配置：已从文件恢复 " + n + " 项 ⇒ " + describe());
        } catch (Throwable t) {
            Diag.key("运行期配置：读取失败（已吞）: " + t);
        }
    }
}
