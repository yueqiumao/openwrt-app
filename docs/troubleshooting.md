# 排障与已知限制

## 已验证不可用的通道

以下方式在本机固件（ImmortalWrt 24.10.6 / IPQ8071A）实测不通，代码里不要再尝试：

| 通道 | 结果 |
| --- | --- |
| `file read /proc/net/dev` | ubus `code 6`（无权限） |
| `sysfs` 读 carrier / speed | 无权限 |
| `thermal_zone` 直读 | 无权限 |
| LuCI RPC（`/cgi-bin/luci/admin/ubus`） | `code 5` |
| LuCI 表单登录（`/cgi-bin/luci`） | HTTP 403 |
| `network.wireless status` | 与 `luci-rpc getWirelessDevices` 结果不一致/不可用 |

因此：接口计数走 `luci-rpc getNetworkDevices`，WiFi 走 `iwinfo`，有线拓扑走 `luci.vantage wired`，温度走 `luci getTempInfo`，CPU 走 `/proc/stat` 差分，取不到则退回 `luci getCPUUsage`（见下节）。

## CPU 用量：两条通道

顺序是 `/proc/stat` 差分 → `luci getCPUUsage` → `load1/cores` 折算，失败通道 60 秒内不重试。

- `file read /proc/stat`：约 51ms，按 App 自己的 3 秒间隔差分，数值平滑。出厂 ACL 不含该路径（`code 6`）；装了 `luci-app-vantage` 后其 ACL 会顺带放开。
- `luci getCPUUsage`：约 165ms（rpcd 45ms 固定开销 + 接口内部约 100ms 采样窗口），出厂 ACL 就放行，返回 100ms 窗口瞬时值，会 0%↔3% 跳。

## 常见错误码

- `code 5`：会话权限不足或过期。会话会重置并重登一次；仍失败说明对应 ubus 方法被 ACL 拒绝。
- `code 6`：方法存在但无权限，通常是 `file read` 路径不在白名单。
- 登录失败：`session.login` 返回非 0，多为 rpcd 登录限速或账号错误。
- HTTP 403 / 非 JSON 响应：地址指向的不是 rpcd 的 `/ubus`，或端口被别的服务占用。

## 数据口径的坑

- **有线设备没有设备级累计**：`vantage` 只给 mac/port/bridge/vlan，端口计数同口多台共享；因此终端详情里的累计是端口级，并标注台数。
- **WiFi 累计是会话级**：`assoclist` 的 `rxBytes/txBytes` 随重连归零，不是终身累计。
- **端口计数随接口重连/重启清零**：所以“累计”只代表本次开机以来的量。
- **端口视角**：所有展示已统一为终端视角（`↓ = tx`、`↑ = rx`），改动时别只换实时速率而漏了累计。
- **设备详情是快照**：弹窗打开时捕获当时的 `Device`，速率与累计不会自动刷新，需要关闭重开。

## UI 已知坑

- 不要给滚动容器内的卡片加 `Modifier.shadow(clip = false)`，该机型会出现整页叠层。
- 不要在 `verticalScroll` 里用 `Crossfade` / `AnimatedContent` 包整页，同样会导致叠层。
- 曲线区域独占触摸，不参与页面滚动（点击按住可左右滑动定位）。

## 真机验收清单

1. 首页六卡数值与路由器 `top` / LuCI 状态页一致；
2. 终端页 WiFi 速率 3 秒一跳，方向与网络页一致；
3. 累计视图分组顺序：有线 → 5 GHz → 2.4 GHz，再按总量倒序；
4. 备注设备离线后仍在列表，来源显示「备注（已离线）」；
5. 退出：默认 30 s、手动 4 min，后台到点能自动关闭；
6. 日志页可全选复制，内容不含口令。
