package net.activitywatch.android.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.threeten.bp.Instant

class AwTimestampTest {
    @Test
    fun parsesMillisecondTimestamp() {
        assertEquals(
            Instant.parse("2026-10-08T10:00:00.123Z"),
            parseAwTimestamp("2026-10-08T10:00:00.123Z"),
        )
    }

    @Test
    fun parsesWholeSecondTimestampWithoutFraction() {
        // chrono omits the fraction entirely when it is zero.
        assertEquals(
            Instant.parse("2026-10-08T10:00:00Z"),
            parseAwTimestamp("2026-10-08T10:00:00Z"),
        )
    }

    @Test
    fun parsesMicroAndNanosecondPrecision() {
        assertEquals(
            Instant.ofEpochSecond(Instant.parse("2026-10-08T10:00:00Z").epochSecond, 123_456_000),
            parseAwTimestamp("2026-10-08T10:00:00.123456Z"),
        )
        assertEquals(
            Instant.ofEpochSecond(Instant.parse("2026-10-08T10:00:00Z").epochSecond, 123_456_789),
            parseAwTimestamp("2026-10-08T10:00:00.123456789Z"),
        )
    }

    @Test
    fun parsesNumericOffset() {
        assertEquals(
            Instant.parse("2026-10-08T08:00:00Z"),
            parseAwTimestamp("2026-10-08T10:00:00+02:00"),
        )
    }

    @Test
    fun returnsNullForGarbage() {
        assertNull(parseAwTimestamp("not a timestamp"))
        assertNull(parseAwTimestamp(""))
    }
}
