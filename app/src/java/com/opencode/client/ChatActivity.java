package com.opencode.client;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Phase 3: chat history + send (prompt) + SSE streaming + interrupt.
 * V2 API (2.0.x): /api/* paths, {data} envelopes, Basic auth, session-scoped
 * streaming events (session.text.delta etc.), completion via
 * session.execution.succeeded/failed. No external deps.
 */
public class ChatActivity extends Activity {

    private static final String BASE_URL = "http://127.0.0.1:4097";

    private String sessionID;
    private TextView statusView;
    private TextView titleView;
    private String baseTitle = "";
    private String costSuffix = "";
    private String todoSuffix = "";

    /** Title suffixes share one writer so cost + todo never clobber each other. */
    private void updateTitle() {
        final String t = baseTitle + costSuffix + todoSuffix;
        runOnUiThread(() -> {
            if (titleView != null) titleView.setText(t);
        });
    }
    private LinearLayout msgBox;
    private ScrollView scrollView;
    // 0.21.7 滚动不锁死：只有视图贴底时才自动跟随新内容。用户手动上滑看历史时暂停跟随，
    // 右下方浮出「⬇ 回到底部」按钮；滑回底部或点按钮即恢复自动跟随。
    private boolean stickToBottom = true;
    private TextView jumpBtn;
    // 历史翻页（0.13.10 存 previous 走错方向，0.13.11 改存 next）：服务端默认只回最新 50 条（倒序）；earlierCursor 存 cursor.next，
    // 为空即已到最早。earlierBtn 常驻消息列表顶部（msgBox index 0）。
    private String earlierCursor = null;
    private Button earlierBtn = null;
    /** 0.22.4：上一条 assistant 是否已把 error 渲染进气泡，用于 idle 不重复显示。 */
    private boolean lastRunErrorShown = false;
    private boolean loadingEarlier = false;
    private EditText input;
    private Button sendBtn;
    private Button stopBtn;
    private Button retryBtn;
    // 再试一次（功能3a）：最后一次发出的纯文字；带附件那次不记（附件发出即清，无法重发）
    private String lastSentText = "";
    private boolean lastHadAttach = false;
    private Button plusBtn;
    // attachment: absolute path under shared storage + mime, null = none.
    // Images are downscaled to JPEG; other files are copied raw (5MB cap).
    // Server reads it via file:// URL (same phone, loopback). First send may
    // trigger the permission approval banner; "always" persists for that dir.
    private String pendingAttachPath = null;
    private String pendingAttachMime = null;
    private static final int REQ_PICK_IMAGE = 1001;
    private static final int REQ_PICK_FILE = 1002;
    private static final long MAX_RAW_FILE = 5 * 1024 * 1024;
    private Spinner modelSpinner;
    private final java.util.ArrayList<String> modelNames = new java.util.ArrayList<>();
    private final java.util.ArrayList<String> modelProviderIDs = new java.util.ArrayList<>();
    private final java.util.ArrayList<String> modelIDs = new java.util.ArrayList<>();
    private ArrayAdapter<String> modelAdapter;
    // agent picker: per-message "agent" field of prompt_async (OpenAPI verified).
    // Only primary-mode chat agents (build/plan/…) are listed; subagent-mode
    // (explore/general) and system agents (compaction/summary/title) are skipped.
    private Spinner agentSpinner;
    private final java.util.ArrayList<String> agentNames = new java.util.ArrayList<>();
    private final java.util.ArrayList<String> agentIDs = new java.util.ArrayList<>();
    private ArrayAdapter<String> agentAdapter;
    // 思考深度（0.20.0）：V2 里就是 Model.Ref.variant；档位归属模型，随所选模型变化。
    // 空串 = 不发 variant（用模型/服务端默认）。
    private Spinner variantSpinner;
    private final java.util.ArrayList<String> variantNames = new java.util.ArrayList<>();
    private final java.util.ArrayList<String> variantIDs = new java.util.ArrayList<>();
    private ArrayAdapter<String> variantAdapter;
    // "providerID\u0000id" -> variant id 列表（来自 GET /api/model 的 variants[]，含收费模型，
    // 保证会话当前若是 openrouter 等非免费模型也能显示档位）。
    private final java.util.HashMap<String, java.util.ArrayList<String>> modelVariants = new java.util.HashMap<>();
    // 进页 GET /api/session/:id 读到的真实状态（null/空 = 未知）；下拉初始即选中它们，
    // 不再用"默认（跟随会话）"占位，做到"是什么就显示什么"。
    private volatile String sessionProviderID = null;
    private volatile String sessionModelID = null;
    private volatile String sessionVariant = null;
    private volatile String sessionAgent = null;
    private volatile boolean sessionMetaFetched = false;
    private volatile boolean modelsLoaded = false;
    private volatile boolean agentsLoaded = false;
    private boolean metaApplied = false;
    private boolean suppressModelEvents = false;
    private String lastVariantModelKey = null;
    // 本轮改动入口（功能1）：GET /api/session/:id/diff，默认 newest user turn
    private Button diffBtn;
    // permission approval banner
    private LinearLayout permBox;
    private TextView permText;
    private String pendingPermID = null;
    // question banner (AI asks user to pick answers, NOT permission)
    private LinearLayout qBox;
    private TextView qTitle;
    private ScrollView qScroll;
    private LinearLayout qList;
    private Button qToggle;
    private boolean qCollapsed = false;
    private String pendingQID = null;
    private final java.util.ArrayList<org.json.JSONObject> pendingQuestions = new java.util.ArrayList<>();
    private final java.util.ArrayList<java.util.LinkedHashSet<String>> qSelected = new java.util.ArrayList<>();
    private final java.util.ArrayList<EditText> qCustomInputs = new java.util.ArrayList<>();
    private final java.util.ArrayList<java.util.ArrayList<Button>> qOptionBtns = new java.util.ArrayList<>();
    // Trusted run status: single source of truth for title line + fixed banner.
    // ALL touched on UI thread only (callers already wrap in runOnUiThread).
    private LinearLayout statusBanner;
    private TextView statusText;
    private final java.util.LinkedHashMap<String, String> runningTools = new java.util.LinkedHashMap<>();
    // 0.21.9：工具事件里只有 input.started 带 name，按 id 记住，called/progress 复用
    private final java.util.HashMap<String, String> liveToolNames = new java.util.HashMap<>();
    private final java.util.HashMap<String, String> liveToolDetails = new java.util.HashMap<>();
    // 0.21.10：`date` 这类工具全程仅 ~160ms，事件一到就摘状态的话横幅根本画不出来。
    // 完成后把结果再挂 1.5 秒（✅ 已完成 · 目标），保证肉眼能看到。
    private String lingerToolText = null;
    private Runnable lingerHideTask = null;
    private static final long TOOL_LINGER_MS = 1500;
    // 服务端 GET /message 默认页大小（App 不传 limit）
    private static final int MSG_PAGE_SIZE = 50;
    // 0.21.12：翻页探针序号，重拉历史后让在途的旧探针结果失效
    private int earlierProbeSeq = 0;

    /** 0.21.11：服务端第一页永远回非空 cursor.next（实测：短会话跟进去是 0 条 + next=null），
     *  不能拿「next 非空」当「还有更早」，否则每个会话都顶着一个没用的翻页按钮。 */
    private static String normCursor(String c) {
        if (c == null) return "";
        String v = c.trim();
        if (v.isEmpty() || "null".equalsIgnoreCase(v) || "undefined".equalsIgnoreCase(v)) return "";
        return v;
    }
    private boolean writingShown = false;
    private boolean thinkingShown = false; // reasoning 阶段：⏳ 与 ✍️ 之间显示 💭
    private boolean doneShowing = false;
    private boolean suppressDone = false; // abort / error / send-fail: idle must NOT show ✅
    private boolean stoppedManually = false; // user pressed 停止: 本次中断不算"运行未成功"
    private boolean stoppedAwait = false; // 已按停止、等服务端确认中断的窗口期
    private String retryText = null; // server retry state (e.g. shaky VPN), cleared on busy/idle
    private volatile boolean sseOk = true; // event stream health; false = banner may be stale
    // 0.21.15：最近一次收到任意一行（含 ": heartbeat"）的时间。服务端每 15 秒心跳一条，
    // 拿它判断「连接看起来活着、实际什么都没收到」，回前台/重连时据此决定要不要对账。
    private volatile long lastSseLineAt = 0;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable hideDoneTask = null;
    // Missed-idle guard: SSE is fire-and-forget, a single lost
    // session.execution.succeeded leaves busy=true forever. lastEventAt tracks
    // the last event for this session; watchdog polls GET /api/session/active
    // (absent sessionID = idle) when busy goes quiet, reconnect triggers
    // the same poll. (V1 used GET /session/status + session.status/idle.)
    private volatile long lastEventAt = 0;
    private volatile boolean sseWasDown = false;
    private volatile boolean toolPollOn = false;
    private Runnable watchdogTask = null;

    private volatile boolean sseRunning = false;
    private volatile boolean busy = false;
    // partID -> text view for delta appends
    private final Map<String, TextView> partViews = new HashMap<>();
    private final Map<String, StringBuilder> partBuf = new HashMap<>();
    // 推理过程实时显示（0.21.0）：run 中 reasoning.* 事件以前被忽略，现边跑边显示，
    // 折叠 6 行、点标题展开；完成时 header 文案改「思考过程」。loadHistory 时清空。
    private final Map<String, ReasonBlock> reasonBlocks = new HashMap<>();
    // messageID -> bubble container (to attach late-arriving parts)
    private final Map<String, LinearLayout> msgBubbles = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLogger.install(this); // 进程级崩溃日志（0.21.0）：闪退写 /sdcard/Download/opencode/crash-*.txt
        Palette.init(this);        // 0.22.0 深色模式：先定主题再建视图
        Palette.apply(this);
        // keyboard fix: shrink layout instead of covering input
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sessionID = getIntent().getStringExtra("sessionID");
        String title = getIntent().getStringExtra("title");
        if (sessionID == null) {
            Toast.makeText(this, "缺少会话ID", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Palette.pageBg());

        // top bar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(12), dp(8), dp(8));
        top.setBackgroundColor(Palette.surface());

        Button back = new Button(this);
        back.setText("‹ 返回");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        back.setOnClickListener(v -> finish());
        top.addView(back);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tbLp.leftMargin = dp(8);
        top.addView(titleBox, tbLp);

        TextView t = new TextView(this);
        baseTitle = title != null ? title : sessionID;
        t.setText(baseTitle);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTextColor(Palette.textPrimary());
        t.setSingleLine(true);
        titleBox.addView(t);
        titleView = t;

        statusView = new TextView(this);
        statusView.setText("空闲");
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusView.setTextColor(Palette.textMuted());
        titleBox.addView(statusView);
        // stop + attach live top-right (bottom bar keeps [input][send] only)
        plusBtn = new Button(this);
        plusBtn.setText("＋");
        plusBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        plusBtn.setMinHeight(dp(44));
        plusBtn.setMinimumWidth(dp(56));
        plusBtn.setPadding(dp(6), 0, dp(6), 0);
        top.addView(plusBtn);
        plusBtn.setOnClickListener(v -> {
            if (pendingAttachPath != null) {
                clearAttachment();
                Toast.makeText(this, "已移除附件", Toast.LENGTH_SHORT).show();
            } else {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("添加附件")
                        .setItems(new String[]{"发送图片", "发送文件", "取消"},
                                (d, which) -> {
                                    if (which == 0) pickFile("image/*", REQ_PICK_IMAGE);
                                    else if (which == 1) pickFile("*/*", REQ_PICK_FILE);
                                })
                        .show();
            }
        });
        stopBtn = new Button(this);
        stopBtn.setText("停止");
        stopBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        stopBtn.setMinHeight(dp(44));
        stopBtn.setMinimumWidth(dp(56));
        stopBtn.setPadding(dp(6), 0, dp(6), 0);
        stopBtn.setVisibility(Button.GONE);
        top.addView(stopBtn);
        stopBtn.setOnClickListener(v -> abort());
        // 重试只在上次失败后出现（平时隐藏，顶栏不挤）
        retryBtn = new Button(this);
        retryBtn.setText("↻ 重试");
        retryBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        retryBtn.setMinHeight(dp(44));
        retryBtn.setMinimumWidth(dp(56));
        retryBtn.setPadding(dp(6), 0, dp(6), 0);
        retryBtn.setVisibility(Button.GONE);
        top.addView(retryBtn);
        retryBtn.setOnClickListener(v -> retryLast());
        root.addView(top);

        // model picker row (compact)
        LinearLayout modelRow = new LinearLayout(this);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        modelRow.setGravity(Gravity.CENTER_VERTICAL);
        modelRow.setPadding(dp(8), dp(4), dp(8), dp(4));
        modelRow.setBackgroundColor(Palette.rowBg());
        root.addView(modelRow);

        TextView modelLabel = new TextView(this);
        modelLabel.setText("模型:");
        modelLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        modelLabel.setTextColor(Palette.textSecondary());
        modelRow.addView(modelLabel);

        modelSpinner = new Spinner(this);
        modelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modelNames);
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(modelAdapter);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        mLp.leftMargin = dp(8);
        modelRow.addView(modelSpinner, mLp);
        // 0.22.8：开放 openrouter 后模型上百个，Spinner 里翻找太痛苦，右侧加搜索入口。
        Button modelSearchBtn = new Button(this);
        modelSearchBtn.setText("🔍");
        modelSearchBtn.setContentDescription("搜索模型");
        modelSearchBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        modelSearchBtn.setMinHeight(dp(44));
        modelSearchBtn.setMinimumWidth(dp(44));
        modelSearchBtn.setPadding(dp(6), 0, dp(6), 0);
        LinearLayout.LayoutParams mslp = new LinearLayout.LayoutParams(dp(48), dp(44));
        mslp.leftMargin = dp(4);
        modelRow.addView(modelSearchBtn, mslp);
        modelSearchBtn.setOnClickListener(v -> showModelSearchDialog());
        // 换模型即重建思考档位（0.20.0）：档位跟模型走，换完默认回落"默认"。
        // 监听器必须是 static 具名内部类（D8 8.2.2-dev 遇匿名/非静态内部类会 NPE 崩溃，见 DEVELOPER.md）。
        modelSpinner.setOnItemSelectedListener(new ModelSelectListener(this));

        // Agent + 思考深度 同行（0.20.0）：左 Agent 右 思考，省一整行空间。
        LinearLayout agentRow = new LinearLayout(this);
        agentRow.setOrientation(LinearLayout.HORIZONTAL);
        agentRow.setGravity(Gravity.CENTER_VERTICAL);
        agentRow.setPadding(dp(8), dp(4), dp(8), dp(4));
        agentRow.setBackgroundColor(Palette.rowBg());
        root.addView(agentRow);

        TextView agentLabel = new TextView(this);
        agentLabel.setText("Agent:");
        agentLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        agentLabel.setTextColor(Palette.textSecondary());
        agentRow.addView(agentLabel);

        agentSpinner = new Spinner(this);
        agentAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, agentNames);
        agentAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        agentSpinner.setAdapter(agentAdapter);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        aLp.leftMargin = dp(4);
        aLp.rightMargin = dp(8);
        agentRow.addView(agentSpinner, aLp);

        TextView variantLabel = new TextView(this);
        variantLabel.setText("思考:");
        variantLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        variantLabel.setTextColor(Palette.textSecondary());
        agentRow.addView(variantLabel);

        variantSpinner = new Spinner(this);
        variantNames.add("默认");
        variantIDs.add("");
        variantAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, variantNames);
        variantAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        variantSpinner.setAdapter(variantAdapter);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        vLp.leftMargin = dp(4);
        agentRow.addView(variantSpinner, vLp);

        // 本轮改动行（功能1）：一行一个大按钮，点之拉取 session.diff 并弹窗看 patch
        LinearLayout diffRow = new LinearLayout(this);
        diffRow.setOrientation(LinearLayout.HORIZONTAL);
        diffRow.setGravity(Gravity.CENTER_VERTICAL);
        diffRow.setPadding(dp(8), dp(4), dp(8), dp(4));
        diffRow.setBackgroundColor(Palette.rowBg());
        root.addView(diffRow);

        diffBtn = new Button(this);
        diffBtn.setText("📝 改动（本轮）");
        diffBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        diffBtn.setMinHeight(dp(44));
        diffBtn.setMinimumWidth(dp(56));
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        diffRow.addView(diffBtn, dLp);
        diffBtn.setOnClickListener(v -> fetchSessionDiff(true));

        // permission banner (hidden unless AI asks for approval)
        permBox = new LinearLayout(this);
        permBox.setOrientation(LinearLayout.VERTICAL);
        permBox.setPadding(dp(8), dp(8), dp(8), dp(8));
        permBox.setBackgroundColor(Palette.permBg());
        permBox.setVisibility(LinearLayout.GONE);
        root.addView(permBox);

        permText = new TextView(this);
        permText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        permText.setTextColor(Palette.permText());
        permBox.addView(permText);

        LinearLayout permRow = new LinearLayout(this);
        permRow.setOrientation(LinearLayout.HORIZONTAL);
        permRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        prLp.topMargin = dp(6);
        permBox.addView(permRow, prLp);

        Button allowOnce = new Button(this);
        allowOnce.setText("允许一次");
        allowOnce.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        allowOnce.setMinHeight(dp(48));
        LinearLayout.LayoutParams poLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        poLp.rightMargin = dp(4);
        permRow.addView(allowOnce, poLp);
        allowOnce.setOnClickListener(v -> replyPerm("once"));

        Button allowAlways = new Button(this);
        allowAlways.setText("总是允许");
        allowAlways.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        allowAlways.setMinHeight(dp(48));
        LinearLayout.LayoutParams paLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        paLp.leftMargin = dp(4);
        paLp.rightMargin = dp(4);
        permRow.addView(allowAlways, paLp);
        allowAlways.setOnClickListener(v -> replyPerm("always"));

        Button denyBtn = new Button(this);
        denyBtn.setText("拒绝");
        denyBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        denyBtn.setMinHeight(dp(48));
        denyBtn.setTextColor(Palette.error());
        LinearLayout.LayoutParams pdLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        pdLp.leftMargin = dp(4);
        permRow.addView(denyBtn, pdLp);
        denyBtn.setOnClickListener(v -> replyPerm("reject"));

        // question banner (hidden unless AI asks questions).
        // Layout: header row (title + collapse toggle, always visible) +
        // ScrollView (capped 240dp, options scroll inside) +
        // button row (submit/skip, always visible). This keeps the banner
        // from covering the whole screen when there are 3+ questions.
        qBox = new LinearLayout(this);
        qBox.setOrientation(LinearLayout.VERTICAL);
        qBox.setPadding(dp(8), dp(8), dp(8), dp(8));
        qBox.setBackgroundColor(Palette.questionBg());
        qBox.setVisibility(LinearLayout.GONE);
        root.addView(qBox);

        LinearLayout qHeader = new LinearLayout(this);
        qHeader.setOrientation(LinearLayout.HORIZONTAL);
        qHeader.setGravity(Gravity.CENTER_VERTICAL);
        qBox.addView(qHeader);

        qTitle = new TextView(this);
        qTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        qTitle.setTextColor(Palette.questionText());
        LinearLayout.LayoutParams qTitleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        qTitleLp.rightMargin = dp(6);
        qHeader.addView(qTitle, qTitleLp);

        qToggle = new Button(this);
        qToggle.setText("收起");
        qToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        qToggle.setMinHeight(dp(44));
        qToggle.setMinimumWidth(dp(56));
        qHeader.addView(qToggle);
        qToggle.setOnClickListener(v -> setQCollapsed(!qCollapsed));

        qScroll = new ScrollView(this);
        LinearLayout.LayoutParams qScrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(240));
        qScrollLp.topMargin = dp(4);
        qBox.addView(qScroll, qScrollLp);

        qList = new LinearLayout(this);
        qList.setOrientation(LinearLayout.VERTICAL);
        qScroll.addView(qList);

        LinearLayout qRow = new LinearLayout(this);
        qRow.setOrientation(LinearLayout.HORIZONTAL);
        qRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams qRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        qRowLp.topMargin = dp(8);
        qBox.addView(qRow, qRowLp);

        Button qSubmit = new Button(this);
        qSubmit.setText("提交答案");
        qSubmit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        qSubmit.setMinHeight(dp(48));
        LinearLayout.LayoutParams qsLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        qsLp.rightMargin = dp(4);
        qRow.addView(qSubmit, qsLp);
        qSubmit.setOnClickListener(v -> replyQuestion());

        Button qSkip = new Button(this);
        qSkip.setText("跳过");
        qSkip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        qSkip.setMinHeight(dp(48));
        qSkip.setTextColor(Palette.error());
        LinearLayout.LayoutParams qkLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        qkLp.leftMargin = dp(4);
        qRow.addView(qSkip, qkLp);
        qSkip.setOnClickListener(v -> rejectQuestion());

        // messages（0.21.7：ScrollView 外套 FrameLayout，右下角悬浮「回到底部」按钮）
        android.widget.FrameLayout msgFrame = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(msgFrame, sLp);

        scrollView = new ScrollView(this);
        msgFrame.addView(scrollView, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        // 贴底判定：滚动结束后看是否还能往下滚（留 24dp 容差，避免像素级抖动）
        scrollView.setOnScrollChangeListener((v, sx, sy, osx, osy) -> updateStick());

        msgBox = new LinearLayout(this);
        msgBox.setOrientation(LinearLayout.VERTICAL);
        msgBox.setPadding(dp(12), dp(12), dp(12), dp(12));
        scrollView.addView(msgBox);

        jumpBtn = new TextView(this);
        jumpBtn.setText("⬇ 回到底部");
        jumpBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        jumpBtn.setTextColor(Palette.onAccent());
        android.graphics.drawable.GradientDrawable jbBg =
                new android.graphics.drawable.GradientDrawable();
        jbBg.setColor(Palette.scrim());
        jbBg.setCornerRadius(dp(18));
        jumpBtn.setBackground(jbBg);
        jumpBtn.setPadding(dp(14), dp(8), dp(14), dp(8));
        jumpBtn.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams jLp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        jLp.gravity = Gravity.BOTTOM | Gravity.END;
        jLp.setMargins(0, 0, dp(12), dp(12));
        msgFrame.addView(jumpBtn, jLp);
        jumpBtn.setOnClickListener(v -> scrollDownForce());

        // 历史翻页按钮（0.13.10）：只在还有更早页时显示，由 refreshEarlierBtn() 管理显隐
        earlierBtn = new Button(this);
        earlierBtn.setText("⬆ 加载更早记录");
        earlierBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        earlierBtn.setMinHeight(dp(44));
        earlierBtn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        earlierBtn.setOnClickListener(v -> loadEarlier());

        // run-status banner: fixed above input, always visible while AI works
        statusBanner = new LinearLayout(this);
        statusBanner.setOrientation(LinearLayout.HORIZONTAL);
        statusBanner.setGravity(Gravity.CENTER_VERTICAL);
        statusBanner.setPadding(dp(12), dp(6), dp(12), dp(6));
        statusBanner.setBackgroundColor(Palette.okBg());
        statusBanner.setVisibility(LinearLayout.GONE);
        root.addView(statusBanner);

        statusText = new TextView(this);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        statusText.setTextColor(Palette.okText());
        statusBanner.addView(statusText);

        // bottom input bar (fixed)
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(8), dp(8), dp(8), dp(12));
        bottom.setBackgroundColor(Palette.surface());
        root.addView(bottom);

        input = new EditText(this);
        input.setHint("输入消息…");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setMinLines(3);
        input.setMaxLines(6);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        iLp.rightMargin = dp(6);
        bottom.addView(input, iLp);
        // keyboard fix: when input gains focus, scroll to latest message
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) scrollView.postDelayed(() -> scrollDown(), 300);
        });

        // (stop button lives top-right; bottom bar keeps [image][input][send])

        sendBtn = new Button(this);
        sendBtn.setText("发送");
        sendBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sendBtn.setMinHeight(dp(44));
        sendBtn.setMinimumWidth(dp(56));
        sendBtn.setPadding(dp(6), 0, dp(6), 0);
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        sendLp.leftMargin = dp(6);
        bottom.addView(sendBtn, sendLp);
        sendBtn.setOnClickListener(v -> send());

        // 0.22.3：状态栏/导航栏/键盘统一走 insets（Android 15 强制 edge-to-edge，
        // 之前顶栏被状态栏压住，键盘态也要让输入栏浮在键盘上方）。
        Edge.apply(root, () -> scrollDown());

        setContentView(root);
        restoreDraft(); // 0.21.9：上次切走前打了一半的内容回填

        loadHistory();
        refreshSessionCost();
        refreshTodos();
        loadModels();
        loadAgents();
        loadSessionMeta();
        startSse();
        checkPendingPerm();
        checkPendingQuestions();
        fetchSessionDiff(false);
    }

    @Override
    protected void onDestroy() {
        sseRunning = false;
        dropPendingFlush();
        try {
            if (watchdogTask != null) uiHandler.removeCallbacks(watchdogTask);
        } catch (Exception ignored) {
        }
        try {
            if (hideDoneTask != null) uiHandler.removeCallbacks(hideDoneTask);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    // 跑完叫你（功能2）：在页内就自己看，离开本页（onPause）后完成就发系统通知
    private boolean uiVisible = false;

    @Override
    protected void onResume() {
        super.onResume();
        uiVisible = true;
        // 点进来就是看结果：清掉绿勾；等结果标记按服务端 active 表对账
        //（还在跑则保留，跑完则清，免得切出去后重复提醒或漏提醒）
        MainActivity.clearJustDone(this, sessionID);
        reconcileAwaiting();
        // 0.21.15：切后台/锁屏期间 socket 可能「活着但没数据」，回来后正文会缺一截。
        // 心跳 15 秒一条，超过 25 秒一行都没有 = 期间不可信，按服务端重建一次
        //（首次进页 lastSseLineAt 还是 0，跳过，免得和 loadHistory() 重复拉）。
        if (lastSseLineAt > 0 && System.currentTimeMillis() - lastSseLineAt > 25000) {
            loadHistory();
            pollSessionStatus(false);
        }
    }

    /** 进页对一次账：在跑则记下来，不在跑则清掉（已看完，无需提醒）。 */
    private void reconcileAwaiting() {
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL(BASE_URL + "/api/session/active");
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                MainActivity.auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setRequestMethod("GET");
                if (c.getResponseCode() != 200) return;
                org.json.JSONObject data = new org.json.JSONObject(readAll(c.getInputStream())).optJSONObject("data");
                if (data != null && data.has(sessionID)) {
                    MainActivity.markAwaiting(this, sessionID, baseTitle);
                } else {
                    MainActivity.clearAwaiting(this, sessionID);
                }
            } catch (Exception ignored) {
            }
        }).start();
    }

    @Override
    protected void onPause() {
        uiVisible = false;
        saveDraft(); // 0.21.9：切走/锁屏不丢已打好的字
        super.onPause();
    }

    private static final String DRAFT_PREFS = "opencode_drafts";

    /** 草稿按会话存取：写一半切出去查东西，回来还在。 */
    private void saveDraft() {
        try {
            if (input == null) return;
            getSharedPreferences(DRAFT_PREFS, MODE_PRIVATE).edit()
                    .putString("d_" + sessionID, input.getText().toString())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    private void restoreDraft() {
        try {
            if (input == null) return;
            String d = getSharedPreferences(DRAFT_PREFS, MODE_PRIVATE)
                    .getString("d_" + sessionID, "");
            if (d != null && !d.isEmpty()) {
                input.setText(d);
                input.setSelection(d.length());
            }
        } catch (Exception ignored) {
        }
    }

    private void pickFile(String mimeType, int requestCode) {
        try {
            android.content.Intent it = new android.content.Intent(
                    android.content.Intent.ACTION_GET_CONTENT);
            it.setType(mimeType);
            it.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            startActivityForResult(android.content.Intent.createChooser(it, "选择附件"),
                    requestCode);
        } catch (Exception e) {
            Toast.makeText(this, "打不开选择器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != REQ_PICK_IMAGE && requestCode != REQ_PICK_FILE)
                || resultCode != RESULT_OK || data == null) return;
        android.net.Uri uri = data.getData();
        if (uri == null) return;
        final boolean isImage = requestCode == REQ_PICK_IMAGE;
        new Thread(() -> {
            try {
                final String path;
                final String mime;
                if (isImage) {
                    path = copyAndCompressImage(uri);
                    mime = "image/jpeg";
                } else {
                    mime = guessMime(uri);
                    path = copyRawFile(uri);
                }
                runOnUiThread(() -> {
                    pendingAttachPath = path;
                    pendingAttachMime = mime;
                    plusBtn.setText("清");
                    Toast.makeText(this, "已选附件，点发送一起发出", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "附件处理失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String guessMime(android.net.Uri uri) {
        String ext = extOfUri(uri);
        // Server rejects text/markdown file parts:
        // "'file part media type text/markdown' functionality not supported"
        // (UnknownError, assistant parts=[]). .md MUST go as text/plain
        // (verified: text/plain+file:// reads fine, text/markdown fails).
        if ("md".equals(ext) || "markdown".equals(ext)
                || "log".equals(ext) || "txt".equals(ext)) return "text/plain";
        if ("json".equals(ext)) return "application/json";
        try {
            String t = getContentResolver().getType(uri);
            if (t != null && !t.isEmpty()) {
                if (t.startsWith("text/markdown") || t.startsWith("text/x-markdown")) {
                    return "text/plain";
                }
                return t;
            }
        } catch (Exception ignored) {
        }
        if ("json".equals(ext)) return "application/json";
        return "application/octet-stream";
    }

    /** Extension from DISPLAY_NAME / path / last segment, lowercase, no dot. */
    private String extOfUri(android.net.Uri uri) {
        String[] candidates = new String[0];
        try {
            try (android.database.Cursor c = getContentResolver().query(
                    uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String dn = c.getString(0);
                    if (dn != null) candidates = new String[]{dn};
                }
            }
        } catch (Exception ignored) {
        }
        if (candidates.length == 0) {
            String p = null;
            String lp = null;
            try {
                p = uri.getPath();
            } catch (Exception ignored) {
            }
            try {
                lp = uri.getLastPathSegment();
            } catch (Exception ignored) {
            }
            candidates = new String[]{lp, p};
        }
        for (String s : candidates) {
            if (s == null) continue;
            int cut = s.lastIndexOf('.');
            if (cut >= 0 && cut < s.length() - 1) {
                String e = s.substring(cut + 1).toLowerCase();
                int q = e.indexOf('?');
                if (q >= 0) e = e.substring(0, q);
                // strip non-alnum (e.g. "md)"), keep short
                e = e.replaceAll("[^a-z0-9]", "");
                if (!e.isEmpty() && e.length() <= 10) return e;
            }
        }
        return "";
    }

    /** Raw copy for non-image files (logs etc.), 5MB cap, returns abs path. */
    private String copyRawFile(android.net.Uri uri) throws Exception {
        java.io.File dir = new java.io.File(
                android.os.Environment.getExternalStorageDirectory(), "Download/opencode");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("建目录失败");
        // Keep the original filename + extension (old code dropped it as
        // "file-<ts>" with no suffix; server stores filename=null anyway, but
        // keeping ".md" makes debugging mime issues possible).
        String name = null;
        try {
            try (android.database.Cursor c = getContentResolver().query(
                    uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) name = c.getString(0);
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) {
            try {
                name = uri.getLastPathSegment();
            } catch (Exception ignored) {
            }
        }
        if (name == null || name.isEmpty() || !name.contains(".")) {
            String ext = extOfUri(uri);
            name = "file-" + System.currentTimeMillis() + (ext.isEmpty() ? "" : "." + ext);
        }
        if (name.length() > 80) {
            int dot = name.lastIndexOf('.');
            String ext = dot >= 0 ? name.substring(dot) : "";
            name = name.substring(0, 80 - ext.length()) + ext;
        }
        name = name.replaceAll("[/\\\\]", "_");
        java.io.File out = new java.io.File(dir, System.currentTimeMillis() + "-" + name);
        long total = 0;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri);
             java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            if (in == null) throw new Exception("打不开文件");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_RAW_FILE) throw new Exception("文件超过5MB");
                fos.write(buf, 0, n);
            }
        } catch (Exception e) {
            out.delete();
            throw e;
        }
        return out.getAbsolutePath();
    }

    /** Downscale to max 1280px + JPEG 80 into /sdcard/Download/opencode/, returns abs path. */
    private String copyAndCompressImage(android.net.Uri uri) throws Exception {
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            android.graphics.BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new Exception("读不到图片");
        int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (maxSide / sample > 1280) sample *= 2;
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inSampleSize = sample;
        android.graphics.Bitmap bmp;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            bmp = android.graphics.BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) throw new Exception("解码失败");
        java.io.File dir = new java.io.File(
                android.os.Environment.getExternalStorageDirectory(), "Download/opencode");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("建目录失败");
        java.io.File out = new java.io.File(dir, "img-" + System.currentTimeMillis() + ".jpg");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            if (!bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, fos)) {
                throw new Exception("压缩失败");
            }
        }
        bmp.recycle();
        return out.getAbsolutePath();
    }

    private void clearAttachment() {
        pendingAttachPath = null;
        pendingAttachMime = null;
        runOnUiThread(() -> {
            if (plusBtn != null) plusBtn.setText("＋");
        });
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    /** 视图是否贴底（留 24dp 容差）。 */
    private boolean isAtBottom() {
        if (scrollView == null || scrollView.getChildCount() == 0) return true;
        View child = scrollView.getChildAt(0);
        int remaining = child.getHeight() - (scrollView.getScrollY() + scrollView.getHeight());
        return remaining <= dp(24);
    }

    /** 用户滚动后重算是否继续自动跟随，并显隐「回到底部」按钮。 */
    private void updateStick() {
        boolean atBottom = isAtBottom();
        stickToBottom = atBottom;
        if (jumpBtn != null) {
            jumpBtn.setVisibility(atBottom ? View.GONE : View.VISIBLE);
        }
    }

    /** 仅当用户贴底时才自动跟随；上滑看历史时绝不抢滚动位置（0.21.7）。 */
    private void scrollDown() {
        if (scrollView == null || !stickToBottom) return;
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    /** 强制回到底部并恢复自动跟随（自己发消息、首次进页、点「回到底部」）。 */
    private void scrollDownForce() {
        if (scrollView == null) return;
        stickToBottom = true;
        if (jumpBtn != null) jumpBtn.setVisibility(View.GONE);
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void setBusy(boolean b) {
        busy = b;
        runOnUiThread(() -> {
            sendBtn.setEnabled(true); // V2 支持 busy 时排队 steer，不再禁用发送
            if (stopBtn != null) stopBtn.setVisibility(b ? Button.VISIBLE : Button.GONE);
            if (b) { startWatchdog(); startBusyPoll(); }
            else { stopWatchdog(); toolPollOn = false; }
            refreshStatus(); // never write statusView directly: single source of truth
        });
    }

    /** 清掉残留的工具行（新工具开始 / 新一发 / 完成时）。 */
    private void clearToolLinger() {
        lingerToolText = null;
        if (lingerHideTask != null) {
            uiHandler.removeCallbacks(lingerHideTask);
            lingerHideTask = null;
        }
    }

    /** 工具完成：结果在横幅多留 1.5 秒再收，否则瞬时工具肉眼看不见。 */
    private void showToolDone(String doneText) {
        clearToolLinger();
        lingerToolText = doneText;
        lingerHideTask = () -> {
            lingerToolText = null;
            lingerHideTask = null;
            refreshStatus();
        };
        uiHandler.postDelayed(lingerHideTask, TOOL_LINGER_MS);
        refreshStatus();
    }

    /** Title line + fixed banner always agree. UI thread only. */
    private void refreshStatus() {
        if (permBox.getVisibility() == LinearLayout.VISIBLE) {
            statusBanner.setVisibility(LinearLayout.GONE);
            statusView.setText("等你确认权限");
            return;
        }
        if (qBox.getVisibility() == LinearLayout.VISIBLE) {
            statusBanner.setVisibility(LinearLayout.GONE);
            statusView.setText("等你回答问题");
            return;
        }
        if (doneShowing) return; // ✅ window owns both views
        if (stoppedAwait) {
            String s = "⏹️ 已发送停止，等待服务器确认中断…";
            statusBanner.setVisibility(LinearLayout.VISIBLE);
            statusText.setText(s);
            statusView.setText(s);
            return;
        }
        String banner;
        String tool = currentToolText();
        if (tool == null) tool = lingerToolText;
        if (retryText != null) {
            banner = retryText;
        } else if (tool != null) {
            banner = tool;
        } else if (writingShown) {
            banner = "✍️ 正在写回复…";
        } else if (thinkingShown) {
            banner = "💭 正在思考…";
        } else if (busy) {
            banner = "⏳ 已发送，等待 AI 响应…";
        } else {
            statusBanner.setVisibility(LinearLayout.GONE);
            statusView.setText("空闲");
            return;
        }
        if (!sseOk) {
            banner = banner + "\n⚠️ 事件连接中断，重连中，状态可能不是最新";
        }
        statusBanner.setVisibility(LinearLayout.VISIBLE);
        statusText.setText(banner);
        statusView.setText(banner);
    }

    private String currentToolText() {
        String last = null;
        for (String v : runningTools.values()) last = v;
        return last;
    }

    /** V2 tool part -> 人话细节：从 state.input 提取命令/路径/URL/查询（state.title 在 V2 恒空）。 */
    private static String toolDetail(JSONObject p) {
        JSONObject st = p != null ? p.optJSONObject("state") : null;
        JSONObject input = st != null ? st.optJSONObject("input") : null;
        if (input == null) return "";
        String name = p.optString("name", "");
        if (name.isEmpty()) name = p.optString("tool", "");
        return detailFromInput(name, input);
    }

    /** 0.21.9：tool 事件只有 input 对象，detail 逻辑与历史 part 共用。 */
    private static String detailFromInput(String name, JSONObject input) {
        if (input == null) return "";
        String v = "";
        switch (name) {
            case "shell":
            case "bash":
                v = input.optString("command", "");
                break;
            case "edit":
            case "write":
            case "apply_patch":
            case "read":
            case "lsp":
                v = input.optString("path", "");
                if (v.isEmpty()) v = input.optString("file", "");
                break;
            case "grep":
            case "glob":
                v = input.optString("pattern", "");
                if (v.isEmpty()) v = input.optString("path", "");
                break;
            case "webfetch":
                v = input.optString("url", "");
                break;
            case "websearch":
                v = input.optString("query", "");
                break;
            default:
                for (String k : new String[]{"path", "file", "url", "query", "command"}) {
                    v = input.optString(k, "");
                    if (!v.isEmpty()) break;
                }
                break;
        }
        if (v.isEmpty()) return "";
        v = v.replace('\n', ' ').trim();
        return v.length() > 40 ? v.substring(0, 40) + "…" : v;
    }

    /** Whitelist mapping from real 1.18.29 tool IDs; unknown tools fall back generic. */
    private static String friendlyToolText(String tool, String title, String detail) {
        String suffix = "";
        String t = (title != null && !title.isEmpty()) ? title : (detail != null ? detail : "");
        if (!t.isEmpty()) {
            if (t.length() > 40) t = t.substring(0, 40) + "…";
            suffix = " · " + t;
        }
        switch (tool) {
            case "read":
            case "glob":
            case "grep":
            case "lsp":
                return "🔧 正在读取项目文件" + suffix;
            case "edit":
            case "write":
            case "apply_patch":
                return "✏️ 正在修改代码" + suffix;
            case "bash":
            case "shell":
                return "⚙️ 正在执行命令" + suffix;
            case "webfetch":
            case "websearch":
                return "🌐 正在联网查找资料" + suffix;
            case "task":
            case "skill":
                return "📦 正在处理子任务" + suffix;
            case "question":
                return "❓ 等你回答问题" + suffix;
            default:
                return "🔧 AI 正在处理…" + suffix;
        }
    }

    /** 0.21.9：工具状态改由 `session.tool.*` 事件驱动（见 handleEvent），这里只保留
     *  「审批/提问」轻量轮询兜底——SSE 是易失的，丢一个 permission 事件就永远不弹。
     *  以前同一循环每 3 秒还拉一次 `/api/session/:id/message`（整包 50 条含工具全量输出，
     *  长会话能到几 MB）只为读工具状态，纯浪费流量和电；已删除。 */
    private void startBusyPoll() {
        if (toolPollOn) return;
        toolPollOn = true;
        new Thread(() -> {
            while (toolPollOn && busy) {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) { break; }
                if (!toolPollOn || !busy) break;
                // 只查这两个小接口（都是空数组/几条），不再碰 message 大包
                checkPendingPerm();
                checkPendingQuestions(true);
            }
            toolPollOn = false;
        }).start();
    }

    private void onTextDelta() {
        if (doneShowing) return;
        if (currentToolText() != null) return; // tool wins: never show 回复中 while a tool runs
        writingShown = true;
        thinkingShown = false;
        refreshStatus();
    }

    private void showDone() {
        runningTools.clear();
        liveToolNames.clear();
        liveToolDetails.clear();
        clearToolLinger();
        writingShown = false;
        doneShowing = true;
        if (hideDoneTask != null) uiHandler.removeCallbacks(hideDoneTask);
        statusBanner.setVisibility(LinearLayout.VISIBLE);
        statusText.setText("✅ 已完成");
        statusView.setText("✅ 已完成");
        hideDoneTask = () -> {
            doneShowing = false;
            refreshStatus();
        };
        uiHandler.postDelayed(hideDoneTask, 2000);
    }

    private void cancelDone() {
        doneShowing = false;
        if (hideDoneTask != null) {
            uiHandler.removeCallbacks(hideDoneTask);
            hideDoneTask = null;
        }
    }

    /** Shared idle completion path: session.status idle, session.idle, poll idle. UI thread only. */
    private void onServerIdle() {
        boolean quiet = suppressDone;
        suppressDone = false;
        stoppedManually = false;
        stoppedAwait = false;
        retryText = null;
        runningTools.clear();
        liveToolNames.clear();
        liveToolDetails.clear();
        clearToolLinger();
        writingShown = false;
        thinkingShown = false;
        setBusy(false);
        if (!quiet) {
            showDone(); // only real completion shows ✅
        } else {
            refreshStatus();
        }
        if (uiVisible) {
            MainActivity.clearAwaiting(this, sessionID); // 人在页内，自己看得见
        } else {
            MainActivity.clearAwaiting(this, sessionID);
            MainActivity.markJustDone(this, sessionID);
            MainActivity.notifyDone(this, sessionID, baseTitle); // 人不在本页，发通知
        }
        if (retryBtn != null) retryBtn.setVisibility(Button.GONE); // 跑成了，重试收掉
        loadHistory(); // reconcile after completion
        refreshSessionCost(); // cost changed after a run
        refreshTodos(); // todos changed after a run
        fetchSessionDiff(false); // 本轮改动数变了，刷新按钮数字
    }

    private void startWatchdog() {
        lastEventAt = System.currentTimeMillis();
        stopWatchdog();
        watchdogTask = () -> checkWatchdog();
        uiHandler.postDelayed(watchdogTask, 15000);
    }

    private void checkWatchdog() {
        watchdogTask = null;
        if (!busy || !sseRunning) return;
        long silent = System.currentTimeMillis() - lastEventAt;
        if (silent >= 30000) {
            pollSessionStatus();
        }
        watchdogTask = () -> checkWatchdog();
        uiHandler.postDelayed(watchdogTask, 15000);
    }

    private void stopWatchdog() {
        if (watchdogTask != null) {
            try {
                uiHandler.removeCallbacks(watchdogTask);
            } catch (Exception ignored) {
            }
            watchdogTask = null;
        }
    }

    /** Authoritative fallback: GET /api/session/active {data:{sid:…}}; absent = idle. */
    private void pollSessionStatus() {
        pollSessionStatus(false);
    }

    /** 0.21.15：reconcile=true 表示刚从断线里回来——活动会话必须按服务端重拉一次补漏。
     *  老逻辑只把 busy 补回来，断线期间生成的正文永远缺一截（要等整轮跑完才对齐）。 */
    private void pollSessionStatus(final boolean reconcile) {
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/api/session/active");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                MainActivity.auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setRequestMethod("GET");
                if (c.getResponseCode() != 200) return;
                JSONObject data = new JSONObject(readAll(c.getInputStream())).optJSONObject("data");
                final boolean stillActive = data != null && data.has(sessionID);
                runOnUiThread(() -> {
                    if (!stillActive) {
                        if (busy) onServerIdle();
                        else {
                            runningTools.clear();
                            liveToolNames.clear();
        liveToolDetails.clear();
        clearToolLinger();
                            writingShown = false;
                            if (stoppedAwait) { stoppedAwait = false; stoppedManually = false; suppressDone = false; loadHistory(); }
                            refreshStatus();
                        }
                    } else {
                        lastEventAt = System.currentTimeMillis();
                        if (!busy) setBusy(true);
                        else refreshStatus();
                        if (reconcile) loadHistory(); // 断线期间的增量：以服务端为准重建
                    }
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    // ---------- history ----------

    /** Title todo chip: V2 has no todo endpoint (verified 404) — hidden, see E. */
    private void refreshTodos() {
        todoSuffix = "";
        updateTitle();
    }

    /** Title shows running cost: "标题（tokens 4.6万 · $0.12）", hidden while unused. */
    private void refreshSessionCost() {
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/api/session/" + sessionID);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                MainActivity.auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setRequestMethod("GET");
                if (c.getResponseCode() != 200) return;
                JSONObject s = new JSONObject(readAll(c.getInputStream())).optJSONObject("data");
                if (s == null) return;
                double cost = s.optDouble("cost", 0);
                JSONObject t = s.optJSONObject("tokens");
                long total = 0;
                if (t != null) total = t.optLong("input", 0) + t.optLong("output", 0);
                final String suffix;
                if (total <= 0 && cost == 0) {
                    suffix = "";
                } else {
                    suffix = "（tokens " + MainActivity.formatTokens(total)
                            + " · " + MainActivity.formatCost(cost) + "）";
                }
                costSuffix = suffix;
                updateTitle();
            } catch (Exception ignored) {
                // cost display optional: never disturb chat on failure
            }
        }).start();
    }

    // ---------- 本轮改动（功能1） ----------
    // 主力：从聊天记录认 newest user turn 之后 completed/error 的 edit/write/apply_patch 工具
    // （服务端 2.0.12 实测 session.diff 所有会话回空——无快照，靠它永远显示无改动）。
    // session.diff 只当 patch 明细增强：有 patch 才进详情，无则只列文件。
    // patch 可能很大，弹窗只看前 8000 字，全文靠长按复制。
    private void fetchSessionDiff(boolean showDialog) {
        new Thread(() -> {
            try {
                // 1) 历史兜底：newest user turn 之后的改文件工具
                final java.util.ArrayList<JSONObject> hist = new java.util.ArrayList<>();
                try {
                    URL mu = new URL(BASE_URL + "/api/session/" + sessionID + "/message");
                    HttpURLConnection cu = (HttpURLConnection) mu.openConnection();
                    MainActivity.auth(cu);
                    cu.setConnectTimeout(5000);
                    cu.setReadTimeout(10000);
                    cu.setRequestMethod("GET");
                    if (cu.getResponseCode() == 200) {
                        JSONArray marr = new JSONObject(readAll(cu.getInputStream())).optJSONArray("data");
                        if (marr != null) {
                            long best = -1;
                            for (int i = 0; i < marr.length(); i++) {
                                JSONObject m = marr.optJSONObject(i);
                                if (m == null || !"user".equals(m.optString("type", ""))) continue;
                                JSONObject tm = m.optJSONObject("time");
                                long tc = tm != null ? tm.optLong("created", 0) : 0;
                                if (tc >= best) best = tc;
                            }
                            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
                            for (int i = 0; i < marr.length(); i++) {
                                JSONObject m = marr.optJSONObject(i);
                                if (m == null || !"assistant".equals(m.optString("type", ""))) continue;
                                JSONObject tm = m.optJSONObject("time");
                                if ((tm != null ? tm.optLong("created", 0) : 0) < best) continue;
                                JSONArray content = m.optJSONArray("content");
                                if (content == null) continue;
                                for (int j = 0; j < content.length(); j++) {
                                    JSONObject p = content.optJSONObject(j);
                                    if (p == null || !"tool".equals(p.optString("type", ""))) continue;
                                    String nm = p.optString("name", "");
                                    if (nm.isEmpty()) nm = p.optString("tool", "");
                                    if (!"edit".equals(nm) && !"write".equals(nm) && !"apply_patch".equals(nm)) continue;
                                    JSONObject st = p.optJSONObject("state");
                                    String sst = st != null ? st.optString("status", "") : "";
                                    if (!"completed".equals(sst) && !"error".equals(sst)) continue;
                                    String path = toolFullPath(p);
                                    if (path.isEmpty()) path = "(未知路径)";
                                    if (!seen.add(path + "|" + sst)) continue;
                                    JSONObject rec = new JSONObject();
                                    rec.put("file", path);
                                    rec.put("status", sst);
                                    rec.put("nopatch", true);
                                    hist.add(rec);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // 历史拉失败就只剩 diff，继续
                }
                // 2) session.diff：有快照时带 patch/additions/deletions
                JSONArray diffArr = new JSONArray();
                try {
                    URL url = new URL(BASE_URL + "/api/session/" + sessionID + "/diff");
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    MainActivity.auth(c);
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(10000);
                    c.setRequestMethod("GET");
                    if (c.getResponseCode() == 200) {
                        JSONArray got = new JSONObject(readAll(c.getInputStream())).optJSONArray("data");
                        if (got != null) diffArr = got;
                    }
                } catch (Exception ignored) {
                }
                // 3) 合并：diff 为主，历史补 diff 里没有的文件
                final JSONArray files = new JSONArray();
                java.util.HashSet<String> inDiff = new java.util.HashSet<>();
                int add = 0;
                int del = 0;
                for (int i = 0; i < diffArr.length(); i++) {
                    JSONObject f = diffArr.optJSONObject(i);
                    if (f == null) continue;
                    files.put(f);
                    inDiff.add(f.optString("file", ""));
                    add += f.optInt("additions", 0);
                    del += f.optInt("deletions", 0);
                }
                for (JSONObject h : hist) {
                    String fp = h.optString("file", "");
                    if (inDiff.contains(fp)) continue;
                    files.put(h);
                }
                final int n = files.length();
                final int a = add;
                final int dl = del;
                final boolean hasPatch = diffArr.length() > 0;
                runOnUiThread(() -> {
                    if (diffBtn != null) {
                        if (n == 0) {
                            diffBtn.setText("📝 改动（本轮无文件改动）");
                        } else if (hasPatch) {
                            diffBtn.setText("📝 改动（" + n + "个文件 +" + a + "/-" + dl + "，点看）");
                        } else {
                            diffBtn.setText("📝 改动（" + n + "个文件，点看）");
                        }
                    }
                    if (showDialog) showDiffList(files);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "改动加载失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /** 改文件工具 state.input 的完整路径（不截断，专门给改动列表用）。 */
    private static String toolFullPath(JSONObject p) {
        try {
            JSONObject st = p.optJSONObject("state");
            JSONObject input = st != null ? st.optJSONObject("input") : null;
            if (input == null) return "";
            String v = input.optString("path", "");
            if (v.isEmpty() || "null".equals(v)) v = input.optString("file", "");
            if ("null".equals(v)) v = "";
            return v != null ? v : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void showDiffList(JSONArray files) {
        if (files == null || files.length() == 0) {
            Toast.makeText(this, "本轮没改文件", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[files.length()];
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) { items[i] = "(?)"; continue; }
            String st = f.optString("status", "");
            if (f.optBoolean("nopatch", false)) {
                items[i] = f.optString("file", "?") + (st.isEmpty() ? "" : " [" + st + "]");
            } else {
                items[i] = f.optString("file", "?") + "  +" + f.optInt("additions", 0)
                        + "/-" + f.optInt("deletions", 0) + (st.isEmpty() ? "" : " [" + st + "]");
            }
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("本轮改了 " + files.length() + " 个文件")
                .setItems(items, (dlg, which) -> {
                    JSONObject f = files.optJSONObject(which);
                    if (f != null) showPatch(f);
                })
                .show();
    }

    private void showPatch(JSONObject f) {
        String file = f.optString("file", "?");
        String patch = f.optString("patch", "");
        if ((patch.isEmpty() || "null".equals(patch)) && f.optBoolean("nopatch", false)) {
            Toast.makeText(this, "服务端没记这次改动的明细，只确认动过此文件", Toast.LENGTH_LONG).show();
            return;
        }
        if (patch.isEmpty() || "null".equals(patch)) patch = "（服务端没给 patch 全文）";
        String shown = patch.length() > 8000 ? patch.substring(0, 8000) + "\n…（太长只看前8000字，长按复制看全文）" : patch;
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(shown);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(Palette.textPrimary());
        tv.setPadding(dp(12), dp(12), dp(12), dp(12));
        tv.setTextIsSelectable(true);
        final String full = patch;
        tv.setOnLongClickListener(v -> {
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("opencode-diff", full));
                Toast.makeText(this, "已复制全文", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "复制失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        sv.addView(tv);
        new android.app.AlertDialog.Builder(this)
                .setTitle(file)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void loadHistory() {
        runOnUiThread(() -> {
            if (!busy && !doneShowing) statusView.setText("加载记录…");
        });
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/api/session/" + sessionID + "/message");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                MainActivity.auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(10000);
                c.setRequestMethod("GET");
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                String body = readAll(c.getInputStream());
                JSONObject root = new JSONObject(body);
                JSONArray arr = root.optJSONArray("data");
                if (arr == null) throw new Exception("无data");
                JSONObject cur = root.optJSONObject("cursor");
                final String nxt = normCursor(cur != null ? cur.optString("next", "") : "");
                final int fetched = arr.length();
                // V2 服务端返回倒序（新在前），先反转再按 time.created 稳定升序（同毫秒保持服务端正序）
                java.util.List<JSONObject> msgs = new java.util.ArrayList<>();
                for (int i = 0; i < arr.length(); i++) msgs.add(arr.getJSONObject(i));
                java.util.Collections.reverse(msgs);
                msgs.sort((a, b) -> Long.compare(
                    a.optJSONObject("time") != null ? a.optJSONObject("time").optLong("created") : 0,
                    b.optJSONObject("time") != null ? b.optJSONObject("time").optLong("created") : 0));
                runOnUiThread(() -> {
                    // 首次进页（列表还是空）强制到底；跑完重拉时尊重用户当前位置，
                    // 免得正在上滑看历史时被拽回底部（0.21.7）
                    boolean firstRender = msgBox.getChildCount() == 0 || stickToBottom;
                    final int y0 = scrollView.getScrollY();
                    dropPendingFlush();
                    msgBox.removeAllViews();
                    partViews.clear();
                    partBuf.clear();
                    reasonBlocks.clear();
                    msgBubbles.clear();
                    loadingEarlier = false;
                    // 只有真的取满一页才可能有更早（短会话直接不显示按钮）；
                    // 整页时再用 limit=1 探针确认，避免最老一页还顶着一个点了回 0 条的按钮。
                    // 0.22.2：这一步必须先把游标设上——0.21.12 只保留了「探针回空则清空」，
                    // 却把赋值删了，导致游标恒为 null，按钮在全新加载后永远不出现（真机反馈丢历史）。
                    earlierCursor = (fetched >= MSG_PAGE_SIZE && !nxt.isEmpty()) ? nxt : null;
                    final int pseq = ++earlierProbeSeq;
                    if (earlierCursor != null) probeHasEarlier(earlierCursor, pseq);
                    refreshEarlierBtn();
                    for (JSONObject m : msgs) {
                        try {
                            renderV2Message(m);
                        } catch (Exception ignored) {
                        }
                    }
                    if (firstRender) {
                        scrollDownForce();
                    } else {
                        // 用户在上滑看历史：重渲染后回到原来的位置（removeAllViews 会归零）
                        scrollView.post(() -> scrollView.scrollTo(0, y0));
                    }
                    runningTools.clear();
                    liveToolNames.clear();
        liveToolDetails.clear();
        clearToolLinger();
                    writingShown = false;
                    thinkingShown = false;
                    if (!doneShowing) refreshStatus();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusView.setText("记录加载失败：" + e.getMessage());
                    Toast.makeText(this, "加载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** 0.21.12：服务端 `next` 游标不表示「还有」，只在取满一页时用 limit=1 探一下真伪，
     *  探针回空即收起按钮（省掉「点一下显示0条」的操作）。网络异常按「有」处理，别把入口弄丢。 */
    private void probeHasEarlier(final String cursor, final int seq) {
        new Thread(() -> {
            boolean has = true;
            try {
                String u = BASE_URL + "/api/session/" + sessionID + "/message?limit=1&cursor="
                        + java.net.URLEncoder.encode(cursor, "UTF-8");
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                MainActivity.auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setRequestMethod("GET");
                if (c.getResponseCode() == 200) {
                    JSONArray arr = new JSONObject(readAll(c.getInputStream())).optJSONArray("data");
                    has = arr != null && arr.length() > 0;
                }
            } catch (Exception ignored) {
            }
            final boolean fhas = has;
            runOnUiThread(() -> {
                if (seq != earlierProbeSeq) return; // 已被新的历史重拉取代
                if (!fhas) {
                    earlierCursor = null;
                    refreshEarlierBtn();
                }
            });
        }).start();
    }

    /** 翻页按钮显隐：有更早页才驻顶（msgBox index 0），加载中则禁用。 */
    private void refreshEarlierBtn() {
        if (earlierBtn == null) return;
        if (earlierCursor == null) {
            if (earlierBtn.getParent() != null) msgBox.removeView(earlierBtn);
            return;
        }
        earlierBtn.setEnabled(!loadingEarlier);
        earlierBtn.setText(loadingEarlier ? "加载中…" : "⬆ 加载更早记录");
        if (earlierBtn.getParent() == null) msgBox.addView(earlierBtn, 0);
    }

    /** 向前翻一页：按 cursor.next 取更早消息，升序 prepend 到顶部并保持滚动位置。 */
    private void loadEarlier() {
        if (loadingEarlier || earlierCursor == null) return;
        loadingEarlier = true;
        runOnUiThread(this::refreshEarlierBtn);
        final String cur = earlierCursor;
        final int y0 = scrollView.getScrollY();
        final int h0 = msgBox.getHeight();
        new Thread(() -> {
            try {
                String q = "?cursor=" + java.net.URLEncoder.encode(cur, "UTF-8");
                URL url = new URL(BASE_URL + "/api/session/" + sessionID + "/message" + q);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                MainActivity.auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(10000);
                c.setRequestMethod("GET");
                int code = c.getResponseCode();
                if (code != 200) {
                    String detail = "";
                    try {
                        java.io.InputStream es = c.getErrorStream();
                        if (es != null) detail = readAll(es).trim();
                    } catch (Exception ignored2) {
                    }
                    throw new Exception("HTTP " + code + (detail.isEmpty() ? "" : " " + detail.substring(0, Math.min(120, detail.length()))));
                }
                String body = readAll(c.getInputStream());
                JSONObject root = new JSONObject(body);
                JSONArray arr = root.optJSONArray("data");
                if (arr == null) throw new Exception("无data");
                JSONObject cu = root.optJSONObject("cursor");
                final String nnxt = normCursor(cu != null ? cu.optString("next", "") : "");
                java.util.List<JSONObject> msgs = new java.util.ArrayList<>();
                for (int i = 0; i < arr.length(); i++) msgs.add(arr.getJSONObject(i));
                // 服务端倒序（新在前）：先反转再稳定排序，同 created 并列时保持服务端正序（旧在前）
                java.util.Collections.reverse(msgs);
                msgs.sort((a, b) -> Long.compare(
                    a.optJSONObject("time") != null ? a.optJSONObject("time").optLong("created") : 0,
                    b.optJSONObject("time") != null ? b.optJSONObject("time").optLong("created") : 0));
                runOnUiThread(() -> {
                    int base = (earlierBtn != null && earlierBtn.getParent() != null) ? 1 : 0;
                    int start = msgBox.getChildCount();
                    for (JSONObject m : msgs) {
                        try {
                            String mid = m.optString("id", "");
                            if (!mid.isEmpty() && msgBubbles.containsKey(mid)) continue;
                            renderV2Message(m);
                        } catch (Exception ignored) {
                        }
                    }
                    // 只搬实际新增的气泡：idle(succeeded)/agent-switched/system 等不渲染，
                    // 旧 added++ 会多搬，把底部最新消息偷到顶部（顺序错乱+最新丢失）；
                    // 另旧搬运循环 remove(start)+insert(base+i) 在 added>1 时本就错位
                    //（尾部新气泡经一次搬运后不再位于 start），现先收后插，一次摆正。
                    int end = msgBox.getChildCount();
                    int addedViews = end - start;
                    if (addedViews > 0) {
                        java.util.ArrayList<android.view.View> moved = new java.util.ArrayList<>();
                        for (int i = start; i < end; i++) moved.add(msgBox.getChildAt(i));
                        for (android.view.View v : moved) msgBox.removeView(v);
                        for (int i = 0; i < moved.size(); i++) msgBox.addView(moved.get(i), base + i);
                    }
                    // 空页或全是已渲染的重复条目 = 已到最早，别再让按钮空转
                    earlierCursor = (msgs.isEmpty() || addedViews == 0 || nnxt.isEmpty()) ? null : nnxt;
                    loadingEarlier = false;
                    final int pseq = ++earlierProbeSeq;
                    if (earlierCursor != null) probeHasEarlier(earlierCursor, pseq);
                    refreshEarlierBtn();
                    scrollView.post(() -> scrollView.scrollTo(0, y0 + Math.max(0, msgBox.getHeight() - h0)));
                    Toast.makeText(this,
                            "已加载 " + addedViews + " 条" + (earlierCursor == null ? "，已到最早" : ""),
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingEarlier = false;
                    refreshEarlierBtn();
                    Toast.makeText(this, "加载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** V2 message ({id,time,text?,type,content[]}) -> v1-style render. */
    private void renderV2Message(JSONObject m) throws Exception {
        String type = m.optString("type", "?");
        String mid = m.optString("id", "");
        if ("user".equals(type)) {
            JSONObject info = new JSONObject();
            info.put("role", "user");
            info.put("id", mid);
            JSONArray parts = new JSONArray();
            String utext = m.optString("text", "");
            if (!utext.isEmpty()) {
                JSONObject tp = new JSONObject();
                tp.put("type", "text");
                tp.put("text", utext);
                parts.put(tp);
            }
            lastRunErrorShown = false; // 新一轮用户发言，上一轮的错误提示翻篇
            // V2 user files: [{name,mime,source{uri},data?}] -> placeholder row
            JSONArray files = m.optJSONArray("files");
            if (files != null) {
                for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.optJSONObject(i);
                    if (f == null) continue;
                    JSONObject fp = new JSONObject();
                    fp.put("type", "file");
                    fp.put("filename", f.optString("name", ""));
                    fp.put("mime", f.optString("mime", ""));
                    JSONObject src = f.optJSONObject("source");
                    fp.put("url", src != null ? src.optString("uri", "") : "");
                    parts.put(fp);
                }
            }
            renderMessage(info, parts);
        } else if ("assistant".equals(type)) {
            JSONObject info = new JSONObject();
            info.put("role", "assistant");
            info.put("id", mid);
            JSONArray parts = m.optJSONArray("content");
            if (parts == null) parts = new JSONArray();
            // tag part views with ids so live deltas can find them
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                if (p == null) continue;
                if (!p.has("id") || p.optString("id", "").isEmpty()) {
                    p.put("id", mid + "#" + i);
                }
                if (!p.has("messageID")) p.put("messageID", mid);
            }
            // 0.22.4：V2 的失败原因挂在消息对象上（error{type,message,status}），
            // 历史渲染必须带进 info —— 否则 content=[] 的失败消息只剩一句
            // 「运行未成功：failed」，看着像断线（真机：provider.quota 429 被吞）。
            JSONObject errObj = m.optJSONObject("error");
            if (errObj != null) info.put("error", errObj);
            lastRunErrorShown = errObj != null; // 失败原因已渲染进气泡，idle 只补壳
            renderMessage(info, parts);
        } else if ("idle".equals(type)) {
            // completion marker: only visible when the run did NOT succeed.
            // 0.22.4：失败原因已由 assistant 的 error 气泡显示时，这里不再重复
            // 一句没有信息量的「运行未成功：failed」。
            String outcome = m.optString("outcome", "succeeded");
            boolean interrupted = "interrupted".equals(outcome) || "aborted".equals(outcome) || "cancelled".equals(outcome);
            if (!"succeeded".equals(outcome) && (interrupted || !lastRunErrorShown)
                    && !mid.isEmpty() && !msgBubbles.containsKey(mid)) {
                LinearLayout bubble = new LinearLayout(this);
                bubble.setOrientation(LinearLayout.VERTICAL);
                bubble.setPadding(dp(12), dp(10), dp(12), dp(10));
                bubble.setBackgroundColor(Palette.surface());
                TextView tv = new TextView(this);
                tv.setText(interrupted ? "⏹️ 已停止" : "❌ 运行未成功：" + outcome);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                tv.setTextColor(interrupted ? Palette.interrupted() : Palette.error());
                bubble.addView(tv);
                msgBox.addView(bubble);
                msgBubbles.put(mid, bubble);
            }
            lastRunErrorShown = false;
        }
        // system/synthetic/skill/shell/compaction: not rendered (same as v1
        // ignoring snapshot/patch/step-*); full text stays in server history.
    }

    private void renderMessage(JSONObject info, JSONArray parts) {
        if (info == null) return;
        String role = info.optString("role", "?");
        String mid = info.optString("id", "");
        boolean user = "user".equals(role);

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bLp.topMargin = dp(8);
        bubble.setLayoutParams(bLp);
        bubble.setBackgroundColor(user ? Palette.userBubble() : Palette.surface());

        TextView roleTv = new TextView(this);
        roleTv.setText(user ? "我" : "AI");
        roleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        roleTv.setTextColor(Palette.textMuted());
        bubble.addView(roleTv);
        if (!mid.isEmpty()) msgBubbles.put(mid, bubble);

        if (parts != null) {
            int shown = 0;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                if (p == null) continue;
                if (addPartView(bubble, p) != null) shown++;
            }
            if (shown == 0 && !user) {
                // assistant message with only step-*/empty-reasoning parts
                // (common right after sending an .md, before the model writes):
                // never leave a confusing blank bubble. BUT if the server
                // recorded an error (e.g. unsupported file mime ->
                // parts=[]), show the error text instead of the placeholder,
                // otherwise the failure looks like "blank chat / stuck".
                String errMsg = extractErrorMsg(info);
                TextView ph = new TextView(this);
                if (!errMsg.isEmpty()) {
                    ph.setText("❌ 运行失败：" + errMsg + errorHint(info));
                    ph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    ph.setTextColor(Palette.error());
                } else {
                    ph.setText("⋯ AI 正在准备回复 ⋯");
                    ph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    ph.setTextColor(Palette.textMuted());
                }
                bubble.addView(ph);
            }
        }
        // 0.21.5：AI 气泡底部加"复制全文"——一键复制整条回复的完整原文（含 markdown 表格）。
        // 分段渲染后长按只能复制单段（见 makeCopyable），整段复制需消息级按钮。
        if (!user) {
            String fullReply = collectReplyText(parts);
            if (!fullReply.trim().isEmpty()) addCopyAllButton(bubble, fullReply);
        }
        msgBox.addView(bubble);
    }

    /** Create a view for one part; returns the body TextView (or null). */
    private TextView addPartView(LinearLayout bubble, JSONObject p) {
        String type = p.optString("type", "?");
        String pid = p.optString("id", "");
        String mid = p.optString("messageID", "");
        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTextColor(Palette.textPrimary());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        tv.setLayoutParams(lp);

        if ("text".equals(type)) {
            String text = p.optString("text", "");
            if ("null".equals(text)) text = "";
            // synthetic expansion: server inlines text attachments (.md/.txt)
            // into synthetic parts — "Called the Read tool ..." noise plus a
            // "<path>..<content>全文</content>" dump (13KB for DEVELOPER.md).
            // Never flood the chat: hide noise, collapse the dump to one line
            // (full text still available via long-press copy).
            if (p.optBoolean("synthetic", false)) {
                if (text.startsWith("Called the Read tool")) return null;
                if (text.length() > 300) {
                    String path = extractPathTag(text);
                    String ph = "📄 附件内容"
                            + (path.isEmpty() ? "" : " · " + shortName(path))
                            + "（约" + text.length() + "字，已折叠，长按复制看全文）";
                    tv.setText(ph);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    tv.setTextColor(Palette.textMuted());
                    tv.setBackgroundColor(Palette.codeBg());
                    final String full = text;
                    tv.setOnLongClickListener(v -> {
                        copyText(full);
                        return true;
                    });
                    bubble.addView(tv);
                    return tv;
                }
            }
            // 0.21.2：正文统一按 段落 / 表格 / 代码块 切段渲染（表格=真网格，代码块去 ``` 引号）
            TextView firstTv = renderTextSegments(bubble, text, tv);
            addCodeCopyIfAny(bubble, text);
            TextView mapTv = firstTv != null ? firstTv : tv;
            if (!pid.isEmpty()) {
                partViews.put(pid, mapTv);
                partBuf.put(pid, new StringBuilder(text));
            }
            // 0.21.15：流式增量按 messageID 定位（事件里根本没有 partID），而实测
            // /message 的 content[].id 全为空、历史只能用 mid#index 兜底。两者对不上，
            // 于是「重拉历史后迟到的增量」会另起一个 TextView，把尾巴重复一遍。
            // 补一条 mid 别名指向本条消息最后一个 text 视图（后面的会覆盖前面的），
            // 重拉后增量就接着这个视图往下写。
            if (!mid.isEmpty()) {
                partViews.put(mid, mapTv);
                partBuf.put(mid, new StringBuilder(text));
            }
            return mapTv;
        } else if ("reasoning".equals(type)) {
            String rt = p.optString("text", "");
            if ("null".equals(rt)) rt = "";
            if (rt.trim().isEmpty()) return null; // empty thinking: no blank line
            // 0.21.2：历史思考也默认折叠 6 行，点标题展开（与流式一致）
            return addCollapsedReasoning(bubble, rt, mid);
        } else if ("tool".equals(type)) {
            tv.setText(toolLine(p));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tv.setTextColor(Palette.textSecondary());
            tv.setBackgroundColor(Palette.codeBg());
            if (!pid.isEmpty()) partViews.put(pid, tv);
            // 0.22.5：工具输出可见——完成后把 state.content 渲染在工具行下（默认折叠）
            makeCopyable(tv);
            bubble.addView(tv);
            addToolOutput(bubble, p);
            return tv;
        } else if ("file".equals(type)) {
            // file placeholder: NEVER dump url content into the bubble.
            // Server stores attachments as data:<mime>;base64,… (≈27KB for a 20KB .md),
            // with filename=null. Old code cut at last '/' ("text/markdown" slash)
            // and set tens of KB base64 as TextView text: whole page looked broken.
            String name = safeFileStr(p.optString("filename", ""));
            String mime = safeFileStr(p.optString("mime", ""));
            String u = safeFileStr(p.optString("url", ""));
            if (name.isEmpty()) {
                if (u.startsWith("data:")) {
                    String size = dataUrlSizeKB(u);
                    String ext = fileExtForMime(mime);
                    name = ext.isEmpty() ? size : ext + (size.isEmpty() ? "" : "（" + size + "）");
                } else if (!u.isEmpty()) {
                    int cut = u.lastIndexOf('/');
                    name = cut >= 0 ? u.substring(cut + 1) : u;
                    int q = name.indexOf('?');
                    if (q >= 0) name = name.substring(0, q);
                    if (name.length() > 40) name = name.substring(0, 40) + "…";
                }
            } else if (name.length() > 40) {
                name = name.substring(0, 40) + "…";
            }
            boolean isImg = mime.startsWith("image/");
            String kind = isImg ? "📷 图片" : "📎 文件";
            String label;
            if (name.isEmpty()) {
                label = mime.isEmpty() ? kind : kind + "（" + mime + "）";
            } else if (u.startsWith("data:")) {
                // data: urls carry no filename: "📷 图片（32KB）" / "📎 文件.md（20KB）"
                if (name.startsWith("（") || name.startsWith("(")) label = kind + name;
                else if (name.startsWith(".")) label = kind + name;
                else label = kind + "：" + name;
            } else {
                label = kind + "：" + name;
            }
            if (label.length() > 80) label = label.substring(0, 80) + "…";
            tv.setText(label);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tv.setTextColor(Palette.textMuted());
            tv.setBackgroundColor(Palette.codeBg());
        } else {
            return null; // snapshot/patch/step 等 MVP 忽略
        }
        makeCopyable(tv);
        bubble.addView(tv);
        // remember message link for delta-created parts
        if (!pid.isEmpty() && !mid.isEmpty()) {
            // no-op, bubble already mapped
        }
        return tv;
    }

    /** File placeholder helpers: org.json may return "null" string for JSONObject.NULL. */
    private static String safeFileStr(String s) {
        if (s == null || "null".equals(s)) return "";
        return s;
    }

    /**
     * 0.22.5 工具输出可见：只处理历史渲染的 tool 分支（不动流式/事件路径）。
     * 实测 V2 state.content = [{type:"text",text:"…"}, …]（单条最大 12KB），
     * 逐项取 .text 拼接，completed 才显示；空数组不显示。
     * 渲染在工具行下：等宽 + codeBg，默认折叠 3 行 + 「▸ 展开/▾ 收起」，
     * 长按复制全文（makeCopyable 拿的是 TextView 全文，maxLines 只影响显示）。
     */
    private void addToolOutput(LinearLayout bubble, JSONObject p) {
        try {
            JSONObject st = p.optJSONObject("state");
            if (st == null) return;
            if (!"completed".equals(st.optString("status"))) return;
            Object c = st.opt("content");
            StringBuilder sb = new StringBuilder();
            if (c instanceof JSONArray) {
                JSONArray arr = (JSONArray) c;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    String t = it != null ? safeFileStr(it.optString("text", ""))
                            : String.valueOf(arr.opt(i));
                    if (!t.isEmpty() && !"null".equals(t)) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(t);
                    }
                }
            } else if (c instanceof String) { // 兼容：极老版本可能直接是字符串
                String s = (String) c;
                if (!s.isEmpty()) sb.append(s);
            }
            final String full = sb.toString();
            if (full.isEmpty()) return;
            final TextView out = new TextView(this);
            out.setTypeface(android.graphics.Typeface.MONOSPACE);
            out.setText(full);
            out.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            out.setTextColor(Palette.textSecondary());
            out.setBackgroundColor(Palette.codeBg());
            out.setPadding(dp(8), dp(4), dp(8), dp(4));
            out.setMaxLines(3);
            out.setEllipsize(android.text.TextUtils.TruncateAt.END);
            makeCopyable(out); // 长按复制全文
            bubble.addView(out);
            final TextView hdr = new TextView(this);
            hdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            hdr.setTextColor(Palette.textMuted());
            hdr.setText("▸ 工具输出（点展开）");
            hdr.setOnClickListener(v -> {
                boolean expanded = out.getMaxLines() == Integer.MAX_VALUE;
                out.setMaxLines(expanded ? 3 : Integer.MAX_VALUE);
                out.setEllipsize(expanded ? android.text.TextUtils.TruncateAt.END : null);
                hdr.setText(expanded ? "▸ 工具输出（点展开）" : "▾ 工具输出（点收起）");
            });
            bubble.addView(hdr);
        } catch (Exception ignored) {
        }
    }

    /** Long-press copy of an explicit string (collapsed synthetic placeholder). */
    private void copyText(String s) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("opencode", s));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "复制失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** "<path>X</path>" -> X, else "". */
    private static String extractPathTag(String s) {
        try {
            int a = s.indexOf("<path>");
            int b = s.indexOf("</path>");
            if (a >= 0 && b > a) return s.substring(a + 6, b).trim();
        } catch (Exception ignored) {
        }
        return "";
    }

    /** Server error text lives at error.data.message (UnknownError) or error.message. */
    private static String extractErrorMsg(JSONObject info) {
        try {
            JSONObject err = info.optJSONObject("error");
            if (err == null) return "";
            String m = err.optString("message", "");
            if (!m.isEmpty() && !"null".equals(m)) return m;
            JSONObject data = err.optJSONObject("data");
            if (data != null) {
                m = data.optString("message", "");
                if (!m.isEmpty() && !"null".equals(m)) return m;
            }
            String name = err.optString("name", "");
            if (!name.isEmpty() && !"null".equals(name)) return name;
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 0.22.4：给常见失败原因补一句中文提示（免费模型 429 额度、invalid-output）。 */
    private static String errorHint(JSONObject info) {
        JSONObject err = info.optJSONObject("error");
        String type = err != null ? err.optString("type", "") : "";
        String low = type.toLowerCase();
        if (low.contains("quota")) {
            return "\n提示：免费额度已用完（服务端 429），不是断线。换一个免费模型，或等额度重置。";
        }
        if (low.contains("invalid-output")) {
            return "\n提示：免费模型偶发无效输出，点顶栏「↻ 重试」再跑一次。";
        }
        if (low.contains("network") || low.contains("timeout")) {
            return "\n提示：网络到模型这一段断了，点顶栏「↻ 重试」。";
        }
        return "";
    }

    /** "a/b/c.md" -> "c.md" (40-char cap). */
    private static String shortName(String path) {
        if (path == null) return "";
        int cut = path.lastIndexOf('/');
        String n = cut >= 0 ? path.substring(cut + 1) : path;
        if (n.length() > 40) n = n.substring(0, 40) + "…";
        return n;
    }

    private static String fileExtForMime(String mime) {
        if (mime == null) return "";
        if (mime.startsWith("text/markdown") || mime.startsWith("text/x-markdown")) return ".md";
        if (mime.startsWith("text/plain")) return ".txt";
        if (mime.startsWith("text/html")) return ".html";
        if (mime.startsWith("application/json")) return ".json";
        if (mime.startsWith("application/pdf")) return ".pdf";
        if (mime.startsWith("image/jpeg")) return ".jpg";
        if (mime.startsWith("image/png")) return ".png";
        if (mime.startsWith("image/webp")) return ".webp";
        if (mime.startsWith("image/gif")) return ".gif";
        return "";
    }

    /** Estimate original bytes from data: URL length (base64 ≈ 4/3). */
    private static String dataUrlSizeKB(String u) {
        try {
            int comma = u.indexOf(',');
            int len = comma >= 0 ? u.length() - comma - 1 : u.length();
            long bytes = len * 3L / 4L;
            if (bytes < 1024) return bytes + "B";
            if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
            return String.format(java.util.Locale.US, "%.1fMB", bytes / 1048576.0);
        } catch (Exception e) {
            return "";
        }
    }

    private static String toolLine(JSONObject p) {
        // V2 keys: name + state.status; V1 used tool + state.title (kept as fallback).
        String tool = p.optString("name", "");
        if (tool.isEmpty()) tool = p.optString("tool", "tool");
        JSONObject st = p.optJSONObject("state");
        String status = st != null ? st.optString("status", "?") : "?";
        String title = st != null ? st.optString("title", "") : "";
        if (title.isEmpty()) title = toolDetail(p); // V2：state.title 恒空，用 input 的命令/路径兜底
        String mark = "⏳";
        if ("completed".equals(status)) mark = "✅";
        else if ("error".equals(status)) mark = "❌";
        else if ("running".equals(status) || "streaming".equals(status)) mark = "🔧";
        String line = mark + " " + tool + " [" + status + "]" + (title.isEmpty() ? "" : " " + title);
        if ("error".equals(status) && st != null) {
            Object errObj = st.opt("error");
            String err = "";
            if (errObj instanceof JSONObject) {
                err = ((JSONObject) errObj).optString("message", "");
                if (err.isEmpty()) err = ((JSONObject) errObj).optString("type", "");
            } else if (errObj instanceof String) {
                err = (String) errObj;
            }
            if (!err.isEmpty() && err.length() > 200) err = err.substring(0, 200) + "…";
            if (!err.isEmpty()) line += "\n" + err;
        }
        return line;
    }

    // ---------- send / abort ----------

    private void send() {
        final String text = input.getText().toString().trim();
        final String attachPath = pendingAttachPath;
        final String attachMime = pendingAttachMime != null ? pendingAttachMime : "image/jpeg";
        if (text.isEmpty() && attachPath == null) return;
        final boolean wasBusy = busy; // busy 时发 = 排队/steer，服务端会接管（实测 delivery:"steer"）
        input.setText("");
        saveDraft(); // 已发出，草稿清掉，下次进来不该回填旧内容
        lastSentText = text; // 再试一次就重发这句；带附件那次只记文字
        lastHadAttach = attachPath != null;
        if (retryBtn != null) retryBtn.setVisibility(Button.GONE); // 新一发，旧失败翻篇
        suppressDone = false;
        stoppedManually = false;
        stoppedAwait = false;
        retryText = null;
        cancelDone();
        lastEventAt = System.currentTimeMillis();
        thinkingShown = false;
        setBusy(true);
        MainActivity.markAwaiting(this, sessionID, baseTitle); // 离开本页后靠它提醒
        if (wasBusy) Toast.makeText(this, "已排队，当前回复结束后处理", Toast.LENGTH_SHORT).show();
        // optimistic user bubble
        runOnUiThread(() -> {
            try {
                JSONObject info = new JSONObject();
                info.put("role", "user");
                info.put("id", "local-" + System.currentTimeMillis());
                JSONArray parts = new JSONArray();
                if (!text.isEmpty()) {
                    JSONObject tp = new JSONObject();
                    tp.put("type", "text");
                    tp.put("text", text);
                    parts.put(tp);
                }
                if (attachPath != null) {
                    JSONObject fp = new JSONObject();
                    fp.put("type", "file");
                    fp.put("mime", attachMime);
                    fp.put("url", "file://" + attachPath);
                    parts.put(fp);
                }
                renderMessage(info, parts);
                scrollDownForce(); // 自己刚发消息：回到底部并恢复跟随
            } catch (Exception ignored) {
            }
        });
        new Thread(() -> {
            try {
                // model + 思考档位（0.20.0）：下拉即会话真实值；空的 providerID/id 表示未知，
                // 跳过不切。V2 Model.Ref = {id, providerID, variant?}（openapi 实测；
                // 错用 modelID 键会 400；variant 为空则不发，用模型默认档位）。
                int pos = modelSpinner != null ? modelSpinner.getSelectedItemPosition() : -1;
                if (pos >= 0 && pos < modelProviderIDs.size() && pos < modelIDs.size()) {
                    String pid = modelProviderIDs.get(pos);
                    String mid2 = modelIDs.get(pos);
                    if (!pid.isEmpty() && !mid2.isEmpty()) {
                        JSONObject mb = new JSONObject();
                        JSONObject mr = new JSONObject();
                        mr.put("providerID", pid);
                        mr.put("id", mid2);
                        String variant = selectedVariantID();
                        if (!variant.isEmpty()) mr.put("variant", variant);
                        mb.put("model", mr);
                        postJson("/api/session/" + sessionID + "/model", mb.toString());
                    }
                }
                // agent selection: 空 id（未设置）则跳过，用服务端默认
                int apos = agentSpinner != null ? agentSpinner.getSelectedItemPosition() : -1;
                if (apos >= 0 && apos < agentIDs.size()) {
                    String aname = agentIDs.get(apos);
                    if (!aname.isEmpty()) {
                        JSONObject ab = new JSONObject();
                        ab.put("agent", aname);
                        postJson("/api/session/" + sessionID + "/agent", ab.toString());
                    }
                }
                URL url = new URL(BASE_URL + "/api/session/" + sessionID + "/prompt");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                MainActivity.auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(15000);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                JSONObject body = new JSONObject();
                // V2 text is required: pure-attachment sends carry a placeholder
                body.put("text", text.isEmpty() ? "（附件说明见文件）" : text);
                if (attachPath != null) {
                    JSONArray files = new JSONArray();
                    JSONObject f = new JSONObject();
                    f.put("uri", "file://" + attachPath);
                    f.put("name", shortName(attachPath));
                    files.put(f);
                    body.put("files", files);
                }
                byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
                OutputStream os = c.getOutputStream();
                os.write(out);
                os.flush();
                os.close();
                int code = c.getResponseCode();
                if (code != 200 && code != 204) throw new Exception("HTTP " + code);
                readAll(c.getInputStream());
                clearAttachment(); // image consumed by this send
                // reply arrives via SSE; busy cleared on execution.succeeded
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "发送失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    MainActivity.clearAwaiting(this, sessionID); // 没发出去就不用等了
                    setBusy(false);
                    // 网络失败也给重试：字还在 lastSentText 里
                    if (retryBtn != null && !lastSentText.isEmpty()) {
                        retryBtn.setVisibility(Button.VISIBLE);
                    }
                });
            }
        }).start();
    }

    /** 再试一次（功能3a）：把上次发出的纯文字原样重发一遍，不用重新打字。 */
    private void retryLast() {
        if (busy) {
            Toast.makeText(this, "还在跑，等跑完再说", Toast.LENGTH_SHORT).show();
            return;
        }
        if (lastSentText == null || lastSentText.isEmpty()) {
            Toast.makeText(this, "没有可重发的内容", Toast.LENGTH_SHORT).show();
            return;
        }
        if (lastHadAttach) {
            input.setText(lastSentText);
            Toast.makeText(this, "上次带附件，附件要重选，文字已填好", Toast.LENGTH_LONG).show();
            return;
        }
        input.setText(lastSentText);
        send();
    }

    /** Small POST helper for V2 switch endpoints (model/agent); throws on HTTP error. */
    private void postJson(String path, String json) throws Exception {
        URL url = new URL(BASE_URL + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        MainActivity.auth(c);
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        OutputStream os = c.getOutputStream();
        os.write(out);
        os.flush();
        os.close();
        int code = c.getResponseCode();
        if (code != 200 && code != 204) throw new Exception("HTTP " + code);
        try {
            readAll(c.getInputStream());
        } catch (Exception ignored) {
        }
    }

    private void abort() {
        flushNow(); // 停止前把已收到的正文落屏
        suppressDone = true; // idle after interrupt must NOT show ✅
        stoppedManually = true;
        stoppedAwait = true;
        runningTools.clear();
        liveToolNames.clear();
        liveToolDetails.clear();
        clearToolLinger();
        writingShown = false;
        refreshStatus(); // 立刻给"已发送停止"反馈，不等服务端 failed 事件
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/api/session/" + sessionID + "/interrupt");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                MainActivity.auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setRequestMethod("POST");
                c.getResponseCode();
                runOnUiThread(() -> {
                    Toast.makeText(this, "已发送停止", Toast.LENGTH_SHORT).show();
                    MainActivity.clearAwaiting(this, sessionID); // 自己按的停止，不用再提醒
                    setBusy(false); // 停止按钮隐藏、watchdog 停
                    stoppedAwait = true;
                    refreshStatus();
                    pollSessionStatus(); // 马上对一次账，补掉服务端已完成未推 SSE 的情况
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    stoppedManually = false;
                    stoppedAwait = false;
                    Toast.makeText(this, "停止失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    refreshStatus();
                });
            }
        }).start();
    }

    // ---------- models & agents ----------

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

    private void loadModels() {
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/api/model");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                MainActivity.auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(10000);
                c.setRequestMethod("GET");
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                String body = readAll(c.getInputStream());
                JSONArray models = new JSONObject(body).optJSONArray("data");
                if (models == null) throw new Exception("no data");
                final java.util.ArrayList<String> names = new java.util.ArrayList<>();
                final java.util.ArrayList<String> pids = new java.util.ArrayList<>();
                final java.util.ArrayList<String> mids = new java.util.ArrayList<>();
                // 思考档位表（0.20.0）：所有模型（含收费/openrouter）的 variants[]，按 providerID+id 建键。
                // 会话当前模型若不在下拉列表里，也能显示并切换它的档位。
                final java.util.HashMap<String, java.util.ArrayList<String>> vs =
                        new java.util.HashMap<>();
                for (int i = 0; i < models.length(); i++) {
                    JSONObject m = models.optJSONObject(i);
                    if (m == null) continue;
                    String p = m.optString("providerID", "");
                    String midKey = m.optString("id", "");
                    JSONArray varr = m.optJSONArray("variants");
                    if (p.isEmpty() || midKey.isEmpty() || varr == null || varr.length() == 0) continue;
                    java.util.ArrayList<String> vlist = new java.util.ArrayList<>();
                    for (int j = 0; j < varr.length(); j++) {
                        JSONObject v = varr.optJSONObject(j);
                        String vid = v != null ? v.optString("id", "") : "";
                        if (!vid.isEmpty()) vlist.add(vid);
                    }
                    if (!vlist.isEmpty()) vs.put(variantKey(p, midKey), vlist);
                }
                // 0.22.8：不再只留 opencode 免费模型。opencode 仍只显示免费（免得误点收费），
                // 其他 provider（openrouter 等）全部显示——免费额度不够时用户要能切过去。
                // 标签前加 🆓/💳，一眼区分是否收费；模型太多靠右侧 🔍 搜索（showModelSearchDialog）。
                java.util.ArrayList<JSONObject> shown = new java.util.ArrayList<>();
                for (int i = 0; i < models.length(); i++) {
                    JSONObject m = models.optJSONObject(i);
                    if (m == null) continue;
                    String p = m.optString("providerID", "");
                    String mid = m.optString("modelID", "");
                    if (p.isEmpty() || mid.isEmpty()) continue;
                    if ("opencode".equals(p) && !isFreeModel(m)) continue;
                    shown.add(m);
                }
                shown.sort((a, b) -> {
                    String pa = a.optString("providerID", "");
                    String pb = b.optString("providerID", "");
                    boolean oa = "opencode".equals(pa);
                    boolean ob = "opencode".equals(pb);
                    if (oa != ob) return oa ? -1 : 1; // opencode 免费在前
                    int pc = pa.compareTo(pb);
                    if (pc != 0) return pc;
                    boolean fa = isFreeModel(a);
                    boolean fb = isFreeModel(b);
                    if (fa != fb) return fa ? -1 : 1; // 同 provider 内免费在前
                    return a.optString("modelID", "").compareTo(b.optString("modelID", ""));
                });
                for (JSONObject m : shown) {
                    String pid = m.optString("providerID", "");
                    String mid = m.optString("modelID", "");
                    String fid = m.optString("id", mid);
                    if (fid.isEmpty()) continue;
                    names.add((isFreeModel(m) ? "🆓 " : "💳 ") + pid + "/" + mid);
                    pids.add(pid);
                    mids.add(fid);
                }
                runOnUiThread(() -> {
                    modelNames.clear();
                    modelNames.addAll(names);
                    modelProviderIDs.clear();
                    modelProviderIDs.addAll(pids);
                    modelIDs.clear();
                    modelIDs.addAll(mids);
                    modelVariants.clear();
                    modelVariants.putAll(vs);
                    suppressModelEvents = true;
                    modelAdapter.notifyDataSetChanged();
                    suppressModelEvents = false;
                    modelsLoaded = true;
                    applySessionMeta();
                });
            } catch (Exception ignored) {
                // model picker optional: sending without model uses session default
                runOnUiThread(() -> {
                    modelsLoaded = true;
                    applySessionMeta();
                });
            }
        }).start();
    }

    /** Agent dropdown: GET /api/agent {data:[{id,name,mode}]}, primary only. Sends id. */
    private void loadAgents() {
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/api/agent");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                MainActivity.auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setRequestMethod("GET");
                if (c.getResponseCode() != 200) throw new Exception("HTTP");
                JSONArray arr = new JSONObject(readAll(c.getInputStream())).optJSONArray("data");
                if (arr == null) throw new Exception("no data");
                final java.util.ArrayList<String> names = new java.util.ArrayList<>();
                final java.util.ArrayList<String> ids = new java.util.ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject a = arr.optJSONObject(i);
                    if (a == null) continue;
                    if (!"primary".equals(a.optString("mode", ""))) continue;
                    String id = a.optString("id", "");
                    if (id.isEmpty()) continue;
                    if ("compaction".equals(id) || "summary".equals(id) || "title".equals(id)) continue;
                    if (ids.contains(id)) continue;
                    String label = a.optString("name", id);
                    names.add(label.isEmpty() ? id : label);
                    ids.add(id);
                }
                runOnUiThread(() -> {
                    agentNames.clear();
                    agentNames.addAll(names);
                    agentIDs.clear();
                    agentIDs.addAll(ids);
                    agentAdapter.notifyDataSetChanged();
                    agentsLoaded = true;
                    applySessionMeta();
                });
            } catch (Exception ignored) {
                // agent picker optional: sending without agent uses session default
                runOnUiThread(() -> {
                    agentsLoaded = true;
                    applySessionMeta();
                });
            }
        }).start();
    }

    // ---------- 模型 / Agent / 思考档位 初始值 ----------

    /**
     * 0.22.8：模型搜索对话框。放开 openrouter 后 Spinner 动辄上百项，单纯点选翻不动。
     * 一个输入框 + 过滤后的 ListView；选中即同步 Spinner（后续发送逻辑完全复用）。
     */
    private void showModelSearchDialog() {
        if (modelNames.isEmpty()) {
            Toast.makeText(this, "模型还没加载好，稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(4));

        EditText query = new EditText(this);
        query.setHint("搜索模型名 / 提供方");
        query.setSingleLine(true);
        query.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        box.addView(query);

        final java.util.ArrayList<Integer> hits = new java.util.ArrayList<>();
        final ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, new java.util.ArrayList<String>());
        android.widget.ListView list = new android.widget.ListView(this);
        list.setAdapter(ad);
        box.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360)));

        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("选择模型（共 " + modelNames.size() + " 个）")
                .setView(box)
                .setNegativeButton("取消", null)
                .create();
        fillModelSearch("", hits, ad);
        list.setOnItemClickListener(new ModelPickListener(this, hits, dlg));
        query.addTextChangedListener(new ModelSearchWatcher(this, hits, ad));
        dlg.show();
    }

    /** 搜索对话框的过滤逻辑：关键字为空显示全部；否则按标签（含 provider）不区分大小写包含匹配。 */
    private void fillModelSearch(String query,
                                 java.util.ArrayList<Integer> hits,
                                 ArrayAdapter<String> ad) {
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        hits.clear();
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        for (int i = 0; i < modelNames.size(); i++) {
            String name = modelNames.get(i);
            if (name == null) continue;
            if (q.isEmpty() || name.toLowerCase(java.util.Locale.ROOT).contains(q)) {
                hits.add(i);
                labels.add(name);
            }
        }
        ad.clear();
        ad.addAll(labels);
        ad.notifyDataSetChanged();
    }

    /** 搜索输入监听。必须 static 具名类（D8 8.2.2-dev 坑，见 DEVELOPER.md）。 */
    private static class ModelSearchWatcher implements android.text.TextWatcher {
        private final ChatActivity act;
        private final java.util.ArrayList<Integer> hits;
        private final ArrayAdapter<String> ad;
        ModelSearchWatcher(ChatActivity a, java.util.ArrayList<Integer> h, ArrayAdapter<String> d) {
            act = a;
            hits = h;
            ad = d;
        }
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
        @Override public void afterTextChanged(android.text.Editable s) {
            act.fillModelSearch(s == null ? "" : s.toString(), hits, ad);
        }
    }

    /** 搜索结果点击：把 Spinner 选中项挪到对应真实下标，再关掉对话框。 */
    private static class ModelPickListener
            implements android.widget.AdapterView.OnItemClickListener {
        private final ChatActivity act;
        private final java.util.ArrayList<Integer> hits;
        private final android.app.AlertDialog dlg;
        ModelPickListener(ChatActivity a, java.util.ArrayList<Integer> h,
                          android.app.AlertDialog d) {
            act = a;
            hits = h;
            dlg = d;
        }
        @Override public void onItemClick(android.widget.AdapterView<?> parent,
                                          View view, int position, long id) {
            if (position < 0 || position >= hits.size()) return;
            act.modelSpinner.setSelection(hits.get(position));
            dlg.dismiss();
        }
    }

    /** 模型下拉联动：换模型 → 重建思考档位。static 具名内部类
     *  （D8 8.2.2-dev 对匿名/非静态内部类会 NPE 崩溃，见 DEVELOPER.md；static 无 this$0 合成字段）。 */
    private static class ModelSelectListener implements android.widget.AdapterView.OnItemSelectedListener {
        private final ChatActivity act;
        ModelSelectListener(ChatActivity a) {
            act = a;
        }
        @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                                               android.view.View view, int position, long id) {
            if (act.suppressModelEvents) return;
            // 模型没变就不重建：Spinner 的选中回调可能异步补发，
            // 否则会把进页预选好的思考档位重置掉。
            if (act.currentVariantModelKey().equals(act.lastVariantModelKey)) return;
            act.rebuildVariants(false);
        }
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {
        }
    }

    private static String variantKey(String pid, String id) {
        return pid + "\u0000" + id;
    }

    /** 当前思考下拉选中的 variant id；空串 = 不发（用模型默认档位）。 */
    private String selectedVariantID() {
        if (variantSpinner == null) return "";
        int p = variantSpinner.getSelectedItemPosition();
        if (p < 0 || p >= variantIDs.size()) return "";
        return variantIDs.get(p);
    }

    private int findModelIndex(String pid, String id) {
        for (int i = 0; i < modelIDs.size() && i < modelProviderIDs.size(); i++) {
            if (pid.equals(modelProviderIDs.get(i)) && id.equals(modelIDs.get(i))) return i;
        }
        return -1;
    }

    /** 当前所选的 (providerID,id) 档位表键；未选/未知回空串。 */
    private String currentVariantModelKey() {
        int p = modelSpinner != null ? modelSpinner.getSelectedItemPosition() : -1;
        if (p >= 0 && p < modelProviderIDs.size() && p < modelIDs.size()) {
            String pid = modelProviderIDs.get(p);
            String mid = modelIDs.get(p);
            if (!pid.isEmpty() && !mid.isEmpty()) return variantKey(pid, mid);
        }
        return "";
    }

    /**
     * 思考档位跟所选模型联动（0.20.0）：按当前模型的 variants 重填下拉，首项恒为
     * "默认"（空 variant = 不发）。preselect=true 仅进页首次，尝试选中会话当前
     * variant；平时换模型回落"默认"。模型无档位时整条禁用并提示。
     */
    private void rebuildVariants(boolean preselect) {
        if (variantSpinner == null) return;
        String key = currentVariantModelKey();
        lastVariantModelKey = key;
        java.util.ArrayList<String> vars = key.isEmpty() ? null : modelVariants.get(key);
        boolean has = vars != null && !vars.isEmpty();
        variantNames.clear();
        variantIDs.clear();
        variantNames.add(has ? "默认" : "默认（无档位）");
        variantIDs.add("");
        int select = 0;
        if (has) {
            int i = 1;
            for (String v : vars) {
                if (v == null || v.isEmpty()) continue;
                variantNames.add(v);
                variantIDs.add(v);
                if (preselect && v.equals(sessionVariant)) select = i;
                i++;
            }
        }
        variantAdapter.notifyDataSetChanged();
        variantSpinner.setSelection(select);
        variantSpinner.setEnabled(has);
    }

    /**
     * 下拉初始值 = 会话真实状态（0.20.0）：等 模型表/Agent表/会话元数据 都到齐后只应用一次。
     * 会话模型或 Agent 未知时插入"（未设置）"占位（空 id = 发送时不切），
     * 不再用"默认（跟随会话）"；知道就选中真实那个。
     */
    private void applySessionMeta() {
        if (metaApplied) return;
        if (!modelsLoaded || !agentsLoaded || !sessionMetaFetched) return;
        metaApplied = true;

        // 模型：未知 → 占位；已知但不在可显示列表 → 插到最前显示真实身份。
        suppressModelEvents = true;
        if (sessionModelID == null || sessionModelID.isEmpty()) {
            modelNames.add(0, "（未设置）");
            modelProviderIDs.add(0, "");
            modelIDs.add(0, "");
            modelAdapter.notifyDataSetChanged();
            modelSpinner.setSelection(0);
        } else {
            String pid = sessionProviderID == null ? "" : sessionProviderID;
            int idx = findModelIndex(pid, sessionModelID);
            if (idx < 0) {
                String label = (pid.isEmpty() ? "" : pid + "/") + sessionModelID;
                modelNames.add(0, label);
                modelProviderIDs.add(0, pid);
                modelIDs.add(0, sessionModelID);
                modelAdapter.notifyDataSetChanged();
                idx = 0;
            }
            modelSpinner.setSelection(idx);
        }
        suppressModelEvents = false;
        rebuildVariants(true);

        // Agent：未知 → 占位；已知 → 选中。
        int a;
        if (sessionAgent == null || sessionAgent.isEmpty()) {
            agentNames.add(0, "（未设置）");
            agentIDs.add(0, "");
            a = 0;
        } else {
            a = agentIDs.indexOf(sessionAgent);
            if (a < 0) {
                agentNames.add(0, sessionAgent);
                agentIDs.add(0, sessionAgent);
                a = 0;
            }
        }
        agentAdapter.notifyDataSetChanged();
        agentSpinner.setSelection(a);
    }

    /** 进页读会话真实状态：agent + model{providerID,id,variant}（0.20.0）。 */
    private void loadSessionMeta() {
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/api/session/" + sessionID);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                MainActivity.auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setRequestMethod("GET");
                if (c.getResponseCode() == 200) {
                    JSONObject s = new JSONObject(readAll(c.getInputStream())).optJSONObject("data");
                    if (s != null) {
                        sessionAgent = s.optString("agent", null);
                        JSONObject m = s.optJSONObject("model");
                        if (m != null) {
                            sessionProviderID = m.optString("providerID", null);
                            sessionModelID = m.optString("id", null);
                            sessionVariant = m.optString("variant", null);
                        }
                    }
                }
            } catch (Exception ignored) {
                // 会话状态可选：读不到就按"未设置"显示
            } finally {
                sessionMetaFetched = true;
            }
            runOnUiThread(this::applySessionMeta);
        }).start();
    }

    // ---------- copy & permission ----------

    private void makeCopyable(TextView tv) {
        tv.setOnLongClickListener(v -> {
            lastLongPressAt = System.currentTimeMillis();
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "opencode", tv.getText().toString()));
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "复制失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            return true;
        });
    }

    private void showPermRequest(String rid, String permission, String patterns) {
        pendingPermID = rid;
        runOnUiThread(() -> {
            permText.setText("AI 请求权限：" + permission + "\n范围：" + patterns
                    + "\n选“总是允许”可免去以后重复确认");
            permBox.setVisibility(LinearLayout.VISIBLE);
            refreshStatus(); // perm wins: hides run banner, title shows 等你确认权限
            scrollDown();
        });
    }

    private void replyPerm(String reply) {
        final String rid = pendingPermID;
        if (rid == null) return;
        pendingPermID = null;
        permBox.setVisibility(LinearLayout.GONE);
        refreshStatus(); // restore run banner if AI still working
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/api/session/" + sessionID
                        + "/permission/" + rid + "/reply");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                MainActivity.auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                JSONObject body = new JSONObject();
                body.put("decision", reply);
                byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
                OutputStream os = c.getOutputStream();
                os.write(out);
                os.flush();
                os.close();
                int rc = c.getResponseCode();
                if (rc != 200 && rc != 204) throw new Exception("HTTP " + rc);
            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : "";
                runOnUiThread(() -> {
                    if (msg.contains("404")) {
                        // 单子已不在（过期/已处理/会话已删）：条子已收，不算失败
                        Toast.makeText(this, "这条审批已经没了，继续聊就行", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "审批发送失败：" + msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void checkPendingPerm() {
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/api/permission/request?sessionID=" + sessionID);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                MainActivity.auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setRequestMethod("GET");
                if (c.getResponseCode() != 200) return;
                // V2: {data:[{id,sessionID,action,resources[],message?}]}
                JSONArray arr = new JSONObject(readAll(c.getInputStream())).optJSONArray("data");
                if (arr == null || arr.length() == 0) return;
                // 列表端点是 location 维度，只展示本会话的单：别人的单弹出来点了必 404
                JSONObject r = null;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    if (sessionID.equals(o.optString("sessionID", ""))) { r = o; break; }
                }
                if (r == null) {
                    // 本会话无等待中的审批：顺手收掉旧条子（已过期/已在别处处理），免得点了报 404
                    if (pendingPermID != null) {
                        pendingPermID = null;
                        runOnUiThread(() -> {
                            permBox.setVisibility(LinearLayout.GONE);
                            refreshStatus();
                        });
                    }
                    return;
                }
                String rid = r.optString("id", "");
                if (rid.isEmpty() || rid.equals(pendingPermID)) return; // 已展示的不重复弹
                String action = r.optString("action", "?");
                JSONArray res = r.optJSONArray("resources");
                StringBuilder sb = new StringBuilder();
                if (res != null) {
                    for (int i = 0; i < res.length(); i++) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(res.optString(i, ""));
                    }
                }
                String msg = r.optString("message", "");
                if (!msg.isEmpty() && !"null".equals(msg)) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(msg);
                }
                if (!rid.isEmpty()) showPermRequest(rid, action, sb.toString());
            } catch (Exception ignored) {
            }
        }).start();
    }

    // ---------- question (AI asks user, NOT permission) ----------

    /** Collapse = hide option list, keep title + submit/skip visible (banner ~110dp). */
    private void setQCollapsed(boolean collapsed) {
        qCollapsed = collapsed;
        runOnUiThread(() -> {
            if (qScroll != null) qScroll.setVisibility(collapsed ? ScrollView.GONE : ScrollView.VISIBLE);
            if (qToggle != null) qToggle.setText(collapsed ? "展开" : "收起");
        });
    }

    private void showQuestionRequest(JSONObject req) {
        showQuestionRequest(req, true);
    }

    private void showQuestionRequest(JSONObject req, boolean expanded) {
        String rid = req.optString("id", "");
        JSONArray qs = req.optJSONArray("questions");
        if (rid.isEmpty() || qs == null || qs.length() == 0) return;
        pendingQID = rid;
        pendingQuestions.clear();
        qSelected.clear();
        qCustomInputs.clear();
        qOptionBtns.clear();
        qList.removeAllViews();
        for (int i = 0; i < qs.length(); i++) {
            JSONObject q = qs.optJSONObject(i);
            if (q == null) continue;
            pendingQuestions.add(q);
            qSelected.add(new java.util.LinkedHashSet<String>());
            qOptionBtns.add(new java.util.ArrayList<Button>());
        }
        final int n = pendingQuestions.size();
        qTitle.setText("AI 向你提问（" + n + "题），选完点提交");
        makeCopyable(qTitle);
        for (int qi = 0; qi < n; qi++) {
            final int qIdx = qi;
            JSONObject q = pendingQuestions.get(qIdx);
            String header = q.optString("header", "");
            String prompt = q.optString("question", "");
            boolean multiple = q.optBoolean("multiple", false);
            boolean custom = q.optBoolean("custom", true);
            JSONArray opts = q.optJSONArray("options");

            LinearLayout qc = new LinearLayout(this);
            qc.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams qcLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            qcLp.topMargin = dp(8);
            qc.setLayoutParams(qcLp);
            qc.setBackgroundColor(Palette.surface());
            qc.setPadding(dp(8), dp(8), dp(8), dp(8));
            qList.addView(qc);

            TextView hTv = new TextView(this);
            hTv.setText("第" + (qIdx + 1) + "题 · " + header + (multiple ? "（可多选）" : "（单选）"));
            hTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            hTv.setTextColor(Palette.textSecondary());
            qc.addView(hTv);

            TextView pTv = new TextView(this);
            pTv.setText(prompt);
            pTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            pTv.setTextColor(Palette.textPrimary());
            qc.addView(pTv);
            makeCopyable(pTv);

            if (opts != null) {
                for (int oi = 0; oi < opts.length(); oi++) {
                    final JSONObject o = opts.optJSONObject(oi);
                    if (o == null) continue;
                    final String label = o.optString("label", "");
                    String desc = o.optString("description", "");
                    Button ob = new Button(this);
                    ob.setText(desc.isEmpty() ? label : label + " — " + desc);
                    ob.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    ob.setMinHeight(dp(48));
                    ob.setTag(label);
                    LinearLayout.LayoutParams obLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    obLp.topMargin = dp(4);
                    qc.addView(ob, obLp);
                    qOptionBtns.get(qIdx).add(ob);
                    ob.setOnClickListener(v -> {
                        java.util.LinkedHashSet<String> sel = qSelected.get(qIdx);
                        boolean isMulti = pendingQuestions.get(qIdx).optBoolean("multiple", false);
                        if (isMulti) {
                            if (sel.contains(label)) sel.remove(label);
                            else sel.add(label);
                        } else {
                            // single-select toggles: tap selected again to clear,
                            // so custom-only answers are possible (validation still
                            // requires option or custom text per question).
                            if (sel.contains(label)) sel.clear();
                            else {
                                sel.clear();
                                sel.add(label);
                            }
                        }
                        refreshQOptionStyles(qIdx);
                    });
                }
            }
            refreshQOptionStyles(qIdx);

            if (custom) {
                EditText ce = new EditText(this);
                ce.setHint("或手动输入答案…");
                ce.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                ce.setSingleLine(true);
                LinearLayout.LayoutParams ceLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                ceLp.topMargin = dp(4);
                qc.addView(ce, ceLp);
                qCustomInputs.add(ce);
            } else {
                EditText ce = new EditText(this);
                ce.setVisibility(EditText.GONE);
                qc.addView(ce);
                qCustomInputs.add(ce);
            }
        }
        qBox.setVisibility(LinearLayout.VISIBLE);
        setQCollapsed(!expanded);
        if (expanded && qScroll != null) qScroll.post(() -> qScroll.scrollTo(0, 0));
        refreshStatus();
        scrollDown();
    }

    private void refreshQOptionStyles(int qIdx) {
        java.util.ArrayList<Button> btns = qOptionBtns.get(qIdx);
        java.util.LinkedHashSet<String> sel = qSelected.get(qIdx);
        for (Button b : btns) {
            Object tag = b.getTag();
            String label = tag != null ? tag.toString() : "";
            if (sel.contains(label)) {
                b.setBackgroundColor(Palette.accent());
                b.setTextColor(Palette.onAccent());
            } else {
                b.setBackgroundColor(Palette.surface());
                b.setTextColor(Palette.textPrimary());
            }
        }
    }

    private void hideQuestionBox() {
        pendingQID = null;
        pendingQuestions.clear();
        qSelected.clear();
        qCustomInputs.clear();
        qOptionBtns.clear();
        qCollapsed = false;
        if (qToggle != null) qToggle.setText("收起");
        if (qScroll != null) qScroll.setVisibility(ScrollView.VISIBLE);
        if (qList != null) qList.removeAllViews();
        if (qBox != null) qBox.setVisibility(LinearLayout.GONE);
        refreshStatus();
    }

    /** Entry call: stale history forms show collapsed (small banner, never covers screen). */
    private void checkPendingQuestions() {
        checkPendingQuestions(false);
    }

    private void checkPendingQuestions(boolean expanded) {
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/api/form?sessionID=" + sessionID);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                MainActivity.auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setRequestMethod("GET");
                if (c.getResponseCode() != 200) return;
                // V2: {data:[{id,title,fields[{key,title,description,type,
                // options[{value,label,description}],custom,required}]}]}
                JSONArray arr = new JSONObject(readAll(c.getInputStream())).optJSONArray("data");
                if (arr == null || arr.length() == 0) return;
                // 同权限：location 维度列表，按 sessionID 过滤；已展示的不重建（避免清空用户已选选项）
                JSONObject form = null;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    if (sessionID.equals(o.optString("sessionID", ""))) { form = o; break; }
                    if (form == null) form = o;
                }
                if (form == null) return;
                if (form.optString("id", "").equals(pendingQID)) return;
                JSONObject req = adaptForm(form);
                if (req == null) return;
                final JSONObject freq = req;
                final boolean fexp = expanded;
                runOnUiThread(() -> showQuestionRequest(freq, fexp));
            } catch (Exception ignored) {
            }
        }).start();
    }

    /** V2 form -> v1-style {id, questions[]} so the existing banner UI is reused. */
    private JSONObject adaptForm(JSONObject form) {
        try {
            String fid = form.optString("id", "");
            JSONArray fields = form.optJSONArray("fields");
            if (fid.isEmpty() || fields == null || fields.length() == 0) return null;
            JSONObject req = new JSONObject();
            req.put("id", fid);
            JSONArray qs = new JSONArray();
            for (int i = 0; i < fields.length(); i++) {
                JSONObject f = fields.optJSONObject(i);
                if (f == null) continue;
                if (f.optBoolean("hidden", false)) continue;
                String key = f.optString("key", "");
                if (key.isEmpty()) continue;
                String ftype = f.optString("type", "string");
                JSONObject q = new JSONObject();
                q.put("key", key);
                q.put("ftype", ftype);
                q.put("required", f.optBoolean("required", true));
                String title = f.optString("title", "");
                q.put("header", title.isEmpty() ? key : title);
                q.put("question", f.optString("description", ""));
                boolean multi = "multiselect".equals(ftype);
                q.put("multiple", multi);
                JSONArray fopts = f.optJSONArray("options");
                JSONArray opts = new JSONArray();
                if (fopts != null) {
                    for (int j = 0; j < fopts.length(); j++) {
                        JSONObject o = fopts.optJSONObject(j);
                        if (o == null) continue;
                        String val = o.optString("value", "");
                        String lab = o.optString("label", val);
                        if (val.isEmpty()) continue;
                        JSONObject no = new JSONObject();
                        no.put("label", lab.isEmpty() ? val : lab);
                        no.put("value", val);
                        no.put("description", o.optString("description", ""));
                        opts.put(no);
                    }
                }
                if ("boolean".equals(ftype) && opts.length() == 0) {
                    // yes/no as options so the banner buttons can answer it
                    JSONObject y = new JSONObject();
                    y.put("label", "是");
                    y.put("value", "true");
                    opts.put(y);
                    JSONObject n = new JSONObject();
                    n.put("label", "否");
                    n.put("value", "false");
                    opts.put(n);
                }
                q.put("options", opts);
                boolean custom;
                if (f.has("custom")) custom = f.optBoolean("custom", false);
                else custom = opts.length() == 0 || multi;
                q.put("custom", custom);
                qs.put(q);
            }
            if (qs.length() == 0) return null;
            req.put("questions", qs);
            String title = form.optString("title", "");
            if (!title.isEmpty()) req.put("title", title);
            return req;
        } catch (Exception e) {
            return null;
        }
    }

    /** label -> value lookup inside an adapted question's options. */
    private static String optionValue(JSONObject q, String label) {
        JSONArray opts = q.optJSONArray("options");
        if (opts != null) {
            for (int i = 0; i < opts.length(); i++) {
                JSONObject o = opts.optJSONObject(i);
                if (o != null && label.equals(o.optString("label", ""))) {
                    String v = o.optString("value", "");
                    if (!v.isEmpty()) return v;
                }
            }
        }
        return label;
    }

    private void replyQuestion() {
        final String fid = pendingQID;
        if (fid == null) return;
        final int n = pendingQuestions.size();
        JSONObject answer = new JSONObject();
        try {
            for (int i = 0; i < n; i++) {
                JSONObject q = pendingQuestions.get(i);
                String key = q.optString("key", "");
                if (key.isEmpty()) continue;
                String ftype = q.optString("ftype", "string");
                boolean multi = q.optBoolean("multiple", false)
                        || "multiselect".equals(ftype);
                boolean required = q.optBoolean("required", true);
                java.util.ArrayList<String> one = new java.util.ArrayList<>();
                for (String lab : qSelected.get(i)) one.add(optionValue(q, lab));
                String custom = qCustomInputs.get(i).getText().toString().trim();
                if (!custom.isEmpty()) {
                    if ("boolean".equals(ftype)) {
                        // custom text on boolean: truthy words -> true
                        one.add(("true".equalsIgnoreCase(custom) || "是".equals(custom)
                                || "yes".equalsIgnoreCase(custom) || "1".equals(custom))
                                ? "true" : custom);
                    } else if (multi || one.isEmpty()) {
                        if (!one.contains(custom)) one.add(custom);
                    } else {
                        one.set(0, custom);
                    }
                }
                if (one.isEmpty()) {
                    if (!required) continue;
                    final int miss = i + 1;
                    runOnUiThread(() ->
                            Toast.makeText(this, "请先回答第" + miss + "题", Toast.LENGTH_SHORT).show());
                    return;
                }
                if ("boolean".equals(ftype)) {
                    answer.put(key, "true".equals(one.get(0)));
                } else if (multi) {
                    JSONArray ja = new JSONArray();
                    for (String s : one) ja.put(s);
                    answer.put(key, ja);
                } else {
                    answer.put(key, one.get(0));
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "答案组装失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }
        final JSONObject payload = answer;
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/api/session/" + sessionID
                        + "/form/" + fid + "/reply");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                MainActivity.auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                JSONObject body = new JSONObject();
                body.put("answer", payload);
                byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
                OutputStream os = c.getOutputStream();
                os.write(out);
                os.flush();
                os.close();
                int rc = c.getResponseCode();
                if (rc != 200 && rc != 204) throw new Exception("HTTP " + rc);
                runOnUiThread(() -> {
                    hideQuestionBox();
                    Toast.makeText(this, "已提交答案", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "提交失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void rejectQuestion() {
        final String fid = pendingQID;
        if (fid == null) return;
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/api/session/" + sessionID
                        + "/form/" + fid);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                MainActivity.auth(c);
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setRequestMethod("DELETE");
                c.getResponseCode();
                runOnUiThread(() -> {
                    hideQuestionBox();
                    Toast.makeText(this, "已跳过提问", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "跳过失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ---------- SSE ----------

    private void startSse() {
        sseRunning = true;
        new Thread(() -> {
            while (sseRunning) {
                HttpURLConnection c = null;
                try {
                    URL url = new URL(BASE_URL + "/api/event");
                    c = (HttpURLConnection) url.openConnection();
                    MainActivity.auth(c);
                    c.setConnectTimeout(8000);
                    // 0.21.15：别再用无限读超时。服务端每 15 秒发一条 ": heartbeat"，
                    // 死连接（切网/休眠/被运营商掐）以前会永远卡在 readLine 上，
                    // 断开横幅都不出、也不重连；60 秒没一条就当断线。
                    c.setReadTimeout(60000);
                    c.setRequestMethod("GET");
                    c.setRequestProperty("Accept", "text/event-stream");
                    c.connect();
                    boolean wasDown = sseWasDown;
                    sseOk = true;
                    sseWasDown = false;
                    runOnUiThread(() -> refreshStatus());
                    if (wasDown) {
                        pollSessionStatus(true); // 断线补漏：活动会话重拉一遍
                    }
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    lastSseLineAt = System.currentTimeMillis();
                    while (sseRunning && (line = r.readLine()) != null) {
                        lastSseLineAt = System.currentTimeMillis(); // 心跳注释也算活着的证据
                        line = line.trim();
                        if (!line.startsWith("data:")) continue;
                        handleEvent(line.substring(5).trim());
                    }
                    r.close();
                } catch (Exception e) {
                    // reconnect after pause; flag stale so banner admits it may be outdated
                    sseOk = false;
                    sseWasDown = true;
                    runOnUiThread(() -> {
                        refreshStatus();
                        if (!busy && !doneShowing && currentToolText() == null && retryText == null)
                            statusView.setText("事件断开，3秒后重连…");
                    });
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }).start();
    }

    private void handleEvent(String json) {
        try {
            JSONObject e = new JSONObject(json);
            String type = e.optString("type", "");
            // V2 envelope: {id, type, data:{sessionID,…}} (V1 used properties)
            JSONObject d = e.optJSONObject("data");
            if (d == null) return;
            String sid = d.optString("sessionID", "");
            if (!sessionID.equals(sid)) {
                // session-scoped fallback: permission/form lists are queried
                // per-session; global config events (*.updated) carry no sid.
                if (type.contains("permission")) runOnUiThread(() -> checkPendingPerm());
                else if (type.contains("form") || type.contains("question")) {
                    runOnUiThread(() -> checkPendingQuestions(true));
                }
                return;
            }
            lastEventAt = System.currentTimeMillis();

            switch (type) {
                case "session.execution.started":
                case "session.step.started": {
                    final String amid = d.optString("assistantMessageID", "");
                    runOnUiThread(() -> {
                        MainActivity.markAwaiting(this, sessionID, baseTitle); // 服务端确认开跑，补记
                        if (!amid.isEmpty() && !msgBubbles.containsKey(amid)) {
                            ensureBubble(amid);
                        }
                        if (!busy) setBusy(true);
                        else refreshStatus();
                    });
                    break;
                }
                case "session.text.delta": {
                    String amid = d.optString("assistantMessageID", "");
                    String delta = d.optString("delta", "");
                    if (!amid.isEmpty() && !delta.isEmpty()) {
                        final String fmid = amid;
                        final String fdelta = delta;
                        runOnUiThread(() -> {
                            appendDelta(fmid, fmid, fdelta);
                            onTextDelta();
                        });
                    }
                    break;
                }
                case "session.reasoning.started":
                case "session.reasoning.delta": {
                    // 0.21.0：reasoning 内容以前直接丢弃，现实时显示到本消息的思考块
                    final String rmid = d.optString("assistantMessageID", "");
                    final String rdelta = d.optString("delta", "");
                    runOnUiThread(() -> {
                        thinkingShown = true;
                        writingShown = false;
                        appendReasoning(rmid, rdelta);
                        refreshStatus();
                    });
                    break;
                }
                case "session.reasoning.ended":
                    runOnUiThread(() -> {
                        flushNow();
                        thinkingShown = false;
                        finishReasoning();
                        refreshStatus();
                    });
                    break;
                case "session.text.ended":
                    runOnUiThread(this::flushNow);
                    break;
                case "session.tool.input.started":
                case "session.tool.called":
                case "session.tool.progress": {
                    // 0.21.9：V2 工具事件（实测 2.0.12）——input.started 带 name，
                    // called 带 input，progress 只有 metadata。之前靠 3 秒轮询整包消息，
                    // 现在直接事件更新，状态栏照样显示 "⚙️ 正在执行命令 · date"。
                    final String tid = d.optString("id", "");
                    if (tid.isEmpty()) break;
                    final String tname = d.optString("name", "");
                    final JSONObject tinput = d.optJSONObject("input");
                    runOnUiThread(() -> {
                        String nm = tname;
                        if (!nm.isEmpty()) liveToolNames.put(tid, nm);
                        else nm = liveToolNames.containsKey(tid) ? liveToolNames.get(tid) : "";
                        // progress 事件没有 input：沿用 called 记下的细节，别把「· date」擦掉
                        String det = tinput != null ? detailFromInput(nm, tinput) : "";
                        if (!det.isEmpty()) liveToolDetails.put(tid, det);
                        else if (liveToolDetails.containsKey(tid)) det = liveToolDetails.get(tid);
                        cancelDone();
                        clearToolLinger();
                        writingShown = false;
                        thinkingShown = false;
                        runningTools.put(tid, friendlyToolText(nm, "", det));
                        refreshStatus();
                    });
                    break;
                }
                case "session.tool.success":
                case "session.tool.failed":
                case "session.tool.error": {
                    final String tid = d.optString("id", "");
                    if (tid.isEmpty()) break;
                    final boolean failed = !"session.tool.success".equals(type);
                    runOnUiThread(() -> {
                        runningTools.remove(tid);
                        String det = liveToolDetails.get(tid);
                        String done = (failed ? "❌ 工具失败" : "✅ 已完成")
                                + (det == null || det.isEmpty() ? "" : " · " + det);
                        showToolDone(done);
                    });
                    break;
                }
                case "session.execution.succeeded": {
                    runOnUiThread(() -> {
                        flushNow(); // 最后一截字别丢
                        onServerIdle();
                    });
                    break;
                }
                case "session.execution.failed": {
                    JSONObject err = d.optJSONObject("error");
                    String em = err != null ? err.optString("message", "") : "";
                    if (em.length() > 160) em = em.substring(0, 160) + "…";
                    String et = err != null ? err.optString("type", "") : "";
                    String toastExtra = "";
                    String elow = et.toLowerCase();
                    if (elow.contains("quota")) toastExtra = "（免费额度用完，非断线；换模型或等重置）";
                    else if (elow.contains("invalid-output")) toastExtra = "（免费模型偶发，点顶栏「↻ 重试」）";
                    final String ferr = em;
                    final String fextra = toastExtra;
                    runOnUiThread(() -> {
                        suppressDone = true;
                        cancelDone();
                        retryText = null;
                        flushNow();
                        runningTools.clear();
                        liveToolNames.clear();
                        liveToolDetails.clear();
                        clearToolLinger();
                        writingShown = false;
                        stoppedAwait = false;
                        if (stoppedManually) {
                            stoppedManually = false;
                            suppressDone = false;
                            MainActivity.clearAwaiting(this, sessionID); // 自己按的停止，不提醒
                            Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show();
                        } else {
                            if (uiVisible) {
                                MainActivity.clearAwaiting(this, sessionID);
                            } else {
                                MainActivity.clearAwaiting(this, sessionID);
                                MainActivity.markJustDone(this, sessionID);
                                MainActivity.notifyDone(this, sessionID, baseTitle);
                            }
                            // 跑挂了才给重试：免费模型偶发 invalid-output，不用重新打字
                            if (retryBtn != null && !lastSentText.isEmpty()) {
                                retryBtn.setVisibility(Button.VISIBLE);
                            }
                            Toast.makeText(this,
                                    ferr.isEmpty()
                                            ? "服务器出错，请下拉看记录" + fextra
                                            : "失败：" + ferr + fextra,
                                    Toast.LENGTH_LONG).show();
                        }
                        setBusy(false);
                        loadHistory();
                    });
                    break;
                }
                case "permission.asked": {
                    // kept for tolerance; V2 ask events surface via fallback below
                    String rid = d.optString("id", "");
                    if (rid.isEmpty()) rid = d.optString("requestID", "");
                    if (!rid.isEmpty()) checkPendingPerm();
                    break;
                }
                case "permission.replied": {
                    pendingPermID = null;
                    runOnUiThread(() -> {
                        permBox.setVisibility(LinearLayout.GONE);
                        refreshStatus();
                    });
                    break;
                }
                default: {
                    // V2 permission/form ask event names are not in the frozen
                    // schema: catch them by substring and re-query the lists.
                    if (type.contains("permission")) {
                        runOnUiThread(() -> checkPendingPerm());
                    } else if (type.contains("form") || type.contains("question")) {
                        runOnUiThread(() -> checkPendingQuestions(true));
                    }
                    break;
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Create an empty AI bubble up-front so streaming deltas have a home. */
    private void ensureBubble(String mid) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(10), dp(12), dp(10));
        bubble.setBackgroundColor(Palette.surface());
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bLp.topMargin = dp(8);
        bubble.setLayoutParams(bLp);
        TextView roleTv = new TextView(this);
        roleTv.setText("AI");
        roleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        roleTv.setTextColor(Palette.textMuted());
        bubble.addView(roleTv);
        TextView ph = new TextView(this);
        ph.setTag("streamingPlaceholder");
        ph.setText("… 正在思考或准备中，详情见上方状态栏 …");
        ph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        ph.setTextColor(Palette.textMuted());
        bubble.addView(ph);
        msgBox.addView(bubble);
        msgBubbles.put(mid, bubble);
        scrollDown();
    }

    // ---------- 流式节流（0.21.13） ----------
    // 以前每个 text.delta / reasoning.delta 都 applyRichText 全文重建 Span + setText，
    // 一条长回复是 O(n²)（几千个增量 × 上万字），是发热掉帧的主因。
    // 现在增量立刻进 buffer，UI 每 100ms 合并刷一次；结束/完成事件强制 flush 兜底。
    private final java.util.LinkedHashSet<String> dirtyParts = new java.util.LinkedHashSet<>();
    private final java.util.LinkedHashSet<String> dirtyReasons = new java.util.LinkedHashSet<>();
    private Runnable flushTask = null;
    private static final long STREAM_FLUSH_MS = 100;
    // 0.21.14：长按复制会同时触发链接点击（View 先处理 long-click，MovementMethod 再处理 UP），
    // 记下长按时刻，链接点击在这个窗口内直接忽略。
    private static volatile long lastLongPressAt = 0;

    private void scheduleFlush() {
        if (flushTask != null) return;
        flushTask = () -> {
            flushTask = null;
            flushStreamingNow();
        };
        uiHandler.postDelayed(flushTask, STREAM_FLUSH_MS);
    }

    /** 把 buffer 里攒下的增量一次性写进视图（UI 线程）。 */
    private void flushStreamingNow() {
        boolean any = false;
        if (!dirtyParts.isEmpty()) {
            for (String pid : new java.util.ArrayList<>(dirtyParts)) {
                TextView tv = partViews.get(pid);
                StringBuilder sb = partBuf.get(pid);
                if (tv != null && sb != null) {
                    applyRichText(tv, sb.toString());
                    any = true;
                }
            }
            dirtyParts.clear();
        }
        if (!dirtyReasons.isEmpty()) {
            for (String mid : new java.util.ArrayList<>(dirtyReasons)) {
                ReasonBlock rb = reasonBlocks.get(mid);
                if (rb != null && rb.content != null) {
                    rb.content.setText(rb.buf.toString());
                    any = true;
                }
            }
            dirtyReasons.clear();
        }
        if (any) scrollDown();
    }

    /** 立刻刷（完成/结束/异常时用，别让最后一截字卡在 buffer 里）。 */
    private void flushNow() {
        if (flushTask != null) {
            uiHandler.removeCallbacks(flushTask);
            flushTask = null;
        }
        flushStreamingNow();
    }

    /** 丢弃待刷内容（重拉历史/销毁时用）。 */
    private void dropPendingFlush() {
        if (flushTask != null) {
            uiHandler.removeCallbacks(flushTask);
            flushTask = null;
        }
        dirtyParts.clear();
        dirtyReasons.clear();
    }

    private void appendDelta(String mid, String partID, String delta) {
        if (delta.isEmpty() || partID.isEmpty()) return;
        boolean created = false;
        TextView tv = partViews.get(partID);
        if (tv == null) {
            created = true;
            LinearLayout bubble = msgBubbles.get(mid);
            if (bubble == null) {
                // new assistant bubble for streaming reply
                bubble = new LinearLayout(this);
                bubble.setOrientation(LinearLayout.VERTICAL);
                bubble.setPadding(dp(12), dp(10), dp(12), dp(10));
                bubble.setBackgroundColor(Palette.surface());
                LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                bLp.topMargin = dp(8);
                bubble.setLayoutParams(bLp);
                TextView roleTv = new TextView(this);
                roleTv.setText("AI");
                roleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                roleTv.setTextColor(Palette.textMuted());
                bubble.addView(roleTv);
                msgBox.addView(bubble);
                if (!mid.isEmpty()) msgBubbles.put(mid, bubble);
            }
            tv = new TextView(this);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tv.setTextColor(Palette.textPrimary());
            makeCopyable(tv);
            bubble.addView(tv);
            for (int ci = 0; ci < bubble.getChildCount(); ci++) {
                View ch = bubble.getChildAt(ci);
                if ("streamingPlaceholder".equals(ch.getTag())) { bubble.removeView(ch); break; }
            }
            partViews.put(partID, tv);
            partBuf.put(partID, new StringBuilder());
        }
        StringBuilder sb = partBuf.get(partID);
        if (sb == null) {
            sb = new StringBuilder(tv.getText().toString());
            partBuf.put(partID, sb);
        }
        sb.append(delta);
        dirtyParts.add(partID);
        if (created) flushNow(); // 新气泡首字立即画，避免占位符移除后空白一瞬
        else scheduleFlush();
    }

    private void setPartText(String mid, String partID, String full, boolean isText) {
        if (partID.isEmpty()) return;
        TextView tv = partViews.get(partID);
        if (tv == null) {
            LinearLayout bubble = msgBubbles.get(mid);
            if (bubble == null) return;
            tv = new TextView(this);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, isText ? 16 : 14);
            tv.setTextColor(isText ? Palette.textPrimary() : Palette.textSecondary());
            makeCopyable(tv);
            bubble.addView(tv);
            partViews.put(partID, tv);
        }
        tv.setText(full);
        partBuf.put(partID, new StringBuilder(full));
        dirtyParts.remove(partID);
        scrollDown();
    }

    // ---------- 富文本渲染（0.21.0）：代码块 / 字符框 / markdown 表格 等宽对齐 ----------
    // 之前正文一律纯 TextView 比例字体，AI 用字符画的框（┌─┬─┐）和 markdown 表格
    // （| a | b |）列全对不齐。这里按行扫描，把 ``` 代码块、含框线字符的连续行、
    // 表格行套上等宽字体 + 浅底色。单视图方案：流式每次重建 Span，长按仍复制纯文本。

    /** 是否含制表/框线类字符（U+2500–U+257F：─│┌┐└┘├┤┬┴┼═║╔╗╚╝╭╮╯╰ 等）。 */
    private static boolean containsBoxChar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x2500 && c <= 0x257F) return true;
        }
        return false;
    }

    /** 便宜预判：全文像不像有结构化内容，没有就直接纯文本、省一次扫描。 */
    private static boolean looksStructured(String s) {
        return s.indexOf('`') >= 0 || s.indexOf('|') >= 0 || containsBoxChar(s);
    }

    /** 单行是否算"等宽行"：markdown 表格行 / 含框线字符 / ASCII 框（+---+、|...|）。 */
    private static boolean isMonoLine(String line) {
        String t = line.trim();
        if (t.isEmpty()) return false;
        if (t.startsWith("|")) return true;
        if (containsBoxChar(t)) return true;
        if (t.length() >= 3 && (t.charAt(0) == '+' || t.charAt(0) == '|')) {
            boolean allSpec = true;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c != '+' && c != '-' && c != '=' && c != '|' && c != ' ') {
                    allSpec = false;
                    break;
                }
            }
            if (allSpec) return true;
        }
        return false;
    }

    private static void applyMonoSpan(android.text.Spannable sp, int start, int end) {
        if (start < 0 || end <= start || end > sp.length()) return;
        sp.setSpan(new android.text.style.TypefaceSpan("monospace"), start, end,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new android.text.style.BackgroundColorSpan(Palette.codeBg()), start, end,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void applyRichText(TextView tv, String text) {
        if (text == null || text.isEmpty()) {
            tv.setText("");
            return;
        }
        if (!looksStructured(text)) {
            setLinkifiedText(tv, text);
            return;
        }
        android.text.SpannableString sp = new android.text.SpannableString(text);
        final int n = text.length();
        int regionStart = -1;   // 当前等宽区起点（-1 = 无）
        boolean inFence = false;
        int i = 0;
        while (i <= n) {
            int nl = text.indexOf('\n', i);
            int lineEnd = nl < 0 ? n : nl;
            String t = text.substring(i, lineEnd).trim();
            if (t.startsWith("```")) {
                if (!inFence) {
                    inFence = true;
                    if (regionStart < 0) regionStart = i;
                } else {
                    inFence = false;
                    applyMonoSpan(sp, regionStart < 0 ? i : regionStart, lineEnd);
                    regionStart = -1;
                }
            } else if (inFence) {
                // 代码块内部：整块连续上等宽，不做单行判断
            } else if (isMonoLine(text.substring(i, lineEnd))) {
                if (regionStart < 0) regionStart = i;
            } else if (regionStart >= 0) {
                applyMonoSpan(sp, regionStart, i);
                regionStart = -1;
            }
            if (nl < 0) break;
            i = nl + 1;
        }
        if (regionStart >= 0) applyMonoSpan(sp, regionStart, n); // 流式中未闭合的代码块也上等宽
        setLinkifiedText(tv, sp);
    }

    /** 0.21.14：正文里的网址变蓝色可点，点开 App 内浏览器（长按复制不受影响）。 */
    private void setLinkifiedText(TextView tv, CharSequence text) {
        try {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(text);
            if (android.text.util.Linkify.addLinks(sb, android.text.util.Linkify.WEB_URLS)) {
                // 默认 URLSpan 会丢到系统浏览器；换成自己的 span，走 App 内浏览器
                android.text.style.URLSpan[] spans = sb.getSpans(0, sb.length(),
                        android.text.style.URLSpan.class);
                for (android.text.style.URLSpan sp0 : spans) {
                    int st = sb.getSpanStart(sp0);
                    int en = sb.getSpanEnd(sp0);
                    int fl = sb.getSpanFlags(sp0);
                    String u = sp0.getURL();
                    sb.removeSpan(sp0);
                    if (st < 0 || en > sb.length() || u == null || u.isEmpty()) continue;
                    sb.setSpan(new UrlClickSpan(u), st, en, fl);
                }
                tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                tv.setHighlightColor(Palette.highlight());
            }
            tv.setText(sb);
        } catch (Exception e) {
            tv.setText(text);
        }
    }

    /** 网址点击：优先进 App 内浏览器（登录态/桥都在那边），失败退回系统浏览器。 */
    static class UrlClickSpan extends android.text.style.ClickableSpan {
        private final String url;

        UrlClickSpan(String url) {
            this.url = url;
        }

        @Override
        public void onClick(View widget) {
            if (System.currentTimeMillis() - lastLongPressAt < 900) return; // 刚长按复制，别跳走
            try {
                android.content.Intent it = new android.content.Intent(
                        widget.getContext(), BrowserActivity.class);
                it.putExtra("url", url);
                widget.getContext().startActivity(it);
            } catch (Exception e) {
                try {
                    widget.getContext().startActivity(new android.content.Intent(
                            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        public void updateDrawState(android.text.TextPaint ds) {
            ds.setColor(Palette.link());
            ds.setUnderlineText(true);
        }
    }

    /** 抽 ``` 代码块正文（去语言标识行）。未闭合的不算。 */
    private static java.util.ArrayList<String> extractCodeBlocks(String text) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        int i = 0;
        while (true) {
            int s = text.indexOf("```", i);
            if (s < 0) break;
            int e = text.indexOf("```", s + 3);
            if (e < 0) break;
            String body = text.substring(s + 3, e);
            int nl = body.indexOf('\n');
            if (nl >= 0) body = body.substring(nl + 1);
            if (!body.isEmpty()) out.add(body);
            i = e + 3;
        }
        return out;
    }

    /** 0.21.5：气泡底部"复制全文"按钮——一键复制整条 AI 回复完整原文（含 markdown 表格）。 */
    private void addCopyAllButton(LinearLayout bubble, final String full) {
        Button btn = new Button(this);
        btn.setText("📋 复制全文");
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btn.setMinHeight(dp(40));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        lp.topMargin = dp(4);
        bubble.addView(btn, lp);
        btn.setOnClickListener(v -> copyText(full));
    }

    /** 拼一条 AI 消息的全部正文：text part 原样拼接（保留 markdown 表格原文），跳过 synthetic 附件转储。 */
    private static String collectReplyText(JSONArray parts) {
        StringBuilder sb = new StringBuilder();
        if (parts == null) return "";
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.optJSONObject(i);
            if (p == null) continue;
            if (!"text".equals(p.optString("type", ""))) continue;
            if (p.optBoolean("synthetic", false)) continue;
            String t = p.optString("text", "");
            if (t.isEmpty() || "null".equals(t)) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(t);
        }
        return sb.toString();
    }

    /** 正文含代码块时，在气泡里加一个"复制代码"按钮（拼全部代码块）。 */
    private void addCodeCopyIfAny(LinearLayout bubble, String text) {
        final java.util.ArrayList<String> blocks = extractCodeBlocks(text);
        if (blocks.isEmpty()) return;
        final StringBuilder all = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) all.append("\n\n");
            all.append(blocks.get(i));
        }
        Button btn = new Button(this);
        btn.setText(blocks.size() > 1 ? "📋 复制代码（" + blocks.size() + "段）" : "📋 复制代码");
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btn.setMinHeight(dp(40));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        lp.topMargin = dp(4);
        bubble.addView(btn, lp);
        btn.setOnClickListener(v -> copyText(all.toString()));
    }

    // ---------- markdown 表格渲染（0.21.1） ----------
    // 0.21.0 的纯等宽方案对含中文的表格对不齐：中文是双宽、拉丁是单宽，字体比例不是整数，
    // 空格补不齐；何况未加空格的 markdown 表格本来就列不对齐。这里把 | a | b | 解析成
    // 真网格 TableLayout：表头灰底加粗、单元格描边、外套 HorizontalScrollView 可横滑，
    // 列宽由 TableLayout 按所有行取最宽，中文不再靠空格对齐。

    private static class Seg {
        static final int TEXT = 0, TABLE = 1, CODE = 2;
        int kind = TEXT;
        String text;                 // TEXT / CODE 正文
        String lang;                 // CODE 语言（留档，暂未用）
        java.util.List<String> rows; // TABLE 原始行
    }

    private static boolean isTableLine(String line) {
        String t = line.trim();
        return t.startsWith("|") && t.length() > 1;
    }

    private static java.util.List<String> parseCells(String line) {
        String t = line.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        java.util.List<String> cells = new java.util.ArrayList<>();
        for (String p : t.split("\\|", -1)) cells.add(p.trim());
        return cells;
    }

    /** markdown 分隔行：`|---|---|` / `|:--:|`。 */
    private static boolean isSeparatorLine(String line) {
        if (!isTableLine(line)) return false;
        java.util.List<String> cells = parseCells(line);
        if (cells.isEmpty()) return false;
        for (String c : cells) {
            String d = c.replace(":", "").trim();
            if (d.isEmpty() || !d.matches("-+")) return false;
        }
        return true;
    }

    /** 把正文切成 [段落 / 表格 / 代码块]：表格 = 连续 | 行且含分隔行；代码块 = ``` 围栏（去引号）。 */
    private static java.util.List<Seg> splitSegments(String text) {
        java.util.List<Seg> out = new java.util.ArrayList<>();
        String[] lines = text.split("\n", -1);
        StringBuilder cur = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("```")) {
                // 代码块：剥掉 ``` 行（含语言标识），只留代码正文
                String lang = trimmed.substring(3).trim();
                int j = i + 1;
                StringBuilder code = new StringBuilder();
                while (j < lines.length && !lines[j].trim().startsWith("```")) {
                    code.append(lines[j]).append('\n');
                    j++;
                }
                if (j < lines.length) j++; // 跳过收尾的 ```
                flushText(out, cur);
                String body = trimEnd(code.toString());
                if (!body.isEmpty()) {
                    Seg s = new Seg();
                    s.kind = Seg.CODE;
                    s.lang = lang;
                    s.text = body;
                    out.add(s);
                }
                i = j;
            } else if (isTableLine(lines[i])) {
                int j = i;
                while (j < lines.length && isTableLine(lines[j])) j++;
                java.util.List<String> run = new java.util.ArrayList<>();
                boolean sep = false;
                for (int k = i; k < j; k++) {
                    run.add(lines[k]);
                    if (isSeparatorLine(lines[k])) sep = true;
                }
                if (sep) {
                    flushText(out, cur);
                    Seg s = new Seg();
                    s.kind = Seg.TABLE;
                    s.rows = run;
                    out.add(s);
                } else {
                    for (int k = i; k < j; k++) cur.append(lines[k]).append('\n');
                }
                i = j;
            } else {
                cur.append(lines[i]).append('\n');
                i++;
            }
        }
        flushText(out, cur);
        return out;
    }

    private static void flushText(java.util.List<Seg> out, StringBuilder cur) {
        String t = trimEnd(cur.toString());
        if (!t.trim().isEmpty()) {
            Seg s = new Seg();
            s.kind = Seg.TEXT;
            s.text = t;
            out.add(s);
        }
        cur.setLength(0);
    }

    private static String trimEnd(String s) {
        int e = s.length();
        while (e > 0 && s.charAt(e - 1) == '\n') e--;
        return s.substring(0, e);
    }

    private android.graphics.drawable.Drawable cellBg(int fill) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(1), Palette.border());
        return g;
    }

    /** 段落渲染：普通文字走等宽富文本，表格走真网格。返回第一个文字 TextView（供流式映射）。 */
    private TextView renderTextSegments(LinearLayout bubble, String text, TextView reuse) {
        java.util.List<Seg> segs = splitSegments(text);
        TextView first = null;
        boolean reuseUsed = false;
        for (Seg s : segs) {
            if (s.kind == Seg.TABLE) {
                addMarkdownTable(bubble, s.rows);
                continue;
            }
            if (s.kind == Seg.CODE) {
                addCodeBlock(bubble, s.text);
                continue;
            }
            TextView tv;
            if (!reuseUsed) {
                tv = reuse;
                reuseUsed = true;
            } else {
                tv = new TextView(this);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                tv.setTextColor(Palette.textPrimary());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(4);
                tv.setLayoutParams(lp);
            }
            applyRichText(tv, s.text);
            makeCopyable(tv);
            bubble.addView(tv);
            if (first == null) first = tv;
        }
        return first;
    }

    /** markdown 表格 → 带边框网格（表头灰底加粗、可横滑、单元格长按复制）。 */
    private void addMarkdownTable(LinearLayout bubble, java.util.List<String> rows) {
        java.util.List<java.util.List<String>> body = new java.util.ArrayList<>();
        boolean hasSep = false;
        for (String line : rows) {
            if (isSeparatorLine(line)) {
                hasSep = true;
                continue;
            }
            body.add(parseCells(line));
        }
        if (body.isEmpty()) return;
        int cols = 0;
        for (java.util.List<String> r : body) cols = Math.max(cols, r.size());
        if (cols == 0) return;

        android.widget.TableLayout table = new android.widget.TableLayout(this);
        for (int r = 0; r < body.size(); r++) {
            boolean header = hasSep && r == 0;
            android.widget.TableRow tr = new android.widget.TableRow(this);
            java.util.List<String> cells = body.get(r);
            for (int c = 0; c < cols; c++) {
                String v = c < cells.size() ? cells.get(c) : "";
                TextView cell = new TextView(this);
                cell.setText(v);
                cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                cell.setTextColor(Palette.textPrimary());
                cell.setPadding(dp(6), dp(4), dp(6), dp(4));
                cell.setBackground(cellBg(header ? Palette.rowBg() : Palette.surface()));
                if (header) cell.setTypeface(null, android.graphics.Typeface.BOLD);
                makeCopyable(cell);
                tr.addView(cell);
            }
            table.addView(tr);
        }
        // 0.21.4：表格改为可横滑（用户要求）。0.21.3 的"压缩到一屏宽"对多列表格会把每列压得又窄又高、
        // 文字疯狂换行；AI 又常输出超屏宽的表，用户既看不清也不能拖。现恢复横滑：列保持内容自然宽度
        // （不收缩），超屏靠 HorizontalScrollView 左右拖；仅当真的溢出时在下方显示一行提示，
        // 兼顾 0.21.1"右列藏在屏幕外看不见"的老问题。
        table.setShrinkAllColumns(false);
        table.setStretchAllColumns(false);
        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(true);
        hsv.addView(table, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(4);
        bubble.addView(hsv, hlp);
        final TextView scrollHint = new TextView(this);
        scrollHint.setText("← 表格较宽，可左右滑动查看 →");
        scrollHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        scrollHint.setTextColor(Palette.textMuted());
        scrollHint.setPadding(0, dp(2), 0, 0);
        scrollHint.setVisibility(android.view.View.GONE);
        bubble.addView(scrollHint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        hsv.post(() -> {
            if (table.getMeasuredWidth() > hsv.getWidth())
                scrollHint.setVisibility(android.view.View.VISIBLE);
        });
    }

    /** 代码块（0.21.2）：去掉 ``` 引号与语言行，等宽 + 浅底，超宽可横滑（保留缩进），长按复制。 */
    private void addCodeBlock(LinearLayout bubble, String code) {
        TextView tv = new TextView(this);
        tv.setText(code);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(Palette.textPrimary());
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(dp(8), dp(6), dp(8), dp(6));
        makeCopyable(tv);
        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
        hsv.setBackgroundColor(Palette.codeBg());
        hsv.addView(tv, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(4);
        hsv.setLayoutParams(hlp);
        bubble.addView(hsv);
    }

    /** 历史思考折叠（0.21.2）：默认 6 行，点标题展开/收起，与流式思考块一致。返回内容视图。 */
    private TextView addCollapsedReasoning(LinearLayout bubble, String text) {
        return addCollapsedReasoning(bubble, text, "");
    }

    /** 0.21.15：历史思考块按 mid 登记，重拉历史后迟到的 reasoning.delta 接在它后面，
     *  不再另开一个「思考中…」块（那会重复一整段思考）。 */
    private TextView addCollapsedReasoning(LinearLayout bubble, String text, String mid) {
        final TextView header = new TextView(this);
        header.setText("💭 思考过程（点开看全部）");
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        header.setTextColor(Palette.textMuted());
        final TextView content = new TextView(this);
        content.setText(text);
        content.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        content.setTextColor(Palette.textMuted());
        content.setMaxLines(6);
        content.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.setOnClickListener(v -> {
            boolean expanded = content.getMaxLines() == Integer.MAX_VALUE;
            content.setMaxLines(expanded ? 6 : Integer.MAX_VALUE);
            content.setEllipsize(expanded ? android.text.TextUtils.TruncateAt.END : null);
            setReasonHeader(header, content, true, !expanded);
        });
        setReasonHeader(header, content, true, false);
        makeCopyable(content);
        bubble.addView(header);
        bubble.addView(content);
        if (mid != null && !mid.isEmpty()) {
            ReasonBlock rb = new ReasonBlock();
            rb.header = header;
            rb.content = content;
            rb.buf.append(text);
            rb.done = true; // 历史里的思考已结束
            reasonBlocks.put(mid, rb);
        }
        return content;
    }

    // ---------- 推理过程实时显示（0.21.0） ----------

    private static class ReasonBlock {
        TextView header;
        TextView content;
        final StringBuilder buf = new StringBuilder();
        boolean done = false;
    }

    private void removeStreamingPlaceholder(LinearLayout bubble) {
        for (int ci = 0; ci < bubble.getChildCount(); ci++) {
            View ch = bubble.getChildAt(ci);
            if ("streamingPlaceholder".equals(ch.getTag())) {
                bubble.removeView(ch);
                break;
            }
        }
    }

    /** done 且折叠时，内容没被截断就不写「点开看全部」（0.21.11：短思考也这么写很迷惑）。 */
    private void setReasonHeader(final TextView header, final TextView content,
                                 final boolean done, final boolean expanded) {
        if (expanded) {
            header.setText(done ? "💭 思考过程（点收起）" : "💭 思考中…（点收起）");
            return;
        }
        if (!done) {
            header.setText("💭 思考中…（点开看全部）");
            return;
        }
        content.post(() -> {
            try {
                android.text.Layout l = content.getLayout();
                boolean truncated = l != null && l.getLineCount() > content.getMaxLines();
                header.setText(truncated ? "💭 思考过程（点开看全部）" : "💭 思考过程");
            } catch (Exception ignored) {
            }
        });
    }

    private void setReasonExpanded(ReasonBlock rb, boolean expanded) {
        rb.content.setMaxLines(expanded ? Integer.MAX_VALUE : 6);
        rb.content.setEllipsize(expanded ? null : android.text.TextUtils.TruncateAt.END);
        setReasonHeader(rb.header, rb.content, rb.done, expanded);
    }

    /** 把一段 reasoning 增量追加进本消息的思考块（首次出现时建块）。 */
    private void appendReasoning(String mid, String delta) {
        if (mid.isEmpty()) return;
        LinearLayout bubble = msgBubbles.get(mid);
        if (bubble == null) {
            ensureBubble(mid);
            bubble = msgBubbles.get(mid);
            if (bubble == null) return;
        }
        ReasonBlock rb = reasonBlocks.get(mid);
        if (rb == null) {
            removeStreamingPlaceholder(bubble);
            rb = new ReasonBlock();
            rb.header = new TextView(this);
            rb.header.setText("💭 思考中…（点开看全部）");
            rb.header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            rb.header.setTextColor(Palette.textMuted());
            rb.content = new TextView(this);
            rb.content.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            rb.content.setTextColor(Palette.textMuted());
            rb.content.setMaxLines(6);
            rb.content.setEllipsize(android.text.TextUtils.TruncateAt.END);
            final ReasonBlock frb = rb;
            rb.header.setOnClickListener(v ->
                    setReasonExpanded(frb, frb.content.getMaxLines() != Integer.MAX_VALUE));
            bubble.addView(rb.header);
            bubble.addView(rb.content);
            reasonBlocks.put(mid, rb);
        }
        if (delta != null && !delta.isEmpty()) {
            rb.buf.append(delta);
            dirtyReasons.add(mid);
            scheduleFlush();
        }
    }

    private void finishReasoning() {
        for (Map.Entry<String, ReasonBlock> e : reasonBlocks.entrySet()) {
            ReasonBlock rb = e.getValue();
            rb.done = true;
            boolean expanded = rb.content.getMaxLines() == Integer.MAX_VALUE;
            setReasonHeader(rb.header, rb.content, true, expanded);
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
