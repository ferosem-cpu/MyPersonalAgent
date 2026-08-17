package com.mypersonalagent.app.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val PERIODIC_WORK_NAME = "mypersonalagent-periodic-drive-backup"
private const val MANUAL_WORK_NAME = "mypersonalagent-manual-drive-backup"

/** Phase E: daily automatic backup (only runs when online - no point retrying offline) plus
 * an on-demand trigger for the "Back up now" Settings button. */
@Singleton
class BackupScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    private val workManager get() = WorkManager.getInstance(context)
    private val onlineOnly = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(onlineOnly)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun backupNow() {
        val request = OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(onlineOnly).build()
        workManager.enqueueUniqueWork(MANUAL_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
