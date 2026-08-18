# MINIX 构建签名

`app/build.gradle.kts` 不再内置 keystore 路径、别名或密码。

## 默认行为

- `assembleDebug` 使用 Android Gradle Plugin 自动生成的 debug keystore，未配置私钥也能本地编译。
- `assembleRelease` 在没有外部 release 配置时保持未签名，输出通常为 `app/build/outputs/apk/release/app-release-unsigned.apk`。
- 要求 CI 必须产出签名包时，设置 `MINIX_RELEASE_SIGNING_REQUIRED=true`；缺少完整配置或 keystore 文件时配置阶段直接失败。

## 外部配置

每个 scope（`debug`、`release`）需要完整提供 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`，可选 `storeType`（默认 `PKCS12`）。Gradle 属性优先于环境变量：

```text
minix.release.storeFile=/secure/path/release.p12
minix.release.storePassword=...
minix.release.keyAlias=release
minix.release.keyPassword=...
minix.release.storeType=PKCS12
```

对应环境变量为 `MINIX_RELEASE_STORE_FILE`、`MINIX_RELEASE_STORE_PASSWORD`、`MINIX_RELEASE_KEY_ALIAS`、`MINIX_RELEASE_KEY_PASSWORD` 和 `MINIX_RELEASE_STORE_TYPE`。Debug scope 使用 `MINIX_DEBUG_*` 前缀。

将模板复制为 `app/signing.properties`（该文件已忽略）或由 CI 注入环境变量；不要把实际密码、私钥或 keystore 加入仓库。模板见 `app/signing.properties.example`。配置优先级为 Gradle `-P` 属性、环境变量、本地 `app/signing.properties`。

## 验证

```powershell
Set-Location D:\Projects\minix
.\gradlew.bat :app:assembleDebug :app:assembleRelease --no-daemon
```

若配置 release signer，再用 `apksigner verify --verbose --print-certs` 检查最终 APK 的 signer。发布流程应只接受已验证的外部 release signer，不应把 debug keystore 当作发布证书。
