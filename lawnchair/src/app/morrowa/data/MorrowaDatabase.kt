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
    ],
    version = 1,
)
abstract class MorrowaDatabase : RoomDatabase() {

    abstract fun habitDao(): HabitDao

    companion object {
        val INSTANCE = MainThreadInitializedObject { context ->
            Room.databaseBuilder(
                context,
                MorrowaDatabase::class.java,
                "morrowa",
            ).build()
        }
    }
}
