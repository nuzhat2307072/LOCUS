package com.locus.app.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.locus.app.model.Hazard;

import java.util.List;

@Dao
public abstract class HazardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void insert(Hazard hazard);

    @Query("SELECT * FROM hazards ORDER BY createdAt DESC")
    public abstract LiveData<List<Hazard>> observeAll();

    /** Only hazards newer than {@code since} matter for routing - old ones are assumed cleared. */
    @Query("SELECT * FROM hazards WHERE createdAt > :since ORDER BY createdAt DESC")
    public abstract LiveData<List<Hazard>> observeSince(long since);

    @Query("DELETE FROM hazards WHERE createdAt < :before AND synced = 1")
    public abstract void deleteOlderThan(long before);

    @Query("SELECT * FROM hazards WHERE synced = 0")
    public abstract List<Hazard> getUnsynced();

    @Query("SELECT COUNT(*) FROM hazards WHERE id = :id")
    public abstract int exists(String id);

    @Query("UPDATE hazards SET synced = 1 WHERE id = :id")
    public abstract void markSynced(String id);

    @Query("DELETE FROM hazards WHERE id = :id")
    public abstract void deleteById(String id);
}
