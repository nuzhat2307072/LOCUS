package com.locus.app.ui.adapter;

import android.graphics.Paint;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.locus.app.databinding.ItemRequestBinding;
import com.locus.app.model.CommunityRequest;
import com.locus.app.model.RequestType;

/** RecyclerView adapter for community requests (click = details, long click = owner actions). */
public class RequestsAdapter extends ListAdapter<CommunityRequest, RequestsAdapter.Holder> {

    public interface Listener {
        void onClick(CommunityRequest r);

        void onLongClick(CommunityRequest r);

        /** The comment section of this post was tapped. */
        void onComments(CommunityRequest r);

        /** "Message": start a private chat with the owner of this request. */
        void onMessage(CommunityRequest r);
    }

    private final Listener listener;
    private java.util.Map<String, Integer> commentCounts = new java.util.HashMap<>();

    public RequestsAdapter(Listener listener) {
        super(new DiffUtil.ItemCallback<CommunityRequest>() {
            @Override
            public boolean areItemsTheSame(@NonNull CommunityRequest a, @NonNull CommunityRequest b) {
                return a.id.equals(b.id);
            }

            @Override
            public boolean areContentsTheSame(@NonNull CommunityRequest a, @NonNull CommunityRequest b) {
                return a.status.equals(b.status) && a.title.equals(b.title)
                        && a.description.equals(b.description) && a.synced == b.synced;
            }
        });
        this.listener = listener;
    }

    /** How many comments each post has (post id -> count); the cards refresh themselves. */
    public void setCommentCounts(java.util.Map<String, Integer> counts) {
        if (counts.equals(commentCounts)) return;
        commentCounts = counts;
        notifyItemRangeChanged(0, getItemCount());
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemRequestBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        CommunityRequest r = getItem(position);
        android.content.Context c = h.b.getRoot().getContext();
        h.b.tvType.setText(c.getString(RequestType.fromName(r.type).labelRes).toUpperCase(java.util.Locale.getDefault()));
        if (r.isSos()) { // an SOS is urgent: shown in red
            h.b.tvType.setText(c.getString(com.locus.app.R.string.sos_label));
            h.b.tvType.setBackgroundColor(androidx.core.content.ContextCompat.getColor(c, com.locus.app.R.color.locus_danger));
            h.b.tvType.setTextColor(androidx.core.content.ContextCompat.getColor(c, com.locus.app.R.color.white));
        } else {
            h.b.tvType.setBackgroundColor(androidx.core.content.ContextCompat.getColor(c, com.locus.app.R.color.locus_primary_light));
            h.b.tvType.setTextColor(androidx.core.content.ContextCompat.getColor(c, com.locus.app.R.color.locus_primary_dark));
        }
        h.b.tvTitle.setText(r.title);
        h.b.tvDesc.setText(r.description);
        boolean done = CommunityRequest.DONE.equals(r.status);
        h.b.tvTitle.setPaintFlags(done ? h.b.tvTitle.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                : h.b.tvTitle.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        CharSequence ago = DateUtils.getRelativeTimeSpanString(r.createdAt, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS);
        boolean mine = r.ownerUid != null && r.ownerUid.equals(com.locus.app.data.firebase.Session.uid(c));
        h.b.tvMeta.setText(c.getString(com.locus.app.R.string.request_meta,
                mine ? c.getString(com.locus.app.R.string.you_label) : r.ownerName, ago,
                c.getString(done ? com.locus.app.R.string.status_fulfilled : com.locus.app.R.string.status_open),
                r.synced ? "" : c.getString(com.locus.app.R.string.waiting_sync)));
        Integer n = commentCounts.get(r.id);
        h.b.tvComments.setText(n == null || n == 0 ? c.getString(com.locus.app.R.string.comments_add)
                : c.getResources().getQuantityString(com.locus.app.R.plurals.comments_count, n, n));
        h.b.tvComments.setOnClickListener(v -> listener.onComments(r));
        // somebody else's open request can be answered privately
        h.b.btnMessage.setVisibility(!mine && !done ? android.view.View.VISIBLE : android.view.View.GONE);
        h.b.btnMessage.setOnClickListener(v -> listener.onMessage(r));
        h.b.getRoot().setOnClickListener(v -> listener.onClick(r));
        h.b.getRoot().setOnLongClickListener(v -> {
            listener.onLongClick(r);
            return true;
        });
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemRequestBinding b;

        Holder(ItemRequestBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
