package com.locus.app.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.locus.app.databinding.ItemPlaceBinding;
import com.locus.app.model.Place;
import com.locus.app.util.GeoUtils;
import com.locus.app.util.Labels;

/** RecyclerView adapter + ViewHolder for nearby places. DiffUtil animates list refreshes. */
public class PlacesAdapter extends ListAdapter<Place, PlacesAdapter.Holder> {

    public interface Listener {
        void onOpen(Place p);

        void onNavigate(Place p);

        void onSave(Place p);
    }

    private final Listener listener;

    public PlacesAdapter(Listener listener) {
        super(new DiffUtil.ItemCallback<Place>() {
            @Override
            public boolean areItemsTheSame(@NonNull Place a, @NonNull Place b) {
                return a.id.equals(b.id);
            }

            @Override
            public boolean areContentsTheSame(@NonNull Place a, @NonNull Place b) {
                return a.name.equals(b.name) && a.distanceMeters == b.distanceMeters
                        && java.util.Objects.equals(a.openingHours, b.openingHours)
                        && java.util.Objects.equals(a.phone, b.phone);
            }
        });
        this.listener = listener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemPlaceBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        bind(h.b, getItem(position), listener);
    }

    /** Fills one place card (list row, or the card shown over the map). */
    public static void bind(ItemPlaceBinding b, Place p, Listener listener) {
        android.content.Context c = b.getRoot().getContext();
        b.tvName.setText(p.name);
        b.tvMeta.setText(c.getString(com.locus.app.R.string.place_meta,
                Labels.category(c, p.category), GeoUtils.distanceText(c, p.distanceMeters)));
        b.tvTimes.setText(Labels.travelTimes(c, p.distanceMeters));
        b.tvStatus.setVisibility(android.view.View.GONE); // no "open now" status in the list
        b.getRoot().setOnClickListener(v -> listener.onOpen(p));
        b.btnNav.setOnClickListener(v -> listener.onNavigate(p));
        b.btnFav.setOnClickListener(v -> listener.onSave(p));
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemPlaceBinding b;

        Holder(ItemPlaceBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
