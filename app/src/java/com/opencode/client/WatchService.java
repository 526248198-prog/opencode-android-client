package com.opencode.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 0.22.6 前台 Service 兜底（跑完叫你）：
 *
 * 旧实现（0.15.0）依赖列表页.onResume 的 15 秒轮询 + App 被杀前的收尾通知，
 * App 切后台被系统冻结/回收进程后就没人监听了，会话跑完用户不知道。
 *
 * 本服务只在「App 前台」时机启动（ChatActivity 发消息记 await、列表页 onResume
 * 发现在等项时 ensureStarted）——Android 14+ 后台起 FGS 会抛
 * ForegroundServiceStartNotAllowedException，绝不在后台调 startForegroundService。
 * 起来后常驻前台（dataSync 类型，Android 14+ 必须声明）15 秒轮询
 * GET /api/session/active：await_<sid> 消失 = 跑完 → 复用 MainActivity.notifyDone()
 * 发通知 → 清 await → 全部等项清空则自停（不空转耗电）。
 *
 * 与 MainActivity.checkDoneOnce() 的双通知防重：claimDone() 同步集合抢号，
 * 谁先 add 成功谁出通知（见 MainActivity.CLAIMED）。
 *
 * D8 约束：禁匿名内部类/非静态内部类（doc §3.1），本类为顶层类，全 lambda/具名。
 */
public class WatchService extends Service {

    public static volatile boolean alive = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread worker;

    /** App 前台时机调：有在等项才起（起服务也要求 system 有前台豁免，这里只在 Activity 界面里调）。 */
    public static void ensureStarted(Context ctx) {
        try {
            if (alive) return;
            if (MainActivity.awaitingMap(ctx).isEmpty()) return; // 无在等项不起，省电
            Intent i = new Intent(ctx, WatchService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception ignored) {
            // 真机上偶发后台拒绝：兜底失败退回旧行为（列表页轮询），无害
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        alive = true;
        startForegroundCompat();
        startWorker();
        return START_STICKY;
    }

    /** 前台常驻通知（低重要性，常驻不响铃）；29+ 显式声明 dataSync 类型。 */
    private void startForegroundCompat() {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(
                        "watch", "运行监听", NotificationManager.IMPORTANCE_LOW));
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, "watch")
                    : new Notification.Builder(this);
            b.setContentTitle("OpenCode 手机端")
                    .setContentText("正在监听 AI 运行结果，跑完会提醒你")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true);
            Notification n = b.build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(2001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(2001, n);
            }
        } catch (Exception e) {
            // 起前台失败没法履行 FGS 契约，直接退掉走旧轮询
            stopSelf();
        }
    }

    private synchronized void startWorker() {
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(this::workLoop, "watch-poll");
        worker.start();
    }

    private void workLoop() {
        while (alive) {
            try {
                Map<String, String> awaiting = MainActivity.awaitingMap(this);
                if (awaiting.isEmpty()) {
                    finishQuietly();
                    break; // 无在等项：自停
                }
                JSONObject data = pollActive();
                if (data != null) {
                    for (Map.Entry<String, String> e : awaiting.entrySet()) {
                        String sid = e.getKey();
                        if (data.has(sid)) continue; // 还在跑
                        if (!MainActivity.claimDone(sid)) continue; // 别处已出过
                        String t = e.getValue();
                        MainActivity.clearAwaiting(this, sid);
                        MainActivity.markJustDone(this, sid);
                        MainActivity.notifyDone(this, sid, t);
                    }
                }
            } catch (Exception ignored) {
                // 网络抖动：下个周期重试
            }
            try {
                Thread.sleep(15000);
            } catch (InterruptedException ie) {
                break;
            }
        }
    }

    /** GET /api/session/active（同 MainActivity.checkDoneOnce 的实现，零依赖 HttpURLConnection）。 */
    private JSONObject pollActive() {
        try {
            URL url = new URL(MainActivity.BASE_URL + "/api/session/active");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            MainActivity.auth(c);            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setRequestMethod("GET");
            if (c.getResponseCode() != 200) return null;
            InputStream is = c.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) > 0) sb.append(new String(buf, 0, r, StandardCharsets.UTF_8));
            is.close();
            c.disconnect();
            return new JSONObject(sb.toString()).optJSONObject("data");
        } catch (Exception e) {
            return null;
        }
    }

    /** 主线程收尾（Service 非 UI，stopForeground 走主线程稳一点，失败也无害）。 */
    private void finishQuietly() {
        main.post(() -> {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } catch (Throwable ignored) {
                try {
                    stopForeground(true);
                } catch (Exception ignored2) {
                }
            }
            stopSelf();
        });
    }

    @Override
    public void onDestroy() {
        alive = false;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }
}
