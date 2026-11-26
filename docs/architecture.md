# 架构说明

## 分层

```
ui/        Compose 页面（Login / Home / Devices / Network / Scan / NetProbe / Log）
AppViewModel  状态机：登录、采样循环、退出、公网检测、日志导出
collect/   RouterRepository：一轮采样把 ubus 结果拼成 HomeData / DeviceSnapshot
           Normalizer：纯函数解析（接口、内存、CPU ticks、温度、assoclist、信道→频段）
           DeviceMerger：把 assoclist / vantage FDB / 租约 / ARP 表合并成设备列表
           RateTracker：按计数器差值算速率
net/       RouterSession：`/ubus` 会话、登录缓存、串行闸门、错误映射
           Rpc / RpcJson：请求体与解析
           NetProbe：Cloudflare `cdn-cgi/trace` 探测（IPv4/IPv6 各自强制地址族）
data/      CredentialStore（Keystore AES-256-GCM）、Ax6Db（Room 速率历史）
domain/    HomeData / Device / Rates 等不可变模型
fmt/       字节、速率、时长、频段、信号等格式化
```

## RPC 协议

- 端点：`POST http://<host>:<port>/ubus`，body 固定四项：

```json
{"jsonrpc":"2.0","id":1,"method":"call",
 "params":["<sid>","<object>","<method>",{}]}
```

  即使方法无参，第 4 项也必须传 `{}`。首次登录 `params[0]` 用 32 个 `0` 的匿名会话。
- 会话：`session.login` 返回 `ubus_rpc_session`，按其 `timeout-90s` 缓存；遇到 `code 5` 重置后重登一次。
- 并发：**所有调用串行**（Mutex 包住整个调用过程），同一时刻只有 1 个在途请求，避免部分固件的 rpcd/uhttpd 并发问题。

## 一轮采样

`RouterRepository.sample()` 每轮按顺序执行：

1. `network.interface dump` + `system.info` → 接口列表、负载、内存、运行时长；
2. `luci-rpc getNetworkDevices` → 各接口 `rx_bytes/tx_bytes`、link 速率；
3. `luci getTempInfo`、`luci getCPUInfo`、`file read /proc/stat`（均带缓存）；
4. `iwinfo info` + `assoclist`（12 s 缓存）→ WiFi 概况；
5. `luci.vantage wired`（5 s）→ 有线主机、端口、网桥；
6. `file read nf_conntrack_count/max`（15 s）；
7. `collectPorts` / `collectTraffic` 计算端口与接口速率。

`devices()` 单独为终端页服务：**每拍强制重新拉 `iwinfo assoclist`**，保证 WiFi 速率是 3 秒粒度。

轮询间隔：前台 3 s；手动退出模式退到后台后 10 s。单轮失败只丢这一拍，连续 3 拍失败回登录页。

## 展示口径（终端视角）

路由器上的计数是路由器视角，统一换算成“终端看到的方向”：

| 场景 | 下行 | 上行 |
| --- | --- | --- |
| 端口 / 接口 | `tx` | `rx` |
| WiFi 客户端 | `txBytes` | `rxBytes` |
| 有线设备速率 | 端口 `tx` | 端口 `rx` |

- 首页物理网口：LAN 口左右互换，WAN 不换（`Port.viewDownRate` 等）。
- 网络页 `br-lan`、`phyX-apX` 与端口累计：`↓ = tx`、`↑ = rx`。
- 有线设备**没有设备级累计**，只有端口计数（同端口多台共享），因此详情里用「累计流量同端口多设备共享」标注台数；WiFi 累计是 `assoclist` 的本次关联计数，重连会归零。

## CPU 占用

`/proc/stat` 首行前 8 个字段做差分：`usage = (Δtotal − Δidle − Δiowait) / Δtotal`。取不到时退回 `load1/cores` 折算，并 60 秒内不再重试。该读取需要 ACL（见排障文档）。

## 退出机制

- `ExitGate`：切后台时记 `leave_elapsed` / `leave_wall` / `limit_ms`（默认 30 s，手动 4 min）。
- `ExitService`：`specialUse` 前台服务 + `PARTIAL_WAKE_LOCK`，每秒比对墙上时间与开机时间，到点调用 `closeFromBackground()`（停轮询 → `finishAndRemoveTask()` → 杀进程）。

## 存储

- 凭据：`CredentialStore`，Android Keystore AES-256-GCM，密文存 SharedPreferences。
- 历史：Room 每 3 秒写入一条，每 200 条清理一次 4 小时前的数据；内存曲线窗口按用户所选区间保留（默认 5 分钟，实际按区间 + 10 s 裁）。
- 诊断日志：`DiagLog` 的 `trace.log` 上限 32 KB（超限截尾半段），`crash.log` 覆盖写；日志页可复制最近 40 行。
