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

    @Query("DELETE FROM places WHERE category = :category")
    public abstract void deleteByCategory(String category);

    @Query("SELECT * FROM places WHERE category = :category ORDER BY distanceMeters ASC")
    public abstract List<Place> getByCategory(String category);

    @Transaction
    public void replaceCategory(String category, List<Place> places) {
        deleteByCategory(category);
        insertAll(places);
    }
}
