package com.locus.app.util;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Vibrator;

import com.locus.app.ui.LoginActivity;
import com.locus.app.ui.RegisterActivity;
import com.locus.app.ui.SosActivity;

/**
 * "Shake three times to start SOS" while any LOCUS screen is open (Settings > Shake for SOS).
 * The sensor is only listened to while a screen is visible, so it costs no battery in the background.
 * The 5 second SOS countdown still applies, so a false alarm can be cancelled.
 */
public final class ShakeSos implements Application.ActivityLifecycleCallbacks, SensorEventListener {
    private final ShakeDetector detector = new ShakeDetector();
    private SensorManager sensors;
    private Activity current;

    @Override
    public void onActivityResumed(Activity a) {
        stop();
        if (a instanceof SosActivity || a instanceof LoginActivity || a instanceof RegisterActivity) return;
        if (!AppPrefs.sosShake(a)) return;
        sensors = (SensorManager) a.getSystemService(Context.SENSOR_SERVICE);
        Sensor accel = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel == null) return;
        current = a;
        detector.reset();
        sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public void onActivityPaused(Activity a) {
        if (a == current) stop();
    }

    private void stop() {
        if (sensors != null) sensors.unregisterListener(this);
        current = null;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        Activity a = current;
        if (a == null || e.values.length < 3) return;
        if (detector.onReading(e.values[0], e.values[1], e.values[2], SystemClock.elapsedRealtime())) {
            try {
                Vibrator v = (Vibrator) a.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(300);
            } catch (RuntimeException ignored) {
            }
            a.startActivity(new Intent(a, SosActivity.class));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onActivityCreated(Activity a, Bundle b) {
    }

    @Override
    public void onActivityStarted(Activity a) {
    }

    @Override
    public void onActivityStopped(Activity a) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity a, Bundle b) {
    }

    @Override
    public void onActivityDestroyed(Activity a) {
    }
}
