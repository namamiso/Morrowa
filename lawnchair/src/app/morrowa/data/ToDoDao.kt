package app.morrowa.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ToDoDao {
    @Query("SELECT * FROM todos WHERE deletedAt IS NULL ORDER BY sortOrder ASC")
    fun getActiveTodos(): Flow<List<ToDoEntity>>

    @Query("SELECT * FROM todos WHERE deletedAt IS NOT NULL")
    fun getDeletedTodos(): Flow<List<ToDoEntity>>

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun getTodo(id: Long): ToDoEntity?

    @Query("SELECT COALESCE(MAX(sortOrder) + 1, 0) FROM todos")
    suspend fun getNextSortOrder(): Int

    @Insert
    suspend fun insert(todo: ToDoEntity): Long

    @Update
    suspend fun update(todo: ToDoEntity)

    @Query("DELETE FROM todos WHERE id = :todoId")
    suspend fun deleteTodoPermanently(todoId: Long)

    @Query("DELETE FROM todos WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun deleteOldTodos(before: Long)
}
