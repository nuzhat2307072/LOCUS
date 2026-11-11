package com.locus.app.util;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.locus.app.R;

import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.TilesOverlay;

/** Light / dark / follow-system theme, and dark map tiles at night. The choice is saved and used at every start. */
public final class ThemeHelper {
    private ThemeHelper() {
    }

    /** Called once when the app starts (LocusApp) and after a new choice. */
    public static void apply(Context c) {
        int mode = AppPrefs.themeMode(c);
        AppCompatDelegate.setDefaultNightMode(mode == 1 ? AppCompatDelegate.MODE_NIGHT_NO
                : mode == 2 ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    public static String currentName(Context c) {
        int m = AppPrefs.themeMode(c);
        return c.getString(m == 1 ? R.string.theme_light : m == 2 ? R.string.theme_dark : R.string.theme_system);
    }

    public static void showChooser(Activity a) {
        String[] names = {a.getString(R.string.theme_system), a.getString(R.string.theme_light),
                a.getString(R.string.theme_dark)};
        new MaterialAlertDialogBuilder(a)
                .setTitle(R.string.theme_title)
                .setSingleChoiceItems(names, AppPrefs.themeMode(a), (d, which) -> {
                    d.dismiss();
                    if (which != AppPrefs.themeMode(a)) {
                        AppPrefs.setThemeMode(a, which);
                        apply(a); // every open screen is rebuilt in the new theme at once
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** True when the app itself is shown in dark mode. */
    public static boolean isUiDark(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** The map background stays light. Dark tiles only when the person turned on "Dark map after sunset" and it is night. */
    public static boolean wantDarkMap(Context c) {
        if (!AppPrefs.autoDarkMap(c)) return false;
        double[] at = AppPrefs.lastLocation(c);
        return SunTimes.isNight(at[0], at[1], System.currentTimeMillis());
    }

    /** Re-checked every time a screen comes back, so the map turns dark by itself as the evening starts. */
    public static void applyToMap(MapView map) {
        if (map == null) return;
        boolean dark = wantDarkMap(map.getContext());
        map.getOverlayManager().getTilesOverlay().setLoadingBackgroundColor(
                dark ? 0xFF1B1B1B : 0xFFF2EFE9); // light paper colour while tiles load (no dark flash)
        map.getOverlayManager().getTilesOverlay().setColorFilter(
                dark ? TilesOverlay.INVERT_COLORS : null);
        map.invalidate();
    }
}
