# Element Classic 自定义修改（基于 v1.6.71）

源码目录：`ElementClassicSrc`（从官方 `element-hq/element-android` tag `v1.6.66` 解压并迁入自定义改动；当前版本 `1.6.71`）。

## 功能

### 1. 清空消息
- 清除全部消息
- 清除 N 天前的消息（对话框可调天数，默认 7）

### 2. 语音/音频状态
- 未下载：下载图标与进度
- 已听过：半透明 + ✓（播完/听过约 85% 即标记，UI 立即刷新）

### 3. 后台下载 / 后台播放
- `MediaDownloadAndroidService`、`VoicePlaybackAndroidService`
- 通知进度可拖动；点击通知回到对应聊天消息

### 4. 私服自动升级
- 读取 `{homeserver}/element-classic/update.json`
- 自动下载、SHA-256 校验、提示安装
- Windows 发布脚本：`..\publish-element-classic.ps1`（说明见 `..\ELEMENT_CLASSIC_UPDATE.md`）

## 编译

```bat
rem Debug
gradlew.bat :vector-app:assembleGplayDebug

rem Release（正式发布）
gradlew.bat :vector-app:assembleGplayRelease
```

Release APK 建议复制到上级目录 `D:\10-信仰\12-工具软件\Element\`，再用发布脚本上传到私服。
