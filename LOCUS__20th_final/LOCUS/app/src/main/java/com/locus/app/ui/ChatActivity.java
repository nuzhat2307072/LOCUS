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
    public static final String EXTRA_REQUEST_ID = "requestId";

    private ActivityChatBinding b;
    private String chatId, otherUid, otherName, me;
    /** Who and what this chat is about: used to keep my chats-list rows complete. */
    private com.locus.app.model.ChatSummary thread;
    private boolean canLoadMore = true;
    private boolean loadingEarlier;
    private boolean initialBatch = true;
    private final java.util.Set<String> imagesLoading = new java.util.HashSet<>();
    private final Runnable markReadLater = () -> ChatRepository.markRead(this, chatId);
    private final List<ChatMessage> messages = new ArrayList<>();
    private Adapter adapter;
    private ChatRepository.Watch watch;
    private long otherSeen;
    private boolean closed;
    private boolean blocked;
    private Runnable stopBlockWatch;
    private final android.util.LruCache<String, android.graphics.Bitmap> photos = new android.util.LruCache<>(12);
    private final androidx.activity.result.ActivityResultLauncher<String> pickPhoto = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.GetContent(), this::sendPhoto);
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
        thread = new com.locus.app.model.ChatSummary();
        thread.chatId = chatId;
        thread.otherUid = otherUid;
        thread.otherName = otherName;
        thread.title = getIntent().getStringExtra(EXTRA_TITLE) == null ? "" : getIntent().getStringExtra(EXTRA_TITLE);
        String reqId = getIntent().getStringExtra(EXTRA_REQUEST_ID);
        thread.requestId = reqId == null || reqId.isEmpty() ? ChatRules.requestIdOf(chatId) : reqId;
        ChatRepository.ensureEntries(this, thread); // repairs a half-empty row in the chats list

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

        b.btnEarlier.setOnClickListener(v -> loadEarlier());
        b.btnPhoto.setOnClickListener(v -> {
            if (!closed && !blocked) pickPhoto.launch("image/*");
        });
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

    /** A picked picture is shrunk on a background thread, then sent as a message. */
    private void sendPhoto(android.net.Uri uri) {
        if (uri == null || closed || blocked) return;
        Toast.makeText(this, R.string.chat_photo_sending, Toast.LENGTH_SHORT).show();
        final android.content.ContentResolver resolver = getContentResolver();
        com.locus.app.util.AppExecutors.io().execute(() -> {
            final String data = com.locus.app.util.ImageCodec.encode(resolver, uri);
            com.locus.app.util.AppExecutors.main().post(() -> {
                if (isFinishing()) return;
                if (data == null) {
                    Toast.makeText(this, R.string.chat_photo_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                ChatRepository.sendPhoto(this, thread, data, error -> {
                    if (error != null && !isFinishing()) {
                        Toast.makeText(this, R.string.chat_send_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            });
        });
    }

    private void applyInputState() {
        boolean off = closed || blocked;
        b.tvClosed.setText(blocked ? R.string.chat_you_blocked : R.string.chat_closed);
        b.tvClosed.setVisibility(off ? View.VISIBLE : View.GONE);
        b.inputRow.setVisibility(off ? View.GONE : View.VISIBLE);
        b.quickScroll.setVisibility(off ? View.GONE : View.VISIBLE);
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.menu_chat, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        menu.findItem(R.id.action_block).setTitle(blocked ? R.string.chat_unblock : R.string.chat_block);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == R.id.action_block) {
            if (blocked) {
                ChatRepository.setBlocked(this, otherUid, false);
            } else {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.chat_block_q, otherName))
                        .setMessage(R.string.chat_block_info)
                        .setPositiveButton(R.string.chat_block, (d, w) -> ChatRepository.setBlocked(this, otherUid, true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
            return true;
        }
        if (item.getItemId() == R.id.action_report) {
            final String[] reasons = {getString(R.string.report_spam), getString(R.string.report_abuse),
                    getString(R.string.report_scam), getString(R.string.report_other)};
            final String[] codes = {"spam", "abuse", "scam", "other"};
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.chat_report)
                    .setItems(reasons, (d, which) -> ChatRepository.report(this, chatId, otherUid, codes[which], err ->
                            Toast.makeText(this, err == null ? R.string.chat_reported : R.string.chat_send_failed,
                                    Toast.LENGTH_LONG).show()))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void send(String text) {
        if (closed || blocked || ChatRules.prepare(text) == null) return;
        sendTyping(false);
        ChatRepository.send(this, thread, text, error -> {
            if (error != null && !isFinishing()) Toast.makeText(this, R.string.chat_send_failed, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        ChatRepository.setOpenChat(chatId);
        ChatRepository.markRead(this, chatId);
        stopBlockWatch = ChatRepository.watchBlocked(this, otherUid, isBlocked -> {
            blocked = isBlocked;
            applyInputState();
            invalidateOptionsMenu();
        });
        watch = ChatRepository.watch(this, chatId, otherUid, new ChatRepository.ChatListener() {
            @Override
            public void onMessage(ChatMessage m) {
                upsert(m);
                if (!m.from.equals(me)) scheduleMarkRead();
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
                applyInputState();
            }
        });
    }

    @Override
    protected void onStop() {
        sendTyping(false);
        handler.removeCallbacks(stopTyping);
        handler.removeCallbacks(markReadLater);
        if (watch != null) {
            watch.stop();
            watch = null;
        }
        ChatRepository.setOpenChat(null);
        if (stopBlockWatch != null) {
            stopBlockWatch.run();
            stopBlockWatch = null;
        }
        super.onStop();
    }

    /** Many messages arriving together (opening a long chat) cause ONE "seen" write, not one each. */
    private void scheduleMarkRead() {
        handler.removeCallbacks(markReadLater);
        handler.postDelayed(markReadLater, 800);
    }

    private boolean nearBottom() {
        LinearLayoutManager lm = (LinearLayoutManager) b.rvMessages.getLayoutManager();
        return lm == null || messages.isEmpty() || lm.findLastVisibleItemPosition() >= messages.size() - 2;
    }

    /** Adds a message, or replaces it (the server time replaces the estimate) and puts it where its time says. */
    private void upsert(ChatMessage m) {
        boolean stick = initialBatch || m.from.equals(me) || nearBottom();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).id.equals(m.id)) {
                messages.remove(i);
                int pos = ChatRules.insertIndex(messages, m);
                messages.add(pos, m);
                if (pos == i) adapter.notifyItemChanged(i);
                else adapter.notifyDataSetChanged();
                return;
            }
        }
        int pos = ChatRules.insertIndex(messages, m);
        messages.add(pos, m);
        adapter.notifyItemInserted(pos);
        if (stick) b.rvMessages.scrollToPosition(messages.size() - 1);
        updateEmpty();
        if (initialBatch) {
            handler.removeCallbacks(endInitial);
            handler.postDelayed(endInitial, 1200); // after the first page has arrived, new messages scroll normally
        }
        updateEarlierButton();
    }

    private final Runnable endInitial = () -> initialBatch = false;

    private void updateEarlierButton() {
        b.btnEarlier.setVisibility(canLoadMore && messages.size() >= ChatRules.PAGE_SIZE ? View.VISIBLE : View.GONE);
    }

    /** History: the page of messages before the oldest one shown. */
    private void loadEarlier() {
        if (loadingEarlier || messages.isEmpty()) return;
        loadingEarlier = true;
        b.btnEarlier.setEnabled(false);
        ChatRepository.loadEarlier(chatId, messages.get(0).at, older -> {
            if (isFinishing() || isDestroyed()) return;
            loadingEarlier = false;
            b.btnEarlier.setEnabled(true);
            LinearLayoutManager lm = (LinearLayoutManager) b.rvMessages.getLayoutManager();
            int first = lm == null ? 0 : lm.findFirstVisibleItemPosition();
            View top = lm == null ? null : lm.findViewByPosition(first);
            int offset = top == null ? 0 : top.getTop();
            int added = 0;
            for (ChatMessage m : older) {
                boolean known = false;
                for (ChatMessage x : messages) {
                    if (x.id.equals(m.id)) {
                        known = true;
                        break;
                    }
                }
                if (known) continue;
                messages.add(ChatRules.insertIndex(messages, m), m);
                added++;
            }
            if (older.size() < ChatRules.PAGE_SIZE + 1) canLoadMore = false; // nothing before this page
            adapter.notifyDataSetChanged();
            if (lm != null && added > 0) lm.scrollToPositionWithOffset(first + added, offset);
            if (added == 0) Toast.makeText(this, R.string.chat_no_earlier, Toast.LENGTH_SHORT).show();
            updateEarlierButton();
        });
    }

    /** Shows a photo: from memory, or fetched once (and cached on disk) when its bubble first appears. */
    private void bindPhoto(final ItemChatMessageBinding v, final ChatMessage m) {
        v.ivPhoto.setTag(m.id);
        android.graphics.Bitmap bmp = photos.get(m.id);
        if (bmp != null) {
            v.ivPhoto.setImageBitmap(bmp);
            v.ivPhoto.setOnClickListener(x -> showPhoto(bmp));
            return;
        }
        v.ivPhoto.setImageDrawable(null);
        v.ivPhoto.setMinimumHeight(160);
        v.ivPhoto.setOnClickListener(null);
        if (!imagesLoading.add(m.id)) return;
        final java.util.function.Consumer<String> decode = data -> com.locus.app.util.AppExecutors.io().execute(() -> {
            final android.graphics.Bitmap decoded = data == null ? null : com.locus.app.util.ImageCodec.decode(data);
            com.locus.app.util.AppExecutors.main().post(() -> {
                imagesLoading.remove(m.id);
                if (decoded == null || isDestroyed()) return;
                photos.put(m.id, decoded);
                if (m.id.equals(v.ivPhoto.getTag())) {
                    v.ivPhoto.setImageBitmap(decoded);
                    v.ivPhoto.setOnClickListener(x -> showPhoto(decoded));
                }
            });
        });
        if (!m.image.isEmpty()) decode.accept(m.image);
        else ChatRepository.loadImage(chatId, m.id, decode::accept);
    }

    private void showPhoto(android.graphics.Bitmap bmp) {
        if (bmp == null) return;
        android.widget.ImageView big = new android.widget.ImageView(this);
        big.setImageBitmap(bmp);
        big.setAdjustViewBounds(true);
        new MaterialAlertDialogBuilder(this).setView(big).setPositiveButton(android.R.string.ok, null).show();
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
            v.tvText.setVisibility(m.text.isEmpty() ? View.GONE : View.VISIBLE);
            if (!m.hasImage) {
                v.ivPhoto.setVisibility(View.GONE);
                v.ivPhoto.setOnClickListener(null);
                v.ivPhoto.setTag(null);
            } else {
                v.ivPhoto.setVisibility(View.VISIBLE);
                bindPhoto(v, m);
            }
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
                        .setPositiveButton(R.string.action_delete, (d, w) -> ChatRepository.deleteMessage(chatId, m.id, m.hasImage))
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
