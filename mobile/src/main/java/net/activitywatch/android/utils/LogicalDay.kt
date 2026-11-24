package net.activitywatch.android.utils

import org.threeten.bp.LocalDate
import org.threeten.bp.LocalDateTime
import org.threeten.bp.LocalTime
import org.threeten.bp.ZoneId
import org.threeten.bp.ZonedDateTime

/** The logical day [now] belongs to: before [startOfDay] it is still the previous day's period. */
internal fun logicalDate(now: LocalDateTime, startOfDay: LocalTime): LocalDate =
    if (now.toLocalTime() < startOfDay) now.toLocalDate().minusDays(1) else now.toLocalDate()

/**
 * The [start, end) window of logical day [date] in [zone].
 *
 * Both ends sit at wall-clock [startOfDay], so a DST day lasts 23 or 25 hours and adjacent days
 * share their boundary. Adding hours to midnight instead would shift the boundary by the DST
 * offset when the transition falls between midnight and [startOfDay].
 */
internal fun logicalDayWindow(
    date: LocalDate,
    startOfDay: LocalTime,
    zone: ZoneId,
): Pair<ZonedDateTime, ZonedDateTime> =
    date.atTime(startOfDay).atZone(zone) to date.plusDays(1).atTime(startOfDay).atZone(zone)
