package com.mypersonalagent.app.notifications

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val PERIODIC_WORK_NAME = "mypersonalagent-periodic-reminders"
private const val IMMEDIATE_WORK_NAME = "mypersonalagent-immediate-reminder-check"

/**
 * Standalone (2026-08-12 pivot): replaces SyncScheduler. Reminders no longer piggyback on a
 * server sync - this runs the same due-window check (ReminderWorker -> ReminderNotifier)
 * purely locally, no network involved.
 */
@Singleton
class ReminderScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    private val workManager get() = WorkManager.getInstance(context)

    /** Call once at app startup. Re-checks due todos every ~15 min, entirely offline. */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Call after creating/updating a todo so a newly-due item doesn't wait up to 15 min. */
    fun requestImmediateCheck() {
        val request = OneTimeWorkRequestBuilder<ReminderWorker>().build()
        workManager.enqueueUniqueWork(IMMEDIATE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
