package com.locus.app.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.lifecycle.LiveData;

/** LiveData<Boolean> that is true while the device has an internet-capable network (connectivity monitoring). */
public class ConnectivityMonitor extends LiveData<Boolean> {
    private final ConnectivityManager cm;

    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            postValue(true);
        }

        @Override
        public void onLost(Network network) {
            postValue(isOnline(cm));
        }
    };

    public ConnectivityMonitor(Context context) {
        cm = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public static boolean isOnline(Context context) {
        return isOnline((ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE));
    }

    private static boolean isOnline(ConnectivityManager cm) {
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    @Override
    protected void onActive() {
        setValue(isOnline(cm));
        cm.registerDefaultNetworkCallback(callback);
    }

    @Override
    protected void onInactive() {
        try {
            cm.unregisterNetworkCallback(callback);
        } catch (IllegalArgumentException ignored) {
            // already unregistered
        }
    }
}
