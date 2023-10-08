SHELL := /bin/bash

# We should probably do this the "Android way" (would also help with getting it on FDroid):
#  - https://developer.android.com/studio/projects/gradle-external-native-builds
#  - https://developer.android.com/ndk/guides/android_mk

RELEASE_TYPE = $(shell test -n "$$RELEASE" && $$RELEASE && echo 'release' || echo 'debug')
RELEASE_TYPE_CAPS = $(shell test -n "$$RELEASE" && $$RELEASE && echo 'Release' || echo 'Debug')
HAS_SECRETS = $(shell test -n "$$JKS_KEYPASS" && echo 'true' || echo 'false')

APKDIR = mobile/build/outputs/apk
AABDIR = mobile/build/outputs/bundle

WEBUI_SRCDIR := aw-server-rust/aw-webui
WEBUI_DISTDIR := $(WEBUI_SRCDIR)/dist

# Main targets
all: aw-server-rust
build: all

# builds an app bundle, puts it in dist
build-bundle: dist/aw-android.aab

# builds a complete, signed apk, puts it in dist
build-apk: dist/aw-android.apk

# builds debug and test apks (unsigned)
build-apk-debug: $(APKDIR)/debug/mobile-debug.apk $(APKDIR)/androidTest/debug/mobile-debug-androidTest.apk
	mkdir -p dist
	cp -r $(APKDIR) dist

# Test targets
test: test-unit

test-unit:
	./gradlew test

test-e2e:
	./gradlew connectedAndroidTest --stacktrace

test-e2e-screenshot-only:
	@# To only run screenshot test:
	./gradlew connectedAndroidTest \
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

install-apk-debug: $(APKDIR)/debug/mobile-debug.apk
	adb install $(APKDIR)/debug/mobile-debug.apk
	adb install $(APKDIR)/debug/mobile-debug-androidTest.apk

# APK targets
$(APKDIR)/$(RELEASE_TYPE)/mobile-$(RELEASE_TYPE).apk:
	TERM=xterm ./gradlew assemble$(RELEASE_TYPE_CAPS)
	tree $(APKDIR)

$(APKDIR)/androidTest/$(RELEASE_TYPE)/mobile-$(RELEASE_TYPE)-androidTest.apk:
	TERM=xterm ./gradlew assembleAndroidTest
	tree $(APKDIR)

# App bundle targets
$(AABDIR)/$(RELEASE_TYPE)/mobile-$(RELEASE_TYPE).aab:
	TERM=xterm ./gradlew bundle$(RELEASE_TYPE_CAPS)
	tree $(AABDIR)

# Signed release bundle
dist/aw-android.aab: $(AABDIR)/$(RELEASE_TYPE)/mobile-$(RELEASE_TYPE).aab
	mkdir -p dist
	@# Only sign if we have key secrets set ($JKS_KEYPASS and $JKS_STOREPASS)
ifneq ($(HAS_SECRETS), true)
	@echo "No key secrets set, not signing"
	cp $< $@
else
	./scripts/sign_apk.sh $< $@
endif

# Signed release APK
dist/aw-android.apk: $(APKDIR)/$(RELEASE_TYPE)/mobile-$(RELEASE_TYPE).apk
	mkdir -p dist
	@# Only sign if we have key secrets set ($JKS_KEYPASS and $JKS_STOREPASS)
ifneq ($(HAS_SECRETS), true)
	@echo "No key secrets set, not signing"
	cp $< $@
else
	./scripts/sign_apk.sh $< $@
endif

# for mobile-debug.apk and mobile-debug-androidTest.apk
dist/$(RELEASE_TYPE)/%: $(APKDIR)/$(RELEASE_TYPE)/%
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

TARGET := aw-server-rust/target
TARGET_arm7 := $(TARGET)/armv7-linux-androideabi
TARGET_arm8 := $(TARGET)/aarch64-linux-android
TARGET_x64 := $(TARGET)/x86_64-linux-android
TARGET_x86 := $(TARGET)/i686-linux-android

# Build webui specifically for Android (disabled update check, different default views, etc)
export ON_ANDROID := -- --android

aw-server-rust: $(JNILIBS)

.PHONY: $(JNILIBS)
$(JNILIBS): $(JNI_arm7)/libaw_server.so $(JNI_arm8)/libaw_server.so $(JNI_x86)/libaw_server.so $(JNI_x64)/libaw_server.so
	@ls -lL $@/*/*  # Check that symlinks are valid

# There must be a better way to do this without repeating almost the same rule over and over?
# NOTE: These must be hard links for CI caching to work
$(JNI_arm7)/libaw_server.so: $(TARGET_arm7)/$(RELEASE_TYPE)/libaw_server.so
	mkdir -p $$(dirname $@)
	ln -fnv $$(pwd)/$^ $@
$(JNI_arm8)/libaw_server.so: $(TARGET_arm8)/$(RELEASE_TYPE)/libaw_server.so
	mkdir -p $$(dirname $@)
	ln -fnv $$(pwd)/$^ $@
$(JNI_x86)/libaw_server.so: $(TARGET_x86)/$(RELEASE_TYPE)/libaw_server.so
	mkdir -p $$(dirname $@)
	ln -fnv $$(pwd)/$^ $@
$(JNI_x64)/libaw_server.so: $(TARGET_x64)/$(RELEASE_TYPE)/libaw_server.so
	mkdir -p $$(dirname $@)
	ln -fnv $$(pwd)/$^ $@

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

# aw-webui
.PHONY: $(WEBUI_DISTDIR)
$(WEBUI_DISTDIR):
	# Ideally this sub-Makefile should not rebuild unless files have changed
	make --directory=aw-server-rust/aw-webui build

clean:
	rm -rf mobile/src/main/assets/webui
	rm -rf mobile/src/main/jniLibs

