package com.mypersonalagent.app.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mypersonalagent.app.data.local.TodoDao
import com.mypersonalagent.app.data.repo.TelegramRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Standalone (2026-08-12 pivot): replaces the reminder-check that used to run at the end
 * of a successful SyncWorker pull. Purely local, no laptop/agent-server dependency - the
 * only outbound network call is the optional, best-effort Telegram ping. */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val todoDao: TodoDao,
    private val telegram: TelegramRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        ReminderNotifier.checkAndNotify(applicationContext, todoDao) { todo ->
            val text = if (todo.project.isNotBlank()) {
                "\u23F0 ${todo.title} — due soon (${todo.project})"
            } else {
                "\u23F0 ${todo.title} — due soon"
            }
            telegram.sendMessage(text) // best-effort; local notification already fired regardless
        }
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
