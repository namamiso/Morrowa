package app.morrowa.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.morrowa.data.ToDoEntity
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar

private val ToDoEditZoneId = ZoneId.of("Asia/Tokyo")

@Composable
fun ToDoEditDialog(
    initial: ToDoEntity? = null,
    onSave: (title: String, memo: String, scheduledDate: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var title by remember(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var memo by remember(initial?.id) { mutableStateOf(initial?.memo.orEmpty()) }
    var scheduledDateMillis by remember(initial?.id) {
        mutableStateOf(initial?.scheduledDate)
    }

    fun showDatePicker() {
        val calendar = Calendar.getInstance()
        scheduledDateMillis?.let { calendar.timeInMillis = it }
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val picked = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                scheduledDateMillis = picked.timeInMillis
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    val canSave = title.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        title.trim(),
                        memo.trim(),
                        scheduledDateMillis,
                    )
                },
            ) {
                Text(text = "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "キャンセル")
            }
        },
        title = {
            Text(text = if (initial == null) "ToDo を追加" else "ToDo を編集")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(100) },
                    label = { Text(text = "タイトル") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = memo,
                    onValueChange = { memo = it.take(100) },
                    label = { Text(text = "メモ") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { showDatePicker() }) {
                    Text(
                        text = scheduledDateMillis?.let { "${formatTodoEditDate(it)} ✕" }
                            ?: "予定日を設定",
                    )
                }
            }
        },
    )
}

private fun formatTodoEditDate(epochMillis: Long): String {
    val date = Instant.ofEpochMilli(epochMillis)
        .atZone(ToDoEditZoneId)
        .toLocalDate()
    return "${date.monthValue}/${date.dayOfMonth}"
}
