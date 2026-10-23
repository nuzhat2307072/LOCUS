package com.locus.app.util;

import android.app.Activity;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.locus.app.R;

/**
 * The language of the whole app (English, Bangla, Hindi). AppCompat stores the choice, applies it to every
 * screen and recreates the open ones; on Android 13+ it also shows up in the phone's own per-app language
 * settings (see res/xml/locales_config.xml).
 */
public final class LanguageHelper {
    private static final String[] TAGS = {"en", "bn", "hi"};

    private LanguageHelper() {
    }

    /** Index into {@link #TAGS} of the language in use (English when the phone language is not offered). */
    private static int currentIndex() {
        LocaleListCompat chosen = AppCompatDelegate.getApplicationLocales();
        String lang = !chosen.isEmpty() && chosen.get(0) != null
                ? chosen.get(0).getLanguage() : java.util.Locale.getDefault().getLanguage();
        for (int i = 0; i < TAGS.length; i++) {
            if (TAGS[i].equals(lang)) return i;
        }
        return 0;
    }

    /** Shows the "Choose language" dialog; picking one switches the app immediately. */
    public static void showChooser(Activity activity) {
        String[] names = {
                activity.getString(R.string.language_english),
                activity.getString(R.string.language_bangla),
                activity.getString(R.string.language_hindi)};
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.language_title)
                .setSingleChoiceItems(names, currentIndex(), (dialog, which) -> {
                    dialog.dismiss();
                    if (which != currentIndex()) {
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(TAGS[which]));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
