package com.mypersonalagent.app.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mypersonalagent.app.data.repo.DriveBackupRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Phase E: runs backupNow() on a WorkManager thread, for both the daily periodic schedule
 * and the manual "Back up now" trigger. Skips quietly (success, no-op) if not signed in yet -
 * this is opt-in, not something that should nag or fail loudly on every device that hasn't
 * configured it. */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val driveBackupRepository: DriveBackupRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!driveBackupRepository.isSignedIn()) return Result.success()
        return try {
            driveBackupRepository.backupNow()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
