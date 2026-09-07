# NagramXF 内置代理方案重构设计

> 目标：弃用基于 WebSocket 的 ws（Cloudflare）代理，替换为更稳定、可自持的代理方案，
> 使软件**能正常打开并建立代理连接**。
> 状态：方案已实现并通过构建验证（run 34113161621，产出 `NagramXF-arm64-v8a-109`）。

---

## 0. 结论速览

| 项 | 结论 |
|---|---|
| 新协议 | **VLESS**（TLS / Reality / XTLS 可选），由 **sing-box** 内核承载 |
| 内核集成 | **libbox AAR 进程内集成**（官方 sing-box 移动端绑定），非独立进程 |
| 连接方式 | 本地 `mixed`(SOCKS5/HTTP) 入站 `127.0.0.1:6357` → sing-box → VLESS 出站 → 远端节点 → Telegram DC |
| 对 ws 的优势 | 不依赖任何第三方中转域名；无 CF Worker 的 WS 时长/520 限制；节点自持 |
| 重构范围 | 移除 `libs/tcp2ws` + `WebSocketHelper` + `WsSettingsActivity`；新增 vless 内核/配置/服务/UI 四层；改接线 4 处 |
| 可用状态 | 未配置时**直连不受影响**；配置 vless 链接后前台服务常驻，Telegram 经本地端口走 VLESS |

---

## 1. 背景：ws 方案为何不可用

旧 ws 链路：
```
Telegram → 本地 SOCKS5(tcp2ws, 6356) → WebSocket(wss://<子域>.ws.nagramxf/api) → CF Worker → TG 服务器
```

实测问题（两类，均为硬伤）：

1. **后端域名失效**：内置后端 `ws.nagramxf` 在系统 DNS 与公共 DNS（8.8.8.8）均 **NXDOMAIN**，
   所有子域无法解析 → `connect()` 立即失败 → MTProto **秒级反复重连**，表现为"连不上/卡启动"。
2. **CF Worker 固有限制**：免费 Worker 对 WebSocket 有约 100 秒时长上限，
   且异常时返回 520（旧实现里有 10 次退避重试逻辑），长连接体验差。

本质问题：**把可用性押在第三方（Cloudflare + 一个已失效的内置域名）上**，
单点失效即全网不可用，且维护方不可控。

---

## 2. 候选方案对比

| 维度 | A. ws（现状，弃用） | B. VLESS + sing-box（**推荐**） | C. MTProto 代理（Telegram 原生） | D. 纯 SOCKS5 |
|---|---|---|---|---|
| 协议/内核 | 自建 WS + CF 中转 | VLESS（TLS/Reality），sing-box 内核 | Telegram 私有混淆协议 | SOCKS5 |
| 是否需要本地进程 | 是（tcp2ws） | 是（sing-box via libbox） | **否**（Telegram 原生支持） | 否（直填地址） |
| 是否需前台服务保活 | 否（进程内线程） | 是（前台 Service） | **否** | 否 |
| 第三方依赖 | **强依赖 CF Worker + 内置域名** | 仅自备节点 | 仅节点 | 仅节点 |
| 节点生态 | 无（自建） | **丰富**（v2ray/xray/sing-box 通用，订阅多） | 较弱（tg://proxy 分享） | 一般 |
| 抗封锁/伪装 | 弱（WS + CF） | **强**（Reality + uTLS 指纹伪装） | 中 | 弱（明文） |
| 弱网表现 | 一般 | **好**（支持 Hysteria2/TUIC 等 UDP 传输） | 一般 | 一般 |
| 多协议/分流扩展 | 无 | **有**（路由/DNS/分流） | 无 | 无 |
| 实现复杂度 | 已存在 | 中（已实现并过编译） | 低 | 低 |

### 关于 MTProto（用户点名要求参考）
MTProto 是**最省资源**的选择：Telegram 原生支持、无需本地进程、无需前台服务、不会因本地端口
异常导致"打不开"。**但它不适合作为"内置代理内核"**：
- 只服务 Telegram 流量，无法复用为通用代理；
- 节点生态与订阅能力远弱于 VLESS；
- 无 Reality/uTLS 等现代伪装手段。

**处理**：Telegram 原生的 SOCKS5 / MTProto 入口**保持原样不动**，用户仍可自行添加；
本方案做的是替换"内置代理"这一槽位。

---

## 3. 推荐选型：B（VLESS + sing-box，libbox 进程内集成）

理由（按权重排序）：

1. **根除第三方单点依赖**——这是 ws 的致命伤。VLESS 节点由用户自备/订阅，失效可即时换节点。
2. **抗封锁能力强**：Reality + uTLS 指纹伪装，握手特征接近正常 HTTPS；ws 的 WS+CF 特征明显。
3. **生态与可维护性**：节点、订阅、客户端通用；后续可平滑加 Hysteria2/TUIC 等传输。
4. **性能**：省去 CF 中转一跳；弱网可选 UDP 传输；sing-box 内核内存/启动优于上一代 v2ray-core。
5. **已被验证的范式**：Telegram 分支 Momogram 也在 `TMessagesProj/libs/` 下集成代理内核 AAR
   （其用 v2ray），说明"AAR 放 libs/"是该代码族的家常做法。

> 若将来只需 Telegram 代理、不愿承担内核体积与前台服务成本，可退到 **C（MTProto）**，
> 此时可移除内核与前台服务，仅保留 UI。这是明确的降级路径。

---

## 4. 新方案设计

### 4.1 协议与连接方式

- **出站协议**：VLESS（`security=none|tls|reality`；支持 `flow`、uTLS 指纹 `fp`、
  Reality 的 `pbk`/`sid`；传输支持 `tcp`/`ws`/`grpc`）
- **内核**：sing-box（libbox AAR，**钉死 v1.13.21**）
- **入站**：`mixed`（SOCKS5 + HTTP 合一）监听 `127.0.0.1:6357`
- **Telegram 侧**：使用其原生 SOCKS5 代理能力，指向该本地端口
  （复用 `native_setProxySettings`，**未改 JNI 层**）

### 4.2 核心交互流程

```
[用户] 粘贴 vless:// 链接 → 开启开关
        │
        ▼
VlessSettingsActivity ──► NekoConfig.vlessLink / vlessEnabled
        │
        ▼
VlessProxyService（前台 Service，常驻通知）
        │
        ▼
LibboxEngine:
   Libbox.setup(SetupOptions)                       // 只做一次
   Libbox.newCommandServer(ServerHandler, PlatformStub)
   CommandServer.start()
   CommandServer.startOrReloadService(configJson, OverrideOptions())
        │
        ▼
sing-box 启动 → 监听 127.0.0.1:6357
        │
        ▼
VlessProxyManager: PROXY_SERVER("vless.nagramxf") 作为哨兵地址
   ConnectionsManager 把它翻译为 127.0.0.1:6357
        │
        ▼
Telegram ──SOCKS5──► 127.0.0.1:6357 ──► sing-box ──VLESS──► 远端节点 ──► Telegram DC
```

### 4.3 关键设计点

| 设计点 | 说明 |
|---|---|
| **哨兵地址** | `VlessProxyManager.PROXY_SERVER`（`vless.nagramxf`）作为代理列表里的条目地址；实际连接时才翻译为 `127.0.0.1:port`。与旧 ws 的 `ws.nagramxf` 同构，接线改动最小 |
| **配置与内核解耦** | `VlessConfig` 只负责 `vless://` → sing-box JSON，不碰内核；换内核不影响解析层 |
| **未配置即直连** | `hasConfig()==false` 时不显示内置条目、不下发代理；`getLocalPort()<=0` 时**不设置代理**，回落直连。避免"端口 -1 导致连不上" |
| **PlatformInterface 桩** | 本地代理无 TUN/VPN，15 个回调返回中性值（v1.13.21 只有 15 个方法，非新版 29 个） |
| **版本钉死** | libbox 跨版本 `PlatformInterface` 签名会变，CI 固定 v1.13.21；升级需重跑 javap dump |

---

## 5. 重构范围

### 5.1 已删除（ws 相关）

| 文件/模块 | 说明 |
|---|---|
| `libs/tcp2ws/` | 整个 gradle 模块（已从 `settings.gradle` 移除 include） |
| `WebSocketHelper.kt` | ws 代理核心（SOCKS 端口、provider、TLS 开关） |
| `WsSettingsActivity.java` | ws 代理设置页 |
| `NekoConfig` 中 `wsEnableTLS` / `wsServerHost` / `wsBuiltInProxyBackend` | ws 配置项 |

### 5.2 已新增

| 文件 | 职责 |
|---|---|
| `helpers/VlessConfig.kt` | `vless://` 解析 → sing-box 配置 JSON（reality/tls/ws/grpc/flow 等） |
| `helpers/VlessProxyManager.kt` | 对外唯一入口：哨兵地址、本地端口、`hasConfig()`、启停 |
| `helpers/LibboxEngine.kt` | libbox 生命周期 + `CommandServerHandler` + `PlatformInterface` 桩 |
| `VlessProxyService.java` | 前台 Service，承载内核、常驻通知 |
| `settings/VlessSettingsActivity.java` | 设置页：填链接 + 开关 |

### 5.3 已改动（接线）

| 文件 | 改动 |
|---|---|
| `ConnectionsManager.java` | 3 处代理分支：`WebSocketHelper.*` → `VlessProxyManager.*`，并加 `port>0` 守卫 |
| `SharedConfig.java` | 代理列表条目改用 `PROXY_SERVER`；`hasConfig()==false` 时不注入内置条目 |
| `ProxyListActivity.java` | 条目展示/跳转指向 `VlessSettingsActivity` |
| `NekoConfig.java` | 新增 `vlessEnabled` / `vlessLink` |
| `build.gradle` | `implementation fileTree("libs")`（对齐 Momogram：`TMessagesProj/libs/` 下任意 AAR 自动纳入；并取代原 `compileOnly fileTree('libs')`，避免同 AAR 双 classpath） |
| `AndroidManifest.xml` | 登记 `VlessProxyService` |
| `values/strings.xml` | 新增 vless 相关文案 |
| `.github/workflows/build_arm64.yml` | CI 拉取并缓存 libbox.aar（v1.13.21） |

### 5.4 重构目标

1. **接线收敛到单点**：代理地址/端口只由 `VlessProxyManager` 暴露，UI 与 JNI 不直接依赖内核。
2. **内核可替换**：`LibboxEngine` 与 `VlessConfig` 解耦，后续换内核或换协议不动 UI/接线。
3. **未配置零副作用**：无链接时行为等同无内置代理，不影响直连与启动。
4. **构建可复现**：内核以固定版本 AAR 由 CI 获取并缓存，不入库、不依赖手工放置。

### 5.5 对齐参考实现（Momogram / `im030/Momogram`）

Momogram 作为"在 Telegram 客户端里内置代理内核"的开源参照，本次已对齐其三要素：

| Momogram 做法 | 本项目的落地 |
|---|---|
| 内核 AAR 放 `TMessagesProj/libs/`（`libv2ray.aar` 等） | ✅ `libbox.aar` 放 `TMessagesProj/libs/` |
| `implementation fileTree("libs")` 引入 | ✅ 已替换原 `files(...)` + `compileOnly fileTree` |
| CI 构建/下载 AAR + `actions/cache` 缓存 | ✅ `gh release download` + cache（钉 `v1.13.21`） |

已知差异（均为有意为之）：

- Momogram 按 **flavor 门控**（仅 `full` 变体引入内核，FOSS/F-Droid 排除）；NagramXF 只有 `normal`/`plugin` 两个 full 变体，**无需门控**。若未来加 FOSS 变体，可仿照其 `sourceSets.all { if (name.startsWith("full")) ... }` 结构。
- Momogram **从源码构建** AAR（`./run libs v2ray`，gomobile）；本项目用第三方预编译 `libbox.aar`。若要消除该第三方信任依赖，可改为 CI 内 gomobile 自构建（成本约 10–20 分钟/次，且需随 sing-box 上游钉 commit）。

---

## 6. 替换后应达到的可用状态（验收）

| # | 验收项 | 期望 |
|---|---|---|
| 1 | **冷启动（未配置 vless）** | 应用正常打开，直连；代理列表**不出现**内置 VLESS 条目；无重连风暴 |
| 2 | **配置后连接** | 填入可用 vless 链接并开启 → 通知栏出现常驻前台服务 → Telegram 经本地端口连通 |
| 3 | **链接无效/节点不可用** | 仅代理失败，**应用仍可正常打开**；关闭开关即回落直连 |
| 4 | **链接为空但开关为开** | 不设置代理（`port<=0` 守卫），不出现连接卡死 |
| 5 | **后台保活** | 切后台后代理不中断（前台 Service + 常驻通知） |
| 6 | **构建** | CI 绿（`Build arm64-v8a Release`），产出 APK —— 已达成 |

---

## 7. 风险与后续

| 风险 | 影响 | 应对 |
|---|---|---|
| libbox 版本升级导致 `PlatformInterface` 变化 | 编译失败 | 版本钉死；升级前用 CI 的 javap 重新 dump API 再改桩 |
| AAR 体积（全 ABI 约 90MB，arm64 单架构较小） | APK 体积 | 仅保留 arm64-v8a；CI 按版本缓存不下库 |
| 前台服务常驻通知 | 用户感知/耗电 | 仅在开启代理时出现；关闭即销毁 |
| Reality/节点参数不支持 | 部分链接连不上 | 已支持 reality/tls/none + ws/grpc；不支持的参数忽略，可扩展 |
| 本地端口冲突 | 启动失败 | 固定 6357；若被占用需重启服务（后续可改为动态端口） |

**后续可选项**：动态端口分配、订阅导入、多节点与延迟测速、按需切换 Hysteria2/TUIC。
