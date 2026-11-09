package com.locus.app.ui;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.locus.app.R;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.data.firebase.CommentRepository;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ItemCommentBinding;
import com.locus.app.databinding.SheetCommentsBinding;
import com.locus.app.model.Comment;
import com.locus.app.model.CommunityRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The comment section of one Community post: everybody who has the app can read it, comment, and REPLY to a
 * comment. Replies are shown under the comment they answer, indented. It follows the phone's copy of the shared
 * board live, so a comment from another phone appears by itself. Long-press a comment to delete it (the person who
 * wrote it, or the owner of the post).
 */
final class CommentsSheet {
    private CommentsSheet() {
    }

    /** The comment being answered right now (null = a new comment under the post). */
    private static final class ReplyState {
        Comment target;
    }

    static void show(AppCompatActivity activity, CommunityRequest post) {
        final SheetCommentsBinding b = SheetCommentsBinding.inflate(LayoutInflater.from(activity));
        final BottomSheetDialog dialog = new BottomSheetDialog(activity);
        dialog.setContentView(b.getRoot());
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        b.tvCommentsPost.setText(post.title);

        final ReplyState state = new ReplyState();
        final Runnable showReplyBar = () -> {
            if (state.target == null) {
                b.barReplying.setVisibility(View.GONE);
                b.etComment.setHint(R.string.comment_hint);
            } else {
                b.barReplying.setVisibility(View.VISIBLE);
                b.tvReplyingTo.setText(activity.getString(R.string.comment_replying_to, state.target.ownerName));
                b.etComment.setHint(R.string.reply_hint);
                b.etComment.requestFocus();
            }
        };
        b.btnCancelReply.setOnClickListener(v -> {
            state.target = null;
            showReplyBar.run();
        });

        final String me = Session.uid(activity);
        final boolean iOwnThePost = me.equals(post.ownerUid);
        final Adapter adapter = new Adapter(me, new Adapter.Actions() {
            @Override
            public void onReply(Comment c) {
                state.target = c;
                showReplyBar.run();
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                        activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(b.etComment, 0);
            }

            @Override
            public void onDelete(Comment c) {
                if (!(c.ownerUid.equals(me) || iOwnThePost)) return;
                new AlertDialog.Builder(activity)
                        .setMessage(R.string.comment_delete_confirm)
                        .setPositiveButton(R.string.action_delete, (d, w) -> {
                            if (state.target != null && state.target.id.equals(c.id)) {
                                state.target = null;
                                showReplyBar.run();
                            }
                            CommentRepository.delete(activity, c);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        b.recyclerComments.setLayoutManager(new LinearLayoutManager(activity));
        b.recyclerComments.setAdapter(adapter);

        final Observer<List<Comment>> observer = all -> {
            List<Comment> mine = new ArrayList<>();
            for (Comment c : all) if (post.id.equals(c.requestId)) mine.add(c);
            final Set<String> replyIds = new HashSet<>();
            final List<Comment> ordered = arrange(mine, replyIds);
            boolean grew = ordered.size() > adapter.getItemCount();
            adapter.setReplyIds(replyIds);
            adapter.submitList(ordered, () -> {
                if (grew && !ordered.isEmpty() && state.target == null) {
                    b.recyclerComments.scrollToPosition(ordered.size() - 1);
                }
            });
            b.tvNoComments.setVisibility(ordered.isEmpty() ? View.VISIBLE : View.GONE);
            b.tvCommentsTitle.setText(activity.getResources().getQuantityString(
                    R.plurals.comments_title, ordered.size(), ordered.size()));
        };
        final LiveData<List<Comment>> live = LocusDatabase.get(activity).commentDao().observeAll();
        live.observe(activity, observer);
        dialog.setOnDismissListener(d -> live.removeObserver(observer));

        final Runnable send = () -> {
            String text = b.etComment.getText() == null ? "" : b.etComment.getText().toString().trim();
            if (text.isEmpty()) return;
            CommentRepository.add(activity, post.id, text, state.target);
            b.etComment.setText("");
            state.target = null;
            showReplyBar.run();
        };
        b.btnSendComment.setOnClickListener(v -> send.run());
        b.etComment.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send.run();
                return true;
            }
            return false;
        });
        dialog.show();
    }

    /**
     * Comments straight under the post first (oldest first), each followed by its replies (oldest first).
     * A reply whose comment no longer exists is shown as an ordinary comment. {@code replyIds} receives the
     * ids of the comments that are drawn as replies (indented).
     */
    static List<Comment> arrange(List<Comment> all, Set<String> replyIds) {
        Set<String> ids = new HashSet<>();
        for (Comment c : all) ids.add(c.id);
        List<Comment> top = new ArrayList<>();
        Map<String, List<Comment>> replies = new HashMap<>();
        for (Comment c : all) { // `all` is already oldest first
            boolean isReply = c.parentId != null && !c.parentId.isEmpty() && ids.contains(c.parentId);
            if (isReply) {
                List<Comment> list = replies.get(c.parentId);
                if (list == null) replies.put(c.parentId, list = new ArrayList<>());
                list.add(c);
            } else {
                top.add(c);
            }
        }
        List<Comment> out = new ArrayList<>();
        for (Comment c : top) {
            out.add(c);
            List<Comment> r = replies.get(c.id);
            if (r != null) {
                for (Comment x : r) {
                    out.add(x);
                    replyIds.add(x.id);
                }
            }
        }
        return out;
    }

    private static final class Adapter extends ListAdapter<Comment, Adapter.Holder> {
        interface Actions {
            void onReply(Comment c);

            void onDelete(Comment c);
        }

        private final String me;
        private final Actions actions;
        private Set<String> replyIds = new HashSet<>();

        Adapter(String me, Actions actions) {
            super(new DiffUtil.ItemCallback<Comment>() {
                @Override
                public boolean areItemsTheSame(@NonNull Comment a, @NonNull Comment b) {
                    return a.id.equals(b.id);
                }

                @Override
                public boolean areContentsTheSame(@NonNull Comment a, @NonNull Comment b) {
                    return a.text.equals(b.text) && a.synced == b.synced && a.createdAt == b.createdAt
                            && String.valueOf(a.parentId).equals(String.valueOf(b.parentId));
                }
            });
            this.me = me;
            this.actions = actions;
        }

        void setReplyIds(Set<String> ids) {
            replyIds = ids;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemCommentBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Comment c = getItem(position);
            android.content.Context ctx = h.b.getRoot().getContext();
            boolean isReply = replyIds.contains(c.id);
            float dp = ctx.getResources().getDisplayMetrics().density;
            ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(h.b.getRoot().getLayoutParams());
            lp.leftMargin = isReply ? (int) (28 * dp) : 0;
            h.b.getRoot().setLayoutParams(lp);
            h.b.getRoot().setBackgroundResource(isReply ? R.color.locus_primary_light : 0);
            h.b.tvAuthor.setText(c.ownerUid.equals(me) ? ctx.getString(R.string.you_label) : c.ownerName);
            if (isReply && c.replyToName != null && !c.replyToName.isEmpty()) {
                h.b.tvReplyTo.setVisibility(View.VISIBLE);
                h.b.tvReplyTo.setText(ctx.getString(R.string.comment_in_reply_to, c.replyToName));
            } else {
                h.b.tvReplyTo.setVisibility(View.GONE);
            }
            h.b.tvText.setText(c.text);
            CharSequence ago = DateUtils.getRelativeTimeSpanString(c.createdAt, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS);
            h.b.tvTime.setText(c.synced ? ago : ctx.getString(R.string.comment_waiting, ago));
            h.b.tvReply.setOnClickListener(v -> actions.onReply(c));
            h.b.getRoot().setOnLongClickListener(v -> {
                actions.onDelete(c);
                return true;
            });
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final ItemCommentBinding b;

            Holder(ItemCommentBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }
    }
}
