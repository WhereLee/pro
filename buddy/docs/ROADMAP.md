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
- 2026-09-29：**CI 结果核查与文档备案（用户要求）**——用 gh CLI 直连核查首次完整流水线（run 36565377941 · `225a079`）：`backend`/`frontend`/`mysql-consistency`/`e2e`/`docker` 五 job 全绿（其中 docker 为新增 job 首跑通过），唯一红为 `security-scan`——根因：`aquasecurity/trivy-action@0.28.0` 引用缺 `v` 前缀（该库 tag 为 `vX.Y.Z`，`0.28.0` ref 实测 404；修复过程见下条）。新增 `buddy/docs/ci.md`：流水线全景 / gh 查看与重跑手册 / 已知问题与修复 / 异地（服务器）能力对齐要点（不含任何凭据），README 文档索引同步。
- 2026-09-29：**security-scan 修复闭环（用户批准）**——补 `v` 前缀（commit 159d806）后仍红，暴露第二层根因：`trivy-action@v0.28.0` 内部 pin 的 `aquasecurity/setup-trivy@v0.2.1` tag 已被上游删除（嵌套 composite 引用失效）；改升 `trivy-action@v0.36.0`（内部改 pin setup-trivy 至 commit SHA / v0.2.6，不再受删 tag 影响；6 个在用输入已核对），commit 978360b 推送后 run 36567189359 **6/6 全绿**。ci.md §3/§4.1 同步修订为最终版并推送。教训：pin 第三方 action 时，嵌套引用链的间接依赖 tag 也可能被上游删除，优先选内部以 SHA pin 依赖的版本。
