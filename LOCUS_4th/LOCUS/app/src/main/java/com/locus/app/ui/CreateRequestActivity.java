package com.locus.app.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.locus.app.R;
import com.locus.app.data.firebase.CommunityRepository;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivityCreateRequestBinding;
import com.locus.app.model.CommunityRequest;
import com.locus.app.model.RequestType;
import com.locus.app.util.LocationHelper;
import com.locus.app.util.PermissionHelper;

import java.util.UUID;
import java.util.regex.Pattern;

/** Form to create a community request, with validation. */
public class CreateRequestActivity extends AppCompatActivity {
    public static final String EXTRA_TYPE = "type";
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9][0-9 \\-]{5,16}$");

    private ActivityCreateRequestBinding b;

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), r -> submit());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityCreateRequestBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        String[] typeNames = new String[RequestType.values().length];
        for (int i = 0; i < typeNames.length; i++) typeNames[i] = getString(RequestType.values()[i].labelRes);
        b.spType.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, typeNames));
        String preset = getIntent().getStringExtra(EXTRA_TYPE);
        if (preset != null) b.spType.setSelection(RequestType.fromName(preset).ordinal());

        b.btnSubmit.setOnClickListener(v -> {
            if (b.chkLocation.isChecked() && !PermissionHelper.hasLocation(this)) {
                permissionLauncher.launch(PermissionHelper.locationPermissions());
            } else {
                submit();
            }
        });
    }

    private void submit() {
        b.tilTitle.setError(null);
        b.tilDesc.setError(null);
        b.tilContact.setError(null);

        final String title = text(b.etTitle.getText());
        final String desc = text(b.etDesc.getText());
        final String contact = text(b.etContact.getText());
        if (title.length() < 3) {
            b.tilTitle.setError(getString(R.string.err_title_short));
            return;
        }
        if (desc.length() < 5) {
            b.tilDesc.setError(getString(R.string.err_details_missing));
            return;
        }
        if (!PHONE.matcher(contact).matches()) {
            b.tilContact.setError(getString(R.string.err_phone_invalid));
            return;
        }

        b.btnSubmit.setEnabled(false);
        b.progress.setVisibility(View.VISIBLE);

        final CommunityRequest r = new CommunityRequest();
        r.id = UUID.randomUUID().toString();
        r.type = RequestType.values()[Math.max(0, b.spType.getSelectedItemPosition())].name();
        r.title = title;
        r.description = desc;
        r.contact = contact;
        r.ownerUid = Session.uid(this);
        r.ownerName = Session.displayName(this);
        r.status = CommunityRequest.OPEN;
        r.createdAt = System.currentTimeMillis();

        if (b.chkLocation.isChecked() && PermissionHelper.hasLocation(this)) {
            LocationHelper.getLocation(this, loc -> {
                if (loc != null) {
                    r.hasLocation = true;
                    r.lat = loc.getLatitude();
                    r.lon = loc.getLongitude();
                }
                save(r);
            });
        } else {
            save(r);
        }
    }

    private void save(CommunityRequest r) {
        CommunityRepository.add(this, r, () -> {
            Toast.makeText(this, Session.canSyncOnline(this)
                    ? getString(R.string.request_posted) : getString(R.string.request_saved_local), Toast.LENGTH_LONG).show();
            finish();
        });
    }

    private static String text(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
