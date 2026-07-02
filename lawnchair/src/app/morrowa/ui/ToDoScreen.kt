package app.morrowa.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.morrowa.ToDoViewModel
import app.morrowa.data.ToDoEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val ToDoBackground = Color.Transparent
private val ToDoContainerColor = Color(0xCC1A1A1A)
private val ToDoZoneId = ZoneId.of("Asia/Tokyo")

@Composable
fun ToDoScreen(viewModel: ToDoViewModel) {
    val todos by viewModel.activeTodos.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingTodo by remember { mutableStateOf<ToDoEntity?>(null) }
    var deletingTodo by remember { mutableStateOf<ToDoEntity?>(null) }
    var alarmTarget by remember { mutableStateOf<ToDoEntity?>(null) }
    var showTrash by remember { mutableStateOf(false) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF8FD7A3),
            surface = Color.Transparent,
            onSurface = Color.White,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ToDoContainerColor, RoundedCornerShape(20.dp))
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "ToDo",
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showTrash = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "ゴミ箱",
                            tint = Color.White.copy(alpha = 0.72f),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))

                if (todos.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "ToDo がありません",
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
                            items = todos,
                            key = { todo -> todo.id },
                        ) { todo ->
                            ToDoItem(
                                todo = todo,
                                onEdit = { editingTodo = todo },
                                onAlarmClick = { alarmTarget = todo },
                                onDelete = { deletingTodo = todo },
                            )
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                containerColor = ToDoContainerColor,
                contentColor = Color(0xFF8FD7A3),
            ) {
                Icon(imageVector = Icons.Rounded.Add, contentDescription = "追加")
            }

            if (showTrash) {
                val deletedTodos by viewModel.deletedTodos.collectAsState()
                ToDoTrashScreen(
                    deletedTodos = deletedTodos,
                    onRestore = { viewModel.restoreTodo(it) },
                    onDeletePermanently = { viewModel.deleteTodoPermanently(it) },
                    onBack = { showTrash = false },
                    backgroundColor = ToDoBackground,
                )
            }
        }

        if (showAddDialog || editingTodo != null) {
            val target = editingTodo
            ToDoEditDialog(
                initial = target,
                onSave = { title, memo, scheduledDate ->
                    if (target == null) {
                        viewModel.addTodo(
                            title = title,
                            memo = memo,
                            scheduledDate = scheduledDate,
                        )
                    } else {
                        viewModel.updateTodo(
                            target.copy(
                                title = title,
                                memo = memo,
                                scheduledDate = scheduledDate,
                            ),
                        )
                    }
                    showAddDialog = false
                    editingTodo = null
                },
                onDismiss = {
                    showAddDialog = false
                    editingTodo = null
                },
            )
        }

        deletingTodo?.let { todo ->
            ConfirmDialog(
                title = "削除",
                message = "「${todo.title}」をゴミ箱に移動しますか？",
                onConfirm = {
                    viewModel.moveToTrash(todo.id)
                    deletingTodo = null
                },
                onDismiss = { deletingTodo = null },
            )
        }

        alarmTarget?.let { todo ->
            val alarm by viewModel.getAlarmFlow(todo.id).collectAsState(initial = null)
            val ctx = LocalContext.current
            AlarmEditDialog(
                initialHour = alarm?.hour ?: 8,
                initialMinute = alarm?.minute ?: 0,
                onSave = { h, m ->
                    viewModel.setAlarm(ctx, todo.id, todo.title, h, m)
                    alarmTarget = null
                },
                onDelete = if (alarm != null) {
                    {
                        viewModel.deleteAlarm(ctx, todo.id)
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
private fun ToDoItem(
    todo: ToDoEntity,
    onEdit: () -> Unit,
    onAlarmClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val scheduledDate = todo.scheduledDate
    val isOverdue = scheduledDate?.let { isScheduledDateOverdue(it) } == true

    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onEdit,
                    onLongClick = { menuExpanded = true },
                ),
            shape = RoundedCornerShape(16.dp),
            color = ToDoContainerColor,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = todo.title,
                    color = if (isOverdue) Color(0xFFFF8A80) else Color.White,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                scheduledDate?.let {
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = formatScheduledDate(it),
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 14.sp,
                    )
                }
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
                text = { Text(text = "削除") },
                onClick = {
                    menuExpanded = false
                    onDelete()
                },
            )
        }
    }
}

private fun formatScheduledDate(epochMillis: Long): String {
    val scheduledLocalDate = Instant.ofEpochMilli(epochMillis)
        .atZone(ToDoZoneId)
        .toLocalDate()
    return "${scheduledLocalDate.monthValue}/${scheduledLocalDate.dayOfMonth}"
}

private fun isScheduledDateOverdue(epochMillis: Long): Boolean {
    val today = LocalDate.now(ToDoZoneId)
    val scheduledLocalDate = Instant.ofEpochMilli(epochMillis)
        .atZone(ToDoZoneId)
        .toLocalDate()
    return scheduledLocalDate.isBefore(today)
}
