package app.morrowa

import android.content.Context
import app.morrowa.data.AlarmEntity
import app.morrowa.data.AlarmRepository
import app.morrowa.data.HabitRepository
import app.morrowa.data.ToDoRepository

object MorrowaAlarmRescheduler {
    suspend fun cancelAll(context: Context) {
        val appContext = context.applicationContext
        cancelAlarms(appContext, AlarmRepository(appContext).getAllAlarms())
        AlarmScheduler.cancelIncompleteHabitNotification(appContext)
    }

    fun cancelAlarms(context: Context, alarms: List<AlarmEntity>) {
        val appContext = context.applicationContext
        alarms.forEach { alarm ->
            AlarmScheduler.cancelAlarm(
                appContext,
                AlarmScheduler.alarmRequestCode(alarm.targetType, alarm.targetId),
            )
        }
    }

    suspend fun rescheduleAll(context: Context) {
        val appContext = context.applicationContext
        val alarmRepo = AlarmRepository(appContext)
        val habitRepo = HabitRepository(appContext)
        val todoRepo = ToDoRepository(appContext)

        alarmRepo.getEnabledAlarms().forEach { alarm ->
            val title = when (alarm.targetType) {
                AlarmRepository.TYPE_HABIT -> habitRepo.getHabitName(alarm.targetId) ?: return@forEach
                AlarmRepository.TYPE_TODO -> todoRepo.getTodoTitle(alarm.targetId) ?: return@forEach
                else -> return@forEach
            }
            AlarmScheduler.scheduleAlarm(
                context = appContext,
                alarmId = AlarmScheduler.alarmRequestCode(alarm.targetType, alarm.targetId),
                targetType = alarm.targetType,
                targetId = alarm.targetId,
                title = title,
                hour = alarm.hour,
                minute = alarm.minute,
            )
        }

        AlarmScheduler.scheduleIncompleteHabitNotification(appContext)
    }
}
