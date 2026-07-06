package app.lawnchair.data.appdrawer

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "DrawerAppOrder")
data class DrawerAppOrderEntity(
    @PrimaryKey val componentKey: String,
    val rank: Int,
)
