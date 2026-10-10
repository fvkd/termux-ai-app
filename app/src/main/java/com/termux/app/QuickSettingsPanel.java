package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.termux.ai.EncryptedPreferencesManager;
import com.termux.ai.R;

/**
 * Quick Settings Panel for Termux AI
 * A sliding panel that provides quick access to common settings
 *
 * The Save button previously showed a "Settings saved" toast without
 * writing anything, and the switches never loaded current values. Both
 * are now wired to the shared "termux_plus_prefs" preferences store, so
 * changes made here are persisted and are visible to the rest of the app
 * (keyboard autocorrect, dynamic colors, provider selection, etc.).
 */
public class QuickSettingsPanel extends LinearLayout {

    private static final String TAG = "QuickSettingsPanel";
    private static final String PREFS_NAME = "termux_plus_prefs";
    private static final String KEY_AUTO_SUGGESTIONS = "auto_suggestions_enabled";
    private static final String KEY_GBOARD_AUTOCOMPLETE = "keyboard_autocorrect";
    private static final String KEY_DYNAMIC_COLORS = "dynamic_colors_enabled";
    private static final String KEY_PRIVACY_MODE = "privacy_mode_enabled";
    private static final String KEY_LOCAL_PROCESSING = "local_processing_enabled";
    private static final String KEY_AI_MODEL = "ai_model";

    private SwitchMaterial switchAutoSuggestions;
    private SwitchMaterial switchGboardAutocomplete;
    private SwitchMaterial switchDynamicColors;
    private SwitchMaterial switchPrivacyMode;
    private SwitchMaterial switchLocalProcessing;
    private Spinner spinnerAiModel;
    private Button btnSaveSettings;
    private Button btnResetSettings;
    private Button btnClosePanel;

    private QuickSettingsPanelCallback callback;

    public interface QuickSettingsPanelCallback {
        void onSettingsApplied();
        void onPanelClosed();
    }

    public QuickSettingsPanel(Context context) {
        super(context);
        init(context);
    }

    public QuickSettingsPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public QuickSettingsPanel(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);

        LayoutInflater.from(context).inflate(R.layout.quick_settings_panel, this, true);

        switchAutoSuggestions = findViewById(R.id.switch_auto_suggestions);
        switchGboardAutocomplete = findViewById(R.id.switch_gboard_autocomplete);
        switchDynamicColors = findViewById(R.id.switch_dynamic_colors);
        switchPrivacyMode = findViewById(R.id.switch_privacy_mode);
        switchLocalProcessing = findViewById(R.id.switch_local_processing);
        spinnerAiModel = findViewById(R.id.spinner_ai_model);
        btnSaveSettings = findViewById(R.id.btn_save_settings);
        btnResetSettings = findViewById(R.id.btn_reset_settings);
        btnClosePanel = findViewById(R.id.btn_close_panel);

        setupListeners();

        // Load current values so the panel reflects actual settings.
        loadSettings();
    }

    private SharedPreferences getPrefs() {
        return EncryptedPreferencesManager.getEncryptedPrefs(getContext(), PREFS_NAME);
    }

    private void setupListeners() {
        if (btnSaveSettings != null) {
            btnSaveSettings.setOnClickListener(v -> {
                saveSettings();
                if (callback != null) callback.onSettingsApplied();
            });
        }

        if (btnResetSettings != null) {
            btnResetSettings.setOnClickListener(v -> resetSettings());
        }

        if (btnClosePanel != null) {
            btnClosePanel.setOnClickListener(v -> {
                if (callback != null) callback.onPanelClosed();
            });
        }
    }

    /** Load persisted settings into the panel controls. */
    private void loadSettings() {
        try {
            SharedPreferences prefs = getPrefs();
            if (switchAutoSuggestions != null)
                switchAutoSuggestions.setChecked(prefs.getBoolean(KEY_AUTO_SUGGESTIONS, true));
            if (switchGboardAutocomplete != null)
                switchGboardAutocomplete.setChecked(prefs.getBoolean(KEY_GBOARD_AUTOCOMPLETE, true));
            if (switchDynamicColors != null)
                switchDynamicColors.setChecked(prefs.getBoolean(KEY_DYNAMIC_COLORS, true));
            if (switchPrivacyMode != null)
                switchPrivacyMode.setChecked(prefs.getBoolean(KEY_PRIVACY_MODE, false));
            if (switchLocalProcessing != null)
                switchLocalProcessing.setChecked(prefs.getBoolean(KEY_LOCAL_PROCESSING, false));

            if (spinnerAiModel != null && spinnerAiModel.getCount() > 0) {
                String model = prefs.getString(KEY_AI_MODEL, null);
                if (model != null) {
                    for (int i = 0; i < spinnerAiModel.getCount(); i++) {
                        if (model.equals(String.valueOf(spinnerAiModel.getItemAtPosition(i)))) {
                            spinnerAiModel.setSelection(i);
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to load settings: " + t.getMessage());
        }
    }

    /** Persist the panel controls to shared preferences. */
    private void saveSettings() {
        try {
            SharedPreferences.Editor editor = getPrefs().edit();
            if (switchAutoSuggestions != null)
                editor.putBoolean(KEY_AUTO_SUGGESTIONS, switchAutoSuggestions.isChecked());
            if (switchGboardAutocomplete != null)
                editor.putBoolean(KEY_GBOARD_AUTOCOMPLETE, switchGboardAutocomplete.isChecked());
            if (switchDynamicColors != null)
                editor.putBoolean(KEY_DYNAMIC_COLORS, switchDynamicColors.isChecked());
            if (switchPrivacyMode != null)
                editor.putBoolean(KEY_PRIVACY_MODE, switchPrivacyMode.isChecked());
            if (switchLocalProcessing != null)
                editor.putBoolean(KEY_LOCAL_PROCESSING, switchLocalProcessing.isChecked());
            if (spinnerAiModel != null && spinnerAiModel.getSelectedItem() != null)
                editor.putString(KEY_AI_MODEL, String.valueOf(spinnerAiModel.getSelectedItem()));
            editor.apply();

            Toast.makeText(getContext(), "Settings saved", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to save settings", t);
            Toast.makeText(getContext(), "Failed to save settings", Toast.LENGTH_SHORT).show();
        }
    }

    private void resetSettings() {
        // Reset to default settings
        if (switchAutoSuggestions != null) switchAutoSuggestions.setChecked(true);
        if (switchGboardAutocomplete != null) switchGboardAutocomplete.setChecked(true);
        if (switchDynamicColors != null) switchDynamicColors.setChecked(false);
        if (switchPrivacyMode != null) switchPrivacyMode.setChecked(false);
        if (switchLocalProcessing != null) switchLocalProcessing.setChecked(false);

        if (spinnerAiModel != null && spinnerAiModel.getCount() > 0) {
            spinnerAiModel.setSelection(0);
        }

        // Persist the defaults immediately.
        saveSettings();

        Toast.makeText(getContext(), "Settings reset to defaults", Toast.LENGTH_SHORT).show();
    }

    public void setCallback(QuickSettingsPanelCallback callback) {
        this.callback = callback;
    }
}
