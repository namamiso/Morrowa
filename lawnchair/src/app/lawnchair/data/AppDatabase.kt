package app.lawnchair.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import app.lawnchair.data.appdrawer.DrawerOrderEntity
import app.lawnchair.data.appdrawer.service.DrawerOrderDao
import app.lawnchair.data.folder.FolderInfoEntity
import app.lawnchair.data.folder.FolderItemEntity
import app.lawnchair.data.folder.service.FolderDao
import app.lawnchair.data.iconoverride.IconOverride
import app.lawnchair.data.iconoverride.IconOverrideDao
import app.lawnchair.data.wallpaper.Wallpaper
import app.lawnchair.data.wallpaper.service.WallpaperDao
import app.lawnchair.util.MainThreadInitializedObject
import kotlinx.coroutines.runBlocking

@Database(
    entities = [
        IconOverride::class,
        Wallpaper::class,
        FolderInfoEntity::class,
        FolderItemEntity::class,
        DrawerOrderEntity::class,
    ],
    version = 4,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun iconOverrideDao(): IconOverrideDao
    abstract fun wallpaperDao(): WallpaperDao
    abstract fun folderDao(): FolderDao
    abstract fun drawerOrderDao(): DrawerOrderDao

    suspend fun checkpoint() {
        iconOverrideDao().checkpoint(SimpleSQLiteQuery("pragma wal_checkpoint(full)"))
        wallpaperDao().checkpoint(SimpleSQLiteQuery("pragma wal_checkpoint(full)"))
        folderDao().checkpoint(SimpleSQLiteQuery("pragma wal_checkpoint(full)"))
    }

    fun checkpointSync() {
        runBlocking {
            checkpoint()
        }
    }

    companion object {
        val MIGRATION_1_3 = object : Migration(1, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
            CREATE TABLE IF NOT EXISTS `Wallpapers` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `imagePath` TEXT NOT NULL,
                `rank` INTEGER NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `checksum` TEXT
            )
                    """.trimIndent(),
                )

                database.execSQL(
                    """
            CREATE TABLE IF NOT EXISTS `Folders` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `hide` INTEGER NOT NULL,
                `rank` INTEGER NOT NULL,
                `timestamp` INTEGER NOT NULL
            )
                    """.trimIndent(),
                )

                database.execSQL(
                    """
            CREATE TABLE IF NOT EXISTS `FolderItems` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `folderId` INTEGER NOT NULL,
                `rank` INTEGER NOT NULL,
                `item_info` TEXT,
                `timestamp` INTEGER NOT NULL,
                FOREIGN KEY(`folderId`) REFERENCES `Folders`(`id`) ON UPDATE CASCADE ON DELETE CASCADE
            )
                    """.trimIndent(),
                )

                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_FolderItems_folderId` ON `FolderItems` (`folderId`)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE INDEX IF NOT EXISTS index_FolderItems_folderId ON FolderItems(folderId)")
            }
        }

        // Morrowa v2 (docs/Morrowa_AppDrawer_編集モードv2_要件設計.md §R4/Q6): v3->v4 adds the
        // empty unified DrawerOrder table only. No data migration — the table stays empty until
        // the user commits their first edit session, and the read path renders the legacy layout
        // while it is empty, so the post-upgrade appearance is unchanged. (This v4 is unrelated to
        // the rolled-back v1 v4/v5; test devices were wiped, see §10.42.)
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
            CREATE TABLE IF NOT EXISTS `DrawerOrder` (
                `key` TEXT NOT NULL,
                `rank` INTEGER NOT NULL,
                PRIMARY KEY(`key`)
            )
                    """.trimIndent(),
                )
            }
        }

        val INSTANCE = MainThreadInitializedObject { context ->
            Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                "preferences",
            ).addMigrations(MIGRATION_1_3).addMigrations(MIGRATION_2_3).addMigrations(MIGRATION_3_4).build()
        }
    }
}
