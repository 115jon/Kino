package com.nuvio.app.core.format

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal actual fun formatCalendarDate(isoDate: String, localeTag: String, includeYear: Boolean): String {
    val date = runCatching { LocalDate.parse(isoDate.take(10)) }.getOrNull() ?: return isoDate
    val pattern = if (includeYear) "d MMMM yyyy" else "d MMMM"
    return DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag(localeTag)).format(date)
}
