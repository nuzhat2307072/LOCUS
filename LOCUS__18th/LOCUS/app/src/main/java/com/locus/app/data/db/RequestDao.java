package com.locus.app.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.locus.app.model.CommunityRequest;

import java.util.List;

@Dao
public abstract class RequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void insert(CommunityRequest request);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void insertAll(List<CommunityRequest> requests);

    @Query("SELECT * FROM requests ORDER BY createdAt DESC")
    public abstract LiveData<List<CommunityRequest>> observeAll();

    @Query("SELECT * FROM requests WHERE synced = 0")
    public abstract List<CommunityRequest> getUnsynced();

    @Query("UPDATE requests SET synced = 1 WHERE id = :id")
    public abstract void markSynced(String id);

    @Query("UPDATE requests SET status = :status, synced = 0 WHERE id = :id")
    public abstract void setStatus(String id, String status);

    @Query("DELETE FROM requests WHERE id = :id")
    public abstract void deleteById(String id);

    @Query("DELETE FROM requests WHERE synced = 1")
    public abstract void deleteSynced();

    /** Replace everything that came from the server; local not-yet-synced rows are kept. */
    @Query("SELECT id FROM requests WHERE synced = 0")
    public abstract List<String> unsyncedIds();

    /**
     * Make the phone's copy equal to the shared board. A post of this phone that has a change not uploaded
     * yet (new, or status changed offline) is kept as it is - the server copy must not overwrite it.
     */
    @Transaction
    public void replaceSynced(List<CommunityRequest> fromServer) {
        java.util.Set<String> pending = new java.util.HashSet<>(unsyncedIds());
        deleteSynced();
        List<CommunityRequest> keep = new java.util.ArrayList<>();
        for (CommunityRequest r : fromServer) {
            if (!pending.contains(r.id)) keep.add(r);
        }
        insertAll(keep);
    }
}
