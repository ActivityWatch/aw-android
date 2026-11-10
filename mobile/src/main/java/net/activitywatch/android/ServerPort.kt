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

    @Volatile
    var value: Int = BuildConfig.SERVER_PORT
        private set

    fun forUid(uid: Int, basePort: Int = BuildConfig.SERVER_PORT): Int =
        basePort + uid / PER_USER_RANGE

    fun initFromProcess() {
        value = forUid(Process.myUid())
    }
}
