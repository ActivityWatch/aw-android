package net.activitywatch.android

internal object SafMirrorCleanup {
    private val DATABASE_FILE = Regex(".+\\.db(?:-(?:wal|shm|journal))?")

    fun staleDatabaseFiles(
        localFileNames: Set<String>,
        safFileNames: Set<String>,
    ): Set<String> =
        safFileNames.filterTo(mutableSetOf()) { name ->
            DATABASE_FILE.matches(name) && name !in localFileNames
        }

    /**
     * The entry in [names] to reuse for [wanted]: the exact name if present,
     * otherwise the single entry that differs only in letter case.
     *
     * External storage is usually case-insensitive, so after a case-only
     * hostname rename (`PIXEL8` -> `pixel8`, ActivityWatch/aw-android#402) the
     * SAF tree still holds `PIXEL8/`. An exact-name lookup misses it, and
     * `createDirectory("pixel8")` then gets a uniquified `pixel8 (1)`.
     * Several case variants are ambiguous, so none is picked.
     */
    fun matchChildName(names: Collection<String>, wanted: String): String? {
        if (wanted in names) return wanted
        return names.filter { it.equals(wanted, ignoreCase = true) }.singleOrNull()
    }

    /**
     * Legacy hostnames whose SAF dirs may be removed after a mirror pass. A
     * name equal to [current] ignoring case is excluded: on case-insensitive
     * storage it is the directory the mirror just wrote this device's data
     * into, and deleting it would drop the mirror after every sync.
     */
    fun staleSafHostnames(legacy: Collection<String>, current: String): List<String> =
        legacy.filter { !it.equals(current, ignoreCase = true) }
}
