package com.locus.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.locus.app.R;
import com.locus.app.data.firebase.ChatRepository;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivityChatsBinding;
import com.locus.app.databinding.ItemChatBinding;
import com.locus.app.model.ChatRules;
import com.locus.app.model.ChatSummary;

import java.util.ArrayList;
import java.util.List;

/** All my private conversations: request title, last message, unread count; tap to open. */
public class ChatsActivity extends AppCompatActivity {
    public static final String EXTRA_REQUEST_ID = "requestId";
    private ActivityChatsBinding b;
    private final List<ChatSummary> chats = new ArrayList<>();
    private Adapter adapter;
    private ChatRepository.Watch watch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityChatsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        adapter = new Adapter();
        b.rvChats.setLayoutManager(new LinearLayoutManager(this));
        b.rvChats.setAdapter(adapter);
        b.tvEmptyChats.setText(Session.canSyncOnline(this) ? R.string.chats_empty : R.string.chat_need_cloud);
    }

    @Override
    protected void onStart() {
        super.onStart();
        watch = ChatRepository.watchList(this, list -> {
            chats.clear();
            String only = getIntent().getStringExtra(EXTRA_REQUEST_ID); // chats about one post of mine
            for (ChatSummary c : list) {
                if (only == null || only.equals(c.requestId) || only.equals(ChatRules.requestIdOf(c.chatId))) chats.add(c);
            }
            adapter.notifyDataSetChanged();
            b.tvEmptyChats.setVisibility(chats.isEmpty() ? View.VISIBLE : View.GONE);
        });
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

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemChatBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            final ChatSummary c = chats.get(position);
            ItemChatBinding v = h.v;
            v.tvChatTitle.setText(c.title);
            String when = c.lastAt > 0 ? DateUtils.getRelativeTimeSpanString(c.lastAt, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS).toString() : "";
            v.tvChatWith.setText(getString(c.closed ? R.string.chats_with_closed : R.string.chats_with, c.otherName, when));
            v.tvChatLast.setText(c.last.isEmpty() ? getString(R.string.chats_no_messages) : c.last);
            v.tvUnread.setVisibility(c.unread > 0 ? View.VISIBLE : View.GONE);
            v.tvUnread.setText(String.valueOf(c.unread));
            v.getRoot().setOnClickListener(x -> startActivity(new Intent(ChatsActivity.this, ChatActivity.class)
                    .putExtra(ChatActivity.EXTRA_CHAT_ID, c.chatId)
                    .putExtra(ChatActivity.EXTRA_OTHER_UID, c.otherUid)
                    .putExtra(ChatActivity.EXTRA_OTHER_NAME, c.otherName)
                    .putExtra(ChatActivity.EXTRA_REQUEST_ID, c.requestId)
                    .putExtra(ChatActivity.EXTRA_TITLE, c.title)));
        }

        @Override
        public int getItemCount() {
            return chats.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ItemChatBinding v;

            Holder(ItemChatBinding v) {
                super(v.getRoot());
                this.v = v;
            }
        }
    }
}
