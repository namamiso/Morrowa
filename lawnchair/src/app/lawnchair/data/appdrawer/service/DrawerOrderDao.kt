package app.lawnchair.data.appdrawer.service

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.lawnchair.data.appdrawer.DrawerOrderEntity
import kotlinx.coroutines.flow.Flow

/**
 * Morrowa §10.38.1 G-1: DAO for the unified App Drawer order ([DrawerOrderEntity]). Same shape as
 * the legacy [app.lawnchair.data.appdrawer.service.DrawerAppOrderDao]: observe the whole order as a
 * [Flow], and commit a reorder by atomically replacing the full table (every reorder renumbers
 * rank 0..N over the whole displayed sequence, folders included).
 */
@Dao
interface DrawerOrderDao {
    @Query("SELECT * FROM DrawerOrder")
    fun getAll(): Flow<List<DrawerOrderEntity>>

    @Query("SELECT * FROM DrawerOrder")
    suspend fun getAllOnce(): List<DrawerOrderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DrawerOrderEntity>)

    @Query("DELETE FROM DrawerOrder")
    suspend fun clear()

    /** Atomically replaces the whole unified order with [items] (full renumber on each reorder). */
    @Transaction
    suspend fun replaceAll(items: List<DrawerOrderEntity>) {
        clear()
        insertAll(items)
    }
}
