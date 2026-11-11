package com.locus.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

import com.locus.app.R;
import com.locus.app.data.firebase.ChatRepository;
import com.locus.app.model.CommunityRequest;

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
}
