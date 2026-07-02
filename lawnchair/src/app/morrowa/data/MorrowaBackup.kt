package app.morrowa.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class BackupData(
    val schemaVersion: Int = 1,
    val exportedAt: String,
    val habits: List<HabitEntity>,
    val habitRules: List<HabitRuleEntity>,
    val habitCompletions: List<HabitCompletionEntity>,
    val alarms: List<AlarmEntity>,
    val todos: List<ToDoEntity>,
)

object MorrowaBackup {
    private const val SCHEMA_VERSION = 1
    private const val APP_NAME = "Morrowa"

    fun serialize(data: BackupData): String {
        val root = JSONObject()
        val payload = JSONObject()
        root.put("app", APP_NAME)
        root.put("schema_version", data.schemaVersion)
        root.put("exported_at", data.exportedAt)

        payload.put("habits", JSONArray().also { arr ->
            data.habits.forEach { h ->
                arr.put(JSONObject().apply {
                    put("id", h.id)
                    put("name", h.name)
                    put("memo", h.memo)
                    put("sortOrder", h.sortOrder)
                    put("isArchived", h.isArchived)
                    putOpt("deletedAt", h.deletedAt)
                    put("createdAt", h.createdAt)
                    put("updatedAt", h.updatedAt)
                })
            }
        })

        payload.put("habit_rules", JSONArray().also { arr ->
            data.habitRules.forEach { r ->
                arr.put(JSONObject().apply {
                    put("id", r.id)
                    put("habitId", r.habitId)
                    put("ruleType", r.ruleType)
                    put("weekdays", r.weekdays)
                    put("monthDays", r.monthDays)
                    put("startDate", r.startDate)
                    putOpt("endDate", r.endDate)
                })
            }
        })

        payload.put("habit_completions", JSONArray().also { arr ->
            data.habitCompletions.forEach { c ->
                arr.put(JSONObject().apply {
                    put("id", c.id)
                    put("habitId", c.habitId)
                    put("habitDay", c.habitDay)
                    put("completedAt", c.completedAt)
                })
            }
        })

        payload.put("alarms", JSONArray().also { arr ->
            data.alarms.forEach { a ->
                arr.put(JSONObject().apply {
                    put("id", a.id)
                    put("targetType", a.targetType)
                    put("targetId", a.targetId)
                    put("hour", a.hour)
                    put("minute", a.minute)
                    put("isEnabled", a.isEnabled)
                    put("createdAt", a.createdAt)
                    put("updatedAt", a.updatedAt)
                })
            }
        })

        payload.put("todos", JSONArray().also { arr ->
            data.todos.forEach { t ->
                arr.put(JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("memo", t.memo)
                    putOpt("scheduledDate", t.scheduledDate)
                    put("sortOrder", t.sortOrder)
                    putOpt("deletedAt", t.deletedAt)
                    put("createdAt", t.createdAt)
                    put("updatedAt", t.updatedAt)
                })
            }
        })

        root.put("data", payload)
        return root.toString(2)
    }

    fun deserialize(json: String): BackupData {
        val root = JSONObject(json)
        val appName = root.optString("app", APP_NAME)
        require(appName == APP_NAME) {
            "Unsupported backup app: $appName"
        }
        val schemaVersion = root.getInt("schema_version")
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported backup schema version: $schemaVersion"
        }
        val exportedAt = root.getString("exported_at")
        val payload = root.optJSONObject("data") ?: root

        val habits = payload.getJSONArray("habits").let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HabitEntity(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    memo = o.optString("memo", ""),
                    sortOrder = o.getInt("sortOrder"),
                    isArchived = o.optBoolean("isArchived", false),
                    deletedAt = if (o.isNull("deletedAt")) null else o.getLong("deletedAt"),
                    createdAt = o.getLong("createdAt"),
                    updatedAt = o.getLong("updatedAt"),
                )
            }
        }

        val habitRules = payload.getJSONArray("habit_rules").let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HabitRuleEntity(
                    id = o.getLong("id"),
                    habitId = o.getLong("habitId"),
                    ruleType = o.getString("ruleType"),
                    weekdays = o.getString("weekdays"),
                    monthDays = o.getString("monthDays"),
                    startDate = o.getString("startDate"),
                    endDate = if (o.isNull("endDate")) null else o.getString("endDate"),
                )
            }
        }

        val habitCompletions = payload.getJSONArray("habit_completions").let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HabitCompletionEntity(
                    id = o.getLong("id"),
                    habitId = o.getLong("habitId"),
                    habitDay = o.getString("habitDay"),
                    completedAt = o.getLong("completedAt"),
                )
            }
        }

        val alarms = payload.getJSONArray("alarms").let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AlarmEntity(
                    id = o.getLong("id"),
                    targetType = o.getString("targetType"),
                    targetId = o.getLong("targetId"),
                    hour = o.getInt("hour"),
                    minute = o.getInt("minute"),
                    isEnabled = o.optBoolean("isEnabled", true),
                    createdAt = o.getLong("createdAt"),
                    updatedAt = o.getLong("updatedAt"),
                )
            }
        }

        val todos = payload.getJSONArray("todos").let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ToDoEntity(
                    id = o.getLong("id"),
                    title = o.getString("title"),
                    memo = o.optString("memo", ""),
                    scheduledDate = if (o.isNull("scheduledDate")) null else o.getLong("scheduledDate"),
                    sortOrder = o.getInt("sortOrder"),
                    deletedAt = if (o.isNull("deletedAt")) null else o.getLong("deletedAt"),
                    createdAt = o.getLong("createdAt"),
                    updatedAt = o.getLong("updatedAt"),
                )
            }
        }

        return BackupData(
            schemaVersion = schemaVersion,
            exportedAt = exportedAt,
            habits = habits,
            habitRules = habitRules,
            habitCompletions = habitCompletions,
            alarms = alarms,
            todos = todos,
        )
    }

    fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))
}
