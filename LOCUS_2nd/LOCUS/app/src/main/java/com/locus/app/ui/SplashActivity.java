package com.locus.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.locus.app.R;
import com.locus.app.data.firebase.Session;

/** Launcher screen: shows the logo, then opens Login or the Dashboard (explicit intents). */
public class SplashActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable proceed = () -> {
        Class<?> next = Session.isLoggedIn(this) ? MainActivity.class : LoginActivity.class;
        startActivity(new Intent(this, next));
        finish();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        handler.postDelayed(proceed, 700);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(proceed);
        super.onDestroy();
    }
}
