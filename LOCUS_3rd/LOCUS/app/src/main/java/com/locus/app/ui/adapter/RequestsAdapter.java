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
    }

    private final Listener listener;

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

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemRequestBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        CommunityRequest r = getItem(position);
        h.b.tvType.setText(RequestType.fromName(r.type).label.toUpperCase());
        h.b.tvTitle.setText(r.title);
        h.b.tvDesc.setText(r.description);
        boolean done = CommunityRequest.DONE.equals(r.status);
        h.b.tvTitle.setPaintFlags(done ? h.b.tvTitle.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                : h.b.tvTitle.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        CharSequence ago = DateUtils.getRelativeTimeSpanString(r.createdAt, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS);
        h.b.tvMeta.setText("By " + r.ownerName + "  -  " + ago + "  -  " + (done ? "Fulfilled" : "Open")
                + (r.synced ? "" : "  -  waiting to sync"));
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
