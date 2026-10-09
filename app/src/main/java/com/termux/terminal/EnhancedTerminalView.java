package com.termux.terminal;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import android.util.Log;

import com.termux.app.CommandGuard;
import com.termux.plus.api.AIProvider;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Enhanced TerminalView for Termux+
 *
 * Key enhancements:
 * - AI Provider integration (modular)
 * - Gboard autocomplete support
 * - Visual indicators for AI operations
 * - Enhanced text selection for mobile
 * - Progress tracking and file highlighting
 * - Dangerous-command guard: intercepts Enter before the command line is
 *   sent to the shell, and if CommandGuard flags it, requires explicit
 *   confirmation. This makes the "command filtering" setting real.
 */
public class EnhancedTerminalView extends TerminalView {
    private static final String TAG = "EnhancedTerminalView";

    // AI Integration
    private AIProvider currentAIProvider;
    private boolean isAIActive = false;
    private String currentAIOperation = "";
    private float aiProgress = 0.0f;
    private int tabIndex = -1;

    // Gboard integration
    private boolean gboardAutoCompleteEnabled = true;
    private InputMethodManager inputMethodManager;

    // Visual enhancements
    private Paint overlayPaint;
    private Paint progressPaint;
    private Paint highlightPaint;
    private List<FileHighlight> fileHighlights;

    // Gesture handling
    private GestureDetector gestureDetector;
    private Handler mainHandler;
    private ExecutorService backgroundExecutor;

    // Dangerous-command guard
    private Boolean commandGuardEnabled; // lazily read from prefs; null = not read yet
    private boolean enterBlocked = false;

    public interface ClaudeCodeListener {
        void onClaudeCodeDetected();
        void onClaudeOperationStarted(String operation);
        void onClaudeProgressUpdated(float progress);
        void onClaudeFileGenerated(String filePath, String action);
        void onClaudeOperationCompleted();
        void onClaudeErrorDetected(String error);
        void onClaudeTokenUsageUpdated(int used, int total);
    }

    private ClaudeCodeListener legacyListener;

    public EnhancedTerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    private void initialize() {
        inputMethodManager = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        fileHighlights = new ArrayList<>();
        mainHandler = new Handler(Looper.getMainLooper());
        backgroundExecutor = Executors.newSingleThreadExecutor();

        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);

        initializePaints();
        setupGestureDetector();
        setTerminalViewClient(new DefaultTerminalViewClient());
        setTextSize((int) (14 * getContext().getResources().getDisplayMetrics().density));
    }

    /** Minimal TerminalViewClient with sensible defaults for Termux+. */
    private class DefaultTerminalViewClient implements TerminalViewClient {
        @Override public float onScale(float scale) { return scale; }
        @Override public void onSingleTapUp(MotionEvent e) { showKeyboard(); }
        @Override public boolean shouldBackButtonBeMappedToEscape() { return true; }
        @Override public boolean shouldEnforceCharBasedInput() { return false; }
        @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
        @Override public boolean isTerminalViewSelected() { return hasFocus(); }
        @Override public void copyModeChanged(boolean copyMode) {}
        @Override public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) { return false; }
        @Override public boolean onKeyUp(int keyCode, KeyEvent e, TerminalSession session) { return false; }
        @Override public boolean onLongPress(MotionEvent event) { return false; }
        @Override public boolean readControlKey() { return false; }
        @Override public boolean readAltKey() { return false; }
        @Override public boolean readShiftKey() { return false; }
        @Override public boolean readFnKey() { return false; }
        @Override public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) { return false; }
        @Override public void onEmulatorSet() {}
        @Override public void logError(String tag, String message) { Log.e(tag, message); }
        @Override public void logWarn(String tag, String message) { Log.w(tag, message); }
        @Override public void logInfo(String tag, String message) { Log.i(tag, message); }
        @Override public void logDebug(String tag, String message) { Log.d(tag, message); }
        @Override public void logVerbose(String tag, String message) { Log.v(tag, message); }
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { Log.e(tag, message, e); }
        @Override public void logStackTrace(String tag, Exception e) { Log.e(tag, "Stack trace", e); }
    }

    public void setAIProvider(AIProvider provider) {
        this.currentAIProvider = provider;
        if (provider != null) {
            setupAIProviderListener();
        }
    }

    public void setTabIndex(int index) {
        this.tabIndex = index;
    }

    // -------------------------------------------------------------------------
    // Dangerous-command guard
    // -------------------------------------------------------------------------

    /**
     * Enable/disable the guard explicitly (e.g. from the settings screen).
     */
    public void setCommandGuardEnabled(boolean enabled) {
        this.commandGuardEnabled = enabled;
    }

    private boolean isCommandGuardEnabled() {
        if (commandGuardEnabled == null) {
            try {
                commandGuardEnabled = com.termux.ai.EncryptedPreferencesManager
                    .getEncryptedPrefs(getContext(), "termux_plus_prefs")
                    .getBoolean("command_filtering_enabled", true);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to read command filtering pref: " + t.getMessage());
                commandGuardEnabled = true; // safety-first default
            }
        }
        return commandGuardEnabled;
    }

    /**
     * Intercepts the IME/commitText Enter path: soft keyboards send the enter
     * key as the code point '\r' through inputCodePoint().
     */
    @Override
    public void inputCodePoint(int eventSource, int codePoint, boolean controlDownFromEvent, boolean leftAltDownFromEvent) {
        if (codePoint == '\r' && !controlDownFromEvent && isCommandGuardEnabled()) {
            String line = getCurrentCommandLine();
            if (line != null) {
                CommandGuard.Verdict verdict = CommandGuard.inspect(line);
                if (verdict.isBlocked()) {
                    blockEnter(verdict, line, () ->
                        super.inputCodePoint(eventSource, codePoint, controlDownFromEvent, leftAltDownFromEvent));
                    return;
                }
            }
        }
        super.inputCodePoint(eventSource, codePoint, controlDownFromEvent, leftAltDownFromEvent);
    }

    /**
     * Intercepts the key-event Enter path (hardware keyboards, Gboard with
     * TYPE_NULL, extra-keys rows): these go through handleKeyCode(), which
     * writes the mapped "\r" directly to the session.
     */
    @Override
    public boolean handleKeyCode(int keyCode, int keyMod) {
        if ((keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            && isCommandGuardEnabled()) {
            String line = getCurrentCommandLine();
            if (line != null) {
                CommandGuard.Verdict verdict = CommandGuard.inspect(line);
                if (verdict.isBlocked()) {
                    blockEnter(verdict, line, () -> super.handleKeyCode(keyCode, keyMod));
                    return true;
                }
            }
        }
        return super.handleKeyCode(keyCode, keyMod);
    }

    /** The command line the user has typed but not yet submitted (current screen row). */
    private String getCurrentCommandLine() {
        try {
            TerminalEmulator emulator = mEmulator;
            if (emulator == null) return null;
            int row = emulator.getCursorRow();
            String line = emulator.getScreen().getSelectedText(0, row, emulator.mColumns, row);
            return line == null ? null : line.trim();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to read current command line: " + t.getMessage());
            return null;
        }
    }

    /**
     * Show the confirmation dialog for a blocked command. The Enter keystroke
     * is held back until the user confirms; on confirm it is sent through,
     * on cancel it is dropped (the user can edit the line).
     */
    private void blockEnter(CommandGuard.Verdict verdict, String line, Runnable sendEnter) {
        if (enterBlocked) return; // a dialog is already up; drop repeat Enters
        enterBlocked = true;

        try {
            new AlertDialog.Builder(getContext())
                .setTitle(verdict.isDangerous() ? "\u26A0\uFE0F Dangerous command" : "Caution")
                .setMessage("This command may " + verdict.reason + ".\n\n" + line + "\n\nRun it anyway?")
                .setPositiveButton("Run anyway", (d, w) -> {
                    enterBlocked = false;
                    sendEnter.run();
                })
                .setNegativeButton("Cancel", (d, w) -> enterBlocked = false)
                .setOnCancelListener(d -> enterBlocked = false)
                .show();
        } catch (Throwable t) {
            // Never let the guard itself break the terminal - fall through.
            Log.e(TAG, "Failed to show command guard dialog", t);
            enterBlocked = false;
            sendEnter.run();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdown();
        }
    }

    private void initializePaints() {
        overlayPaint = new Paint();
        overlayPaint.setAntiAlias(true);
        overlayPaint.setColor(0x88000000);

        progressPaint = new Paint();
        progressPaint.setAntiAlias(true);
        progressPaint.setColor(0xFF4CAF50);
        progressPaint.setStrokeWidth(8);

        highlightPaint = new Paint();
        highlightPaint.setAntiAlias(true);
        highlightPaint.setColor(0x4400FF00);
    }

    private void setupGestureDetector() {
        gestureDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                // Implementation for double tap
                return true;
            }
        });
    }

    private void setupAIProviderListener() {
        currentAIProvider.setAIListener(new AIProvider.AIListener() {
            @Override
            public void onOperationDetected(String operation) {
                mainHandler.post(() -> {
                    isAIActive = true;
                    currentAIOperation = operation;
                    invalidate();
                    if (legacyListener != null) legacyListener.onClaudeOperationStarted(operation);
                });
            }

            @Override
            public void onProgressUpdated(float progress) {
                mainHandler.post(() -> {
                    aiProgress = progress;
                    invalidate(0, getHeight() - 50, getWidth(), getHeight());
                    if (legacyListener != null) legacyListener.onClaudeProgressUpdated(progress);
                });
            }

            @Override
            public void onFileGenerated(String filePath, String action) {
                mainHandler.post(() -> {
                    addFileHighlight(filePath, action);
                    if (legacyListener != null) legacyListener.onClaudeFileGenerated(filePath, action);
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    if (legacyListener != null) legacyListener.onClaudeErrorDetected(error);
                });
            }

            @Override
            public void onCompleted() {
                mainHandler.post(() -> {
                    isAIActive = false;
                    currentAIOperation = "";
                    aiProgress = 0.0f;
                    invalidate();
                    if (legacyListener != null) legacyListener.onClaudeOperationCompleted();
                });
            }

            @Override
            public void onTokenUsage(int used, int total) {
                mainHandler.post(() -> {
                    if (legacyListener != null) legacyListener.onClaudeTokenUsageUpdated(used, total);
                });
            }
        });
    }

    /** Called by TerminalFragment when session text changes; triggers AI processing. */
    public void processNewOutput() {
        final String currentLine = getCurrentLine();
        if (currentLine != null && currentAIProvider != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.execute(() -> {
                if (currentAIProvider.isEnabled()) {
                    currentAIProvider.processTerminalOutput(currentLine, tabIndex);
                }
            });
        }
    }

    private String getCurrentLine() {
        try {
            TerminalSession session = getCurrentSession();
            if (session != null) {
                TerminalEmulator emulator = session.getEmulator();
                if (emulator != null) {
                    int row = emulator.getCursorRow();
                    return emulator.getScreen().getSelectedText(0, row, emulator.mColumns, row);
                }
            }
        } catch (Exception e) {
            // Ignore errors
        }
        return null;
    }

    public String getTranscriptText() {
        try {
            TerminalSession session = getCurrentSession();
            if (session != null) {
                TerminalEmulator emulator = session.getEmulator();
                if (emulator != null) {
                    return emulator.getScreen().getTranscriptText();
                }
            }
        } catch (Exception e) {
            // Ignore errors
        }
        return null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (isAIActive) {
            drawAIOverlays(canvas);
            drawProgressIndicator(canvas);
        }
        drawFileHighlights(canvas);
    }

    private void drawAIOverlays(Canvas canvas) {
        int width = getWidth();

        // Top indicator bar
        canvas.drawRect(0, 0, width, 4, progressPaint);

        // Status text
        if (!currentAIOperation.isEmpty()) {
            String statusText = "\uD83E\uDD16 " + currentAIOperation;
            Paint textPaint = new Paint();
            textPaint.setColor(0xFF4CAF50);
            textPaint.setTextSize(24);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);

            Rect textBounds = new Rect();
            textPaint.getTextBounds(statusText, 0, statusText.length(), textBounds);

            canvas.drawText(statusText, width - textBounds.width() - 16, 32, textPaint);
        }
    }

    private void drawProgressIndicator(Canvas canvas) {
        if (aiProgress > 0 && aiProgress < 1.0f) {
            int width = getWidth();
            int progressWidth = (int)(width * aiProgress);

            canvas.drawRect(0, getHeight() - 8, progressWidth, getHeight(), progressPaint);
        }
    }

    private void drawFileHighlights(Canvas canvas) {
        long currentTime = System.currentTimeMillis();
        boolean needsInvalidate = false;

        fileHighlights.removeIf(highlight -> currentTime - highlight.timestamp > 5000);

        for (FileHighlight highlight : fileHighlights) {
            float alpha = 1.0f - ((currentTime - highlight.timestamp) / 5000.0f);
            if (alpha > 0) {
                highlightPaint.setAlpha((int)(alpha * 68));
                canvas.drawRect(highlight.rect, highlightPaint);
                needsInvalidate = true;
            }
        }

        if (needsInvalidate) {
            postInvalidateOnAnimation();
        }
    }

    private void addFileHighlight(String filePath, String action) {
        FileHighlight highlight = new FileHighlight();
        highlight.filePath = filePath;
        highlight.action = action;
        highlight.timestamp = System.currentTimeMillis();
        highlight.rect = new Rect(0, 0, getWidth(), 40);
        fileHighlights.add(highlight);
        invalidate();
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_UNSPECIFIED | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        if (gboardAutoCompleteEnabled) {
            outAttrs.inputType |= InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;
        }
        return new TerminalViewInputConnection(this, true);
    }

    public void setGboardAutoCompleteEnabled(boolean enabled) {
        this.gboardAutoCompleteEnabled = enabled;
        // Re-create input connection to apply changes
        if (inputMethodManager != null) {
            inputMethodManager.restartInput(this);
        }
    }

    public void showKeyboard() {
        if (inputMethodManager != null) {
            inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    public void hideKeyboard() {
        if (inputMethodManager != null) {
            inputMethodManager.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (gestureDetector != null && gestureDetector.onTouchEvent(event)) {
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (!hasFocus()) requestFocus();
            showKeyboard();
        }
        return super.onTouchEvent(event);
    }

    // Legacy support methods
    public void setClaudeCodeListener(ClaudeCodeListener listener) {
        this.legacyListener = listener;
    }

    public boolean isClaudeCodeActive() {
        return isAIActive;
    }

    public void forceClaudeCodeMode(boolean active) {
        this.isAIActive = active;
        invalidate();
    }

    private static class FileHighlight {
        String filePath;
        String action;
        long timestamp;
        Rect rect;
    }
}
