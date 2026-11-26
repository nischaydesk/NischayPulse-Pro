package com.prince.aiassistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ScheduledTaskReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getIntExtra("task_id", -1)
        val taskCommand = intent.getStringExtra("task_command").orEmpty()
        val isDaily = intent.getBooleanExtra("is_daily", false)

        if (taskCommand.isBlank()) return

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NischayPulse:ScheduledTaskWakeLock"
        )
        wakeLock?.acquire(15_000L)

        try {
            val prefs = context.getSharedPreferences(WhatsAppHelperAccessibilityService.PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(WhatsAppHelperAccessibilityService.KEY_ORB_ENABLED, true)
                .putBoolean(WhatsAppHelperAccessibilityService.KEY_SHOW_ORB, true)
                .putBoolean(WhatsAppHelperAccessibilityService.KEY_ACTIVE, true)
                .putBoolean("from_schedule", true)
                .putString(WhatsAppHelperAccessibilityService.KEY_GOAL, taskCommand)
                .apply()

            if (isDaily && taskId != -1) {
                val nextCal = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
                scheduleExactAlarm(context, taskId, taskCommand, nextCal.timeInMillis, true)
            } else if (taskId != -1) {
                removeTaskFromStorage(context, taskId)
            }
        } finally {
            try {
                if (wakeLock?.isHeld == true) wakeLock.release()
            } catch (_: Exception) { }
        }
    }

    companion object {
        private const val SCHEDULE_PREFS = "nischay_scheduled_tasks"
        private const val KEY_LIST = "tasks_json"

        data class ScheduledItem(
            val id: Int,
            val command: String,
            val triggerTimeMillis: Long,
            val isDaily: Boolean
        ) {
            fun formattedTime(): String {
                val fmt = SimpleDateFormat("dd MMM, hh:mm:ss a", Locale.getDefault())
                val prefix = if (isDaily) "रोज़ " else ""
                return prefix + fmt.format(Date(triggerTimeMillis))
            }
        }

        fun addAndScheduleTask(
            context: Context,
            command: String,
            hour: Int,
            minute: Int,
            delaySeconds: Int = 0,
            isDaily: Boolean = false
        ): ScheduledItem {
            val triggerMillis = if (delaySeconds > 0) {
                System.currentTimeMillis() + (delaySeconds * 1000L)
            } else {
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                    set(Calendar.MINUTE, minute.coerceIn(0, 59))
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= System.currentTimeMillis()) {
                        add(Calendar.DAY_OF_YEAR, 1)
                    }
                }.timeInMillis
            }

            val id = (System.currentTimeMillis() % 100000).toInt()
            val item = ScheduledItem(id, command, triggerMillis, isDaily)

            val list = getAllTasks(context).toMutableList()
            list.add(item)
            saveAllTasks(context, list)

            scheduleExactAlarm(context, id, command, triggerMillis, isDaily)
            return item
        }

        fun scheduleExactAlarm(
            context: Context,
            id: Int,
            command: String,
            triggerAtMillis: Long,
            isDaily: Boolean
        ) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ScheduledTaskReceiver::class.java).apply {
                putExtra("task_id", id)
                putExtra("task_command", command)
                putExtra("is_daily", isDaily)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                }
            } catch (_: Exception) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        }

        fun cancelAndRemoveTask(context: Context, id: Int) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ScheduledTaskReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context,
                id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pi)
            removeTaskFromStorage(context, id)
        }

        fun getAllTasks(context: Context): List<ScheduledItem> {
            val raw = context.getSharedPreferences(SCHEDULE_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LIST, "[]").orEmpty()
            val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
            val result = mutableListOf<ScheduledItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                result.add(
                    ScheduledItem(
                        id = obj.optInt("id"),
                        command = obj.optString("command"),
                        triggerTimeMillis = obj.optLong("time"),
                        isDaily = obj.optBoolean("daily", false)
                    )
                )
            }
            return result
        }

        private fun removeTaskFromStorage(context: Context, id: Int) {
            val updated = getAllTasks(context).filterNot { it.id == id }
            saveAllTasks(context, updated)
        }

        private fun saveAllTasks(context: Context, list: List<ScheduledItem>) {
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    JSONObject()
                        .put("id", it.id)
                        .put("command", it.command)
                        .put("time", it.triggerTimeMillis)
                        .put("daily", it.isDaily)
                )
            }
            context.getSharedPreferences(SCHEDULE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LIST, arr.toString()).apply()
        }
    }
}
