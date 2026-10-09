package com.termux.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termux.ai.R;
import com.termux.terminal.EnhancedTerminalView;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import com.termux.plus.api.AIProvider;
import com.termux.plus.plugin.PluginManager;
import com.termux.plus.plugin.impl.ClaudePlugin;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Fragment containing an enhanced terminal with Claude Code integration.
 *
 * The terminal session now runs the real Termux bootstrap environment
 * ($PREFIX/bin/bash) installed by {@link TermuxInstaller}, instead of a bare
 * /system/bin/sh with a half-Termux environment (which caused the startup
 * SIGSEGV reported in thejaustin/termux-ai-app#53).
 */
public class TerminalFragment extends Fragment implements TerminalSessionClient {
    private static final String ARG_TAB_NAME = "tab_name";
    private static final String ARG_WORKING_DIR = "working_dir";
    private static final String ARG_TAB_INDEX = "tab_index";

    private String tabName;
    private String workingDirectory;
    private int tabIndex;

    private EnhancedTerminalView terminalView;
    private TerminalSession terminalSession;
    private WeakReference<TabbedTerminalActivity> parentActivityRef;
    private boolean bootstrapChecked = false;

    public static TerminalFragment newInstance(String tabName, String workingDirectory, int tabIndex) {
        TerminalFragment fragment = new TerminalFragment();
        Bundle args = new Bundle();
        args.putString(ARG_TAB_NAME, tabName);
        args.putString(ARG_WORKING_DIR, workingDirectory);
        args.putInt(ARG_TAB_INDEX, tabIndex);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            tabName = getArguments().getString(ARG_TAB_NAME);
            workingDirectory = getArguments().getString(ARG_WORKING_DIR);
            tabIndex = getArguments().getInt(ARG_TAB_INDEX);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof TabbedTerminalActivity) {
            parentActivityRef = new WeakReference<>((TabbedTerminalActivity) context);
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        if (parentActivityRef != null) {
            parentActivityRef.clear();
            parentActivityRef = null;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);

        terminalView = view.findViewById(R.id.terminal_view);
        setupTerminalView();
        createTerminalSession();

        return view;
    }

    private void setupTerminalView() {
        // Initialize font size
        if (getContext() != null) {
            terminalView.setTextSize((int) (14 * getResources().getDisplayMetrics().density));
        }

        // Disable autofill for the terminal view
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            terminalView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }

        // Enable Gboard autocomplete based on settings
        boolean autoCorrectEnabled = true;
        try {
            if (getContext() != null) {
                SharedPreferences prefs = com.termux.ai.EncryptedPreferencesManager.getEncryptedPrefs(getContext(), "termux_plus_prefs");
                autoCorrectEnabled = prefs.getBoolean(TermuxPlusSettingsActivity.PREF_KEYBOARD_AUTOCORRECT, true);
            }
        } catch (Throwable t) {
            Log.w("TerminalFragment", "Failed to read autocorrect preference: " + t.getMessage());
        }
        terminalView.setGboardAutoCompleteEnabled(autoCorrectEnabled);

        // Request focus and show keyboard when terminal is ready
        terminalView.post(() -> {
            terminalView.requestFocus();
            terminalView.showKeyboard();
        });

        // Setup AI Provider
        if (getContext() != null) {
            PluginManager manager = PluginManager.getInstance(getContext());
            // Use the first enabled AI provider
            List<AIProvider> providers = manager.getEnabledPluginsByType(AIProvider.class);
            if (!providers.isEmpty()) {
                terminalView.setTabIndex(tabIndex);
                terminalView.setAIProvider(providers.get(0));
            }
        }

        // Set Claude Code listener
        terminalView.setClaudeCodeListener(new EnhancedTerminalView.ClaudeCodeListener() {
            @Override
            public void onClaudeCodeDetected() {
                TabbedTerminalActivity activity = parentActivityRef != null ? parentActivityRef.get() : null;
                if (activity != null) {
                    // Update tab to show Claude is active
                    TabbedTerminalActivity.TerminalTab tab = activity.getTab(tabIndex);
                    if (tab != null) {
                        tab.setClaudeActive(true);
                    }
                }
            }

            @Override
            public void onClaudeOperationStarted(String operation) {
                Toast.makeText(getContext(), "Claude: " + operation, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onClaudeProgressUpdated(float progress) {
                // Progress is handled by the terminal view overlay
            }

            @Override
            public void onClaudeFileGenerated(String filePath, String action) {
                Toast.makeText(getContext(), "📁 " + action + ": " + filePath, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onClaudeOperationCompleted() {
                TabbedTerminalActivity activity = parentActivityRef != null ? parentActivityRef.get() : null;
                if (activity != null) {
                    TabbedTerminalActivity.TerminalTab tab = activity.getTab(tabIndex);
                    if (tab != null) {
                        tab.setClaudeActive(false);
                    }
                }
                Toast.makeText(getContext(), "✅ Claude operation completed", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onClaudeErrorDetected(String error) {
                Toast.makeText(getContext(), "❌ Claude Error: " + error, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onClaudeTokenUsageUpdated(int used, int total) {
                // Update token usage display - could be shown in status bar
                String tokenInfo = used + "/" + total + " tokens";
                // For now, just log it - could be displayed in UI later
                if (getContext() != null && used > total * 0.8) {
                    Toast.makeText(getContext(), "⚠️ Token usage: " + tokenInfo, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void createTerminalSession() {
        if (workingDirectory != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(workingDirectory).mkdirs();
        }
        Context context = getContext();
        if (context == null) {
            return;
        }

        if (!bootstrapChecked) {
            bootstrapChecked = true;
            if (!TermuxInstaller.isBootstrapInstalled(context)) {
                Toast.makeText(context, "Installing Termux environment (first launch)...", Toast.LENGTH_SHORT).show();
            }
            TermuxInstaller.setupBootstrapIfNeeded(context, (ok, error) -> {
                Context ctx = getContext();
                if (ctx == null) return;
                if (!ok) {
                    Log.e("TermuxAI", "Bootstrap installation failed", new Exception(error));
                    Toast.makeText(ctx, "Bootstrap install failed - falling back to system shell", Toast.LENGTH_LONG).show();
                }
                startTerminalSession();
            });
        } else {
            startTerminalSession();
        }
    }

    private void startTerminalSession() {
        Context context = getContext();
        if (context == null || terminalSession != null) {
            return;
        }

        String prefix = TermuxInstaller.getPrefixDir(context).getAbsolutePath();
        String shellPath = prefix + "/bin/bash";
        if (!new File(shellPath).exists()) {
            // Bootstrap not available - fall back to the system shell.
            Log.w("TermuxAI", "Termux bootstrap shell not found, falling back to /system/bin/sh");
            shellPath = "/system/bin/sh";
        }

        String[] args = {shellPath, "-l"};
        String[] env = buildShellEnvironment(prefix, shellPath);

        terminalSession = new TerminalSession(
            shellPath,
            workingDirectory,
            args,
            env,
            null,
            this
        );

        terminalView.attachSession(terminalSession);

        // Send initial setup commands
        sendInitialCommands();
    }

    /**
     * Build a proper Termux shell environment, mirroring
     * termux/termux-app TermuxShellEnvironment. Replacing the inherited
     * environment means the APEX vars required by the Android dynamic linker
     * must be carried over explicitly.
     */
    private String[] buildShellEnvironment(String prefix, String shellPath) {
        List<String> env = new java.util.ArrayList<>();
        env.add("TERM=xterm-256color");
        env.add("HOME=" + workingDirectory);
        env.add("PATH=" + prefix + "/bin:/system/bin:/system/xbin");
        env.add("LD_LIBRARY_PATH=" + prefix + "/lib");
        env.add("TMPDIR=" + prefix + "/tmp");
        env.add("PREFIX=" + prefix);
        env.add("SHELL=" + shellPath);
        env.add("TERMUX_AI=1");
        env.add("TERMUX_AI_TAB=" + tabName);
        env.add("TERMUX_AI_VERSION=2.2.0");
        env.add("COLORTERM=truecolor");
        env.add("LANG=en_US.UTF-8");

        // Carry over environment variables required on Android 10+ for the
        // dynamic linker to resolve APEX-provided runtime libraries, plus
        // other standard inherited variables.
        String[] inherited = {
            "ANDROID_ART_ROOT", "ANDROID_TZDATA_ROOT", "ANDROID_I18N",
            "ANDROID_DATA", "ANDROID_ROOT", "ANDROID_STORAGE",
            "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "EXTERNAL_STORAGE"
        };
        for (String key : inherited) {
            String value = System.getenv(key);
            if (value != null && !value.isEmpty()) {
                env.add(key + "=" + value);
            }
        }

        return env.toArray(new String[0]);
    }

    private void sendInitialCommands() {
        // Send commands to set up the terminal environment
        if (terminalSession != null) {
            try {
                // Change to working directory
                terminalSession.write("cd \"" + workingDirectory + "\"\r");

                // Show welcome message
                terminalSession.write("echo 'Welcome to Termux AI - " + tabName + "'\r");
                terminalSession.write("echo 'Type \"claude code\" to start AI-enhanced coding'\r");
                terminalSession.write("echo 'Gestures: Swipe down=stop, Double-tap=history'\r");
            } catch (Exception e) {
                Log.e("TermuxAI", "Failed to send initial commands", e);
            }
        }
    }

    public void shareTranscript() {
        if (terminalView != null) {
            String transcriptText = terminalView.getTranscriptText();
            if (transcriptText != null && !transcriptText.isEmpty()) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT, transcriptText);
                startActivity(Intent.createChooser(intent, "Share Transcript"));
            } else {
                Toast.makeText(getContext(), "No transcript to share", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public boolean isClaudeActive() {
        return terminalView != null && terminalView.isClaudeCodeActive();
    }

    public void forceClaudeMode(boolean active) {
        if (terminalView != null) {
            terminalView.forceClaudeCodeMode(active);
        }
    }

    public String getTabName() {
        return tabName;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public int getTabIndex() {
        return tabIndex;
    }

    public void clearTerminal() {
        if (terminalSession != null) {
            terminalSession.write("clear\r");
        }
    }

    /**
     * Send a command to the terminal
     * @param command The command to execute (without trailing newline)
     */
    public void sendCommand(String command) {
        if (terminalSession != null && command != null) {
            terminalSession.write(command + "\r");
        }
    }

    /**
     * Send raw bytes to the terminal (for control characters like Ctrl+C)
     * @param bytes The bytes to send
     */
    public void sendBytes(byte[] bytes) {
        if (terminalSession != null) {
            terminalSession.write(new String(bytes));
        }
    }

    /**
     * Send interrupt signal (Ctrl+C) to the terminal
     */
    public void sendInterrupt() {
        if (terminalSession != null) {
            // Ctrl+C is ASCII 3 (ETX - End of Text)
            terminalSession.write("\u0003");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (terminalSession != null) {
            terminalSession.finishIfRunning();
        }
    }

    // TerminalSessionClient implementation
    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
        if (terminalView != null) {
            terminalView.onScreenUpdated();
            terminalView.processNewOutput();
        }
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession changedSession) {
        // Update tab title if needed
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        // Handle session finish
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                Toast.makeText(getContext(), "Terminal session ended", Toast.LENGTH_SHORT).show();
            });
        }
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        // Handle clipboard copy
        if (getContext() == null) return;
        android.content.ClipboardManager clipboard =
            (android.content.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            android.content.ClipData clip = android.content.ClipData.newPlainText("Terminal", text);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(getContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        // Handle clipboard paste
        if (getContext() == null) return;
        android.content.ClipboardManager clipboard =
            (android.content.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            android.content.ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
            if (item != null && item.getText() != null) {
                String text = item.getText().toString();
                if (terminalSession != null) {
                    terminalSession.write(text);
                }
            }
        }
    }

    @Override
    public void onBell(@NonNull TerminalSession session) {
        // Handle terminal bell
    }

    @Override
    public void onColorsChanged(@NonNull TerminalSession session) {
        // Handle color changes
        if (terminalView != null) {
            terminalView.onScreenUpdated();
        }
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {}

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession session, int pid) {}

    @Override
    public Integer getTerminalCursorStyle() { return null; }

    @Override
    public void logError(String tag, String message) { Log.e(tag, message); }

    @Override
    public void logWarn(String tag, String message) { Log.w(tag, message); }

    @Override
    public void logInfo(String tag, String message) { Log.i(tag, message); }

    @Override
    public void logDebug(String tag, String message) { Log.d(tag, message); }

    @Override
    public void logVerbose(String tag, String message) { Log.v(tag, message); }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Log.e(tag, message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) { Log.e(tag, "Stack trace", e); }
}
