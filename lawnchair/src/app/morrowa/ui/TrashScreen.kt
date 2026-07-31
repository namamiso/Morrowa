package app.morrowa.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.morrowa.data.HabitEntity
import app.morrowa.data.ToDoEntity

internal val TrashBackground =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Color(0x991A1A1A)
    else Color(0xF21A1A1A)

@Composable
fun HabitTrashScreen(
    deletedHabits: List<HabitEntity>,
    onRestore: (habitId: Long) -> Unit,
    onDeletePermanently: (habitId: Long) -> Unit,
    onBack: () -> Unit,
    backgroundColor: Color,
) {
    TrashContent(
        title = "習慣のゴミ箱",
        emptyMessage = "削除した習慣はありません",
        items = deletedHabits.map { habit ->
            TrashItem(
                id = habit.id,
                label = habit.name,
                deletedAt = habit.deletedAt,
            )
        },
        onRestore = onRestore,
        onDeletePermanently = onDeletePermanently,
        onBack = onBack,
        backgroundColor = backgroundColor,
    )
}

@Composable
fun ToDoTrashScreen(
    deletedTodos: List<ToDoEntity>,
    onRestore: (todoId: Long) -> Unit,
    onDeletePermanently: (todoId: Long) -> Unit,
    onBack: () -> Unit,
    backgroundColor: Color,
) {
    TrashContent(
        title = "ToDo のゴミ箱",
        emptyMessage = "削除した ToDo はありません",
        items = deletedTodos.map { todo ->
            TrashItem(
                id = todo.id,
                label = todo.title,
                deletedAt = todo.deletedAt,
            )
        },
        onRestore = onRestore,
        onDeletePermanently = onDeletePermanently,
        onBack = onBack,
        backgroundColor = backgroundColor,
    )
}

private data class TrashItem(
    val id: Long,
    val label: String,
    val deletedAt: Long?,
)

@Composable
private fun TrashContent(
    title: String,
    emptyMessage: String,
    items: List<TrashItem>,
    onRestore: (Long) -> Unit,
    onDeletePermanently: (Long) -> Unit,
    onBack: () -> Unit,
    backgroundColor: Color,
) {
    var confirmDeleteId by remember { mutableStateOf<Long?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "戻る",
                        tint = Color.White,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = emptyMessage, color = Color.White.copy(alpha = 0.72f))
                }
            } else {
                val trashListState = rememberLazyListState()
                LazyColumn(
                    state = trashListState,
                    modifier = Modifier.morrowaVerticalScrollRegion(trashListState),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        TrashItemRow(
                            item = item,
                            onRestore = { onRestore(item.id) },
                            onDeletePermanently = { confirmDeleteId = item.id },
                        )
                    }
                }
            }
        }
    }

    confirmDeleteId?.let { id ->
        val label = items.firstOrNull { it.id == id }?.label ?: ""
        ConfirmDialog(
            title = "完全に削除",
            message = "「$label」を完全に削除しますか？この操作は取り消せません。",
            onConfirm = {
                onDeletePermanently(id)
                confirmDeleteId = null
            },
            onDismiss = { confirmDeleteId = null },
        )
    }
}

@Composable
private fun TrashItemRow(
    item: TrashItem,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit,
) {
    val daysLeft = item.deletedAt?.let { deletedAt ->
        val thirtyDays = 30L * 24 * 60 * 60 * 1000
        val remaining = (deletedAt + thirtyDays - System.currentTimeMillis()) /
            (24 * 60 * 60 * 1000)
        remaining.coerceAtLeast(0)
    }

    Surface(
        color = Color.White.copy(alpha = 0.08f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.label,
                    color = Color.White,
                    fontSize = 16.sp,
                    maxLines = 1,
                )
                daysLeft?.let {
                    Text(
                        text = "${it}日後に自動削除",
                        color = Color.White.copy(alpha = 0.56f),
                        fontSize = 12.sp,
                    )
                }
            }
            TextButton(onClick = onRestore) {
                Text("復元", color = Color(0xFF8FD7A3))
            }
            TextButton(onClick = onDeletePermanently) {
                Text("削除", color = Color(0xFFFF8A80))
            }
        }
    }
}
