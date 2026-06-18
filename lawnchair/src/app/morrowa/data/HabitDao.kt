package app.morrowa.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits WHERE isArchived = 0 AND deletedAt IS NULL ORDER BY sortOrder ASC")
    fun getActiveHabits(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits WHERE isArchived = 0 AND deletedAt IS NULL ORDER BY sortOrder ASC")
    suspend fun getActiveHabitsOnce(): List<HabitEntity>

    @Query("SELECT * FROM habits WHERE isArchived = 1 AND deletedAt IS NULL")
    fun getArchivedHabits(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits WHERE deletedAt IS NOT NULL")
    fun getDeletedHabits(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits WHERE id = :habitId")
    suspend fun getHabit(habitId: Long): HabitEntity?

    @Query("SELECT * FROM habits")
    suspend fun getAllHabits(): List<HabitEntity>

    @Query("SELECT COALESCE(MAX(sortOrder) + 1, 0) FROM habits")
    suspend fun getNextSortOrder(): Int

    @Insert
    suspend fun insert(habit: HabitEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHabitWithId(habit: HabitEntity)

    @Update
    suspend fun update(habit: HabitEntity)

    @Query("DELETE FROM habits")
    suspend fun deleteAllHabits()

    @Query("DELETE FROM habits WHERE id = :habitId")
    suspend fun deleteHabitPermanently(habitId: Long)

    @Query("DELETE FROM habits WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun deleteOldHabits(before: Long)

    @Query("SELECT * FROM habit_rules WHERE habitId = :habitId AND endDate IS NULL")
    fun getCurrentRule(habitId: Long): Flow<HabitRuleEntity?>

    @Query("SELECT * FROM habit_rules WHERE habitId = :habitId AND endDate IS NULL")
    suspend fun getCurrentRuleOnce(habitId: Long): HabitRuleEntity?

    @Query("SELECT * FROM habit_rules WHERE habitId = :habitId ORDER BY startDate ASC")
    fun getRuleHistory(habitId: Long): Flow<List<HabitRuleEntity>>

    @Query("SELECT * FROM habit_rules WHERE habitId IN (SELECT id FROM habits)")
    suspend fun getAllRules(): List<HabitRuleEntity>

    @Insert
    suspend fun insertRule(rule: HabitRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRuleWithId(rule: HabitRuleEntity)

    @Update
    suspend fun updateRule(rule: HabitRuleEntity)

    @Query("SELECT * FROM habit_completions WHERE habitId = :habitId")
    fun getCompletions(habitId: Long): Flow<List<HabitCompletionEntity>>

    @Query("SELECT * FROM habit_completions WHERE habitDay = :habitDay")
    fun getCompletionsByDay(habitDay: String): Flow<List<HabitCompletionEntity>>

    @Query("SELECT * FROM habit_completions WHERE habitDay = :habitDay")
    suspend fun getCompletionsByDayOnce(habitDay: String): List<HabitCompletionEntity>

    @Query("SELECT * FROM habit_completions WHERE habitId = :habitId AND habitDay = :habitDay")
    suspend fun getCompletion(habitId: Long, habitDay: String): HabitCompletionEntity?

    @Query("SELECT * FROM habit_completions")
    suspend fun getAllCompletions(): List<HabitCompletionEntity>

    @Insert
    suspend fun insertCompletion(completion: HabitCompletionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCompletionWithId(completion: HabitCompletionEntity)

    @Query("DELETE FROM habit_completions WHERE habitId = :habitId AND habitDay = :habitDay")
    suspend fun deleteCompletion(habitId: Long, habitDay: String)
}
