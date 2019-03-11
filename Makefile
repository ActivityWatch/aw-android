.PHONY: aw-server-rust aw-webui

aw-server-rust:
	cd aw-server-rust && bash compile-android.sh

aw-webui:
	make --directory=aw-server-rust/aw-webui build
	mkdir -p mobile/src/main/assets/webui
	cp -r aw-server-rust/aw-webui/dist/* mobile/src/main/assets/webui
