# swap · 换电订单状态机与异常补偿规范（M0-2 定稿）

> 本文是换电主链路的**正确性规范**，依据 [`swap-protocol.md`](swap-protocol.md)（M0-1 通信契约）展开。
> 它定义：订单/步骤/指令/资产四套状态、完整迁移表、**以半途异常为主线的异常矩阵**、不变式及其实现机制、补偿动作目录。
> 实现（M2 主链路、M3 异常与一致性）与测试（含故障注入、压测）以本文为断言依据。
>
> **设计取向（重要）**：本文不是从"正常路径图"出发往上添分支，而是**先枚举每一步可能被打断的方式，再由矩阵倒推状态与守卫**。
> 因此读本文时，异常矩阵（§6）是主体，正常迁移（§4）只是它的一个子集。

---

## 1. 已确认的业务决定（用户拍板，2026-09-29）

| # | 决定 | 内容 | 代价与对策 |
|---|---|---|---|
| **B1** | **权益扣减时机 = `TAKEN`（用户确实取得满电电池）之后** | 不在"投入旧电池"时扣。口径一句话可解释：**拿到电池才收钱** | 代价：存在"投了旧电池就走"的白占窗口 → 用**在途预占 + 每用户在途上限 1 笔**（B3）+ 站点级并发水位堵住。收益：`ABORTED` 路径天然无需退款，资金纠纷面最小 |
| **B2** | **`SUSPENDED` 允许用户自助恢复 1 次，但它不推进状态，只触发反查** | "我关好门了"按钮 → 下发 `QUERY_STATUS` 反查 → **以读数决定迁移**；第 2 次失败强制转人工 | 依据 §3 可信度阶梯：**用户声明是优先级最低的来源**，不得作为推进依据。省工单但不放权 |
| **B3** | **同一用户同一时刻至多 1 笔在途订单（硬约束）** | A 柜未完成时扫 B 柜 → 直接提示，不建单 | 明确**不做**"一人持有多多电池"（真实业务存在但牵动 I1/I2 与整套对账）→ 登记为已知边界 §10 |

**物理世界没有 rollback**：换电是不可逆物理动作序列，出错后只能**向前补偿**。所以本文的核心不是"怎么走到 `COMPLETED`"，
而是"**每个中间态停住时，谁在吃亏、亏的是电池还是钱，以及由谁在多久之内把它收敛掉**"。

---

## 2. 四套状态机及其所有权（架构性决定）

| 状态机 | 载体 | 粒度职责 | 谁有权改它 | 定义处 |
|---|---|---|---|---|
| **订单态** | `swap_order.status` | 业务与资金语义（面向用户/客服/财务口径） | 仅"步骤完成器"与"裁决器" | §4.1 |
| **步骤态** | `swap_order_step.state` | 物理动作的精确位置（面向设备与排障） | 仅"物理事件匹配器" | §4.2 |
| **指令态** | `iot_command.status` | 通信可靠性（可复用，属 `framework/iot`） | 指令分发器 + 应答处理器 | `swap-protocol.md` §4.2 |
| **资产态** | `swap_slot.status` / `swap_battery.status` | 仓位与电池的可用性与归属 | 资产服务（由订单/告警/运维驱动） | §4.4 |

**为什么必须拆**：钱的状态和门的状态**天然会不一致**（钱扣了门没关、门开了订单失败）——这不是缺陷，是弱网物理系统的常态。
若塞进同一个字段，就只能表达"一致的那些情况"，而恰恰需要处理的正是"不一致的那些"。
拆开后"不一致"变成一个**可检测、可命名、可补偿**的差异记录（§7 `swap_discrepancy`），而不是一团说不清的状态。

**拆开的直接收益举例**：FI-12（弹仓未取且关门）与 FI-13（取走电池门未关）在**订单层看起来完全一样**（都停在 `OFFERING`），
只有步骤层能区分它们——而这两条的资金结论相反（一个不扣、一个必扣）。这就是拆分的价值所在。

**分层推进规则（硬约束）**：
1. **指令态变更永不直接改订单态**（协议 §7.1）。
2. **物理事件推进步骤态**；步骤全部完成后由"步骤完成器"推进订单态。
3. 订单层的 `UNCONFIRMED` 是**步骤层 `CONFIRM_PENDING` 的投影**（存在 step 处于 `CONFIRM_PENDING` 且反查已耗尽），
   **不在两处独立推进**——否则会出现订单和步骤都说自己"在等"的死锁式不一致。

---

## 3. 事件来源与可信度阶梯

同一事实可能多源，冲突时按此优先级裁决（**高优先级可推翻低优先级，反之不可**）：

```
① 物理事件  door_open / door_close / battery_detected / battery_taken / slot_occupied
② 主动反查  QUERY_STATUS 读数（需连续两次一致才算"事实"）
③ 指令应答  cmd_reply
④ 用户声明  "我已关门" / "我已取走"
⑤ 超时推断  deadline 到期
```

> **用户声明不推进状态。** 听着不近人情，但它是唯一能让账实相符的立场：否则投诉与纠纷永远没有裁决依据。
> `⑤超时推断` 只能得出"**未知**"（`CONFIRM_PENDING` / `UNCONFIRMED`），**永远不得直接得出"没发生"**——见 §6 FI-01。

**事件幂等键**：`(orderId, eventType, sourceMsgId)` 唯一索引；命中即判重复，只生效一次迁移（协议 §6 第 8 步的云侧对应面）。

---

## 4. 状态定义

### 4.1 订单状态（粗粒度，资金口径）

| 状态 | 语义 | 终态 | 最大停留 | 进入条件 | 停留期间禁止 |
|---|---|---|---|---|---|
| `CREATED` | 已建单，未校验 | 否 | 5s | 前置 guard 通过 | 下发任何指令 |
| `AUTHORIZED` | 权益/押金/黑名单通过，权益已**预占** | 否 | 3s | 预占成功 | 未预占即下发 |
| `RETURNING` | 归还阶段（S1+S2） | 否 | 150s | 已发 `OPEN_SLOT` | 分配第二笔在途单（B3） |
| `RETURNED` | 旧电池已识别 + 归还仓已关（物理事件双确认） | 否 | 5s | S2 完成 | 直接进 OFFERING（须过 S3） |
| `VERIFYING` | 身份/隔离/SOC 核验（S3） | 否 | 25s | 进入 S3 | 弹新仓 |
| `OFFERING` | 取电阶段（S4+S5） | 否 | 180s | S3 通过 | 扣减权益 |
| `TAKEN` | 满电电池已取出 + 取电仓已关 | 否 | 3s | S5 完成 | **此刻起允许扣减** |
| `SETTLING` | 扣权益 + 计费流水 + 台账变更（S6） | 否 | 30s（可重试） | 进入 S6 | 重复扣减（幂等键=orderId） |
| `COMPLETED` | 正常闭环 | **是** | — | S6 完成 | — |
| `SUSPENDED` | 物理中断，等用户自助/运维/超时裁决 | 否 | 600s | 见矩阵 | 分配该仓位 |
| `UNCONFIRMED` | 通信层与事实层分歧且反查耗尽（§2 投影） | 否 | 900s | 见矩阵 | 任何自动资金动作 |
| `ABORTING` | 补偿集执行中 | 否 | 60s | 决定中止 | 落 `ABORTED`（I8） |
| `REJECTED` | **从未发出任何物理动作**即被拒 | **是** | — | guard 失败 / 设备明确 `EXPIRED`/`E2*` 未执行 | 自动开工单 |
| `ABORTED` | 已发出物理动作后中止，**补偿集已完成** | **是** | — | I8 满足 | — |
| `FAILED_MANUAL` | 需人工核资后方能落终 | **是**（人工落） | — | 自动裁决不可达 | 自动退款 |

**`REJECTED` 与 `FAILED_MANUAL` 必须分开**（普通项目最常糊掉的一条）：

| 维度 | `REJECTED` | `FAILED_MANUAL` |
|---|---|---|
| 是否发生过物理动作 | 否 | 是（或不确定） |
| 退款 | **自动全额退**，无需人工 | **禁止自动退**，人工核资后回写形成资金闭环审计 |
| 权益 | 释放预占，不计次 | 冻结预占，人工裁决扣/退/部分扣 |
| 工单 | 不留（计入拒绝率指标） | **强制工单** + 资产盘点 |
| 资产 | 未变动 | 必须逐项确认电池归属 |

### 4.2 步骤定义（细粒度，物理位置）

| 步骤 | 期望指令 | 期望物理事件（权威） | deadline | 失败策略 |
|---|---|---|---|---|
| **S1** `OPEN_RETURN` | `OPEN_SLOT(returnSlot)` | `door_open(returnSlot, trigger=CMD)` | 15s | 反查→未开则重发 1 次；已开则继续（FI-01） |
| **S2** `WAIT_INSERT` | — | `battery_detected(returnSlot)` **且** `door_close(returnSlot)` | 90s+60s 宽限 | 提示→`SUSPENDED`（FI-04/FI-11） |
| **S3** `VERIFY_RETURN` | `BATTERY_VERIFY(returnSlot)`（可选反查） | `battery_detected` 载荷核验 | 云内 5s / 指令 20s | `E4*` → 拒收分支（FI-双分岔，§6.9） |
| **S4** `UNLOCK_OFFER` | `UNLOCK_SLOT(offerSlot)` | `door_open(offerSlot)` | 15s | 换另一仓重分配（幂等安全，未开即无副作用） |
| **S5** `WAIT_TAKE` | — | `battery_taken(offerSlot)` **且** `door_close(offerSlot)` | 60s | 未取→`ABORTING`；取了未关→继续（FI-12/13） |
| **S6** `SETTLE` | — | 无设备动作（云内事务 + Outbox） | 30s | 重试，**不允许丢**（I3） |

步骤态：`PENDING → DISPATCHED → OPEN_CONFIRMED → PHYSICS_DONE → VERIFIED / FAILED / CONFIRM_PENDING`。
步骤是**唯一被物理事件直接推进的对象**；订单态由"哪几个步骤完成"推导。

### 4.3 指令态

见 `swap-protocol.md` §4.2（`CREATED/DISPATCHED/ACKED/CONFIRMED/NACKED/TIMEOUT/EXPIRED/SUPERSEDED/UNCONFIRMED`）。
**本文不重复定义**，只规定它与步骤态的映射：`cmd.CONFIRMED → step.PHYSICS_DONE`（不是 `→ order` 的任何迁移）。

### 4.4 资产状态（与订单交叉约束）

**仓位** `swap_slot.status`：
`IDLE_EMPTY`（空仓可用）｜`IDLE_CHARGING`（有电池充电中，非本人预占不可用）｜`RESERVED_ORDER`（被在途订单预占）｜
`OPEN_IN_USE`（门开中/动作进行）｜`PENDING_PICKUP`（内有他人电池，待取回，**不得分配**）｜`FAULT`（故障锁定）｜`DISABLED`（运维/策略禁用）｜`ISOLATED`（安全隔离，如热失控风险）

**电池** `swap_battery.status`：
`IN_STOCK`（在库可分配）｜`IN_CABINET_CHARGING`｜`HELD_BY_USER`（在用户手上，含在途）｜`PENDING_PICKUP`（**待取回：属于该用户、被平台暂存于某仓**）｜
`ISOLATED`（隔离：SOH 过低/故障码/被拒收）｜`MAINTENANCE`｜`LOST`（围栏外/长期无上报）｜`SCRAPPED`

> **`PENDING_PICKUP` 是本状态机里必须存在、又最容易被省掉的资产态**（FI-11 的落点）。
> 它是**责任边界**：用户的旧电池被平台暂存，绝不能被静默放回 `IN_STOCK` 而被下一个骑手取走——那等于平台吞了用户的电池。
> 进入必须留痕（谁、哪块、哪个仓、原因、订单号），退出必须经人工或用户自助取回并核销。

---

## 5. 完整迁移表

记法：`(from, event, guard, action, to)`。**未列出的组合即非法迁移 → 抛 `IllegalStateTransitionException` 并告警，严禁静默忽略**
（静默忽略 = "状态不对但没人知道"的根源）。

### 5.1 正常主线

```
 1  (CREATED,    guard_pass,          额度/黑名单/在线/温度/门磁/仓可用 全通过,  reserve_right + pick_slots + create_steps,  AUTHORIZED)
 2  (AUTHORIZED, dispatch_S1,         已选 returnSlot,                        dispatch(OPEN_SLOT) + reserve_slot,           RETURNING)
 3  (RETURNING,  evt:door_open@ret,   cmd.sessionId 一致 且 trigger=CMD,       S1=PHYSICS_DONE,                               RETURNING)
 4  (RETURNING,  evt:battery_detected@ret,  seq 单调通过,                      S2.inserted=true,                              RETURNING)
 5  (RETURNING,  evt:door_close@ret,  inserted==true,                         S2=PHYSICS_DONE → 订单推进,                    RETURNED)
 6  (RETURNED,   enter_S3,            —,                                       verify_identity,                                VERIFYING)
 7  (VERIFYING,  verify_ok,           非隔离/非拒收/SOC≥下限/本网/归属可核验,   旧电池=IN_CABINET_CHARGING(或ISOLATED),          OFFERING)
 8  (OFFERING,   dispatch_S4,         offerSlot 已选且未被他人占,               dispatch(UNLOCK_SLOT) + reserve_slot,           OFFERING)
 9  (OFFERING,   evt:door_open@off,   sessionId 一致,                          S4=PHYSICS_DONE,                                OFFERING)
10  (OFFERING,   evt:battery_taken@off, 电池码==分配码,                         S5.taken=true + 电池=HELD_BY_USER,               OFFERING)
11  (OFFERING,   evt:door_close@off,  taken==true,                             S5=PHYSICS_DONE → 订单推进,                     TAKEN)
12  (TAKEN,      enter_S6,            —,                                       开事务：扣权益+计费流水+台账+事件（I3 同事务）,   SETTLING)
13  (SETTLING,   settle_done,         幂等键 orderId 未使用过,                 写 swap_result 对账锚点,                         COMPLETED)
```

### 5.2 前置拒绝（零物理动作 → `REJECTED`）

```
14  (CREATED,    guard_fail,          柜机离线 / 温度超阈 / 门磁故障 / 无满足 SOC 的满电仓 / 无空归还仓 / 用户有在途单(B3) / 权益不足,
                                       建单前拒绝，不落任何指令,                                                              REJECTED)
15  (AUTHORIZED, cmd:NACK(E2*),       设备明确"未执行"且无任何副作用,           release_right + release_slot,                   REJECTED)
16  (RETURNING,  cmd:EXPIRED,         指令从未被设备接受(ttl 已过),             release_slot(仅该仓) + 允许重下单,               REJECTED)
```

### 5.3 超时与不可断定（核心分支）

```
17  (RETURNING,  deadline_S1,         反查=门已开,                             按事实继续（视为 S3 已发生）,                    RETURNING)
18  (RETURNING,  deadline_S1,         反查=门未开,                             重发 OPEN_SLOT（仅此情形允许，因无副作用）,        RETURNING)
19  (RETURNING,  deadline_S1,         反查也无结果(连续 2 次不一致),            step=CONFIRM_PENDING,                            UNCONFIRMED)
20  (RETURNING,  deadline_insert(150s), 未收到 battery_detected,               锁 returnSlot + notify,                          SUSPENDED)
21  (RETURNING,  deadline_close(90s), battery_detected 已到（电池在柜内）,      锁仓 + 声光 + 工单预告,                          SUSPENDED)
22  (SUSPENDED,  user_declare_closed(B2), 反查=已关且电池在,                    走 5 的后续,                                    RETURNED)
23  (SUSPENDED,  user_declare_closed(B2), 反查=仍未关 或 已第 2 次声明,         强制人工：lock_slot + create_work_order,        SUSPENDED→(人工)
24  (SUSPENDED,  deadline_susp(600s), 无人为动作,                              battery 进入 PENDING_PICKUP + 订单 ABORTING,     ABORTING)
25  (UNCONFIRMED, query_consistent×2, 两次读数一致,                             按读数走对应正常/异常迁移,                      (视读数)
26  (UNCONFIRMED, deadline_unc(900s), —,                                       freeze_right + create_work_order + 禁自动资金,   FAILED_MANUAL)
```

### 5.4 取电阶段异常

```
27  (OFFERING,   deadline_S4,         反查=未开,                               换 offerSlot 重新分配（S4 无副作用可重试）,       OFFERING)
28  (OFFERING,   deadline_take(60s),  battery_detected 显示电池仍在仓,          C4 电池回池 + C1 释放预占,                        ABORTING
29  (OFFERING,   evt:battery_taken@off + deadline_close,  **已取走但门未关**,    电池归用户 → 走 11→12→13，订单不受门影响,        TAKEN
30  (OFFERING,   evt:slot_empty@off 但无 battery_taken,   电池消失（被他人取/传感器异常）, C10 差异记录 + 锁仓 + 工单,            UNCONFIRMED
31  (ABORTING,   abort_done,          补偿集全部 DONE（I8）,                    不扣权益，释放预占,                              ABORTED)
```

### 5.5 拒收（FI-双分岔，§6.9）与结算异常

```
32  (VERIFYING,  verify_fail(E4002/E4001/E4003),  S5 尚未发生（用户手上无电池）, C5 待取回登记 + 锁仓 + 工单 + 释放新仓预占,       ABORTING)
33  (OFFERING,   late_verify_fail,    已过 S3 但后置核验失败（用户手上已有电池）, 允许完成扣减 + 旧电池 ISOLATED + 工单 + 差异记录, SETTLING)
34  (SETTLING,   settle_fail,         DB/Outbox 异常,                          重试（同幂等键），**不得回退 TAKEN 以外状态**,     SETTLING
35  (SETTLING,   settle_retry_exhausted, 重试耗尽,                             C10 差异记录 + 告警 + 保持 SETTLING 可续跑,        SETTLING(挂账)
```

### 5.6 安全联动与管理员干预

```
36  (任意非终态, alarm(SMOKE|TEMP_HIGH|WATER|LEAK), level≥CRITICAL,            C3 锁全柜 + dispatch(EMERGENCY_STOP) + C12 升级告警,
                                       并按"用户手上有没有电池"分岔（见 §6.8）,  → ABORTING 或 允许走完 TAKEN
37  (任意非终态, admin_abort,         仅 swap:order:intervene 权限 + 审计留痕,   C13 冻结 → 人工裁决,                             FAILED_MANUAL
38  (FAILED_MANUAL, admin_resolve,    人工核资（扣/退/部分扣）+ 双人复核,         回写资金流水 + 资产核销,                           COMPLETED|ABORTED
39  (任意非终态, restart_recover,     兜底扫描发现 deadline 已过,               从最后一条事实事件重建现场,                        (按重建结果)
```

---

## 6. 异常矩阵（本文主体）

每条给出：**系统反应 / 资产处置 / 资金与权益 / 终态 / 人工 / 资损方向 / 断言测试**。
`资损方向`列是关键——它决定"该自动裁决还是必须人工"：**凡资损方向不明确或指向平台的，一律不允许自动裁决。**

| # | 触发 | 系统反应 | 资产处置 | 资金/权益 | 终态 | 人工 | 资损方 | 断言 |
|---|---|---|---|---|---|---|---|---|
| **FI-01** | S1 无任何应答 | `TIMEOUT`→反查。**超时 ≠ 未发生**（抖的是应答不是指令）；反查显示已开→继续；确认未开→才允许重发 1 次；反查无果→`UNCONFIRMED` | 视反查 | 不动 | 视反查 | 反查耗尽时 | 无 | `Fi01AckLostTest` |
| **FI-02** | 同 msgId 应答×3（随机延迟） | 去重命中，仅 1 次迁移（`fromState` 谓词 CAS） | 无 | 无 | 正常 | 否 | 无 | `Fi02DuplicateAckTest` |
| **FI-03** | 应答带 `E2/E3/E4` 各类 | 按 `swap-protocol.md` §8 分流：`E2*`→换仓/重分配；`E3*`→锁仓+工单+不得让用户重试解决；`E4*`→拒收双分岔 | 按族 | 按 B1 | 分流 | `E3*` 必 | `E3*`:平台 | `Fi03NackRoutingTest` |
| **FI-04** | ACK 成功但 `door_close` 永不到 | `CONFIRM_PENDING`→反查→`SUSPENDED` | **锁该仓**禁分配 | 预占不结算 | `SUSPENDED`→`ABORTING`/人工 | 是 | 平台（仓位被占） | `Fi04FactMissingTest` |
| **FI-05** | 事件乱序（close 早于 open） | `seq` 缺口记录（不静默）；迁移靠 `fromState` 谓词，不因顺序倒挂而错；乱序窗口内可容忍 | 无 | 无 | 正常 | 否 | 无 | `Fi05OutOfOrderTest` |
| **FI-06** | 离线 30min 后上线收到过期指令 | 设备侧 `E1003 EXPIRED` 拒绝；云侧指令终态 `EXPIRED`，**不重试不改写** | 无 | 释放预占 | `REJECTED` | 否 | 无 | `Fi06ExpiredCmdTest` |
| **FI-07** | 重放同 sign+nonce | `E1002 REPLAY` + 安全审计告警 + 风控计数 | 无 | 无 | `REJECTED` | 安全侧 | 无 | `Fi07ReplayTest` |
| **FI-08** | 错 secret / 篡改 data | `E1001 SIGN` + 审计 + 设备级异常签名率指标告警（连续即锁设备连接） | 无 | 无 | `REJECTED` | 安全侧 | 无 | `Fi08BadSignTest` |
| **FI-09** | 门磁 1s 内抖动 ×5 | 同 `(slot,type)` 1s 窗口合并为 1 条；抖动超阈转 `door_fault`；**`battery_taken` 不参与合并**（防吞真实取电） | 无 | 无 | 正常/`FAULT` | 转故障时 | 无 | `Fi09DebounceTest` |
| **FI-10** | 重连 + 旧会话应答迟到 | `sessionId` 不匹配→丢弃 + `iot.session.stale` 计数；订单不推进 | 无 | 无 | 不推进 | 否 | 无 | `Fi10StaleSessionTest` |
| **FI-11** | **投了旧电池就走** | 提示→`SUSPENDED`（迁移 21）→600s 无人为→迁移 24 | 锁仓 + 旧电池 **`PENDING_PICKUP`（责任边界，严禁回池）** | **不扣**（B1） | `ABORTED` | 是 | 平台（仓位占用） | `Fi11AbandonedBatteryTest` |
| **FI-12** | 弹仓未取且关门 | 迁移 28 | 新电池回池（**须 `battery_detected` 佐证仍在**，无佐证→`UNCONFIRMED`） | 不扣 | `ABORTED` | 否 | 无 | `Fi12NotTakenTest` |
| **FI-13** | **取走电池门未关** | 迁移 29：**订单照常完成**；门未关走独立告警 + 远程处置（`BUZZER`/声光/工单） | 电池归用户 | **正常扣减** | `COMPLETED` | 柜侧 | 无（用户担门责） | `Fi13TakenDoorOpenTest` |
| **FI-14** | **换电中途烟感/过温** | 迁移 36：`EMERGENCY_STOP` + 锁全柜 + 告警升级 + 强制工单。**按用户手上有没有电池分岔，不按订单状态一刀切** | 已投入的留仓并登记；已取出的确认归用户 | 手上**无**电池→不扣；**有**→扣（用了就要付） | `ABORTED`/`COMPLETED` | **强制** | 安全优先 | `Fi14SafetyAbortTest` |
| **FI-15** | 上报不合物模型 | 入 `raw_payload` + 校验失败记录；**主链路不阻塞**；`telemetry.reject` 计数 | 无 | 无 | 正常 | 否 | 无 | `Fi15BadTelemetryTest` |
| **FI-16** | 等应答时服务端重启 | 兜底扫描（ShedLock）→ 从最后一条事实事件重建 → 反查收敛；**无永久悬挂订单** | 按重建 | 按重建 | 正常/`FAILED_MANUAL` | 视重建 | 无 | `Fi16CrashRecoveryIT` |
| **X-01** | 反查两次读数不一致 | `UNCONFIRMED` 挂起 + `freeze_right` + 工单（迁移 26） | 锁相关仓 | 冻结 | `FAILED_MANUAL` | 是 | 不明→人工 | `InconsistentQueryTest` |
| **X-02** | 柜侧 `swap_result` 与云端事实不一致 | 落 `swap_discrepancy`，**绝不因设备说成功就改状态**；日终对账驱动人工核资 | 按差异 | 按差异 | 保持/`FAILED_MANUAL` | 是 | 不明→人工 | `SwapResultMismatchTest` |
| **X-03** | S4 换仓重分配反复失败（>3 仓） | 停止分配 + 提示"暂无可用电瓶" + 释放预占 | 不动 | 不扣 | `REJECTED` | 否 | 无 | `NoOfferBatteryTest` |
| **X-04** | 用户 A 柜未完成又扫 B 柜 | guard 直接拒绝建单（B3） | 不动 | 不动 | 不建单 | 否 | 无 | `SingleInFlightTest` |
| **X-05** | 补偿集中某一动作失败 | **不得进 `ABORTED`**（I8）；停在 `ABORTING` + 退避重试 + 积压告警 | 部分完成 | 不动 | `ABORTING`→… | 超阈值时 | 平台 | `CompensationIncompleteTest` |
| **X-06** | 仓位被他人运维开门（无归属事件） | 落 `unmatched_event` + 告警，**不静默丢弃**（可能是运维、可能是攻击） | 不动 | 不动 | 不推进 | 是 | 待查 | `UnmatchedEventTest` |

### 6.9 拒收双分岔（必须两条都定义，否则线上就是"电池不知去哪了"）

`battery_detected` 判定旧电池属异常（隔离名单 / 非本网 / 绑在他人名下 / SOC 异常）时，**结论取决于它发生在哪一步**：

| 分岔 | 位置 | 处理 | 资金 |
|---|---|---|---|
| **R-A** | S3，用户手上**还没有**新电池 | 拒收：C5 待取回登记 + 锁仓 + 释放新仓预占 + 工单让人把这块电池取出去 | **不扣**，订单 `ABORTED` |
| **R-B** | S3 之后（后置核验/延迟上报），用户手上**已有**新电池 | **不允许撤销已发生的物理事实**：允许本单完成并扣减；旧电池标 `ISOLATED` + 差异记录 + 工单 + 对该用户加核验标记 | **照常扣**（B1 口径：拿到就付） |

---

## 7. 表与实体清单（明细 DDL 属 M0-3，此处定职责与关键约束）

| 表 | 职责 | 关键约束 |
|---|---|---|
| `swap_order` | 订单态 + 快照（用户/柜/两仓/两电池/套餐） | `(status, deadline)` 索引；`active_user_id` **生成列唯一**（B3） |
| `swap_order_step` | 步骤态、期望事件、sessionId | `(order_id, step_no)` 唯一 |
| `swap_order_event` | **append-only 事实源**（I5），可重放出状态 | `(order_id, seq)` 唯一；**无 version、无 del_flag、不加外键**（同 `barrier_event` 理由：父变更后必须存活） |
| `swap_event_dedup` | 上行去重（I6） | `(order_id, event_type, source_msg_id)` 唯一 |
| `swap_compensation` | 补偿集台账（I8） | `(order_id, action, target)` 唯一 + `state` |
| `swap_discrepancy` | 账实差异（对账/人工核资输入） | `(order_id, kind)` + 处理态 |
| `swap_cabinet` / `swap_slot` | 柜机 / 仓位台账与健康 | `slot` 上 `active_order` **生成列唯一**（I2） |
| `swap_battery` | 电池档案 + 资产态 + 归属 | `holder_active` **生成列唯一**（I1）；循环次数、SOH、入库/激活/隔离史 |
| `swap_right_occupation` | 权益预占/扣减/释放 | `(user_id)` 在途唯一；`(order_id, kind)` 幂等唯一 |
| `iot_*`（框架层） | device/product/thing_model/session/command/shadow/outbox/telemetry 端口 | 见 `swap-protocol.md` §11 |

> **MySQL 无部分唯一索引**，"某状态下唯一"用生成列技巧落 DB 约束：
> `active_order_id VARCHAR(64) GENERATED ALWAYS AS (IF(state='ACTIVE', slot_id, NULL)) STORED` + `UNIQUE(active_order_id)`。
> **不变式必须落在数据库约束上，不能只落在应用代码里**——代码会漏、会并发、会被绕过，约束不会。

---

## 8. 不变式：实现机制 + 测试（每条都必须能被测出来）

| # | 不变式 | 实现机制 | 测试 |
|---|---|---|---|
| **I1** | 一块电池至多属于一个归属（仓位 或 某人订单） | `swap_battery.holder_active` 生成列唯一索引 + 变更走 CAS | `BatteryOwnershipInvariantTest` |
| **I2** | 一个仓位同一时刻至多被一笔在途订单预占 | `swap_slot.active_order` 生成列唯一 + 分配用 `fromState` 谓词 CAS | `SlotReservationInvariantTest` |
| **I3** | 权益扣减 ⇔ 电池所有权变更 | **同一 `@Transactional`**（CAS 失败整事务回滚，不产生孤儿事件）；跨进程部分走 Outbox | `DeductionOwnershipAtomicTest`（含 CAS 冲突回滚断言） |
| **I4** | 非终态必有生效 deadline | 状态变更方法**强制传 deadline**，`deadline IS NULL AND status NOT IN (终态)` 启动即断言 | `DeadlinePresenceTest` |
| **I5** | 事件流可重放出当前状态 | 迁移前先 append 事件再改状态；重放器与投影比对 | `EventStreamReplayTest`（随机事件序列 + 固定样本） |
| **I6** | 重复投递不产生第二次迁移 | `swap_event_dedup` 唯一索引 + 迁移带 `fromState` 谓词 | `IdempotentTransitionTest`（并发 32 打同一事件） |
| **I7** | 在册电池 = 仓内 + 用户持有 + 隔离/维修/丢失 | 日终对账 Job（ShedLock + `@IgnoreTenant`），不等即告警 + 生成差异 | `AssetLedgerIdentityTest` |
| **I8** | 进 `ABORTED` 前补偿集必须全部完成 | `swap_compensation` 台账 + `ABORTING→ABORTED` 迁移 guard 查台账 | `CompensationIncompleteTest` |

---

## 9. 并发、锁与超时驱动（复用 barrier 已验证的范式）

| 层 | 机制 | 作用域 | 说明 |
|---|---|---|---|
| ① 进程内 | **按柜机分段锁**（`ConcurrentMap<cabinetId, ReentrantLock>`） | 同一柜机的仓位分配/动作串行；异柜并行 | 直接沿用 `BarrierEngine.lockFor` 的思路，粒度从"杆"换成"柜" |
| ② 跨实例（调度） | ShedLock（`@SchedulerLock`，`usingDbTime` 抗时钟漂移） | 兜底扫描、对账、超时裁决在集群内单例 | 框架已有 `ShedLockConfig` |
| ③ 跨实例（写） | `@Version` 乐观锁 CAS + DB 唯一约束兜底 | 状态与资产归属的并发写 | `BaseEntity.version` 已有 |
| ④ 业务级 | DB 唯一约束（§7 生成列） | **即使 ①②③ 全被绕过也兜住** | 仓位/归属/去重三类不变式的最终防线 |

**为什么需要 ④**：①②③ 是"减少冲突"，④ 是"冲突了也不会错"。只有前三层的设计在压测下迟早出问题（尤其多实例 + 运维端与 C 端同时操作同一仓）。

**超时驱动双保险**（协议 §7.4）：内存延迟任务走快路径 + `deadline < now` 窄集合扫描走兜底；
**两条路径同时命中同一订单时，迁移必须幂等**（靠 I6 与 `fromState` 谓词）。

**冲突处理分场景**（沿用 barrier 的结论）：
- 调度/补偿路径遇 CAS 冲突 → **本次让步**，下轮扫描重读事实自愈（幂等）。
- 用户同步请求路径（下单、取电确认）遇冲突 → **有界重试 3 次**，耗尽则映射 `ResultCode.CONFLICT`（HTTP 200 + `code:409`，"柜机状态正被其他操作修改，请稍后重试"），不外泄内部异常。

---

## 10. 实现形态与明确不做

### 10.1 实现形态（含一个我已定死的取舍）

- 通用骨架回流 **`framework/statemachine`**：状态/事件/迁移三元组、guard 与 action 注册、非法迁移异常、迁移留痕钩子、deadline 约定。**业务无关**。
- 各业务（换电订单 / 告警 / 工单）在**代码里**写自己的迁移表。
- **不做可视化/可配置状态机**（不做拖拽改迁移）。理由：换电状态机是**业务不变式**，
  一个能把"扣钱"从 `TAKEN` 后拖到 `AUTHORIZED` 时的后台配置界面，就是生产事故发生器。
  迁移表变更必须走代码评审 + 测试 + Flyway 版本化菜单/权限，与 schema 演进同等严肃。
- `framework/iot` 侧**禁止**出现 slot/battery/order 语义（协议 §开篇包边界纪律）。

### 10.2 明确不做（已知边界，非"用不上"）

| 项 | 不做的技术原因 | 影响 |
|---|---|---|
| 一人持有多块电池 | 会摧毁 I1/I2 与整套对账恒等式（归属从 1:1 变 1:N），牵动权益、押金、丢失追责全链路 | 登记；B3 硬约束下不会出现该形态 |
| 跨柜续作（A 柜故障引导到 B 柜接着上次步骤走） | 需要"物理动作断点"可迁移语义，而断点两侧的资产归属不可信 | 用户在新柜重下单（旧单走 `ABORTED`/`PENDING_PICKUP`） |
| 离线自治换电（柜机断网时本地放行） | 物理侧凭据来源不可验证 | 协议 §4.3 默认禁止，站点/租户级开关留口 |
| 预约满电电池（提前锁一块） | 引入 `RESERVED` 态与占用计费，与分配策略需一起重设计 | M6+ 评估 |
| 押金扣抵（异常吞电池后的押金处置） | 属资金域规则，须与 M4 退款/对账一起定 | M4 |
| 人工核资双人复核的完整审批流 | 属 ops 域工单/权限联动 | M5；本状态机已预留 `admin_resolve` 迁移与审计 |

---

## 11. 验证计划（M2/M3 的门槛，此处先定口径）

| 层 | 内容 | 门槛 |
|---|---|---|
| 单元（脱库） | 迁移表全遍历：**所有未列出的 (state,event) 组合必须抛异常**；每条合法迁移至少 1 例；deadline 单调不倒退 | 100% 分支遍历 |
| 性质测试 | 随机事件序列 → 从空重放 == 当前投影（I5）；同一事件重复 N 次 == 1 次（I6） | 每性质 ≥200 随机序列 |
| 集成（真 Broker） | FI-01..FI-16 全量：断言**状态 + 资产 + 权益 + 工单**四方面同时正确 | 16/16 绿 |
| 并发（真 InnoDB） | 32 并发抢同一仓位仅 1 成功；同事件并发仅 1 次迁移；补偿与下单交叉 | 不变式零违反 |
| 混沌 | 等应答时 kill 实例、重启、断 Redis、Broker 重启、模拟时钟偏移 | 无永久悬挂订单 |
| E2E（H5 C 端） | 浏览器完整换电主链路 + 至少 2 条异常路径（含"我关好门了"自助恢复） | 全绿 |
| 压测 | 柜机并发 × 换电并发（含随机故障率） | 正常网络下换电成功率 ≥99.5%；**异常注入下不变式违反数必须为 0** |

> 压测的第二条断言才是这个项目真正的自证点：**压力下不是看吞吐，而是看有没有出现"钱扣了电池没给"这类不变式破口。**

---

## 12. 遗留决策点（进 M0-3 / M1 前须定）

1. **满电仓分配策略的可插拔维度与权重**：SOC 阈值、SOH、循环次数、充电器温度、最近充满时间、该电池被同一用户连续分配的次数（防"总拿到差电池"）。
   → 这是纯函数，完全适用 barrier 的"决策纯函数 + 脱库单测"范式；但维度优先级要你参与定（涉及体验口径）。
2. **归还仓选择策略**：优先空仓 vs 优先低电仓（允许把已充到 80% 的仓让出来）。
3. **`UNCONFIRMED` / `SUSPENDED` 的 SLA 数值**是否需要按站点/租户配置（而非全局常量）。
4. **C 端在"未知态"的表达**（协议 §15.6）：不能显示为失败，需第三态文案与后续动作引导——这条影响接口字段设计。

---

## 13. 变更日志

- 2026-09-29：初版定稿（M0-2）。确立四套状态机分层与所有权（订单/步骤/指令/资产，指令态变更不直接改订单态）、
  事件可信度五阶阶梯（用户声明不推进状态、超时只能得出"未知"不能得出"没发生"）、
  15 个订单态含 `REJECTED`/`ABORTED`/`FAILED_MANUAL` 三种终态的职责分离、S1–S6 步骤定义、
  39 条完整迁移、**22 行异常矩阵 + 拒收双分岔**（含 FI-01"超时≠未发生"、FI-11 待取回责任边界、FI-13"钱按成功、物按异常"、FI-14 按手上有没有电池分岔）、
  8 条不变式各自的 DB 约束与对应测试、并发四层（分段锁+ShedLock+CAS+**DB 唯一约束为最终防线**）、超时双保险幂等、
  实现形态（`framework/statemachine` 骨架 + 代码内迁移表，**不做可配置状态机**）、6 项明确不做及理由、7 层验证门槛。
  用户拍板记录：B1 扣减在 `TAKEN` 之后、B2 自助恢复 1 次仅触发反查、B3 单用户在途至多 1 笔。
- 相对讨论稿的收紧：`UNCONFIRMED` 明确定义为步骤层 `CONFIRM_PENDING` 的**订单级投影**，避免订单与步骤两处独立推进造成互相等待式不一致。
