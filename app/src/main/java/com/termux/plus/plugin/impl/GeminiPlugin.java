package com.termux.plus.plugin.impl;

import android.content.Context;
import android.util.Log;

import com.termux.plus.api.AIProvider;
import com.termux.plus.api.TermuxPlugin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Official Termux+ Plugin for Google Gemini CLI integration.
 *
 * Previously the settings screen allowed selecting Gemini as the AI provider,
 * but no GeminiPlugin existed - PluginManager only had Claude + AutoSave, so
 * selecting Gemini silently kept Claude as the live provider. This plugin
 * closes that gap: it detects gemini-cli activity in terminal output and
 * surfaces operation/progress/error/token events through AIListener, the
 * same interface the terminal view already consumes for Claude.
 */
public class GeminiPlugin implements AIProvider {

    private static final String TAG = "GeminiPlugin";

    /** Operation start patterns emitted by gemini-cli. */
    private static final Pattern[] OPERATION_PATTERNS = {
        Pattern.compile("gemini[^ ]* (generating|creating|editing|analyzing|refactoring|summarizing)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\[(gemini)[^]]*\\] (starting|running)", Pattern.CASE_INSENSITIVE)
    };

    /** Progress like "45%". */
    private static final Pattern PROGRESS_PATTERN = Pattern.compile("\\b(\\d{1,3})%\\b");

    /** File-written markers. */
    private static final Pattern FILE_PATTERN = Pattern.compile("(?:wrote|created|updated|edited) \"([^" + '"' + "]+)\"", Pattern.CASE_INSENSITIVE);

    /** Common error shapes. */
    private static final Pattern ERROR_PATTERN = Pattern.compile("\\b(error|failed|exception|traceback)\\b", Pattern.CASE_INSENSITIVE);

    /** Token usage like "tokens: 1234/8192". */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("tokens?:\\s*(\\d+)\\s*/\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private AIListener aiListener;
    private boolean enabled = true;
    private boolean active;
    private Context context;

    @Override
    public String getId() {
        return "com.termux.plus.gemini";
    }

    @Override
    public String getName() {
        return "Gemini CLI Integration";
    }

    @Override
    public String getDescription() {
        return "Integration with Google's Gemini CLI for inline AI assistance.";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getAuthor() {
        return "Termux+ Team";
    }

    @Override
    public void onInit(Context context) {
        this.context = context;
        Log.i(TAG, "GeminiPlugin initialized");
    }

    @Override
    public void onUnload() {
        active = false;
        aiListener = null;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void processTerminalOutput(String output, int tabIndex) {
        if (!isEnabled() || output == null || aiListener == null) {
            return;
        }

        for (Pattern p : OPERATION_PATTERNS) {
            Matcher m = p.matcher(output);
            if (m.find()) {
                active = true;
                aiListener.onOperationDetected(m.group(2) != null ? m.group(2) : m.group(1));
                break;
            }
        }

        Matcher progress = PROGRESS_PATTERN.matcher(output);
        if (progress.find()) {
            try {
                aiListener.onProgressUpdated(Integer.parseInt(progress.group(1)) / 100f);
            } catch (NumberFormatException ignored) {
            }
        }

        Matcher file = FILE_PATTERN.matcher(output);
        if (file.find()) {
            aiListener.onFileGenerated(file.group(1), "Updated");
        }

        Matcher tokens = TOKEN_PATTERN.matcher(output);
        if (tokens.find()) {
            try {
                aiListener.onTokenUsage(Integer.parseInt(tokens.group(1)), Integer.parseInt(tokens.group(2)));
            } catch (NumberFormatException ignored) {
            }
        }

        if (ERROR_PATTERN.matcher(output).find()) {
            active = false;
            aiListener.onError(output.trim());
        } else if (active && (output.contains("done") || output.contains("complete"))) {
            active = false;
            aiListener.onCompleted();
        }
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void setAIListener(AIListener listener) {
        this.aiListener = listener;
    }

    @Override
    public void stopOperation() {
        active = false;
    }
}
