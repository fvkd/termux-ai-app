package com.termux.app;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.view.TerminalView;

/**
 * Row of terminal keys missing from soft keyboards (ESC, TAB, CTRL, ALT,
 * arrows, ...), shown above the keyboard like Termux's extra keys.
 *
 * CTRL and ALT latch for the next key; the focused {@link TerminalView}
 * consumes them through its TerminalViewClient via {@link #consumeCtrl()} and
 * {@link #consumeAlt()}. Buttons are not focusable so the terminal keeps the
 * keyboard while they are tapped.
 */
public class ExtraKeysBar extends HorizontalScrollView {

    private static final int COLOR_TEXT = 0xFFE6E6E6;
    private static final int COLOR_LATCHED = 0xFF4CAF50;

    private TextView ctrlButton;
    private TextView altButton;
    private boolean ctrlLatched;
    private boolean altLatched;

    public ExtraKeysBar(Context context) {
        this(context, null);
    }

    public ExtraKeysBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        setHorizontalScrollBarEnabled(false);
        setFocusable(false);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));

        addKey(row, "ESC", v -> sendKey(KeyEvent.KEYCODE_ESCAPE));
        addKey(row, "TAB", v -> sendKey(KeyEvent.KEYCODE_TAB));
        ctrlButton = addKey(row, "CTRL", v -> {
            ctrlLatched = !ctrlLatched;
            refreshLatches();
        });
        altButton = addKey(row, "ALT", v -> {
            altLatched = !altLatched;
            refreshLatches();
        });
        addKey(row, "←", v -> sendKey(KeyEvent.KEYCODE_DPAD_LEFT));
        addKey(row, "↓", v -> sendKey(KeyEvent.KEYCODE_DPAD_DOWN));
        addKey(row, "↑", v -> sendKey(KeyEvent.KEYCODE_DPAD_UP));
        addKey(row, "→", v -> sendKey(KeyEvent.KEYCODE_DPAD_RIGHT));
        addKey(row, "HOME", v -> sendKey(KeyEvent.KEYCODE_MOVE_HOME));
        addKey(row, "END", v -> sendKey(KeyEvent.KEYCODE_MOVE_END));
        addKey(row, "PGUP", v -> sendKey(KeyEvent.KEYCODE_PAGE_UP));
        addKey(row, "PGDN", v -> sendKey(KeyEvent.KEYCODE_PAGE_DOWN));
        for (String symbol : new String[]{"-", "/", "|", "~", "*", "&", "$", ">"}) {
            addKey(row, symbol, v -> sendText(symbol));
        }
    }

    /** Returns whether CTRL was latched, clearing the latch. */
    public boolean consumeCtrl() {
        if (!ctrlLatched) return false;
        ctrlLatched = false;
        post(this::refreshLatches);
        return true;
    }

    /** Returns whether ALT was latched, clearing the latch. */
    public boolean consumeAlt() {
        if (!altLatched) return false;
        altLatched = false;
        post(this::refreshLatches);
        return true;
    }

    private TextView addKey(LinearLayout row, String label, OnClickListener listener) {
        TextView key = new TextView(getContext());
        key.setText(label);
        key.setTextColor(COLOR_TEXT);
        key.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        key.setGravity(Gravity.CENTER);
        int pad = dp(14);
        key.setPadding(pad, 0, pad, 0);
        key.setMinWidth(dp(44));
        key.setFocusable(false);
        TypedValue ripple = new TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        key.setBackgroundResource(ripple.resourceId);
        key.setOnClickListener(listener);
        row.addView(key, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        return key;
    }

    private void refreshLatches() {
        ctrlButton.setTextColor(ctrlLatched ? COLOR_LATCHED : COLOR_TEXT);
        altButton.setTextColor(altLatched ? COLOR_LATCHED : COLOR_TEXT);
    }

    private TerminalView focusedTerminal() {
        View focused = getRootView().findFocus();
        return focused instanceof TerminalView ? (TerminalView) focused : null;
    }

    private void sendKey(int keyCode) {
        TerminalView terminal = focusedTerminal();
        if (terminal == null) return;
        terminal.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        terminal.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    private void sendText(String text) {
        TerminalView terminal = focusedTerminal();
        if (terminal == null) return;
        text.codePoints().forEach(cp -> terminal.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD, cp, false, false));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
