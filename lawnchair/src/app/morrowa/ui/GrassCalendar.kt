package app.morrowa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
fun GrassCalendar(
    completedDays: Set<String>,
    todayHabitDay: String,
    onDayToggle: ((habitDay: String) -> Unit)?,
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

    // The grid lives inside a Morrowa Habit/ToDo page, which is a normal Workspace (PagedView)
    // page. A horizontal drag on the grid would otherwise be intercepted by the workspace and
    // turned into a home-screen page swipe. Once we detect the drag is horizontal, tell the
    // parent chain (up to the Workspace) not to intercept, so the grid's own scroll always wins.
    // Vertical gestures are left untouched, so full-screen swipe actions still work off the grid.
    val hostView = LocalView.current
    LazyRow(
        modifier = modifier.pointerInput(Unit) {
            val touchSlop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var totalX = 0f
                var totalY = 0f
                var claimed = false
                while (!claimed) {
                    // Observe on the Initial pass so we decide before the LazyRow consumes moves.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) break
                    totalX += change.positionChange().x
                    totalY += change.positionChange().y
                    if (abs(totalX) > touchSlop && abs(totalX) >= abs(totalY)) {
                        hostView.parent?.requestDisallowInterceptTouchEvent(true)
                        claimed = true
                    }
                }
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
                            if (isFuture || onDayToggle == null) {
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
