package app.lawnchair.data.appdrawer.service

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.lawnchair.data.appdrawer.DrawerOrderEntity
import kotlinx.coroutines.flow.Flow

/**
 * Morrowa v2 §R4: DAO for the unified App Drawer order ([DrawerOrderEntity]). The drawer observes
 * the whole order as a [Flow]; an edit session commits by atomically replacing the full table
 * (rank renumbered 0..N over the whole sequence). No partial writes exist by design.
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

    /** Atomically replaces the whole unified order with [items]. */
    @Transaction
    suspend fun replaceAll(items: List<DrawerOrderEntity>) {
        clear()
        insertAll(items)
    }
}
