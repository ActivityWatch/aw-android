SHELL := /bin/bash

# We should probably do this the "Android way" (would also help with getting it on FDroid):
#  - https://developer.android.com/studio/projects/gradle-external-native-builds
#  - https://developer.android.com/ndk/guides/android_mk

RELEASE_TYPE = $(shell test -n "$$RELEASE" && $$RELEASE && echo 'release' || echo 'debug')
RELEASE_TYPE_UNSIGNED = $(shell test -n "$$RELEASE" && $$RELEASE && echo 'release-unsigned' || echo 'debug')
RELEASE_TYPE_CAPS = $(shell test -n "$$RELEASE" && $$RELEASE && echo 'Release' || echo 'Debug')
HAS_SECRETS = $(shell test -n "$$JKS_KEYPASS" && echo 'true' || echo 'false')

APKDIR = mobile/build/outputs/apk
AABDIR = mobile/build/outputs/bundle

WEBUI_SRCDIR := aw-server-rust/aw-webui
WEBUI_DISTDIR := $(WEBUI_SRCDIR)/dist

# NOTE: you have to download bundletool manually and set this path
#		https://github.com/google/bundletool/releases
BUNDLETOOL := java -jar ~/Downloads/bundletool-all-1.15.5.jar

# Main targets
all: aw-server-rust metadata
build: all
metadata: fastlane/metadata/android/en-US/images/icon.png

# builds an app bundle, puts it in dist
build-bundle: dist/aw-android.aab

# builds a complete, signed apk, puts it in dist
build-apk: dist/aw-android.apk

# Attempts at working with bundletool to build device-specific APKs
# See: https://github.com/ActivityWatch/aw-android/issues/61
dist/aw-android.apks:
	rm -rf dist/aw-android.apks
	$(BUNDLETOOL) \
		build-apks --bundle=dist/aw-android.aab --output=dist/aw-android.apks

# Extracts device-specific APKs from the apks bundle
build-device-specific-apks:
	@# For distributable per-ABI APKs, use build-apk-per-abi instead (aw-android#61).
	$(BUNDLETOOL) \
		extract-apks \
		--apks=dist/aw-android.apks \
		--output-dir=dist/splits/arm64 \
		--device-spec=scripts/device-arm64.json

# Useful for inspecting the contents of the apks
unzip-apks: dist/aw-android.apks
	rm -rf dist/apks
	unzip -o dist/aw-android.apks -d dist/apks

# builds debug and test apks (unsigned)
build-apk-debug: $(APKDIR)/standard/debug/mobile-standard-debug.apk $(APKDIR)/androidTest/standard/debug/mobile-standard-debug-androidTest.apk
	mkdir -p dist
	cp -r $(APKDIR) dist

# Test targets
test: test-unit

test-unit:
	./gradlew testStandardDebugUnitTest

# The upgrade test seeds a large legacy database before the datastore opens, so it
# needs its own instrumentation process: run it after the rest, in a separate
# gradle invocation (see UpgradeWithHistoryTest).
test-e2e: test-e2e-main test-e2e-upgrade

test-e2e-main:
	./gradlew connectedStandardDebugAndroidTest --stacktrace \
		-Pandroid.testInstrumentationRunnerArguments.notClass=net.activitywatch.android.UpgradeWithHistoryTest

test-e2e-upgrade:
	./gradlew connectedStandardDebugAndroidTest --stacktrace \
		-Pandroid.testInstrumentationRunnerArguments.class=net.activitywatch.android.UpgradeWithHistoryTest

test-e2e-screenshot-only:
	@# To only run screenshot test:
	./gradlew connectedStandardDebugAndroidTest \
		-Pandroid.testInstrumentationRunnerArguments.class=net.activitywatch.android.ScreenshotTest

test-e2e-adb:
	@# Requires that you have a device connected with the necessary APKs installed
	@# Alternative to using gradle, if you don't want to rebuild.
	@#
	@# To list instrumentation tests, run:
	@# adb shell pm list instrumentation
	@#
	@# Run only screenshot test, for now
	adb shell am instrument -w \
		-e class net.activitywatch.android.ScreenshotTest
		net.activitywatch.android.debug.test/androidx.test.runner.AndroidJUnitRunner

install-apk-debug: $(APKDIR)/standard/debug/mobile-standard-debug.apk
	adb install $(APKDIR)/standard/debug/mobile-standard-debug.apk
	adb install $(APKDIR)/androidTest/standard/debug/mobile-standard-debug-androidTest.apk

# APK targets
# NOTE: the product flavors (standard / research, see mobile/build.gradle)
# make AGP emit per-flavor output dirs and variant-suffixed task names.
# Distribution workflows below pin the *standard* flavor explicitly.
$(APKDIR)/standard/$(RELEASE_TYPE)/mobile-standard-$(RELEASE_TYPE_UNSIGNED).apk:
	TERM=xterm ./gradlew assembleStandard$(RELEASE_TYPE_CAPS)
	tree $(APKDIR)

$(APKDIR)/androidTest/standard/$(RELEASE_TYPE)/mobile-standard-$(RELEASE_TYPE)-androidTest.apk:
	TERM=xterm ./gradlew assembleStandard$(RELEASE_TYPE_CAPS)AndroidTest
	tree $(APKDIR)

# App bundle targets
# NOTE: unlike APKs (`outputs/apk/<flavor>/<buildType>/`), AGP writes bundles to
# `outputs/bundle/<variantName>/` — flavor+buildType camel-cased, e.g.
# `standardDebug`. Verified against the actual tree in CI (job "Build aab").
$(AABDIR)/standard$(RELEASE_TYPE_CAPS)/mobile-standard-$(RELEASE_TYPE).aab:
	TERM=xterm ./gradlew bundleStandard$(RELEASE_TYPE_CAPS)
	tree $(AABDIR)

# Signed release bundle
dist/aw-android.aab: $(AABDIR)/standard$(RELEASE_TYPE_CAPS)/mobile-standard-$(RELEASE_TYPE).aab
	mkdir -p dist
	@# Only sign if we have key secrets set ($JKS_KEYPASS and $JKS_STOREPASS)
ifneq ($(HAS_SECRETS), true)
	@echo "No key secrets set, not signing"
	cp $< $@
else
	./scripts/sign_apk.sh $< $@
endif

# Signed release APK
dist/aw-android.apk: $(APKDIR)/standard/$(RELEASE_TYPE)/mobile-standard-$(RELEASE_TYPE_UNSIGNED).apk
	mkdir -p dist
	@# Only sign if we have key secrets set ($JKS_KEYPASS and $JKS_STOREPASS)
ifneq ($(HAS_SECRETS), true)
	@echo "No key secrets set, not signing"
	cp $< $@
else
	./scripts/sign_apk.sh $< $@
endif

# Per-ABI APKs, e.g. dist/aw-android-arm64-v8a.apk (aw-android#61).
# The universal APK carries native libs for every ABI, which puts it well over
# 200MB. Each per-ABI APK is the same unsigned APK with the other ABIs' lib/
# dirs (and any stale v1 signature files listing them) removed, then signed
# the same way as dist/aw-android.apk.
# ABIs are read from the APK itself, so debug builds (abiFilters) only yield
# the ABIs they actually contain.
build-apk-per-abi: $(APKDIR)/standard/$(RELEASE_TYPE)/mobile-standard-$(RELEASE_TYPE_UNSIGNED).apk
	mkdir -p dist
	@set -ef; \
	abis=$$(unzip -Z1 $< 'lib/*' | cut -d/ -f2 | sort -u); \
	if [ -z "$$abis" ]; then echo "No native libs found in $<"; exit 1; fi; \
	for abi in $$abis; do \
		out=dist/aw-android-$$abi.apk; tmp=dist/aw-android-$$abi.unsigned.apk; \
		cp $< $$tmp; \
		others=$$(for a in $$abis; do [ "$$a" = "$$abi" ] || echo "lib/$$a/*"; done); \
		if [ -n "$$others" ]; then \
			zip -q -d $$tmp $$others; \
			zip -q -d $$tmp 'META-INF/MANIFEST.MF' 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.DSA' 'META-INF/*.EC' || [ $$? -eq 12 ]; \
		fi; \
		if [ "$(HAS_SECRETS)" = true ]; then \
			./scripts/sign_apk.sh $$tmp $$out; rm -f $$tmp.idsig; \
		else \
			echo "No key secrets set, not signing $$out"; \
			mv $$tmp $$out; \
		fi; \
	done

# for mobile-standard-debug.apk and mobile-standard-debug-androidTest.apk
dist/$(RELEASE_TYPE)/%: $(APKDIR)/standard/$(RELEASE_TYPE)/%
	mkdir -p dist
	cp $< $@

# aw-server-rust stuff

RS_SRCDIR := aw-server-rust
RS_OUTDIR := $(JNILIBS)
RS_SOURCES := $(shell find $(RS_SRCDIR)/aw-* -type f -name '*.rs')

JNILIBS := mobile/src/main/jniLibs
JNI_arm8 := $(JNILIBS)/arm64-v8a
JNI_arm7 := $(JNILIBS)/armeabi-v7a
JNI_x86 := $(JNILIBS)/x86
JNI_x64 := $(JNILIBS)/x86_64

TARGETDIR := aw-server-rust/target
TARGETDIR_arm7 := $(TARGETDIR)/armv7-linux-androideabi
TARGETDIR_arm8 := $(TARGETDIR)/aarch64-linux-android
TARGETDIR_x64 := $(TARGETDIR)/x86_64-linux-android
TARGETDIR_x86 := $(TARGETDIR)/i686-linux-android

# Build webui specifically for Android (disabled update check, different default views, etc)
export ON_ANDROID := -- --android

aw-server-rust: $(JNILIBS)

.PHONY: $(JNILIBS)
$(JNILIBS): $(JNI_arm7)/libaw_server.so $(JNI_arm8)/libaw_server.so $(JNI_x86)/libaw_server.so $(JNI_x64)/libaw_server.so \
            $(JNI_arm7)/libaw_sync.so $(JNI_arm8)/libaw_sync.so $(JNI_x86)/libaw_sync.so $(JNI_x64)/libaw_sync.so
	@ls -lL $@/*/*  # Check that symlinks are valid

# There must be a better way to do this without repeating almost the same rule over and over?
# NOTE: These must be hard links for CI caching to work
$(JNI_arm7)/libaw_server.so: $(TARGETDIR_arm7)/$(RELEASE_TYPE)/libaw_server.so
	mkdir -p $$(dirname $@)
	# if target is empty, then create symlink
	if [ -z "$(TARGET)" ] || [ "$(TARGET)" == "arm" ]; then ln -fnv $$(pwd)/$^ $@; fi
$(JNI_arm8)/libaw_server.so: $(TARGETDIR_arm8)/$(RELEASE_TYPE)/libaw_server.so
	mkdir -p $$(dirname $@)
	if [ -z "$(TARGET)" ] || [ "$(TARGET)" == "arm64" ]; then ln -fnv $$(pwd)/$^ $@; fi
$(JNI_x86)/libaw_server.so: $(TARGETDIR_x86)/$(RELEASE_TYPE)/libaw_server.so
	mkdir -p $$(dirname $@)
	if [ -z "$(TARGET)" ] || [ "$(TARGET)" == "x86" ]; then ln -fnv $$(pwd)/$^ $@; fi
$(JNI_x64)/libaw_server.so: $(TARGETDIR_x64)/$(RELEASE_TYPE)/libaw_server.so
	mkdir -p $$(dirname $@)
	if [ -z "$(TARGET)" ] || [ "$(TARGET)" == "x86_64" ]; then ln -fnv $$(pwd)/$^ $@; fi

$(JNI_arm7)/libaw_sync.so: $(TARGETDIR_arm7)/$(RELEASE_TYPE)/libaw_sync.so
	mkdir -p $$(dirname $@)
	# if target is empty, then create symlink
	if [ -z "$(TARGET)" ] || [ "$(TARGET)" == "arm" ]; then ln -fnv $$(pwd)/$^ $@; fi
$(JNI_arm8)/libaw_sync.so: $(TARGETDIR_arm8)/$(RELEASE_TYPE)/libaw_sync.so
	mkdir -p $$(dirname $@)
	if [ -z "$(TARGET)" ] || [ "$(TARGET)" == "arm64" ]; then ln -fnv $$(pwd)/$^ $@; fi
$(JNI_x86)/libaw_sync.so: $(TARGETDIR_x86)/$(RELEASE_TYPE)/libaw_sync.so
	mkdir -p $$(dirname $@)
	if [ -z "$(TARGET)" ] || [ "$(TARGET)" == "x86" ]; then ln -fnv $$(pwd)/$^ $@; fi
$(JNI_x64)/libaw_sync.so: $(TARGETDIR_x64)/$(RELEASE_TYPE)/libaw_sync.so
	mkdir -p $$(dirname $@)
	if [ -z "$(TARGET)" ] || [ "$(TARGET)" == "x86_64" ]; then ln -fnv $$(pwd)/$^ $@; fi

RUSTFLAGS_ANDROID="-C debuginfo=2 -Awarnings"
# Explanation of RUSTFLAGS:
#  `-Awarnings` allows all warnings, for cleaner output (warnings should be detected in aw-server-rust CI anyway)
#  `-C debuginfo=2` is to keep debug symbols, even in release builds (later stripped by gradle on production builds, non-stripped versions needed for stack resymbolizing with ndk-stack)
#  `-g` is to keep debug symbols in the build, bloats the binary from 7M -> 60MB. Currently using profile.release.debug in Cargo.toml however as -g didn't work on the first try


# This target runs multiple times because it's matched multiple times, not sure how to fix
$(RS_SRCDIR)/target/%/$(RELEASE_TYPE)/libaw_server.so: $(RS_SOURCES) $(WEBUI_DISTDIR)
	@echo $@
	@echo "Release type: $(RELEASE_TYPE)"
	@# if we indicate in CI via USE_PREBUILT that we've
	@# fetched prebuilt libaw_server.so from aw-server-rust repo,
	@# then don't rebuild it
	@# also check libraries exist, if not, error
	@if [ "$$USE_PREBUILT" == "true" ] && [ -f $@ ]; then \
		echo "Using prebuilt libaw_server.so"; \
	else \
		echo "Building libaw_server.so from aw-server-rust repo"; \
		env RUSTFLAGS=$(RUSTFLAGS_ANDROID) make -C aw-server-rust android; \
	fi

# Same rule for libaw_sync.so (but without webui dependency)
$(RS_SRCDIR)/target/%/$(RELEASE_TYPE)/libaw_sync.so: $(RS_SOURCES)
	@echo $@
	@echo "Release type: $(RELEASE_TYPE)"
	@if [ "$$USE_PREBUILT" == "true" ] && [ -f $@ ]; then \
		echo "Using prebuilt libaw_sync.so"; \
	else \
		echo "Building libaw_sync.so from aw-server-rust repo"; \
		env RUSTFLAGS=$(RUSTFLAGS_ANDROID) make -C aw-server-rust android; \
	fi

# aw-webui
.PHONY: $(WEBUI_DISTDIR)
$(WEBUI_DISTDIR):
	# Ideally this sub-Makefile should not rebuild unless files have changed
	# Don't run if SKIP_WEBUI is set
	if [ "$$SKIP_WEBUI" == "true" ]; then \
		echo "Skipping aw-webui build, as SKIP_WEBUI is set"; \
	else \
		echo "Building aw-webui"; \
		make --directory=aw-server-rust/aw-webui build; \
	fi

clean:
	rm -rf mobile/src/main/assets/webui
	rm -rf mobile/src/main/jniLibs

.PHONY: fastlane/metadata/android/en-US/images/icon.png
fastlane/metadata/android/en-US/images/icon.png: aw-server-rust/aw-webui/media/logo/logo.png
	magick $< -resize 75% -gravity center -background white -extent 512x512 $@
