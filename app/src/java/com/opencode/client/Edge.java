package com.opencode.client;

import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/**
 * 0.22.3 系统栏内边距（edge-to-edge 适配）。
 *
 * 背景：targetSdk 35 在 Android 15 上强制 edge-to-edge，窗口内容会画到状态栏/导航栏底下，
 * 而本项目是纯代码布局、从未处理 insets —— 真机表现是聊天页标题被状态栏的时间和图标压住
 * （截图实证），底部输入栏也可能贴到导航条上。
 *
 * 做法：给根布局挂 insets 监听，把 statusBars/显示缺口的 top 与
 * navigationBars/IME 的 bottom 加到原有 padding 上。Android 10–14 未强制 edge-to-edge 时，
 * 这些 inset 已被 decor 消费（读到 0），加 0 不会双重留白。
 */
public final class Edge {

    private Edge() {
    }

    public static void apply(View root) {
        apply(root, null);
    }

    /**
     * @param onImeOpen 键盘由无到有时回调（聊天页用来滚到底部），可为 null
     */
    public static void apply(final View root, final Runnable onImeOpen) {
        if (Build.VERSION.SDK_INT < 30) return; // 26–29 默认 fitsSystemWindows，不需要手动补
        final int bl = root.getPaddingLeft();
        final int bt = root.getPaddingTop();
        final int br = root.getPaddingRight();
        final int bb = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = bt;
            int bottom = bb;
            int ime = 0;
            try {
                Insets bars = insets.getInsets(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.displayCutout());
                Insets nav = insets.getInsets(WindowInsets.Type.navigationBars());
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                top = bt + bars.top;
                bottom = bb + Math.max(nav.bottom, ime);
            } catch (Exception ignored) {
            }
            if (v.getPaddingTop() != top || v.getPaddingBottom() != bottom) {
                v.setPadding(bl, top, br, bottom);
                if (ime > 0 && onImeOpen != null) onImeOpen.run();
            }
            return insets;
        });
    }
}
