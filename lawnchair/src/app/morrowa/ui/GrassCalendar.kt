package app.morrowa.ui

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import app.morrowa.LocalMorrowaScrollerRegistry
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun GrassCalendar(
    completedDays: Set<String>,
    todayHabitDay: String,
    onDayToggle: ((habitDay: String) -> Unit)?,
    modifier: Modifier = Modifier,
    dayDensities: Map<String, Float>? = null,
    targetDays: Set<String>? = null,
    cellSize: Dp = 14.dp,
    monthLabelHeight: Dp = 16.dp,
    cellSpacing: Dp = 2.dp,
    registerHitRect: Boolean = true,
) {
    val registry = LocalMorrowaScrollerRegistry.current
    val registryKey = remember { Any() }
    val lastBounds = remember { arrayOf<RectF?>(null) }
    DisposableEffect(registry, registerHitRect) {
        if (registerHitRect) {
            lastBounds[0]?.let { registry?.publish(registryKey, it) }
        } else {
            registry?.remove(registryKey)
        }
        onDispose {
            registry?.remove(registryKey)
        }
    }

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
        modifier = modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInRoot()
            lastBounds[0] = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
            if (registerHitRect) {
                registry?.publish(
                    registryKey,
                    lastBounds[0]!!,
                )
            } else {
                registry?.remove(registryKey)
            }
        },
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
                        // The label ("1月") is wider and taller than the cell-sized reserved slot.
                        // The slot keeps every week column's day cells vertically aligned, so instead
                        // of shrinking it (which clipped "月" horizontally and the digit's bottom half
                        // vertically) we let the text overflow the slot without being measured into it.
                        Text(
                            text = label,
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible,
                            modifier = Modifier.wrapContentSize(
                                align = Alignment.CenterStart,
                                unbounded = true,
                            ),
                        )
                    }
                }
                repeat(7) { dayOffset ->
                    val date = weekStart.plusDays(dayOffset.toLong())
                    val habitDay = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                    val isFuture = habitDay > todayHabitDay
                    val isTargetDay = targetDays == null || habitDay in targetDays
                    val color = when {
                        isFuture -> Color(0x15FFFFFF)
                        dayDensities != null -> dayDensities[habitDay]?.let { density ->
                            Color(0xFF8FD7A3).copy(
                                alpha = 0.30f + 0.70f * density.coerceIn(0f, 1f),
                            )
                        } ?: Color(0x40FFFFFF)
                        habitDay in completedDays -> Color(0xFF8FD7A3)
                        !isTargetDay -> Color(0x20FFFFFF)
                        else -> Color(0x40FFFFFF)
                    }
                    val cellModifier = Modifier
                        .size(cellSize)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color)
                        .let { base ->
                            // Non-target cells stay tappable while completed so stray
                            // check-ins made before the off-day lockout can be cleared.
                            val canToggle = !isFuture && onDayToggle != null &&
                                (isTargetDay || habitDay in completedDays)
                            if (canToggle) {
                                base.clickable { onDayToggle!!(habitDay) }
                            } else {
                                base
                            }
                        }

                    Box(modifier = cellModifier)
                }
            }
        }
    }
}
