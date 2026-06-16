package app.morrowa

import app.morrowa.data.HabitRuleEntity
import java.time.LocalDate
import org.json.JSONArray

object HabitFrequency {
    const val RULE_DAILY = "DAILY"
    const val RULE_WEEKLY = "WEEKLY"
    const val RULE_MONTHLY = "MONTHLY"

    fun isTargetDay(rule: HabitRuleEntity, habitDay: String): Boolean {
        if (!isRuleEffectiveOn(rule, habitDay)) {
            return false
        }
        return isTargetDay(
            ruleType = rule.ruleType,
            weekdays = parseIntList(rule.weekdays),
            monthDays = parseIntList(rule.monthDays),
            habitDay = habitDay,
        )
    }

    fun isTargetDay(
        ruleType: String,
        weekdays: List<Int>,
        monthDays: List<Int>,
        habitDay: String,
    ): Boolean {
        val date = runCatching { LocalDate.parse(habitDay) }.getOrNull() ?: return false
        return when (ruleType) {
            RULE_DAILY -> true
            RULE_WEEKLY -> weekdays.containsWeekday(date.dayOfWeek.value)
            RULE_MONTHLY -> date.dayOfMonth in monthDays
            else -> false
        }
    }

    fun parseIntList(json: String): List<Int> {
        if (json.isBlank()) {
            return emptyList()
        }
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optInt(index, Int.MIN_VALUE)
                if (value != Int.MIN_VALUE) {
                    add(value)
                }
            }
        }
    }

    private fun isRuleEffectiveOn(rule: HabitRuleEntity, habitDay: String): Boolean {
        if (habitDay < rule.startDate) {
            return false
        }
        return rule.endDate?.let { habitDay < it } ?: true
    }

    private fun List<Int>.containsWeekday(dayOfWeekValue: Int): Boolean {
        return dayOfWeekValue in this || (dayOfWeekValue == 7 && 0 in this)
    }
}
