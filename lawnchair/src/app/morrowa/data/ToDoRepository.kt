package app.morrowa.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

class ToDoRepository(context: Context) {
    private val db = MorrowaDatabase.INSTANCE.get(context.applicationContext)
    private val dao = db.todoDao()

    fun getActiveTodos(): Flow<List<ToDoEntity>> = dao.getActiveTodos()

    fun getDeletedTodos(): Flow<List<ToDoEntity>> = dao.getDeletedTodos()

    suspend fun getTodoTitle(todoId: Long): String? = dao.getTodo(todoId)?.title

    suspend fun addTodo(title: String, memo: String, scheduledDate: Long?): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            ToDoEntity(
                title = title,
                memo = memo,
                scheduledDate = scheduledDate,
                sortOrder = dao.getNextSortOrder(),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun updateTodo(todo: ToDoEntity) {
        dao.update(todo.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun reorderTodos(orderedIds: List<Long>) {
        db.withTransaction {
            orderedIds.forEachIndexed { index, id ->
                dao.updateSortOrder(id, index)
            }
        }
    }

    suspend fun moveToTrash(todoId: Long) {
        val now = System.currentTimeMillis()
        dao.getTodo(todoId)?.let { todo ->
            dao.update(todo.copy(deletedAt = now, updatedAt = now))
        }
    }

    suspend fun restoreTodo(todoId: Long) {
        dao.getTodo(todoId)?.let { todo ->
            val nextOrder = dao.getNextSortOrder()
            dao.update(
                todo.copy(
                    deletedAt = null,
                    sortOrder = nextOrder,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun deleteTodoPermanently(todoId: Long) {
        dao.deleteTodoPermanently(todoId)
    }

    suspend fun deleteOldTodos() {
        val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        dao.deleteOldTodos(thirtyDaysAgo)
    }
}
