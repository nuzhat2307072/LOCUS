package com.locus.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.locus.app.R;
import com.locus.app.data.firebase.EmailConfig;
import com.locus.app.data.firebase.FirebaseConnector;
import com.locus.app.data.firebase.PasswordChangeNotifier;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivityConnectFirebaseBinding;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.ExternalIntents;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Connect the app to your own Firebase project without rebuilding it: choose the google-services.json that the
 * Firebase console gave you. After that e-mail password reset, online accounts and the shared Community board work.
 */
public class ConnectFirebaseActivity extends AppCompatActivity {
    private ActivityConnectFirebaseBinding b;

    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onFilePicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityConnectFirebaseBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        b.btnChoose.setOnClickListener(v -> picker.launch(new String[]{"application/json", "application/octet-stream", "text/plain", "*/*"}));
        b.btnConsole.setOnClickListener(v -> ExternalIntents.start(this,
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://console.firebase.google.com"))));
        showStatus();
        setupEmail();
    }

    private void setupEmail() {
        b.etServiceId.setText(EmailConfig.serviceId(this));
        b.etTemplateId.setText(EmailConfig.templateId(this));
        b.etPublicKey.setText(EmailConfig.publicKey(this));
        String me = Session.email(this);
        if (me != null) b.etTestTo.setText(me);
        b.btnSaveEmail.setOnClickListener(v -> {
            saveEmail();
            showEmailResult(getString(R.string.email_saved));
        });
        b.btnTestEmail.setOnClickListener(v -> {
            saveEmail();
            final String to = text(b.etTestTo);
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(to).matches()) {
                showEmailResult(getString(R.string.err_valid_email));
                return;
            }
            if (!EmailConfig.isConfigured(this)) {
                showEmailResult(getString(R.string.email_fill_all));
                return;
            }
            b.btnTestEmail.setEnabled(false);
            showEmailResult(getString(R.string.email_sending));
            AppExecutors.io().execute(() -> {
                final String error = PasswordChangeNotifier.sendTest(this, to);
                AppExecutors.main().post(() -> {
                    if (isDestroyed()) return;
                    b.btnTestEmail.setEnabled(true);
                    showEmailResult(error == null ? getString(R.string.email_test_ok, to)
                            : getString(R.string.email_test_fail, error));
                });
            });
        });
    }

    private void saveEmail() {
        EmailConfig.save(this, text(b.etServiceId), text(b.etTemplateId), text(b.etPublicKey));
    }

    private static String text(android.widget.EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private void showEmailResult(String s) {
        b.tvEmailResult.setVisibility(View.VISIBLE);
        b.tvEmailResult.setText(s);
    }

    private void showStatus() {
        String id = FirebaseConnector.projectId(this);
        b.tvStatus.setText(id == null ? getString(R.string.connect_fb_status_off)
                : getString(R.string.connect_fb_status_on, id));
    }

    private void onFilePicked(Uri uri) {
        if (uri == null) return;
        b.tvResult.setVisibility(View.GONE);
        AppExecutors.io().execute(() -> {
            String error = null;
            boolean running = false;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("no stream");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 500_000) throw new FirebaseConnector.BadFile(getString(R.string.connect_fb_err_json));
                }
                running = FirebaseConnector.connect(this, out.toString("UTF-8"));
            } catch (FirebaseConnector.BadFile e) {
                error = e.getMessage();
            } catch (Exception e) {
                error = getString(R.string.connect_fb_err_read);
            }
            final String err = error;
            final boolean now = running;
            AppExecutors.main().post(() -> {
                if (isDestroyed()) return;
                b.tvResult.setVisibility(View.VISIBLE);
                if (err != null) {
                    b.tvResult.setText(err);
                } else {
                    b.tvResult.setText(now ? getString(R.string.connect_fb_ok, FirebaseConnector.projectId(this))
                            : getString(R.string.connect_fb_ok_restart));
                    showStatus();
                }
            });
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
