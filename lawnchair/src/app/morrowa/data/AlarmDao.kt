package app.morrowa.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms WHERE targetType = :targetType AND targetId = :targetId LIMIT 1")
    fun getAlarmFlow(targetType: String, targetId: Long): Flow<AlarmEntity?>

    @Query("SELECT * FROM alarms WHERE targetType = :targetType AND targetId = :targetId LIMIT 1")
    suspend fun getAlarm(targetType: String, targetId: Long): AlarmEntity?

    @Query("SELECT * FROM alarms WHERE isEnabled = 1")
    suspend fun getEnabledAlarms(): List<AlarmEntity>

    @Query("SELECT * FROM alarms")
    suspend fun getAllAlarms(): List<AlarmEntity>

    @Insert
    suspend fun insert(alarm: AlarmEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlarmWithId(alarm: AlarmEntity)

    @Update
    suspend fun update(alarm: AlarmEntity)

    @Query("DELETE FROM alarms")
    suspend fun deleteAllAlarms()

    @Query("DELETE FROM alarms WHERE targetType = :targetType AND targetId = :targetId")
    suspend fun delete(targetType: String, targetId: Long)
}
