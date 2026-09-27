# WS 中继与 sing-box 链路：稳定性缺陷修复

日期：2026-09-27
范围：`libs/tcp2ws`（内置 ws 中继）与 sing-box 节点链路
前置文档：`2026-09-26-可连性与稳定性加固.md`

---

## 一、结论

「WS 代理确定可用，但仍频繁出现"正在连接代理"」有**明确的代码级根因**，不是网络问题，也不是用户配置问题。共定位 7 处缺陷，其中 1 处直接生产该症状：

> `ProxyHandler.prepareServer()` 在无法建立上游 WebSocket 时**静默返回**（`m_ServerSocket == null`），
> 而调用方已经向客户端回写了 **SOCKS5 成功应答**。客户端因此认为"代理连接已建立"，
> 一行之后隧道即被关闭 → 客户端立刻重连 → 循环。

SOCKS5 应答本身是**畸形**的（8 字节、`VER=0x00`），但经核实 Telegram 的客户端实现只校验第 2 字节：

`TMessagesProj/jni/tgnet/ConnectionSocket.cpp:823-837`
```cpp
} else if (proxyAuthState == 6) {
    if (readCount > 2) {
        uint8_t status = buffer->bytes()[1];
        if (status == 0x00) { proxyAuthState = 0; ... }
        else { closeSocket(1, -1); }
```
即 `VER` / `ATYP` / 长度均不校验，只要求 `readCount > 2` 且 `bytes[1] == 0`。
**所以该应答格式是"能工作"的，本次刻意不改动**——改动它属于在无法真机验证的情况下修改一条已验证可用的协议细节。

---

## 二、根因（按影响排序）

### R1 —— 失败后仍回"成功"，客户端建立即被断（直接产生症状）

`ProxyHandler.prepareServer()` 原逻辑（`libs/tcp2ws`）：

```java
int count_520 = 0;
while (count_520 < 10) {
    try { m_ServerSocket = ...connect(); break; }
    catch (WebSocketException e) {
        if (e.getMessage().contains("520")) { count_520++; sleep(...); }
        else { System.out.println(server); e.printStackTrace(); break; }   // ← 静默放弃
    }
}
```

三处独立缺陷叠加：

| 子问题 | 后果 |
|---|---|
| 非 520 异常**零重试**立即 `break` | 连接被重置、TLS 抖动、边缘节点慢响应都算"致命"，直接放弃 |
| 放弃后**不抛异常** | `Socks5Impl` 继续执行 `replyCommand(getSuccessCode())`，回写成功 |
| `e.getMessage()` 未判空 | 无消息的 `WebSocketException` 在此抛 NPE，**应答根本没发出**，客户端一直等握手 |

失败序列（修复前）：

```
Telegram → SOCKS5 CONNECT → 中继：上游 dial 失败，m_ServerSocket = null
                            ↓ 仍回 0x00 (success)
Telegram：代理连接"已建立"
                            ↓ processHandshake() 对 null 解引用 → NPE
中继：捕获 → close() → 客户端 socket 关闭
Telegram：EOF → 立即重连 → 循环
```

修复：`prepareServer()` 在上游不可用时**抛 `IOException`** → `Socks4Impl.connect()` 捕获并回写 SOCKS 失败码。
客户端看到的是"这次代理连接失败"，而不是"建立后瞬间掉线"。

### R2 —— 未知目的地址回退到裸 IP（必然失败）

`Socks4Impl.getCdn()` 原逻辑在映射表未命中时 `return _server`（裸 IP），于是对
`wss://149.154.167.51/api` 发起 TLS：Worker 按 Host/SNI 路由，且没有证书匹配裸 IP。
该路径与 R1 叠加，构成"必然失败 + 成功应答"的最坏组合。

修复：未命中即**拒绝并记录**（`RelayLog`），不再构造注定失败的 dial。选择拒绝而非猜测
DC 主机名，是因为猜错会静默把流量引到**错误的 DC**——比拒绝更糟。

### R3 —— 非 520 的瞬时失败无重试

见 R1 子问题。修复：统一在 `UPSTREAM_DIAL_BUDGET_MS = 12s` 预算内对所有
`IOException | WebSocketException` 做指数退避重试（250ms → 2s 封顶）。

### R4 —— `relay()` 忙轮询

`m_ClientSocket.setSoTimeout(DEFAULT_PROXY_TIMEOUT)` 中 `DEFAULT_PROXY_TIMEOUT = 10`（**毫秒**），
配合 `Thread.yield()` 构成忙等热循环。Telegram 同时维持多条长连接（主/媒体/上传/下载），
每条隧道一个线程、每线程约 100 次唤醒/秒 → 常驻占满单核。系统一旦节流（后台、省电、Doze），
WebSocket 读写被延迟，表现为隧道卡死与重连。

修复：新增 `RELAY_READ_TIMEOUT_MS = 30_000`，改为阻塞读（空闲隧道零 CPU）；
`close()` 从其他线程关闭 socket 仍可立即中断阻塞读。

### R5 —— 40 字节中继缓冲 → 每 40 字节一个 WebSocket 帧

`DEFAULT_BUF_SIZE = 40` 被同时用作 SOCKS 头解析长度与中继缓冲长度，而
`permessage-deflate` 已协商开启 → 每个帧一次压缩上下文操作。

修复：新增 `RELAY_BUF_SIZE = 16 * 1024`，帧率下降约两个数量级。

### R6 —— 全部失败日志写往 stdout

`System.out.println` / `printStackTrace` 在 Android 上不进入应用日志，导致"用户报障但无法归因"。

修复：`libs/tcp2ws` 新增 `RelayLog` 注入式日志出口；`WebSocketHelper` 启动时接到 `FileLog`
（前缀 `tcp2ws:`）。库本身保持零日志依赖。

### R7 —— 半开隧道无检测

Worker 回收、移动网 NAT 重绑定、Wi-Fi↔蜂窝切换都会留下**半开 WebSocket**：
`isOpen()` 仍返回 true，无 close 帧、无异常。客户端会持续写入直到自身超时。

修复：看门狗（20s tick）在**完全无入向帧**达 `UPSTREAM_DEAD_AFTER_SILENCE_MS = 100s` 时关闭隧道。
阈值刻意取大：健康隧道的静默上限受 Telegram 自身 MTProto ping（约 60s）约束，而 100s 留有充分余量。
**未采用"单个 ping 未响应即关"的激进策略**——若该 Worker 不回 pong，那会自己制造周期性重连。

---

## 三、取消定时 ping，改为事件驱动

`ProxyPingController`（10s 定时 ping）已删除，替换为 `ProxyHealthController`。

### 为什么删除是对的

1. **探测本身有成本**：ws 行为一次新的 CDN WebSocket dial；节点行为一次引擎往返。
2. **测的不是要的东西**：正在正常承载流量的代理，一次探测失败说明不了任何问题；而把**在用**
   代理标记为不可用会让列表把它排到末位、轮换控制器当它已死。
3. **需要的信号客户端已经在给**：代理启用时，Telegram 自身的连接状态**就是**可用性度量。

### 新的三层可用性判定

| 层 | 触发 | 行为 |
|---|---|---|
| 被动（免费、精确） | `didUpdateConnectionState == Connected/Updating` | 在用代理判定为可用，并打上新鲜度时间戳（同时抑制列表页重复探测） |
| 反应式 | `didUpdateConnectionState == ConnectingToProxy` | 检查 app 侧能修的东西：节点引擎是否还在（`recoverEngine()`）、内置 ws 中继是否在监听；15s 节流 |
| 按需 | 列表页打开（`onResume`，900ms 去抖 + 新鲜窗口）、菜单 `RetestPing` | 单轮探测 |

### 列表页探测放大器已拆除

`ProxyListActivity.updateRows()` 原本**末尾无条件**调用 `checkProxyList()`，而 `updateRows` 会被
`proxyCheckDone`、每次连接状态变为 `Connected`、`proxySettingsChanged` 触发 —— 形成
"探测 → 通知 → 重建 → 再探测"的正反馈。现已断开：`updateRows()` 不再探测。

---

## 四、sing-box 链路稳定性修复

### S1 —— 引擎恢复"5 轮后永久放弃"

`ProxyEngineClient.scheduleRecovery()` 原逻辑在 `MAX_RECOVERY_ROUNDS = 5` 后**永久停止**尝试。
后果：冷启动期间引擎被系统回收，5 轮内没起来 → 之后**整个进程生命周期内** Telegram 都指向一个
无人监听的本地端口，只能重启 app。

这也是原来那个 10s 定时器存在的唯一理由。修复后定时器可以彻底删除：
`MAX_RECOVERY_DELAY_MS = 60s` 封顶的指数退避，**不设次数上限**，引擎一旦应答即重置计数。
（退避位移已做钳位：`shl` 的位移量按 64 取模，不钳位会在计数变大后回绕成极小延迟。）

### S2 —— 切换节点导致本地端口漂移

`SingBoxEngineService.handleStart()` 原逻辑对每个节点用 `31000 + hash(link) % 18000` 选端口，
于是节点 A→B 会换一个本地端口，Telegram 的 `127.0.0.1:<port>` 端点随之失效，必须先
`native_setProxySettings` 重设、期间全部连接被拒。

修复：`handleStart` 记住**旧引擎正在监听的端口**并优先复用（带 ≤300ms 宽限期等待内核释放），
仅当确实无法复用时才回落到哈希基址。效果：切换节点只换上游，本地端点不变。

---

## 五、改动文件

| 文件 | 变更 |
|---|---|
| `libs/tcp2ws/RelayLog.java` | 新增：注入式日志出口 |
| `libs/tcp2ws/SocksConstants.java` | 新增 `RELAY_READ_TIMEOUT_MS` / `RELAY_BUF_SIZE` / `UPSTREAM_DIAL_BUDGET_MS` |
| `libs/tcp2ws/ProxyHandler.java` | R1/R3/R4/R5/R6/R7：上游失败必须抛出、全类型退避重试、阻塞读、16KB 帧、日志、静默看门狗、`close()` 幂等 |
| `libs/tcp2ws/Socks4Impl.java` | R2：`getCdn()` 未命中即拒绝；`connect()` 失败路径不再解引用 null |
| `libs/tcp2ws/tcp2wsServer.java` | 接受循环/accept 失败写日志 |
| `tw/nekomimi/.../WebSocketHelper.kt` | 接入 `RelayLog` → `FileLog`；记录监听端口与上游主机 |
| `org/telegram/messenger/ProxyHealthController.java` | 新增：事件驱动可用性判定 |
| `org/telegram/messenger/ProxyPingController.java` | **删除** |
| `org/telegram/messenger/ApplicationLoader.java` | init 调用替换 |
| `org/telegram/ui/ProxyListActivity.java` | 拆除 `updateRows()` 内的无条件探测 |
| `tw/nekomimi/.../ProxyEngineClient.kt` | S1：恢复改为封顶指数退避、不设次数上限 |
| `tw/nekomimi/.../services/SingBoxEngineService.kt` | S2：切换节点复用本地端口 |

---

## 六、静态校验

* 全部 11 个改动文件的括号 / 圆括号 / 方括号配平 **PASS**。
* 引用一致性：无 `ProxyPingController` 残留；`choosePort` 唯一调用点已同步为 3 参数；
  无 `MAX_RECOVERY_ROUNDS` 残留；`RelayLog` 全部调用点在同一模块内。
* **未编译**：本机无 JDK / Android SDK，`javac` 不存在。编译由 CI（`.github/workflows/build_arm64.yml`）承担。

---

## 七、真机验证清单

| # | 步骤 | 期望 |
|---|---|---|
| 1 | 内置 ws 代理在线，抓日志 | 出现 `tcp2ws: relay listening on 127.0.0.1:6356`，无 `relay failed to start` |
| 2 | 持续使用 5 分钟 | 不再出现周期性的 `Tunnel` 重建；`tcp2ws: ws upstream connected` 的次数应≈连接建立次数而非每秒 |
| 3 | 断开/切网（Wi-Fi↔蜂窝） | 30s 内恢复；日志出现 `ws upstream silent for ...` 或 `ws disconnected`，**不出现** `ws upstream connected` 与 `connected` 交替刷屏 |
| 4 | 目的地址未映射时 | 日志 `no ws upstream mapped for <ip> - refusing the tunnel`，客户端表现为"代理连接失败"而非"已连接后掉线" |
| 5 | 内置 ws 代理在线时看 CPU | 空闲应接近 0%（修复前为忙轮询） |
| 6 | 节点 A → 节点 B 切换 | 日志显示新引擎复用同一端口；`native_setProxySettings` 不应被反复调用 |
| 7 | 杀掉 `:singbox` 进程 | ≤60s 自动恢复（退避上限），且**不再**出现"永久放弃" |
| 8 | 代理列表页停留 2 分钟 | 不出现周期性探测（无 `tunnel`/`socks request` 依据的行内延迟刷新） |

---

## 八、剩余风险与未实施项

1. **`getCdn()` 映射表未扩充**。Telegram 若使用表中不存在的 IP（例如 `149.154.175.53/54`、
   `149.154.167.50/92` 等交替地址），现在会被明确拒绝而不是静默失败。要真正可用需补齐映射表，
   但**我没有把握的 IP 一律没有添加**——错映射会把流量引到错误 DC，比拒绝更糟。若真机日志出现
   第 4 条，把 IP 报给我即可精确补充。
2. **R7 的 100s 阈值未经真机验证**。若该 Worker 不回 pong 且 Telegram 侧确有空闲 >100s 的连接，
   可能出现误关。验证方式：观察是否有"无流量时每约 100s 一次 `ws upstream silent`"。若出现，
   把阈值调高或改为"仅在客户端有写入且长时间无响应"时判定。
3. **SOCKS5 应答格式仍为 8 字节畸形格式**。当前 Telegram 客户端容忍，故未改。若日后更换客户端
   实现或 Telegram 收紧校验，这将立即成为"完全连不上"，需改为标准 10 字节应答——
   **这是一颗埋着的雷，建议在有余力时一并改掉并真机验证**。
4. **节点故障时仍不能自动切到另一节点**（`ProxyConnectivityHelper.canProbe` 的已知缺口），
   原因与陷阱见 2026-09-26 文档第三节。
5. **`SocksConstants.DEFAULT_PROXY_TIMEOUT = 10ms` 仍用于 UDP 路径**（`Socks5Impl.initUdpInOut`），
   Telegram 不使用 SOCKS5 UDP，故未动；若将来启用则为同样的忙轮询问题。
