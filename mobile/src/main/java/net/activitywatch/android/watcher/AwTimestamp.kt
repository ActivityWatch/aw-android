package net.activitywatch.android.watcher

import org.threeten.bp.Instant
import org.threeten.bp.OffsetDateTime
import org.threeten.bp.format.DateTimeFormatter
import org.threeten.bp.format.DateTimeParseException

// aw-server-rust serializes event timestamps with chrono, which writes 0, 3, 6 or 9
// fractional-second digits: a timestamp on a whole second has no fraction at all
// ("2026-10-08T10:00:00Z"). A fixed ".SSS" pattern rejects that form, so accept any
// precision. Returns null for anything that is not an ISO-8601 offset timestamp.
internal fun parseAwTimestamp(timestamp: String): Instant? =
    try {
        OffsetDateTime.parse(timestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
    } catch (e: DateTimeParseException) {
        null
    }
