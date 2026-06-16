package app.morrowa

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object HabitDay {
    private val JST_ZONE_ID: ZoneId = ZoneId.of("Asia/Tokyo")
    private val DAY_BOUNDARY: LocalTime = LocalTime.of(4, 0)

    fun today(): String = today(Clock.system(JST_ZONE_ID))

    fun today(clock: Clock): String = fromInstant(clock.instant())

    fun fromEpochMillis(epochMillis: Long): String = fromInstant(Instant.ofEpochMilli(epochMillis))

    fun fromInstant(instant: Instant): String {
        val dateTime = LocalDateTime.ofInstant(instant, JST_ZONE_ID)
        return fromJstDateTime(dateTime)
    }

    fun fromJstDateTime(dateTime: LocalDateTime): String {
        val habitDate = if (dateTime.toLocalTime().isBefore(DAY_BOUNDARY)) {
            dateTime.toLocalDate().minusDays(1)
        } else {
            dateTime.toLocalDate()
        }
        return habitDate.toString()
    }
}
