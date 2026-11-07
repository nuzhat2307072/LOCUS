package com.locus.app.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.locus.app.model.Comment;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Dao
public abstract class CommentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void insert(Comment comment);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void insertAll(List<Comment> comments);

    /** Every comment, oldest first: the screen groups them by post (and counts them for the cards). */
    @Query("SELECT * FROM comments ORDER BY createdAt ASC")
    public abstract LiveData<List<Comment>> observeAll();

    @Query("SELECT * FROM comments WHERE synced = 0")
    public abstract List<Comment> getUnsynced();

    @Query("UPDATE comments SET synced = 1 WHERE id = :id")
    public abstract void markSynced(String id);

    @Query("DELETE FROM comments WHERE id = :id")
    public abstract void deleteById(String id);

    @Query("DELETE FROM comments WHERE requestId = :requestId")
    public abstract void deleteForRequest(String requestId);

    @Query("DELETE FROM comments WHERE synced = 1")
    public abstract void deleteSynced();

    @Query("SELECT id FROM comments WHERE synced = 0")
    public abstract List<String> unsyncedIds();

    /** The phone's copy becomes equal to the shared board; comments not uploaded yet are kept. */
    @Transaction
    public void replaceSynced(List<Comment> fromServer) {
        Set<String> pending = new HashSet<>(unsyncedIds());
        deleteSynced();
        java.util.ArrayList<Comment> keep = new java.util.ArrayList<>();
        for (Comment c : fromServer) if (!pending.contains(c.id)) keep.add(c);
        insertAll(keep);
    }
}
