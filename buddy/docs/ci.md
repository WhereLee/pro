# CI 与 GitHub 工作流参考

> 仓库：`WhereLee/pro` · 触发：`push` / `pull_request` → `master` · 工作流定义：`.github/workflows/ci.yml`
>
> 本文三用：① 流水线构成速查；② 任意机器查看/重跑 CI 的操作手册；③ 已知问题与修复指引。

## 1. 流水线全景（6 个 job）

| job | 名称 | 内容 | 依赖 |
|---|---|---|---|
| `backend` | Backend (build + test + coverage) | `mvn verify`（H2 档全量测试 + JaCoCo 覆盖率门禁）；Redis service container | — |
| `frontend` | Frontend (type-check + build) | `npm ci` → Vitest → `vue-tsc --noEmit && vite build` | — |
| `mysql-consistency` | Backend @ MySQL (consistency) | MySQL 8 + Redis service container，真库跑 `@Tag("mysql")`（一致性 + 并发 CAS） | `backend` |
| `e2e` | Frontend E2E (Playwright) | 构建后端 jar（dev/H2）→ 起服务等 health → Playwright chromium 跑主链路 | `backend` |
| `docker` | Docker images (build) | buildx 构建 backend/frontend 双镜像（不推送，只验证可构建；GHA 层缓存） | `backend`, `frontend` |
| `security-scan` | Security scan (Trivy) | fs 扫描：CRITICAL 依赖漏洞硬门禁 + secret 参考扫描（`exit-code: 0`，不阻断） | — |

- 产物：`jacoco-report`（backend job 上传的覆盖率 artifact）。
- 失败策略：任一 job 失败 → 整个 run 置 failure（其余 job 继续跑完）。

## 2. 查看 / 操作 CI（gh CLI）

本机现状：`gh` 2.96.0，已登录账号 `WhereLee`（能力基线见 §5）。常用命令：

```powershell
gh run list  --repo WhereLee/pro --limit 5        # 最近 run 列表（状态/结论/提交）
gh run view  <run-id> --repo WhereLee/pro         # 本次 run 的 job 级状态表
gh run view  --job=<job-id> --repo WhereLee/pro   # 单个 job 的步骤明细
gh run view  <run-id> --log-failed                # 只看失败步骤的日志
gh run rerun <run-id> --failed                    # 只重跑失败的 job
gh run watch <run-id> --exit-status               # 盯到结束（失败时退出码非 0）
gh run download <run-id> -n jacoco-report         # 下载覆盖率报告
```

Windows PowerShell 两个实测注意点：

1. 中文乱码：先执行 `[Console]::OutputEncoding=[Text.Encoding]::UTF8` 再跑 gh。
2. `--jq` 表达式不要用双引号字符串：PS 5.1 向原生命令传参时会吞坏内层双引号（报 `failed to parse jq expression ... unexpected token`）。用单值表达式（如 `.jobs[].conclusion`），或让 gh 输出 JSON 后交给 PowerShell `ConvertFrom-Json` 自行格式化。

## 3. 最近一次 run 快照（供对照）

run `36567189359` · commit `978360b` · 2026-09-29：**6/6 全绿**（含 security-scan 修复闭环）。

| job | 结果 |
|---|---|
| Backend (build + test + coverage) | ✅ success |
| Frontend (type-check + build) | ✅ success |
| Backend @ MySQL (consistency) | ✅ success |
| Frontend E2E (Playwright) | ✅ success |
| Docker images (build) | ✅ success |
| Security scan (Trivy) | ✅ success |

> 历史：`225a079` 首跑 5 绿 1 红（security-scan，根因与修复全过程见 §4.1）；`docker` 与 `security-scan` 是 `225a079` 起新增的 job，更早的 run 只有前 4 个。

## 4. 已知问题与修复指引

### 4.1 security-scan（已修复闭环）：引用的 action 版本链两层失效

修复历程（2026-09-29，run `36565377941` → `36566439018` → `36567189359`）：

1. **第一层**：`.github/workflows/ci.yml` 原写 `trivy-action@0.28.0`，缺 `v` 前缀（该库 tag 为 `vX.Y.Z`，`0.28.0` ref 不存在）→ "Set up job" 解析失败。
2. **第二层**（补 `v` 后仍红）：`trivy-action@v0.28.0` 的 action.yaml 内部 pin 了 `aquasecurity/setup-trivy@v0.2.1`，该 tag 已被上游删除（现仅存 v0.2.6/v0.3.x）→ 嵌套引用解析失败。
3. **最终修复**：升级为 `aquasecurity/trivy-action@v0.36.0`——其内部改为 pin setup-trivy 至 **commit SHA**（v0.2.6），不再受上游删 tag 影响；`scan-type/scan-ref/scanners/severity/ignore-unfixed/exit-code` 共 6 个在用输入已核对存在。

**教训**：pin 第三方 action 版本时，要注意 composite action 的嵌套引用——间接依赖的 tag 也可能被上游删除；优先选内部以 SHA pin 依赖的版本。

### 4.2 弃用警告（非阻断，建议排期升级）

- `actions/checkout@v4`、`actions/setup-java@v4`、`actions/setup-node@v4`、`actions/upload-artifact@v4` 已被强制运行在 Node 24（Node 20 弃用）；`setup-java` 官方建议迁 `@v5`。
- `ubuntu-latest` 标签将于 2026-10-19 起迁移到 Ubuntu 26。

## 5. 异地/服务器取得 CI 能力

本机已在 interview 目录备一份"能力对齐"交接文档（含明文凭据与账号信息，**切勿外传、切勿提交进任何仓库**）：

`C:\Users\lrs\Desktop\py\interview\GitHub-能力对齐-交接给云端DSH.md`

无凭据要点（正文含完整步骤与验收清单）：

1. 安装 `gh`（官方 release 二进制）→ `gh auth login --with-token` 用 PAT 登录 → `gh auth status` 验证。
2. SSH 走账号级 key：`ssh -T git@github.com` 回显须为 `Hi WhereLee!`（账号名；若回显仓库名则为 deploy key，权限不足）。
3. 设置提交身份：`git config --global user.name / user.email`。
4. 验收：`gh repo list`、`gh run list --repo WhereLee/pro`、临时建 + 删仓库。

## 6. 相关文件

| 文件 | 说明 |
|---|---|
| `.github/workflows/ci.yml` | 流水线定义（§1 的来源） |
| `scripts/restart-ui.ps1` | 本机起前端 dev（日志在 `buddy-ui/.logs/`） |
| `buddy/load/` | JMeter / k6 压测脚本（barrier 并发场景） |
| `buddy/docs/ROADMAP.md` | 开发计划与变更日志（含 CI 相关修复记录） |
