# barrier 样例 · 升降杆调度系统

> `biz.barrier` 是 buddy 框架自带的**完整业务样例**：用框架的地基（鉴权/审计/多租户/可观测/Flyway）
> 承载一个"真实小系统"，示范业务如何接入框架。本文以仓库实际代码为准。
>
> 定位演进：早期 barrier 是"单杆、`synchronized` 单写锁、独立工程"；并入 buddy 框架后升级为
> **多杆、策略驱动、三层并发一致性**的样例，复用框架全部横切能力，不再独立成工程。

---

## 1. 业务本质

把"多根升降杆，各自按时间窗到点开/关，允许随时手动覆盖、到下一个定时边界自然回归，每次动作可追溯"
这件事**做对**。领域本质是 **调度正确性 + 并发/时序一致性 + 可追溯**，不是"接设备"（纯软件模拟，无真实设备）。

价值不在功能多，而在把真实上线必然遇到的边角情况——**错过时间、重复触发、时钟回拨、手动撞定时、
跨实例并发写、重启漂移**——全部处理掉且可测试、讲得清。

---

## 2. 领域模型（多杆）

| 概念 | 表 | 说明 |
|---|---|---|
| 杆 | `barrier` | 设备抽象；每根独立调度、独立状态；`enabled` 控制是否纳入心跳 |
| 策略 | `barrier_strategy` | 一组计划点的归属与优先级载体（`priority`）；`enabled` |
| 计划点 | `barrier_schedule` | 归属某策略；`time_of_day` + `plan_state(OPEN/CLOSED)` + `enabled`，每天重复 |
| 当前态 | `barrier_status` | 每杆一行（`barrier_id` 唯一）；`barrier_state` + `manual_override` + `last_scheduled_action` + `version` |
| 事件流 | `barrier_event` | 某杆一次生效切换，**只追加不可变**（`source(SCHEDULED/MANUAL)`、`occurred_at`、`operator_id`）——事实源 |
| 策略↔杆 | `strategy_barrier` | **N:M** 绑定（纯关联表，DB 外键 `ON DELETE CASCADE`） |

**建模选择：时间窗 = "到点设置目标态"，而非"到点发一次动作"。**
"开着"本质是**状态**而非瞬间动作，于是"此刻该不该开"成了纯函数 `f(now, 计划)`：可脱库单测、可跨午夜回绕、
错过自然由下一次算对——**幂等与补偿都是免费的**。若建模成边沿动作（8:00 发开、20:00 发关），错过就丢、
重启要补偿、幂等要额外记边沿，更脆更复杂。

样例种子：1 号杆 + 默认通行策略（`priority=100`，08:00→OPEN「早开」/ 20:00→CLOSED「晚关」）+ 绑定，clone 后即可演示。

---

## 3. 决策纯函数（`core`，无副作用、重点测试）

- **`ScheduleComputer.scheduledStateAt(minuteOfDay, enabledPoints)`**：取 `minuteOfDay ≤ now` 的**最大**点，其状态即当前应处态；
  若当天尚无点被越过（now 早于首点），**回绕**取全天最大点（= 昨天最后一次动作），天然处理跨午夜窗口；无启用点返回 `null`（保持现状、不擅自动作）。
- **`StrategyResolver.pickActive(strategies)`**：从启用策略里取 `priority` 最高者；同优先级取 `id` 最小者。
- **`BarrierEngine`** 接收外部传入的 `now`（不自取时钟）→ 跨午夜、时钟回拨等可在单测精确构造。

---

## 4. 引擎不变式与三层并发一致性（`BarrierEngine`）

多杆引擎遍历启用杆，**每根杆独立** `reconcile`/`manual`/`alignOnStartup`。并发安全由三层协同兜底：

| 层 | 机制 | 作用域 | 落点 |
|---|---|---|---|
| ① 进程内 | 每杆一把 `ReentrantLock`（分段锁，`ConcurrentMap<Long,ReentrantLock>`） | 同杆串行、异杆并行；`reconcile` 逐杆持锁不整轮独占 | `BarrierEngine.lockFor` |
| ② 跨实例心跳 | ShedLock `@SchedulerLock`（JDBC provider，锁落 `shedlock` 表，`usingDbTime` 抗时钟漂移） | 集群内同一时刻至多一个实例执行本次 tick | `BarrierScheduler`（`biz.barrier.scheduler`）/ `ShedLockConfig`（`framework.config`） |
| ③ 跨实例写 | `@Version` 乐观锁 CAS（`casUpdate` 按 version 谓词更新，影响行数 0 即冲突；并发插入由唯一索引 `uk_status_barrier` 兜底） | DB 层写冲突检测，抛 `OptimisticLockConflictException` | `DbBarrierStore.upsertStatus` |

**核心不变式**：

1. **幂等**：仅当"目标 ≠ 当前"才切换并记事件；`manual` 遇 `state==target && manualOverride` 直接短路返回。重复 tick / 重试 / 时钟回拨不产生第二次动作。
2. **边界跨越**：`crossed = (lastScheduled==null || target!=lastScheduled)`。跨越时以计划为准并清 `override`（手动回归定时）；未跨越且有 `override` 则沉默（保护手动覆盖）。
3. **错过补偿**：决策只依赖 `now`，漏跑/停机后下一次 `reconcile` 直接对齐到"现在应有的状态"，**不逐点回放**。
4. **状态↔事件一致**：`DbBarrierStore.apply` 在一个 `@Transactional` 里落状态 + 追加事件 + 记指标；CAS 冲突则整个事务回滚，不产生孤儿事件。
5. **重启对齐**：`alignOnStartup` 逐杆清 `override`、按当前时间设态并推进 `lastScheduled`，不恢复内存/手动临时态。
6. **冲突处理分场景**：
   - `reconcile`/`alignOnStartup` 遇 CAS 冲突 → **本次让步**，下次心跳重读最新态自愈（幂等）。
   - `manual` 遇 CAS 冲突 → **有界重试**（`MAX_CAS_ATTEMPTS=3`），耗尽则上抛，由控制器翻译为业务冲突码
     `ResultCode.CONFLICT`（HTTP 200 + `code:409`，"杆状态正被其他操作修改，请稍后重试"），不外泄内部异常。

> 单 JVM 内同杆被①串行化，CAS 冲突只可能发生在**跨实例**；`MySQLConcurrencyTest` 绕过进程内锁直接并发 `store.apply`，
> 在真实 InnoDB 上验证"并发写只有一个胜出、其余抛冲突"。

---

## 5. 调度与可测性

- **心跳**：`BarrierScheduler.tick()` 用 `@Scheduled(fixedDelayString="${barrier.scheduler.tick-ms:5000}")` 周期触发；
  决策是分钟粒度，tick 更细只为降低"到点后延迟"，即便漏跑下一次 `reconcile` 依当前时间补回。
- **集群单例**：叠加 `@SchedulerLock(name="barrier-reconcile", lockAtMostFor="PT30S", lockAtLeastFor="PT2S")`——
  持锁实例崩溃后锁最多 30s 自动释放；即使瞬间完成也至少持锁 2s（< tick 5s，不漏拍）避免同窗口重复触发。
- **多租户**：`tick()` 标 `@IgnoreTenant`——心跳是系统级任务，需跨租户对齐所有杆，不被 `tenant_id` 过滤。
- **可关**：`@ConditionalOnProperty(barrier.scheduler.enabled, matchIfMissing=true)`；测试档置 `false` 手动喂 `now` 驱动引擎，完全确定。
- **可观测**：`BarrierMetrics` 暴露 `barrier.apply`(Counter, tag `source`)、`barrier.cas.conflict`(Counter, tag `reason=version|duplicate-insert`)、
  `barrier.state`(Gauge, tag `barrierId`，内存态、抓取不打库)；`metrics.timeReconcile` 计时单次对齐。

---

## 6. 接口与权限（真实路径含 context-path `/api`）

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| GET | `/api/barriers` | `barrier:status:read` | 杆列表（含各自当前态） |
| GET | `/api/barriers/{id}/status` | `barrier:status:read` | 某杆当前态 |
| GET | `/api/barriers/{id}/events?limit=50` | `barrier:events:read` | 某杆事件流（limit 上限 200） |
| POST | `/api/barriers/{id}/manual` `{action:OPEN\|CLOSE}` | `barrier:manual` | 手动指令；`@OperateLog` 审计；冲突→`code:409` |
| POST | `/api/barriers` | `barrier:manage` | 新增杆；`@OperateLog` |
| PUT | `/api/barriers/{id}` | `barrier:manage` | 修改杆 |
| PUT | `/api/barriers/{id}/enabled?enabled=0\|1` | `barrier:manage` | 启用/停用杆 |
| DELETE | `/api/barriers/{id}` | `barrier:manage` | 删除杆（服务层守卫子表引用） |
| GET/POST/PUT/DELETE | `/api/barrier/strategies[/{id}]` | `strategy:manage` | 策略 CRUD |
| GET/PUT | `/api/barrier/strategies/{id}/barriers` | `strategy:manage` | 策略绑定的杆（N:M）读取/重设 |
| GET/POST/PUT/DELETE | `/api/barrier/schedules[/{id}]` | `schedule:manage` | 计划点 CRUD |

权限码经 Flyway `V5__barrier_menu_seed` 灌入 `sys_menu`；前端按 `sys_menu.perms` 做菜单/按钮级显隐。
超级管理员经 `selectPermsByUserId(userId,true)` 获全部权限码，故天然拥有以上所有 `barrier:*`/`strategy:*`/`schedule:*`。

---

## 7. 持久化（端口-适配器）

- **端口** `BarrierStore`（`core`，纯接口）：`enabledBarrierIds` / `enabledStrategiesBoundTo` / `enabledPointsOf` /
  `loadStatus` / `saveStatus` / `apply`。领域层不依赖任何框架，可脱库单测。
- **适配器** `DbBarrierStore`（`infrastructure`，MyBatis-Plus 实现）：
  - `upsertStatus`：`version==null` → 插入（并发插入撞唯一索引 `uk_status_barrier` → `DuplicateKeyException` → 记 `duplicate-insert` 冲突并抛）；
    否则 `casUpdate` 按 version 谓词更新（影响行数 0 → 记 `version` 冲突并抛）。
  - `apply`：`@Transactional` 内 upsert 状态 + 追加事件 + 记指标，保证状态与留痕原子一致。
- 实体表（`barrier`/`strategy`/`schedule`/`status`）继承框架 `BaseEntity`（审计 + 逻辑删除 + 乐观锁）；
  `barrier_event` 为 append-only 历史，刻意不含 `version`/`del_flag`、不加外键（父变更/删除后仍须存活）。

---

## 8. 明确不做（已知边界）

- **真实设备与现场安全**：防砸、卡杆、断电、离线、现场按钮旁路——纯软件模拟，不接物理设备。
- **多杆联动/互锁**：样例是"每杆独立按各自策略调度"，不做杆间协同约束（如"A 开必须 B 关"）。
- **任务编排**：多任务类型 / DAG / 抢占 / 优先级继承等——超出"时间窗调度"范围。
- **日历例外**：计划点是"每天重复的时间点"，不含"每周某几天 / 例外日 / 节假日日历"（`SchedulePointSpec` 已预留扩展缝）。
- **计费**：无。

> "知道边界在哪、哪些故意不做"本身就是设计的一部分——预留扩展缝（每杆锁已就位、`occurredAt` 可加幂等键做严格乱序防护、
> store 端口化可换库/接设备回报），但不在样例里替不存在的需求写逻辑。

---

## 9. 30 秒讲清

"一个**多杆**时间调度样例：把'开=状态'建模成纯函数 `f(now, 计划)`，于是错过补偿、幂等、跨午夜都是自然结果；
每杆用分段 `ReentrantLock` 进程内串行、ShedLock 保证集群内心跳单例、`@Version` 乐观锁 CAS 检测跨实例并发写，
三层协同保证定时与手动撞车时状态与留痕始终一致（同事务）；重启按当前时间对齐、不恢复临时手动。
它同时是 buddy 框架拿去落地一个真实小业务的活证据——功能不多，但边角情况都处理且可测。"
