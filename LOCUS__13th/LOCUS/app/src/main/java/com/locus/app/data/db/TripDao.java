package com.locus.app.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.locus.app.model.Trip;

import java.util.List;

@Dao
public abstract class TripDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void insert(Trip t);

    @Query("SELECT * FROM trips ORDER BY startedAt DESC")
    public abstract LiveData<List<Trip>> observeAll();

    @Query("SELECT * FROM trips ORDER BY startedAt DESC")
    public abstract List<Trip> getAll();

    @Query("DELETE FROM trips WHERE id = :id")
    public abstract void delete(String id);

    @Query("DELETE FROM trips")
    public abstract void deleteAll();
}
