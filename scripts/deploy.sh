#!/bin/bash

set -e

./gradlew assembleRelease
mv mobile/build/outputs/apk/release/mobile-release-unsigned.apk aw-android.apk
jarsigner -verbose -sigalg SHA1withRSA -storepass $storepass -keypass $keypass -digestalg SHA1 -keystore android.jks aw-android.apk activitywatch
zipalign -v 4 aw-android.apk aw-android-release.apk
bundle exec fastlane supply run -b aw-android-release.apk
