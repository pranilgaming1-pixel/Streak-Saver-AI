package com.streaksaver.ai

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class StreakReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val dao = AppDatabase.get(applicationContext).friendDao()
            val today = LocalDate.now().toString()
            val now = LocalTime.now()
            dao.getAll().forEach { friend ->
                val due = now.hour > friend.reminderHour ||
                    (now.hour == friend.reminderHour && now.minute >= friend.reminderMinute)
                if (friend.reminderEnabled && due && friend.lastNotificationDate != today) {
                    showReminder(friend)
                    dao.markNotified(friend.id, today)
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun showReminder(friend: FriendEntity) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Streak reminders", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            applicationContext, friend.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = "Remember " + friend.displayName
        val body = if (friend.username.isBlank()) {
            "Time for your TikTok check-in."
        } else {
            "Your reminder for @" + friend.username + " is due."
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body + " Open Streak Saver to prepare a message."))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(friend.id.toInt(), notification)
    }

    companion object {
        private const val CHANNEL_ID = "streak_reminders"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<StreakReminderWorker>(
                15, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "streak-reminder-check",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
