package app.morrowa

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.morrowa.data.AlarmEntity
import app.morrowa.data.AlarmRepository
import app.morrowa.data.HabitCompletionEntity
import app.morrowa.data.HabitEntity
import app.morrowa.data.HabitRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HabitViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository: HabitRepository = HabitRepository(application)
    private val alarmRepository: AlarmRepository = AlarmRepository(application)

    private val _habitDay = MutableStateFlow(HabitDay.today())
    val habitDay: StateFlow<String> = _habitDay.asStateFlow()

    val activeHabits: StateFlow<List<HabitEntity>> = repository.getActiveHabits()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    val completions: StateFlow<Map<Long, Boolean>> = combine(
        activeHabits,
        habitDay.flatMapLatest { day -> repository.getCompletionsByDay(day) },
    ) { habits, completions ->
        completions.toCompletionMap(habits)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyMap(),
    )

    fun refreshHabitDay() {
        _habitDay.value = HabitDay.today()
    }

    fun checkHabit(habitId: Long) {
        viewModelScope.launch {
            repository.toggleCompletion(habitId, habitDay.value)
        }
    }

    fun getCompletionsFlow(habitId: Long): Flow<List<HabitCompletionEntity>> =
        repository.getCompletions(habitId)

    fun getAlarmFlow(habitId: Long): Flow<AlarmEntity?> =
        alarmRepository.getAlarmFlow(AlarmRepository.TYPE_HABIT, habitId)

    fun setAlarm(context: Context, habitId: Long, habitName: String, hour: Int, minute: Int) {
        viewModelScope.launch {
            alarmRepository.setAlarm(AlarmRepository.TYPE_HABIT, habitId, hour, minute)
            AlarmScheduler.scheduleAlarm(
                context = context,
                alarmId = AlarmScheduler.alarmRequestCode(AlarmRepository.TYPE_HABIT, habitId),
                targetType = AlarmRepository.TYPE_HABIT,
                targetId = habitId,
                title = habitName,
                hour = hour,
                minute = minute,
            )
        }
    }

    fun deleteAlarm(context: Context, habitId: Long) {
        viewModelScope.launch {
            alarmRepository.deleteAlarm(AlarmRepository.TYPE_HABIT, habitId)
            AlarmScheduler.cancelAlarm(
                context,
                AlarmScheduler.alarmRequestCode(AlarmRepository.TYPE_HABIT, habitId),
            )
        }
    }

    fun toggleCompletionForDay(habitId: Long, habitDay: String) {
        viewModelScope.launch {
            repository.toggleCompletion(habitId, habitDay)
        }
    }

    fun addHabit(
        name: String,
        memo: String,
        ruleType: String,
        weekdays: List<Int>,
        monthDays: List<Int>,
    ) {
        viewModelScope.launch {
            repository.addHabit(
                name = name,
                memo = memo,
                ruleType = ruleType,
                weekdays = weekdays,
                monthDays = monthDays,
            )
        }
    }

    fun archiveHabit(habitId: Long) {
        viewModelScope.launch {
            repository.archiveHabit(habitId)
        }
    }

    fun moveToTrash(habitId: Long) {
        viewModelScope.launch {
            repository.moveToTrash(habitId)
        }
    }

    fun updateHabit(habit: HabitEntity) {
        viewModelScope.launch {
            repository.updateHabit(habit)
        }
    }

    private fun List<HabitCompletionEntity>.toCompletionMap(
        activeHabits: List<HabitEntity>,
    ): Map<Long, Boolean> {
        val completedHabitIds = map { it.habitId }.toSet()
        return activeHabits.associate { habit ->
            habit.id to (habit.id in completedHabitIds)
        }
    }
}
