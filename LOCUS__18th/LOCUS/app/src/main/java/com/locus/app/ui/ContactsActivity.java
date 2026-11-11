package com.locus.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.locus.app.R;
import com.locus.app.data.firebase.ChatRepository;
import com.locus.app.data.firebase.ContactRepository;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivityContactsBinding;
import com.locus.app.databinding.ItemContactBinding;
import com.locus.app.model.Contact;
import com.locus.app.model.ContactRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * My contacts: people who asked to message me (Accept / Decline) and people I am connected with (Message).
 * Contacts are saved on both accounts, so they can message each other directly any time.
 */
public class ContactsActivity extends AppCompatActivity {
    private ActivityContactsBinding b;
    private ChatRepository.Watch watch;
    private final List<Object> rows = new ArrayList<>(); // String header | ContactRequest | Contact
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityContactsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        adapter = new Adapter();
        b.rvContacts.setLayoutManager(new LinearLayoutManager(this));
        b.rvContacts.setAdapter(adapter);
        b.tvEmptyContacts.setText(Session.canSyncOnline(this) ? R.string.contacts_empty : R.string.chat_need_cloud);
        b.tvEmptyContacts.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        watch = ContactRepository.watch(this, (incoming, contacts) -> runOnUiThread(() -> show(incoming, contacts)));
    }

    @Override
    protected void onStop() {
        if (watch != null) {
            watch.stop();
            watch = null;
        }
        super.onStop();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void show(List<ContactRequest> incoming, List<Contact> contacts) {
        rows.clear();
        if (!incoming.isEmpty()) {
            rows.add(getString(R.string.contacts_requests_header, incoming.size()));
            rows.addAll(incoming);
        }
        if (!contacts.isEmpty()) {
            rows.add(getString(R.string.contacts_header, contacts.size()));
            rows.addAll(contacts);
        }
        adapter.notifyDataSetChanged();
        b.tvEmptyContacts.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void toast(String error, int okText) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, error == null ? getString(okText) : getString(R.string.contact_failed), Toast.LENGTH_SHORT).show();
    }

    private void openChat(Contact c) {
        ChatRepository.openDirect(this, c.uid, c.name, (chatId, error) -> {
            if (isFinishing() || isDestroyed()) return;
            if (chatId == null) {
                Toast.makeText(this, R.string.chat_refused, Toast.LENGTH_LONG).show();
                return;
            }
            startActivity(new Intent(this, ChatActivity.class)
                    .putExtra(ChatActivity.EXTRA_CHAT_ID, chatId)
                    .putExtra(ChatActivity.EXTRA_OTHER_UID, c.uid)
                    .putExtra(ChatActivity.EXTRA_OTHER_NAME, c.name)
                    .putExtra(ChatActivity.EXTRA_REQUEST_ID, "dm")
                    .putExtra(ChatActivity.EXTRA_TITLE, getString(R.string.chat_direct_title)));
        });
    }

    private void confirmRemove(final Contact c) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.contact_remove_title)
                .setMessage(getString(R.string.contact_remove_text, c.name))
                .setPositiveButton(R.string.contact_remove, (d, w) ->
                        ContactRepository.remove(this, c, err -> runOnUiThread(() -> toast(err, R.string.contact_removed))))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemContactBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Object row = rows.get(position);
            ItemContactBinding v = h.v;
            if (row instanceof String) {
                v.tvHeader.setVisibility(View.VISIBLE);
                v.tvHeader.setText((String) row);
                v.card.setVisibility(View.GONE);
                return;
            }
            v.tvHeader.setVisibility(View.GONE);
            v.card.setVisibility(View.VISIBLE);
            if (row instanceof ContactRequest) {
                final ContactRequest r = (ContactRequest) row;
                v.tvName.setText(r.fromName.isEmpty() ? getString(R.string.chat_someone) : r.fromName);
                String when = r.at > 0 ? DateUtils.getRelativeTimeSpanString(r.at, System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS).toString() : "";
                v.tvAbout.setText(r.postTitle.isEmpty() ? when : getString(R.string.contact_request_about, r.postTitle) + "  " + when);
                v.tvNote.setVisibility(r.note.isEmpty() ? View.GONE : View.VISIBLE);
                v.tvNote.setText("“" + r.note + "”");
                v.btnPrimary.setVisibility(View.VISIBLE);
                v.btnPrimary.setText(R.string.contact_accept);
                v.btnPrimary.setOnClickListener(x -> ContactRepository.accept(ContactsActivity.this, r,
                        err -> runOnUiThread(() -> toast(err, R.string.contact_accepted_toast))));
                v.btnSecondary.setVisibility(View.VISIBLE);
                v.btnSecondary.setText(R.string.contact_decline);
                v.btnSecondary.setOnClickListener(x -> ContactRepository.decline(ContactsActivity.this, r,
                        err -> runOnUiThread(() -> toast(err, R.string.contact_declined_toast))));
                v.card.setOnClickListener(null);
                v.card.setClickable(false);
            } else {
                final Contact c = (Contact) row;
                v.tvName.setText(c.name.isEmpty() ? getString(R.string.chat_someone) : c.name);
                v.tvAbout.setText(c.postTitle.isEmpty() ? getString(R.string.contact_saved)
                        : getString(R.string.contact_met_about, c.postTitle));
                v.tvNote.setVisibility(View.GONE);
                v.btnPrimary.setVisibility(View.VISIBLE);
                v.btnPrimary.setText(R.string.contact_message);
                v.btnPrimary.setOnClickListener(x -> openChat(c));
                v.btnSecondary.setVisibility(View.VISIBLE);
                v.btnSecondary.setText(R.string.contact_remove);
                v.btnSecondary.setOnClickListener(x -> confirmRemove(c));
                v.card.setClickable(true);
                v.card.setOnClickListener(x -> openChat(c));
            }
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ItemContactBinding v;

            Holder(ItemContactBinding v) {
                super(v.getRoot());
                this.v = v;
            }
        }
    }
}
