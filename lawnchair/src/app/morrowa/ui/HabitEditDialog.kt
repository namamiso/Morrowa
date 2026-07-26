package app.morrowa.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.morrowa.HabitFrequency
import app.morrowa.data.HabitEntity

@Composable
fun HabitEditDialog(
    initial: HabitEntity? = null,
    onSave: (
        name: String,
        memo: String,
        ruleType: String,
        weekdays: List<Int>,
        monthDays: List<Int>,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var memo by remember(initial?.id) { mutableStateOf(initial?.memo.orEmpty()) }
    var ruleType by remember(initial?.id) { mutableStateOf(HabitFrequency.RULE_DAILY) }
    var weekdays by remember(initial?.id) { mutableStateOf(emptySet<Int>()) }
    var monthDays by remember(initial?.id) { mutableStateOf(emptySet<Int>()) }

    val canSave = name.trim().isNotEmpty() &&
        when (ruleType) {
            HabitFrequency.RULE_WEEKLY -> weekdays.isNotEmpty()
            HabitFrequency.RULE_MONTHLY -> monthDays.isNotEmpty()
            else -> true
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        name.trim(),
                        memo.trim(),
                        ruleType,
                        weekdays.sorted(),
                        monthDays.sorted(),
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
            Text(text = if (initial == null) "Habit を追加" else "Habit を編集")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    label = { Text(text = "名前") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = memo,
                    onValueChange = { memo = it.replace("\n", "").replace("\r", "").take(100) },
                    label = { Text(text = "メモ") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "頻度", fontWeight = FontWeight.SemiBold)
                    RuleTypeOption(
                        label = "毎日",
                        selected = ruleType == HabitFrequency.RULE_DAILY,
                        onClick = { ruleType = HabitFrequency.RULE_DAILY },
                    )
                    RuleTypeOption(
                        label = "毎週",
                        selected = ruleType == HabitFrequency.RULE_WEEKLY,
                        onClick = { ruleType = HabitFrequency.RULE_WEEKLY },
                    )
                    RuleTypeOption(
                        label = "毎月",
                        selected = ruleType == HabitFrequency.RULE_MONTHLY,
                        onClick = { ruleType = HabitFrequency.RULE_MONTHLY },
                    )
                }

                if (ruleType == HabitFrequency.RULE_WEEKLY) {
                    WeekdaySelector(
                        selected = weekdays,
                        onToggle = { weekday ->
                            weekdays = weekdays.toggle(weekday)
                        },
                    )
                }

                if (ruleType == HabitFrequency.RULE_MONTHLY) {
                    MonthDaySelector(
                        selected = monthDays,
                        onToggle = { day ->
                            monthDays = monthDays.toggle(day)
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun RuleTypeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label)
    }
}

@Composable
private fun WeekdaySelector(
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
) {
    val weekdays = listOf(
        0 to "日",
        1 to "月",
        2 to "火",
        3 to "水",
        4 to "木",
        5 to "金",
        6 to "土",
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "曜日", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            weekdays.forEach { (value, label) ->
                CheckboxCell(
                    label = label,
                    checked = value in selected,
                    onClick = { onToggle(value) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MonthDaySelector(
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "日付", fontWeight = FontWeight.SemiBold)
        (1..31).chunked(7).forEach { rowDays ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowDays.forEach { day ->
                    CheckboxCell(
                        label = day.toString(),
                        checked = day in selected,
                        onClick = { onToggle(day) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(7 - rowDays.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CheckboxCell(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label)
            Checkbox(
                checked = checked,
                onCheckedChange = { onClick() },
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

private fun Set<Int>.toggle(value: Int): Set<Int> =
    if (value in this) {
        this - value
    } else {
        this + value
    }
