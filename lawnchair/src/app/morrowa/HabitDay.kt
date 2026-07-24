package app.morrowa

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object HabitDay {
    private val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
    private val DAY_BOUNDARY: LocalTime = LocalTime.of(4, 0)

    fun today(): String = today(Clock.system(ZONE))

    fun today(clock: Clock): String = fromInstant(clock.instant())

    fun fromEpochMillis(epochMillis: Long): String = fromInstant(Instant.ofEpochMilli(epochMillis))

    fun fromInstant(instant: Instant): String {
        val dateTime = LocalDateTime.ofInstant(instant, ZONE)
        return fromJstDateTime(dateTime)
    }

    fun millisUntilNextBoundary(now: ZonedDateTime = ZonedDateTime.now(ZONE)): Long {
        val jstNow = now.withZoneSameInstant(ZONE)
        var nextBoundary = jstNow.toLocalDate().atTime(DAY_BOUNDARY).atZone(ZONE)
        if (!nextBoundary.isAfter(jstNow)) {
            nextBoundary = nextBoundary.plusDays(1)
        }
        return Duration.between(jstNow, nextBoundary).toMillis()
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
