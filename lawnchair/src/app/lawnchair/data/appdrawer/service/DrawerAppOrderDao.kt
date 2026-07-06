package app.lawnchair.data.appdrawer.service

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.lawnchair.data.appdrawer.DrawerAppOrderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DrawerAppOrderDao {
    @Query("SELECT * FROM DrawerAppOrder")
    fun getAll(): Flow<List<DrawerAppOrderEntity>>

    @Query("SELECT * FROM DrawerAppOrder")
    suspend fun getAllOnce(): List<DrawerAppOrderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DrawerAppOrderEntity>)

    @Query("DELETE FROM DrawerAppOrder")
    suspend fun clear()

    /** Atomically replaces the whole manual order with [items] (full renumber on each reorder). */
    @Transaction
    suspend fun replaceAll(items: List<DrawerAppOrderEntity>) {
        clear()
        insertAll(items)
    }
}
