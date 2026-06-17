package app.morrowa

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.morrowa.data.AlarmEntity
import app.morrowa.data.AlarmRepository
import app.morrowa.data.ToDoEntity
import app.morrowa.data.ToDoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ToDoViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ToDoRepository(application)
    private val alarmRepository = AlarmRepository(application)

    val activeTodos: StateFlow<List<ToDoEntity>> = repository.getActiveTodos()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    fun addTodo(title: String, memo: String, scheduledDate: Long?) {
        viewModelScope.launch {
            repository.addTodo(title = title, memo = memo, scheduledDate = scheduledDate)
        }
    }

    fun updateTodo(todo: ToDoEntity) {
        viewModelScope.launch {
            repository.updateTodo(todo)
        }
    }

    fun getAlarmFlow(todoId: Long): Flow<AlarmEntity?> =
        alarmRepository.getAlarmFlow(AlarmRepository.TYPE_TODO, todoId)

    fun setAlarm(context: Context, todoId: Long, todoTitle: String, hour: Int, minute: Int) {
        viewModelScope.launch {
            alarmRepository.setAlarm(AlarmRepository.TYPE_TODO, todoId, hour, minute)
            AlarmScheduler.scheduleAlarm(
                context = context,
                alarmId = AlarmScheduler.alarmRequestCode(AlarmRepository.TYPE_TODO, todoId),
                targetType = AlarmRepository.TYPE_TODO,
                targetId = todoId,
                title = todoTitle,
                hour = hour,
                minute = minute,
            )
        }
    }

    fun deleteAlarm(context: Context, todoId: Long) {
        viewModelScope.launch {
            alarmRepository.deleteAlarm(AlarmRepository.TYPE_TODO, todoId)
            AlarmScheduler.cancelAlarm(
                context,
                AlarmScheduler.alarmRequestCode(AlarmRepository.TYPE_TODO, todoId),
            )
        }
    }

    fun moveToTrash(todoId: Long) {
        viewModelScope.launch {
            repository.moveToTrash(todoId)
        }
    }
}
