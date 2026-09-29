# swap · 换电柜设备接入与通信协议规范（M0-1 定稿）

> 本文是换电柜项目的**上游地基规范**：定义云端与柜机/电池之间的通信契约。
> M0-2 订单状态机、M1 接入实现、M2 换电主链路、M3 异常补偿、模拟器与压测**全部以本文为依据**；
> 与本文冲突的实现一律视为缺陷。实际情况与本文不符时就地修改本文并登记变更。
>
> **分层归属（包边界纪律）**：
> - §1–§3、§5–§7、§9–§11 中与业务无关的部分 → 落地为 `framework/iot`（通用设备接入基座），
>   其中**禁止出现 slot / battery / order / 套餐 等业务语义**，只有 device / session / command / thing-model / shadow / telemetry。
> - §4 换电指令集与事件集（`OPEN_SLOT`、`swap_result` 等）→ 落地为 `biz/swap`，通过框架的指令与事件 SPI 注册。
> - 表结构明细属 M0-3，本文只列实体边界与关键字段约束。

---

## 1. 设备模型与标识

### 1.1 三层标识

| 概念 | 标识 | 说明 |
|---|---|---|
| 品类（产品） | `productKey` | 一类设备的物模型载体。如 `SWAP-CAB-8`（8 仓换电柜）、`SWAP-CAB-12`、`BAT-60V20AH`（电池本体）、`CAB-GW-PRO`（柜机主控） |
| 设备 | `deviceId` | 全局唯一，出厂写入。`clientId = {productKey}::{deviceId}` |
| 连接会话 | `sessionId` | **每次物理连接一个新值**，由云侧签发（CONNECT 成功时下发），用于丢弃跨会话的迟到报文 |

### 1.2 设备树（关键建模选择）

```
柜机（网关设备，独立 deviceId，MQTT 直连）
 ├── 仓位 slot 1..N      ← 不是设备，是柜机的"子资源"，随柜机上报、随柜机寻址
 ├── 主控/充电模块        ← 归并入柜机属性，不独立建模
 └── 电池（子设备，独立 deviceId，经柜机透传上报）
```

- **电池本体建模为独立设备**：BMS 上报（SOC/SOH/单体电压/温度/故障码/互锁）、GPS 定位、循环次数、SOH 衰减都需要脱离"是否在柜内"而存在（电池在骑手车上、在维修点、丢失在路边时同样有数据）。
- 电池经**柜机透传**上报，信封带 `viaDeviceId`；电池独立 4G 上报时 `viaDeviceId` 为空。物模型与校验规则统一，只是寻址路径不同。
- **仓位不建为设备**：仓位没有独立通信身份，开门指令的目标是"柜机的第 k 号执行器"。把仓位建成设备会让指令路由、影子、在线判定全部无意义地翻倍。
- 电池与柜机的关系是**运行期位置关系**（在哪个仓 / 在谁手上），属业务侧资产台账，不进设备树静态结构。

---

## 2. 传输层（MQTT 5.0）

### 2.1 连接参数

| 项 | 取值 | 理由 |
|---|---|---|
| 协议版本 | MQTT 5.0 | 需要 User Properties（traceId / ts / nonce 载体）、Reason Code、共享订阅、Session Expiry |
| KeepAlive | 120s（出厂可配） | 4G 流量与功耗权衡；柜机市电供电可激进，电池终端保守 |
| 离线判定 | 云侧 `1.5 × KeepAlive` = 180s 无报文即**疑似**离线，**连续 2 个判定窗口缺失才确认离线** | 单次抖动不得判离线，否则在线率与告警全是噪声 |
| CleanStart | `false`，`Session-Expiry-Interval ≥ 300s` | 短闪断不丢订阅 |
| TLS | 生产 `8883 + TLS1.2+`；dev `1883` 明文 | 见 §9 |

**在线状态判定不依赖 Broker 私有特性**（`$SYS` 遗嘱事件、EMQX 专有主题、retained 语义在各 Broker 实现不一致）。
判定输入只有三个来源，按优先级合并：**① 自有心跳报文（权威）→ ② LWT（仅用于加速感知，不单独定罪）→ ③ 云侧报文静默计时器（兜底）**。
这是"本地嵌入式 Java Broker"与"生产 EMQX"可互换的前提。

**同理，可靠投递不依赖 Broker 侧离线消息队列。** 未投递成功的指令持久化在云端 `iot_command` + Outbox，由云侧重投；Broker 的 session 缓存只当"顺手的加速"，不做正确性依赖。理由：Moquette / Vert.x MQTT / EMQX / 云厂商 IoT 的会话缓存与过期语义实现差异大，且 Broker 重启即丢，押它上面等于把正确性交给别人的实现细节。

### 2.2 QoS 分级（铁律）

| 报文 | QoS | 理由 |
|---|---|---|
| `telemetry` 遥测 | **0** | 5–10s 后下一条就来，为它重传只会放大弱网拥塞；丢包用"末值 + 空洞可见"处理 |
| `event` 物理事件 | **1** | 事实源，不可丢；重复由 §6 去重消化 |
| `cmd_reply` 指令应答 | **1** | 必须知道结果 |
| `status` 上下线 | **1 + Retained** | 新订阅者/重启后能立即拿到当前快照 |
| `cmd` 下行指令 | **1** | 必须送达；**送达不等于只送达一次** |

> **QoS1 必然产生重复，因此业务层去重是强制项，不是容错选项。**
> 反过来：MQTT 的"恰好一次"（QoS2）解决的是**协议传输层**，不解决**客户端/业务层幂等**——
> 客户端在 PUBLISH 中途崩溃后重连，消息可能已发也可能未发。所以即使 QoS2 也仍需 msgId 去重。

### 2.3 主题规范

```
上行  swap/v1/up/{productKey}/{deviceId}/status        Retained, QoS1
      swap/v1/up/{productKey}/{deviceId}/telemetry     QoS0
      swap/v1/up/{productKey}/{deviceId}/event         QoS1
      swap/v1/up/{productKey}/{deviceId}/cmd_reply     QoS1
      swap/v1/up/{productKey}/{deviceId}/sub/{subDeviceId}/{kind}   子设备(电池)透传，kind ∈ telemetry|event

下行  swap/v1/dn/{productKey}/{deviceId}/cmd           QoS1
      swap/v1/dn/{productKey}/{deviceId}/cmd/critical  QoS1，独立主题（见 §4.3）
      swap/v1/dn/{productKey}/{deviceId}/shadow        QoS1, Retained
      swap/v1/dn/{productKey}/{deviceId}/ota           QoS1
      swap/v1/dn/{productKey}/group/{groupId}/cmd      QoS1 组播（批量配参、灰度）
      swap/v1/dn/{productKey}/group/{groupId}/ota      QoS1 组播
```

- 设备侧订阅：`swap/v1/dn/{productKey}/{deviceId}/#` + 自身所属 group 主题。
- **组播由 Broker 主题前缀展开，云端不得 for 循环逐台 publish。** 1000 台柜机的批量配参若逐台发，发布线程会被打死。
- **ACL 最小权限（真实项目最常漏的一条）**：设备只允许 `PUBLISH` 自己的 `up/*`、只允许 `SUBSCRIBE` 自己的 `dn/*` 与所属 group 主题；**禁止订阅他机主题**。否则一台被破解的柜机可以静默监听全网"开仓门"指令，或伪造他人 deviceId 发布。

---

## 3. 报文信封（`cmd` / `cmd_reply` / `event` 共用）

```json
{
  "v": "1.0",
  "msgId": "01JZ8Q3X7K2N9F4R6T8Y0W1E3S",
  "ts": 1790000000000,
  "nonce": "8f3a1c9d2e",
  "traceId": "order:2088:step:3:OPEN_RETURN",
  "sessionId": "c-7f3d91",
  "from": "SWAP-CAB-8::CAB0000123",
  "via": null,
  "seq": 42,
  "cmd": "OPEN_SLOT",
  "ttl": 60,
  "code": null,
  "data": {},
  "sign": "hex(HMAC-SHA256)"
}
```

| 字段 | 必填 | 语义 | 缺失/被绕过的后果 |
|---|---|---|---|
| `v` | 是 | 协议版本 `major.minor` | 无法判定兼容策略（§10） |
| `msgId` | 是 | **ULID，全局唯一，去重键**（DB 唯一索引） | QoS1 重复被执行两次 → 开门两次 |
| `ts` | 是 | 发送方毫秒时间戳，**只用于诊断与时钟偏移监测，不作过期判据**（见 §3.2） | — |
| `nonce` | 是 | 随机串，防重放窗口内唯一（Redis，TTL = 2×窗口） | 抓包重放合法指令 |
| `traceId` | 是 | 贯穿 HTTP → MQTT → 应答 → Outbox 的链路号 | 排障只能靠时间戳猜 |
| `sessionId` | 下行必填/上行回填 | 报文所属连接会话 | 重连后旧会话的迟到应答被当作新会话处理 → **串单**（§7.3） |
| `from` | 上行是 | 实际发布设备 | — |
| `via` | 否 | 子设备透传时的网关 deviceId | 电池数据无法归位 |
| `seq` | 是 | **每设备单调递增**，云侧维护高水位 | 乱序无法被发现 |
| `cmd` | 是 | 指令标识；`event` 用 `data.eventType` | — |
| `ttl` | 下行是 | 有效期秒数；绝对过期时刻由云侧在投递时计算（§3.2） | 离线补发的过期指令被执行 |
| `code` | 应答是 | 错误码（§8）；`null`/`OK` = 受理成功 | 失败原因丢失 |
| `sign` | 是 | 报文层签名（§3.1） | 报文可伪造 |

### 3.1 签名规则

```
canonical(data) = JSON 序列化：key 字典序、无空白、UTF-8
digest(data)    = SHA256(canonical(data))                 → hex
signBase        = cmd + "\n" + msgId + "\n" + ts + "\n" + nonce + "\n" + ttl + "\n" + digest(data)
sign            = HMAC-SHA256(deviceSecret, signBase)     → hex
```

- `canonical()` 固定字段顺序，**消除"同一语义 JSON 因 key 顺序不同导致验签失败"** 这一类不可复现缺陷。
- 设备 secret 与 MQTT 连接口令 secret **同源不同用途**（派生：`connSecret = HMAC(masterSecret,"conn")`、`msgSecret = HMAC(masterSecret,"msg")`），避免一处泄露两处沦陷。

### 3.2 有效期判定：不信设备时钟，不信报文 `ts`

柜机的 RTC 可被现场人员改、NTP 在 4G 弱网下不可靠、电池终端甚至没有时钟源。因此：

- **云侧在下发时记录 `sentAt`（服务端时间）并计算 `expireAt = sentAt + ttl`。**
- 过期判定发生在**两处**，都用可信时间：
  - 云侧：重投前检查 `expireAt`，已过则不再投递，指令直接终态 `EXPIRED`；
  - 设备侧：Broker/云侧在投递时**注入可信 `brokerTs`**（User Property），设备以"接收到的 `brokerTs` 与 `expireAt` 比较"判定过期，不依赖自身 RTC。
- 设备无 `brokerTs` 时（纯离线自治路径）**保守拒绝执行非幂等指令**并上报，宁可不执行。

> **设备时钟偏移是一个可观测指标**（`ts` 与云侧接收时间之差），超阈值告警，但不参与任何正确性判定。

---

## 4. 指令矩阵（本文核心）

### 4.1 换电指令集（`biz/swap` 注册）

| cmd | 副作用 | 幂等依据 | 自动重试 | ttl | 两级确认 | 说明 |
|---|---|---|---|---|---|---|
| `QUERY_STATUS` | 无 | 天然 | 2 | 30s | 仅 ACK | 反查/收敛专用；超时降级读影子末值 |
| `SET_PARAMS` | 低 | 目标值收敛 | 2 | 24h | ACK | 走影子优先，仅即时生效类走 cmd |
| `OPEN_SLOT`（归还仓） | **高** | `msgId`+`slotNo` | **0** | 60s | ACK + `door_open` 事件 | 开门 |
| `LOCK_SLOT` | 中 | 状态收敛 | 1 | 60s | ACK | 运维锁仓/解锁 |
| `UNLOCK_SLOT`（取满电） | **高** | `msgId`+`slotNo` | **0** | 120s | ACK + `door_open`+`battery_taken` | 弹仓 |
| `START_CHARGE`（单仓） | 高 | 状态收敛 | 1 | 15s | ACK + `charge_start` | |
| `STOP_CHARGE`（单仓/整柜） | 高 | 状态收敛 | 1 | 15s | ACK + `charge_stop` | |
| `EMERGENCY_STOP` | **极高** | 状态收敛 | 1 | 10s | ACK；**设备收不到也必须自停** | 走 `cmd/critical`，见 §4.3 |
| `BATTERY_VERIFY` | 无 | 天然 | 2 | 20s | ACK + `battery_detected` | 仓内电池身份/SOC 核验 |
| `BUZZER`/`LIGHT_GUIDE` | 低 | 天然 | 0 | 30s | 仅 ACK | 引导用户，失败不阻塞主链路 |
| `REBOOT` | 高 | — | **0** | 5min | ACK（应答后即断连属预期） | 不得自动重试 |
| `OTA_PUSH` | 中 | 目标版本号幂等 | 1 | 7d | 分段进度事件 | 见 §4.4 |

**铁律一：有物理副作用、重复执行会造成资产损失的指令（开门 / 弹仓 / 重启）绝不自动重试。** 超时后交上层业务决策（人工介入或让用户重下单）。只读的、值收敛的（查询、配参、停充）才允许有限重试。
**铁律二："应答成功" ≠ "动作发生"。** `cmd_reply=OK` 只表示设备受理并执行了指令（通信层事实）；物理事实由后续 `event` 证明。
→ 因此**订单状态机只由物理事件推进，ACK 只推进指令自身的状态**。见 §7。

### 4.2 指令生命周期（框架级状态机，`framework/iot`）

```
CREATED ──dispatch──► DISPATCHED ──reply(OK)──► ACKED ──事实事件匹配──► CONFIRMED  [终态]
   │                       │                                             
   │                       ├─reply(err)────────────────────────────────► NACKED     [终态]
   │                       ├─ACK 超时──────────────────────────────────► TIMEOUT    [非终态→反查]
   │                       └─expireAt 已过（含离线补发）────────────────► EXPIRED    [终态]
   ├─同类新指令取代────────────────────────────────────────────────────► SUPERSEDED [终态]
   └─TIMEOUT/反查后 ACK 成功但事实事件缺失 ────────────────────────────► UNCONFIRMED[非终态→反查/人工]
```

- `UNCONFIRMED` 是**必须存在**的状态：它表达"我承认设备可能骗我 / 事实可能没来"。没有它的实现都隐含假设设备诚实。
- 收敛手段（按序）：`QUERY_STATUS` 主动反查 → 连续两次读数一致才推进 → 仍不一致则 `UNCONFIRMED` 挂起并开工单，**禁止靠猜测推进**。
- 每个非终态都有 `deadline`；扫描由 §7.4 双保险驱动。

### 4.3 紧急控制与失效安全（fail-safe）

- `EMERGENCY_STOP` 走独立主题 `cmd/critical`，与常规指令队列隔离（**常规队列积压 500 条时不得阻塞停充**），消费侧独立线程池 + 最高优先级。
- **失效安全定义在设备侧，不依赖云端**：过温、烟感、水浸、漏电任一触发 → 柜机本地立即断充 + 锁仓 + 声光 + 尽力上报。云侧不可用时也必须发生。
  云侧的角色是**接收事实、派单、复核**，不是安全闭环的唯一守门人。
- 反向策略需明确（M0-2 决策点之一）：**柜机离线时是否允许"已授权用户"离线换电？** 行业存在蓝牙离线换电做法。本方案立场：默认**禁止**（离线即拒绝，权益不受损可重试），支持按站点/租户配置放开为"离线自治 + 事后补单"，但配置项必须显式、需审批、且强制记录离线决策凭证供对账。

### 4.4 OTA 专项

分段进度事件（`downloading/verified/installing/success/rollback`）+ **批次灰度**（按 group 分批、失败率超阈值自动熔断暂停批次）+ **双分区回滚**（设备升级失败下次心跳自动回退旧版本并上报）。
批次推进必须可中断、可续跑、可审计——这与 barrier 的"调度幂等"是同一类正确性问题。

---

## 5. 上行事件与遥测

### 5.1 事件清单（`event`，QoS1，事实源）

| eventType | 关键载荷 | 业务挂钩 |
|---|---|---|
| `door_open` / `door_close` | slotNo、magnet（门磁原始态）、trigger（`CMD`/`MANUAL`/`REMOTE`/`MECHANICAL`） | 订单推进的**唯一权威输入** |
| `door_fault` | slotNo、持续时长 | 挂起 + 工单 |
| `slot_occupied` / `slot_empty` | slotNo | 仓位可用性、资产对账 |
| `battery_detected` | slotNo、batteryCode、soc、voltage、temp、**identityMatch**（是否本人绑定/是否本网/是否隔离名单） | 归还核验、拒收判定 |
| `battery_taken` | slotNo、batteryCode、soc | 取电确认，权益扣减前置 |
| `charge_start` / `charge_stop` | slotNo、reason | 充电健康度、能耗 |
| `alarm` / `alarm_clear` | code、level、slotNo 或柜级、快照（当时遥测切片） | 告警引擎输入 |
| `swap_result` | 见 §5.2 | **订单结算的对账锚点** |
| `reboot` / `watchdog` | cause、uptime | 异常重启追踪 |
| `session_ready` | 上线首报：全量属性快照 + 影子版本 + 待执行指令游标 | 上线对齐 |

### 5.2 `swap_result`（柜侧一次换电的物理小结）

```json
{
  "eventType": "swap_result",
  "orderNo": "SW20260929...",
  "userToken": "骑手侧一次性核验串（不含身份证/手机号）",
  "returnSlot": 3, "returnBatteryCode": "BAT...", "returnSoc": 12,
  "offerSlot": 7,  "offerBatteryCode": "BAT...",  "offerSoc": 98,
  "result": "SUCCESS" | "PARTIAL" | "ABORTED",
  "failReason": null,
  "timeline": [ {"event":"door_open","ts":...}, {"event":"battery_detected","ts":...}, ... ],
  "cabTemp": 41.2, "meterNo": 100234
}
```

- **它是柜机对物理世界的独立陈述**，与云端订单推进过程互相**交叉核对**，不互为输入。
- 云端订单**不得**因为"设备说成功了"就把状态改成成功；只把它作为**对账输入**：一致 → 正常闭环；不一致 → 落差异记录 + 人工核资 + 工单。
- `timeline` 让"物理侧看到的顺序"可追溯，是排查乱序/丢事件的唯一依据。

### 5.3 遥测节拍与字段（对齐《两轮电动车换电用 BMS 技术要求》）

- 常规上报 **≤10s**；关键告警**事件触发即时（≤1s）**。
- 允许 `data.points[]` 批量携带（省 4G 流量），批量必须带各点独立时间戳。
- 柜体：输入电压/电流/功率、柜内温度、湿度、烟感、水浸、震动、每仓门磁、充电器温度、漏电状态、固件版本、4G RSRP。
- 电池：包电压、包电流、SOC、SOH、最高/最低单体电压、平均/最高温度、故障码、安全互锁状态、允许最大充/放电电流、循环次数、（有定位模块时）经纬度 + 精度 + 授时源。
- 遥测写入走 `TelemetryStore` 端口（§11.3），**不落交易表的行**，且写入失败**不得影响指令与订单链路**（隔离线程池 + 丢弃计数指标）。

---

## 6. 上行校验链（设备侧规范实现，严格按序，失败即回错误码且不执行）

```
1 长度/JSON 解析          → E_MALFORMED
2 主版本支持              → E_VERSION
3 cmd 在指令矩阵中        → E_UNKNOWN_CMD        （禁止静默忽略）
4 验签                    → E_SIGN
5 brokerTs 未过期         → E_EXPIRED
6 nonce 在窗口内未见      → E_REPLAY
7 seq 单调（允许窗口内乱序）→ 记录 E_SEQ_GAP（仍执行并上报，不静默）
8 msgId 是否处理过        → 命中则**重放历史结果**（见下）
9 前置状态守卫            → E_PRECONDITION（如目标仓故障/门未关/仓位被锁定）
10 执行 → reply(OK) → 物理事件
```

**第 8 步是重点，也是最容易做错的地方：重复指令不能"忽略"，必须"重放上次执行结果"。**

- 错误做法：收到重复 msgId → 直接 return，不响应。云侧拿不到应答 → 误判 `TIMEOUT` → 触发反查/告警，把一个本来成功的动作搅成一团糊账。
- 正确做法：设备侧持久化 `(msgId → code, data, processedAt)`（保留最近 N 条 / 7 天），重复到达时**原样重发上次的应答**。云侧据此正常闭环，去重在两端都成立。

对应地，**云侧消费上行也必须幂等**：`iot_event_dedup`（`deviceId + msgId` 唯一索引）先落库再分发；撞唯一索引即判重复，直接 ack 丢弃并计数（指标 `iot.event.duplicate`）。

---

## 7. 下行确认与会话匹配（框架级，`framework/cmd`）

### 7.1 指令与业务步骤解耦

一个换电动作步骤 = 一条指令；一条指令可能对应多条应答/事件。
`iot_command` 表以 `(bizType, bizId, stepNo)` 反向挂接到业务步骤（`biz/swap` 的 `swap_order_step`），
但**指令状态变更不直接改订单状态**——必须经由 §7.2 的物理事件匹配器。

### 7.2 物理事件匹配键

```
match(orderId, stepNo, expectedEventType, slotNo, sessionId, ts ∈ [stepStart, stepStart + window])
```
匹配成功才产生一次订单状态迁移；匹配失败（无归属事件）落 `unmatched_event` 记录并告警——
**它可能是"用户手动开仓"、"运维远程开门"、或"上一单的迟到尾巴"，都必须可见，不许静默丢弃。**

### 7.3 会话隔离（防串单）

- 下发指令时绑定当时的 `sessionId`；回来的应答/事件 `sessionId` 不一致 → **判定为跨会话迟到报文，丢弃并计数**（指标 `iot.session.stale`）。
- 柜机 4G 闪断重连很常见（`sessionId` 变），若不隔离，旧会话的迟到 `OPEN_SLOT` 应答会推进新会话下的**另一笔订单**——这类 bug 在真实业务里表现为"我扫了 B 单，A 单莫名完成、电池还少一块"。
- 设备侧另需处理：重连后收到"上一次连接期间已执行但用户未完成"的动作 → 以 `session_ready` + `swap_result` 上报现场事实，由云侧走 §7.2 反查与差异处理。

### 7.4 超时驱动：双保险，不把正确性押在内存上

- **快路径**：内存延迟任务（JDK `ScheduledThreadPool`），秒级精度，覆盖 99% 正常超时。
- **兜底路径**：`iot_command` 表 + ShedLock 保护的扫描 Job（10s 一次），
  只捞 `status IN (CREATED,DISPATCHED,ACKED) AND deadline < now` 的窄集合（走索引，不扫全表）。
- 发布/重启/崩溃时内存任务全丢是**必然发生**而非异常，兜底扫描是它唯一的解药；同一指令的超时处理必须**可重入且幂等**（两条路径同时命中不得产生两次迁移）。

> 哲学与 barrier 的"错过自然由下一次算对"一致：**不假设调度一定准时，只假设下一次会重读事实。**
> 差别在于 barrier 靠纯函数天然免费，换电必须显式建模 deadline + 反查 + 对账。

### 7.5 跨实例指令路由（单体保留的薄抽象）

柜机长连接只落在某一台接入节点，而 HTTP 请求可能落在任意业务实例。
- `iot_device_session` 注册表（Redis）：`deviceId → {nodeId, connId, ts}`，节点心跳自过期。
- `DeviceGateway` 端口：本节点持有连接 → 直发；否则经节点间通道转发（本地实现 = Redis Pub/Sub）。
- **当前单节点部署**，本层退化为进程内直调，但接口与注册表必须先立起来——否则将来横向扩容时"指令找不到路"是无解的。

---

## 8. 错误码

| 段 | 含义 | 代表码 |
|---|---|---|
| `E0xxx` 协议/解析 | 报文不合法 | `E0001 MALFORMED` / `E0002 VERSION` / `E0003 UNKNOWN_CMD` |
| `E1xxx` 安全 | 认证与防重放 | `E1001 SIGN` / `E1002 REPLAY` / `E1003 EXPIRED` / `E1004 AUTHZ` |
| `E2xxx` 前置状态 | 设备/仓位状态不允许 | `E2001 SLOT_DISABLED` / `E2002 SLOT_FAULT` / `E2003 DOOR_NOT_CLOSED` / `E2004 BUSY_IN_OTHER_ORDER` |
| `E3xxx` 硬件故障 | 执行失败 | `E3001 LOCK_JAM` / `E3002 SENSOR_FAIL` / `E3003 CHARGER_FAULT` / `E3004 TEMP_PROTECT` |
| `E4xxx` 业务拒收 | 换电语义拒绝 | `E4001 BATTERY_UNKNOWN` / `E4002 BATTERY_ISOLATED` / `E4003 BATTERY_NOT_OWNER` / `E4004 SOC_BELOW_MIN` |
| `OK` | 受理成功 | — |

映射到框架 `ResultCode`（保持双层契约，HTTP 侧仍恒 200）：

| 错误码族 | 映射 | 前端/调用方语义 |
|---|---|---|
| `E0*` / 参数类 | `400` | 报文或入参非法 |
| `E2*` / `E4*` | `409 CONFLICT` | 状态冲突，可提示用户重试或换仓 |
| `E3*` | `500` + 自动开工单 | 需现场介入，不得让用户重试解决 |
| `E1*` | 专用安全码 + 安全审计告警 | 疑似攻击，需风控 |
| 设备离线 / 指令超时 | 专用码（M0-2 定义） | 不是"失败"，是"未知"——UI 必须区分显示 |

---

## 9. 安全设计（双层）

| 层 | 机制 | 防护目标 |
|---|---|---|
| 连接层 | `username = {deviceId}|{ts}|{nonce}`，`password = hex HMAC(connSecret, username)`；云侧校验时间窗 + nonce 唯一 | 冒设备身份连入 |
| 报文层 | §3.1 签名 + §6 校验链 | **伪造/篡改/重放开柜指令** |
| 授权层 | Broker ACL（§2.3）+ 云侧指令-设备归属校验（不得向非归属设备下发） | 越权控制他人设备 |
| 密钥存储 | `deviceSecret` **加密落库**（AES-GCM，主密钥走环境变量/KMS），日志与异常栈**严禁输出** secret 或完整 sign | 侧信道泄露 |
| 审计 | 所有下行控制指令进 `@OperateLog` + `iot_command` 不可变留痕；开仓类指令额外记录发起人、审批链（§10 运维侧） | 事后追责 |
| 时钟 | 设备时钟不可信，正确性只用服务端/Broker 可信时间（§3.2） | 篡改 RTC 绕过有效期 |
| 传输 | 生产 TLS + 证书校验；dev 明文但强制 `swap.dev-mode=true` 才允许 | 降级攻击 |

> 依据：行业已发生"换电柜后台被构造报文暴力开柜门"的真实事件。设备侧鉴权、防重放、指令有效期在本项目里是**硬要求，不是可选项**。

---

## 10. 影子与期望状态

- 三段式：`desired`（云侧期望）/ `reported`（设备上报）/ `delta`（差异），各带**单调递增版本号**防丢更新。
- 适用范围：**属性类收敛型配置**——充电功率上限、过温阈值、禁用仓位集合、固件目标版本。
- **明确不适用范围：任何换电动作指令。** 影子是最终一致语义（设备上线才拉 delta），开门必须是指令语义（现在就要结果）。混用会导致"点了按钮三秒后才开门"这类不可解释的体验。
- 设备上线主动拉 delta；`SET_PARAMS` 类指令幂等由目标值收敛保证。

---

## 11. 可靠性支撑件（框架层，`framework/iot` + `framework/event`）

### 11.1 去重
`iot_event_dedup`（`deviceId + msgId` 唯一索引，7 天滚动清理）+ 设备侧 `(msgId → result)` 重放表（§6）。

### 11.2 Outbox（事务与消息的原子性）
业务事务内写 `outbox_event`（同库同事务），提交后由投递器（ShedLock 保护 + 退避重试 + 死信记录）发布到分发通道。
`EventPublisher` 为端口：本地实现 = Redis Stream 消费组；生产可切 RocketMQ 事务消息。
**要求本地实现就得具备**：退避重试、最大重试次数、死信表、消费侧幂等、积压深度指标——不得把"顺序性和可靠性"推给"以后换 MQ 就解决了"。

### 11.3 遥测存储
`TelemetryStore` 端口。本地实现 = MySQL 分区表（按日 RANGE 分区 + 滚动归档 Job）；生产可选实现 = Apache IoTDB（Java 栈，本机可实证）。
**明确不选 TDengine 作为本地实现**：其 3.x 服务端无 Windows 原生发行版，本机无法自证。

### 11.4 物模型
`iot_product` + `iot_thing_model`（**版本化 JSON**：properties / metrics / events / commands 四类，含数据类型、单位、枚举、上下限、读写权限）。
运行期按 `productKey + modelVersion` 加载缓存；上报数据先过物模型校验再入库，非法值入 `raw_payload` + 校验失败记录（**不得因数据不合规模型就丢原始报文**，重放与协议演进都依赖它）。

### 11.5 可观测（自定义指标，进 Prometheus）
`iot.device.online` / `iot.event.duplicate` / `iot.event.unmatched` / `iot.session.stale` / `iot.seq.gap` /
`cmd.dispatch.total{cmd,result}` / `cmd.timeout.total{cmd}` / `cmd.rtt.seconds (P50/P99)` /
`cmd.unconfirmed` / `outbox.backlog` / `telemetry.drop.total` / `security.reject.total{code}`
+ **traceId 跨 MQTT 线程传播**（扩现有 `AsyncConfig.TaskDecorator`，把 MDC 传播覆盖到接入网关与消费线程）。

---

## 12. 版本演进与兼容

- 主题含 `v1`，信封含 `v: "1.0"`。
- **只增不改**：新增字段必须可选；接收方**忽略未知字段**（前向兼容）。
- 语义变更必须升主版本，且云端需同时支持相邻两个主版本（现场设备无法同步升级，滚动升级期是常态）。
- **未知 `cmd` 必须显式回 `E0003 UNKNOWN_CMD`，严禁静默忽略**——静默会让云侧误判为"设备接受但未响应"。
- 兼容性验证方式：协议 **golden-file 契约测试**（`src/test/resources/protocol/v1/*.json` 固定样本，新旧 codec 双向解析断言）；CI 中真 Broker 跑一遍样本回放。

---

## 13. 设备模拟器规范（**协议的规范性实现，不是 mock**）

模拟器是"设备侧行为的第二份实现"，与协议文档同源，是 M3 所有异常路径能被测试的唯一前提。
它必须**真实执行 §6 校验链**（否则任何安全与可靠性测试都是自欺），并提供故障注入开关：

| 编号 | 注入 | 期望系统行为（→ M0-2 异常矩阵的行） |
|---|---|---|
| FI-01 | 指令不回任何应答 | `TIMEOUT` → 反查 → 不自动重试开仓 |
| FI-02 | 同 msgId 应答重复 3 次（含随机延迟） | 云侧去重，仅 1 次迁移 |
| FI-03 | 应答返回失败错误码（E2/E3/E4 各类） | 按 §8 映射分流（换仓 / 工单 / 拒收） |
| FI-04 | ACK 成功后物理事件永不到达 | `UNCONFIRMED` → 反查 → 挂起 + 工单 |
| FI-05 | 事件乱序（close 早于 open） | `seq` 缺口可观测，迁移不因顺序倒挂而错 |
| FI-06 | 离线 30min 后上线收到已过期指令 | 设备侧 `E1003 EXPIRED` 拒绝执行 |
| FI-07 | 重放同一 sign+nonce 的报文 | `E1002 REPLAY` + 安全审计告警 |
| FI-08 | 错误 secret 签名 / 篡改 data | `E1001 SIGN` + 风控 |
| FI-09 | 门磁 1s 内抖动 open/close ×5 | 防抖收敛，不产生 5 次迁移、不刷 5 条工单 |
| FI-10 | 断网重连（sessionId 变更）+ 旧会话应答迟到 | 旧应答丢弃，`iot.session.stale` 计数 |
| FI-11 | 投入旧电池后不关门离开 | 挂起 + 锁仓 + 权益不结算 + 工单 |
| FI-12 | 弹开满电仓后未取并关门 | 电池释放回池，订单不得完成 |
| FI-13 | 取走满电电池但门未关 | 权益正常扣减 + 门未关告警（钱按成功、物按异常） |
| FI-14 | 换电中途 BMS 温度阶跃 / 烟感告警 | 停充 + 锁仓 + 订单中止路径 + 告警升级 |
| FI-15 | 上报不合物模型的数据（越界/类型错） | 入 `raw_payload` + 校验失败记录，主链路不阻塞 |
| FI-16 | 云侧实例在等应答时重启 | 兜底扫描恢复，无永久悬挂订单 |

实现要求：MQTT 客户端 + 独立进程（可与后端同 JVM 不同 main，便于本地起）；
参数化（数量、节拍、故障概率、故障时刻与订单步骤的相对位置）；
**支持"按测试脚本精确编排故障发生在第几步之后"**（否则异常路径测试不可重现）。

---

## 14. 简化 / 取舍登记（本轮决策，供后续回补与对外解释）

| # | 项 | 企业标准做法 | 本方案 | 原因 | 影响 / 回补点 |
|---|---|---|---|---|---|
| S1 | 报文格式 | 二进制紧凑帧（省 4G 流量与终端算力，如 T/BIKE 7.3 / GB/T 27930 风格） | **JSON 先行 + `PayloadCodec` SPI**，二进制帧作为第二实现 | 不阻塞主链路；SPI 抽象需两种实现同时满足才算数 | M5 后补二进制 codec + 报文长度/流量对比数据 |
| S2 | 设备认证 | X.509 双向 TLS 或三元组 + 动态轮转 | 派生双密钥 HMAC（连接层 + 报文层），三元组/证书链**只留接口** | 本地无 CA 与证书签发设施，需可自证 | M6 补证书签发链与轮转（若继续投入） |
| S3 | MQTT Broker | EMQX 集群 + 共享订阅 + 桥接 | 嵌入式 Java Broker（Vert.x MQTT / Moquette）跑主链路与 CI | 本机无 Docker、EMQX 无 Windows 原生包；且**刻意不依赖 Broker 私有特性**（§2.1） | 生产 compose 增加 EMQX；协议不变 |
| S4 | 消息中间件 | RocketMQ 事务消息 / Kafka | Transactional Outbox + Redis Stream 分发，`EventPublisher` 端口 | 环境实证性；Outbox 本身即企业级标准模式 | 生产切 RocketMQ；端口不动业务代码 |
| S5 | 时序存储 | TDengine / IoTDB 专库 | `TelemetryStore` 端口 + MySQL 分区表 + 归档 Job | TDengine 3.x 服务端无 Windows 原生版，本机不可自证；IoTDB 可跑但先不引依赖 | 量级上来切 IoTDB（同一端口另一实现） |
| S6 | 部署形态 | 微服务（接入/业务/账务分域） | **单体同进程 + 薄抽象（§7.5 路由注册表、gateway 端口）** | 用户明确不做微服务；抽象保留横向扩容路径 | 扩容时按 profile 拆接入层进程 |
| S7 | 离线自治换电 | 现场蓝牙离线开仓（行业存在） | 默认禁止；保留站点/租户级开关 + 强制留痕 | 无法验证物理侧凭据来源，默认取保守安全侧 | 需要时实现开关与补单对账 |
| S8 | 支付 | 真实微信支付 V3 商户号 + 证书 + 线上对账单 | 渠道端口 + **模拟渠道**（可触发重复/乱序/验签失败/退款失败）+ 对账框架 | 无商户号；但回调幂等与对账差异链必须能测通 | 有资质时接真实渠道，端口不变 |

> 说明：S3/S4/S5 **不是降级**，而是把实现换成"本机可跑、CI 可证"的等价件，同时保留企业级实现接口。
> 真正延后的是 S1、S2、S7、S8 的四项能力，均已登记且不隐藏。

---

## 15. 遗留到 M0-2 的决策点

1. `REJECTED`（从未发出物理动作）与 `FAILED`（已发出物理动作后失败）两个终态的**退款/权益/工单/统计**差异规则。
2. 每个状态的 `deadline` 取值与"超时后由谁负责"（自动收敛 / 反查 / 人工 / 工单）。
3. 权益扣减的时机语义：**投入旧电池成功即扣**，还是**取到新电池才扣**，以及拒收与回滚时的账实相符规则。
4. 半途异常的资损方向判定（谁吃亏：用户/运营商/电池被错拿/电池被吞），以及哪些格允许自动裁决、哪些强制人工。
5. 同一用户存在在途订单时的并发策略（新单是否允许、A 柜未完成时 B 柜扫码的处理）。
6. 设备离线时"未知态"在 C 端与后台的表达（不能显示为失败）。

---

## 16. 变更日志

- 2026-09-29：初版定稿（M0-1）。确立三层标识与设备树（电池为独立设备、仓位为柜机子资源）、
  QoS 分级与"不依赖 Broker 私有特性/不依赖 Broker 离线队列"两条独立性原则、统一信封与签名、
  有效期不信设备时钟、指令矩阵与两条铁律（副作用指令不自动重试 / ACK≠动作发生）、
  指令生命周期含 `UNCONFIRMED`、上行校验 10 步含"重复指令重放历史结果"、
  会话隔离防串单、超时双保险、跨实例路由薄抽象、错误码与 `ResultCode` 映射、双层安全、
  影子适用边界、Outbox/去重/遥测端口/物模型/可观测件、协议演进与 golden-file 兼容测试、
  模拟器 16 项故障注入目录、8 项简化取舍登记。
