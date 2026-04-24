# aw-android-plus / 交接手册 (Handover Manual)

> 本文档供后续模型快速理解项目上下文。  
> **最后更新**: 2026-04-23  
> **当前状态**: 纯远程转发 + WorkManager 后台采集，代码已 push 至 `liv10let/aw-android-plus`

---

## 1. 项目概况

- **原项目**: https://github.com/ActivityWatch/aw-android
- **用户仓库**: https://github.com/liv10let/aw-android-plus
- **核心目标**: 将 aw-android 从"本地 SQLite 存储"改为"纯远程 HTTP 转发"
- **工作目录**: `e:\Project_workspace\aw-android`

### 1.1 功能概述

用户部署了一台远程 ActivityWatch 服务器（aw-server-rust 或 aw-server），希望手机采集的数据**直接转发到该服务器**，不在本地保存。

数据流向：
```
UsageStatsWatcher / ChromeWatcher (采集端)
    ↓
RustInterface.heartbeatHelper() / createBucketHelper()
    ↓
HTTP POST → 远程 ActivityWatch 服务器
```

---

## 2. 核心改动

### 2.1 RustInterface.kt — 从 JNI 改为 HTTP 客户端

**原始逻辑**:
- `System.loadLibrary("aw_server")` 加载 Rust JNI
- `initialize()` / `startServer()` 启动本地 aw-server (127.0.0.1:5600)
- `createBucket()` / `heartbeat()` / `getBuckets()` / `getEvents()` 通过 JNI 调用本地 Rust

**当前逻辑**:
- 移除所有 JNI 代码（不加载 so、不启动本地服务器）
- 实现 `httpGet()` / `httpPost()` 辅助方法
- 所有操作通过 HTTP API 访问远程服务器：
  - `createBucketHelper()` → `POST /api/0/buckets/{id}`
  - `heartbeatHelper()` → `POST /api/0/buckets/{id}/heartbeat?pulsetime={seconds}`
  - `getBucketsJSON()` → `GET /api/0/buckets`
  - `getEventsJSON()` → `GET /api/0/buckets/{id}/events`
- 远程地址从 `AWPreferences.getRemoteServerUrl()` 读取，默认为 `http://127.0.0.1:5600`

**关键代码位置**: [RustInterface.kt](mobile/src/main/java/net/activitywatch/android/RustInterface.kt)

### 2.2 UsageStatsWatcher.kt — Bucket 改名 + lastUpdated 修复 + WorkManager 后台采集

**Bucket 名称**（避免与原版冲突）:
- `aw-watcher-android-test` → `aw-watcher-android-plus`
- `aw-watcher-android-unlock` → `aw-watcher-android-plus-unlock`

**lastUpdated 逻辑修复**:
- 原始代码：`getLastEventTime()` 查询服务器上最后一个事件的时间戳作为 `lastUpdated`
- **问题**：如果服务器 bucket 已有旧数据（如 9 天前），`queryEvents` 从 9 天前开始查询，但 MIUI 的 `UsageStatsManager` 只保留最近 3-5 天数据，导致查询不到今天的事件
- **修复**：如果服务器数据超过 7 天，使用 `now - 1hour` 作为 `lastUpdated`

```kotlin
val now = Instant.now()
if (lastUpdated != null && Duration.between(lastUpdated, now).toDays() > 7) {
    lastUpdated = now.minus(Duration.ofHours(1))
}
```

**WorkManager 后台采集**（替代 AsyncTask）:
- **原始方案**：`AsyncTask.execute()` 挂在 Activity 上，app 切后台或被 MIUI 杀进程时，采集任务会中断，导致数据丢失
- **当前方案**：使用 `androidx.work:work-runtime-ktx` 的 `WorkManager`
  - `PeriodicWorkRequest` 每 15 分钟执行一次后台采集（替代 `AlarmManager`）
  - `OneTimeWorkRequest` 用于打开 app 时手动触发
  - Worker 在独立后台进程中执行，app 切走、锁屏、甚至进程被杀后，系统仍会在资源允许时继续执行
  - 设备重启后 `WorkManager` 自动恢复定时任务

**关键代码位置**: [UsageStatsWatcher.kt](mobile/src/main/java/net/activitywatch/android/watcher/UsageStatsWatcher.kt) · [HeartbeatWorker.kt](mobile/src/main/java/net/activitywatch/android/watcher/HeartbeatWorker.kt)

### 2.3 MainActivity.kt — UI 改动

改动内容：
- **移除** `ri.startServerTask(this)` — 不再启动本地服务器
- **新增** `showRemoteServerDialog()` — AlertDialog 让用户输入远程服务器地址
- **启用 Toolbar** — 配合 `ActionBarDrawerToggle`，点击顶部 ☰ 打开原生导航抽屉
- **动态 baseURL** — `baseURL` 读取 `AWPreferences.getRemoteServerUrl()`，为空则回退到 `http://127.0.0.1:5600`

**关键代码位置**: [MainActivity.kt](mobile/src/main/java/net/activitywatch/android/MainActivity.kt)

### 2.4 其他文件

| 文件 | 改动 |
|------|------|
| `AWPreferences.kt` | 新增 `getRemoteServerUrl()` / `setRemoteServerUrl()`，持久化到 SharedPreferences |
| `activity_main_drawer.xml` | 新增 `nav_remote_server` 菜单项 |
| `app_bar_main.xml` | 启用 Toolbar（取消注释） |
| `activity_main.xml` | 给 `app_bar_main` include 添加 `android:id="@+id/app_bar"` |
| `network_security_config.xml` | 添加 `<base-config cleartextTrafficPermitted="true"/>` |
| `.gitignore` | 添加 `android-sdk/`, `jdk-17.0.18+8/`, `cmdline-tools.zip`, `jdk17.zip`, `apk_extracted/` |

---

## 3. 已解决的问题

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| 数据丢失 95.2% | `lastUpdated` 被服务器旧数据拉回 9 天前 | 超过 7 天则使用 `now - 1hour` |
| 事件时间戳错误 | 同上 | 同上 |
| 找不到 Remote Server 菜单 | Toolbar 被注释，WebView 拦截边缘滑动 | 启用 Toolbar + ActionBarDrawerToggle |
| Cleartext HTTP 被拦截 | Android 9+ 默认禁止明文 HTTP | `network_security_config.xml` 添加 `cleartextTrafficPermitted="true"` |
| Tailscale VPN 不稳定 | MIUI 限制 App 进程 VPN 流量 | 改用公网 IP / 局域网 IP |

---

## 4. 已知问题

1. **数据碎片化严重**: `UsageStatsManager` 产生的事件本身很碎（大量 `duration=0`），这是 aw-android 原生的采集策略问题，不是我们的 bug
2. **网络断开丢数据**: 当前是 fire-and-forget，无本地缓存，网络断开时数据直接丢失
3. **UsageStats 延迟**: 应用使用数据是每 15 分钟批量采集（WorkManager），不是实时
4. **Chrome 数据**: 通过 AccessibilityService 实时采集，但需要单独开启无障碍权限
5. **MIUI 后台限制**: 即使使用 WorkManager，MIUI 仍可能延迟后台任务执行。建议用户设置省电策略为"无限制"并锁定最近任务

---

## 5. 构建指南

### 5.1 环境（Windows 11，无 Android Studio）

| 组件 | 路径 |
|------|------|
| JDK 17 | `./jdk-17.0.18+8` |
| Android SDK | `./android-sdk` |
| 输出 APK | `mobile/build/outputs/apk/debug/mobile-debug.apk` |

### 5.2 构建命令

```bash
export JAVA_HOME="$(pwd)/jdk-17.0.18+8"
export ANDROID_HOME="$(pwd)/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

./gradlew assembleDebug
adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk
```

> **注意**: 依赖预构建的 Rust JNI 库（`libaw_server.so`），已包含在 `jniLibs/` 中。当前代码**不使用**这些 JNI 库（纯 HTTP 模式），但保留它们以避免破坏构建。
> 
> **新增依赖**: `androidx.work:work-runtime-ktx:2.9.0`（WorkManager 后台任务），Gradle 会自动下载。

---

## 6. 快速诊断

### 6.1 验证远程转发是否工作

```bash
adb logcat -s HeartbeatWorker:D RustInterface:D
```

正常应看到：
```
HeartbeatWorker: HeartbeatWorker finished, sent 87 events
RustInterface: HTTP POST OK: /api/0/buckets/aw-watcher-android-plus/heartbeat?pulsetime=1.0
```

### 6.2 验证配置是否保存

```bash
adb shell "run-as net.activitywatch.android.debug cat /data/data/net.activitywatch.android.debug/shared_prefs/AWPreferences.xml" | grep remote
```

### 6.3 验证服务器是否有新数据

```bash
# 查询今天的事件
adb shell "curl -s 'http://YOUR_SERVER:5600/api/0/buckets/aw-watcher-android-test2/events?start=2026-04-23T00:00:00Z&limit=10'"
```

### 6.4 如果用户说"找不到 Remote Server 菜单"

**99% 是因为他们在看 WebUI 的菜单**。必须点顶部状态栏的 ☰（白色 Toolbar 左上角），那才是原生 Android 导航抽屉。

---

## 7. 后续优化方向

如果用户提出新需求，可参考以下方向：

1. **失败重试 / 本地队列**: 当前 fire-and-forget 在网络抖动时会丢数据。可添加本地 SQLite 队列 + 定时重试
2. **远程连通性检测**: 配置对话框中增加"测试连接"按钮
3. **HTTPS / Token 鉴权**: 当前是明文 HTTP，公网部署需加 TLS 或 API Token
4. **多远程服务器**: 支持配置多个地址，同时向多个服务器转发
5. **数据压缩 / 批量发送**: 减少 HTTP 请求次数
6. **事件合并优化**: 在客户端自行计算 duration，减少零时长碎片事件

---

## 8. 文件清单（修改过的）

```
M  .gitignore
M  README.md
A  README_zh.md
M  mobile/build.gradle
M  mobile/src/main/java/net/activitywatch/android/AWPreferences.kt
M  mobile/src/main/java/net/activitywatch/android/MainActivity.kt
M  mobile/src/main/java/net/activitywatch/android/RustInterface.kt
M  mobile/src/main/java/net/activitywatch/android/watcher/AlarmReceiver.kt
A  mobile/src/main/java/net/activitywatch/android/watcher/HeartbeatWorker.kt
M  mobile/src/main/java/net/activitywatch/android/watcher/UsageStatsWatcher.kt
M  mobile/src/main/res/layout/activity_main.xml
M  mobile/src/main/res/layout/app_bar_main.xml
M  mobile/src/main/res/menu/activity_main_drawer.xml
M  mobile/src/main/res/xml/network_security_config.xml
```

**未修改的原始文件**（不要动它们）：
- `UsageStatsWatcher.kt` 的采集逻辑（`SendHeartbeatsTask`）
- `Event.kt` 的数据模型
- `ChromeWatcher.kt`
- `BucketsContent.kt`
- `OnboardingActivity.kt`

---

## 9. 联系上下文

如果用户问"之前做了什么"，直接引用本文档第 2 节。  
如果用户要求"重新编译安装"，使用第 5.2 节的命令。  
如果用户问"为什么看不到远程数据"，先检查第 6.1 节的 logcat，再检查第 6.4 节的菜单区分问题。

---

*文档最后更新: 2026-04-24*  
*当前状态: 纯远程转发模式，代码已 push 至 liv1let/aw-android-plus*
