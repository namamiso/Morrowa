package app.morrowa.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.morrowa.HabitViewModel
import app.morrowa.data.HabitEntity
import java.time.LocalDate
import java.time.ZoneId

private val HabitBackground = Color(0xFF111711)
private val HabitAccent = Color(0xFF78A980)
private val HabitChip = Color(0xFF9A8465)

@Composable
fun HabitScreen(viewModel: HabitViewModel) {
    val habits by viewModel.activeHabits.collectAsState()
    val completions by viewModel.completions.collectAsState()
    val todayHabitDay by viewModel.habitDay.collectAsState()
    val today = remember(todayHabitDay) {
        LocalDate.parse(todayHabitDay)
    }
    val currentYear = LocalDate.now(ZoneId.of("Asia/Tokyo")).year
    var showAddDialog by remember { mutableStateOf(false) }
    var editingHabit by remember { mutableStateOf<HabitEntity?>(null) }
    var confirmAction by remember { mutableStateOf<ConfirmAction?>(null) }
    var selectedHabitId by remember { mutableStateOf<Long?>(null) }
    var alarmTarget by remember { mutableStateOf<HabitEntity?>(null) }
    var showTrash by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = HabitAccent,
            surface = Color(0xFF202820),
            onSurface = Color(0xFFE6E8E1),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HabitBackground),
        ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 18.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Morrowa",
                        color = Color(0xFFE6E8E1),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showBackup = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Backup,
                            contentDescription = "バックアップ",
                            tint = Color(0xFFE6E8E1).copy(alpha = 0.72f),
                        )
                    }
                    IconButton(onClick = { showTrash = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "ゴミ箱",
                            tint = Color(0xFFE6E8E1).copy(alpha = 0.72f),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))

                YearSelector(currentYear = currentYear)
                Spacer(modifier = Modifier.height(10.dp))

                GrassCalendar(
                    completedDays = emptySet(),
                    todayHabitDay = todayHabitDay,
                    onDayToggle = {},
                    cellSize = 9.dp,
                    monthLabelHeight = 12.dp,
                    cellSpacing = 2.dp,
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "%04d/%02d/%02d（%s）".format(
                            today.year,
                            today.monthValue,
                            today.dayOfMonth,
                            japaneseDayOfWeek(today),
                        ),
                        color = Color(0xFFE6E8E1),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "今日",
                        color = HabitAccent,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                if (habits.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Habit がありません",
                            color = Color(0xFFE6E8E1).copy(alpha = 0.72f),
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 64.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = habits,
                            key = { habit -> habit.id },
                        ) { habit ->
                            Column {
                                HabitItem(
                                    habit = habit,
                                    completed = completions[habit.id] == true,
                                    onToggle = { viewModel.checkHabit(habit.id) },
                                    onCalendarClick = {
                                        selectedHabitId = if (selectedHabitId == habit.id) {
                                            null
                                        } else {
                                            habit.id
                                        }
                                    },
                                    onEdit = { editingHabit = habit },
                                    onAlarmClick = { alarmTarget = habit },
                                    onArchive = {
                                        confirmAction = ConfirmAction(
                                            habit = habit,
                                            kind = ConfirmActionKind.Archive,
                                        )
                                    },
                                    onDelete = {
                                        confirmAction = ConfirmAction(
                                            habit = habit,
                                            kind = ConfirmActionKind.Delete,
                                        )
                                    },
                                )
                                if (selectedHabitId == habit.id) {
                                    val completionsList by viewModel.getCompletionsFlow(habit.id)
                                        .collectAsState(initial = emptyList())
                                    val todayHabitDay by viewModel.habitDay.collectAsState()
                                    GrassCalendar(
                                        completedDays = completionsList.map { it.habitDay }.toSet(),
                                        todayHabitDay = todayHabitDay,
                                        onDayToggle = { day ->
                                            viewModel.toggleCompletionForDay(habit.id, day)
                                        },
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                containerColor = Color(0xFF4F875D),
                contentColor = Color(0xFFE6E8E1),
            ) {
                Icon(imageVector = Icons.Rounded.Add, contentDescription = "追加")
            }

            if (showTrash) {
                val deletedHabits by viewModel.deletedHabits.collectAsState()
                HabitTrashScreen(
                    deletedHabits = deletedHabits,
                    onRestore = { viewModel.restoreHabit(it) },
                    onDeletePermanently = { viewModel.deleteHabitPermanently(it) },
                    onBack = { showTrash = false },
                    backgroundColor = HabitBackground,
                )
            }

            if (showBackup) {
                BackupScreen(
                    backgroundColor = HabitBackground,
                    onBack = { showBackup = false },
                )
            }
        }

        if (showAddDialog || editingHabit != null) {
            val target = editingHabit
            HabitEditDialog(
                initial = target,
                onSave = { name, memo, ruleType, weekdays, monthDays ->
                    if (target == null) {
                        viewModel.addHabit(
                            name = name,
                            memo = memo,
                            ruleType = ruleType,
                            weekdays = weekdays,
                            monthDays = monthDays,
                        )
                    } else {
                        viewModel.updateHabit(target.copy(name = name, memo = memo))
                    }
                    showAddDialog = false
                    editingHabit = null
                },
                onDismiss = {
                    showAddDialog = false
                    editingHabit = null
                },
            )
        }

        confirmAction?.let { action ->
            ConfirmDialog(
                title = action.kind.title,
                message = "「${action.habit.name}」を${action.kind.messageAction}しますか？",
                onConfirm = {
                    when (action.kind) {
                        ConfirmActionKind.Archive -> viewModel.archiveHabit(action.habit.id)
                        ConfirmActionKind.Delete -> viewModel.moveToTrash(action.habit.id)
                    }
                    confirmAction = null
                },
                onDismiss = { confirmAction = null },
            )
        }

        alarmTarget?.let { habit ->
            val alarm by viewModel.getAlarmFlow(habit.id).collectAsState(initial = null)
            val ctx = LocalContext.current
            AlarmEditDialog(
                initialHour = alarm?.hour ?: 8,
                initialMinute = alarm?.minute ?: 0,
                onSave = { h, m ->
                    viewModel.setAlarm(ctx, habit.id, habit.name, h, m)
                    alarmTarget = null
                },
                onDelete = if (alarm != null) {
                    {
                        viewModel.deleteAlarm(ctx, habit.id)
                        alarmTarget = null
                    }
                } else {
                    null
                },
                onDismiss = { alarmTarget = null },
            )
        }
    }
}

@Composable
private fun YearSelector(currentYear: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(currentYear - 1, currentYear, currentYear + 1).forEach { year ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (year == currentYear) HabitChip else Color.Transparent,
                border = if (year == currentYear) {
                    null
                } else {
                    BorderStroke(1.dp, Color(0xFFE6E8E1).copy(alpha = 0.58f))
                },
            ) {
                Text(
                    text = year.toString(),
                    color = Color(0xFFE6E8E1).copy(alpha = if (year == currentYear) 1f else 0.82f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                )
            }
        }
    }
}

private fun japaneseDayOfWeek(date: LocalDate): String = when (date.dayOfWeek.value) {
    1 -> "月"
    2 -> "火"
    3 -> "水"
    4 -> "木"
    5 -> "金"
    6 -> "土"
    else -> "日"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HabitItem(
    habit: HabitEntity,
    completed: Boolean,
    onToggle: () -> Unit,
    onCalendarClick: () -> Unit,
    onEdit: () -> Unit,
    onAlarmClick: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .combinedClickable(
                    onClick = onToggle,
                    onLongClick = { menuExpanded = true },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(Color.Transparent, RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.size(30.dp),
                    shape = RoundedCornerShape(3.dp),
                    color = Color.Transparent,
                    border = BorderStroke(
                        3.dp,
                        if (completed) HabitAccent else Color(0xFFE6E8E1).copy(alpha = 0.84f),
                    ),
                ) {
                    if (completed) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = HabitAccent,
                            modifier = Modifier.padding(5.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = habit.name,
                color = Color(0xFFE6E8E1),
                fontSize = 18.sp,
                fontWeight = if (completed) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onCalendarClick,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.CalendarMonth,
                    contentDescription = "カレンダー",
                    tint = Color(0xFFE6E8E1).copy(alpha = 0.38f),
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = "メニュー",
                    tint = Color(0xFFE6E8E1).copy(alpha = 0.38f),
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(text = "編集") },
                onClick = {
                    menuExpanded = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(text = "アラーム設定") },
                onClick = {
                    menuExpanded = false
                    onAlarmClick()
                },
            )
            DropdownMenuItem(
                text = { Text(text = "アーカイブ") },
                onClick = {
                    menuExpanded = false
                    onArchive()
                },
            )
            DropdownMenuItem(
                text = { Text(text = "削除") },
                onClick = {
                    menuExpanded = false
                    onDelete()
                },
            )
        }
    }
}

private data class ConfirmAction(
    val habit: HabitEntity,
    val kind: ConfirmActionKind,
)

private enum class ConfirmActionKind(
    val title: String,
    val messageAction: String,
) {
    Archive(title = "アーカイブ", messageAction = "アーカイブ"),
    Delete(title = "削除", messageAction = "削除"),
}
