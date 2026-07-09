package app.lawnchair.data.folder.service

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Relation
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import app.lawnchair.data.folder.FolderInfoEntity
import app.lawnchair.data.folder.FolderItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderInfoEntity)

    /**
     * Morrowa §10.26 (R3): like [insertFolder] but returns the auto-generated row id so a
     * brand-new folder's children can be linked to it in the same transaction. [insertFolder]
     * returns Unit, which left callers with no way to learn the id Room generated.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolderReturningId(folder: FolderInfoEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolderItems(items: List<FolderItemEntity>)

    /**
     * Morrowa §10.26 (R3) + §10.32.3: creates a new folder AND its initial members atomically.
     * [folder] is forced to id=0 so Room always inserts a fresh row; [items] is a factory given
     * the generated id so the children carry the correct foreign key. Because folder + items are
     * written in a single @Transaction, [FolderService.getFoldersFlow] only ever emits this folder
     * once its contents are already present -- this is what makes the newly created folder appear
     * in the App Drawer immediately instead of only after a relaunch (the "folder created only
     * after restart" bug: LawnchairAlphabeticalAppsList#addAppsWithSections only renders a folder
     * whose resolved contents size is > 1).
     */
    @Transaction
    suspend fun createFolderWithItems(
        folder: FolderInfoEntity,
        items: (folderId: Int) -> List<FolderItemEntity>,
    ): Int {
        val newId = insertFolderReturningId(folder.copy(id = 0)).toInt()
        insertFolderItems(items(newId))
        return newId
    }

    @Query("SELECT * FROM Folders WHERE id = :folderId")
    @Transaction
    suspend fun getFolderWithItems(folderId: Int): FolderWithItems?

    @Query("SELECT * FROM FolderItems WHERE folderId IS NOT :folderId")
    @Transaction
    suspend fun getItems(folderId: Int): List<FolderItemEntity>

    @Query("SELECT * FROM Folders")
    fun getAllFolders(): Flow<List<FolderInfoEntity>>

    @Transaction
    suspend fun insertFolderWithItems(folder: FolderInfoEntity, items: List<FolderItemEntity>) {
        insertFolder(folder)
        insertFolderItems(items)
    }

    @Query("DELETE FROM FolderItems WHERE folderId = :folderId")
    suspend fun deleteFolderItemsByFolderId(folderId: Int)

    @Query(
        value = """
                UPDATE Folders
                SET title = :newTitle, hide = :hide, timestamp = :timestamp
                WHERE id = :folderId
            """,
    )
    suspend fun updateFolderInfo(
        folderId: Int,
        newTitle: String,
        hide: Boolean,
        timestamp: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM Folders WHERE id = :folderId")
    suspend fun deleteFolder(folderId: Int)

    @RawQuery
    suspend fun checkpoint(supportSQLiteQuery: SupportSQLiteQuery): Int
}

data class FolderWithItems(
    @Embedded val folder: FolderInfoEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "folderId",
    )
    val items: List<FolderItemEntity>,
)
