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
import app.morrowa.data.HabitRuleEntity
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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

    val deletedHabits: StateFlow<List<HabitEntity>> = repository.getDeletedHabits()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    val dailyAchievement: StateFlow<Map<String, Float>> = combine(
        repository.getRulesForDailyAchievement(),
        repository.getCompletionsForDailyAchievement(),
        habitDay,
    ) { rules, completions, currentHabitDay ->
        Triple(rules, completions, currentHabitDay)
    }.map { (rules, completions, currentHabitDay) ->
        val today = LocalDate.parse(currentHabitDay)
        val firstDayOfYear = LocalDate.of(today.year, 1, 1)
        val rulesByHabit = rules.groupBy { it.habitId }
        val completedHabitIdsByDay = completions
            .groupBy { it.habitDay }
            .mapValues { (_, dayCompletions) -> dayCompletions.mapTo(mutableSetOf()) { it.habitId } }

        buildMap {
            generateSequence(firstDayOfYear) { it.plusDays(1) }
                .takeWhile { !it.isAfter(today) }
                .forEach { date ->
                    val habitDay = date.toString()
                    val targetHabitIds = rulesByHabit
                        .filterValues { habitRules ->
                            habitRules.any { rule -> HabitFrequency.isTargetDay(rule, habitDay) }
                        }
                        .keys
                    if (targetHabitIds.isNotEmpty()) {
                        val completedCount = completedHabitIdsByDay[habitDay]
                            ?.count { it in targetHabitIds }
                            ?: 0
                        if (completedCount > 0) {
                            put(habitDay, completedCount.toFloat() / targetHabitIds.size)
                        }
                    }
                }
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap(),
        )

    val targetToday: StateFlow<Map<Long, Boolean>> = combine(
        repository.getRulesForDailyAchievement(),
        habitDay,
    ) { rules, day ->
        rules.groupBy { it.habitId }.mapValues { (_, habitRules) ->
            habitRules.any { rule -> HabitFrequency.isTargetDay(rule, day) }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyMap(),
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
            val day = HabitDay.today()
            _habitDay.value = day
            repository.toggleCompletion(habitId, day)
        }
    }

    fun getCompletionsFlow(habitId: Long): Flow<List<HabitCompletionEntity>> =
        repository.getCompletions(habitId)

    fun getRuleHistoryFlow(habitId: Long): Flow<List<HabitRuleEntity>> =
        repository.getRuleHistory(habitId)

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

    fun restoreHabit(habitId: Long) {
        viewModelScope.launch {
            repository.restoreHabit(habitId)
        }
    }

    fun deleteHabitPermanently(habitId: Long) {
        viewModelScope.launch {
            repository.deleteHabitPermanently(habitId)
        }
    }

    fun updateHabit(habit: HabitEntity) {
        viewModelScope.launch {
            repository.updateHabit(habit)
        }
    }

    fun reorder(orderedIds: List<Long>) {
        viewModelScope.launch {
            repository.reorderHabits(orderedIds)
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
