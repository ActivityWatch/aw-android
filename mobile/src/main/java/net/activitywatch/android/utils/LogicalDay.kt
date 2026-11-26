package net.activitywatch.android.utils

import org.threeten.bp.LocalDate
import org.threeten.bp.LocalTime
import org.threeten.bp.ZoneId
import org.threeten.bp.ZonedDateTime

/** The logical day [now] belongs to, using the same resolved boundary as [logicalDayWindow]. */
internal fun logicalDate(now: ZonedDateTime, startOfDay: LocalTime): LocalDate {
    val date = now.toLocalDate()
    val boundary = date.atTime(startOfDay).atZone(now.zone)
    return if (now.isBefore(boundary)) date.minusDays(1) else date
}

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
