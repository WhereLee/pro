# buddy 框架 · 合并与后续开发顺序计划（ROADMAP）

> 本文件是"框架(buddy) + 框架样例(barrier 升降杆)"合并与后续开发的**顺序计划**，供开发过程参照。
> 实际情况与本文不符时，我会**就地修改本文**（见文末"变更日志"）。
>
> **决策总原则（用户指定）**：当 buddy 与 barrier 出现分歧/纰漏，一律从**框架的技术深度、可复用性、生产级**角度取舍——不是"谁现成听谁的"，而是"怎样让框架更强"。

---

## 0. 定位（已确认）
- **buddy = 框架基座**（`com.lrs:buddy`，"可复用的企业级 Spring Boot 基础框架"）。
- **barrier（升降杆）= 框架样例**，并入 `com.lrs.buddy.biz.barrier`。
- 单 Maven 模块；根包 `com.lrs.buddy` 不改名；框架侧统一在 `framework/`（common/config/security/modules/tenant），业务样例在 `biz/`。
- 复用方式：新项目 clone 本仓库 → 保留框架包 → 替换/新增 `biz` 里的业务。
- barrier 独立工程（`Desktop\barrier`）在合并全绿后退役（保留为回退参照直到最后）。

## 1. 已锁定的关键决策
| # | 决策 | 结论 | 依据 |
|---|---|---|---|
| ① | DB 迁移方式 | **统一到 Flyway**：buddy 的 `schema.sql`→`V1` 基线、`data.sql`→`V2` 种子；移除 `spring.sql.init`；barrier 业务表接 `V3+` | schema.sql 每次启动重建、无版本管理，是 dev/demo 手段；Flyway 版本化迁移是生产级框架标准，利于未来项目 schema 演进 |
| ② | 跨实例定时锁 | **保留 ShedLock（JDBC provider）**，加依赖 + `shedlock` 表（Flyway 版本） | ShedLock 是调度单实例执行的专用生产级方案、barrier 已验证；JDBC provider 不额外依赖 Redis；改用 Redisson 要重写已测并发代码，风险大收益小 |
| ③ | 多租户范围 | **框架级能力（opt-in，默认 `enabled=false`）**：业务表 + `sys_*` 表都加 `tenant_id`（V6 预留、默认租户 1），拦截器按开关装配 | 用户要求多租户可被未来项目复用 → 必须是框架能力、覆盖全表；默认关闭保证单租户项目零开销/零回归，开启只是翻配置 |
| ④ | 样例包路径 | `com.lrs.buddy.biz.barrier.*`（controller/service/domain/mapper/engine/scheduler…） | biz = 业务/样例根；barrier 作为当前样例 |
| ⑤ | 重复脚手架 | **丢弃 barrier 的** R/ResultCode/GlobalExceptionHandler/BaseEntity/SecurityConfig/JWT/RBAC/审计AOP/AuthController/AdminBootstrap，**改用 buddy 的** | buddy 已有且更完整（RBAC 菜单权限、Redis TokenService、@OperateLog、OpenAPI、分页、RepeatSubmit、Prometheus） |
| ⑥ | 框架小增强 | buddy `ResultCode` 加 `CONFLICT(409)`；`LoginUser` 加 `tenantId`；`AsyncConfig` 加 `TaskDecorator` 传播 user+tenant 上下文 | barrier 的 CAS 冲突语义 + 多租户所需，属框架合理增强 |

## 2. 顺序计划（每块都有验证门，绿了才进下一块）

### M0 · 基线与备份
- 交付：跑 buddy 现有构建确认基线绿（记录测试数/JaCoCo）；跑 barrier 确认 49 用例基线；确认本机 Maven/JDK/Redis/MySQL 可用性。
- 验证门：buddy `mvn verify` 绿（或记录环境限制）；barrier `mvn verify` 绿。

### M1 · 框架升级 Flyway（决策①）
- 交付：pom 加 `flyway-core`+`flyway-mysql`；`schema.sql`→`db/migration/V1__buddy_baseline.sql`、`data.sql`→`V2__buddy_seed.sql`；三 profile(dev/test/mysql) 移除 `spring.sql.init` 改 Flyway；`DataInitializer`（管理员 BCrypt）保持启动时执行、与 Flyway 种子不冲突。
- 验证门：buddy 现有全部测试仍绿（H2 档 + 真库档），Flyway 迁移成功、无 pending。

### M2 · barrier 领域并入 biz（决策④⑤⑥）
- 交付：
  - 复制 barrier 领域代码到 `com.lrs.buddy.biz.barrier.*`：core(BarrierEngine/ScheduleComputer/StrategyResolver/BarrierStore 端口/views/specs/异常)、infrastructure(DbBarrierStore/BarrierMetrics)、mapper、model.entity(Barrier/Strategy/SchedulePoint/BarrierStatus/BarrierEvent)、web(controller/service/dto/vo)、scheduler(BarrierScheduler/StartupRunner/Config/ShedLockConfig)。
  - 实体改继承 `com.lrs.buddy.framework.common.model.BaseEntity`；改用 buddy `R`/`ResultCode`(+CONFLICT)/`@OperateLog`/`SecurityUtils`/`UserContext`；`@Version` CAS 与 BaseEntity 的 version 对齐。
  - barrier 业务表 → Flyway `V3__barrier_business.sql`；`shedlock` → `V4`；barrier 菜单/权限 → `sys_menu`+`sys_role_menu` 种子（新 Flyway 版本）。
  - 丢弃 barrier 重复脚手架；barrier 领域测试迁入 buddy 测试树并适配（buddy 的 AbstractIntegrationTest/application-test.yml）。
- 验证门：✅ **已完成**——H2 档 69 绿、真库档 74 绿（含 MySQLConcurrencyTest 真 InnoDB CAS 2/2），JaCoCo 达标；`barrier:*` 权限受控、审计走 buddy @OperateLog。

### M3 · 多租户框架能力（决策③⑥）
- 交付：`com.lrs.buddy.tenant`：`TenantContext`(ThreadLocal + `runAs`)、`TenantLineHandler`+接入 `MybatisPlusConfig`(顺序：tenant→pagination→乐观锁→防全表)、`TenantProperties`(`buddy.tenant.enabled/column/ignore-tables`)、`@IgnoreTenant`；`LoginUser.tenantId` + JWT claim；`JwtAuthenticationFilter` 填充 `TenantContext`；`AsyncConfig` `TaskDecorator` 传播 user+tenant；`V5__tenant.sql` 加 `tenant_id`（含 `sys_*`，默认租户）；调度线程用 `TenantContext.runAs`/`@IgnoreTenant`。
- 验证门：✅ **已完成**——TenantContextTest(7)+BuddyTenantLineHandlerTest(8)+TenantIsolationTest(4，独立库实证 SQL 改写/隔离/@IgnoreTenant/默认回退/跨线程传播) 全绿；H2 92 + 真库 97 双绿、JaCoCo 达标。附带修复既有审计缺陷（见变更日志）。

### F · 前端（buddy-ui）
- 交付：barrier 页面（仪表盘/各杆状态 + 手动开合、杆 CRUD、策略 CRUD + N:M 绑定、计划点 CRUD、事件流、审计页）；API 客户端；按 `sys_menu.perms` 做菜单/按钮级显隐 + 动态路由；barrier 菜单并入后端种子。Vitest 单测。
- 验证门：✅ **已完成**——`vue-tsc --noEmit`+`vite build` 绿(8.36s)；Vitest 12/12 绿（helpers 6 + 契约 3 + permission store 3）；契约检查已扩展为扫描全部 Flyway 迁移，barrier 权限码(V5)与前端引用对齐。交付 api/barrier.ts + 3 页面(杆管理/策略/计划点) + helpers。

### Q · E2E + 压测
- 交付：Playwright E2E（登录→仪表盘→手动开合→看事件/审计→权限显隐）；JMeter/k6 压测（调度并发 + "手动撞定时"竞态，验证三层锁 + CAS 在压力下不破）。
- 验证门：✅ **已完成**——Playwright E2E 2/2 绿（`workers:1` 串行，避免 admin 会话互踢）；JMeter 竞态压测 320 样本 0 错误（三层锁 + CAS 在压力下不破）；k6 脚本交付（本机未装 k6，登记简化）。

### D · 打包部署 + CI
- 交付：`Dockerfile`（多阶段构建可执行 jar）+ `docker-compose.yml`（MySQL+Redis+app）+ prod profile（JWT/DB 密钥外置走环境变量、HTTPS/反向代理说明、日志 UTF-8、Actuator 暴露收敛）+ CI 扩展（backend/frontend/mysql-consistency/e2e/security-scan）+ 部署脚本。
- 验证门：✅ **已完成**——jar 重新构建；prod profile 用真实 jar + MySQL(`buddy_prod`) 本地实证：Flyway 迁移至 v6、硬化生效（`env` 不暴露、springdoc/swagger 关闭、Actuator `health` 匿名 + `prometheus/metrics` 管理员）、admin 登录 + barrier 业务 + RBAC 全绿；本机无 Docker → 镜像构建交 CI docker job（登记简化）。

### Doc · 文档收口
- 交付：**重写 barrier 过时 docs**（现仍写"单杆/synchronized 单锁/独立工程/不做多租户"）→ `buddy/docs`：架构（框架+样例、多杆 N:M、三层并发、多租户、Flyway、RBAC、审计、可观测）；`README`（框架能力清单 + 如何基于框架起新项目 + barrier 样例说明 + 部署）；business-plan/tech-design/design-rationale 对齐最终。
- 验证门：✅ **已完成**——新增 `README.md`（仓库入口：monorepo 结构 + 框架能力清单 + 快速开始 + 构建验证 + 生产部署 + CI）、`buddy/docs/architecture.md`（框架分层与横切能力：双层响应契约/双链安全/MyBatis-Plus 拦截器链/Flyway V1–V6/多租户 opt-in/审计/可观测/异步上下文传播/测试质量门）、`buddy/docs/barrier-sample.md`（样例：多杆领域模型/决策纯函数/三层并发/接口与权限/端口-适配器/边界）。均已逐项校对实际代码，无"独立工程/单杆/单锁/不做多租户"等过时表述；business-plan/tech-design/design-rationale 已收敛并入 barrier-sample.md。附带修正 `ShedLockConfig` 陈旧注释（shedlock 表 V8→Flyway V4）。

### Final · 全量自证
- 交付：buddy `mvn verify`（H2 档）全绿 + 真库档全绿；前端 build+Vitest 绿；JaCoCo 达标；生产可部署检查（jar 启动 + prod profile）；`Desktop\barrier` 退役说明；**所有简化/阻塞项汇总登记**。
- 验证门：✅ **已完成**——后端 H2 档 `mvn verify`：**95 用例全绿**（0 失败/0 错误）、JaCoCo 行覆盖 ≥45% 达标、BUILD SUCCESS；后端真库档（`buddy_it`）：`MysqlConsistencyTest` 3 + `MySQLConcurrencyTest` 2（真 InnoDB 跨实例 CAS）**全绿**；前端：Vitest **12/12** 绿、`npm run build`（vue-tsc 类型检查 + vite）**绿**；生产可部署：真实 jar + MySQL(`buddy_prod`) 本地实证（Flyway→v6、硬化、admin 登录、barrier 业务、RBAC、带令牌抓 Prometheus 均绿）；`Desktop\barrier` 已加退役横幅（指向 biz.barrier 与新文档）；临时文件已清理（含密钥的临时脚本/生成物已删，`.gitignore` 补 `*.jtl`/`uploads*/`）；简化/阻塞项已汇总于第 4 节。

## 3. 生产可部署标准（用户要求"可直接部署生产"）
- 后端：可执行 jar 构建成功；prod profile 密钥全外置（无硬编码）；Flyway 生产迁移可用；Actuator 健康探针 + Prometheus 指标；日志 UTF-8。
- 数据：MySQL 8 迁移链完整可重放；种子数据（管理员/菜单/角色/租户默认）就绪。
- 交付物：Dockerfile + compose + CI + 部署脚本 + 文档齐备。
- 未能在本机自证的（如无 Docker 的镜像构建），在"简化/阻塞记录"明确登记，不隐藏。

## 4. 简化 / 阻塞记录（尝试≥2 次仍不成才简化，逐条登记）
| 时间 | 位置 | 问题 | 尝试 | 采用的简化 | 影响 |
|---|---|---|---|---|---|
| 2026-09-29 | D · Docker 镜像 | 本机环境未安装 Docker，无法本地 `docker build` / `compose up` 实证容器栈（属环境缺失，非尝试失败） | 探测确认无 Docker 运行时 | 交付 `buddy/Dockerfile`+`buddy-ui/Dockerfile`+`docker-compose.yml`+`nginx.conf`+`.env.example`+两份 `.dockerignore`，镜像构建与编排交 CI `docker` job 及目标环境验证 | 本机未跑起容器栈；但 prod profile 已用真实 jar + MySQL 本地实证（Flyway→v6、硬化、鉴权、业务全绿），Dockerfile/compose 经逐项 review |
| 2026-09-29 | Q · k6 压测 | 本机未安装 k6，脚本无法本地执行出报告 | 探测确认无 k6；改用已装 JMeter 承担压测实证 | 交付 k6 脚本 `load/barrier-race.js`（未本地执行），压测实证由 JMeter 完成（320 样本 0 错误） | k6 脚本未经本地执行验证，压测场景与 JMeter 一致（登录→随机开/合竞态打同一杆） |
| 2026-09-29 | swap · 技术选型 | 换电柜需 IoT 中间件（EMQX / TDengine / RocketMQ），本机实测**无 Docker**（MySQL 8.0.44 + Redis 5.0.14 + JDK 17 可用），而 EMQX 与 TDengine 3.x 服务端均无 Windows 原生发行版 → 主链路依赖它们就无法本地与 CI 自证 | 逐项核实环境可用性与各中间件的 Windows 支持情况 | 采用“**能力端口化 + 本地可跑等价件 + 生产可换实现**”：嵌入式 Java Broker（Vert.x MQTT / Moquette）、Transactional Outbox + Redis Stream、MySQL 分区表；并刻意**不依赖 Broker 私有特性与离线队列** | 主链路与故障路径均可本机/CI 实证；代价是本地侧不展示真集群。**取舍登记已集中至 `swap-plan.md` §7（A 类我方选择/B 类加严项）；原 8 项已重分类** |
| 2026-09-30 | M1 · 本地 Broker 选型 | 目标：本地/CI 用可嵌入纯 Java Broker 跑通 MQTT 5 接入。实测：IotTransportTest 连上 CONNECT 阶段即失败——Moquette 0.17 对 MQTT 5 CONNECT 回的 CONNACK 无法被标准 v5 客户端（HiveMQ MQTT Client）解码，报 `MqttDecodeException: Exception while decoding CONNACK: wrong reason code`；排除自身认证因素后（已把内部客户端口令拆为 `InternalClientSecrets` 单独实现并校验 `cleanStart=true`）仍复现 | 先试 Moquette 0.18/0.19（仓库不存在该版本）、再排除会话缓存与云侧认证路径差异 | **暂定 `buddy.iot.enabled=false` + `IotTransportTest` 标 `@Disabled` 并写明原因**；接入层改为 **Vert.x MQTT Server**（纯 Java、可嵌入、Apache-2.0、v5 支持完整），完成后去掉注解并把默认值改回 true | **已解决（2026-09-30）**：接入层换为 **Vert.x MQTT Server**，`IotTransportTest` 5 个用例全部跑绿（真 TCP + 真 Broker + 真 H2），`buddy.iot.enabled` 默认值已恢复 true、测试档统一关闭 |

## 5. 变更日志
- 2026-09-29：初版。基于"buddy=框架、barrier=样例并入 biz、先迁移后开发"重排；旧计划（多租户/前端/E2E+压测/部署/文档）并入本表，前置 M0–M3 合并块，API 健壮性(分页/OpenAPI/幂等)因 buddy 已具备而取消。
- 2026-09-29：M2 完成——barrier 43 领域文件并入 `com.lrs.buddy.biz.barrier`、丢重复脚手架、V3/V4/V5 迁移、控制器改用 @OperateLog；测试移植适配 buddy 的 adminToken/JWT 约定；H2 69 + 真库 74 双绿。
- 2026-09-29：M3 完成——`com.lrs.buddy.tenant` 框架级多租户（opt-in 默认关闭）、V6 预留 tenant_id、JWT/LoginUser/AsyncConfig(TaskDecorator)/调度(@IgnoreTenant)接入；H2 92 + 真库 97 双绿。
- 2026-09-29：**修复既有框架缺陷**——OperateLogAspect.truncate 旧实现 `substring(0,2000)+"..."` 得 2003 字符，撑爆 oper_param/json_result 的 VARCHAR(2000)，导致审计（如杆手动控制）异步写库静默失败、审计记录丢失；改为为省略号预留长度（总长恰 2000），加 OperateLogAspectTest(4) 回归。此坑在 M3 给 LoginUser 增 tenantId、参数序列化变长后触发暴露（H2 与 MySQL 严格模式均会失败）。
- 2026-09-29：F 完成——buddy-ui 新增 api/barrier.ts、杆管理/策略/计划点三页面（按 sys_menu.perms 做按钮级显隐 + 动态路由）、展示 helpers；permission-contract.spec 扩为扫描全部迁移；vue-tsc+vite build 绿、Vitest 12/12 绿。
- 2026-09-29：Q 完成——Playwright E2E 2/2 绿（`workers:1` 串行，规避 auth.spec 登出使 Redis 会话失效踢掉 barrier.spec 令牌的并行互扰）；JMeter 竞态压测 320 样本 0 错误（修正 `__chooseRandom` 非法函数→CSV 数据源、断言 AND 语义→单模式 NOT-contains `code:500`）；k6 脚本交付。
- 2026-09-29：D 完成——`application-prod.yml`（密钥全外置无弱默认、Flyway `clean-disabled`、springdoc 关闭、Actuator 收敛 health/info/prometheus/metrics、日志 UTF-8）；`buddy`/`buddy-ui` 双 `Dockerfile` + `docker-compose.yml`（mysql8+redis7+backend+nginx）+ `nginx.conf`（SPA 回退 + /api 反代）+ `.env.example` + 两份 `.dockerignore`；CI 扩展 `docker` 构建 job + `trivy` 安全扫描 job。prod profile 本地实证：真实 jar 连 MySQL `buddy_prod`、Flyway→v6、硬化生效、admin 登录 + barrier 业务 + RBAC 全绿。
- 2026-09-29：**修复既有框架缺陷（可观测性）**——`SecurityConfig` 的 Actuator 过滤链（`@Order(1)`）遗漏装配 `JwtAuthenticationFilter`，而该链要求 `hasRole("ADMIN")`；因 Spring Security 多条过滤链彼此独立、主业务链（`@Order(2)`）的 JWT 过滤器不作用于本链，令牌根本不被解析 → 即便持有效管理员令牌访问 `/actuator/prometheus` 也被挡 401，Prometheus 指标实际无法抓取、可观测性端点形同虚设。修复：actuator 链补装 JWT 过滤器；加 `ActuatorSecurityTest`(3) 回归（health 匿名 200 / metrics 匿名 401 / metrics 带管理员令牌 200）。prod 实证：带令牌抓取 `/actuator/prometheus` 返回 200、含 `jvm_*` 与自定义 `barrier_*` 指标（42KB）。
- 2026-09-29：Doc 完成——新增仓库 `README.md` + `buddy/docs/architecture.md`（框架横切能力）+ `buddy/docs/barrier-sample.md`（多杆样例，收敛并重写旧 barrier 的 business-plan/tech-design/design-rationale，纠正为多杆/三层并发/已并入框架）；均校对实际代码。附带修正 `ShedLockConfig` 陈旧注释（V8→V4）。
- 2026-09-29：**Final 全量自证完成（全部验证门绿）**——后端 H2 `mvn verify` 95 用例全绿 + JaCoCo 达标；真库 `buddy_it` 5 用例全绿（含 MySQLConcurrencyTest 真 InnoDB 跨实例 CAS 2/2）；前端 Vitest 12/12 + build 绿；prod profile 真实 jar + MySQL `buddy_prod` 实证。清理临时文件（删除含密钥的 `start-prod.ps1`/`prod-verify.ps1`、`result.jtl`、各 session 日志；`.gitignore` 补 `*.jtl` 与 `uploads*/`）；`Desktop\barrier` 加退役横幅（标记已被 `com.lrs.buddy.biz.barrier` 取代、指向新文档，保留作历史回退参照，未删除整目录以免不可逆）。本次会话共修复两个既有框架缺陷（审计截断溢出、Actuator 链缺 JWT 过滤器），均附回归测试。
- 2026-09-29：**详细自检（用户要求复核）发现并修正 3 处**——① `security-scan` CI job 原 `scanners: vuln,secret,misconfig` + `exit-code:1` 会因仓库合法 dev 固定串（`Admin@123456` 于 test/E2E/k6/README、`change-me` 默认、`.env.example` 占位）触发 secret/misconfig 误报而首跑即红；改为 CRITICAL 依赖漏洞硬门禁 + secret 非阻断报表（真实密钥防护归 pre-commit）。② 前端登录页 `login/index.vue` 原硬编码预填 `admin/Admin@123456` 且页面显式口令提示——生产构建会泄露种子口令；改用 `import.meta.env.DEV` 仅开发模式预填/提示，生产构建表单为空（重跑 `npm run build` 绿、2283 模块/9.2s；两 E2E spec 均显式 `.fill()` 用户名+密码，不受影响）。③ `Desktop\barrier` 旧 business-plan/tech-design/design-rationale 补退役横幅（此前仅 README 加了）。核对：V5 权限码与文档一致、application-prod.yml 密钥无默认、sx 工作区无 `com.lrs.barrier`/旧路径残留。
- 2026-09-29：**目录结构整理（"框架+样例"清晰化，用户要求）**——`biz/barrier` 统一为与 `framework/modules/*` 同构的标准分层（落实决策④的 controller/service/scheduler 命名意图）：`web/` 拆分 → `controller/` + `service/` + `model/{form,vo}`（`XxxVo` 统一 `XxxVO`）；`model.entity` → `entity/`；`config/` 只留 `BarrierConfig`，`BarrierScheduler`/`BarrierStartupRunner` 归 `scheduler/`；`ShedLockConfig` 上移 `framework/config`（跨实例调度锁=框架能力，与决策②定位一致）。测试包同步（`web/`→`controller/`）；自证：H2 档 95 + 真库档 5 双绿、JaCoCo 达标、HTTP 契约不变（前端/E2E 无感）。顺带清理运行残留：`restart-ui.ps1` 日志改道 `buddy-ui/.logs/`（`.gitignore` 补 `.logs/`）、删 `buddy/uploads` 测试产物与 `uploads-prod` 空目录、删 `pnpm-lock.yaml`（全仓统一 npm）。
- 2026-09-29：**framework/common 结构整理（用户要求）**——common 包根零散类清零：`R`/`ResultCode` → `response/`、`BusinessException`/`GlobalExceptionHandler` → `exception/`、`BaseEntity`/`PageQuery`/`PageResult` → `model/`（与既有 `DataScopeQuery` 聚齐，解决其继承 `PageQuery` 却分居两包的不一致）；测试镜像归位（`RContractTest` → `response/`、`BaseEntityTest` → `model/`），修复拆包导致的测试同包裸引用失效。评估后维持不动：config/security/tenant 平铺与 modules 门面类（Quartz 入口、DataInitializer）——各自围绕单一概念、非无规则散放。自证：H2 档 95 + 真库档 5 双绿、JaCoCo 达标；运行验证（dev 档真实启动）：Flyway→H2、启动对齐、health UP、admin 登录、`/api/barriers` 带令牌查询全通。
- 2026-09-29：**启动新业务 `biz/swap`（换电柜）· M0-1 定稿**——新增 `buddy/docs/swap-protocol.md`（设备接入与通信协议规范）：三层标识与设备树（电池为独立设备、仓位为柜机子资源）、QoS 分级与两条独立性原则（不依赖 Broker 私有特性 / 不依赖 Broker 离线队列）、统一信封与双密钥 HMAC 签名、有效期不信设备时钟、指令矩阵与两条铁律（副作用指令不自动重试 / ACK ≠ 动作发生）、含 `UNCONFIRMED` 的指令生命周期、上行 10 步校验链（含“重复指令重放历史结果”）、会话隔离防串单、超时双保险、跨实例路由薄抽象、错误码与 `ResultCode` 映射、双层安全、影子适用边界、Outbox/去重/遥测端口/物模型/可观测件、协议演进与 golden-file 兼容测试、模拟器 16 项故障注入目录、**8 项简化取舍登记**（见该文档 §14 与本文 §4 末行）。定位：barrier 回归“框架用法示例”，换电沉淀的通用能力回流升级 `framework/*`（iot / event / statemachine / idempotent / telemetry 端口）；不做微服务（用户决策）；C 端换电全链路以浏览器 H5 页面承载，**业务完整性不削减**。
- 2026-09-29：**swap M0-2 定稿**——新增 `buddy/docs/swap-order-fsm.md`（换电订单状态机与异常补偿规范）：确立**四套状态机分层与所有权**（订单/步骤/指令/资产，指令态变更永不直接改订单态）、事件可信度五级阶梯（**用户声明不推进状态；超时只能得出“未知”而非“未发生”**）、15 个订单态（`REJECTED`/`ABORTED`/`FAILED_MANUAL` 三终态职责分离）+ S1–S6 步骤、**39 条完整迁移**、**22 行异常矩阵 + 拒收双分岔**（重点：FI-01 超时≠未发生、FI-11 电池 `PENDING_PICKUP` 责任边界、FI-13 钱按成功物按异常、FI-14 按“用户手上有没有电池”而非订单态一刀切）、8 条不变式各自配 DB 生成列唯一约束与对应测试（**不变式必须落在数据库约束上，不能只落在应用代码里**）、**四层并发**（柜分段锁 + ShedLock + `@Version` CAS + **DB 唯一约束为最终防线**）与超时双保险幂等、实现形态（`framework/statemachine` 骨架 + 代码内迁移表，**明确不做可配置状态机**）、6 项明确不做及理由、7 层验证门槛（含“压力下不变式违反数必须为 0”）。用户拍板：**B1** 权益扣减发生在 `TAKEN` 之后、**B2** `SUSPENDED` 允许用户自助恢复 1 次但仅触发反查、**B3** 单用户在途订单硬上限 1 笔。
- 2026-09-29：**swap M0 文档层收口（用户定“范围不削减”后的重排）**——新增 `buddy/docs/swap-plan.md`（项目内计划单一真相源：总原则 P1~P5、M0~M9 里程碑与验证门、依赖顺序约束、**框架能力回流节点表**、外部硬约束四项各附最强替代、**取舍登记唯一维护处**）与 `buddy/docs/swap-simulator.md`（`buddy-sim` 独立工程规范：三条不入 buddy 的硬理由、**零代码共享原则与 `protocol/` 共享数据资产**、L0/L1/L2 能力分层、Vert.x 选型及其代价、**正确性边界声明**、故障注入三要件、控制面、**双端可归因指标与归因矩阵**、入网注册、模拟器自身五项强制测试）。
  修订：协议新增 §5.4 电池身份核验与防伪（挑战应答，强/中/弱三级证据及可用边界）、§12 补二进制 codec 必逐字段过同一套 golden 样本、§13 改为“故障注入目录”（16 项语义不删）并指向独立工程文档、§14 取舍整表迁出、§15 原六项遗留逐条定案；
  FSM 新增 §4.5 电池身份与归属证明（三层归属分离 + 五级裁决表 + 跨设备观测冲突 O1..O5 + 归属变更入口收敛）、新增不变式 **I9/I10** 与三张表、**删除 §10.2“明确不做”整表**改为“边界归属”（九项逐项给里程碑与技术前提）、§11 补双端归因/身份裁决/观测冲突三层门槛、§12 分配策略定案（门槛不砍 + 3 档优先级链 + 惩罚项实现）。
  **结构性变化**：由“先云侧后设备侧”改为**双轨互为对手方同步**（M1 同期交付 L1 与 FI-01/02/04/10/13）；原登记为简化的二进制帧与设备认证增强分别升为 **M8/M9** 正式里程碑；本文 §4 的 swap 选型行已改指新总账。
- 2026-09-29：**swap M0-3 定稿（表结构与 DDL）**——新增 `buddy/docs/swap-ddl.md` 与迁移 `V7__iot_infrastructure`（11 表）/`V8__swap_ledger`（8）/`V9__swap_order_flow`（6）/`V10__member_right`（5）/`V11__swap_menu_seed`（权限码 6000+ 段），共 30 张新表 + 换电演示种子。
  **两路实测自证**：H2 2.2.224（`MODE=MySQL`）与 MySQL 8.0.44 按 V1→V11 全量执行均 exit 0；`mvn verify` **95 用例全绿 + JaCoCo 达标 + BUILD SUCCESS**；真库档 `MysqlConsistencyTest` 3 用例全绿（Flyway 落到 v11）；
  MySQL 端核实 6 处 `VIRTUAL GENERATED` 生成列、56 条 CHECK、50 表；**四条负例均被拒**（站点放宽 SOC 门槛 / 同电池双 ACTIVE 绑定 / 同仓位双 ACTIVE 预占 / 同用户双在途单）+ 非法枚举被拒，**一条正例通过**（订单进终态后同用户可重建单）——只证约束会拦不够，必须同时证它能释放。
  踩到并记录的坑：**H2 即使 `MODE=MySQL` 也不支持 `IF()`**，因此生成列表达式全部改写为 `CASE WHEN` 且不写 `STORED`（已反向修正 FSM/协议文档中的示例）；另 `CHECK` 无法跨表→“站点只能收紧门槛”必须冗余型号默认值到站点表，漂移风险已登记待 M1 一致性校验。
  设计调整：分区 DDL 不进 Flyway（一次性迁移无法承担持续滚动），改由 M1 的 `TelemetryPartitionJob` 维护；`iot_telemetry` 主键设 `(id, occurred_at)` 提前做到分区就绪。`member_right` 提前至 V10（M2 guard 硬前置）。
- 2026-09-29：**swap M0-5 契约测试交付**——新增 `src/test/java/com/lrs/buddy/contract/SwapDdlContractTest.java`（纯文件解析、不启 Spring、毫秒级，7 项断言）：
  ① `@enum` 标记→表/列存在且与 CHECK 集合逐元素相等；② 反向：有 CHECK 枚举必有 `@enum`；③ FSM 订单态 == `order_state` DB 枚举；
  ④ **FSM 非终态集合 == `swap_order.active_user` 生成列在途列表**（防“漏 SUSPENDED 可刷双单”与“多算终态永久无法建单”两类事故）；
  ⑤ 步骤码/步骤态 == `swap_order_step` 两枚举；⑥ FSM 引用的 `evt:*`/`dispatch(CMD)` 均在协议清单内；⑦ 源码 `hasAuthority` 权限码 ⊆ Flyway 种子。
  **首跑即抽出一处真实漂移**：DB 步骤态含 `SKIPPED` 而 FSM “步骤态”行漏声明——已反向修正文档并补齐 `CONFIRM_PENDING`/`SKIPPED` 两个状态的语义与“不得静默消失”约束。
  自证：`mvn verify` **102 用例全绿**（95 + 新增 7）+ JaCoCo 达标 + BUILD SUCCESS。`swap-ddl.md` §10 与 `swap-plan.md` M0-5 同步。
- 2026-09-29：**swap M0-4 定稿（C 端会员身份域）→ M0 收口**——新增 `buddy/docs/swap-member-auth.md` 与 `V12__member_auth`（3 表 + 3 条生成列不变式 + `member_user` 补列 + 3 个权限码）。
  要点：两域分离（骑手无权限码、只有权益）；**串域必须 401 而非 403**（403 = 已进入后台权限判定路径，是可断言的串域前兆）；
  独立密钥（缺失即启动失败）+ `typ` 分派 + 第三条过滤链（并显式警惕“每条链自装过滤器”这一框架历史缺陷的重发点）；
  access 2h + refresh 30 天一次性轮换与**复用检测即整族撤销**；多设备类型并存、同类型互斥（DB 生成列）；
  实名强制（申请表为真相、`member_user` 为投影）、验证码只存哈希且同手机号同用途仅一条 PENDING（防轰炸与多码并存）、注销双前置（跨表校验收不了 CHECK，已诚实标注为服务层守卫）；
  C 端 `displayState` 五态契约（内部状态名不下发、`UNKNOWN` 不显示失败）——协议 §15 最后一条遗留就此闭环。
  自证：H2 V1→V12 全量 `exit=0`、`mvn verify` **102 全绿 + JaCoCo 达标**、真库档 5 用例全绿（落到 v12）、
  MySQL 上 8 项 V12 约束用例全部符合预期（含 4 项**正向对照**）；库内生成列 9 处、CHECK 65 条、业务域 33 表。
  环境事件（已写入 `swap-ddl.md` §11）：`mvn verify` 一度因**本机 Redis 服务处于 Stopped** 导致 `SysUserApiTest.repeat_submit_blocked` 失败（30s 超时→`code:500`），属环境前置而非回归；因无权限启服务，改用 `redis-server.exe` 拉起后重跑至全绿。
- 2026-09-30：**M1 开工（块 1：设备接入垂直切面）——发现并记录 Broker 选型阻塞**——
  已落地代码：`framework/iot` 的 `transport`（`BrokerLifecycle` SmartLifecycle 生命周期、`MqttTopics` 主题类型化解析与 `matches`、
  `MqttSecurityPolicies` 认证与主题级 ACL、`InboundRouter` 十步校验链与监听器分发、`DedupService`、`CloudMqttLink` 标准客户端接入）、
  `envelope`（`Envelope` 全字段、`PayloadCodec` SPI、`JsonPayloadCodec` 含签名用的递归规范化）、
  `security`（`DeviceSecrets` 派生密钥与签名、`DeviceCredentialService` 设备认证、`InternalClientSecrets` 云侧内部口令）、
  `repo`（`DeviceDirectoryDao`/`IngestDao`，接入层统一走 JdbcTemplate 而非 MP，避开全局逻辑删除与复合主键）、
  `framework/config/IotTransportConfig` 装配与 `buddy.iot.*` 配置；新增依赖 `io.moquette:moquette-broker:0.17`（排除 slf4j-reload4j）
  与 `com.hivemq:hivemq-mqtt-client:1.3.3`。
  同时写 `IotTransportTest`（真 TCP + 真 Broker + 真 H2，5 个用例：正确口令可连入并走完分发、错口令拒绝、重复 msgId 只分发一次、
  伪造签名不分发且留 E1001、订阅他人主题被 ACL 拒绝）。
  **它的第一个发现就是选型本身**：Moquette 0.17 与 MQTT5 客户端在 CONNACK 上不兼容，已记入 §4（标 `@Disabled` 而非删测试，
  开关默认 false 而非“装作可用”），下一步换 Vert.x MQTT Server。
  自证：`mvn test` **102 用例全绿**（新测试已 `@Disabled`，不拉低基线；上下文能加载即证明新增 Bean 装配无环）。
- 2026-09-30：**M1 块 1–2 完成：Broker 换型 Vert.x MQTT Server 并跑绿验收**——
  换型后连续踩到三个坑，全部定位并修好，且每个坑都留下了可讲清的注释：
  ① **拒因码分版本**：netty 的 `MqttConnectReturnCode` 把 v3 与 v5 拆成两套常量，
     用 v3 的 `NOT_AUTHORIZED`(0x05) 回给 v5 客户端会导致客户端报"无法解析 CONNACK"而不是"认证失败"，
     错因被误导性文案盖住（**与 Moquette 的死因同一类**）；已改用 `NOT_AUTHORIZED_5`(0x87)。
  ② **主题解析按完整主题分段**：早期实现去掉了 `swap/v1` 前缀再数段数，导致所有上行被误判为未知主题（E0003）；
     发现它靠的是测试失败时把 `reject_code/reject_step` 一并报出来——**可诊断的失败与不可诊断的失败，差价就是一个下午**。
  ③ **v5 连接必须用带属性的 accept**，且处理器要装在 CONNACK 之前；否则 Vert.x 报
     "Received an MQTT packet from a not connected client"，客户端则永远等不到 CONNACK 而超时。
  另外补上 `publishAutoAck(true)`（不回 PUBACK 会让设备 QoS1 发布死等）、
  云侧链路只在 `mode=client` 启动（embedded 模式下再起订阅会造成重复投递）。
  新增 `EndpointRegistry`（本节点端点表 + Redis 路由记录，重连时旧连接的关闭回调不能误新会话）、
  `DeviceSessionService`（ONLINE/STALE/OFFLINE 三态 + 连续多轮静默才判离线，不信 LWT）。
  自证：`mvn test` **107 用例全绿（0 fail / 0 skip）**，其中 `IotTransportTest` 5 个为真 TCP 集成用例。
- 2026-09-30：**swap M1 整阶段完成（设备接入双轨）**——
  云侧：`framework/iot`（transport / envelope / security / repo / command / session / telemetry / model / maintenance / error / config）
  + `framework/event`（Outbox 与 Redis Stream 分发）+ `framework/statemachine`（骨架）；接入层统一走 JdbcTemplate（避开全局逻辑删除与复合主键）。
  设备侧：新建独立工程 **`buddy-sim`**（零代码共享：自带协议实现、十步校验链、柜机物理模型、5 项故障注入、CLI）
  + 仓库根 **`protocol/v1/samples/`**（3 份跨端 golden 样本，两侧各自独立断言，两侧都对才算约定成立）。
  CI：新增 `device-sim` 与 `protocol-interop`（mosquitto 当第三方裁判，防“自研 server 与自研 client 彼此自洽但不合标准”）。
  设计反向修正两处（均因实测/测试发现，不是改口）：协议 §6 步序改为 **msgId 去重优先于 nonce**
  （否则 QoS1 正常重投会被当攻击拒绝且不回应答，云侧误判超时）；§3.2 **不再由 Broker 注入 brokerTs**
  （有效期改由签名信封内的 issuedAt/expireAt 承载，否则绑死单一 Broker 的属性转发能力）。
  自证：`buddy` **120 用例全绿 + JaCoCo 达标**；`buddy-sim` 15 用例全绿；真库档 3 用例全绿；前端 12/12 绿；
  其中 M1 新增的真实链路证据：正确口令连入并分发留痕、错口令拒、重复 msgId 只分发一次、伪造签名不分发并留 E1001、
  越权订阅被 ACL 拒、指令下发-应答-超时-迟到应答纠正、跨用例唯一约束隔离、模拟器自身校验链 10 项。
  两个由测试抽出的真 bug已修：`!putIfAbsent(...)` NPE；nonce 与幂等检查顺序倒置。
- 2026-09-30：**CRITICAL 依赖漏洞闭环（含方法论修正）**——M1 推送后 `security-scan` 连续两轮红。
  第一轮我只从日志拿到 `Total: 1 (CRITICAL: 1)`，table 表体被日志输出形式吃掉，**不知道包名只能猜**：
  我先猜 netty 把 buddy-sim 对齐到 4.1.135 —— 白跑一轮（仍红）。第二轮改为**先让门禁自己报出包名**：
  给 Trivy 加 `format: json` + `output:` 并接一个 `if: always()` 的打印步骤，立即得到
  `CVE-2026-75595，fixed by 4.2.17.Final, 4.1.137.Final`（netty）。
  处置：buddy-sim 逐件抬到 4.1.137（本地镜像无 netty-bom 4.1.137，但各件存在，所以显式条目写在 BOM 导入之前）；
  buddy 侧额外加 `<netty.version>4.1.137.Final</netty.version>`——
  **这一步不是为了让门禁变绿**：Trivy 扫 pom 只报声明过的依赖，buddy 没声明 netty 故从未报，
  但运行镜像里确实带着 4.1.135。“门禁未报”不等于“不存在”，这类差异必须主动补。
  同时顺手修了模拟器一个真 bug：`DeviceLink` 把 `ACCEPT` 分支的应答直接丢弃（发了指令永远收不到回复），
  开不了这个口就测不到幂等重放；FI-01/FI-04 在真 Broker 上行为已验（详见下条）。
  教训已写进记忆：**可诊断的门禁比严门禁重要，拿不到包名的安全门禁只会逼人猜**。
- 2026-09-30：**M1 补上设备侧的真 Broker 联跑证据**：新增 `buddy-sim/src/test/.../SimBrokerRoundTripTest`（5 例），
  设备连的是**与两侧实现都无关的 Vert.x MQTT 5 Server**（test 作用域，不引入 buddy 代码），真 TCP 跑：
  收指令→开门→回应答→发 `door_open` 事件；同 msgId 重复投递重放上次应答且门只开一次；
  FI-01（不应答）时**门已开**；FI-04（有 ACK 无事件）时 ACK 照到；伪造签名回 E1001 且不开门。
  这里又踩到与云侧同一个 PUBACK 坑（测试 Broker 忘开 `publishAutoAck` 时设备 QoS1 发布死等，
  症状是测试挂住而不是报错）—— 同一个坑两次在不同代码里重现，证明它值得写进文档而不只是注释。
  自证：`buddy-sim` **20 用例全绿**（原 15 + 新 5）；`buddy` **120 用例全绿 + JaCoCo 达标**。
  仍未做：两个独立进程的跨进程联跑（属 M3 混沌/场景 DSL 范畴），以及设备注册接口取密钥（M2）。
- 2026-09-29：**CI 结果核查与文档备案（用户要求）**——用 gh CLI 直连核查首次完整流水线（run 36565377941 · `225a079`）：`backend`/`frontend`/`mysql-consistency`/`e2e`/`docker` 五 job 全绿（其中 docker 为新增 job 首跑通过），唯一红为 `security-scan`——根因：`aquasecurity/trivy-action@0.28.0` 引用缺 `v` 前缀（该库 tag 为 `vX.Y.Z`，`0.28.0` ref 实测 404；修复过程见下条）。新增 `buddy/docs/ci.md`：流水线全景 / gh 查看与重跑手册 / 已知问题与修复 / 异地（服务器）能力对齐要点（不含任何凭据），README 文档索引同步。
- 2026-09-29：**security-scan 修复闭环（用户批准）**——补 `v` 前缀（commit 159d806）后仍红，暴露第二层根因：`trivy-action@v0.28.0` 内部 pin 的 `aquasecurity/setup-trivy@v0.2.1` tag 已被上游删除（嵌套 composite 引用失效）；改升 `trivy-action@v0.36.0`（内部改 pin setup-trivy 至 commit SHA / v0.2.6，不再受删 tag 影响；6 个在用输入已核对），commit 978360b 推送后 run 36567189359 **6/6 全绿**。ci.md §3/§4.1 同步修订为最终版并推送。教训：pin 第三方 action 时，嵌套引用链的间接依赖 tag 也可能被上游删除，优先选内部以 SHA pin 依赖的版本。
