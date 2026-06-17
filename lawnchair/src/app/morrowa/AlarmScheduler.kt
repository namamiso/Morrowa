package app.morrowa

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar
import java.util.TimeZone

object AlarmScheduler {
    const val ACTION_ALARM = "app.morrowa.action.ALARM"
    const val EXTRA_TARGET_TYPE = "target_type"
    const val EXTRA_TARGET_ID = "target_id"
    const val EXTRA_TITLE = "title"

    const val ACTION_INCOMPLETE_HABIT = "app.morrowa.action.INCOMPLETE_HABIT"

    fun scheduleAlarm(
        context: Context,
        alarmId: Int,
        targetType: String,
        targetId: Long,
        title: String,
        hour: Int,
        minute: Int,
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildAlarmPendingIntent(context, alarmId, targetType, targetId, title)
        val triggerAt = nextTriggerMillis(hour, minute)
        scheduleExact(am, triggerAt, pi)
    }

    fun cancelAlarm(context: Context, alarmId: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context,
            alarmId,
            Intent(ACTION_ALARM).setPackage(context.packageName),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        am.cancel(pi)
        pi.cancel()
    }

    fun scheduleIncompleteHabitNotification(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildIncompleteHabitPendingIntent(context)
        val triggerAt = nextTriggerMillis(hour = 20, minute = 0)
        scheduleExact(am, triggerAt, pi)
    }

    fun cancelIncompleteHabitNotification(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context,
            INCOMPLETE_HABIT_REQUEST_CODE,
            Intent(ACTION_INCOMPLETE_HABIT).setPackage(context.packageName),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        am.cancel(pi)
        pi.cancel()
    }

    private fun scheduleExact(am: AlarmManager, triggerAt: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 5 * 60 * 1000L, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun nextTriggerMillis(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo")).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun buildAlarmPendingIntent(
        context: Context,
        alarmId: Int,
        targetType: String,
        targetId: Long,
        title: String,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        alarmId,
        Intent(ACTION_ALARM).setPackage(context.packageName).apply {
            putExtra(EXTRA_TARGET_TYPE, targetType)
            putExtra(EXTRA_TARGET_ID, targetId)
            putExtra(EXTRA_TITLE, title)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildIncompleteHabitPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            INCOMPLETE_HABIT_REQUEST_CODE,
            Intent(ACTION_INCOMPLETE_HABIT).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun alarmRequestCode(targetType: String, targetId: Long): Int =
        if (targetType == "HABIT") (targetId * 2).toInt() else (targetId * 2 + 1).toInt()

    private const val INCOMPLETE_HABIT_REQUEST_CODE = 999_999
}
