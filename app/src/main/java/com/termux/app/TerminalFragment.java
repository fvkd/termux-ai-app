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
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.termux.ai.R;
import com.termux.terminal.EnhancedTerminalView;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import com.termux.plus.PlusFeatureManager;
import com.termux.plus.api.AIProvider;
import com.termux.plus.api.TermuxPlugin;
import com.termux.plus.plugin.PluginManager;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Fragment containing an enhanced terminal with Claude Code integration.
 *
 * The terminal session runs the real Termux bootstrap environment
 * ($PREFIX/bin/bash) installed by TermuxInstaller, instead of a bare
 * /system/bin/sh with a half-Termux environment (which caused the startup
 * SIGSEGV reported in thejaustin/termux-ai-app#53).
 *
 * Plus feature toggles are honored: the AI provider hookup only runs when
 * the AI Integration toggle is on, and the provider instance is chosen by
 * the user's provider selection (Claude or Gemini).
 *
 * The dangerous-command guard is wired here: the "command filtering" pref
 * enables/disables it on the terminal view, and programmatic command sends
 * (quick commands, dialogs) go through the same confirmation flow.
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
    private boolean aiHookedUp = false;

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
        // Plus Toggles may have changed while we were paused - re-evaluate
        // the AI hookup so turning AI off actually detaches the provider.
        hookupAIProvider();
        applyCommandGuardSetting();
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

        // Apply the dangerous-command guard setting.
        applyCommandGuardSetting();

        // Request focus and show keyboard when terminal is ready
        terminalView.post(() -> {
            terminalView.requestFocus();
            terminalView.showKeyboard();
        });

        // Set Claude Code listener
        terminalView.setClaudeCodeListener(new EnhancedTerminalView.ClaudeCodeListener() {
            @Override
            public void onClaudeCodeDetected() {
                TabbedTerminalActivity activity = parentActivityRef != null ? parentActivityRef.get() : null;
                if (activity != null) {
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
                Toast.makeText(getContext(), "\uD83D\uDCC1 " + action + ": " + filePath, Toast.LENGTH_LONG).show();
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
                Toast.makeText(getContext(), "\u2705 Claude operation completed", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onClaudeErrorDetected(String error) {
                Toast.makeText(getContext(), "\u274C Claude Error: " + error, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onClaudeTokenUsageUpdated(int used, int total) {
                if (getContext() != null && total > 0 && used > total * 0.8) {
                    Toast.makeText(getContext(), "\u26A0\uFE0F Token usage: " + used + "/" + total + " tokens", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** Push the "command filtering" pref into the terminal view's guard. */
    private void applyCommandGuardSetting() {
        if (terminalView == null || getContext() == null) return;
        try {
            SharedPreferences prefs = com.termux.ai.EncryptedPreferencesManager.getEncryptedPrefs(getContext(), "termux_plus_prefs");
            terminalView.setCommandGuardEnabled(prefs.getBoolean("command_filtering_enabled", true));
        } catch (Throwable t) {
            Log.w("TerminalFragment", "Failed to read command filtering pref: " + t.getMessage());
            terminalView.setCommandGuardEnabled(true);
        }
    }

    /**
     * Attach (or detach) the AI provider based on the Plus Toggles AI
     * Integration flag and the user's selected provider (Claude/Gemini).
     * Runs on every resume so toggle changes take effect immediately.
     */
    private void hookupAIProvider() {
        if (getContext() == null || terminalView == null) return;

        boolean aiEnabled;
        try {
            aiEnabled = PlusFeatureManager.getInstance(getContext()).isAIEnabled();
        } catch (Throwable t) {
            Log.w("TerminalFragment", "Failed to read AI toggle: " + t.getMessage());
            aiEnabled = true;
        }

        if (!aiEnabled) {
            if (aiHookedUp) {
                terminalView.setAIProvider(null);
                terminalView.setTabIndex(-1);
                aiHookedUp = false;
                Log.i("TerminalFragment", "AI Integration disabled via Plus Toggles; provider detached.");
            }
            return;
        }

        PluginManager manager = PluginManager.getInstance(getContext());
        List<AIProvider> providers = manager.getEnabledPluginsByType(AIProvider.class);
        if (providers.isEmpty()) return;

        AIProvider selected = null;

        // Prefer the provider the user selected in settings.
        try {
            SharedPreferences prefs = com.termux.ai.EncryptedPreferencesManager.getEncryptedPrefs(getContext(), "termux_plus_prefs");
            String providerChoice = prefs.getString("ai_provider", "claude");
            String wantedId = "gemini".equals(providerChoice)
                ? "com.termux.plus.gemini" : "com.termux.plus.claude";
            for (AIProvider p : providers) {
                if (wantedId.equals(((TermuxPlugin) p).getId())) {
                    selected = p;
                    break;
                }
            }
        } catch (Throwable t) {
            Log.w("TerminalFragment", "Failed to resolve selected provider: " + t.getMessage());
        }

        // Fall back to the first enabled provider.
        if (selected == null) selected = providers.get(0);

        terminalView.setTabIndex(tabIndex);
        terminalView.setAIProvider(selected);
        aiHookedUp = true;
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
        if (terminalSession == null) return;
        try {
            terminalSession.write("cd \"" + workingDirectory + "\"\r");
            terminalSession.write("echo 'Welcome to Termux AI - " + tabName + "'\r");

            // AI hint only when the AI Integration toggle is on.
            boolean aiEnabled = true;
            if (getContext() != null) {
                try {
                    aiEnabled = PlusFeatureManager.getInstance(getContext()).isAIEnabled();
                } catch (Throwable ignored) {}
            }
            if (aiEnabled) {
                terminalSession.write("echo 'Type \"claude code\" to start AI-enhanced coding'\r");
            }
            terminalSession.write("echo 'Gestures: Swipe down=stop, Double-tap=history'\r");
        } catch (Exception e) {
            Log.e("TermuxAI", "Failed to send initial commands", e);
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
     * Send a command to the terminal, with the dangerous-command guard
     * applied to programmatic sends (quick commands, dialogs) too.
     * @param command The command to execute (without trailing newline)
     */
    public void sendCommand(String command) {
        if (terminalSession == null || command == null) return;

        CommandGuard.Verdict verdict = CommandGuard.inspect(command);
        if (!verdict.isBlocked()) {
            terminalSession.write(command + "\r");
            return;
        }

        try {
            new AlertDialog.Builder(requireActivity())
                .setTitle(verdict.isDangerous() ? "\u26A0\uFE0F Dangerous command" : "Caution")
                .setMessage("This command may " + verdict.reason + ".\n\n" + command + "\n\nRun it anyway?")
                .setPositiveButton("Run anyway", (d, w) -> {
                    if (terminalSession != null) terminalSession.write(command + "\r");
                })
                .setNegativeButton("Cancel", null)
                .show();
        } catch (Throwable t) {
            Log.e("TermuxAI", "Guard dialog failed; allowing command", t);
            terminalSession.write(command + "\r");
        }
    }

    public void sendBytes(byte[] bytes) {
        if (terminalSession != null) {
            terminalSession.write(new String(bytes));
        }
    }

    public void sendInterrupt() {
        if (terminalSession != null) {
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
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() ->
                Toast.makeText(getContext(), "Terminal session ended", Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
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
        if (getContext() == null) return;
        android.content.ClipboardManager clipboard =
            (android.content.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            android.content.ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
            if (item != null && item.getText() != null && terminalSession != null) {
                terminalSession.write(item.getText().toString());
            }
        }
    }

    @Override
    public void onBell(@NonNull TerminalSession session) {
    }

    @Override
    public void onColorsChanged(@NonNull TerminalSession session) {
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
