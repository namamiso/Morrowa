package app.morrowa

enum class MorrowaPage(val storedValue: String) {
    HOME("home"),
    HABIT("habit"),
    TODO("todo"),
    WIDGET_BLANK("widget_blank"),
    ;

    fun next(): MorrowaPage = when (this) {
        HOME -> HABIT
        HABIT -> TODO
        TODO -> WIDGET_BLANK
        WIDGET_BLANK -> HOME
    }

    fun previous(): MorrowaPage = when (this) {
        HOME -> WIDGET_BLANK
        HABIT -> HOME
        TODO -> HABIT
        WIDGET_BLANK -> TODO
    }

    companion object {
        fun fromStoredValue(value: String?): MorrowaPage =
            entries.firstOrNull { it.storedValue == value } ?: HOME
    }
}
