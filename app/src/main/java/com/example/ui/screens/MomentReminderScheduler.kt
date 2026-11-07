package com.example.ui.screens

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Recordatorios locales de OMNI Momentos, sin servicios remotos ni nuevas reglas Firebase. */
object MomentReminderScheduler {
    private const val PREFS = "omni_moment_reminders"
    private const val CHANNEL_ID = "omni_moment_reminders"

    fun schedule(context: Context, id: String, personName: String, occasion: String, date: String, hour: Int, minute: Int) {
        val payload = JSONObject().apply {
            put("id", id); put("personName", personName); put("occasion", occasion)
            put("date", date); put("hour", hour); put("minute", minute)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(id, payload.toString()).apply()
        scheduleNext(context, payload)
    }

    fun cancel(context: Context, id: String) {
        val intent = Intent(context, MomentReminderReceiver::class.java).putExtra("moment_id", id)
        val pending = PendingIntent.getBroadcast(context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id).apply()
    }

    fun restoreAll(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.forEach { (_, value) ->
            if (value is String) runCatching { scheduleNext(context, JSONObject(value)) }
        }
    }

    internal fun rescheduleNext(context: Context, id: String) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(id, null) ?: return
        runCatching { scheduleNext(context, JSONObject(raw)) }
    }

    private fun scheduleNext(context: Context, data: JSONObject) {
        val id = data.optString("id")
        if (id.isBlank()) return
        val parsed = runCatching {
            SimpleDateFormat("dd/MM/yyyy", Locale("es", "AR")).apply { isLenient = false }.parse(data.optString("date"))
        }.getOrNull() ?: return
        val now = Calendar.getInstance()
        val dateParts = Calendar.getInstance().apply { time = parsed }
        val next = Calendar.getInstance().apply {
            set(Calendar.YEAR, now.get(Calendar.YEAR))
            set(Calendar.MONTH, dateParts.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, dateParts.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, data.optInt("hour", 9).coerceIn(0, 23))
            set(Calendar.MINUTE, data.optInt("minute", 0).coerceIn(0, 59))
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (next.timeInMillis <= now.timeInMillis) next.add(Calendar.YEAR, 1)
        val intent = Intent(context, MomentReminderReceiver::class.java).putExtra("moment_id", id)
        val pending = PendingIntent.getBroadcast(context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.cancel(pending)
        // Alarma compatible con Android 12+: no exige permiso especial de alarmas exactas.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
    }

    internal fun notificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "OMNI Momentos · Recordatorios", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Avisos de cumpleaños, aniversarios y fechas especiales" })
        }
    }

    internal fun channelId() = CHANNEL_ID
}

class MomentReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("moment_id") ?: return
        val raw = context.getSharedPreferences("omni_moment_reminders", Context.MODE_PRIVATE).getString(id, null) ?: return
        val data = runCatching { JSONObject(raw) }.getOrNull() ?: return
        MomentReminderScheduler.notificationChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT < 33 || manager.areNotificationsEnabled()) {
            val openApp = Intent(context, com.example.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val contentIntent = PendingIntent.getActivity(context, id.hashCode(), openApp,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val title = data.optString("occasion", "Fecha especial") + " · " + data.optString("personName", "Tu momento")
            val notification = NotificationCompat.Builder(context, MomentReminderScheduler.channelId())
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("OMNI Momentos")
                .setContentText("Hoy: $title")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Hoy es ${data.optString("date")}: $title. ${data.optString("greeting", "")}"))
                .setContentIntent(contentIntent).setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
            manager.notify(id.hashCode(), notification)
        }
        MomentReminderScheduler.rescheduleNext(context, id)
    }
}

class MomentReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) MomentReminderScheduler.restoreAll(context)
    }
}
