# AX6 助手（Android）

一个为 Redmi AX6（ImmortalWrt 24.10.6）定制的原生 Android 监控客户端。包名 `com.ax6.luyouqi`，Kotlin + Jetpack Compose + Material 3，**直连路由器的 ubus JSON-RPC（`/ubus`）**，不经任何中间服务。


## 功能

- **首页**：实时上下行速率与曲线、CPU 占用、温度、内存、运行时长、连接数、物理网口四卡（终端视角）、WiFi 概况、有线终端计数。
- **终端页**：搜索与分类（全部 / WiFi / 有线 / 累计），设备详情（IPv4/IPv6、WiFi 参数、有线端口与共享说明、累计收发、实时速率）。
- **网络页**：`br-lan` 与各 `phyX-apX` 的收发速率与累计、端口映射。
- **扫描**：`iwinfo scan` 查看周边 WiFi（只读，不改路由器配置）。
- **公网检测**：手机侧直连 Cloudflare `cdn-cgi/trace`，分别测 IPv4 / IPv6 出口与本地地址，不写库。
- **设置**：账号、认证方式、退出策略（默认 30 s / 手动 4 min）、公网检测入口、日志页、重启、关于。
- **退出**：前台服务 + 唤醒锁，退到后台按策略计时，到点自动退出（可选“手动退出”模式）。

## 构建

需要 JDK 17、Android SDK 35、Gradle 8.9：

```bash
cd android
JAVA_HOME=<jdk17> ANDROID_HOME=<sdk35> gradle :app:assembleDebug :app:testDebugUnitTest
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`。`android/local.properties` 里的 `sdk.dir` 需按本机路径自行生成，不入库。

## 版本规则

每次出包 `versionName` 增加 `0.01`、`versionCode` 增加 `1`。当前基线见 `android/app/build.gradle.kts`。

## 目录

```
android/                 Android 工程（唯一源码）
  app/src/main/java/com/ax6/luyouqi/
    net/                 RPC 会话与探测（RouterSession / Rpc / NetProbe）
    collect/             采集与归一化（RouterRepository / Normalizer / DeviceMerger / RateTracker）
    data/                凭据存储（Keystore）与历史库（Room）
    domain/              领域模型
    ui/                  页面与通用控件
    fmt/                 单位与文案格式化
docs/                    架构、构建发布、排障
```

## 说明

- 只支持已开启 `rpcd`（ubus over HTTP）的固件；不依赖 LuCI 的表单登录，也不走 LuCI RPC 通道。
- 账户密码用 Android Keystore（AES-256-GCM）本地加密，不写入日志。
- 目前仅出 debug 包；正式 release 需要单独提供 keystore 与口令。
