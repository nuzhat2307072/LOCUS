package com.locus.app.ui;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
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
import java.util.List;

/**
 * The comment section of one Community post: everybody who has the app can read it and add a comment.
 * It follows the phone's copy of the shared board live, so a comment from another phone appears by itself.
 * Long-press a comment to delete it (the person who wrote it, or the owner of the post).
 */
final class CommentsSheet {
    private CommentsSheet() {
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

        final String me = Session.uid(activity);
        final boolean iOwnThePost = me.equals(post.ownerUid);
        final Adapter adapter = new Adapter(me, c -> {
            if (!(c.ownerUid.equals(me) || iOwnThePost)) return;
            new AlertDialog.Builder(activity)
                    .setMessage(R.string.comment_delete_confirm)
                    .setPositiveButton(R.string.action_delete, (d, w) -> CommentRepository.delete(activity, c))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
        final LinearLayoutManager lm = new LinearLayoutManager(activity);
        b.recyclerComments.setLayoutManager(lm);
        b.recyclerComments.setAdapter(adapter);

        final Observer<List<Comment>> observer = all -> {
            List<Comment> mine = new ArrayList<>();
            for (Comment c : all) if (post.id.equals(c.requestId)) mine.add(c);
            boolean grew = mine.size() > adapter.getItemCount();
            adapter.submitList(mine, () -> {
                if (grew && !mine.isEmpty()) b.recyclerComments.scrollToPosition(mine.size() - 1); // newest
            });
            b.tvNoComments.setVisibility(mine.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
            b.tvCommentsTitle.setText(activity.getResources().getQuantityString(
                    R.plurals.comments_title, mine.size(), mine.size()));
        };
        final androidx.lifecycle.LiveData<List<Comment>> live = LocusDatabase.get(activity).commentDao().observeAll();
        live.observe(activity, observer);
        dialog.setOnDismissListener(d -> live.removeObserver(observer));

        final Runnable send = () -> {
            String text = b.etComment.getText() == null ? "" : b.etComment.getText().toString().trim();
            if (text.isEmpty()) return;
            CommentRepository.add(activity, post.id, text);
            b.etComment.setText("");
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

    private static final class Adapter extends ListAdapter<Comment, Adapter.Holder> {
        interface LongClick {
            void on(Comment c);
        }

        private final String me;
        private final LongClick longClick;

        Adapter(String me, LongClick longClick) {
            super(new DiffUtil.ItemCallback<Comment>() {
                @Override
                public boolean areItemsTheSame(@NonNull Comment a, @NonNull Comment b) {
                    return a.id.equals(b.id);
                }

                @Override
                public boolean areContentsTheSame(@NonNull Comment a, @NonNull Comment b) {
                    return a.text.equals(b.text) && a.synced == b.synced && a.createdAt == b.createdAt;
                }
            });
            this.me = me;
            this.longClick = longClick;
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
            h.b.tvAuthor.setText(c.ownerUid.equals(me) ? ctx.getString(R.string.you_label) : c.ownerName);
            h.b.tvText.setText(c.text);
            CharSequence ago = DateUtils.getRelativeTimeSpanString(c.createdAt, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS);
            h.b.tvTime.setText(c.synced ? ago : ctx.getString(R.string.comment_waiting, ago));
            h.b.getRoot().setOnLongClickListener(v -> {
                longClick.on(c);
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
