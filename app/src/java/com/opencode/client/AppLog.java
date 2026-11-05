package com.opencode.client;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 轻量运行日志（0.22.10）。
 *
 * 为什么不用 logcat：Termux 侧看不到本 App 的 logcat（无 root/adb），断线是间歇性的，
 * 没有时间线根本没法判断「到底断在哪一步」。这里把网络重连/请求失败/恢复写到
 * /sdcard/Download/opencode/app.log（Termux 可直接 tail），超过 1MB 自动清空重来。
 *
 * 只在状态切换时写（连接成功/首次失败/恢复），不会每个心跳都刷。
 * 零第三方依赖；任何异常都吞掉，日志绝不能影响主流程。
 */
public final class AppLog {

    private static final String FILE = CrashLogger.SHARED_DIR + "/app.log";
    private static final long MAX_BYTES = 1024 * 1024;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private AppLog() {
    }

    public static synchronized void log(String msg) {
        try {
            File f = new File(FILE);
            if (f.length() > MAX_BYTES) {
                // 留最近一份即可：直接截断重写。
                try {
                    new FileWriter(f, false).close();
                } catch (Throwable ignored) {
                }
            }
            FileWriter w = new FileWriter(f, true);
            try {
                w.write(FMT.format(new Date()) + " | " + msg + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
            // 写不进去就算了（没给存储权限时）
        }
    }
}
