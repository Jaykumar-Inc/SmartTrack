package io.smarttrack.android.data;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import java.util.List;

@Dao
public interface ReadingDao {
  @Query("SELECT * FROM readings ORDER BY receivedAt DESC LIMIT 500")
  LiveData<List<ReadingEntry>> observe();

  @Query("SELECT * FROM readings WHERE pendingSync=1 ORDER BY receivedAt LIMIT 100")
  List<ReadingEntry> pending();

  @Query(
      "SELECT * FROM readings WHERE pendingSync=1 AND deviceId IN (:devices) ORDER BY receivedAt"
          + " LIMIT 100")
  List<ReadingEntry> pendingForDevices(List<String> devices);

  @Query("SELECT COUNT(*) FROM readings WHERE pendingSync=1")
  int pendingCount();

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  long insert(ReadingEntry entry);

  @Query("UPDATE readings SET pendingSync=0 WHERE id=:id")
  void synced(String id);

  @Query("UPDATE readings SET reviewed=1 WHERE id=:id")
  void review(String id);

  @Query("DELETE FROM readings WHERE receivedAt<:before")
  void expire(long before);

  @Query(
      "DELETE FROM readings WHERE id IN (SELECT id FROM readings ORDER BY receivedAt DESC LIMIT -1"
          + " OFFSET 10000)")
  void trim();

  @Query("DELETE FROM readings")
  void clear();
}
