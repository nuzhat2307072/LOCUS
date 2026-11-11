package com.locus.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

import com.locus.app.R;
import com.locus.app.data.firebase.ChatRepository;
import com.locus.app.data.firebase.ContactRepository;
import com.locus.app.model.CommunityRequest;
import com.locus.app.model.ContactRules;

/** "Message" on a request: opens (or creates) the private chat, or explains in plain words why it cannot. */
public final class ChatLauncher {
    private ChatLauncher() {
    }

    public static void start(final Activity a, final CommunityRequest r) {
        Toast.makeText(a, R.string.chat_opening, Toast.LENGTH_SHORT).show();
        ChatRepository.open(a, r, (chatId, error) -> {
            if (a.isFinishing() || a.isDestroyed()) return;
            if (chatId == null) {
                String e = error == null ? "" : error;
                if ("needcontact".equals(e)) {
                    askForContact(a, r);
                    return;
                }
                int msg = "cloud".equals(e) ? R.string.chat_need_cloud
                        : "timeout".equals(e) ? R.string.chat_timeout
                        : "own".equals(e) ? R.string.chat_own_request
                        : "nodb".equals(e) ? R.string.chat_need_db
                        : "noowner".equals(e) ? R.string.chat_noowner
                        : "offline".equals(e) ? R.string.chat_offline
                        : "rules".equals(e) ? R.string.chat_rules : R.string.chat_refused;
                String text = a.getString(msg);
                if (e.startsWith("other:") && e.length() > 6) text += "\n(" + e.substring(6) + ")";
                Toast.makeText(a, text, Toast.LENGTH_LONG).show();
                return;
            }
            a.startActivity(new Intent(a, ChatActivity.class)
                    .putExtra(ChatActivity.EXTRA_CHAT_ID, chatId)
                    .putExtra(ChatActivity.EXTRA_OTHER_UID, r.ownerUid)
                    .putExtra(ChatActivity.EXTRA_OTHER_NAME, r.ownerName == null ? "" : r.ownerName)
                    .putExtra(ChatActivity.EXTRA_REQUEST_ID, r.id)
                    .putExtra(ChatActivity.EXTRA_TITLE, r.title));
        });
    }

    /** Not contacts yet: show where the request stands, or offer to send one (with an optional note). */
    private static void askForContact(final Activity a, final CommunityRequest r) {
        ContactRepository.stateWith(a, r.ownerUid, (state, error) -> a.runOnUiThread(() -> {
            if (a.isFinishing() || a.isDestroyed()) return;
            if (error != null) {
                Toast.makeText(a, "cloud".equals(error) ? R.string.chat_need_cloud
                        : "offline".equals(error) ? R.string.chat_offline : R.string.chat_refused, Toast.LENGTH_LONG).show();
                return;
            }
            if (state == ContactRules.CONTACT) {
                start(a, r); // accepted in the meantime
            } else if (state == ContactRules.PENDING) {
                new androidx.appcompat.app.AlertDialog.Builder(a)
                        .setTitle(R.string.contact_pending_title)
                        .setMessage(R.string.contact_pending_text)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNegativeButton(R.string.contact_cancel_request, (d, w) ->
                                ContactRepository.cancelRequest(a, r.ownerUid, err -> a.runOnUiThread(() ->
                                        Toast.makeText(a, err == null ? R.string.contact_cancelled : R.string.contact_failed,
                                                Toast.LENGTH_SHORT).show())))
                        .show();
            } else if (state == ContactRules.DECLINED) {
                Toast.makeText(a, R.string.contact_declined, Toast.LENGTH_LONG).show();
            } else {
                showRequestDialog(a, r);
            }
        }));
    }

    private static void showRequestDialog(final Activity a, final CommunityRequest r) {
        final android.widget.EditText note = new android.widget.EditText(a);
        note.setHint(R.string.contact_note_hint);
        note.setMaxLines(4);
        note.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(ContactRules.MAX_NOTE)});
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(a);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(note);
        String owner = r.ownerName == null || r.ownerName.isEmpty() ? a.getString(R.string.chat_someone) : r.ownerName;
        new androidx.appcompat.app.AlertDialog.Builder(a)
                .setTitle(R.string.contact_request_dialog_title)
                .setMessage(a.getString(R.string.contact_request_dialog_text, owner))
                .setView(box)
                .setPositiveButton(R.string.contact_send, (d, w) ->
                        ContactRepository.sendRequest(a, r, note.getText().toString(), err -> a.runOnUiThread(() -> {
                            if (a.isFinishing() || a.isDestroyed()) return;
                            Toast.makeText(a, err == null ? R.string.contact_sent
                                    : "rules".equals(err) ? R.string.chat_rules : R.string.contact_failed, Toast.LENGTH_LONG).show();
                        })))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
