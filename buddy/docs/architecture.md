# buddy 框架架构

> 本文描述 **buddy 框架基座**的分层与横切能力；业务样例（升降杆）的领域细节见
> [`barrier-sample.md`](barrier-sample.md)。所有描述以仓库实际代码为准。

---

## 1. 分层与包结构

根包 `com.lrs.buddy`，单 Maven 模块（`com.lrs:buddy:1.0.0`）。框架侧与业务侧包平级：

```
com.lrs.buddy
├── framework/    通用基座（与业务无关，新项目直接沿用）
│   ├── common/       框架公共件：response（R 响应信封、ResultCode）、exception（BusinessException、
│   │                 GlobalExceptionHandler）、model（BaseEntity、PageQuery/PageResult、DataScopeQuery）、
│   │                 annotation/aspect/enums（@DataScope/@RepeatSubmit 家族）、sse、util（TreeUtils 等）
│   ├── config/       装配：SecurityConfig、MybatisPlusConfig、AsyncConfig、CorsConfig、JacksonConfig、
│   │                 MybatisMetaObjectHandler、OpenApiConfig、RedisConfig、ShedLockConfig（跨实例调度锁）
│   ├── security/     无状态鉴权：JwtTokenProvider、JwtAuthenticationFilter、LoginUser、TokenService、
│   │                 UserContext、SecurityUtils、认证/授权失败处理器
│   ├── modules/      框架自带通用模块：sys（用户/角色/部门/菜单/鉴权）、file、job、log、monitor、notice
│   └── tenant/       多租户框架能力（opt-in）：TenantContext、TenantProperties、BuddyTenantLineHandler、
│                     @IgnoreTenant、TenantIgnoreAspect、TenantConfig
└── biz/          ★ 业务/样例根。新项目替换或新增此包下的业务；当前样例为 biz.barrier
```

**复用契约**：`framework/*`（common/config/security/modules/tenant）是通用基座，保持通用、不含具体业务；
`biz` 是业务落点，其模块内分层与 `framework/modules/*` 同构（controller/service/entity/mapper/model/form|query|vo，
复杂领域可加 `core`/`infrastructure`，见 §8）。起新项目 = 保留 `framework` + 重写 `biz`。

---

## 2. 统一响应契约（双层语义）

所有 REST 接口返回统一信封 `R`（`framework/common/response/R.java`，Java record）：

```json
{ "code": 200, "message": "...", "data": { }, "timestamp": 1790000000000, "success": true }
```

- **业务信号在 `body.code`**：成功 200；校验失败 400；业务异常 500；冲突 409（`ResultCode.CONFLICT`）；
  强制下线/令牌失效等各有专属码。业务错误 **HTTP 状态恒为 200**，前端按 `body.code` 分派。
- **鉴权信号走真实 HTTP 状态**：401（未认证/令牌失效）、403（无权限）发生在 Spring Security 过滤器链，
  进入 `DispatcherServlet` 之前，由 `AuthenticationEntryPointImpl` / `AccessDeniedHandlerImpl` 就地写出。
- `GlobalExceptionHandler` 统一兜底：`BusinessException`→其携带码、`@Valid` 失败→400、
  `NoHandlerFoundException` 等→500 信封，避免堆栈外泄。

> 设计取舍：前端拦截器已按 `body.code` 契约实现（401/140x 跳登录），管理端 API 无第三方按 HTTP 码集成，
> 故保留"双层契约"而非纯 RESTful——两层语义各表其位、不冲突。

---

## 3. 安全架构

**无状态 JWT + RBAC**，`SecurityConfig` 装配**两条独立过滤链**：

| 链 | Order | 匹配 | 策略 |
|---|---|---|---|
| Actuator 链 | 1 | `EndpointRequest.toAnyEndpoint()` | `health`/`info` 匿名放行；其余需 `hasRole("ADMIN")` |
| 主业务链 | 2 | 其余全部 | `/auth/login` 与 Swagger 路径放行；其余 `authenticated()` |

两条链均 `SessionCreationPolicy.STATELESS`、关闭 CSRF（令牌走 `Authorization` 头、无 Cookie 会话，不具备 CSRF 攻击条件），
且**各自装配 `JwtAuthenticationFilter`**——Security 的多条链彼此独立，主链的过滤器不作用于 Actuator 链；
若 Actuator 链遗漏装配，则令牌不被解析、`hasRole("ADMIN")` 永不成立（`prometheus` 指标将无法抓取，此为已修复的历史缺陷，见 `ActuatorSecurityTest`）。

**鉴权流程**（`JwtAuthenticationFilter`）：取 `Bearer` 令牌 → `JwtTokenProvider` 解析 → 校验 Redis 在线台账
（`TokenService.isTokenValid(userId, jti)`，支持**强制下线**：jti 不一致即失效）→ 每次请求实时加载权限
（`menuService.userPerms`，权限变更立即生效）→ 构造 `LoginUser` 放入 `SecurityContext`，并填充
`UserContext`（供审计字段自动填充）与 `TenantContext`（供租户拦截器）→ `finally` 清理 ThreadLocal（容器线程复用，防串号）。

**主体 `LoginUser`**：超级管理员额外获得 `ROLE_ADMIN`（供 Actuator 这类按角色控制的场景），
并携带全部权限码（`selectPermsByUserId(userId, true)` 不走 join 直接返回全量），故管理员天然拥有所有 `biz` 权限。

---

## 4. 数据层

`MybatisPlusConfig` 装配拦截器链，**顺序敏感**：

```
[多租户 TenantLineInnerInterceptor（仅 buddy.tenant.enabled=true 时，必须最先）]
 → 分页 PaginationInnerInterceptor(DbType.MYSQL)
 → 乐观锁 OptimisticLockerInnerInterceptor（@Version CAS）
 → 防全表更新/删除 BlockAttackInnerInterceptor
```

`BaseEntity`（`framework/common/model/BaseEntity.java`）统一承载：`createBy/createTime/updateBy/updateTime`（审计）、
`remark`、`version`（乐观锁）、`delFlag`（逻辑删除）。`MybatisMetaObjectHandler` 在 insert/update 时
从 `UserContext` 自动填充审计字段。逻辑删除由 MyBatis-Plus 全局配置（`logic-delete-field=delFlag`）。

`JacksonConfig` 将 `Long` 序列化为字符串，规避 JS `Number` 53 位精度丢失（前端 ID 一律 string）。

---

## 5. 数据库演进（Flyway）

版本化迁移取代早期 `schema.sql`/`data.sql`，脚本在 `classpath:db/migration`，**H2（`MODE=MySQL`）与 MySQL 8 共用同一套**：

| 版本 | 内容 |
|---|---|
| `V1__buddy_baseline` | 框架 `sys_*` 表基线（用户/角色/部门/菜单/文件/任务/日志/公告…） |
| `V2__buddy_seed` | 框架种子（角色、菜单、超级管理员等） |
| `V3__barrier_business` | barrier 样例业务表：`barrier`/`barrier_strategy`/`barrier_schedule`/`barrier_status`/`barrier_event`/`strategy_barrier` + 样例种子 |
| `V4__shedlock` | ShedLock 锁表（跨实例调度单例） |
| `V5__barrier_menu_seed` | barrier 菜单与权限码种子（`sys_menu` + `sys_role_menu`） |
| `V6__tenant` | 为业务表与 `sys_*` 表预留 `tenant_id`（默认租户 1）+ 索引 |

**铁律：已发布版本只增不改**——schema 演进一律新增 `V{n+1}`，保证任意环境可重放、可审计。
生产档 `clean-disabled=true`（防误清库）、`validate-on-migrate=true`（迁移前校验一致性）。
管理员账号的 BCrypt 口令由 `DataInitializer` 启动时按 `buddy.init` 初始化（与 Flyway 种子不冲突，已存在则跳过）。

---

## 6. 多租户（opt-in 框架能力）

默认 **关闭**（`buddy.tenant.enabled=false`）= 单租户，零开销、零回归；开启只翻配置。

- `TenantContext`：`ThreadLocal` 保存当前 `tenantId` 与 `ignore` 标记；提供 `runAs/callAs/callIgnoring`（均 `finally` 恢复，支持嵌套）。
- `BuddyTenantLineHandler`：实现 MyBatis-Plus `TenantLineHandler`，为非忽略表自动追加 `tenant_id` 过滤；
  忽略表 = `TenantContext.isIgnore()` ∪ `qrtz_` 前缀 ∪ 配置 `ignore-tables`（如 `sys_menu`/`flyway_schema_history`/`shedlock`）。
- `@IgnoreTenant` + `TenantIgnoreAspect`：标注系统级任务（如跨租户调度心跳）跳过租户过滤。
- 上下文传播：`JwtAuthenticationFilter` 从令牌 claim 填充 `TenantContext`；`AsyncConfig` 的 `TaskDecorator`
  跨线程传播 user/tenant/security 上下文；登录查找用 `callIgnoring` 跨租户定位用户，再解析其 `tenantId`。
- 表结构由 `V6` 预留 `tenant_id`（默认 1），`SysUser.tenantId` 只读（`FieldStrategy.NEVER`，写入交拦截器/DB 默认）。

---

## 7. 审计、异步与可观测

- **审计**：`@OperateLog` + `OperateLogAspect`（`@Order(200)`）异步入库（`saveAsync`），记录操作人/类型/参数/结果；
  参数与结果按 `VARCHAR(2000)` **安全截断**（为省略号预留长度，避免超长撑爆列导致审计静默丢失——此为已修复的历史缺陷，见 `OperateLogAspectTest`）。
- **异步**：`AsyncConfig` 定义业务线程池，并用 `TaskDecorator` 把提交线程的 `UserContext`/`TenantContext`/`SecurityContext`
  复制到执行线程，保证异步任务里审计填充、租户隔离、鉴权主体不丢失。
- **可观测**：Actuator 暴露 `health/info/metrics/prometheus`（生产收敛）；Micrometer + Prometheus 注册表；
  业务可注册自定义指标（样例 `BarrierMetrics` 暴露 `barrier.apply`/`barrier.cas.conflict`/`barrier.state`）。
  抓取受 Actuator 链鉴权保护（见 §3）。

---

## 8. 业务样例如何接入框架

`biz.barrier` 是"框架承载真实业务"的范本，接入点：

1. **分层**（与 `framework/modules/*` 同构）：`controller`（REST 接口）↔ `service`（应用编排）↔ `core`（纯领域，无框架依赖，可脱库单测）
   ↔ `infrastructure`（`BarrierStore` 端口的 MyBatis-Plus 适配器）↔ `mapper`/`entity`（实体继承 `BaseEntity`）；
   模型收于 `model/form`（写入参）/ `model/vo`（出参）；`scheduler` 承担调度驱动（心跳 tick + 启动对齐）；`config` 只放装配。
2. **复用框架件**：统一 `R`/`ResultCode`、`@OperateLog` 审计、`SecurityUtils`/`@AuthenticationPrincipal` 取主体、
   `@PreAuthorize("hasAuthority('...')")` 鉴权、全局异常处理。
3. **权限与菜单**：新增权限码经 `V5` 灌入 `sys_menu`，前端按 `sys_menu.perms` 做菜单/按钮级显隐 + 动态路由。
4. **调度单例**：`@Scheduled` 心跳叠加 `@SchedulerLock`（ShedLock，`V4` 表）保证集群内单实例执行；系统级任务标 `@IgnoreTenant`。

---

## 9. 测试与质量门

| 层次 | 手段 |
|---|---|
| 后端单元/集成 | `@SpringBootTest` + MockMvc + 真实登录 `adminToken`（`AbstractIntegrationTest`），断言"HTTP 状态 + body.code"双层契约 |
| H2 档 | `mvn verify`：全部非 `mysql` 标签用例 + JaCoCo 行覆盖率 ≥45% 门禁（零外部 DB 依赖） |
| 真库档 | `@Tag("mysql")` 用例（默认排除），CI `mysql-consistency` job 反选运行：utf8mb4 中文种子、MyBatis 方言、跨实例 CAS 并发 |
| 前端 | Vitest（单元 + 权限契约扫描全部 Flyway 迁移）、`vue-tsc` 类型检查、Playwright E2E（串行 `workers:1`） |
| 压测 | JMeter 竞态压测（手动撞定时）、k6 脚本 |
| 安全 | CI Trivy：CRITICAL 依赖漏洞硬门禁；密钥扫描为非阻断报表（避免 dev 固定串误报） |
