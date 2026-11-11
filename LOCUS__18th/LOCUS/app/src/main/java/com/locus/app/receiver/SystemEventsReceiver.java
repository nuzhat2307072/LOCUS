package com.locus.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;

import com.locus.app.R;

/**
 * Dynamically registered receiver (register in onStart/onResume, unregister in onStop/onPause) for
 * SYSTEM broadcasts: airplane mode, charger connected, battery low.
 */
public class SystemEventsReceiver extends BroadcastReceiver {

    public interface Listener {
        void onSystemEvent(String message);
    }

    private final Listener listener;

    public SystemEventsReceiver(Listener listener) {
        this.listener = listener;
    }

    public static IntentFilter filter() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_BATTERY_LOW);
        return f;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        switch (action) {
            case Intent.ACTION_AIRPLANE_MODE_CHANGED:
                boolean on = Settings.Global.getInt(context.getContentResolver(),
                        Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
                listener.onSystemEvent(context.getString(on ? R.string.sys_airplane_on
                        : R.string.sys_airplane_off));
                break;
            case Intent.ACTION_POWER_CONNECTED:
                listener.onSystemEvent(context.getString(R.string.sys_charger));
                break;
            case Intent.ACTION_BATTERY_LOW:
                listener.onSystemEvent(context.getString(R.string.sys_battery_low));
                break;
            default:
                break;
        }
    }
}
