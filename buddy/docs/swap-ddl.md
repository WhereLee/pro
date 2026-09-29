# swap · 表结构与 DDL 决策（M0-3 定稿）

> 本文记录 `V7`~`V11` 换电柜 schema 的**决策与兼容约束**，以及哪些决定不可回退、为什么。
> 依据：[`swap-protocol.md`](swap-protocol.md)（协议）、[`swap-order-fsm.md`](swap-order-fsm.md)（状态机与不变式）、[`swap-plan.md`](swap-plan.md)（里程碑）。
> 所有兼容性结论**均为本机 H2 2.2.224（`MODE=MySQL`）与 MySQL 8.0.44 实测得出**，不是文档转述。

---

## 1. 版本切分与顺序理由

| 版本 | 内容 | 表数 | 为什么排在这 |
|---|---|---|---|
| `V7__iot_infrastructure` | 设备接入基座：product / thing_model / device / device_session / command / shadow / message_log / msg_dedup / raw_payload / outbox_event / telemetry | 11 | 业务无关，被后面所有表引用，必须最先 |
| `V8__swap_ledger` | 台账：site / cabinet / slot / slot_reservation / battery / battery_binding / battery_observation / battery_conflict | 8 | 订单要引用仓位与电池 |
| `V9__swap_order_flow` | 主线：order / order_step / order_event / event_dedup / compensation / discrepancy | 6 | 依赖 V8 的资产 ID 语义 |
| `V10__member_right` | C 端：member_user / member_identity / right_plan / right_account / right_transaction | 5 | **必须在 M2 之前**：`AUTHORIZED` 的 guard 就是"权益有效 + 无在途单" |
| `V11__swap_menu_seed` | 菜单与权限码（6000+ 段） | — | 权限码要跟控制器一起提交，先建结构后接页面 |

后续版本已预留：`V12` 告警与工单（M5）、`V13` 支付与对账（M4）、`V14` 分账与提现（M6）。
**V11 刻意不预支 M3~M6 的菜单**：预支会产生"菜单存在但接口不存在"的假可用状态。

---

## 2. 双引擎兼容铁律（违反任一条，构建当场红）

Flyway 一套脚本同时跑 H2（dev/test）与 MySQL 8（prod/真库档），以下为本轮实测出的硬约束：

| # | 规则 | 实测依据 |
|---|---|---|
| C-1 | **禁用 MySQL 的 `IF()` 函数**，条件表达式一律 `CASE WHEN ... THEN ... ELSE ... END` | H2 即使 `MODE=MySQL` 也报语法错误：`expected "DISTINCT, ALL, *, INTERSECTS, ..."` |
| C-2 | 计算列**不写 `STORED`/`VIRTUAL` 关键字** | MySQL 默认 VIRTUAL 且允许在虚拟列上建唯一索引；H2 接受无关键字写法。两边索引均创建成功 |
| C-3 | 不写 `ENGINE=` / `CHARSET=` / 列级 `COMMENT` / 索引 `IF NOT EXISTS` | 沿用 V3/V6 既有约定（H2 不接受列级 COMMENT） |
| C-4 | **MySQL 无部分索引**：`CHECK (col='ACTIVE' ...)` 也表达不了 → 唯一可行形态是"生成列 + 唯一索引" | 见 §3 |
| C-5 | **`CHECK` 不能跨表引用**：所以"站点只能收紧型号门槛"必须把型号默认值**冗余进本表**才能落 DB 约束 | 见 §4.3 与漂移风险登记 |
| C-6 | H2 不支持 `PARTITION BY RANGE` | 分区不进迁移，见 §7 |
| C-7 | `TEXT` 列可有 `NOT NULL` 但不能有 `DEFAULT`；`DATETIME(3)` 两边都支持 | 实测通过 |
| C-8 | 唯一索引允许多个 `NULL`（两边语义一致）——这是 C-4 方案成立的前提 | 实测：4 行非 ACTIVE 同电池历史行并存成功 |

---

## 3. 不变式的 DB 落点（生成列清单，6 处）

```sql
-- 形态统一为：活跃时=业务键，非活跃时=NULL，再对生成列建唯一索引
active_xxx <type> AS (CASE WHEN <state 列> IN (<在途/生效状态集合>) THEN <业务键> ELSE NULL END)
```

| 生成列 | 所在表 | 承载的不变式 | 实测拒绝证据 |
|---|---|---|---|
| `active_battery` | `swap_battery_binding` | I1/I9 一块电池至多一条生效绑定 | 同 `battery_id=201` 两条 `ACTIVE` → `exit=1` |
| `active_user` | `swap_battery_binding` | B3 本期"一人至多一块电池" | 同上机制（同用户两条 ACTIVE 互斥） |
| `active_slot` | `swap_slot_reservation` | I2 一个仓位至多一笔生效预占 | 同 `slot_id=101` 两条 `ACTIVE` → `exit=1` |
| `active_user` | `swap_order` | B3 单用户在途订单至多 1 笔 | 同用户 `RETURNING` + `OFFERING` → `exit=1`；改为 `COMPLETED`+`REJECTED` 后同用户再建单 → `exit=0` |
| `active_device` | `iot_device_session` | 每台设备至多一条 ACTIVE 会话（重连互踢的 DB 防线） | 唯一索引创建成功 |
| `active_step` | `iot_command` | 同一业务步骤至多一条在途指令（重发前必须置 `SUPERSEDED`） | 唯一索引创建成功 |

**最后一列的正向对照是刻意做的**：只证明"会被拒绝"没意义，必须同时证明**终态能释放约束**。
否则很可能上线后才发现"用户有一笔 `SUSPENDED` 订单，从此永远无法新建订单"——那这类生成列就是故障源而非防线。

> **`SUSPENDED` / `UNCONFIRMED` / `ABORTING` 属于在途集合**，这是有意的保守选择：
> 挂起中的订单继续占用用户的唯一名额，宁可让用户等人工处理，也不允许"两笔挂起单交叉操作同一批仓位"。

---

## 4. CHECK 约束（56 条，三类用途）

### 4.1 枚举闭集（状态机在 DB 层的第二道门）
所有 `*_state` / `*_type` / `kind` / `source` 列都带 `CHECK (col IN (...))`。
作用不是防业务代码写错，而是**防"绕过业务代码的写入"**：数据修复脚本、运维直连库、将来新增入口忘了校验——都会被 DB 挡下。

### 4.2 数值与跨列规则
`soc 0..100`、`soh 0..100`、`times_used >= 0 AND times_total >= times_used`、`slot_count 1..64`、
`gateway_row_id <> id`（设备不能是自己的网关）。

### 4.3 `bind_source IN ('ORDER','MANUAL','IMPORT')` —— I10 的枚举级表达
FSM §4.5.4 要求"观测数据不得改归属"。这条规则**在 schema 层就被写进枚举**：不存在 `'OBSERVATION'` 这个取值，
所以任何"观测顺手写一笔绑定"的代码在写库时就失败，而不是靠 code review 拦。

### 4.4 漂移风险登记（C-5 的代价）
`swap_site.product_min_soc` 是 `iot_product.min_soc` 的冗余副本，用于让 CHECK 能表达。
**副本会漂移**：型号默认值改了，站点副本不会自动跟。
处置：M1 提供一个阈值一致性校验（不等即告警 + 生成差异记录），**不假装这个问题不存在**，也不为此放弃 DB 约束。

---

## 5. 不可回退决定（改动会引发连锁返工，动之前先读这段）

| # | 决定 | 理由 | 若被"顺手改掉"的后果 |
|---|---|---|---|
| D-1 | **状态列用 `VARCHAR` + CHECK，不用 TINYINT 数字码** | 状态机是审计对象；`PENDING_PICKUP` 与 `7` 在半夜排障时差别巨大；CHECK 让"写入不存在的状态"直接失败 | 代码不会报错，只是所有历史数据、日志、工单都需要一张对照表才能读 |
| D-2 | **append-only 表不建 `version` / `del_flag`，不加外键**（`swap_order_event`、`iot_message_log`、`iot_raw_payload`、`swap_battery_observation`、`swap_right_transaction`） | ① 事实不能因父记录变更/删除而消失；② 框架全局配置 `logic-delete-field=delFlag` 生效——**一旦这些表建了 `del_flag` 列，MyBatis-Plus 会自动追加 `del_flag=0` 过滤，等于"历史可被隐删"且无人察觉** | I5"事件流可重放出状态"被静默破坏，对账与排障失去依据 |
| D-3 | **不变式落 DB 生成列，不靠应用代码** | 代码会漏、会并发、会被绕过，约束不会 | 见 §3，抢仓/双单类问题在压力下才会出现，届时无法归因 |
| D-4 | **内部 `BIGINT` 主键 + 对外 `*_no VARCHAR(32)` 单号** | 雪花 ID 连续递增，暴露到 C 端与回调即泄露业务量；跨系统对账需要可读可校验的号 | 改主键代价极高，改单号代价低——所以单号一定要有 |
| D-5 | **每张新表从第一天带 `tenant_id` 并实际写入**（与 `buddy.tenant.enabled` 解耦） | 开多租户只是翻开关；否则 M6 开档时要做一次"改表 + 全量回填"的数据迁移工程 | 沿用 V6 的教训：预留列与真正启用之间隔着一次不可逆的数据搬迁 |

D-2 与 D-1 这两条最容易被无意改掉（写了不会立刻报错），已同步写入长期规范记忆。

---

## 6. 投影与真相

| 表.列 | 身份 | 谁能写 |
|---|---|---|
| `swap_battery.holder_user_id`、`current_cabinet_id`、`current_slot_id`、`soc`、`location_state` | **投影缓存**（为查询与 guard 加速） | 仅订单终态迁移与核销流程 |
| `swap_battery_binding`（ACTIVE 行） | 归属**真相** | 同上，且受 §3 唯一约束 |
| `swap_order_event` | 状态**真相**（可重放） | 迁移前置写入，只追不改 |
| `swap_order.order_state` | 投影 | 状态机唯一出口 |
| `swap_right_account.times_used` | 投影（快照） | 与 `swap_right_transaction` 同事务写；恒等式由日终对账校验 |

**规则**：任何"读投影做决策"的路径都必须能被真相重算验证；对账 Job 的职责就是持续证明投影没坏。

---

## 7. 遥测分区：为什么不放进 Flyway

`iot_telemetry` 主键定为 `(id, occurred_at)`——MySQL 要求分区键必须进每个唯一索引，**先把 PK 设计成分区就绪形态**，否则将来要改主键。

但分区本身不在迁移里建，原因是：**分区需要持续滚动（建新分区 + 淘汰过期分区），而 Flyway 迁移是一次性的**。
把一次性动作当成持续机制的起点，是这类设计最常犯的错误——第一个月正常，第二个月插入落到不存在的分区。

方案：M1 交付 `TelemetryPartitionJob`
- 启动时对齐（确保今天 + 未来 N 天分区存在）+ 每日滚动；
- `@SchedulerLock`（集群单例）+ `@IgnoreTenant`（系统级任务）+ `@ConditionalOnProperty`；
- 仅 MySQL 生效（按 `DatabaseMetaData` 判断），H2 下 no-op；
- 淘汰前先把该分区行数与归档水位写入日志与指标，**不静默删数据**。

验证边界（诚实登记）：**H2 档不验证分区**；由真库档断言 `information_schema.PARTITIONS` 中分区存在、命名符合日期、数量等于保留策略。

---

## 8. 索引与扫描路径（每张表都要对上一条真实查询）

| 索引 | 服务的查询 | 若缺失的代价 |
|---|---|---|
| `iot_command (cmd_state, deadline_ts)` | 超时兜底扫描（协议 §7.4） | 每 10s 全表扫，指令量一上来直接拖死库 |
| `swap_order (order_state, deadline_ts)` | deadline 扫描（I4） | 同上 |
| `swap_order_step (step_state, deadline_ts)` | 步骤级超时与反查 | 反查任务堆积 |
| `outbox_event (outbox_state, next_retry_at)` | 投递器 + 退避重试 | 积压不可见 |
| `swap_compensation (comp_state, next_retry_at)` | I8 补偿续跑 | 补偿卡死无人知 |
| `swap_order (cabinet_id, order_state)` | 按柜查在途单（分段锁作用域、安全联动停单） | 紧急场景下查不动 |
| `swap_order_event (order_id, seq_no)` 唯一 | I5 重放 | 重放无序，状态重建不稳定 |
| `iot_telemetry (device_row_id, ts_millis)` | 新鲜度窗口与曲线 | 遥测查询全表扫 |

**要求**：这些扫描 SQL 在真库档必须 `EXPLAIN` 断言 `type <> 'ALL'` 且 `rows` 在量级内——
索引"存在"和索引"被使用"是两件事，只有断言能区分。

---

## 9. 敏感列处理

| 列 | 形态 | 规则 |
|---|---|---|
| `iot_device.secret_cipher` | AES-GCM 密文，主密钥来自环境变量/KMS | 派生 `connSecret` / `msgSecret` 在内存完成，**不落库**；日志与异常栈禁止输出密文与签名 |
| `member_user.phone_cipher` / `phone_hash` | 密文 + `HMAC-SHA256(pepper, plain)` 哈希双列 | 密文供业务读取，哈希供唯一约束与等值查询；不存明文 |
| `member_user.idcard_cipher` / `idcard_hash` | 同上 | 同上 |
| `swap_site.contact_phone_cipher` | 密文 | 同上 |

哈希而非密文做唯一索引，是因为密文带随机 IV 不可比较；这一层区分写在这里，避免后人误改成 `UNIQUE(phone_cipher)`（那会让同一号码能注册两次）。

---

## 10. M0-5 契约测试（把三张纸钉在一起）

实现为 `SwapDdlContractTest`（H2 档运行），三条断言：

1. **枚举三方一致**：迁移脚本中的 `-- @enum 表.列: A|B|C` 注释 ∩ Java `enum` ∩ `swap-order-fsm.md` §4.1/§4.2 声明的状态集合，三者两两相等。
   （`@enum` 注释是"把文档里的枚举变成机器可读"的最小约定，不引入额外 DSL）
2. **在途集合与生成列一致**：FSM 声明的"非终态"集合必须与 `swap_order.active_user` 生成列里的状态列表**逐元素相等**——这条直接防住"漏掉 `SUSPENDED` 导致永久无法建单"或"多算终态导致用户刷单"。
3. **事件与权限码闭环**：FSM 迁移表引用的事件名 ⊆ 协议指令矩阵/事件清单；`@PreAuthorize` 用到的权限码 ⊆ `V11` 种子（后端版），与前端 `permission-contract.spec.ts` 形成双向覆盖。

---

## 11. 本轮实测结论（自证记录）

| 项 | 结果 |
|---|---|
| H2 2.2.224（`MODE=MySQL`）按 V1→V11 顺序全量执行 | `exit=0` |
| MySQL 8.0.44（utf8mb4）同一套脚本全量执行 | `exit=0` |
| 生成列创建数量 | 6 处 `VIRTUAL GENERATED`（与 §3 一致）+ `shedlock.locked_at` 框架自带 |
| CHECK 约束注册数 | 56 |
| 表总数 | 50 |
| 4 条负例（站点放宽门槛 / 双 ACTIVE 绑定 / 双 ACTIVE 预占 / 同用户双在途单 / 非法枚举值） | 全部被拒绝 `exit=1` |
| 1 条正例（终态释放后同用户可再建单） | 通过 `exit=0` |
| `iot_telemetry` 主键含 `occurred_at` | 确认（分区就绪） |

---

## 12. 遗留

1. `M0-4`：C 端会员令牌与鉴权域（独立 audience/密钥、权益快照接口形态）——影响 `member_*` 是否需要额外的令牌/授权表。
2. 押金与授信免押的账户结构属 M4（V13 一起定），V10 刻意不建，避免把未定的资金规则固化成 schema。
3. `iot_thing_model` 的 `spec_json` 结构细化（单位、上下限、可写性、告警映射）在 M1 实现物模型校验器时一并定版。
4. 分区保留天数与归档策略的默认值（当前按 30 天设计），需在 M7 生产配置时按真实写入量级复核。

---

## 13. 变更日志

- 2026-09-29：初版定稿（M0-3）。交付 `V7`~`V11`（30 张新表 + 种子），并记录：双引擎兼容 8 条实测规则
  （**H2 不支持 `IF()`** 是本轮最重要的发现，直接改写全部生成列表达式）、6 处生成列不变式及其**正负双向实测证据**、
  56 条 CHECK 的三类用途与跨表限制的冗余代价、5 条不可回退决定（含"append-only 表若建 `del_flag` 会被 MyBatis-Plus 隐式过滤"这一隐蔽陷阱）、
  投影与真相的边界、分区改由 M1 滚动 Job 承担的理由、索引与扫描路径的 EXPLAIN 断言要求、敏感列密文/哈希双列规则、
  M0-5 契约测试三条断言。
