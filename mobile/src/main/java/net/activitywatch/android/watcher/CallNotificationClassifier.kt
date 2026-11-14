package net.activitywatch.android.watcher

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * Decides whether a notification is a call notification, and how far along the call is.
 *
 * Only structural fields are read (category, flags, template and a few boolean/int extras).
 * The title and text, which usually hold the contact name or number, are never touched, and
 * nothing is returned for notifications that are not about a call.
 */
internal object CallNotificationClassifier {
    // Notification.EXTRA_CALL_TYPE (API 31) and the Notification.CallStyle constants,
    // written out so this compiles and behaves the same on every supported API level.
    private const val EXTRA_CALL_TYPE = "android.callType"
    private const val CALL_TYPE_INCOMING = 1
    private const val CALL_TYPE_OUTGOING = 2
    private const val CALL_TYPE_SCREENING = 3

    class Result(val phase: CallPhase, val description: String)

    /** Returns null when [sbn] is not a call that is happening right now. */
    fun classify(sbn: StatusBarNotification): Result? {
        val n = sbn.notification ?: return null
        val extras = n.extras

        val template = extras?.getString(Notification.EXTRA_TEMPLATE)
        // Platform "Notification$CallStyle" and androidx "NotificationCompat$CallStyle".
        val isCallStyle = template?.endsWith("\$CallStyle") == true
        val isCategoryCall = n.category == Notification.CATEGORY_CALL
        if (!isCallStyle && !isCategoryCall) return null

        val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val hasFullScreenIntent = n.fullScreenIntent != null
        // A call that is happening is ongoing, or is ringing with a full-screen intent. This
        // keeps "missed call" notifications (category call, but dismissable) out, because
        // those would otherwise stretch a call until the user swipes them away.
        if (!isCallStyle && !ongoing && !hasFullScreenIntent) return null

        val callType = extras?.getInt(EXTRA_CALL_TYPE, 0) ?: 0
        val chronometer = extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) == true

        val phase = when {
            chronometer -> CallPhase.ACTIVE
            callType == CALL_TYPE_OUTGOING -> CallPhase.UNKNOWN
            callType == CALL_TYPE_INCOMING || callType == CALL_TYPE_SCREENING -> CallPhase.RINGING
            hasFullScreenIntent -> CallPhase.RINGING
            else -> CallPhase.UNKNOWN
        }

        val description = "category=${n.category} callStyle=$isCallStyle callType=$callType " +
                "ongoing=$ongoing fullScreen=$hasFullScreenIntent chronometer=$chronometer"
        return Result(phase, description)
    }
}