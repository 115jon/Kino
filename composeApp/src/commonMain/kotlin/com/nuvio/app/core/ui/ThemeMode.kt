package com.nuvio.app.core.ui

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromKey(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
