.PHONY: aw-server-rust aw-webui

build: aw-server-rust aw-webui

aw-server-rust:
	cd aw-server-rust && env RUSTFLAGS="-C debuginfo=2" bash compile-android.sh  # RUSTFLAGS="-C debuginfo=2" is to keep debug symbols, even in release builds (later stripped by gradle on production builds, non-stripped versions needed for stack resymbolizing with ndk-stack)
	# arm64
	mkdir -p mobile/src/main/jniLibs/arm64-v8a/
	ln -sfnv $$(pwd)/aw-server-rust/target/aarch64-linux-android/$$($$RELEASE && echo 'release' || echo 'debug')/libaw_server.so \
	        mobile/src/main/jniLibs/arm64-v8a/libaw_server.so
	# armv7
	mkdir -p mobile/src/main/jniLibs/armeabi-v7a/
	ln -sfnv $$(pwd)/aw-server-rust/target/armv7-linux-androideabi/$$($$RELEASE && echo 'release' || echo 'debug')/libaw_server.so \
	        mobile/src/main/jniLibs/armeabi-v7a/libaw_server.so
	# x86
	mkdir -p mobile/src/main/jniLibs/x86/
	ln -sfnv $$(pwd)/aw-server-rust/target/i686-linux-android/$$($$RELEASE && echo 'release' || echo 'debug')/libaw_server.so \
	        mobile/src/main/jniLibs/x86/libaw_server.so
	# x86_64
	mkdir -p mobile/src/main/jniLibs/x86_64/
	ln -sfnv $$(pwd)/aw-server-rust/target/x86_64-linux-android/$$($$RELEASE && echo 'release' || echo 'debug')/libaw_server.so \
	        mobile/src/main/jniLibs/x86_64/libaw_server.so
	# Check that symlinks are valid
	ls -lL mobile/src/main/jniLibs/*/*

aw-webui:
	make --directory=aw-server-rust/aw-webui build
	mkdir -p mobile/src/main/assets/webui
	cp -r aw-server-rust/aw-webui/dist/* mobile/src/main/assets/webui

clean:
	rm -r mobile/src/main/assets/webui
	rm -r mobile/src/main/jniLibs
