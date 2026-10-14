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
}
