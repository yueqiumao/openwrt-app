# 构建与发布

## 工具链

- JDK 17
- Android SDK 35（build-tools 35.0.0）
- Gradle 8.9
- Kotlin / Compose（BOM 2024.09.03）、Room 2.6.1、OkHttp 4.12、kotlinx-serialization 1.7.3

`android/local.properties` 需要本机 `sdk.dir`，不入库。

## 构建

```bash
cd android
JAVA_HOME=<jdk17> ANDROID_HOME=<sdk35> gradle :app:assembleDebug :app:testDebugUnitTest
```

- 产物 `app/build/outputs/apk/debug/app-debug.apk`
- 单测 47 项，覆盖 Normalizer、DeviceMerger、RouterSession、CPU 差分、格式化；fixtures 放在 `app/src/test/resources/fixtures/`

## 版本号

- `versionName` 每包 +0.01，`versionCode` 每包 +1（例：1.3.25 / 26）。
- 只改 `android/app/build.gradle.kts` 的两处，不另存版本文件。

## 出包流程

1. 构建 + 跑单测；
2. 复制到出包目录并按 `AX6助手-vX.Y.Z-说明.apk` 命名；
3. 记录 `md5sum` 与 `aapt2 dump badging` 的 versionCode/versionName；
4. 上传分发，把链接与 md5 一并交付。

## 签名

- 当前只有 debug 签名（TOFU 调试证书），可直接安装，不适合正式分发。
- 正式 release 需要 keystore + `storePassword` / `keyAlias` / `keyPassword`，通过环境变量或 CI Secrets 注入，**keystore 文件与口令都不入库**。
- 发布用的签名密钥一旦对外，就与该包名永久绑定；换钥匙会导致已装用户无法覆盖升级。

## 体积

debug 包约 10–11 MB，dex 占绝大部分。release 已开启 R8 + 资源压缩，正式签名后可显著变小。`ui-tooling`（仅 debug）目前保留，工程内暂无 `@Preview`，移除可再省 1–2 MB。
