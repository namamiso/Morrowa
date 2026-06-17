package app.morrowa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.morrowa.data.AlarmRepository
import app.morrowa.data.HabitRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MorrowaAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmScheduler.ACTION_ALARM -> handleItemAlarm(context, intent)
            AlarmScheduler.ACTION_INCOMPLETE_HABIT -> handleIncompleteHabit(context)
        }
    }

    private fun handleItemAlarm(context: Context, intent: Intent) {
        val targetType = intent.getStringExtra(AlarmScheduler.EXTRA_TARGET_TYPE) ?: return
        val targetId = intent.getLongExtra(AlarmScheduler.EXTRA_TARGET_ID, -1L)
        val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: return
        if (targetId == -1L) return

        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationHelper.createChannels(context)
            val notifId = AlarmScheduler.alarmRequestCode(targetType, targetId)
            NotificationHelper.showAlarmNotification(context, notifId, title, targetType)
        }

        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = AlarmRepository(context)
                val alarm = repo.getAlarm(targetType, targetId)
                if (alarm != null && alarm.isEnabled) {
                    AlarmScheduler.scheduleAlarm(
                        context = context,
                        alarmId = AlarmScheduler.alarmRequestCode(targetType, targetId),
                        targetType = targetType,
                        targetId = targetId,
                        title = title,
                        hour = alarm.hour,
                        minute = alarm.minute,
                    )
                }
            } finally {
                result.finish()
            }
        }
    }

    private fun handleIncompleteHabit(context: Context) {
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val today = HabitDay.today()
                val repo = HabitRepository(context)
                val activeHabits = repo.getActiveHabitsOnce()
                val completions = repo.getCompletionsByDayOnce(today)
                val completedIds = completions.map { it.habitId }.toSet()
                val incompleteCount = activeHabits.count { it.id !in completedIds }

                if (
                    incompleteCount > 0 &&
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
                ) {
                    NotificationHelper.createChannels(context)
                    NotificationHelper.showIncompleteHabitNotification(context, incompleteCount)
                }

                AlarmScheduler.scheduleIncompleteHabitNotification(context)
            } finally {
                result.finish()
            }
        }
    }
}
