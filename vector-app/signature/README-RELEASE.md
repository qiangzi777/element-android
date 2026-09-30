# Element Classic Release 签名说明

## 密钥库位置（本地，勿提交 Git）
- `vector-app/signature/element-classic-release.keystore`
- `vector-app/signature/release-signing.properties`（密码等）

**请务必备份 keystore 与密码。** 丢失后无法对同一 `applicationId` 发布可覆盖升级的新版本。

## 编译命令

在 `ElementClassicSrc` 目录：

```bat
gradlew.bat :vector-app:assembleGplayRelease
```

产物在：
`vector-app/build/outputs/apk/gplay/release/`

## Debug vs Release 区别
| | Debug | Release |
|---|---|---|
| 包名 | `im.vector.app.debug` | `im.vector.app` |
| 应用名 | Element Classic - dbg | Element Classic |
| 签名 | debug.keystore | element-classic-release.keystore |

注意：Release 包名与官方 Element Android 相同（`im.vector.app`），不能与官方版同时安装。若需并存，要把 `applicationId` 改成例如 `im.vector.app.classic`。
