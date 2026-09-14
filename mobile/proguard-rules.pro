# R8 configuration for the release build (minifyEnabled true, see #268).
#
# Anything reached *by name* at runtime has to be kept explicitly: JNI symbols,
# @JavascriptInterface members, classes WorkManager/FragmentManager instantiate
# reflectively. Everything referenced only from Kotlin/Java source is reachable
# through normal call graph analysis and needs no rule.
#
# Manifest-declared components (activities, services, receivers, providers) are
# kept automatically by AGP's generated rules, so they are not repeated here.

# ---------------------------------------------------------------------------
# Crash stacks
# ---------------------------------------------------------------------------
# Keep file/line info so Play vitals and GitHub issue reports stay useful once
# the mapping.txt produced by this build is uploaded alongside the release.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Annotations are load-bearing here (@JavascriptInterface is matched below);
# Signature/InnerClasses/EnclosingMethod keep Kotlin generic + nested type info
# intact for reflection and for readable stack traces.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# ---------------------------------------------------------------------------
# JNI: aw-server-rust (libaw_server.so)
# ---------------------------------------------------------------------------
# The native symbols are Java_net_activitywatch_android_RustInterface_<method>
# and Java_net_activitywatch_android_SyncInterface_<method> (aw-server-rust:
# aw-server/src/android/mod.rs, aw-sync/src/android.rs). Both the *class* name
# and the *method* name are encoded in the symbol, so neither may be renamed —
# the `-keep class ... { native <methods>; }` form pins both.
#
# proguard-android-optimize.txt already ships a generic
# `-keepclasseswithmembernames class * { native <methods>; }`, but that keeps
# names only for classes R8 decides to retain; being explicit here means a
# refactor that stops referencing one of these from Kotlin can't silently
# break the JNI binding.
-keep class net.activitywatch.android.RustInterface {
    native <methods>;
}
-keep class net.activitywatch.android.SyncInterface {
    native <methods>;
}
# Catch-all for any future class declaring native methods.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# WebView bridge (aw-webui -> app)
# ---------------------------------------------------------------------------
# aw-webui calls window.Android.beginExport/appendExport/finishExport/
# downloadCSV/downloadJSON by name (WebAppInterface in fragments/WebUIFragment.kt,
# bound via addJavascriptInterface(..., "Android")). The class itself may be
# renamed; the annotated methods may not.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---------------------------------------------------------------------------
# WorkManager
# ---------------------------------------------------------------------------
# WorkManager persists the worker class name as a string and reconstructs it
# through the default WorkerFactory, i.e. by reflection on the
# (Context, WorkerParameters) constructor. Covers EventParsingWorker,
# NotifyWorker (Worker) and SyncWorker (CoroutineWorker).
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---------------------------------------------------------------------------
# Reflectively instantiated framework classes
# ---------------------------------------------------------------------------
# MainActivity.setFragment() does `fragmentClass?.newInstance()`, and the
# FragmentManager re-creates fragments by class name after process death.
-keep public class * extends androidx.fragment.app.Fragment {
    public <init>();
}

# ViewModelProvider instantiates ViewModels reflectively (TestFragment).
-keep class * extends androidx.lifecycle.ViewModel {
    <init>();
}

# ---------------------------------------------------------------------------
# ThreeTenABP
# ---------------------------------------------------------------------------
# AndroidThreeTen.init() installs a ZoneRulesInitializer that the
# org.threeten.bp.zone machinery looks up reflectively; the TZDB blob itself
# lives in assets/ and is untouched by resource shrinking.
-keep class org.threeten.bp.zone.** { *; }
