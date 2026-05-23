# 工作交接手册 / Handover Manual

> 本文档用于上下文压缩或清零后，让后续模型快速接手本项目工作。
> This document is for future model continuity after context compression/reset.

---

## 1. 项目概况 / Project Overview

- **项目**: aw-android (ActivityWatch Android 客户端)
- **仓库路径**: `e:\Project_workspace\aw-android` (Git Bash 内为 `/e/Project_workspace/aw-android`)
- **原项目**: https://github.com/ActivityWatch/aw-android
- **本 fork 核心目标**: 在保留本地数据存储的基础上，增加**远程 ActivityWatch 服务器双写**功能，让用户既能用 App 内置 WebUI 查看本地数据，也能在手机外的浏览器查看远程服务器上的手机数据。

---

## 2. 核心改动 / Core Changes

### 2.1 远程服务器双写 (RustInterface.kt)

数据流向：
```
采集端 (UsageStatsWatcher / AccessibilityService)
    ↓
RustInterface.heartbeatHelper() / createBucketHelper()
    ├→ JNI 调用本地 aw-server (127.0.0.1:5600) ← 原有逻辑，不变
    └→ 异步 HTTP POST 到远程服务器 ← 新增逻辑
```

实现要点：
- `sendRemoteHeartbeat()` — 在本地 heartbeat 完成后，异步向远程发送 `POST /api/0/buckets/{id}/heartbeat?pulsetime={seconds}`
- `sendRemoteBucketCreate()` — 在本地 createBucket 完成后，异步向远程发送 `POST /api/0/buckets/{id}`
- 使用 `Executors.newSingleThreadExecutor()` 作为 fire-and-forget 线程池
- 失败仅打日志，绝不阻塞本地存储流程
- 远程地址为空时自动跳过，无性能开销

### 2.2 远程地址配置持久化 (AWPreferences.kt)

在原有 `AWPreferences` 中新增两个方法：
- `getRemoteServerUrl()` — 从 SharedPreferences 读取 `"remoteServerUrl"`
- `setRemoteServerUrl(url)` — 写入 `"remoteServerUrl"`

### 2.3 WebUI 地址动态切换 (MainActivity.kt)

`baseURL` 从原来的硬编码常量改为动态属性：
```kotlin
private val baseURL: String
    get() {
        val remote = AWPreferences(this).getRemoteServerUrl()
        return if (remote.isNotBlank()) remote else "http://127.0.0.1:5600"
    }
```
这样配置远程服务器后，App 内嵌 WebUI 直接展示远程仪表盘。

### 2.4 远程服务器配置 UI (MainActivity.kt + activity_main_drawer.xml)

- 在导航抽屉的 Misc 分组中新增 **Remote Server** 菜单项
- 点击弹出 AlertDialog，内含 EditText，可输入远程地址（如 `http://100.122.190.51:5600`）
- 留空则仅使用本地服务器
- 保存后弹出 Snackbar 提示当前设置

### 2.5 Toolbar 恢复 (app_bar_main.xml + MainActivity.kt)

原代码中 `Toolbar` 被注释掉，导致原生导航抽屉只能靠左侧边缘滑动触发，且容易被 WebView 拦截。
- 重新启用 `Toolbar`
- 在 `onCreate()` 中配置 `ActionBarDrawerToggle`，绑定 Toolbar 与 DrawerLayout
- 用户现在可点击顶部 ☰ 按钮打开原生菜单

### 2.6 明文 HTTP 支持 (network_security_config.xml)

Android 9+ 默认禁止明文 HTTP。新增：
```xml
<base-config cleartextTrafficPermitted="true"/>
```
以允许向局域网/内网 HTTP 服务器转发数据。

---

## 3. 改动文件清单 / Modified Files

| 文件 | 说明 |
|------|------|
| `README.md` | 顶部新增双语功能增强说明（双写、配置 UI、动态 URL、Toolbar、明文 HTTP），含修改文件表、使用步骤、已知限制 |
| `mobile/src/main/java/net/activitywatch/android/AWPreferences.kt` | 新增 `getRemoteServerUrl()` / `setRemoteServerUrl()` |
| `mobile/src/main/java/net/activitywatch/android/RustInterface.kt` | 新增 `prefs`、`remoteExecutor`、`sendRemoteHeartbeat()`、`sendRemoteBucketCreate()`；修改 `createBucketHelper()` 和 `heartbeatHelper()` 以双写 |
| `mobile/src/main/java/net/activitywatch/android/MainActivity.kt` | `baseURL` 改为动态读取 SharedPreferences；新增 `showRemoteServerDialog()`；启用 Toolbar + ActionBarDrawerToggle |
| `mobile/src/main/res/menu/activity_main_drawer.xml` | 新增 `nav_remote_server` 菜单项 |
| `mobile/src/main/res/layout/app_bar_main.xml` | 取消注释并启用 Toolbar |
| `mobile/src/main/res/layout/activity_main.xml` | 给 `app_bar_main` include 添加 `android:id="@+id/app_bar"` |
| `mobile/src/main/res/xml/network_security_config.xml` | 增加 `<base-config cleartextTrafficPermitted="true"/>` |

---

## 4. 构建环境 / Build Environment

本环境是在 Windows 11 上从零搭建的（无 Android Studio）。

### 4.1 关键路径

| 组件 | 路径（Git Bash / Unix 语法） | 路径（Windows 语法） |
|------|------------------------------|----------------------|
| 项目根目录 | `/e/Project_workspace/aw-android` | `E:\Project_workspace\aw-android` |
| JDK 17 | `./jdk-17.0.18+8` | `E:\Project_workspace\aw-android\jdk-17.0.18+8` |
| Android SDK | `./android-sdk` | `E:\Project_workspace\aw-android\android-sdk` |
| Gradle wrapper | `./gradlew` | `E:\Project_workspace\aw-android\gradlew` |
| 输出 APK | `mobile/build/outputs/apk/debug/mobile-debug.apk` | 同上 |

### 4.2 构建命令

在项目根目录执行（Git Bash）：
```bash
export JAVA_HOME="$(pwd)/jdk-17.0.18+8"
export ANDROID_HOME="$(pwd)/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# 构建 Debug APK
./gradlew assembleDebug

# 安装到已连接的设备
adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk
```

> **注意**: 该项目依赖预构建的 Rust JNI 库（`libaw_server.so`），这些库在原始 APK 中已存在，无需自行编译 Rust/NDK。本仓库中已包含从官方 release APK 提取的 `jniLibs/`。

### 4.3 环境相关未跟踪文件

以下目录/文件被 Git 忽略（未加入版本控制）：
- `jdk-17.0.18+8/` — Eclipse Temurin JDK 17
- `android-sdk/` — Android SDK（含 platform-tools、build-tools、platforms、NDK）
- `cmdline-tools.zip` — SDK 命令行工具压缩包
- `jdk17.zip` — JDK 17 压缩包
- `apk_extracted/` — 提取官方 APK 时的临时目录

---

## 5. 使用新功能 / How to Use

1. 安装 APK 后打开 App，授予 Usage Access 权限。
2. 点击顶部 ☰ 按钮打开导航抽屉（这是**原生 Android 菜单**，不是 WebUI 菜单）。
3. 点击 **Remote Server**。
4. 输入 ActivityWatch 服务器地址，如 `http://100.122.190.51:5600`，保存。
5. 正常使用手机，数据会自动双写到本地和远程。
6. 在浏览器中访问同样地址（如 `http://100.122.190.51:5600/#/timeline`）即可查看手机数据。

---

## 6. 已知限制 / Known Limitations

- UsageStats 应用使用数据是每小时批量采集一次（AlarmManager），不是秒级实时。
- Chrome 浏览器数据通过 AccessibilityService 实时采集，转发延迟约几百毫秒。
- 远程服务器必须是标准 ActivityWatch API（`aw-server-rust` 或 `aw-server`）。
- 双写是 fire-and-forget，不保证远程一定成功；失败仅通过 logcat 输出日志。

---

## 7. 调试技巧 / Debugging Tips

### 7.1 验证远程转发是否工作

连接手机后查看 logcat：
```bash
adb logcat -s RustInterface:D
```
正常应看到：
```
Remote bucket create OK: aw-watcher-android-test
Remote heartbeat OK: aw-watcher-android-test
```
若看到：
```
Remote heartbeat failed: HTTP 4xx/5xx ...
Remote heartbeat error: java.net.UnknownHostException...
```
说明地址配置错误或网络不通。

### 7.2 验证 Toolbar 和原生菜单

如果用户说"找不到 Remote Server 菜单"，99% 是因为他们在看 WebUI 的菜单（WebUI 左上角有三条线，那是网页自己的菜单）。**必须点顶部状态栏的 ☰（白色背景 Toolbar 左上角）**，那才是原生 Android 导航抽屉，里面有 Remote Server。

### 7.3 Cleartext HTTP 问题

如果 logcat 出现：
```
Cleartext HTTP traffic to x.x.x.x not permitted
```
检查 `network_security_config.xml` 是否包含 `<base-config cleartextTrafficPermitted="true"/>`。该改动已通过验证，若仍报错可能是 APK 未重新安装或安装的是旧版本。

---

## 8. 可能的后续工作 / Potential Future Work

用户（仓库所有者）可能感兴趣的方向：
- **远程写失败重试 / 队列化**: 当前 fire-and-forget 在网络抖动时会丢数据。可改用本地 SQLite 队列 + 定时重试。
- **远程服务器连通性检测**: 在配置对话框中增加"测试连接"按钮，实时验证地址可达性。
- **数据加密传输**: 当前远程 HTTP 是明文。若用户有公网部署需求，建议配置 HTTPS 或增加 Token 鉴权。
- **多远程服务器**: 支持配置多个远程地址，向多个服务器同时双写。
- **远程-only 模式**: 提供开关彻底关闭本地服务器，减少电量/内存占用（适合只想看远程数据的用户）。

---

## 9. 联系上下文 / Context Recovery

如果用户问"之前做了什么"或"改动了哪些文件"，直接引用本文档第 2、3 节即可。如果用户要求"重新编译安装"，使用第 4.2 节的命令。如果用户问"为什么看不到远程数据"，先检查第 7.1 节的 logcat，再检查第 7.2 节的菜单区分问题。

---

## 10. 演进记录 / Evolution History

### 10.1 阶段一：双写模式（本地 + 远程）

初始实现。`RustInterface` 在原有本地 JNI 调用后，叠加了异步 HTTP 远程发送。
- 问题：远程发送失败时，本地已写但远程未写的事件形成**永久空洞**（`lastUpdated` 基于本地最后事件时间，缺失的数据永远不会被再次采集）。
- 结果：远程服务器数据不完整。

### 10.2 阶段二：远程单写模式（纯远程，无本地）

为解决数据空洞，将 `RustInterface` 改为纯 HTTP 客户端：
- 去掉本地 JNI 调用（`System.loadLibrary("aw_server")`、`initialize()` 等）
- `createBucketHelper()` 和 `heartbeatHelper()` 改为同步 HTTP 远程发送
- `getBucketsJSON()` / `getEventsJSON()` 改为从远程 HTTP GET
- `MainActivity` 不再启动本地 aw-server
- `UsageStatsWatcher` 的 `lastUpdated` 自动变为基于远程服务器最后事件时间，可补回缺失历史

**关键诊断发现**：
- adb shell 的 curl/ping 到远程服务器（`100.122.190.51:5600`）：**完全正常**（0.6s，0% 丢包）
- `run-as net.activitywatch.android.debug`（App 进程内）的 curl/ping：**全部失败**（`000` 超时 / `Operation not permitted`）
- 原因：**App 进程的网络流量被 Android/MIUI 限制，无法稳定通过 Tailscale VPN**，而 adb shell 的流量正常走 VPN
- 结果：22860 次 heartbeat 尝试中，只有约 3492 个到达服务器，最终合并为 443 条事件。数据严重丢失。

### 10.3 阶段三：回退到本地存储

由于 Tailscale 网络在 App 进程内极不稳定，决定回退到官方版本的本地存储行为：
- `RustInterface.kt`：恢复原始本地 JNI 逻辑
- `MainActivity.kt`：恢复 `ri.startServerTask()`，WebUI 固定连接 `127.0.0.1:5600`
- `ChromeWatcher.kt`：恢复原始本地调用逻辑
- UI 改动保留：Toolbar、Remote Server 菜单和对话框（配置保存在 SharedPreferences 中，但当前不影响功能）

### 10.4 阶段四：恢复双写模式（公网 IP）

用户提供了新的公网 IP `43.173.85.67:5600`，不再经过 Tailscale，App 进程内网络连通性正常。恢复双写：
- `RustInterface.kt`：在 `createBucketHelper()` / `heartbeatHelper()` 中本地 JNI 调用后，异步 HTTP POST 到远程
- `MainActivity.kt`：`baseURL` 重新动态读取 `SharedPreferences` 中的远程地址
- 构建安装成功，远程 heartbeat 日志显示 HTTP 200

**数据质量分析**（基于远程服务器导出 `aw-buckets-export.json`）：
- `aw-watcher-android-test` 共 13,297 条事件（约 9 天数据）
- **30.3% 的事件 duration = 0**，大量事件未被合并为连续会话
- **840 个连续重复事件**（相同 package + classname 连续出现），说明 heartbeat 合并不彻底
- 原因：`UsageStatsManager` 产生的 `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED` 事件本身碎片化严重，且 `Event.fromUsageEvent` 中 `duration` 始终为 `0.0`，完全依赖服务器端 heartbeat 合并；当 pulsetime（RESUMED=1.0s, PAUSED=24h）与事件间隔不匹配时，产生大量零散的短事件
- **结论**：这不是网络传输丢失，而是数据采集端的**事件碎片化问题**，需要优化 `UsageStatsWatcher` 的事件合并策略（如自行计算 duration 而非全依赖 heartbeat 合并）

---

## 11. 教训 / Lessons Learned

1. **Android VPN 对 App 进程和 shell 进程的行为可能完全不同**。adb shell 的网络测试结果不能代表 App 的实际网络表现。
2. **`AsyncTask` + 同步网络请求在批量场景下不可靠**。即使改为同步发送，Android 的后台线程调度、Doze 模式、MIUI 省电策略都可能导致请求被延迟或中断。
3. **ActivityWatch 的 heartbeat API 会合并相邻的相同事件**。`id=3492` 不代表 3492 条独立事件，实际存储的事件数可能远少（本例中 3492 个 heartbeat 被合并为 443 条）。
4. **如果以后要重新实现远程同步**，建议：
   - 先解决网络问题（公网 IP + 防火墙白名单，或修复 Tailscale 对 App 进程的访问）
   - 使用本地 SQLite 队列 + 定时批量同步，而不是 fire-and-forget
   - 在首次配置远程服务器时，触发一次全量历史同步

---

*文档更新日期: 2026-04-23*
*当前状态: 双写模式（本地 + 远程公网 IP），数据 100% 写入本地，异步转发远程；远程数据存在碎片化问题待优化*
