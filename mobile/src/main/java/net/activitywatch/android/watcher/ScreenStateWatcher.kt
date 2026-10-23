package net.activitywatch.android.watcher

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlin.math.roundToLong
import net.activitywatch.android.RustInterface
import net.activitywatch.android.data.UsageEvent
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.threeten.bp.Instant
import org.threeten.bp.OffsetDateTime
import org.threeten.bp.format.DateTimeFormatter
import org.threeten.bp.format.DateTimeParseException

const val SCREEN_STATE_BUCKET_ID = "aw-watcher-android-screen"
const val SCREEN_STATE_BUCKET_TYPE = "os.screen.state"

/**
 * Records whether the screen is interactive and whether the keyguard is shown, read after
 * the fact from Android's usage events, so a run missed while the app was stopped is caught
 * up on the next one.
 *
 * Each event in [SCREEN_STATE_BUCKET_ID] is a span of constant state, with data
 * `{"screen": "interactive" | "non-interactive", "keyguard": "shown" | "hidden"}`. A new
 * span starts whenever either value changes, for as little as a millisecond: an unlock
 * usually passes through a moment with the screen interactive and the keyguard still shown,
 * or, with a fingerprint from screen-off, with the keyguard hidden and the screen not yet
 * interactive. Changes within the same millisecond count only by their result, so an unlock
 * whose two changes share one leaves no span in between.
 *
 * - `screen` is Android's interactive state, not whether the display is lit: an always-on
 *   display is lit while non-interactive.
 * - `keyguard` is whether the lock screen is shown; an app shown over it (camera, calls)
 *   leaves it shown.
 * - There is no event while either value is unknown: before the first events in Android's
 *   history, and from a shutdown until the events recorded at the next startup.
 * - The last span runs until the time of the read and is extended by the next one.
 * - Spans never overlap. A change that Android adds to its history after a read but dates
 *   before it is placed at the end of the span that read stored, up to a couple of seconds
 *   late.
 *
 * Not covered:
 * - a gap between reads longer than the history Android keeps: nothing in the data tells it
 *   from a stretch with no events, so the last span runs on to the first event still kept;
 * - a clock correction of more than a couple of seconds, manual or from the network: Android
 *   moves the history it has saved, and putting the stored spans right would mean changing
 *   them, while the datastore calls used here only add events or lengthen the last one;
 * - a second late change that appears only at a later read, dated before the span placed at
 *   the end of the stored one: rare, and catching it would need a look-back of arbitrary
 *   length or a cursor kept outside the bucket, so it is missed until its state changes again;
 * - a run stopped between two writes just after a startup: rare, and the writes cannot be
 *   made all at once, so the startup can be taken for a new one and the time until the next
 *   screen event lost;
 * - Android before version 10, which records no shutdowns: nothing is recorded there.
 */
class ScreenStateWatcher(private val context: Context) {

    companion object {
        private const val TAG = "ScreenStateWatcher"
    }

    fun sendSinceLastStored(ri: RustInterface) {
        // Without shutdown events the span open when the phone is turned off would run on
        // until it is turned on again.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        // Without usage access Android returns no events at all, which would read as no change
        // and extend the last span to now.
        if (!UsageStatsWatcher.isUsageAllowed(context)) return
        try {
            send(ri)
        } catch (e: Exception) {
            // A failure here must not stop the caller's own ingest.
            Log.e(TAG, "Screen state ingest failed", e)
        }
    }

    private fun send(ri: RustInterface) {
        ri.createBucketHelper(SCREEN_STATE_BUCKET_ID, SCREEN_STATE_BUCKET_TYPE)

        val last = parseLastEvent(ri.getEvents(SCREEN_STATE_BUCKET_ID, 1)).getOrElse {
            Log.e(TAG, "Could not read the last screen state event, skipping this run", it)
            return
        }
        val resume = resumePoint(last) ?: run {
            Log.e(TAG, "Unreadable time in the last screen state event, skipping this run: $last")
            return
        }
        if (last != null && resume.stored == null) {
            Log.w(TAG, "Unknown data in the last screen state event, resuming after it: $last")
        }

        val now = System.currentTimeMillis()
        val events = readEvents(resume.begin, now)
        val result = parseScreenStateSpans(events, resume.stored, now)

        for (span in result.spans) {
            ri.insertEvent(
                SCREEN_STATE_BUCKET_ID,
                Instant.ofEpochMilli(span.start),
                (span.end - span.start) / 1000.0,
                span.state.toEventData(),
            )
        }
        for (startup in result.startupsWithoutShutdown) {
            Log.w(TAG, "Startup at $startup with no shutdown since the previous one: " +
                "the span open before it runs on through it")
        }
        Log.i(TAG, "Sent ${result.spans.size} screen state spans")
    }

    private fun readEvents(begin: Long, end: Long): List<UsageEvent> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val usageEvents = usm.queryEvents(begin, end) ?: return emptyList()
        val events = mutableListOf<UsageEvent>()
        val event = UsageEvents.Event()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType in SCREEN_STATE_EVENT_TYPES) {
                events.add(
                    UsageEvent(event.eventType, event.timeStamp, event.packageName ?: "", "")
                )
            }
        }
        return events
    }
}

/**
 * The bucket's last event from a getEvents response, or null if the bucket is empty. A response
 * that is not a list is the datastore reporting an error: taken for an empty bucket, it would
 * read the whole history again and add every span a second time.
 */
internal fun parseLastEvent(response: String): Result<JSONObject?> =
    try {
        Result.success(JSONArray(response).optJSONObject(0))
    } catch (e: JSONException) {
        Result.failure(e)
    }

/** Where a read resumes: the stored span to extend, if any, and the time to read from. */
internal data class ResumePoint(val stored: ScreenStateSpan?, val begin: Long)

/**
 * Where to resume after [last], the bucket's last event, or null if it cannot be placed in
 * time: reading the whole history again would add every span a second time.
 */
internal fun resumePoint(last: JSONObject?): ResumePoint? {
    if (last == null) return ResumePoint(stored = null, begin = 0L)
    val start = parseServerTimestamp(last.optString("timestamp"))?.toEpochMilli() ?: return null
    val end = start + (last.optDouble("duration", 0.0) * 1000).roundToLong()
    // Data this code cannot read (another format) is left as it is: reading goes on after
    // that event, with the state unknown.
    val state = screenStateFromEventData(last.optJSONObject("data"))
        ?: return ResumePoint(stored = null, begin = end)
    return ResumePoint(ScreenStateSpan(start, end, state), begin = start)
}

// aw-server-rust writes 0, 3, 6 or 9 fractional-second digits, so the pattern must accept any
// precision.
private fun parseServerTimestamp(timestamp: String): Instant? =
    try {
        OffsetDateTime.parse(timestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
    } catch (e: DateTimeParseException) {
        null
    }

// Values named after Android's own events, so they need no interpretation.
internal fun ScreenState.toEventData(): JSONObject =
    JSONObject()
        .put("screen", if (interactive) "interactive" else "non-interactive")
        .put("keyguard", if (keyguardShown) "shown" else "hidden")

internal fun screenStateFromEventData(data: JSONObject?): ScreenState? {
    if (data == null) return null
    val interactive = when (data.optString("screen")) {
        "interactive" -> true
        "non-interactive" -> false
        else -> return null
    }
    val keyguardShown = when (data.optString("keyguard")) {
        "shown" -> true
        "hidden" -> false
        else -> return null
    }
    return ScreenState(interactive, keyguardShown)
}
