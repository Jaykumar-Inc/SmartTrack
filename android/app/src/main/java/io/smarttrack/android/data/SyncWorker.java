package io.smarttrack.android.data;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.google.gson.*;

public class SyncWorker extends Worker {
  public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
    super(context, params);
  }

  @NonNull
  @Override
  public Result doWork() {
    Vault vault = new Vault(getApplicationContext());
    if (!vault.preferences().getBoolean("sync", false) || vault.token() == null)
      return Result.success();
    SyncEngine.Outcome outcome = SyncEngine.run(vault, LocalDatabase.get(getApplicationContext()));
    return outcome.retry() ? Result.retry() : Result.success();
  }

  public static void enqueue(Context context) {
    OneTimeWorkRequest request =
        new OneTimeWorkRequest.Builder(SyncWorker.class)
            .setConstraints(
                new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    WorkManager.getInstance(context)
        .enqueueUniqueWork("smarttrack-sync", ExistingWorkPolicy.KEEP, request);
  }
}
