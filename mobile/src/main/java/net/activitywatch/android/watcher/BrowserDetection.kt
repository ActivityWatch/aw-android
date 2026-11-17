package net.activitywatch.android.watcher

// Browsers found via PackageManager that WebWatcher has no dedicated extractor for.
internal fun selectDetectedBrowsers(
    resolvedPackages: List<String>,
    builtIn: Set<String>,
    ownPackage: String,
): Set<String> = resolvedPackages.filterTo(LinkedHashSet()) { it !in builtIn && it != ownPackage }
