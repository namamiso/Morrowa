package app.morrowa.data

import android.content.Context
import androidx.room.withTransaction

class BackupRepository(context: Context) {
    private val db = MorrowaDatabase.INSTANCE.get(context.applicationContext)
    private val habitDao = db.habitDao()
    private val todoDao = db.todoDao()
    private val alarmDao = db.alarmDao()

    suspend fun exportAll(): BackupData {
        return BackupData(
            exportedAt = MorrowaBackup.nowIso(),
            habits = habitDao.getAllHabits(),
            habitRules = habitDao.getAllRules(),
            habitCompletions = habitDao.getAllCompletions(),
            alarms = alarmDao.getAllAlarms(),
            todos = todoDao.getAllTodos(),
        )
    }

    suspend fun importAll(data: BackupData) {
        db.withTransaction {
            alarmDao.deleteAllAlarms()
            todoDao.deleteAllTodos()
            habitDao.deleteAllHabits()

            data.habits.forEach { habitDao.insertHabitWithId(it) }
            data.habitRules.forEach { habitDao.insertRuleWithId(it) }
            data.habitCompletions.forEach { habitDao.insertCompletionWithId(it) }
            data.alarms.forEach { alarmDao.insertAlarmWithId(it) }
            data.todos.forEach { todoDao.insertTodoWithId(it) }
        }
    }
}
