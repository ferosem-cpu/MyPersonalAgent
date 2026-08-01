package com.mypersonalagent.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.mypersonalagent.app.MainActivity
import com.mypersonalagent.app.data.local.TodoDao
import com.mypersonalagent.app.data.local.TodoEntity
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

private const val CHANNEL_ID = "reminders"

/**
 * Best-effort local reminders for todos due soon. Telegram (server-side, via scheduler.py)
 * remains the guaranteed escalation channel for overdue items - this only covers the
 * "coming up soon" window so the phone doesn't stay silent between syncs.
 */
object ReminderNotifier {

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "To-dos that are due soon" }
        manager.createNotificationChannel(channel)
    }

    /** Call after every successful sync pull. Notifies for open todos entering their due window. */
    suspend fun checkAndNotify(context: Context, todoDao: TodoDao) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)

        val now = Instant.now()
        for (todo in todoDao.openTodos()) {
            val effectiveDue = todo.snoozeUntil ?: todo.due ?: continue
            if (todo.notifiedForDue == effectiveDue) continue

            val due = parseInstant(effectiveDue) ?: continue
            val windowStart = due.minusSeconds(todo.remindBeforeMin * 60L)
            val windowEnd = due.plusSeconds(60 * 60) // stay quiet on old overdue items - Telegram nags those
            if (now.isBefore(windowStart) || now.isAfter(windowEnd)) continue

            notify(context, todo)
            todoDao.setNotifiedForDue(todo.id, effectiveDue)
        }
    }

    private fun parseInstant(raw: String): Instant? = try {
        OffsetDateTime.parse(raw).toInstant()
    } catch (e: DateTimeParseException) {
        try {
            Instant.parse(raw)
        } catch (e2: DateTimeParseException) {
            null
        }
    }

    private fun notify(context: Context, todo: TodoEntity) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, todo.id.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(todo.title)
            .setContentText(
                if (todo.project.isNotBlank()) "Due soon - ${todo.project}" else "Due soon",
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.notify(todo.id.hashCode(), notification)
    }
}
