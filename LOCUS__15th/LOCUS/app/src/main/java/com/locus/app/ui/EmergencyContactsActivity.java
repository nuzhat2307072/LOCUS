package com.locus.app.ui;

import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.locus.app.R;
import com.locus.app.databinding.ActivityEmergencyContactsBinding;
import com.locus.app.databinding.ItemContactBinding;
import com.locus.app.util.EmergencyContacts;
import com.locus.app.util.ExternalIntents;

import java.util.List;

/** Emergency contact manager (Settings): who gets the SMS when SOS is pressed. Up to five people. */
public class EmergencyContactsActivity extends AppCompatActivity {
    private ActivityEmergencyContactsBinding b;

    private final ActivityResultLauncher<Intent> picker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() != RESULT_OK || r.getData() == null || r.getData().getData() == null) return;
                // the picker grants read access to the one contact chosen: no contacts permission needed
                try (Cursor c = getContentResolver().query(r.getData().getData(), new String[]{
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
                    if (c != null && c.moveToFirst()) save(c.getString(0), c.getString(1));
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityEmergencyContactsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        b.btnPick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
            try {
                picker.launch(i);
            } catch (Exception e) {
                ExternalIntents.start(this, i);
            }
        });
        b.btnAddNumber.setOnClickListener(v -> askNumber());
        refresh();
    }

    private void askNumber() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText name = new EditText(this);
        name.setHint(R.string.hint_contact_name);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        final EditText phone = new EditText(this);
        phone.setHint(R.string.hint_contact_phone);
        phone.setInputType(InputType.TYPE_CLASS_PHONE);
        box.addView(name);
        box.addView(phone);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.emergency_add_number)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_save, (d, w) -> save(name.getText().toString(), phone.getText().toString()))
                .show();
    }

    private void save(String name, String phone) {
        if (EmergencyContacts.load(this).size() >= EmergencyContacts.MAX) {
            Toast.makeText(this, getString(R.string.emergency_full, EmergencyContacts.MAX), Toast.LENGTH_LONG).show();
        } else if (!EmergencyContacts.add(this, name, phone)) {
            Toast.makeText(this, R.string.emergency_bad_number, Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    private void refresh() {
        b.listContacts.removeAllViews();
        List<EmergencyContacts.Contact> list = EmergencyContacts.load(this);
        b.tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < list.size(); i++) {
            final int index = i;
            EmergencyContacts.Contact c = list.get(i);
            ItemContactBinding row = ItemContactBinding.inflate(getLayoutInflater(), b.listContacts, false);
            row.tvName.setText(c.name);
            row.tvPhone.setText(c.phone);
            row.btnDelete.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.emergency_remove_q, c.name))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.action_delete, (d, w) -> {
                        EmergencyContacts.remove(this, index);
                        refresh();
                    })
                    .show());
            b.listContacts.addView(row.getRoot());
        }
        b.btnPick.setEnabled(list.size() < EmergencyContacts.MAX);
        b.btnAddNumber.setEnabled(list.size() < EmergencyContacts.MAX);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
