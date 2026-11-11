package com.locus.app.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.locus.app.model.OfflineArea;

import java.util.List;

@Dao
public abstract class OfflineAreaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void insert(OfflineArea area);

    @Query("SELECT * FROM offline_areas ORDER BY updatedAt DESC")
    public abstract LiveData<List<OfflineArea>> observeAll();

    @Query("SELECT * FROM offline_areas")
    public abstract List<OfflineArea> getAll();

    @Query("UPDATE offline_areas SET name = :name WHERE id = :id")
    public abstract void rename(String id, String name);

    @Query("DELETE FROM offline_areas WHERE id = :id")
    public abstract void delete(String id);
}
