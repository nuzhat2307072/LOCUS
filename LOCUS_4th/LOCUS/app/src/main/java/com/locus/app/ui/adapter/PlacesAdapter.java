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
                return a.name.equals(b.name) && a.distanceMeters == b.distanceMeters;
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
        Place p = getItem(position);
        h.b.tvName.setText(p.name);
        android.content.Context c = h.b.getRoot().getContext();
        h.b.tvMeta.setText(c.getString(com.locus.app.R.string.place_meta,
                Labels.category(c, p.category), GeoUtils.distanceText(c, p.distanceMeters)));
        h.b.tvTimes.setText(Labels.travelTimes(c, p.distanceMeters));
        h.b.getRoot().setOnClickListener(v -> listener.onOpen(p));
        h.b.btnNav.setOnClickListener(v -> listener.onNavigate(p));
        h.b.btnFav.setOnClickListener(v -> listener.onSave(p));
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemPlaceBinding b;

        Holder(ItemPlaceBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
