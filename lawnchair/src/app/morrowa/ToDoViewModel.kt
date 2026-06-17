package app.morrowa

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.morrowa.data.ToDoEntity
import app.morrowa.data.ToDoRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ToDoViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ToDoRepository(application)

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

    fun moveToTrash(todoId: Long) {
        viewModelScope.launch {
            repository.moveToTrash(todoId)
        }
    }
}
