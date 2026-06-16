package app.morrowa.data

import android.content.Context
import androidx.room.withTransaction
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

class HabitRepository(context: Context) {
    private val db = MorrowaDatabase.INSTANCE.get(context.applicationContext)
    private val dao = db.habitDao()

    fun getActiveHabits(): Flow<List<HabitEntity>> = dao.getActiveHabits()

    fun getArchivedHabits(): Flow<List<HabitEntity>> = dao.getArchivedHabits()

    suspend fun addHabit(
        name: String,
        memo: String,
        ruleType: String,
        weekdays: List<Int>,
        monthDays: List<Int>,
    ): Long {
        return db.withTransaction {
            val now = System.currentTimeMillis()
            val habitId = dao.insert(
                HabitEntity(
                    name = name,
                    memo = memo,
                    sortOrder = dao.getNextSortOrder(),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            dao.insertRule(
                HabitRuleEntity(
                    habitId = habitId,
                    ruleType = ruleType,
                    weekdays = weekdays.toJsonArrayString(),
                    monthDays = monthDays.toJsonArrayString(),
                    startDate = todayIsoDate(),
                ),
            )
            habitId
        }
    }

    suspend fun updateHabit(habit: HabitEntity) {
        dao.update(habit.copy(updatedAt = System.currentTimeMillis()))
    }

    fun getCurrentRule(habitId: Long): Flow<HabitRuleEntity?> = dao.getCurrentRule(habitId)

    suspend fun setRule(
        habitId: Long,
        ruleType: String,
        weekdays: List<Int>,
        monthDays: List<Int>,
    ) {
        db.withTransaction {
            val today = todayIsoDate()
            dao.getCurrentRuleOnce(habitId)?.let { currentRule ->
                dao.updateRule(currentRule.copy(endDate = today))
            }
            dao.insertRule(
                HabitRuleEntity(
                    habitId = habitId,
                    ruleType = ruleType,
                    weekdays = weekdays.toJsonArrayString(),
                    monthDays = monthDays.toJsonArrayString(),
                    startDate = today,
                ),
            )
        }
    }

    fun getCompletions(habitId: Long): Flow<List<HabitCompletionEntity>> = dao.getCompletions(habitId)

    fun getCompletionsByDay(
        habitDay: String,
    ): Flow<List<HabitCompletionEntity>> = dao.getCompletionsByDay(habitDay)

    suspend fun toggleCompletion(habitId: Long, habitDay: String) {
        db.withTransaction {
            if (dao.getCompletion(habitId, habitDay) != null) {
                dao.deleteCompletion(habitId, habitDay)
            } else {
                dao.insertCompletion(
                    HabitCompletionEntity(
                        habitId = habitId,
                        habitDay = habitDay,
                        completedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    suspend fun archiveHabit(habitId: Long) {
        val now = System.currentTimeMillis()
        dao.getHabit(habitId)?.let { habit ->
            dao.update(habit.copy(isArchived = true, updatedAt = now))
        }
    }

    suspend fun moveToTrash(habitId: Long) {
        val now = System.currentTimeMillis()
        dao.getHabit(habitId)?.let { habit ->
            dao.update(habit.copy(deletedAt = now, updatedAt = now))
        }
    }

    private fun todayIsoDate(): String = LocalDate.now(JST_ZONE_ID).toString()

    private fun List<Int>.toJsonArrayString(): String = JSONArray(this).toString()

    companion object {
        private val JST_ZONE_ID = ZoneId.of("Asia/Tokyo")
    }
}
