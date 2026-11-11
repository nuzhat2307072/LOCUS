package com.locus.app.util;

import android.content.Context;
import android.content.res.Configuration;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/**
 * Speaks navigation directions with Android's built-in text-to-speech (works offline, no key needed).
 * If the phone has no voice for the app language (Bangla is often missing), English is spoken instead and the
 * sentences are taken from the English string resources, so the words always match the voice.
 */
public final class VoiceGuide {
    private final Context app;
    private TextToSpeech tts;
    private boolean ready;
    private boolean englishFallback;
    private String pending;

    public VoiceGuide(Context context) {
        this.app = context.getApplicationContext();
        try {
            tts = new TextToSpeech(app, status -> {
                if (status != TextToSpeech.SUCCESS || tts == null) return;
                Locale want = Locale.getDefault();
                int r = tts.setLanguage(want);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.ENGLISH);
                    englishFallback = true;
                }
                ready = true;
                if (pending != null) {
                    tts.speak(pending, TextToSpeech.QUEUE_FLUSH, null, "locus-nav");
                    pending = null;
                }
            });
        } catch (RuntimeException e) {
            tts = null; // no speech engine on this phone: directions stay on screen
        }
    }

    /** A context whose strings match the voice that will read them. */
    public Context textContext(Context base) {
        if (!englishFallback) return base;
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.setLocale(Locale.ENGLISH);
        return base.createConfigurationContext(c);
    }

    /** Says the text now, cutting off anything still being said (a newer direction replaces an old one). */
    public void say(String text) {
        if (tts == null || text == null || text.isEmpty()) return;
        if (!ready) {
            pending = text;
            return;
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "locus-nav");
    }

    public void stopSpeaking() {
        pending = null;
        if (tts != null && ready) tts.stop();
    }

    public void shutdown() {
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (RuntimeException ignored) {
            }
            tts = null;
        }
        ready = false;
    }
}
