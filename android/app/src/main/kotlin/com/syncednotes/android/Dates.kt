package com.syncednotes.android

import android.content.Context
import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * When a note was edited, as Apple Notes says it: the time today, "Yesterday", the weekday
 * within the week, and the date before that. A relative "0 minutes ago" changes under the
 * reader's eyes and says less than the clock time does.
 */
fun Context.listDate(at: Instant, now: Instant = Instant.now()): String {
    val zone = ZoneId.systemDefault()
    val day = at.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val locale = resources.configuration.locales[0] ?: Locale.getDefault()
    return when {
        day == today -> DateFormat.getTimeFormat(this).format(java.util.Date.from(at))
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) && !day.isAfter(today) -> day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(day)
    }
}

/** How long since sync last compared notes, in words that do not tick by the second. */
fun sinceInWords(at: Instant, now: Instant = Instant.now()): String {
    val seconds = java.time.Duration.between(at, now).seconds
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        LocalDate.now() == at.atZone(ZoneId.systemDefault()).toLocalDate() -> "today"
        else -> "earlier"
    }
}
