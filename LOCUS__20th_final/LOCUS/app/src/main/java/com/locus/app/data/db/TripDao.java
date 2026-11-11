package com.locus.app.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.locus.app.model.Trip;

import java.util.List;

@Dao
public abstract class TripDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void insert(Trip trip);

    @Query("SELECT * FROM planned_trips ORDER BY startAt ASC")
    public abstract List<Trip> getAll();

    @Query("SELECT * FROM planned_trips WHERE id = :id")
    public abstract Trip get(String id);

    @Query("SELECT * FROM planned_trips WHERE startAt >= :from AND startAt < :to ORDER BY startAt ASC")
    public abstract List<Trip> between(long from, long to);

    /** Trips that have not started and whose reminder has not been shown yet. */
    @Query("SELECT * FROM planned_trips WHERE reminded = 0 AND startAt > :now ORDER BY startAt ASC")
    public abstract List<Trip> awaitingReminder(long now);

    @Query("SELECT * FROM planned_trips WHERE startAt > :now ORDER BY startAt ASC LIMIT 1")
    public abstract Trip next(long now);

    @Query("UPDATE planned_trips SET reminded = 1 WHERE id = :id")
    public abstract void markReminded(String id);

    @Query("DELETE FROM planned_trips WHERE id = :id")
    public abstract void delete(String id);
}
