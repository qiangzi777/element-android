# Element Classic 自定义修改（基于 v1.6.66）

源码目录：`ElementClassicSrc`（从官方 `element-hq/element-android` tag `v1.6.66` 解压，并迁入原 v1.6.62 上的自定义改动）。

## 功能

### 1. 清空消息
聊天页右上角菜单：
- **清除全部消息**：删除本房间本地时间线中的消息（保留房间成员/状态）
- **清除7天前的消息**：只删除 7 天前的本地消息

说明：仅清除本机缓存，不会在服务器上撤回；同步后旧消息可能再次拉取回来。

### 2. 语音/音频状态
- 未下载：显示下载图标与进度
- 已听过：气泡半透明 + ✓ 标记

### 3. 后台下载
文件/音频下载改为会话级协程 + 前台服务 `MediaDownloadAndroidService`，离开聊天页或锁屏后仍可继续。

### 4. 后台播放
语音/音频播放进入后台时启动 `VoicePlaybackAndroidService`（MediaSession 通知），保留进度；回来后可从断点继续。

## 编译建议

1. 安装 Android Studio（建议 Hedgehog / Iguana）与 JDK 17
2. 用 Android Studio 打开本目录
3. 配置 `local.properties` 中的 `sdk.dir`
4. 构建：`./gradlew :vector-app:assembleGplayDebug`（或 IDE 中 Run）

首次同步依赖较慢；若 GitHub 访问困难，需配置 Gradle 镜像。
