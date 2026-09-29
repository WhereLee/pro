# swap · C 端会员身份域设计（M0-4 定稿）

> 本文定义换电柜的**第二身份域**：骑手续（C 端会员）是谁、怎么证明、会话怎么管、实名怎么审、注销怎么处理。
> 数据结构由 `V10__member_right`（会员主体与权益）与 **`V12__member_auth`**（会话、验证码、实名申请）承载。
> 关联文档：[`swap-order-fsm.md`](swap-order-fsm.md)（guard 与快照）、[`swap-ddl.md`](swap-ddl.md)（兼容规则与不变式）、[`swap-plan.md`](swap-plan.md)。

---

## 1. 两个身份域，不是一套系统的两种角色

| | 后台域（框架已有） | **会员域（本文新增）** |
|---|---|---|
| 主体 | `sys_user`（运营/管理人员） | `member_user`（骑手续） |
| 授权模型 | RBAC：菜单 + 按钮权限码 + 部门数据范围 | **无权限码**，只有权益（可用次数、有效期、状态） |
| 令牌 | `typ=admin`，8h，Redis 在线台账，支持强制下线 | `typ=member`，access 2h + refresh 30 天，独立台账 |
| 数量级 | 百级 | 万到百万级 |
| 暴露面 | 内网/受控 | **公网直连，是被刷的目标** |

**为什么不能塞进 `sys_user` + RBAC**：把权益做成权限码是概念错误——权益是有数量、有效期、状态的**资产**，权限是布尔判定；且两者的令牌生命周期、数量级、攻击面都不同。

最要紧的一条：**混用会产生"串域"这种静默致命失效**。若 C 端令牌被后台接口接受，骑手就能调 `swap:battery:reconcile`（人工改电池归属）或 `swap:order:intervene`。这类漏洞的特征是功能全正常、测试全绿，只有安全审计能发现。

---

## 2. 令牌设计

| 项 | 决定 | 理由 |
|---|---|---|
| 签名密钥 | **独立配置** `buddy.member.jwt-secret`，无默认值、缺失即启动失败 | 物理隔离优于"同一密钥派生"。派生方案一处泄露两处沦陷 |
| Claim | `typ=member`、`sub=memberId`、`aud=member`、`jti`、`sid`（会话族）、`tenantId` | `typ` 是分派依据，`sid` 让单个令牌可定位到会话族以便撤销 |
| access TTL | 2 小时 | 短寿命限制泄露窗口 |
| refresh TTL | 30 天 | 骑手不能天天登录 |
| **refresh 一次性轮换** | 每次刷新：新增一行 `ACTIVE`、旧行置 `ROTATED`，**旧 refresh 立即失效** | 长效凭证不能复用 |
| **复用检测** | 再次出示已 `ROTATED` 的 refresh → 判定凭证泄露 → **撤销整个 `session_family`** 并记录 `revoked_reason=REUSE_DETECTED` | 这是"令牌被复制"唯一可被及时发现的形式：攻击者和真用户不可能都只用到一次 |
| 多设备 | 允许不同 `device_type` 并存（H5/APP/MINI/VEHICLE/OPS）；**同一设备类型只保留最新会话族** | 骑手确实可能手机 + 车机同时在线；同类型互斥才能让"强制下线"有确定语义 |

### 2.1 为什么"串域"必须是 401，不能是 403

这条是整个设计的核心约束，也是它能被自证的原因：

- **403** 的语义是"我认得你是谁，但你的权限不够" → 说明请求**已经走进了后台域的鉴权与权限判定路径**；
- **401** 的语义是"你根本不是这个域的人" → 说明在**分派阶段就被拒**，后台权限判定根本没执行。

所以 C 端令牌打后台接口**必须 401**。如果实现成 403，说明过滤器把 member 令牌解出了主体、只是权限码不匹配——那就是串域的前兆。
这条能被写成断言：`MemberTokenCannotReachAdminApis` / `AdminTokenCannotReachMemberApis`，两者都断言 **HTTP 401 且响应体不含权限错误码**。

### 2.2 过滤链结构（沿用框架已有的多链先例）

`SecurityConfig` 现在已有两条链（Actuator `@Order(1)`、主业务 `@Order(2)`），会员域加第三条：

```
@Order(3)  /api/member/**    → MemberJwtAuthenticationFilter   只接受 typ=member，principal=MemberPrincipal
@Order(2)  其余              → JwtAuthenticationFilter（现有）  只接受 typ=admin；遇到 typ=member 直接 401
```

**每条链都必须自己装配自己的过滤器**——框架历史缺陷就是 Actuator 链漏装 JWT 过滤器导致 Prometheus 抓不到（见 `docs/architecture.md` §3、`ActuatorSecurityTest`）。多一条链就是同一个坑的重发点，所以链的装配本身要有测试。

`MemberPrincipal` 携带 `ROLE_MEMBER`，**不携带任何权限码集合**。这样即使某接口漏写鉴权注解，`hasAuthority('...')` 也必然失败——权限码不存在于该 principal，属于**失效方向安全（fail-closed）**。

### 2.3 框架 / 业务的边界

| 归属 | 内容 |
|---|---|
| `framework/security`（通用，业务无关） | 多域令牌签发与校验的基类、按 `typ` 分派的抽象、域隔离测试脚手架、会话台账的通用件（jti 校验 + 强制下线） |
| `biz/swap/member`（业务） | `MemberPrincipal`、会员令牌服务、登录/注册/实名/注销控制器、权益快照装配 |

判据：**"多身份域"是任何 C 端产品都会遇到的通用需求**（订单系统、租赁、社区都同理），因此分派机制进框架；"骑手续要实名"是换电业务，留在 biz。

---

## 3. V12 表与不变式

| 表 | 职责 | DB 约束 |
|---|---|---|
| `member_session` | 会话族、refresh 轮换、复用检测、强制下线的载体 | `uk_msess_refresh`、`uk_msess_jti`；**I-MS-FAMILY** `active_family` 生成列唯一 = 同会员同设备类型至多一个生效会话族 |
| `member_sms_code` | 验证码（**存哈希不存明文码**）、用途区分、尝试次数 | **I-MS-SMS** `active_key` 生成列唯一 = 同手机号同用途至多一条待验证 |
| `member_realname` | 实名申请全过程（**真相**；`member_user.realname_state` 是投影） | **I-MRN** `active_member` 生成列唯一 = 一个会员至多一条在审申请 |
| `member_user` 补列 | `canceled_at`、`cancel_reason`、`realname_verified_at`；`idcard_hash` 唯一 | 一证只会员（跨会员防冒领/一证多号） |

**为什么"同手机号同用途至多一条待验证"值得单独设约束**：不约束会出现两类问题——验证码轰炸（每次请求都发一条、都有效），以及**多码并存时任一码都能通过**（用户收到 5 条短信，攻击者猜中任一即登录）。约束迫使业务在发新码前显式让旧码失效，这个动作本身就被留痕成一次状态变更。

**实测证据（MySQL 8.0.44，V1→V12 全量执行后）**：8 项用例全部符合预期。

| 用例 | 期望 | 结果 |
|---|---|---|
| 同会员 H5 + VEHICLE 两个 ACTIVE 会话族并存 | 允许 | ✅ |
| 同会员同类型第二个 ACTIVE 族 | 拒绝 | ✅ |
| 把旧族置 `REVOKED` 后再建同类型新族 | 允许（**生成列随 UPDATE 重算**） | ✅ |
| `access_expires_at < issued_at` 的 CHECK | 拒绝 | ✅ |
| 同手机号同用途两条 PENDING 验证码 | 拒绝 | ✅ |
| 同手机号不同用途并存 | 允许 | ✅ |
| 同会员两条在审实名申请 | 拒绝 | ✅ |
| 上一条被 `REJECTED` 后重新提交 | 允许 | ✅ |

第 3 条与第 6/8 条是**正向对照**：只证明"会被拦"不足以说明约束设计正确，必须同时证明**该放的确实放得过去**，否则很可能上线后发现"骑手手机登录过就再也无法从车机登录"。

---

## 4. 流程

### 4.1 注册 / 登录
```
手机号 + 验证码（purpose=LOGIN）→ 校验通过即建/取 member_user（phone_hash 定位）
                                 → 新建 session_family + access/refresh 令牌
微信入口：openId 命中 member_identity → 同会员；未命中 → 建会员 + 绑 identity
```
`member_user` 与 `member_identity` 分离的**直接收益**：同一人从微信、手机号、以后的 App 进来都是同一个会员，权益与历史不会裂成两份。

**短信通道是端口 + 模拟实现**（与支付渠道同一手法）：dev/test 下不真发短信，验证码从服务端日志/接口取；这使登录链路、频控、过期、错误次数上限**全部可在 CI 里测**，而不是"等有真短信账号再测"。

### 4.2 实名（业务口径：**强制**）
- `member_realname` 一次申请一条记录；驳回后重新申请是**新增行**，不覆盖历史（可追溯"谁在什么时候用什么证件申请过"）。
- 审核通过 → 同事务写 `member_user.realname_state=VERIFIED` + `realname_verified_at`（投影），真相仍在申请表。
- 未实名会员：换电 guard 直接 `REJECTED`（零物理动作，自动可解释），原因码 `NEED_REALNAME`。
- 展示一律用 `mask_name` / `mask_id_no`；密文与哈希禁止出现在日志、异常栈与接口响应。

### 4.3 注销（口径已定）
```
有在途订单（swap_order 非终态）        → 拒绝，提示先完成或取消
有 ACTIVE 电池绑定（持有电池未归还）    → 拒绝，引导先到柜归还
两者都干净                            → 允许：member_state=CANCELED、canceled_at、
                                       撤销全部会话族、权益作废不退款、手机号哈希保留
```
"权益作废不退款"属业务规则，退款分支留到 M4 资金域一起定（避免在此偷偷定资金规则）。
**注销前置校验是跨表判定，无法做成 CHECK**（`swap-ddl.md` §2 C-5），所以落在服务层守卫 + 回归测试——这是诚实标注，不是遗漏。

注销后手机号可否复用：**不做即时复用**（保留 `phone_hash` 占位），因为"注销即释放手机号"会让纠纷追溯断裂。要做需引入号码回收冷却期，归 M6 评估。

---

## 5. C 端可见状态契约（协议 §15 遗留项到此闭环）

C 端订单接口**不返回内部状态名**，返回：

```json
{ "orderNo": "SW...", "displayState": "PROCESSING|NEED_ACTION|UNKNOWN|FINISHED|REJECTED",
  "reasonCode": "RETURN_DOOR_NOT_CLOSED", "actionable": ["WAIT", "DECLARE_CLOSED_ONCE"],
  "rightDeducted": false }
```

| `displayState` | 由哪些内部态映射 | 用户看到什么 |
|---|---|---|
| `PROCESSING` | `CREATED/AUTHORIZED/RETURNING/RETURNED/VERIFYING/OFFERING/SETTLING` | "处理中"，禁重复提交 |
| `NEED_ACTION` | `SUSPENDED` | "请把仓门关好"，给一次性"我关好了"按钮（B2：只触发反查） |
| `UNKNOWN` | `UNCONFIRMED/ABORTING` | "**正在核实**，若 5 分钟内未自动完成我们会主动联系你"，**不显示失败** |
| `FINISHED` | `COMPLETED` | 成功 + 新电池信息 |
| `REJECTED` | `REJECTED/ABORTED` | 未扣费 + 原因（可复购） |

映射表由服务端持有并可测（一条测试：15 个内部态都有映射，且 `UNKNOWN` 三态绝不产出"失败"文案）。
后台侧相反：展示**原始状态名 + 事件流**，运维需要精确。

---

## 6. 安全清单

| 项 | 做法 |
|---|---|
| 敏感列 | 手机号/身份证/姓名 = **密文列（AES-GCM）+ 哈希列（HMAC-SHA256 + pepper）**；哈希用于唯一约束与等值查询，密文用于业务读取 |
| 验证码 | 只存 `code_hash`，不存明文；`attempts/max_attempts` 限爆破；`expires_at` 限时 |
| 日志 | 禁止输出密文、哈希、令牌、签名；`@OperateLog` 参数序列化对 `*Cipher`/`*Hash`/`refresh_hash` 字段名做脱敏 |
| 撞库/枚举 | 登录与"取验证码"接口对不存在手机号与已注册手机号**返回完全一致的响应与耗时特征**（不泄露注册状态） |
| 频控 | 验证码 `active_key` 唯一（DB 级）+ Redis 计数（IP/设备维度）双层 |
| 会话 | 强制下线走 `member:session:revoke` 权限 + `@OperateLog` 审计；撤销按 `session_family` 整族生效 |
| 令牌泄露 | refresh 复用检测 → 整族撤销 + 安全告警 |

---

## 7. 实现归属与测试清单

| 里程碑 | 交付 |
|---|---|
| M1 | `framework/security` 多域基类与链装配、独立密钥配置、会话台账通用件、`MemberJwtAuthenticationFilter` |
| M2 | 登录/注册/验证码（mock 通道）、会员令牌服务、guard 读权益快照并落 `GUARD_SNAPSHOT` 事件、C 端 `displayState` 契约、H5 登录与换电主链路 |
| M3 | refresh 轮换与复用检测、强制下线、`UNKNOWN` 态的 C 端引导与工单联动 |
| M4 | 押金与授信免押（口径已定，落在 V13+）、注销时的资金分支 |
| M6 | 号码回收冷却、租户级实名/注销策略差异 |

**必测清单**（进 M2/M3 验证门）：
1. `DomainIsolationCrossTest`：C 端 token 打后台 → 401；后台 token 打 C 端 → 401；两者都断言**不是 403**。
2. `MemberFilterChainAssemblyTest`：第三条链确实装配了 member 过滤器（防历史缺陷重发：链间过滤器互不生效）。
3. `RefreshRotationAndReuseDetectionTest`：轮换后旧令牌失效；重放旧 refresh → 整族撤销 + `revoked_reason` 正确。
4. `SameDeviceTypeExclusiveFamilyTest`：同类型第二次登录使第一次会话族失效。
5. `SmsSinglePendingPerPurposeTest` + `SmsAttemptLimitTest`。
6. `RealnameProjectionConsistencyTest`：申请表真相与 `member_user` 投影一致；驳回后重新申请不覆盖历史。
7. `CancellationPreconditionTest`：在途订单 / 持有电池两种情形必须拒绝，且拒绝原因可解释。
8. `DisplayStateMappingTotalTest`：15 个内部态全覆盖映射，`UNKNOWN` 类不得产生失败文案。
9. `MemberNoPermCodesTest`：`MemberPrincipal` 权限码集合恒为空（漏写注解时 fail-closed）。

---

## 8. 遗留决策点

1. 押金账户结构与授信免押的额度模型（M4，与退款/对账一起定）。
2. 号码回收冷却期是否要做（M6）。
3. 多租户下"同一手机号可否在不同运营商各建一会员"——影响 `member_user.phone_hash` 唯一性是否要改成 `(tenant_id, phone_hash)`。**这个决定必须在 M6 开租户前定死**，因为改唯一索引要清理重复数据。
4. `device_type` 枚举是否需要扩展到店端/换电柜屏（柜机上的 HMI）——影响互斥粒度。

---

## 9. 变更日志

- 2026-09-29：初版定稿（M0-4）。两域分离的理由与"串域必须 401 不是 403"的可测化；独立密钥 + `typ` 分派 + 第三条过滤链（并明确"每条链自装过滤器"这一框架历史缺陷的重发点）；
  access 2h / refresh 30 天一次性轮换与**复用检测整族撤销**；多设备类型并存、同类型互斥；
  实名强制口径与"申请是真相、会员表是投影"；注销双前置（跨表校验无法落 CHECK，诚实标注）；
  C 端 `displayState` 五态契约（内部状态名不下发，`UNKNOWN` 不显示失败）；
  安全清单（密文+哈希双列、验证码只存哈希、响应与耗时不泄露注册状态）；
  交付 `V12__member_auth`（3 表 + 3 条生成列不变式 + 4 列补充 + 3 个权限码），
  并在 MySQL 8.0.44 上跑完 8 项**含正向对照**的约束用例，全部符合预期。
