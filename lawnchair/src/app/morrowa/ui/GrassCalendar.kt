package app.morrowa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun GrassCalendar(
    completedDays: Set<String>,
    todayHabitDay: String,
    onDayToggle: (habitDay: String) -> Unit,
    modifier: Modifier = Modifier,
    cellSize: Dp = 14.dp,
    monthLabelHeight: Dp = 16.dp,
    cellSpacing: Dp = 2.dp,
) {
    val year = LocalDate.now(ZoneId.of("Asia/Tokyo")).year
    val weeks = remember(year) {
        val firstSunday = LocalDate.of(year, 1, 1).let { jan1 ->
            jan1.minusDays((jan1.dayOfWeek.value % 7).toLong())
        }
        val lastSaturday = LocalDate.of(year, 12, 31).let { dec31 ->
            dec31.plusDays((6 - (dec31.dayOfWeek.value % 7)).toLong())
        }
        generateSequence(firstSunday) { date -> date.plusWeeks(1) }
            .takeWhile { date -> !date.isAfter(lastSaturday) }
            .toList()
    }
    val monthLabels = remember(year) {
        (1..12).associate { month ->
            val firstDay = LocalDate.of(year, month, 1)
            val weekStart = firstDay.minusDays((firstDay.dayOfWeek.value % 7).toLong())
            weekStart to "${month}月"
        }
    }

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(cellSpacing),
    ) {
        items(weeks) { weekStart ->
            Column(
                verticalArrangement = Arrangement.spacedBy(cellSpacing),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.size(width = cellSize, height = monthLabelHeight),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    monthLabels[weekStart]?.let { label ->
                        Text(
                            text = label,
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 10.sp,
                            maxLines = 1,
                        )
                    }
                }
                repeat(7) { dayOffset ->
                    val date = weekStart.plusDays(dayOffset.toLong())
                    val habitDay = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                    val isFuture = habitDay > todayHabitDay
                    val color = when {
                        isFuture -> Color(0x15FFFFFF)
                        habitDay in completedDays -> Color(0xFF8FD7A3)
                        else -> Color(0x40FFFFFF)
                    }
                    val cellModifier = Modifier
                        .size(cellSize)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color)
                        .let { base ->
                            if (isFuture) {
                                base
                            } else {
                                base.clickable { onDayToggle(habitDay) }
                            }
                        }

                    Box(modifier = cellModifier)
                }
            }
        }
    }
}
