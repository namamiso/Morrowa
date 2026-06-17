package app.morrowa.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun AlarmEditDialog(
    initialHour: Int = 8,
    initialMinute: Int = 0,
    onSave: (hour: Int, minute: Int) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var hour by remember { mutableIntStateOf(initialHour) }
    var minute by remember { mutableIntStateOf(initialMinute) }

    fun showTimePicker() {
        TimePickerDialog(
            context,
            { _, h, m ->
                hour = h
                minute = m
            },
            hour,
            minute,
            true,
        ).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("アラーム設定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("時刻: %02d:%02d".format(hour, minute))
                TextButton(onClick = { showTimePicker() }) {
                    Text("時刻を変更")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(hour, minute) }) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("削除")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("キャンセル")
                }
            }
        },
    )
}
