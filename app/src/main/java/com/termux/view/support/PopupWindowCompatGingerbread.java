package com.termux.view.support;

import android.os.Build;
import android.view.WindowManager;
import android.widget.PopupWindow;

import java.lang.reflect.Field;

/**
 * Sets a {@link PopupWindow}'s window layout type on API levels where
 * {@link PopupWindow#setWindowLayoutType(int)} is not available.
 *
 * <p>{@code setWindowLayoutType} was added to {@link PopupWindow} in API 23 (M).
 * On older releases the field has to be written reflectively, which is what this
 * helper does. {@link #setWindowLayoutType(PopupWindow, int)} is a no-op on M and
 * above so callers can use it unconditionally.
 */
public class PopupWindowCompatGingerbread {

    private static final String FIELD_NAME = "mWindowLayoutType";
    private static final int WINDOW_LAYOUT_TYPE_X_LAYER = 0x80000;

    private PopupWindowCompatGingerbread() {
        // No instances.
    }

    /**
     * Set the window layout type of the given popup.
     *
     * @param popup The popup to modify.
     * @param type  The window layout type, e.g.
     *              {@link WindowManager.LayoutParams#TYPE_APPLICATION_SUB_PANEL}.
     */
    public static void setWindowLayoutType(PopupWindow popup, int type) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            popup.setWindowLayoutType(type);
            return;
        }

        // Below M there is no public setter; write mWindowLayoutType directly.
        try {
            Field field = PopupWindow.class.getDeclaredField(FIELD_NAME);
            field.setAccessible(true);
            field.setInt(popup, type);
        } catch (NoSuchFieldException | IllegalAccessException | RuntimeException e) {
            // Best effort only: the popup still works, it just may be positioned
            // differently on very old releases.
        }
    }

    /**
     * Set the window layout type to a value that keeps the popup above other
     * application windows.
     *
     * @param popup The popup to modify.
     */
    public static void setWindowLayoutTypeXLayer(PopupWindow popup) {
        setWindowLayoutType(popup, WINDOW_LAYOUT_TYPE_X_LAYER);
    }
}
