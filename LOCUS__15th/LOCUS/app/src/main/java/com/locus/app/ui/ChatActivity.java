package com.locus.app.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.locus.app.R;
import com.locus.app.data.firebase.ChatRepository;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivityChatBinding;
import com.locus.app.databinding.ItemChatMessageBinding;
import com.locus.app.model.ChatMessage;
import com.locus.app.model.ChatRules;

import java.util.ArrayList;
import java.util.List;

/** One private conversation about a request: bubbles, send, "seen" ticks, typing indicator, delete own messages. */
public class ChatActivity extends AppCompatActivity {
    public static final String EXTRA_CHAT_ID = "chatId";
    public static final String EXTRA_OTHER_UID = "otherUid";
    public static final String EXTRA_OTHER_NAME = "otherName";
    public static final String EXTRA_TITLE = "title";

    private ActivityChatBinding b;
    private String chatId, otherUid, otherName, me;
    private final List<ChatMessage> messages = new ArrayList<>();
    private Adapter adapter;
    private ChatRepository.Watch watch;
    private long otherSeen;
    private boolean closed;
    private boolean typingSent;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stopTyping = () -> sendTyping(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        chatId = getIntent().getStringExtra(EXTRA_CHAT_ID);
        otherUid = getIntent().getStringExtra(EXTRA_OTHER_UID);
        otherName = getIntent().getStringExtra(EXTRA_OTHER_NAME);
        if (chatId == null || otherUid == null || !Session.canSyncOnline(this)) {
            Toast.makeText(this, R.string.chat_need_cloud, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (otherName == null) otherName = "";
        me = Session.uid(this);

        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        b.toolbar.setTitle(getIntent().getStringExtra(EXTRA_TITLE));
        showSubtitle(false);

        adapter = new Adapter();
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        b.rvMessages.setLayoutManager(lm);
        b.rvMessages.setAdapter(adapter);

        // quick replies
        int[] quick = {R.string.chat_quick_on_my_way, R.string.chat_quick_still_needed, R.string.chat_quick_can_help, R.string.chat_quick_thanks};
        for (int q : quick) {
            Chip chip = new Chip(this);
            chip.setText(q);
            chip.setOnClickListener(v -> send(getString(q)));
            b.quickReplies.addView(chip);
        }

        b.btnSend.setOnClickListener(v -> {
            send(b.etMessage.getText().toString());
            b.etMessage.setText("");
        });
        b.etMessage.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int c, int d) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() == 0) {
                    sendTyping(false);
                    return;
                }
                sendTyping(true);
                handler.removeCallbacks(stopTyping);
                handler.postDelayed(stopTyping, 3000);
            }
        });
        updateEmpty();
    }

    private void showSubtitle(boolean typing) {
        b.toolbar.setSubtitle(typing ? getString(R.string.chat_typing, otherName) : otherName);
    }

    private void sendTyping(boolean on) {
        if (typingSent == on) return;
        typingSent = on;
        ChatRepository.setTyping(this, chatId, on);
    }

    private void send(String text) {
        if (closed || ChatRules.prepare(text) == null) return;
        sendTyping(false);
        ChatRepository.send(this, chatId, otherUid, text, error -> {
            if (error != null && !isFinishing()) Toast.makeText(this, R.string.chat_send_failed, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        ChatRepository.setOpenChat(chatId);
        ChatRepository.markRead(this, chatId);
        watch = ChatRepository.watch(this, chatId, otherUid, new ChatRepository.ChatListener() {
            @Override
            public void onMessage(ChatMessage m) {
                upsert(m);
                if (!m.from.equals(me)) ChatRepository.markRead(ChatActivity.this, chatId);
            }

            @Override
            public void onMessageRemoved(String id) {
                for (int i = 0; i < messages.size(); i++) {
                    if (messages.get(i).id.equals(id)) {
                        messages.remove(i);
                        adapter.notifyItemRemoved(i);
                        break;
                    }
                }
                updateEmpty();
            }

            @Override
            public void onOtherSeen(long at) {
                otherSeen = at;
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onOtherTyping(boolean typing) {
                showSubtitle(typing);
            }

            @Override
            public void onClosed(boolean isClosed) {
                closed = isClosed;
                b.tvClosed.setVisibility(isClosed ? View.VISIBLE : View.GONE);
                b.inputRow.setVisibility(isClosed ? View.GONE : View.VISIBLE);
                b.quickScroll.setVisibility(isClosed ? View.GONE : View.VISIBLE);
            }
        });
    }

    @Override
    protected void onStop() {
        sendTyping(false);
        handler.removeCallbacks(stopTyping);
        if (watch != null) {
            watch.stop();
            watch = null;
        }
        ChatRepository.setOpenChat(null);
        super.onStop();
    }

    private void upsert(ChatMessage m) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).id.equals(m.id)) {
                messages.set(i, m);
                adapter.notifyItemChanged(i);
                return;
            }
        }
        int pos = messages.size();
        while (pos > 0 && messages.get(pos - 1).at > m.at) pos--;
        messages.add(pos, m);
        adapter.notifyItemInserted(pos);
        b.rvMessages.scrollToPosition(messages.size() - 1);
        updateEmpty();
    }

    private void updateEmpty() {
        b.tvEmptyChat.setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemChatMessageBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            final ChatMessage m = messages.get(position);
            boolean mine = m.from.equals(me);
            ItemChatMessageBinding v = h.v;
            v.tvText.setText(m.text);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.bubble.getLayoutParams();
            lp.gravity = mine ? Gravity.END : Gravity.START;
            v.bubble.setLayoutParams(lp);
            v.bubble.setBackgroundResource(mine ? R.drawable.bg_bubble_me : R.drawable.bg_bubble_other);
            int text = mine ? ContextCompat.getColor(ChatActivity.this, R.color.white)
                    : ContextCompat.getColor(ChatActivity.this, R.color.locus_text);
            v.tvText.setTextColor(text);
            v.tvMeta.setTextColor(text);
            String time = DateUtils.formatDateTime(ChatActivity.this, m.at, DateUtils.FORMAT_SHOW_TIME);
            if (mine) {
                time += "  " + getString(ChatRules.isSeen(m.at, otherSeen) ? R.string.chat_seen : R.string.chat_sent);
            }
            v.tvMeta.setText(time);
            v.bubble.setOnLongClickListener(mine ? x -> {
                new MaterialAlertDialogBuilder(ChatActivity.this)
                        .setMessage(R.string.chat_delete_q)
                        .setPositiveButton(R.string.action_delete, (d, w) -> ChatRepository.deleteMessage(chatId, m.id))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return true;
            } : null);
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ItemChatMessageBinding v;

            Holder(ItemChatMessageBinding v) {
                super(v.getRoot());
                this.v = v;
            }
        }
    }
}
