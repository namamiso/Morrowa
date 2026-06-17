package app.morrowa.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.android.launcher3.util.MainThreadInitializedObject

@Database(
    entities = [
        HabitEntity::class,
        HabitRuleEntity::class,
        HabitCompletionEntity::class,
        ToDoEntity::class,
    ],
    version = 2,
)
abstract class MorrowaDatabase : RoomDatabase() {

    abstract fun habitDao(): HabitDao
    abstract fun todoDao(): ToDoDao

    companion object {
        val INSTANCE = MainThreadInitializedObject { context ->
            Room.databaseBuilder(
                context,
                MorrowaDatabase::class.java,
                "morrowa",
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
