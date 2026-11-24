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

    // Each user moves the port by a whole hundred, so a flavor keeps its base
    // port's last two digits (standard 5600 -> x00, research 5667 -> x67) and no
    // user of one flavor can land on a port of the other. A plain `+ userId`
    // put standard user 67 on research's 5667.
    private const val USER_PORT_STRIDE = 100
    private const val MAX_PORT = 65_535

    @Volatile
    var value: Int = BuildConfig.SERVER_PORT
        private set

    fun forUid(uid: Int, basePort: Int = BuildConfig.SERVER_PORT): Int {
        val port = basePort + (uid / PER_USER_RANGE) * USER_PORT_STRIDE
        // Past user ~599 the stride leaves the port range. Fall back to the base
        // port (the pre-#68 behaviour: startup reports it in use) rather than
        // handing the server an invalid port.
        return if (port <= MAX_PORT) port else basePort
    }

    fun initFromProcess() {
        value = forUid(Process.myUid())
    }
}
