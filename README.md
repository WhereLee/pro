# buddy · 可复用的企业级 Spring Boot 基础框架

> 一套开箱即用的企业级后台地基（后端 `buddy` + 前端 `buddy-ui`），并附带一个**完整业务样例**
> （`biz.barrier` 升降杆调度系统）演示"框架能力如何承载真实业务"。
>
> **复用方式**：clone 本仓库 → 保留框架层 `buddy/src/main/java/com/lrs/buddy/framework`（`common` / `config` / `security` / `modules` / `tenant`）
> → 替换或新增业务层 `com.lrs.buddy.biz` 下的内容 → 即得一个带鉴权、审计、多租户、可观测、可容器化部署的新项目。
>
> **一句话看懂结构**：`framework/` = 与业务无关的可复用基座；`biz/` = 跑在基座上的业务样例（当前是 `biz/barrier` 升降杆）。二者以包边界物理隔离，照着 `biz/barrier` 依葫芦画瓢即可加新业务。

---

## 1. 仓库结构（monorepo）

```
sx/
├── buddy/                 后端框架 + barrier 样例（Spring Boot 3.5 / Java 17 / Maven）
│   └── src/main/java/com/lrs/buddy/
│       ├── framework/     ◈ 可复用基座（与业务无关，新项目直接沿用）
│       │   ├── common/      response（R/ResultCode）、exception（异常体系）、model（BaseEntity/分页）、annotation/aspect/enums、SSE、util
│       │   ├── config/      Security、MyBatis-Plus、Async、Cors、Jackson、OpenApi、Redis、ShedLock（跨实例调度锁）
│       │   ├── security/    无状态 JWT + RBAC + 在线台账（Redis）+ 用户上下文
│       │   ├── modules/     框架自带通用模块：sys / file / job / log / monitor / notice
│       │   └── tenant/      多租户框架能力（opt-in，默认关闭）
│       ├── biz/           ◈ 业务层（替换/新增你自己的业务）
│       │   └── barrier/     ★ 样例：升降杆调度（controller/service/model/entity/mapper 同构分层 + 领域 core/infrastructure/scheduler）
│       └── BuddyApplication.java   启动类（根包 com.lrs.buddy，扫描 framework + biz）
│   └── src/main/resources/
│       ├── db/migration/    Flyway 版本化迁移 V1–V6（sys 基座 + barrier 样例 + tenant）
│       └── mapper/          MyBatis XML（framework/modules 的 sys/notice）
├── buddy-ui/              前端管理台（Vue 3 + Element Plus + Pinia + Vite）
│   └── src/
│       ├── api/biz/         ★ 样例业务 API（barrier.ts）；其余 api/ 为框架通用接口
│       └── views/biz/       ★ 样例业务页面（barrier）；其余 views/ 为框架通用页面
├── scripts/               本地辅助脚本（校验/重启等，非运行时依赖）
├── docker-compose.yml     生产编排：MySQL 8 + Redis 7 + 后端(prod) + 前端(nginx)
├── .env.example           生产密钥模板（复制为 .env 填入强密钥）
└── .github/workflows/     CI：backend / frontend / mysql-consistency / e2e / docker / security-scan
```

### 上手：新增一个业务模块（以 `biz/barrier` 为模板）
1. **后端**：在 `com.lrs.buddy.biz.<你的模块>` 下按样例分层——`entity` 建实体（继承 `framework.common.model.BaseEntity`）、`mapper` 声明接口、`model/form` 收写入参 / `model/vo` 收出参、`controller` 放 Controller（返回 `framework.common.response.R`，鉴权用 `@PreAuthorize`）、`service` 写应用编排、`core` 写纯领域；框架的响应/异常/分页/审计/数据权限无需重复实现，直接复用 `framework/*`。
2. **数据库**：新增 `db/migration/V<n>__<模块>.sql`（只增不改），沿用 Flyway 版本化演进。
3. **前端**：`src/api/biz/<模块>.ts` 封装接口、`src/views/biz/<模块>/` 写页面；菜单/权限经 `sys` 模块下发（动态路由）。
4. **测试**：照 `src/test/.../biz/barrier` 补领域单测 + `framework` 的 `AbstractIntegrationTest` 集成测试；CI 的 JaCoCo 门禁会兜底覆盖率。
> 需要改动 `framework/*` 时请谨慎——它是所有业务共享的地基，尽量通过新增而非改语义来扩展。

文档：
- [`buddy/docs/architecture.md`](buddy/docs/architecture.md) — 框架架构与横切能力（安全、多租户、Flyway、审计、可观测、并发上下文传播）
- [`buddy/docs/barrier-sample.md`](buddy/docs/barrier-sample.md) — barrier 样例：业务本质、领域模型、三层并发、接口与边界
- [`buddy/docs/ROADMAP.md`](buddy/docs/ROADMAP.md) — 框架与样例的合并/开发顺序计划、决策依据、变更日志、简化项登记
- [`buddy/docs/ci.md`](buddy/docs/ci.md) — CI 流水线全景、gh 查看/重跑操作手册、已知问题与修复指引

---

## 2. 框架能力清单

| 领域 | 能力 | 落点 |
|---|---|---|
| 认证鉴权 | 无状态 JWT（含 jti 强制下线）、RBAC 菜单/按钮级权限、`@PreAuthorize` 方法级鉴权 | `framework/security`、`framework/config/SecurityConfig` |
| 统一契约 | 双层响应信封 `R{code,message,data,timestamp,success}` + `ResultCode` 业务码；全局异常处理 | `framework/common/response/R`、`framework/common/exception/GlobalExceptionHandler` |
| 数据层 | MyBatis-Plus（分页 + 乐观锁 + 防全表更新/删除）、审计字段与逻辑删除自动填充 | `framework/config/MybatisPlusConfig`、`framework/common/model/BaseEntity` |
| 数据库演进 | Flyway 版本化迁移（V1 基线 / V2 种子 / V3+ 业务），H2 与 MySQL 同一套脚本 | `db/migration/` |
| 多租户 | opt-in 框架能力：`tenant_id` 列级隔离、MyBatis-Plus 租户拦截器、`@IgnoreTenant`、跨线程上下文传播 | `framework/tenant`（默认 `buddy.tenant.enabled=false`） |
| 数据权限 | 部门/本人维度的行级数据范围过滤 `@DataScope` | `framework/common/aspect/DataScopeAspect` |
| 幂等 | 防重复提交 `@RepeatSubmit`（Redis） | `framework/common/aspect/RepeatSubmitAspect` |
| 审计 | 操作日志 `@OperateLog`（异步入库、参数/结果安全截断） | `framework/modules/log` |
| 文件 | 本地存储（可替换 `StorageService` 接对象存储）、扩展名/大小校验 | `framework/modules/file` |
| 定时任务 | Quartz 动态任务（增删改查、暂停恢复、并发策略） | `framework/modules/job` |
| 系统监控 | 服务器硬件指标（OSHI）、在线用户、Prometheus 指标暴露 | `framework/modules/monitor`、`micrometer-registry-prometheus` |
| 通知公告 | 公告发布 + 定向可见 + 已读跟踪 | `framework/modules/notice` |
| 实时推送 | SSE 长连接管理 | `framework/common/sse` |
| 接口文档 | OpenAPI 3 / Swagger UI（生产环境自动关闭） | `framework/config/OpenApiConfig` |
| 异步 | 线程池 + `TaskDecorator` 传播 user/tenant/security 上下文 | `framework/config/AsyncConfig` |
| 可观测 | Actuator（health/info/metrics/prometheus）、UTF-8 日志、滚动策略 | `application-prod.yml` |

样例 `biz.barrier` 额外演示：**多杆调度**、**三层并发一致性**（进程内分段锁 + 跨实例 ShedLock + `@Version` 乐观锁 CAS）、**端口-适配器**分层（`core` 纯领域 ↔ `infrastructure` 持久化）、策略与杆的 **N:M** 绑定。

---

## 3. 快速开始

### 前置依赖
- **Docker 方式**：仅需 Docker + Docker Compose（推荐，最接近生产）。
- **本地开发**：JDK 17、Maven 3.9+、Node 22、一个可用的 Redis（6379）。开发档后端用 H2 内存库，无需装 MySQL。

### 方式 A · Docker Compose（一键起全栈，生产同构）
```bash
cp .env.example .env          # 填入强密钥：MYSQL_ROOT_PASSWORD / BUDDY_JWT_SECRET(≥32字节) / BUDDY_ADMIN_PASSWORD
docker compose up -d --build
```
- 前端：http://localhost:3000 （nginx 托管，`/api` 反代到后端）
- 后端健康：http://localhost:8200/api/actuator/health
- 管理员：`.env` 里的 `BUDDY_ADMIN_USERNAME` / `BUDDY_ADMIN_PASSWORD`
- 首次启动 Flyway 自动建库建表 + 灌种子（含 barrier 样例：1 号杆 + 默认策略 08:00 开 / 20:00 关）。

### 方式 B · 本地开发
后端（H2 内存库 + Redis，dev 档）：
```bash
cd buddy
mvn spring-boot:run
# http://localhost:8200/api ，H2 控制台 http://localhost:8200/api/h2-console
# 默认管理员 admin / Admin@123456（dev 档，见 application.yml buddy.init）
```
前端（Vite，端口 3000，代理 /api → 8200）：
```bash
cd buddy-ui
npm ci --legacy-peer-deps
npm run dev
# http://localhost:3000
```

---

## 4. 构建与验证

后端（H2 档，零外部数据库依赖 + JaCoCo 行覆盖率 ≥45% 门禁）：
```bash
cd buddy
mvn verify
```
真库一致性（`@Tag("mysql")` 用例，需 MySQL + Redis；默认被 `surefire.excludedGroups=mysql` 排除，CI 的 `mysql-consistency` job 反选运行）：
```bash
mvn test -Dtest=MysqlConsistencyTest,MySQLConcurrencyTest -Dsurefire.excludedGroups=
```
前端：
```bash
cd buddy-ui
npm run test:unit     # Vitest 单元 + 权限契约测试
npm run build         # vue-tsc --noEmit 类型检查 + vite build
npm run test:e2e      # Playwright 端到端（需后端在 8200 运行；串行 workers:1）
```

---

## 5. 生产部署

生产用 `prod` profile：**所有密钥/连接串外置为环境变量，敏感项无默认值——缺失即启动失败**，杜绝弱默认口令上生产。

| 环境变量 | 必填 | 说明 |
|---|---|---|
| `BUDDY_DB_HOST` / `BUDDY_DB_PORT` / `BUDDY_DB_NAME` | 是 | MySQL 连接（`BUDDY_DB_NAME` 默认 `buddy`） |
| `BUDDY_DB_USER` / `BUDDY_DB_PASSWORD` | 是 | 数据库账号 |
| `BUDDY_REDIS_HOST` / `BUDDY_REDIS_PORT` | 是 | Redis 连接 |
| `BUDDY_JWT_SECRET` | 是 | JWT 签名密钥，HS256 要求 **≥32 字节**（`openssl rand -base64 48`） |
| `BUDDY_ADMIN_PASSWORD` | 是 | 管理员初始口令，首登后应立即修改 |
| `BUDDY_TENANT_ENABLED` | 否 | 多租户开关，默认 `false`（单租户） |
| `BUDDY_FILE_PATH` / `BUDDY_LOG_FILE` | 否 | 上传根目录 / 日志文件（容器内默认 `/app/uploads`、`/app/logs/buddy.log`） |

生产硬化（`application-prod.yml`）：Flyway `clean-disabled=true` 防误清库、`validate-on-migrate`；springdoc/Swagger 关闭缩小攻击面；Actuator 仅暴露 `health,info,prometheus,metrics`；SQL 打印关闭；日志强制 UTF-8 + 滚动。

Actuator 鉴权（`SecurityConfig`）：`health`/`info` 匿名放行（供容器探针、负载均衡健康检查）；`prometheus`/`metrics` 等需**管理员角色**——Prometheus 抓取 `/api/actuator/prometheus` 须携带管理员 JWT（`Authorization: Bearer <token>`），或在内网侧受控访问。

容器化：`buddy/Dockerfile`（多阶段 Maven 构建 → JRE 运行、非 root、容器感知堆、HEALTHCHECK）、`buddy-ui/Dockerfile`（Node 构建 → nginx 托管 + `/api` 反代）。编排见 `docker-compose.yml`。

> 说明：本机未安装 Docker，镜像构建与 `compose up` 未做本地实证，交由 CI `docker` job 及目标环境验证；`prod` profile 已用真实 jar + MySQL 本地实证（Flyway 迁移至 v6、硬化生效、鉴权与业务全绿）。详见 `buddy/docs/ROADMAP.md` 简化/阻塞记录。

---

## 6. CI 流水线（GitHub Actions）

| Job | 内容 |
|---|---|
| `backend` | `mvn verify`（H2 档全测试 + JaCoCo 覆盖率，Redis service container） |
| `frontend` | `npm ci` → Vitest → `vue-tsc` + `vite build` |
| `mysql-consistency` | 真库跑 `@Tag("mysql")` 一致性 + barrier 跨实例 CAS 并发用例（MySQL + Redis service） |
| `e2e` | 起后端 jar(dev/H2) + Playwright 真实浏览器主链路 |
| `docker` | 构建后端/前端两个镜像（不推送），验证 Dockerfile 可成功构建 |
| `security-scan` | Trivy：CRITICAL 依赖漏洞为硬门禁；密钥扫描为非阻断报表（dev 固定串/占位不参与阻断；真实密钥泄露防护走 pre-commit） |

---

## 7. 技术栈

- **后端**：Spring Boot 3.5.16、Java 17、MyBatis-Plus 3.5.17、Flyway、Spring Security + JJWT 0.12、Redis + Redisson、Quartz、ShedLock 6.3、springdoc-openapi 2.9、Micrometer + Prometheus、OSHI、Hutool、Lombok、JUnit 5 + JaCoCo。
- **前端**：Vue 3.5、Element Plus 2.8、Pinia 2.2、Vue Router 4.4、Axios、ECharts 5.5、Vite 5、TypeScript 5.6、Vitest、Playwright。
- **数据库**：MySQL 8（生产）/ H2（开发、测试，`MODE=MySQL`）。
