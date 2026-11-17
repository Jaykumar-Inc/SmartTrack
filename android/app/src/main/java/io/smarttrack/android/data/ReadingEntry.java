package io.smarttrack.android.data;

import androidx.annotation.NonNull;
import androidx.room.*;

@Entity(
    tableName = "readings",
    indices = {@Index(value = {"deviceId", "receivedAt"}), @Index(value = {"receivedAt"})})
public class ReadingEntry {
  @PrimaryKey @NonNull public String id = "";
  @NonNull public String deviceId = "";
  @NonNull public String payload = "";
  public long receivedAt;
  public boolean pendingSync;
  public boolean reviewed;
}
