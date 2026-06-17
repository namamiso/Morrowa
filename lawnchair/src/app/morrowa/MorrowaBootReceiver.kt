package app.morrowa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.morrowa.data.AlarmRepository
import app.morrowa.data.HabitRepository
import app.morrowa.data.ToDoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MorrowaBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                rescheduleAll(context)
            } finally {
                result.finish()
            }
        }
    }

    private suspend fun rescheduleAll(context: Context) {
        val alarmRepo = AlarmRepository(context)
        val habitRepo = HabitRepository(context)
        val todoRepo = ToDoRepository(context)

        alarmRepo.getEnabledAlarms().forEach { alarm ->
            val title = when (alarm.targetType) {
                AlarmRepository.TYPE_HABIT -> habitRepo.getHabitName(alarm.targetId) ?: return@forEach
                AlarmRepository.TYPE_TODO -> todoRepo.getTodoTitle(alarm.targetId) ?: return@forEach
                else -> return@forEach
            }
            AlarmScheduler.scheduleAlarm(
                context = context,
                alarmId = AlarmScheduler.alarmRequestCode(alarm.targetType, alarm.targetId),
                targetType = alarm.targetType,
                targetId = alarm.targetId,
                title = title,
                hour = alarm.hour,
                minute = alarm.minute,
            )
        }

        AlarmScheduler.scheduleIncompleteHabitNotification(context)
    }
}
