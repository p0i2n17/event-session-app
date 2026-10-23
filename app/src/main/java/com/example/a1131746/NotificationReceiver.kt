package com.example.a1131746

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventName = intent.getStringExtra("EVENT_NAME") ?: "展場活動"
        val location = intent.getStringExtra("LOCATION") ?: "展館"
        val startTime = intent.getStringExtra("START_TIME") ?: "10:00"
        val type = intent.getStringExtra("NOTIFY_TYPE") ?: "BEFORE"

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "event_reminder_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "場次活動提醒", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val title = if (type == "WEEK") "🔥 場次倒數一週！" else "🚨 明天就是場次啦！"
        val content = if (type == "WEEK") {
            "下週有【$eventName】活動喔！地點在 $location，預計 $startTime 開始，相機電池充飽了嗎？"
        } else {
            "別忘了明天【$eventName】於 $startTime 開始！地點在 $location，明早現場見！"
        }

        val tapIntent = Intent(context, EventListActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            (eventName + type).hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify((eventName + type).hashCode(), notification)
    }
}