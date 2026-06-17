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
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
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

private val HabitBackground = Color(0xFF12253F)

@Composable
fun HabitScreen(viewModel: HabitViewModel) {
    val habits by viewModel.activeHabits.collectAsState()
    val completions by viewModel.completions.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingHabit by remember { mutableStateOf<HabitEntity?>(null) }
    var confirmAction by remember { mutableStateOf<ConfirmAction?>(null) }
    var selectedHabitId by remember { mutableStateOf<Long?>(null) }
    var alarmTarget by remember { mutableStateOf<HabitEntity?>(null) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF8FD7A3),
            surface = Color(0xFF1B2F4D),
            onSurface = Color.White,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HabitBackground)
                .padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "Habit",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(24.dp))

                if (habits.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Habit がありません",
                            color = Color.White.copy(alpha = 0.72f),
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
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
                modifier = Modifier.align(Alignment.BottomEnd),
            ) {
                Icon(imageVector = Icons.Rounded.Add, contentDescription = "追加")
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
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onToggle,
                    onLongClick = { menuExpanded = true },
                ),
            shape = RoundedCornerShape(16.dp),
            color = if (completed) {
                Color.White.copy(alpha = 0.18f)
            } else {
                Color.White.copy(alpha = 0.08f)
            },
            border = if (completed) {
                BorderStroke(1.dp, Color(0xFF8FD7A3).copy(alpha = 0.72f))
            } else {
                null
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = habit.name,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = if (completed) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(16.dp))
                IconButton(
                    onClick = onCalendarClick,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CalendarMonth,
                        contentDescription = "カレンダー",
                        tint = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = if (completed) {
                        Color(0xFF8FD7A3)
                    } else {
                        Color.White.copy(alpha = 0.32f)
                    },
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
