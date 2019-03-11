.PHONY: aw-server-rust aw-webui

aw-server-rust:
	cd aw-server-rust && bash compile-android.sh
	ln -sfn $$(pwd)/aw-server-rust/target/aarch64-linux-android/$$($$RELEASE && echo 'release' || echo 'debug')/libaw_server.so \
	        mobile/src/main/jniLibs/arm64-v8a/libaw_server.so
	ln -sfn $$(pwd)/aw-server-rust/target/i686-linux-android/$$($$RELEASE && echo 'release' || echo 'debug')/libaw_server.so \
	        mobile/src/main/jniLibs/x86/libaw_server.so

aw-webui:
	make --directory=aw-server-rust/aw-webui build
	mkdir -p mobile/src/main/assets/webui
	cp -r aw-server-rust/aw-webui/dist/* mobile/src/main/assets/webui
