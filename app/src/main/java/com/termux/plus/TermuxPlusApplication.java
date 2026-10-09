package com.termux.plus;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.DynamicColors;
import com.termux.ai.BuildConfig;
import com.termux.ai.EncryptedPreferencesManager;
import com.termux.plus.plugin.PluginManager;
import com.termux.plus.plugin.impl.AutoSavePlugin;
import com.termux.plus.plugin.impl.ClaudePlugin;
import com.termux.plus.plugin.impl.GeminiPlugin;

/**
 * Main Application class for Termux+.
 *
 * Plus feature toggles (PlusFeatureManager) are now consulted here so the
 * Plus Toggles screen is honest: dynamic colors and the plugin system can
 * actually be turned off, and the Gemini plugin is registered alongside
 * Claude so the provider selection in settings works.
 */
public class TermuxPlusApplication extends Application {
    private static final String TAG = "TermuxPlusApplication";
    private static final String PREFS_NAME = "termux_plus_prefs";
    private static final String PREF_DYNAMIC_COLORS = "dynamic_colors_enabled";

    private static TermuxPlusApplication instance;
    private SharedPreferences preferences;
    private PlusFeatureManager featureManager;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        setupCrashHandler();

        if (BuildConfig.DEBUG) {
            enableStrictMode();
        }

        Log.d(TAG, "Initializing Termux+ v" + BuildConfig.VERSION_NAME);

        featureManager = PlusFeatureManager.getInstance(this);

        preferences = EncryptedPreferencesManager.getEncryptedPrefs(this, PREFS_NAME);

        // Apply Material You 3 Dynamic Colors safely - only if BOTH the
        // settings pref and the Plus Toggles feature flag allow it.
        try {
            boolean dynamicColorsEnabled = preferences.getBoolean(PREF_DYNAMIC_COLORS, true)
                && featureManager.isDynamicColorsEnabled();
            if (dynamicColorsEnabled) {
                DynamicColors.applyToActivitiesIfAvailable(this);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to apply dynamic colors: " + t.getMessage());
        }

        try {
            int nightMode = preferences.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            AppCompatDelegate.setDefaultNightMode(nightMode);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to set default night mode: " + t.getMessage());
        }

        // Initialize Plugin Manager and Core Plugins safely - honors the
        // Plugin System toggle on the Plus Features screen.
        try {
            if (featureManager.isPluginSystemEnabled()) {
                initializePlugins();
            } else {
                Log.i(TAG, "Plugin system disabled via Plus Toggles; skipping plugin registration.");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to initialize plugins: " + t.getMessage());
        }

        new Thread(this::initializeTerminalEnvironment).start();

        Log.d(TAG, "Termux+ initialized successfully");
    }

    private void initializePlugins() {
        PluginManager manager = PluginManager.getInstance(this);

        // Register Official Core Plugins - Claude AND Gemini so the provider
        // selection in settings actually has both providers available.
        manager.registerPlugin(new ClaudePlugin());
        manager.registerPlugin(new GeminiPlugin());
        manager.registerPlugin(new AutoSavePlugin());

        Log.i(TAG, "Core plugins registered.");
    }

    private void enableStrictMode() {
        android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build());
        android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build());
    }

    private void initializeTerminalEnvironment() {
        createDirectories();
    }

    private void createDirectories() {
        try {
            java.io.File homeDir = new java.io.File(getFilesDir(), "home");
            if (!homeDir.exists()) homeDir.mkdirs();
        } catch (Exception e) {
            Log.e(TAG, "Failed to create directories", e);
        }
    }

    private void setupCrashHandler() {
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            Log.e("TERMUX_CRASH", "FATAL UNCAUGHT EXCEPTION in thread " + thread.getName(), ex);
            try {
                java.io.File crashFile = new java.io.File(getFilesDir(), "last_crash.txt");
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                ex.printStackTrace(pw);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(crashFile);
                fos.write(("Thread: " + thread.getName() + "\nMessage: " + ex.getMessage() + "\n\n" + sw.toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fos.flush();
                fos.close();
            } catch (Throwable ignored) {}
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, ex);
            }
        });
    }

    public static TermuxPlusApplication getInstance() {
        return instance;
    }

    public SharedPreferences getAppPreferences() {
        return preferences;
    }

    public PlusFeatureManager getFeatureManager() {
        return featureManager;
    }

    public boolean isClaudeEnabled() {
        return preferences.getBoolean("claude_enabled", true);
    }

    public boolean areDynamicColorsAvailable() {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S;
    }

    public boolean isDynamicColorsEnabled() {
        return preferences.getBoolean(PREF_DYNAMIC_COLORS, true)
            && featureManager.isDynamicColorsEnabled();
    }

    public void setDynamicColorsEnabled(boolean enabled) {
        preferences.edit().putBoolean(PREF_DYNAMIC_COLORS, enabled).apply();
    }

    /** Selected AI provider: "claude" (default) or "gemini". */
    public String getAIProviderId() {
        return preferences.getString("ai_provider", "claude");
    }

    public void setAIProviderId(String providerId) {
        preferences.edit().putString("ai_provider", providerId).apply();
    }

    public int getNightMode() {
        return preferences.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    public void setNightMode(int nightMode) {
        preferences.edit().putInt("night_mode", nightMode).apply();
    }

    public String getThemeStyle() {
        return preferences.getString("theme_style", "expressive");
    }

    public void setThemeStyle(String style) {
        preferences.edit().putString("theme_style", style).apply();
    }
}
