package com.locus.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.locus.app.databinding.ActivityLifecycleLogBinding;
import com.locus.app.databinding.ItemLifecycleBinding;
import com.locus.app.util.LifecycleLogger;

import java.util.ArrayList;
import java.util.List;

/** Shows every Activity lifecycle callback recorded since the app started (newest first). */
public class LifecycleLogActivity extends AppCompatActivity {
    private ActivityLifecycleLogBinding b;
    private LogAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityLifecycleLogBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        adapter = new LogAdapter();
        b.recycler.setLayoutManager(new LinearLayoutManager(this));
        b.recycler.setAdapter(adapter);
        b.btnClear.setOnClickListener(v -> {
            LifecycleLogger.clear();
            refresh();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(); // shows onPause/onStop of the previous screen and onCreate/onStart/onResume of this one
    }

    private void refresh() {
        adapter.lines = LifecycleLogger.snapshot();
        adapter.notifyDataSetChanged();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private static class LogAdapter extends RecyclerView.Adapter<LogAdapter.Holder> {
        List<String> lines = new ArrayList<>();

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemLifecycleBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            h.b.tvLine.setText(lines.get(position));
        }

        @Override
        public int getItemCount() {
            return lines.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final ItemLifecycleBinding b;

            Holder(ItemLifecycleBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }
    }
}
