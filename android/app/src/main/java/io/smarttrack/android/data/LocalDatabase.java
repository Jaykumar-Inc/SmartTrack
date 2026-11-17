package io.smarttrack.android.data;

import android.content.Context;
import androidx.room.*;

@Database(
    entities = {ReadingEntry.class},
    version = 1,
    exportSchema = false)
public abstract class LocalDatabase extends RoomDatabase {
  private static volatile LocalDatabase instance;

  public abstract ReadingDao readings();

  public static LocalDatabase get(Context context) {
    if (instance == null)
      synchronized (LocalDatabase.class) {
        if (instance == null)
          instance =
              Room.databaseBuilder(
                      context.getApplicationContext(), LocalDatabase.class, "smarttrack.db")
                  .build();
      }
    return instance;
  }
}
