package com.opencode.client;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;

/**
 * 0.22.0 深色模式调色板（零第三方依赖）。
 *
 * 以前三个 Activity 里写死 #FFFFFF / #222222 等 30 个色值，深色无从下手。
 * 现在全部色值从这里取语义名（pageBg / surface / textPrimary …），内部存两套值；
 * 模式三态（跟随系统 / 浅色 / 深色）存 SharedPreferences(opencode_theme)。
 *
 * 用法：每个 Activity 的 onCreate 里 super.onCreate 之后立刻
 *   Palette.init(this); Palette.apply(this);
 * 取色一律 Palette.xxx()，禁止再写 Color.parseColor("#…")。
 */
public final class Palette {

    public static final int MODE_SYSTEM = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;

    private static final String PREF = "opencode_theme";
    private static final String KEY_MODE = "mode";

    private static int mode = MODE_SYSTEM;
    private static boolean dark = false;
    /** 模式每次变化 +1：常驻页 onResume 发现代号变了就自行重建，避免半深半浅。 */
    private static int gen = 0;

    private Palette() {
    }

    // ---------- 模式 ----------

    public static void init(Context ctx) {
        mode = readMode(ctx);
        dark = mode == MODE_DARK
                || (mode == MODE_SYSTEM && systemDark(ctx));
    }

    private static int readMode(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getInt(KEY_MODE, MODE_SYSTEM);
        } catch (Exception e) {
            return MODE_SYSTEM;
        }
    }

    private static boolean systemDark(Context ctx) {
        try {
            int ui = ctx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return ui == Configuration.UI_MODE_NIGHT_YES;
        } catch (Exception e) {
            return false;
        }
    }

    public static int mode() {
        return mode;
    }

    public static int gen() {
        return gen;
    }

    public static boolean isDark() {
        return dark;
    }

    /** 循环切换：跟随系统 → 浅色 → 深色 → 跟随系统。返回新模式。 */
    public static int cycle(Context ctx) {
        mode = (mode + 1) % 3;
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_MODE, mode).apply();
        } catch (Exception ignored) {
        }
        dark = mode == MODE_DARK || (mode == MODE_SYSTEM && systemDark(ctx));
        gen++;
        return mode;
    }

    public static String label(int m) {
        if (m == MODE_LIGHT) return "☀️ 浅色";
        if (m == MODE_DARK) return "🌙 深色";
        return "🌓 跟随系统";
    }

    public static String currentLabel() {
        return label(mode);
    }

    /**
     * 给 Activity 套主题 + 系统栏。必须在 super.onCreate 之后、建视图之前调用。
     * 用框架主题切换，AlertDialog / Spinner 弹层 / EditText / Button 的默认配色才会跟着变；
     * 只改自己的背景色会得到「黑底白框浅按钮」的拼接效果。
     */
    public static void apply(Activity a) {
        try {
            a.setTheme(dark
                    ? android.R.style.Theme_Material_NoActionBar
                    : android.R.style.Theme_Material_Light_NoActionBar);
        } catch (Exception ignored) {
        }
        Window w = a.getWindow();
        if (w == null) return;
        try {
            w.setBackgroundDrawable(new ColorDrawable(pageBg()));
            w.setStatusBarColor(barBg());
            w.setNavigationBarColor(barBg());
        } catch (Exception ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    c.setSystemBarsAppearance(dark ? 0 : mask, mask);
                }
            } else {
                int vis = w.getDecorView().getSystemUiVisibility();
                int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                vis = dark ? (vis & ~flags) : (vis | flags);
                w.getDecorView().setSystemUiVisibility(vis);
            }
        } catch (Exception ignored) {
        }
    }

    private static int c(boolean d, String light, String darkHex) {
        return android.graphics.Color.parseColor(d ? darkHex : light);
    }

    // ---------- 语义色 ----------

    /** 页面底色（滚动区、根布局）。 */
    public static int pageBg() {
        return c(dark, "#F5F5F5", "#0F1216");
    }

    /** 顶栏 / 底栏 / AI 气泡 / 未选中选项的底色。 */
    public static int surface() {
        return c(dark, "#FFFFFF", "#1B2026");
    }

    /** 顶栏底栏（与 surface 同值，语义分开，将来要单独调不用改调用点）。 */
    public static int barBg() {
        return c(dark, "#FFFFFF", "#1B2026");
    }

    /** 用户气泡（浅绿）。 */
    public static int userBubble() {
        return c(dark, "#DCF8C6", "#24402C");
    }

    /** 正文。 */
    public static int textPrimary() {
        return c(dark, "#222222", "#E6EAF0");
    }

    /** 次要文字（标签、状态）。 */
    public static int textSecondary() {
        return c(dark, "#666666", "#B2BAC4");
    }

    /** 弱化文字（时间、提示、占位）。 */
    public static int textMuted() {
        return c(dark, "#999999", "#8B939D");
    }

    /** 模型/Agent/改动 行底色、表头底。 */
    public static int rowBg() {
        return c(dark, "#EEEEEE", "#242A31");
    }

    /** 代码块 / 工具行 / 附件占位底色。 */
    public static int codeBg() {
        return c(dark, "#F0F0F0", "#242A31");
    }

    /** 表格单元格描边。 */
    public static int border() {
        return c(dark, "#CCCCCC", "#3B444E");
    }

    /** 权限审批条底色。 */
    public static int permBg() {
        return c(dark, "#FFF3E0", "#3A2E1D");
    }

    /** 权限审批条文字。 */
    public static int permText() {
        return c(dark, "#5D4037", "#E9CDAF");
    }

    /** 提问条底色。 */
    public static int questionBg() {
        return c(dark, "#E3F2FD", "#16293C");
    }

    /** 提问标题。 */
    public static int questionText() {
        return c(dark, "#0D47A1", "#9CC7FF");
    }

    /** 错误 / 拒绝 / 失败。 */
    public static int error() {
        return c(dark, "#C62828", "#FF7B72");
    }

    /** 运行状态横幅底色。 */
    public static int okBg() {
        return c(dark, "#E8F5E9", "#16301C");
    }

    /** 运行状态横幅文字。 */
    public static int okText() {
        return c(dark, "#2E7D32", "#7FD88F");
    }

    /** 已停止（中性灰棕）。 */
    public static int interrupted() {
        return c(dark, "#8D6E63", "#C3A69B");
    }

    /** 主按钮底色。 */
    public static int accent() {
        return c(dark, "#1976D2", "#2F6FD0");
    }

    /** 主按钮上的文字（恒白）。 */
    public static int onAccent() {
        return 0xFFFFFFFF;
    }

    /** 正文链接。 */
    public static int link() {
        return c(dark, "#1565C0", "#6CB0FF");
    }

    /** 悬浮按钮半透明底。 */
    public static int scrim() {
        return c(dark, "#99000000", "#B3000000");
    }

    /** 文本选中高亮。 */
    public static int highlight() {
        return c(dark, "#33000000", "#33FFFFFF");
    }
}
