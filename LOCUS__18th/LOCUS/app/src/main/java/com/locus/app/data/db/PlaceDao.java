package com.locus.app.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.locus.app.model.Place;

import java.util.List;

@Dao
public abstract class PlaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void insertAll(List<Place> places);

    @Query("DELETE FROM places WHERE searchKey = :searchKey")
    public abstract void deleteBySearch(String searchKey);

    @Query("SELECT * FROM places WHERE searchKey = :searchKey ORDER BY distanceMeters ASC")
    public abstract List<Place> getBySearch(String searchKey);

    /** Housekeeping: cached searches older than a week are dropped. */
    @Query("DELETE FROM places WHERE fetchedAt < :before")
    public abstract void deleteOlderThan(long before);

    /** Offline fallback: anything saved by an earlier search whose name, type or search text matches. */
    @Query("SELECT * FROM places WHERE name LIKE :like OR category LIKE :like OR searchKey LIKE :like "
            + "ORDER BY fetchedAt DESC LIMIT 200")
    public abstract List<Place> searchCached(String like);

    @Transaction
    public void replaceSearch(String searchKey, List<Place> places) {
        deleteBySearch(searchKey);
        insertAll(places);
    }
}
