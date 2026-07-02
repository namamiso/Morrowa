package app.morrowa.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class AlarmRepository(context: Context) {
    private val db = MorrowaDatabase.INSTANCE.get(context.applicationContext)
    private val dao = db.alarmDao()

    fun getAlarmFlow(targetType: String, targetId: Long): Flow<AlarmEntity?> =
        dao.getAlarmFlow(targetType, targetId)

    suspend fun getAlarm(targetType: String, targetId: Long): AlarmEntity? =
        dao.getAlarm(targetType, targetId)

    suspend fun getEnabledAlarms(): List<AlarmEntity> = dao.getEnabledAlarms()

    suspend fun getAllAlarms(): List<AlarmEntity> = dao.getAllAlarms()

    suspend fun setAlarm(targetType: String, targetId: Long, hour: Int, minute: Int) {
        val existing = dao.getAlarm(targetType, targetId)
        if (existing == null) {
            dao.insert(
                AlarmEntity(
                    targetType = targetType,
                    targetId = targetId,
                    hour = hour,
                    minute = minute,
                ),
            )
        } else {
            dao.update(
                existing.copy(
                    hour = hour,
                    minute = minute,
                    isEnabled = true,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun deleteAlarm(targetType: String, targetId: Long) {
        dao.delete(targetType, targetId)
    }

    companion object {
        const val TYPE_HABIT = "HABIT"
        const val TYPE_TODO = "TODO"
    }
}
