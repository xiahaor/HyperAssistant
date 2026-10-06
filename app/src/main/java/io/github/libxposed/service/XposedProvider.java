package io.github.libxposed.service;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;

/**
 * P90：自写强化版 provider（<b>全部本文件内实现 ✓ 未搬运任何第三方源码 ✓</b>）。
 *
 * <p>它只做与框架<b>互操作所必需</b>的两件事（都是接口约定 ✓ 不是实现 ✓）：
 * <ol>
 *   <li>能被解析：清单里 {@code authority = <pkg>.XposedService} ✓；</li>
 *   <li>收下框架送来的服务 binder：{@code call("SendBinder", …, bundle)} ✓，
 *       从 {@code bundle.getBinder("binder")} 取出 ✓。</li>
 * </ol>
 *
 * <p>⚠️ 我们<b>不使用</b>该服务（不需要动态申请作用域 ✓ —— 作用域由用户在管理器里勾选 ✓，
 * 已在 LSPosed 数据库实证 ✓）。这里只是"收下 + 记录 + 留证据" ✓。
 *
 * <p><b>判据（本类自带证据 ✓）</b>：被调用时写一行到
 * {@code /sdcard/Download/dsh-debuglog/ANIMV3-SVCBINDER.txt} ✓
 * ⇒ 只要该文件出现 ✓，就证明"框架确实来找过我们" ✓。
 */
public final class XposedProvider extends ContentProvider {

    private static final String TAG = "AssistantAnimSvc";
    /** 收下的框架服务 binder（仅供诊断 ✓ 不使用 ✓）。 */
    private static volatile IBinder sFrameworkBinder;

    /** 写证据文件（三个候选目录都试 ✓ 与 Diag 的策略一致 ✓）。 */
    private static void evidence(String line) {
        String[] dirs = {
                "/sdcard/Download/dsh-debuglog",
                "/storage/emulated/0/Download/dsh-debuglog",
                "/data/local/tmp",
        };
        for (String d : dirs) {
            try {
                File dir = new File(d);
                if (!dir.exists() && !dir.mkdirs()) {
                    continue;
                }
                File f = new File(dir, "ANIMV3-SVCBINDER.txt");
                try (FileWriter w = new FileWriter(f, true)) {
                    w.write(line + "\n");
                }
                return;
            } catch (Throwable ignored) {
                // 换下一个目录
            }
        }
    }

    @Override
    public boolean onCreate() {
        Log.i(TAG, "provider onCreate");
        evidence("onCreate pid=" + android.os.Process.myPid() + " uid=" + android.os.Process.myUid());
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        try {
            String desc = (extras == null) ? "-" : extras.toString();
            Log.i(TAG, "call method=" + method + " arg=" + arg + " extras=" + desc);
            evidence("call method=" + method + " arg=" + arg);

            if (!"SendBinder".equals(method) || extras == null) {
                return null;
            }
            IBinder b = extras.getBinder("binder");
            evidence("getBinder(binder)=" + b);
            if (b == null) {
                return null;
            }
            sFrameworkBinder = b;                       // ★ 收下（不使用 ✓）
            String local = null;
            try {
                local = String.valueOf(b.queryLocalInterface(
                        "io.github.libxposed.service.IXposedService"));
            } catch (Throwable t) {
                local = "queryLocalInterface 抛异常: " + t;
            }
            evidence("收下 binder=" + b + " queryLocalInterface=" + local
                    + " isBinderAlive=" + b.isBinderAlive());
            Log.i(TAG, "framework binder stored: " + b);
        } catch (Throwable t) {
            Log.e(TAG, "call 失败", t);
            evidence("call 异常: " + t);
        }
        return null;
    }

    /** 供将来诊断用 ✓（当前无人调用 ✓）。 */
    public static IBinder frameworkBinder() {
        return sFrameworkBinder;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
