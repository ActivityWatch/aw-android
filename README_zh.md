# aw-android (Remote Fork)

基于 [ActivityWatch/aw-android](https://github.com/ActivityWatch/aw-android) 的 fork，增加了**远程 ActivityWatch 服务器纯转发**功能。

---

## 功能说明

本 fork 将数据采集方式从"本地存储"改为"远程 HTTP 转发"。采集到的应用使用数据、浏览器访问数据、屏幕解锁事件等，直接通过 HTTP API 发送到用户配置的远程 ActivityWatch 服务器。

### 主要特性

- **纯远程转发** — 数据直接通过 HTTP 发送到远程服务器，不在本地保存
- **可配置远程地址** — App 内提供 UI 让用户填写远程服务器地址
- **动态 WebUI** — 配置远程服务器后，App 内嵌 WebUI 自动展示远程仪表盘
- **原生菜单** — 恢复 Toolbar，点击顶部 ☰ 按钮即可打开导航抽屉
- **明文 HTTP 支持** — 允许向 HTTP 服务器发送数据（适用于局域网/内网部署）

---

## 改动文件

| 文件 | 改动说明 |
|------|---------|
| `RustInterface.kt` | 从 JNI 客户端改为 HTTP 客户端；移除本地 aw-server 调用；所有操作通过 HTTP API 访问远程服务器 |
| `UsageStatsWatcher.kt` | bucket 名称改为 `aw-watcher-android-test2` 和 `aw-watcher-android-unlock2` |
| `MainActivity.kt` | 移除 `ri.startServerTask()`（不再启动本地服务器）；`baseURL` 动态读取远程地址配置；新增 `showRemoteServerDialog()`；启用 Toolbar + ActionBarDrawerToggle |
| `AWPreferences.kt` | 新增 `getRemoteServerUrl()` / `setRemoteServerUrl()` 用于持久化远程地址 |
| `activity_main_drawer.xml` | 新增 `nav_remote_server` 菜单项 |
| `app_bar_main.xml` | 启用 Toolbar |
| `activity_main.xml` | 给 `app_bar_main` include 添加 `android:id` |
| `network_security_config.xml` | 添加 `<base-config cleartextTrafficPermitted="true"/>` 允许明文 HTTP |

---

## 配置指南

### 1. 准备远程服务器

你需要一个运行中的 ActivityWatch 服务器，例如：
- `aw-server-rust`（推荐）
- `aw-server`（Python 版）

服务器需要暴露 HTTP API，默认端口为 `5600`。

### 2. 安装 APK

```bash
./gradlew assembleDebug
adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk
```

### 3. 配置远程地址

1. 打开 App，授予 **Usage Access** 权限
2. 点击顶部 **☰** 按钮打开导航抽屉（白色 Toolbar 左上角）
3. 点击 **Remote Server**
4. 输入远程服务器地址，如 `http://192.168.1.100:5600`
5. 点击 **Save**

> **注意**：留空则使用本地服务器 `http://127.0.0.1:5600`

### 4. 验证数据

正常使用手机 1-2 小时后，在浏览器中访问：
```
http://your-server-ip:5600/#/timeline
```

即可查看手机采集的数据。

---

## 调试

查看远程转发日志：
```bash
adb logcat -s RustInterface:D
```

正常应看到：
```
HTTP POST OK: /api/0/buckets/aw-watcher-android-test2/heartbeat?pulsetime=1.0
```

---

## 构建环境

本项目在 Windows 11 上从零搭建（无 Android Studio）。

| 组件 | 路径 |
|------|------|
| JDK 17 | `./jdk-17.0.18+8` |
| Android SDK | `./android-sdk` |
| 输出 APK | `mobile/build/outputs/apk/debug/mobile-debug.apk` |

构建命令：
```bash
export JAVA_HOME="$(pwd)/jdk-17.0.18+8"
export ANDROID_HOME="$(pwd)/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

./gradlew assembleDebug
```

> **注意**：本项目依赖预构建的 Rust JNI 库（`libaw_server.so`），这些库已包含在 `jniLibs/` 中，无需自行编译 Rust/NDK。

---

## 已知限制

- UsageStats 应用使用数据是每小时批量采集一次（AlarmManager），不是秒级实时
- Chrome 浏览器数据通过 AccessibilityService 实时采集，转发延迟约几百毫秒
- 远程服务器必须是标准 ActivityWatch API（`aw-server-rust` 或 `aw-server`）
- 网络断开时数据会丢失（fire-and-forget，无本地缓存）

---

## 原项目

https://github.com/ActivityWatch/aw-android
