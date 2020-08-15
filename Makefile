.PHONY: aw-webui
SHELL := /bin/bash

# We should probably do this the "Android way" (would also help with getting it on FDroid):
#  - https://developer.android.com/studio/projects/gradle-external-native-builds
#  - https://developer.android.com/ndk/guides/android_mk

RELEASE_TYPE = $(shell $$RELEASE && echo 'release' || echo 'debug')

# Main targets
all: aw-server-rust aw-webui
build: all


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

aw-server-rust: $(JNILIBS)

.PHONY: $(JNILIBS)
$(JNILIBS): $(JNI_arm7)/libaw_server.so $(JNI_arm8)/libaw_server.so $(JNI_x86)/libaw_server.so $(JNI_x64)/libaw_server.so
	ls -lL $@/*/*  # Check that symlinks are valid

# There must be a better way to do this without repeating almost the same rule over and over?
$(JNI_arm7)/libaw_server.so: $(TARGET_arm7)/$(RELEASE_TYPE)/libaw_server.so
	mkdir -p $$(dirname $@)
	ln -sfnv $$(pwd)/$^ $@
$(JNI_arm8)/libaw_server.so: $(TARGET_arm8)/$(RELEASE_TYPE)/libaw_server.so
	mkdir -p $$(dirname $@)
	ln -sfnv $$(pwd)/$^ $@
$(JNI_x86)/libaw_server.so: $(TARGET_x86)/$(RELEASE_TYPE)/libaw_server.so
	mkdir -p $$(dirname $@)
	ln -sfnv $$(pwd)/$^ $@
$(JNI_x64)/libaw_server.so: $(TARGET_x64)/$(RELEASE_TYPE)/libaw_server.so
	mkdir -p $$(dirname $@)
	ln -sfnv $$(pwd)/$^ $@

# This target runs multiple times because it's matched multiple times, not sure how to fix
$(RS_SRCDIR)/target/%/$(RELEASE_TYPE)/libaw_server.so: $(RS_SOURCES)
	echo $@
	cd aw-server-rust && env RUSTFLAGS="-C debuginfo=2 -Awarnings" bash compile-android.sh
#	Explanation of RUSTFLAGS:
#	  `-Awarnings` allows all warnings, for cleaner output (warnings should be detected in aw-server-rust CI anyway)
#     `-C debuginfo=2` is to keep debug symbols, even in release builds (later stripped by gradle on production builds, non-stripped versions needed for stack resymbolizing with ndk-stack)


# aw-webui

WEBUI_SRCDIR := aw-server-rust/aw-webui
WEBUI_OUTDIR := mobile/src/main/assets/webui
WEBUI_SOURCES := $(shell find $(RS_SRCDIR) -type f -name *.rs)
export ON_ANDROID := -- --android # Disable check for updates in aw-webui

aw-webui: $(WEBUI_OUTDIR)

.PHONY: $(WEBUI_OUTDIR)
$(WEBUI_OUTDIR): $(WEBUI_SRCDIR)/dist
	mkdir -p mobile/src/main/assets/webui
	cp -r $(WEBUI_SRCDIR)/dist/* mobile/src/main/assets/webui

.PHONY: $(WEBUI_SRCDIR)/dist
$(WEBUI_SRCDIR)/dist:
	# Ideally this sub-Makefile should not rebuild unless files have changed
	make --directory=aw-server-rust/aw-webui build

clean:
	rm -rf mobile/src/main/assets/webui
	rm -rf mobile/src/main/jniLibs
