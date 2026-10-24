package com.opencode.client;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * 0.17.0: AI 代操作网页・第1步——只装浏览器框。
 * 本版仅手动浏览（地址栏/前往/后退/前进/刷新），验证 WebView 在本机可用、
 * 用户可在此完成一次登录（cookie 留在 App 内）。
 * 读 DOM / 点 / 填的本机 HTTP 桥下一版再加，AI 暂不经此页操作。
 * 注意：禁止匿名内部类（D8 8.2.2-dev 内部崩溃），监听器一律 lambda/具名类。
 */
public class BrowserActivity extends Activity {

    /** 具名子类：链接留在本 WebView 内，不抛外部浏览器。 */
    static class StayClient extends WebViewClient {
    }

    private WebView web;
    private EditText urlBox;
    private BridgeServer bridge;
    private android.widget.TextView bridgeStatus;
    /** 进程级常驻：关页不断桥（0.18.2），随进程死或列表页手动停。 */
    static BridgeServer live;
    /** 0.22.0：本页创建时的调色板代号；onResume 发现变了就重建，避免半深半浅。 */
    private int themeGen;

    static void stopLive() {
        try {
            if (live != null) live.stop();
        } catch (Exception ignored) {
        }
        live = null;
    }

    static boolean liveOn() {
        try {
            return live != null && live.isBound();
        } catch (Exception e) {
            return false;
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 0.21.14：聊天里点链接会带 url extra 再进来；常驻页不会重建，这里手动导航
        try {
            String u = intent != null ? intent.getStringExtra("url") : null;
            if (u != null && !u.trim().isEmpty() && urlBox != null && web != null) go(u);
        } catch (Exception ignored) {
        }
    }

    private void go(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (u.isEmpty()) {
            Toast.makeText(this, "先输入网址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        urlBox.setText(u);
        web.loadUrl(u);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLogger.install(this); // 进程级崩溃日志（0.21.0）
        Palette.init(this);        // 0.22.0 深色模式
        Palette.apply(this);
        themeGen = Palette.gen();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Palette.pageBg());
        Edge.apply(root); // 0.22.3：状态栏/导航栏内边距

        // 顶栏：返回 + 地址栏 + 前往
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(12), dp(8), dp(8));
        top.setBackgroundColor(Palette.surface());
        root.addView(top);

        Button back = new Button(this);
        back.setText("‹ 返回");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        // 常驻页（0.19.1建，0.19.2修）：返回只把列表搬到前面，不销毁本页
        // （REORDER_TO_FRONT；之前误用 CLEAR_TOP，会把本页一起清掉等于白做）。
        // 本页 stop 不 destroy，WebView 状态/登录都在；真关页走底栏“✕ 关页”。
        back.setOnClickListener(v -> {
            android.content.Intent it = new android.content.Intent(this, MainActivity.class);
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(it);
        });
        top.addView(back);

        urlBox = new EditText(this);
        urlBox.setHint("输入网址…");
        urlBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        urlBox.setSingleLine(true);
        LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        uLp.leftMargin = dp(8);
        uLp.rightMargin = dp(8);
        top.addView(urlBox, uLp);

        Button goBtn = new Button(this);
        goBtn.setText("前往");
        goBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        goBtn.setMinHeight(dp(44));
        goBtn.setMinimumWidth(dp(56));
        goBtn.setOnClickListener(v -> go(urlBox.getText().toString()));
        top.addView(goBtn);

        // 桥状态行：监听中/失败原因/收到的请求都显示在这里，AI 排障看这行
        bridgeStatus = new android.widget.TextView(this);
        bridgeStatus.setText("桥：启动中…");
        bridgeStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        bridgeStatus.setTextColor(Palette.textMuted());
        bridgeStatus.setPadding(dp(12), dp(2), dp(12), dp(2));
        root.addView(bridgeStatus);

        // 中间：网页
        web = new WebView(this);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(web, wLp);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        applyWebDark();
        web.setWebViewClient(new StayClient());

        // 0.18.0 读桥：随本页生死，AI 用 curl 调 127.0.0.1:4098
        if (live == null) {
            live = new BridgeServer(this, web);
            live.setListener(s -> runOnUiThread(() -> {
                if (bridgeStatus != null) bridgeStatus.setText(s);
            }));
            live.start();
        } else {
            live.attach(this, web);
        }
        bridge = live;
        if (bridgeStatus != null) bridgeStatus.setText("桥：常驻中（已开 token 校验，关页不断）");
        Toast.makeText(this, "AI 读桥已开：127.0.0.1:4098（需 token，见 Download/opencode/bridge_token.txt）",
                Toast.LENGTH_LONG).show();

        // 底栏：后退 / 前进 / 刷新（大按钮，防误触）
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(8), dp(8), dp(8), dp(12));
        bottom.setBackgroundColor(Palette.surface());
        root.addView(bottom);

        Button backBtn = new Button(this);
        backBtn.setText("◀ 后退");
        backBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        backBtn.setMinHeight(dp(48));
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        b1.rightMargin = dp(4);
        bottom.addView(backBtn, b1);
        backBtn.setOnClickListener(v -> {
            if (web.canGoBack()) web.goBack();
            else Toast.makeText(this, "没有上一页了", Toast.LENGTH_SHORT).show();
        });

        Button fwdBtn = new Button(this);
        fwdBtn.setText("前进 ▶");
        fwdBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        fwdBtn.setMinHeight(dp(48));
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        b2.leftMargin = dp(4);
        b2.rightMargin = dp(4);
        bottom.addView(fwdBtn, b2);
        fwdBtn.setOnClickListener(v -> {
            if (web.canGoForward()) web.goForward();
            else Toast.makeText(this, "没有下一页了", Toast.LENGTH_SHORT).show();
        });

        Button reloadBtn = new Button(this);
        reloadBtn.setText("⟳ 刷新");
        reloadBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        reloadBtn.setMinHeight(dp(48));
        LinearLayout.LayoutParams b3 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        b3.leftMargin = dp(4);
        b3.rightMargin = dp(4);
        bottom.addView(reloadBtn, b3);
        reloadBtn.setOnClickListener(v -> web.reload());

        Button closeBtn = new Button(this);
        closeBtn.setText("✕ 关页");
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        closeBtn.setMinHeight(dp(48));
        LinearLayout.LayoutParams b4 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        b4.leftMargin = dp(4);
        bottom.addView(closeBtn, b4);
        closeBtn.setOnClickListener(v -> finish());

        setContentView(root);

        String start = getIntent().getStringExtra("url");
        go(start != null ? start : "https://github.com");
    }

    /** 0.22.0：网页跟随深色（尽力而为）。API 33+ 算法暗化，29–32 用 force dark。 */
    private void applyWebDark() {
        try {
            if (web == null) return;
            android.webkit.WebSettings s = web.getSettings();
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                s.setAlgorithmicDarkeningAllowed(Palette.isDark());
            } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                s.setForceDark(Palette.isDark()
                        ? android.webkit.WebSettings.FORCE_DARK_ON
                        : android.webkit.WebSettings.FORCE_DARK_OFF);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 列表页切了配色：本页是 singleTask 常驻，不重建就会「黑底配浅色控件」。
        // recreate 会重建 WebView，但 cookie/登录态在磁盘上，不掉登录。
        if (themeGen != Palette.gen()) {
            recreate();
            return;
        }
        applyWebDark();
    }

    @Override
    public void onBackPressed() {
        // 常驻页：先退网页历史，退完不销毁，回列表（页活着，桥一直绑着）
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        android.content.Intent it = new android.content.Intent(this, MainActivity.class);
        it.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(it);
    }

    @Override
    protected void onDestroy() {
        try {
            if (live != null) live.detachWeb(); // 关页不断桥，socket 留给进程
            bridge = null;
        } catch (Exception ignored) {
        }
        try {
            if (web != null) {
                if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web);
                web.destroy();
                web = null;
            }
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
