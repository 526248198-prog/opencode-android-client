package com.opencode.client;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Phase 2: session list + create. No external deps.
 */
public class MainActivity extends Activity {

    private static final String BASE_URL = "http://127.0.0.1:4097";
    private static final String SERVER_USER = "opencode";
    // 密码不写死在源码里（本仓库公开）。优先读 PASSWORD_FILE 第一行；读不到用占位符，
    // 你也可以把下面这行直接改成自己的密码（但别把改后的版本推上来）。
    private static final String PASSWORD_FILE =
            "/sdcard/Download/opencode/server_password.txt";
    private static final String SERVER_PASSWORD = loadServerPassword();
    private static final String SERVE_CMD =
            "OPENCODE_SERVER_PASSWORD=" + SERVER_PASSWORD
                    + " opencode serve --hostname 127.0.0.1 --port 4097 --print-logs";
    private static final String INSTALL_ACTION = "com.opencode.client.INSTALL_RESULT";

    /** 读密码文件首行；不存在/为空/无权限一律回落占位符（0.21.6 起，公开仓库不带真实密码）。 */
    private static String loadServerPassword() {
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(
                    new FileInputStream(new File(PASSWORD_FILE)), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line != null) {
                line = line.trim();
                if (line.length() > 0) return line;
            }
        } catch (Throwable ignored) {
            // 文件缺失或读不到：走占位符
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) { }
            }
        }
        return "YOUR_SERVER_PASSWORD";
    }

    /** V2 server needs HTTP Basic auth (loopback only, same trust as before). */
    static void auth(HttpURLConnection c) {
        String cred = SERVER_USER + ":" + SERVER_PASSWORD;
        String b64 = android.util.Base64.encodeToString(
                cred.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        c.setRequestProperty("Authorization", "Basic " + b64);
    }

    private static String apkPath() {
        return android.os.Environment.getExternalStorageDirectory()
                + "/opencode伪安卓版/app/OpenCodeClient.apk";
    }

    /** One-tap update: installs the APK sitting next to this project via PackageInstaller. */
    private void installUpdate() {
        final java.io.File apk = new java.io.File(apkPath());
        if (!apk.exists()) {
            Toast.makeText(this, "没找到安装包，请确认项目还在手机存储", Toast.LENGTH_LONG).show();
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 30
                && !android.os.Environment.isExternalStorageManager()) {
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception e) {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())));
            }
            Toast.makeText(this, "请打开“所有文件访问权限”，然后回来再点一次安装更新",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 26
                && !getPackageManager().canRequestPackageInstalls()) {
            startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "请允许“安装未知应用”，然后回来再点一次安装更新",
                    Toast.LENGTH_LONG).show();
            return;
        }
        // install result comes back to this activity (singleTop + onNewIntent below)
        new Thread(() -> {
            try {
                android.content.pm.PackageInstaller pi = getPackageManager().getPackageInstaller();
                android.content.pm.PackageInstaller.SessionParams params =
                        new android.content.pm.PackageInstaller.SessionParams(
                                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int sid = pi.createSession(params);
                android.content.pm.PackageInstaller.Session session = pi.openSession(sid);
                try (java.io.OutputStream out = session.openWrite("apk", 0, apk.length());
                     java.io.InputStream in = new java.io.FileInputStream(apk)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                android.content.Intent result = new android.content.Intent(this, MainActivity.class);
                result.setAction(INSTALL_ACTION);
                int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    flags |= android.app.PendingIntent.FLAG_MUTABLE;
                }
                android.app.PendingIntent pi2 = android.app.PendingIntent.getActivity(
                        this, 1001, result, flags);
                session.commit(pi2.getIntentSender());
                session.close();
                runOnUiThread(() -> Toast.makeText(this, "正在安装，看系统弹窗确认",
                        Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "安装失败：" + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleInstallResult(intent);
    }

    private void handleInstallResult(android.content.Intent intent) {
        if (intent == null || !INSTALL_ACTION.equals(intent.getAction())) return;
        if (!intent.hasExtra(android.content.pm.PackageInstaller.EXTRA_STATUS)) return;
        int status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1);
        if (status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // System asks the user to confirm: the confirm dialog NEVER shows
            // by itself, the app MUST start EXTRA_INTENT (0.11.5: this branch
            // was missing, so every install ended as "安装返回：null" with no
            // dialog — the button never worked once).
            android.content.Intent confirm =
                    intent.getParcelableExtra(android.content.Intent.EXTRA_INTENT);
            if (confirm != null) {
                try {
                    startActivity(confirm);
                    return;
                } catch (Exception e) {
                    Toast.makeText(this, "拉不起确认框：" + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    return;
                }
            }
            Toast.makeText(this, "系统要确认但没给确认框，请重试", Toast.LENGTH_LONG).show();
            return;
        }
        String msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE);
        Toast.makeText(this,
                status == android.content.pm.PackageInstaller.STATUS_SUCCESS
                        ? "安装成功" : "安装返回(" + status + ")：" + msg,
                Toast.LENGTH_LONG).show();
    }

    private TextView statusView;
    private Button refreshBtn;
    private Button newBtn;
    private Button stopBridgeBtn;
    private Button crashBtn; // 崩溃日志入口（0.21.0），有日志才显示
    private ListView listView;
    private ArrayAdapter<String> adapter;
    private final ArrayList<String> titles = new ArrayList<>();
    private final ArrayList<String> ids = new ArrayList<>();
    // 跑完叫你（功能2）：在等结果的会话 + 刚完成的绿勾 + 15秒轮询
    private final android.os.Handler doneHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable doneTask = null;
    private boolean listVisible = false;

    /** SharedPreferences：await_<sid>=标题（在等结果），done_<sid>=1（刚完成待看）。 */
    private static android.content.SharedPreferences prefs(android.content.Context ctx) {
        return ctx.getSharedPreferences("opencode_done", MODE_PRIVATE);
    }

    static void markAwaiting(android.content.Context ctx, String sid, String title) {
        if (sid == null || sid.isEmpty()) return;
        prefs(ctx).edit().putString("await_" + sid, title != null ? title : sid).apply();
    }

    static void clearAwaiting(android.content.Context ctx, String sid) {
        if (sid == null || sid.isEmpty()) return;
        prefs(ctx).edit().remove("await_" + sid).apply();
    }

    static java.util.Map<String, String> awaitingMap(android.content.Context ctx) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, ?> e : prefs(ctx).getAll().entrySet()) {
            if (e.getKey().startsWith("await_") && e.getValue() instanceof String) {
                out.put(e.getKey().substring(6), (String) e.getValue());
            }
        }
        return out;
    }

    static void markJustDone(android.content.Context ctx, String sid) {
        if (sid == null || sid.isEmpty()) return;
        prefs(ctx).edit().putString("done_" + sid, "1").apply();
    }

    static boolean isJustDone(android.content.Context ctx, String sid) {
        return prefs(ctx).contains("done_" + sid);
    }

    static void clearJustDone(android.content.Context ctx, String sid) {
        prefs(ctx).edit().remove("done_" + sid).apply();
    }

    /** 系统通知：点之回到该会话聊天页。无通知权限时系统直接丢弃，不崩。 */
    static void notifyDone(android.content.Context ctx, String sid, String title) {
        try {
            String name = "跑完提醒";
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        "done", name, android.app.NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            android.content.Intent it = new android.content.Intent(ctx, ChatActivity.class);
            it.putExtra("sessionID", sid);
            it.putExtra("title", title != null ? title : sid);
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 31) flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    ctx, sid.hashCode(), it, flags);
            android.app.Notification.Builder b =
                    android.os.Build.VERSION.SDK_INT >= 26
                            ? new android.app.Notification.Builder(ctx, "done")
                            : new android.app.Notification.Builder(ctx);
            b.setContentTitle("AI 跑完了")
                    .setContentText(title != null ? title : sid)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            try {
                b.setDefaults(android.app.Notification.DEFAULT_ALL);
            } catch (Exception ignored) {
            }
            nm.notify(sid.hashCode(), b.build());
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLogger.install(this); // 进程级崩溃日志（0.21.0）：闪退写 /sdcard/Download/opencode/crash-*.txt

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(24), pad, pad);
        root.setBackgroundColor(Color.parseColor("#F5F5F5"));

        TextView title = new TextView(this);
        title.setText("OpenCode 会话");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(Color.parseColor("#222222"));
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("正在连接服务器…");
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusView.setTextColor(Color.parseColor("#666666"));
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(4);
        root.addView(statusView, stLp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(12);
        root.addView(btnRow, rowLp);

        refreshBtn = new Button(this);
        refreshBtn.setText("刷新");
        refreshBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        rLp.rightMargin = dp(8);
        btnRow.addView(refreshBtn, rLp);
        refreshBtn.setOnClickListener(v -> loadSessions());

        newBtn = new Button(this);
        newBtn.setText("＋ 新建会话");
        newBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        nLp.leftMargin = dp(8);
        btnRow.addView(newBtn, nLp);
        newBtn.setOnClickListener(v -> createSession());

        listView = new ListView(this);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lLp.topMargin = dp(12);
        root.addView(listView, lLp);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, titles);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String sid = ids.get(position);
            String line = titles.get(position);
            String firstLine = line.contains("\n") ? line.substring(0, line.indexOf("\n")) : line;
            if (firstLine.startsWith("✅ ")) firstLine = firstLine.substring(2);
            if (firstLine.startsWith("⏳ ")) firstLine = firstLine.substring(2);
            clearJustDone(this, sid); // 点进去看了，绿勾清掉；等结果标记由聊天页按 active 对账
            adapter.notifyDataSetChanged();
            android.content.Intent it = new android.content.Intent(this, ChatActivity.class);
            it.putExtra("sessionID", sid);
            it.putExtra("title", firstLine);
            startActivity(it);
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            showSessionMenu(position);
            return true;
        });

        // footer: server start command + copy + in-app update install
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.VERTICAL);
        foot.setPadding(dp(4), dp(8), dp(4), dp(4));
        root.addView(foot);

        TextView cmdView = new TextView(this);
        cmdView.setText(SERVE_CMD);
        cmdView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        cmdView.setTextColor(Color.parseColor("#999999"));
        foot.addView(cmdView);

        LinearLayout footRow = new LinearLayout(this);
        footRow.setOrientation(LinearLayout.HORIZONTAL);
        footRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams frLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        frLp.topMargin = dp(6);
        foot.addView(footRow, frLp);

        Button copyBtn = new Button(this);
        copyBtn.setText("复制指令");
        copyBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        copyBtn.setMinHeight(dp(48));
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        cbLp.rightMargin = dp(4);
        footRow.addView(copyBtn, cbLp);
        copyBtn.setOnClickListener(v -> {
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("opencode", SERVE_CMD));
                Toast.makeText(this, "已复制，去 Termux 粘贴回车就行", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "复制失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        Button updateBtn = new Button(this);
        updateBtn.setText("安装更新");
        updateBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        updateBtn.setMinHeight(dp(48));
        LinearLayout.LayoutParams ubLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        ubLp.leftMargin = dp(4);
        footRow.addView(updateBtn, ubLp);
        updateBtn.setOnClickListener(v -> installUpdate());

        Button browserBtn = new Button(this);
        browserBtn.setText("🌐 浏览器");
        browserBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        browserBtn.setMinHeight(dp(48));
        LinearLayout.LayoutParams bbLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        bbLp.leftMargin = dp(4);
        footRow.addView(browserBtn, bbLp);
        browserBtn.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, BrowserActivity.class)));

        stopBridgeBtn = new Button(this);
        stopBridgeBtn.setText("停桥");
        stopBridgeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        stopBridgeBtn.setMinHeight(dp(48));
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        sbLp.leftMargin = dp(4);
        footRow.addView(stopBridgeBtn, sbLp);
        stopBridgeBtn.setVisibility(Button.GONE);
        stopBridgeBtn.setOnClickListener(v -> {
            BrowserActivity.stopLive();
            stopBridgeBtn.setVisibility(Button.GONE);
            Toast.makeText(this, "桥已停", Toast.LENGTH_SHORT).show();
        });

        // 崩溃日志入口（0.21.0）：平时隐藏，检测到 crash-*.txt 才出现
        LinearLayout footRow2 = new LinearLayout(this);
        footRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams fr2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fr2Lp.topMargin = dp(6);
        foot.addView(footRow2, fr2Lp);

        crashBtn = new Button(this);
        crashBtn.setText("🐞 查看崩溃日志");
        crashBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        crashBtn.setMinHeight(dp(48));
        footRow2.setGravity(android.view.Gravity.CENTER_VERTICAL);
        footRow2.addView(crashBtn, new LinearLayout.LayoutParams(
                0, dp(48), 1f));
        crashBtn.setVisibility(Button.GONE);
        crashBtn.setOnClickListener(v -> showCrashLog());

        // 0.21.10：底部显示版本号，装没装上、装的是哪版一眼能看出来
        TextView verTv = new TextView(this);
        verTv.setText("v" + appVersionName());
        verTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        verTv.setTextColor(Color.parseColor("#999999"));
        verTv.setPadding(dp(6), 0, dp(4), 0);
        footRow2.addView(verTv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        loadSessions();
        handleInstallResult(getIntent());
        // Android 13+ 发通知要运行时权限：没给则通知被静默丢弃，列表绿勾不受影响
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9001);
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        listVisible = true;
        if (stopBridgeBtn != null) {
            boolean on = false;
            try { on = BrowserActivity.liveOn(); } catch (Exception ignored) { }
            stopBridgeBtn.setVisibility(on ? Button.VISIBLE : Button.GONE);
        }
        if (crashBtn != null) {
            boolean hasCrash = false;
            try { hasCrash = CrashLogger.latest(this) != null; } catch (Exception ignored) { }
            crashBtn.setVisibility(hasCrash ? Button.VISIBLE : Button.GONE);
        }
        loadSessions(); // 回来即重拉，刚完成的绿勾第一时间可见
        startDoneWatch();
    }

    @Override
    protected void onPause() {
        listVisible = false;
        stopDoneWatch();
        super.onPause();
    }

    /** 列表页 visible 时每 15 秒对一次账：在等但已不在 active 表 = 跑完了。 */
    private void startDoneWatch() {
        stopDoneWatch();
        doneTask = () -> checkDoneOnce();
        doneHandler.postDelayed(doneTask, 15000);
        checkDoneOnce(); // 回来立刻查一次，不用等 15 秒
    }

    private void stopDoneWatch() {
        if (doneTask != null) {
            try {
                doneHandler.removeCallbacks(doneTask);
            } catch (Exception ignored) {
            }
            doneTask = null;
        }
    }

    private void checkDoneOnce() {
        doneTask = null;
        new Thread(() -> {
            try {
                java.util.Map<String, String> awaiting = awaitingMap(this);
                if (awaiting.isEmpty()) return;
                URL url = new URL(BASE_URL + "/api/session/active");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setRequestMethod("GET");
                if (c.getResponseCode() != 200) return;
                JSONObject data = new JSONObject(readAll(c.getInputStream())).optJSONObject("data");
                final java.util.ArrayList<String> finished = new java.util.ArrayList<>();
                for (String sid : awaiting.keySet()) {
                    if (data == null || !data.has(sid)) finished.add(sid);
                }
                if (finished.isEmpty()) return;
                runOnUiThread(() -> {
                    for (String sid : finished) {
                        String t = awaiting.get(sid);
                        clearAwaiting(this, sid);
                        markJustDone(this, sid);
                        notifyDone(this, sid, t);
                    }
                    loadSessions(); // 刷新绿勾
                    Toast.makeText(this, "有 " + finished.size() + " 个会话跑完了", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception ignored) {
            } finally {
                if (doneTask == null && listVisible) {
                    doneTask = () -> checkDoneOnce();
                    try {
                        doneHandler.postDelayed(doneTask, 15000);
                    } catch (Exception ignored) {
                    }
                }
            }
        }).start();
    }

    private void showSessionMenu(int position) {
        String sid = ids.get(position);
        String line = titles.get(position);
        String cur = line.contains("\n") ? line.substring(0, line.indexOf("\n")) : line;
        String[] items = {"改名", "删除", "取消"};
        new android.app.AlertDialog.Builder(this)
                .setTitle(cur)
                .setItems(items, (d, which) -> {
                    if (which == 0) showRenameDialog(sid, cur);
                    else if (which == 1) showDeleteConfirm(sid, cur);
                })
                .show();
    }

    private void showRenameDialog(String sid, String cur) {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(cur);
        et.setSingleLine(true);
        new android.app.AlertDialog.Builder(this)
                .setTitle("改名")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> renameSession(sid, et.getText().toString().trim()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void renameSession(String sid, String name) {
        if (name.isEmpty()) {
            Toast.makeText(this, "名字不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true);
        statusView.setText("正在改名…");
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/api/session/" + sid);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setRequestMethod("PATCH");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                JSONObject body = new JSONObject();
                body.put("title", name);
                byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
                OutputStream os = c.getOutputStream();
                os.write(out);
                os.flush();
                os.close();
                int rc = c.getResponseCode();
                if (rc != 200 && rc != 204) throw new Exception("HTTP " + rc);
                runOnUiThread(() -> {
                    Toast.makeText(this, "已改名", Toast.LENGTH_SHORT).show();
                    loadSessions();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusView.setText("改名失败：" + e.getMessage());
                    setBusy(false);
                });
            }
        }).start();
    }

    private void showDeleteConfirm(String sid, String cur) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除会话？")
                .setMessage(cur + "\n删除后聊天记录无法恢复。")
                .setPositiveButton("删除", (d, w) -> deleteSession(sid))
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteSession(String sid) {
        setBusy(true);
        statusView.setText("正在删除…");
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/api/session/" + sid);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setRequestMethod("DELETE");
                int dcode = c.getResponseCode();
                if (dcode != 200 && dcode != 204) throw new Exception("HTTP " + dcode);
                runOnUiThread(() -> {
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    loadSessions();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusView.setText("删除失败：" + e.getMessage());
                    setBusy(false);
                });
            }
        }).start();
    }

    /** 0.21.10：读当前安装版本号，底部小字显示，便于确认「安装更新」是否真装上。 */
    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    /** tokens compact: 9500 -> "9500", 46000 -> "4.6万". */
    static String formatTokens(long n) {
        if (n < 10000) return String.valueOf(n);
        double w = n / 10000.0;
        String s = String.format(Locale.US, "%.1f", w);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + "万";
    }

    /** cost compact: 0 -> "$0", 0.0042 -> "$0.0042", 1.2 -> "$1.20". */
    static String formatCost(double c) {
        if (c == 0) return "$0";
        if (c < 0.01) return "$" + String.format(Locale.US, "%.4f", c);
        return "$" + String.format(Locale.US, "%.2f", c);
    }

    /** " · tokens 4.6万 · $0.12", empty when session has no usage yet. */
    static String sessionCostLine(JSONObject s) {
        double cost = s.optDouble("cost", 0);
        JSONObject t = s.optJSONObject("tokens");
        long total = 0;
        if (t != null) total = t.optLong("input", 0) + t.optLong("output", 0);
        if (total <= 0 && cost == 0) return "";
        return " · tokens " + formatTokens(total) + " · " + formatCost(cost);
    }

    private void setBusy(boolean busy) {
        refreshBtn.setEnabled(!busy);
        newBtn.setEnabled(!busy);
    }

    private void loadSessions() {
        setBusy(true);
        statusView.setText("正在加载会话…");
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/api/session");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setRequestMethod("GET");
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                String body = readAll(c.getInputStream());
                JSONArray arr = new JSONObject(body).getJSONArray("data");
                final ArrayList<String> nt = new ArrayList<>();
                final ArrayList<String> ni = new ArrayList<>();
                SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject s = arr.getJSONObject(i);
                    String sid = s.optString("id", "?");
                    String stitle = s.optString("title", "(无标题)");
                    long updated = s.optJSONObject("time") != null
                            ? s.optJSONObject("time").optLong("updated", 0) : 0;
                    String when = updated > 0 ? fmt.format(new Date(updated)) : "";
                    String model = "";
                    JSONObject m = s.optJSONObject("model");
                    if (m != null) {
                        model = m.optString("id", "");
                        if (model.isEmpty()) {
                            String mp = m.optString("providerID", "");
                            String mm = m.optString("modelID", "");
                            if (!mm.isEmpty()) model = (mp.isEmpty() ? "" : mp + "/") + mm;
                        }
                    }
                    String line = stitle + (when.isEmpty() ? "" : "\n" + when)
                            + (model.isEmpty() ? "" : "  " + model)
                            + sessionCostLine(s);
                    if (isJustDone(MainActivity.this, sid)) line = "✅ " + line;
                    else if (prefs(MainActivity.this).contains("await_" + sid)) line = "⏳ " + line;
                    nt.add(line);
                    ni.add(sid);
                }
                runOnUiThread(() -> {
                    titles.clear();
                    titles.addAll(nt);
                    ids.clear();
                    ids.addAll(ni);
                    adapter.notifyDataSetChanged();
                    statusView.setText("共 " + nt.size() + " 个会话，点条目选中，聊天下一步做");
                    setBusy(false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusView.setText("加载失败：" + e.getMessage() + "，请确认服务器开着");
                    setBusy(false);
                });
            }
        }).start();
    }

    private void createSession() {
        setBusy(true);
        statusView.setText("正在新建会话…");
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/api/session");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
                OutputStream os = c.getOutputStream();
                os.write(out);
                os.flush();
                os.close();
                int code = c.getResponseCode();
                if (code != 200 && code != 201) throw new Exception("HTTP " + code);
                String resp = readAll(c.getInputStream());
                // New sessions default to opencode free so the app never depends on
                // the server default (currently openrouter free). Best effort: ignore switch failure.
                try {
                    JSONObject data = new JSONObject(resp).optJSONObject("data");
                    String nid = data != null ? data.optString("id", "") : "";
                    if (!nid.isEmpty()) switchSessionModel(nid);
                } catch (Exception ignored) {
                }
                runOnUiThread(() -> {
                    Toast.makeText(this, "新建成功", Toast.LENGTH_SHORT).show();
                    loadSessions();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusView.setText("新建失败：" + e.getMessage());
                    setBusy(false);
                });
            }
        }).start();
    }

    /**
     * New sessions start on an opencode free model (keyless). The model name is
     * NOT hard-coded any more: the old fledge-alpha-free vanished from the server
     * list (2026-10, /api/model has no fledge), so the write was silently failing
     * and new sessions fell back to the server default (openrouter). Now pick a
     * providerID==opencode + all-zero-cost model from /api/model: prefer the old
     * fledge-alpha-free if it ever returns, else the first free one in server
     * order. Best effort.
     */
    private static void switchSessionModel(String sid) {
        try {
            URL mu = new URL(BASE_URL + "/api/model");
            HttpURLConnection mc = (HttpURLConnection) mu.openConnection();
            auth(mc);
            mc.setConnectTimeout(5000);
            mc.setReadTimeout(10000);
            mc.setRequestMethod("GET");
            if (mc.getResponseCode() != 200) return;
            JSONArray models = new JSONObject(readAll(mc.getInputStream())).optJSONArray("data");
            if (models == null) return;
            ArrayList<JSONObject> free = new ArrayList<>();
            for (int i = 0; i < models.length(); i++) {
                JSONObject m = models.optJSONObject(i);
                if (m == null) continue;
                if (!"opencode".equals(m.optString("providerID", ""))) continue;
                if (m.optString("modelID", "").isEmpty()) continue;
                if (!isFreeModel(m)) continue;
                free.add(m);
            }
            if (free.isEmpty()) return;
            // 优先沿用旧默认 fledge-alpha-free（若它回来了）；否则取 /api/model 返回顺序里第一个免费模型。
            JSONObject pick = null;
            for (JSONObject m : free) {
                if ("fledge-alpha-free".equals(m.optString("modelID", ""))) {
                    pick = m;
                    break;
                }
            }
            if (pick == null) pick = free.get(0);
            String pickMid = pick.optString("modelID", "");
            String pickId = pick.optString("id", pickMid);
            if (pickId.isEmpty()) return;

            URL url = new URL(BASE_URL + "/api/session/" + sid + "/model");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            auth(c);
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            JSONObject body = new JSONObject();
            JSONObject mr = new JSONObject();
            mr.put("providerID", "opencode");
            mr.put("id", pickId);
            body.put("model", mr);
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = c.getOutputStream();
            os.write(out);
            os.flush();
            os.close();
            c.getResponseCode();
            try {
                readAll(c.getInputStream());
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
    }

    /** Free = cost array present and every tier input==0 and output==0 (V2 Model.Info.cost). */
    private static boolean isFreeModel(JSONObject m) {
        JSONArray cost = m.optJSONArray("cost");
        if (cost == null || cost.length() == 0) return false;
        for (int i = 0; i < cost.length(); i++) {
            JSONObject t = cost.optJSONObject(i);
            if (t == null) return false;
            if (t.optDouble("input", 1) != 0) return false;
            if (t.optDouble("output", 1) != 0) return false;
        }
        return true;
    }

    /** 崩溃日志查看（0.21.0）：读最新一份，弹窗可选中/复制。I/O 放后台线程。 */
    private void showCrashLog() {
        new Thread(() -> {
            final java.io.File f = CrashLogger.latest(this);
            final String txt = CrashLogger.read(f);
            runOnUiThread(() -> {
                if (txt == null || txt.isEmpty()) {
                    Toast.makeText(this, "没有崩溃日志", Toast.LENGTH_SHORT).show();
                    return;
                }
                TextView tv = new TextView(this);
                String shown = txt.length() > 20000
                        ? txt.substring(0, 20000) + "\n…（已截断，完整见 " + f.getAbsolutePath() + "）"
                        : txt;
                tv.setText(shown);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                tv.setTextIsSelectable(true);
                android.widget.ScrollView sv = new android.widget.ScrollView(this);
                sv.setPadding(dp(12), dp(4), dp(12), dp(4));
                sv.addView(tv);
                new android.app.AlertDialog.Builder(this)
                        .setTitle("崩溃日志（最新）")
                        .setView(sv)
                        .setPositiveButton("关闭", null)
                        .setNeutralButton("复制全文", (d, w) -> copyToClipboard(txt))
                        .show();
            });
        }).start();
    }

    private void copyToClipboard(String s) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("opencode", s));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "复制失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }
}
