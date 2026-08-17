package com.mypersonalagent.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mypersonalagent.app.backup.BackupScheduler
import com.mypersonalagent.app.notifications.ReminderScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var reminderScheduler: ReminderScheduler
    @Inject lateinit var backupScheduler: BackupScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        reminderScheduler.schedulePeriodic()
        backupScheduler.schedulePeriodic()
    }
}
