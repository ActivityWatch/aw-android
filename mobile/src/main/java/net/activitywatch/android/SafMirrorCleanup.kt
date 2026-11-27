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

    /** Reserve exact names before assigning case-only fallbacks, independent of copy order. */
    fun matchChildren(
        sources: Collection<String>,
        destinations: Collection<String>,
        destinationDirectories: Collection<String> = destinations,
        sourceDirectories: Collection<String> = sources,
    ): Map<String, String> {
        val matches = sources.filter { it in destinations }.associateWith { it }.toMutableMap()
        val available = destinationDirectories.toMutableSet().apply { removeAll(matches.values.toSet()) }
        for (source in sourceDirectories.filterNot { it in matches }.sorted()) {
            val match = matchChildName(available, source) ?: continue
            matches[source] = match
            available.remove(match)
        }
        return matches
    }

    /** Protect the actual destination, not every similarly spelled directory. */
    fun staleSafHostnames(legacy: Collection<String>, mirroredHostname: String): List<String> =
        legacy.filter { it != mirroredHostname }
}
