package com.opencode.client;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 进程级崩溃日志（0.21.0）。
 *
 * 背景：Termux 侧看不到本 App 的 logcat（无 root、无 adb），一旦闪退就没法定位。
 * 做法：装一个全局未捕获异常处理器，闪退时把 时间/线程/机型/版本 + 完整堆栈
 * 写到共享存储 /sdcard/Download/opencode/crash-*.txt（Termux 可读），写不进去
 * 再退回 App 私有目录（Termux 读不到，至少留档）。零第三方依赖。
 *
 * install() 幂等，各 Activity.onCreate 都可调用，且发生在任何 UI 之前。
 */
public final class CrashLogger {

    /** Termux 可读的共享目录（与附件同目录）。 */
    public static final String SHARED_DIR = "/sdcard/Download/opencode";

    private static boolean installed = false;

    private CrashLogger() {
    }

    public static synchronized void install(Context ctx) {
        if (installed) return;
        installed = true;
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                write(app, t, e);
            } catch (Throwable ignored) {
                // 记日志本身绝不能二次崩溃
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    private static void write(Context ctx, Thread t, Throwable e) throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        pw.println("thread: " + (t != null ? t.getName() : "?"));
        pw.println("android: " + android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ")");
        pw.println("device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        pw.println("app: " + versionName(ctx));
        pw.println("------ stack ------");
        e.printStackTrace(pw);
        pw.flush();
        byte[] bytes = sw.toString().getBytes(StandardCharsets.UTF_8);
        String name = "crash-" + System.currentTimeMillis() + ".txt";

        // 1) 共享存储（Termux 可读）
        try {
            File dir = new File(SHARED_DIR);
            if (!dir.exists()) dir.mkdirs();
            FileOutputStream fo = new FileOutputStream(new File(dir, name));
            fo.write(bytes);
            fo.flush();
            fo.close();
            return;
        } catch (Throwable ignored) {
        }
        // 2) 退路：App 私有目录
        FileOutputStream fo = new FileOutputStream(new File(ctx.getFilesDir(), name));
        fo.write(bytes);
        fo.flush();
        fo.close();
    }

    /** 最新一份崩溃日志：共享目录优先，其次 App 私有目录；都没有回 null。 */
    public static File latest(Context ctx) {
        File best = newestIn(new File(SHARED_DIR));
        if (best != null) return best;
        return newestIn(ctx.getFilesDir());
    }

    private static File newestIn(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        File best = null;
        for (File f : fs) {
            String n = f.getName();
            if (!f.isFile() || !n.startsWith("crash-") || !n.endsWith(".txt")) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    public static String read(File f) {
        if (f == null) return "";
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return "读取失败：" + e.getMessage();
        }
    }

    private static String versionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "?";
        }
    }
}
