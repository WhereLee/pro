# swap · 换电柜项目总计划（里程碑、验证门与登记总账）

> 本文是 `biz/swap`（换电柜）开发的**项目内单一计划真相源**：范围、里程碑顺序、双轨同步点、每块验证门、外部硬约束、取舍登记。
> 设计依据：[`swap-protocol.md`](swap-protocol.md)（M0-1 通信契约）、[`swap-order-fsm.md`](swap-order-fsm.md)（M0-2 状态机）、[`swap-simulator.md`](swap-simulator.md)（设备侧工程规范）。
> 实际情况与本文不符时**就地修改本文**并在 [`ROADMAP.md`](ROADMAP.md) 变更日志记一条，不允许"计划外完成"或"沉默降范围"。

---

## 1. 定位

- **基座**：`buddy` 框架（`framework/*`）原样复用其鉴权、RBAC、审计、多租户、Flyway、幂等、可观测、容器化与 CI 能力。
- **业务**：新增 `com.lrs.buddy.biz.swap`（换电柜 SaaS：柜机/仓位/电池台账、换电订单、权益套餐、资金对账、运维告警、多运营商分账）。
- **样例定位**：`biz/barrier`（升降杆）回归为**框架用法示例**，不再是本项目范围；但其已验证的范式（决策纯函数、三层并发、append-only 事件流、端口-适配器）作为换电的实现范式沿用并外扩。
- **设备侧**：新增与 `buddy` 平级的独立 Maven 工程 `buddy-sim`，作为协议的**规范性设备实现**，独立产物、**绝不进生产 jar**。
- **框架升级义务**：换电过程中沉淀的通用能力**必须回流 `framework/*`**（见 §5 回流节点表），不得留在 biz 包内变成一次性代码。

---

## 2. 五条总原则

| # | 原则 | 含义 |
|---|---|---|
| P1 | **双轨互为对手方** | 云侧与 `buddy-sim` 自 M1 起每个里程碑同步演进。异步系统的接口形状只有在真实对手方下才能定对，串行做必然导致接入层返工 |
| P2 | **每块带验证门** | H2 档全绿 + 真库档全绿 + JaCoCo 达标 + CI 全绿 + 该块专属断言；**绿了才进下一块** |
| P3 | **框架能力逐块回流** | 每个里程碑末做一次"可沉淀抽取"，不攒到最后（攒到最后必然沉淀不动，只剩业务代码） |
| P4 | **范围不削减** | 功能与能力项**一项不减**。仅三项属外部硬约束（§6），每项均登记"能做到的最强替代形式" |
| P5 | **能力自证** | 所有结论由本仓的测试/CI/压测/报告产出，不依赖任何外部存量资产背书；做不到的一律写成"外部硬约束"而非"有意留下" |

---

## 3. 里程碑

### M0 · 设计收口（进行中）

| 子块 | 内容 | 状态 |
|---|---|---|
| M0-1 | 设备接入与通信协议规范 | ✅ 定稿 |
| M0-2 | 订单状态机与异常补偿规范 | ✅ 定稿 |
| M0-2b | **电池身份与归属证明规则 + 跨设备可观测性冲突裁决** | 本轮补（FSM §4.5），M0-2 遗留真缺口 |
| M0-3 | 表结构与 DDL：**已交付** `V7__iot_infrastructure`（11 表）/ `V8__swap_ledger`（8 表）/ `V9__swap_order_flow`（6 表）/ `V10__member_right`（5 表）/ `V11__swap_menu_seed`（权限码 6000+ 段），含生成列唯一约束、56 条 CHECK、分区就绪主键与扫描索引 | ✅ 定稿（见 [`swap-ddl.md`](swap-ddl.md)） |
| M0-3b | 派生项（归 M1 实现）：`TelemetryPartitionJob`（分区滚动，仅 MySQL 生效）、阈值冗余漂移校验、`iot_msg_dedup` 过期清理、扫描 SQL 的 `EXPLAIN` 断言 | 随 M1 |
| M0-4 | C 端会员身份域设计 → **已定稿** `buddy/docs/swap-member-auth.md` + `V12__member_auth`（3 表）：两域令牌隔离、独立密钥与 `typ` 分派、第三条过滤链、refresh 轮换与复用检测、实名强制、注销双前置、C 端 `displayState` 契约 | ✅ 定稿 |
| M0-5 | **文档自洽性契约测试** `SwapDdlContractTest`（七项断言，见 `swap-ddl.md` §10） | ✅ 已交付（首跑即抽出一处真实漂移） |

**M0 验证门**：迁移表中每个 `(state, event)` 都能在 ①DDL 枚举 ②协议指令矩阵 ③模拟器故障目录 中找到落点，反向亦然；不一致即测试失败。
> 这条测试的价值：三张纸各写各的一定时会互不认账，而这类偏差在编码期表现为"这个事件没人处理"。

### M1 · 设备接入双轨 —— ✅ 已交付（2026-09-30）

> **Broker 选型更正**：原计划写“Vert.x MQTT / Moquette 两选一”，实测 Moquette 0.17 对 MQTT 5 CONNECT
> 回的 CONNACK 无法被标准 v5 客户端解码，已固定采用 **Vert.x MQTT Server**（详见 ROADMAP §4 与本文 §7 A3）。
> 协议同步修正两处：§6 步序改为“msgId 去重优先于 nonce”；§3.2 不再由 Broker 注入 `brokerTs`，
> 改由签名信封内的 `issuedAt/expireAt` 承载有效期（避免绑到单一 Broker 的属性转发能力）。

- **云侧**：嵌入式 Java Broker（Vert.x MQTT）、信封编解码与 `PayloadCodec` SPI、`sessionId` 会话隔离、在线双源防抖判定、指令分发 + ACK + 超时双保险、上行 10 步校验链与去重、`TelemetryStore` 端口 + MySQL 分区表、版本化物模型校验、`raw_payload` 留存、Transactional Outbox。
- **设备侧 L1**：注册入网取密钥、连接、遥测/事件上报、收指令回 ACK、**完整 §6 校验链**（验签/去重/过期/重放/seq）、最小柜内物理模型（门/锁/电池/SOC 爬升）、`swap_result` 生成；**同步实现 FI-01/02/04/10/13**（验收必需能力，非临时脚手架）。
- **验证门**：golden 样本双端互解；**mosquitto 第三方互操作**（防"双端自洽但不符合标准 MQTT"）；H2 + 真库双绿；JaCoCo；CI 新增 `sim` job 与 `cross-protocol` job。

### M2 · 换电主链路（正常路径 + 并发）—— 进行中（台账与状态机层已交付）

> **已交付（2026-09-30）**：
> ① 设备开通与凭证颁发（`DeviceProvisionService` + `AdminSwapDeviceController`，权限码 `iot:device:provision/rotate`）；
> ② 柜机建账与资产入仓（`SwapLedgerService`，建柜机时硬要求设备已注册且启用，资产双向引用同事务）；
> ③ 分配策略纯函数 `SlotAllocator`（硬门槛不减 + 三档优先级链 + SOC 只做门槛 + 同人连续惩罚降权不剔除）；
> ④ 订单/步骤双状态机代码化（`OrderState` 15 态、`OrderEvent` 41 个**事实粒度**事件、
>    `SwapOrderFsm` 展开全部 §5 迁移、`SwapStepFsm`），并用**穷举测试**验证“未登记组合必抛”；
> ⑤ 契约闭环从“文档↔DDL”扩展为**文档↔DDL↔代码枚举**三方对齐；
> ⑥ **建单主链路与 guard 链**（`SwapOrderService` + `SwapOrderRepository`）：
>    一人一单 = `active_user` 生成列唯一索引；抢仓 = `active_slot` 唯一索引直接 INSERT（不先查）；
>    额度够不够 = 带条件的 `UPDATE ... WHERE times_total - times_used - times_occupied >= 1` 的影响行数；
>    补偿集中在 `reject()` 里按当前事实状态回退（不散在各失败分支）；
>    拒绝也留 REJECTED 行，并可用断言证明它**零物理动作**（无 iot_command / 无预占 / 无权益流水）。
>    实现中补了一处会造成**误拒**的不一致：预占只写 reservation 表而不推 `slot_state`，
>    下一单仍会把那个仓当候选 → 现在预占与仓态同步推进/回滚（两方法必须成对调用）；
>    同理，分配结果必须回写 `swap_order.return_slot_no/offer_slot_no`——
>    只写步骤表与预占表会让订单头上仓号为 NULL，设备事件“到了但被当无关丢弃”（本批真实踩到）。
> ⑦ **事件驱动闭环（B4）**：`SwapFlowService` + `SwapEventListener`。S1 走**真 MQTT**（设备未连上就不推订单，
>    整事务回滚，不留永远发不出的指令）；物理事件推步骤、步骤完成组合推订单；
>    归属变更与扣减同事务（I3）；`swap_event_dedup` 保证重复投递不二次迁移（I6）；
>    取走电池与分配不一致记 `IDENTITY_SUSPECT` 且**不推进**；柜侧 `swap_result` 不一致记
>    `SWAP_RESULT_MISMATCH` 而不改云端结论（对账要能在已终订单上做，所以归属按 orderNo 而非“在途单”）。
>    按测试补上的三处步骤机缺口（把文档里空着的地方变成可执行，不是改口径）：
>    无指令步骤的第一个事实用新事件 `EVT_FACT_ARRIVED`（不能复用 `EVT_DOOR_OPEN`）；
>    核验型步骤 S3 允许 DISPATCHED→VERIFIED 直达；云内步骤 S6 允许 PENDING→PHYSICS_DONE 直达。
>    一处**有意偏离**：§5.1 第 10 条写的“取走时电池=HELD_BY_USER”实现上推迟到结算事务里写，
>    因为 I3（扣减⇔归属同事务）优先；偏离已写进代码注释。
> **实现时发现并修正的一处语义矛盾**：步骤态原写 `PHYSICS_DONE` 为终态，
> 但 §4.2 主线是 `PHYSICS_DONE → VERIFIED`（S3 核验就发生在物理完成之后）；
> 穷举测试直接把它抓出来了（登记了出边却被当成封口）。
>
> **事件拆成事实粒度是一个设计决定，不是重写文档**：
> §5 里如 deadline_S1 一个事件按反查结果分三岔，若保持粗粒度，一条 `(state,event)` 就会对应三个目标态，
> “未列出即非法”会退化成“运行时再说”。现在事件名就是反查得到的事实，分岔责任前移到反查。
>
> ⑧ **四层并发的第一层：柜机分段锁 + 真并发实测**（`CabinetLocks`，64 段取模）。
>    关键层次约定：**先拿锁、再开事务**（`create()` 不再用 `@Transactional` 而是 `TransactionTemplate`）——
>    锁在方法内释放而事务在其后提交，两者错开就等于锁白加（唯一索引的裁决在提交时刻）。
>    最有用的一条断言是**拒绝原因本身**：有锁时抢不到唯一满电仓的人应得到 `NO_OFFER_SLOT`（重算过候选、确实没仓），
>    没锁时才会得到 `SLOT_RACE_LOST`（撞上别人刚占的仓）——把原因钉成断言，锁被误删时测试会立刻红。
>
> ⑨ **超时与反查驱动（A 块）**：`SwapTimeoutDriver` + `SwapShadowBridge` + `SwapFlowService.declareClosed`。
>    反查只承认两个来源且都要求新鲜：台账投影（`swap_slot.door_state`，由事件维护）与影子上报态
>    （`QUERY_STATUS` 应答经 `SwapShadowBridge` 写入）；都没结论只能判 `UNCONFIRMED`。
>    **不得把“云端不知道”伪装成“门没开”**——那会直接导致重复开仓。
>    §5.3 的三条分岔、#22/#23 自助恢复一次、#24 挂起到期转 `PENDING_PICKUP` + 中止、#26 转人工已全部实现并有用例。
>    本批被测试抓出的三个真 bug（都不是测试写错）：
>    ① 重发前没 `SUPERSEDE` 旧在途指令 → 撞 `uk_icmd_active`，所以上一批写的“重发一次”实机上根本发不出去（异常被 catch 吞）；
>    ② 步骤事件映射只看目标态 → `DISPATCHED→DISPATCHED`（重发）被错配成 `DISPATCHED` 事件判非法；
>    ③ `CREATED` 指令的超时被强推成 `TIMEOUT`——语义上应是 `EXPIRED`（零物理动作），两者不能混。
>
> ⑩ **C 端身份域（member realm，B1）**：`MemberTokens` + `MemberJwtAuthenticationFilter` +
> `MemberSecurityConfig`（@Order(2) 独立过滤链，后台主链改为 @Order(3)）+ `MemberAuthService` +
> `MemberAuthController`。能力：手机号+验证码注册即登录、access/refresh 双令牌、会话族顶号互斥、
> refresh 轮换与复用检测、登出、实名提交。
>    **顺序调整声明**：member 域原属 M4，这里提前是因为 C 端页面必须有真鉴权——
>    用 header 传 memberId 的“演示态登录”等于交付一个未鉴权的 C 端接口，这不是简化而是假。
>    三个实现约定：串域一律 401（两域密钥不同，在验签阶段就失败）；一次性验证码用 CAS 更新而非先查后改；
>    **复用检测的整族撤销走 `REQUIRES_NEW`**（同事务的撤销会被紧接着抛出的异常回滚掉，等于没撤销）。
>    C 端过滤器每请求查会话有效性（不查则已签发的 access 在自然过期前继续可用，“强制下线”停留在纸面）。
>    同时补上换电 guard 的实名强制（`REALNAME_NOT_VERIFIED`）。
>
> ⑩b **C 端 H5 主链路（B2）**：后端 `DisplayState`（订单态→展示态的**唯一**映射处）+
> `MemberSwapService`/`MemberSwapController`（找柜/建单/进度/开仓/声明已关门）；
> 前端 `api/member.ts`（独立 axios 实例与独立令牌存储，**不复用后台 request**，
> 否则会员 401 会把管理员会话一起登出）、`views/h5/login.vue` + `swap.vue`（四屏：找柜→确认→进度→结果）、
> `views/h5/display.ts`（纯函数， tone→样式/按钮/轮询）、路由 `/h5/**` 分支、Vitest 6 例 + Playwright 3 例。
>    **关键约束落在哪一层**：“未知不得显示为失败”同时钉在后端（`MemberSwapViewTest` 断 `tone=warn` 与
>    `canReorder=false`）与前端（`display.spec.ts` 断 `isFailureLike('warn')===false`），
>    并约定页面**不得用文案字符判断失败**（只能看 tone）。
>    两个真实修正：柜机概况的“可取仓”必须用 `slot_state='IDLE_CHARGING'`（`FULL` 是 charge_state，
>    写错会让列表永远计 0，正是 `SlotAllocator` 注释里警告过的坑）；新会员额度为 0 不得换电是**正确领域行为**
>    （套餐购买属 M4），页面必须先引导实名与提示额度不足，而不是绕过 guard。
>    地图/经纬度属外部硬约束，找柜先按站点列出（已标注）。
>
> ⑩c **后台订单管理与人工干预（B3 第一部分）**：`swap_intervention` 表（V16）+
> `SwapInterventionService`/`SwapInterventionRecorder`/`AdminSwapOrderController` + 前端 `biz/swap/order.vue`。
>    **双人复核落在哪**：不在二次确认弹窗，而在（一）权限码分开 `swap:order:intervene` / `swap:order:approve`
>    （同一个码 = 能申请就能自己批），（二）`ck_iv_two_person` 表级 CHECK 直接拒绝审批人=申请人，
>    （三）服务层前置校验，（四）同一单只允许一条待处理申请（`uk_iv_active` 生成列）。
>    审批记录先 `REQUIRES_NEW` 独立提交再执行订单动作：执行失败必须留下 FAILED + 原因，而不是“什么都没发生”。
>    申请阶段就用 `canFire` 校验状态机：注定失败的申请不该进复核队列占位。
>    `ADMIN_ABORT` 的落点是 `FAILED_MANUAL`（**只冻结现场**）而不是 `ABORTED`：
>    人工按“中止”时系统并不知道是“没换成”还是“电池已被取走”，两者处置相反，方向由后续 RESOLVE_* 定。
>    `ADMIN_RESOLVE_COMPLETED` 做资金实扣 + 释放预占 + 记一条 `FACT_MISSING` 差异台账，
>    但**不伪造设备事实改资产归属**（归属需另走 `swap:battery:reconcile`）。
>
> ⑩d **后台只读三页**（柜机监控 / 电池资产 / 账实差异）：`SwapAdminReadRepository` + `AdminSwapReadController`
> （`/swap/cabinets`、`/swap/batteries`、`/swap/discrepancies`）+ 三个 Vue 页（V11 预置的菜单现在真的能打开了）。
>    权限码不合并：电池台账含持有人与隔离原因（能看=能追人），与“看柜机几个仓”不是一个敏感级别。
>    **新增 `AdminSwapReadTest` 这类“只验 SQL 能执行”的冒烟测试的理由**：手写读模型的列名写错
>    （本批真把 `c.device_row_id` 写成了 `d.device_row_id`）在任何业务断言里都可能看不出来，
>    只有真的执行那条 SQL 才红——这是手工冒烟发现的，于是固定成 CI 断言而不是靠人肉起服务。
>
> ⑪ **不变式逐条实证（I1–I10）+ 跨进程联跑（未闭环）**：
>    `SwapInvariantTest` 9 例逐条钉住 I1–I10（每条都尽量用"故意违反一次、期望被拒"的写法）。
>    自查发现并修了两处**文档承诺与实现不一致**：
>    ① I9 的 per-battery 唯一只有生成列 `active_battery` **没建唯一索引**（V17 补）；
>    ② I8 的 `swap_compensation` 表建了但**代码里零写入者** → 现在 compensate 逐项落台账，
>      `ABORTING→ABORTED` 前有真 guard（`countOpenCompensation`），并多了 `finishAborting` 入口给 M3 异步补偿。
>    另一个发现：`ADMIN_RESOLVE_COMPLETED` 原本在实扣失败时默默放过去（违反 I3），已改为抛异常回滚。
>    跨进程联跑：模拟器新增 `--auto-swap`/`--device`，脚本 `scripts/cross-process-swap.ps1` 能跑到
>    “设备上线→建单→S1 下发→柜侧收到并执行”，但**柜侧上行事件云侧收不到**（已列入 ROADMAP §4）；
>    为此补了最小权益发放接口 `POST /swap/rights/grant`（复用现有码 `member:right:adjust`，不新增）。
>
> ⑫ **阶段 0 完成：跨进程联跑跑通**（M2 出门）。两个真进程（buddy 内嵌 Broker + buddy-sim 柜侧）
> 跑成一单，`displayState=SUCCESS` 加四维断言（状态/资产/权益/事件流）；入口是公开 HTTP API，
> 不改库、不用测试替身。脚本 `scripts/cross-process-swap.sh`（CI）/ `.ps1`（本地），CI 新 job `cross-process`。
> 联跑一次跑出四个同 JVM 永远测不到的真缺陷（详见 ROADMAP §4）：会话号权威来源错、事件未按订单串行、
> `TAKEN` 后关门被丢、`ACKED` 被反复按超时处理。**这正是把联跑从 M3 提到 M2 的理由**。
>
> **M2 已收口；待做 → M3**：FI-01..16 全量故障注入与四维断言、补偿执行器与对账自愈、
> 安全联动、重启现场重建、场景 DSL、混沌（kill/重启/断 Redis）、压测“不变式违反数=0”。

台账（站点/柜机/仓位/电池）→ 订单 + 步骤双状态机 → 分配策略（硬门槛 + 3 档优先级链 + 同人连续分配惩罚）→ 物理事件匹配器 → `swap_result` 交叉核对 → **四层并发**（柜分段锁 + ShedLock + `@Version` CAS + DB 生成列唯一约束）→ C 端 H5 主链路 → 后台管理页。

**开工前新增前置（均来自 M1 实做发现的缺口）**：
- **M2-0 台账与物模型种子（V13）**：产品 / 物模型 / 站点 / 柜机 / 仓位 / 电池的可跑数据。
  同时给 `SwapDdlContractTest` 加一条断言：**每个 `iot_product` 必须有一个 PUBLISHED 物模型**。
  原因：M1 的校验器在缺物模型时按设计**静默跳过**（不能阻断主链路），不补这条就会长期假绿 ——
  看起来在做遥测校验，其实一行都没校。
- **M2-0b 设备注册与凭证颁发**：预分配 deviceKey + 首连取主密钥。
  M1 的模拟器主密钥靠参数给定；不补这一段，M2 主链路只能靠手工插表跑，E2E 就是假的。
- **验证门调整（重要）**：把“**跨进程联跑（buddy + buddy-sim 两个独立进程）**”从 M3 **提前到 M2**。
  理由：M1 的云端测试里“设备”是 buddy 自己的测试客户端用 buddy 自己的 `DeviceSecrets` 签名，**两端同源**；
  golden 样本能挡住格式/签名基串的漂移，但挡不住“云侧对事件语义的理解本身写错、设备侧按同一套理解生成”。
  主链路是第一次有完整业务事实链，必须以 buddy-sim 为对手方跑通，否则 M3 的 16 项 FI 全建在同源假设上。
- **迁移遍历范围收敛**：指令状态机（9 态）已由 M1 的 `IotCommandTest` 覆盖，M2 只遍历**订单与步骤**两组，
  避免重复劳力并明确归属（否则“39 条全遍历”会被当成 M2 又要重做一遍）。

**验证门**：订单/步骤迁移全遍历（**未列出的 `(state,event)` 组合必须抛异常**）；I1–I10 不变式全部有 DB 约束实证；真 InnoDB 仓位抢占；E2E 主链路绿；**buddy + buddy-sim 跨进程跑成一单**。

### M3 · 异常与一致性（设备侧 L2 全套）—— 进行中（云侧阶段 1 已交付）

> **已交付（2026-10-01，阶段 1·云侧一致性与补偿）**：
> ① **补偿动作目录代码化**：`CompensationAction`（14 个动作，与 V9 `ck_comp_action` 逐字对齐），
>    每个动作带 `blocking()`（是否属于 I8 的“落终态前必须完成”集）与 `targetType`；
>    FSM 文档新增 **§7.1 补偿动作目录**（动作 / 执行者 / 幂等键 / 完成判据 / 阻塞终态），
>    `SwapDdlContractTest#compensationCatalogIsAlignedAcrossDocDdlCodeAndExecutor` 钉住**四方**一致：
>    文档 §7.1 ↔ DDL CHECK ↔ 枚举 ↔ `SwapCompensationExecutor.implementedActions()`。
>    第四方是这批的关键：前三方只能证“名字存在”，证不了“有人执行”——
>    库里写得进、执行器不认的动作会永远 PENDING，而账面看起来是“待处理”而不是“没人管”。
> ② **补偿执行器** `SwapCompensationExecutor`：ShedLock 扫 `PENDING/FAILED` → 每项独立事务执行 →
>    CAS 置 `DONE`；失败累加 `attempts` + `last_error` + 指数退避（30s→30min 封顶）；
>    重试耗尽（8 次）记差异转人工；**本域没有执行者的动作保持 PENDING 并记差异，绝不标 DONE/SKIPPED**；
>    阻塞项做完后由执行器把停在 `ABORTING` 的单收尾（复用 M2 的 guard 与 `finishAborting`，不重写一份）。
> ③ **对账自愈** `AssetLedgerReconcileJob`：I7 的三条**双向引用检查**（仓→电池不回指 / 电池→仓位不含它 /
>    ACTIVE 绑定与电池状态不同真）；只在证据充分时自愈，且自愈的方式是**写补偿台账交给执行器**
>    （幂等、可重试、有流水），而不是 job 自己改表——否则“谁改的”又多一条只有日志能回答的旁路。
> ④ **拒收双分岔 R-A/R-B**：`SwapFlowService.rejectIntake` 按“电池在不在仓内 / 用户手上有没有新电池”分岔
>    （锁仓 + 待取回 + 工单 vs 重开仓让用户取回 + `ABORTED` + 权益 `RELEASED`），两条都不误改归属；
>    断言形状是“**拒收后停在 ABORTING 才是对的**，跑一次执行器才到 ABORTED”。
> ⑤ **安全联动** `SwapSafetyLinkageService` + 受控接口 `POST /api/swap/safety/emergency-stop`（新权限码 `swap:safety:stop`，V18）：
>    站点内逐柜锁柜 + `EMERGENCY_STOP`（走 `cmd/critical` 独立主题，qos/ttl/retryMax 全照协议 §4.1）+
>    在途单批量走 `ALARM_SAFETY_LOCK`（系统自动，**不占 `ADMIN_ABORT` 的人工通道**）+ 告警升级挂补偿台账；
>    另对外提供补偿台账的查看与对账/补偿的手动触发（`swap:compensation:read`、`swap:discrepancy:replay`）。
> ⑥ **重启现场重建** `SwapRecoveryService`（`ApplicationReadyEvent`）：补非终态单的 deadline（否则超时驱动永远扫不到，
>    “挂着不动”变成“永久悬挂”）、收尾补偿已做完的 `ABORTING` 单、孤立指令**只记差异不删**（删指令等于销毁证据）。
>
> 这批里改掉的三个真缺陷（都不是“写完就绿”能发现的）：
> `WRITE_DISCREPANCY` 原先是只打计数的 noop 却被标 `DONE`（谎报完成）；
> 安全联动重复触发时第二次停充指令被 `uk_icmd_active` 拒掉又被 catch 吞掉（柜机只收到过一次停充）；
> `emergencyStopSite` 的外层 `@Transactional` + 内部 try/catch 是**假隔离**（内层一抛整个事务被标 rollback-only，
> 方法照样返回“成功”，锁柜与中止全被回滚）——改为逐柜独立事务 + 逐单独立提交。
>
> **待做 → 阶段 2（设备侧 L2）/ 阶段 3（验证门）**：FaultPolicy 补满 9 类、场景 DSL、**HTTP 控制面**
>（原列入阶段 0 的计划项，因联跑用 `--auto-swap` 定长动作即可完成而**顺延到本阶段**，已登记 ROADMAP §4）、
> FI-01..16 矩阵与四维断言、双端指标可归因、千台规模、混沌三脚本、压测“不变式违反数=0”。

- **云侧**：补偿动作目录与台账、对账自愈、拒收双分岔（R-A/R-B）、安全联动（紧急停充/锁柜/告警升级）、`UNCONFIRMED` 收敛、重启现场重建。
- **设备侧 L2**：FI-01..16 补满、场景 DSL、seed 可重复、HTTP 控制面、双端 Prometheus 指标（可归因）、千台规模。
- **验证门**：16 项 FI 全绿且**四维断言**（状态/资产/权益/工单同时正确）；混沌（kill 实例、Broker 重启、断 Redis）；压测**不变式违反数 = 0**（这是本项目的核心指标，不是吞吐量）。

### M4 · 权益与资金

member 域（注册/登录/实名、权益账户、预占-扣减-释放、套餐按次/包月/额度/超量、**押金与授信免押**、优惠券、续费与代扣）+ pay 域（渠道端口、模拟渠道、回调幂等、退款规则、日终对账单与差异处理、不可变资金流水）。
**设备侧**：支付回调故障编排（重复/乱序/验签失败/退款失败）。
**验证门**：重复回调只结一次；对账差异每类可复现可消化；资金流水台账恒等。

### M5 · 运维与安全

告警规则引擎（阈值 + 持续时长 + 收敛抑制 + 升级）、**工单全流转（派单/接单/处置/回访/SLA）**、巡检、远程开门审批 + **双人复核**、电池 SOH/循环与隔离、**GPS 围栏与越界告警**、安全事件看板。
**验证门**：规则引擎单测；联动 E2E；工单闭环；安全事件审计链完整可追溯。

### M6 · 多租户与经营

租户开通（运营商/加盟商/场地方）、**`BUDDY_TENANT_ENABLED=true` 真开档隔离实证**、分账规则、结算与提现、经营大屏、报表、地图找柜。
**验证门**：跨租户隔离测试；分账恒等式对账测试；租户级配置（阈值/SLA/资费模板）生效测试。
> 这是 buddy 多租户能力**第一次在真实业务里开启**，预期会暴露框架缺陷；发现即修框架并补回归测试。

### M7 · 交付化与双环境验证

compose 增 emqx / iotdb / prometheus / grafana；**同一协议在 EMQX 下跑通（双 broker 验证）**；`TelemetryStore` 的 IoTDB 第二实现；部署脚本；文档收口。
**验证门**：CI 全 job 绿；生产 jar + MySQL 实证；性能与故障矩阵报告。

### M8 · 协议第二实现（二进制帧）

Netty `ByteBuf` 二进制 codec（参照 T/BIKE 7.3 报文分类与格式）作为 `PayloadCodec` **第二实现** + 带宽/CPU/终端算力对比报告；设备侧同步实现。
**验证门**：**同一套 golden 样本双 codec 全部通过**（抽象没焊死才算达成）。

### M9 · 设备认证增强

本地 CA 证书链签发、X.509 双向 TLS、三元组与密钥轮转；设备侧证书校验与轮转窗口响应。
**验证门**：握手/篡改/过期证书/轮转窗口四类测试全绿。

---

## 4. 依赖与顺序约束（哪些是真依赖，不是随意排的）

```
M0 ──► M1 ──► M2 ──► M3 ─┬─► M4 ──► M6
                         └─► M5 ──► M6 ──► M7 ──► M8 / M9
```

- **M1 是 M2 的硬前置**：没有可信设备侧对手方，订单接入层接口形状会按"永远成功"长歪，M3 时必返工。
- **M3 是 M4 的硬前置**：资金与退款规则依赖 `REJECTED` / `ABORTED` / `FAILED_MANUAL` 三种终态语义已被证明可区分，否则退款口径无法定。
- **M4 与 M5 可部分并行**（工单/告警不依赖资金），但 M6 分账必须等 M4 资金流水台账稳定。
- **M6 必须晚于 M2~M5**：多租户开启会让**每张新表**都受租户拦截器影响，越早开返工越小、越晚开暴露越充分——取"表结构定型后立刻开"，即 M6。
- **M8/M9 放最后**：它们是对**抽象层的检验**（codec SPI、认证 SPI），必须等接口被真实业务钉死后才有意义，提前做等于对着空气设计。

---

## 5. 框架回流节点（每块末执行，不延后）

| 里程碑 | 回流到 `framework/` 的能力 |
|---|---|
| M1 | `iot`（信封/编解码 SPI、CommandBus、会话与在线状态、影子、物模型、`TelemetryStore` 端口）、`event`（Outbox + 幂等消费 + 去重）、`statemachine`（迁移表骨架、非法迁移异常、留痕钩子、deadline 约定） |
| M2 | 决策纯函数范式落地（分配策略）、`@Version` CAS + DB 约束的四层并发工具化 |
| M3 | 补偿动作台账、对账自愈骨架、超时双保险调度件、混沌测试基设 |
| M4 | 幂等消费、不可变流水台账、日终对账框架 |
| M5 | 规则引擎骨架（阈值/持续/收敛/升级）、审批+双人复核件 |
| M6 | **多租户开档缺陷修复**、租户级配置件 |
| M7 | 双 broker / 双时序库第二实现验证端口的真实性 |

**纪律**：`framework/iot` 内**禁止**出现 slot / battery / order / 套餐 等业务语义，只有 device / session / command / thing-model / shadow / telemetry。

---

## 6. 外部硬约束（不是缓做，是条件不具备；各附最强替代）

| 项 | 硬约束事实 | 已做到的最强替代 | 归属 |
|---|---|---|---|
| 真实微信支付 | 无商户号、无线上资质与证书 | 渠道端口 + **模拟渠道可触发等价故障**（重复/乱序/验签失败/退款失败）+ 回调幂等与对账差异**全链路测通** | M4 |
| 真实柜机硬件 | 无物理设备 | 协议的规范性设备实现（校验链 + 物理模型 + 16 项故障注入）。**边界声明：仿的是协议与状态，不仿热/机/电物理效应** | M1/M3 |
| 真实十万级连接 | 无该规模环境 | 千台规模压测 + 可外推的连接数资源模型；集群与真连接数表现**登记为需生产环境实证**（同 Docker 镜像的处理方式） | M3/M7 |
| 本机容器运行时 | 实测无 Docker（MySQL 8.0.44 / Redis 5.0.14 / JDK 17 可用） | 主链路全部使用本机可跑件；镜像构建与编排交 CI `docker` job | M7 |

---

## 7. 取舍登记总账（唯一维护处，其他文档只引用不重复）

### A 类：我方工程选择 —— 均有归属里程碑，**不是永久简化**

| # | 项 | 企业标准 | 当前排期 | 归属 |
|---|---|---|---|---|
| A1 | 报文格式 | 二进制紧凑帧 | JSON 先行（不阻塞主链路），二进制帧作 `PayloadCodec` 第二实现 | **M8** |
| A2 | 设备认证 | X.509 双向 TLS / 三元组 + 轮转 | 先做派生双密钥 HMAC（连接层 + 报文层） | **M9** |
| A3 | 时序存储 | TDengine / IoTDB 专库 | 先 `TelemetryStore` 端口 + MySQL 分区表 | **M7**（IoTDB 第二实现） |
| A4 | 消息中间件 | RocketMQ 事务消息 | Outbox（同库同事务）+ Redis Stream，退避/死信/积压指标在本地实现即完备 | **M7** 接 RocketMQ 作可选实现 |
| A5 | 部署形态 | 接入层独立进程/微服务 | 单体同进程 + `DeviceGateway` 与路由注册表薄抽象 | **M7**（profile 可拆，抽象已就位） |
| A6 | 离线自治换电 | 柜机本地蓝牙离线放行 | 默认禁止；实现站点/租户级开关 + 强制离线决策留痕与补单对账 | **M3** |
| A7 | 一人多电池持有 / 跨柜续作 / 预约满电电池 / 物理层热机仿真 | — | 前三项进 M4~M6 评估实现；**物理层热/机/电仿真属硬约束边界，不做**（见 §6） | M4~M6 |

### B 类：加严项（比企业常见做法更严，登记以免被误读为简化）

| # | 项 | 内容 |
|---|---|---|
| B1 | 零代码共享 | 设备侧与云侧**故意各自独立实现协议编解码**，只共享 `protocol/` 数据资产，防同源错误互相掩盖（企业常见做法是共享 SDK 包） |
| B2 | 第三方裁判 | 引入 mosquitto 做标准 MQTT 互操作验证，防"自研 server 与自研 client 彼此自洽但不合标准" |
| B3 | 双端可归因 | 模拟器自带 Prometheus 指标，使"云端报超时"能区分设备未收/未回/回丢三种成因 |
| B4 | 不变式落 DB | 能被数据库约束表达的不变式不使用应用代码守护（生成列唯一索引替代部分索引） |
| B5 | 正确性不依赖 Broker | 在线判定不信 LWT/`$SYS`、可靠投递不信 Broker 离线队列，全部由云侧持久化兜底 |

---

## 8. 交付物清单

- **代码**：`buddy`（`framework/` 增量 + `biz/swap` + 权限/菜单种子迁移）、`buddy-sim`（独立产物）、`buddy-ui`（后台页 + C 端 H5）、`protocol/`（双端共享数据资产）。
- **文档**：本文 + `swap-protocol.md` + `swap-order-fsm.md` + `swap-simulator.md` + `swap-ddl.md` + `swap-member-auth.md`，并同步 `architecture.md` 与 README 能力清单。
- **质量**：单元 / 集成 / 协议契约 / 并发 / 混沌 / 故障矩阵 / E2E / 压测 / 安全测试 + CI 门禁 + 可观测仪表盘与压测报告。

---

## 9. 变更日志

- 2026-09-30：**M2 开工前复核**。整体里程碑划分、双轨策略、都不砍原则维持不变；
  向 M2 加入四项调整：M2-0 台账与物模型种子（+“每个产品必须有已发布物模型”契约断言）、
  M2-0b 设备注册与凭证颁发、跨进程联跑从 M3 提前到 M2 验证门、迁移遍历范围收敛（指令态归 M1）。
  四项全部是 M1 实做暴露的缺口而非新增野心；其中“跨进程提前”是为了避开**两端同源**这个最贵的风险。

- 2026-09-30：**M1 整阶段交付**。云侧新增 `framework/iot`（transport/envelope/security/repo/command/session/telemetry/model/maintenance/error/config）、
  `framework/event`（Outbox 写入与投递 + Redis Stream 分发端口）、`framework/statemachine`（骨架 + 4 项行为测试）；
  新增独立工程 `buddy-sim`（零代码共享的设备侧实现：协议、十步校验链、柜机物理模型、5 项故障注入、CLI）
  与仓库根 `protocol/v1/samples/`（3 份跨端 golden 样本，两侧各自独立断言）；CI 新增 `device-sim` 与 `protocol-interop`（mosquitto 第三方裁判）两个 job。
  自证：`buddy` `mvn verify` **120 用例全绿 + JaCoCo 达标**（含 5 个真 TCP 指令总线用例、4 个 golden 契约用例、7 个接入层用例）；
  `buddy-sim` 15 用例全绿；真库档 `MysqlConsistencyTest` 3 用例全绿；前端 12/12 绿。
  过程中两个由测试抓出来的真 bug：设备侧 `!putIfAbsent(...)` 导致 NPE；校验链步序错误会把 QoS1 正常重投当成攻击拒绝并**不回应答**（已反向修正协议 §6）。
- 2026-09-29：**M0 收口（M0-4 定稿）**——新增 `buddy/docs/swap-member-auth.md` 与 `V12__member_auth`（`member_session` / `member_sms_code` / `member_realname`，3 张表 + 3 条生成列不变式 + `member_user` 补列 + 3 个权限码）。
  核心约束：“**串域必须返回 401 而不是 403**”——403 意味着已进入后台权限判定路径（串域前兆），401 意味着在分派阶段就被拒；该区分可直接断言，因此成为可自证的安全约束而非口头承诺。
  令牌：独立密钥（缺失即启动失败）+ access 2h / refresh 30 天一次性轮换 + **复用检测即整族撤销**；多设备类型并存、同类型互斥（DB 生成列）。
  业务口径（用户拍板）：**实名强制**、**refresh 30 天 + 同设备类型互斥**、**注销需无在途且无 ACTIVE 绑定（权益作废不退款）**。
  MySQL 8.0.44 上跑完 8 项约束用例（含 4 项**正向对照**，其中一项专门验证生成列随 UPDATE 重算）全部符合预期；同时修正 V11 预留版本号（V12 已被身份域占用，后移为 V13/V14/V15）。
  环境事件：本轮 `mvn verify` 一度因**本机 Redis 服务处于 Stopped** 而在 `SysUserApiTest.repeat_submit_blocked` 失败（30s 连接超时 → code 500 而非 1404），属环境前置而非代码回归；已重启 Redis 并重跑。
- 2026-09-29：**M0-5 交付**——`SwapDdlContractTest`（纯文件解析、不启 Spring，7 项断言）。
  **首跑即抽出一处真实漂移**：DB 步骤态枚举含 `SKIPPED` 而 FSM 步骤态行漏声明，已反向修正文档并补齐两个状态的语义定义。
  本块同时确认：`order_state`、`order_step.step_code/step_state`、`active_user` 在途集合、
  `@enum` 标记与列存在性双向对齐、FSM 引用的事件/指令均在协议清单内、`@PreAuthorize` 权限码均在种子内。
- 2026-09-29：**M0-3 定稿**——新增 `buddy/docs/swap-ddl.md`，交付 `V7`~`V11`（30 张新表 + 样例种子 + 权限菜单）。
  版本切分已修正（原写 `V7__swap_business`/`V9__member_billing` 等）：`member_right` 提前至 V10，因为它是 M2 主链路 guard 的硬前置。
  两路实测均通过：H2 2.2.224（`MODE=MySQL`）与 MySQL 8.0.44 按 V1→V11 全量执行，`mvn verify` **95 用例全绿 + JaCoCo 达标**，
  真库档 `MysqlConsistencyTest` 3 用例全绿；四条负例（站点放宽门槛 / 双 ACTIVE 绑定 / 双 ACTIVE 预占 / 同用户双在途单）均被 DB 拒绝，
  一条正例（终态后同用户可重建单）通过——**只证“会被拒绝”不够，必须同时证“能释放”**。
  派生项归 M1：`TelemetryPartitionJob`（分区需持续滚动，Flyway 一次性迁移无法承担）、阈值冗余漂移校验、扫描 SQL 的 EXPLAIN 断言。
  新记录一坑：**H2 即使 `MODE=MySQL` 也不支持 `IF()`**，生成列表达式必须用 `CASE WHEN` 且不写 `STORED`。
- 2026-09-29：初版。确立 P1~P5 总原则（双轨互为对手方、每块验证门、框架逐块回流、范围不削减、能力自证）；
  M0~M9 里程碑与依赖顺序；框架回流节点表；外部硬约束四项（各附最强替代）；
  取舍登记**重分类为 A 类（我方选择，均有归属里程碑）/ B 类（加严项）**，本文成为取舍登记唯一维护处；
  吸收原 `swap-protocol.md` §14 的 8 项取舍并新增 A6/A7/B1~B5；删除"明确不做"表述，改由 §6/§7 承载。
