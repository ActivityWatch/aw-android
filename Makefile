.PHONY: aw-webui apk
SHELL := /bin/bash

# We should probably do this the "Android way" (would also help with getting it on FDroid):
#  - https://developer.android.com/studio/projects/gradle-external-native-builds
#  - https://developer.android.com/ndk/guides/android_mk

RELEASE_TYPE = $(shell $$RELEASE && echo 'release' || echo 'debug')

# Main targets
all: aw-webui apk
build: all

apk:
	env RUSTFLAGS="-C debuginfo=2 -Awarnings" TERM=xterm ./gradlew build

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
