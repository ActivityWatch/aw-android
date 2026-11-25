package net.activitywatch.android

import android.os.Process

/**
 * The port this install's aw-server listens on.
 *
 * Android shares the loopback interface between users, so a main-profile and a
 * work-profile install cannot both bind the build's port: the second one finds it
 * taken and never starts its own server (ActivityWatch/aw-android#68). The port is
 * offset by the Android user id. User 0, the main profile, keeps the build's base
 * port, so existing setups and sync peers see no change.
 *
 * [AWApplication] resolves it once at process start; until then (and in plain JVM
 * unit tests) it is the base port.
 */
object ServerPort {
    // android.os.UserHandle.PER_USER_RANGE: uid = userId * PER_USER_RANGE + appId
    private const val PER_USER_RANGE = 100_000

    // Keep each flavor's last two digits (standard x00, research x67) distinct.
    // Non-main users start above Chromium/Fetch's highest restricted port,
    // 10080: the stride alone hit X11's 6000 and IRC's 6667. User 0 retains
    // the existing base port for backwards compatibility.
    private const val PROFILE_PORT_OFFSET = 5000
    private const val USER_PORT_STRIDE = 100
    private const val MAX_PORT = 65_535

    @Volatile
    var value: Int = BuildConfig.SERVER_PORT
        private set

    fun forUid(uid: Int, basePort: Int = BuildConfig.SERVER_PORT): Int {
        val userId = uid / PER_USER_RANGE
        if (userId == 0) return basePort
        val port = basePort + PROFILE_PORT_OFFSET + userId * USER_PORT_STRIDE
        // Past user ~549 the stride leaves the port range. Fall back to the base
        // port (the pre-#68 behaviour: startup reports it in use) rather than
        // handing the server an invalid port.
        return if (port <= MAX_PORT) port else basePort
    }

    fun initFromProcess() {
        value = forUid(Process.myUid())
    }
}
