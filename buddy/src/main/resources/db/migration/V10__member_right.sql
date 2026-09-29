-- ============================================================
-- V10：C 端会员与权益（biz.swap member 域）——M2 主链路的 guard 依赖。
--
-- 为什么提前到 M2 之前：订单第一个状态迁移 CREATED→AUTHORIZED 的守卫就是
-- "权益是否有效 + 是否已有在途单 + 是否黑名单"，没有权益账户表，AUTHORIZED 无定义。
-- C 端登录/令牌/实名的**鉴权细节**在 M0-4 定案（第二受众、独立 JWT audience），
-- 本文件只定数据结构，不含鉴权流程。
--
-- 关键设计：
--   1. 手机号/身份证**密文 + 哈希双列**：密文供业务读取（AES-GCM），哈希供唯一约束与等值查询
--      （HMAC-SHA256(pepper, plain)，pepper 走环境变量）。不存明文，日志禁止输出两列。
--   2. 权益 = 账户快照 + 不可变流水，两者必须同事务写（I3）；
--      恒等式 snapshot.times_used == Σ 流水净额 由日终对账校验（I7 的权益侧翻版）。
--      为什么不只留流水：guard 在热路径上，不该每次扫流水表聚合。
--      为什么不只留快照：无法审计、无法重算、出错无法定位。
--   3. 押金与授信免押属 M4，本文件不建表（避免把未定的资金规则固化成 schema）。
-- ============================================================

-- ---------------- C 端会员 ----------------
CREATE TABLE member_user (
    id                BIGINT      NOT NULL,
    member_no         VARCHAR(32) NOT NULL,
    phone_cipher      VARCHAR(255),
    phone_hash        CHAR(64),
    nickname          VARCHAR(64),
    avatar_path       VARCHAR(255),
    realname_state    VARCHAR(16) NOT NULL DEFAULT 'NONE',
    idname_cipher     VARCHAR(255),
    idcard_cipher     VARCHAR(512),
    idcard_hash       CHAR(64),
    member_state      VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    risk_flag         INT         NOT NULL DEFAULT 0,
    credit_level      INT         NOT NULL DEFAULT 0,
    register_source   VARCHAR(16) NOT NULL DEFAULT 'H5',
    last_login_at     DATETIME(3),
    frozen_reason     VARCHAR(255),
    create_by         BIGINT,
    create_time       DATETIME,
    update_by         BIGINT,
    update_time       DATETIME,
    remark            VARCHAR(255),
    version           INT         NOT NULL DEFAULT 0,
    del_flag          INT         NOT NULL DEFAULT 0,
    tenant_id         BIGINT      DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_muser_realname CHECK (realname_state IN ('NONE', 'PENDING', 'VERIFIED', 'REJECTED')),
    CONSTRAINT ck_muser_state CHECK (member_state IN ('NORMAL', 'FROZEN', 'BLACKLIST', 'CANCELED'))
);
-- @enum member_user.realname_state: NONE|PENDING|VERIFIED|REJECTED
-- @enum member_user.member_state: NORMAL|FROZEN|BLACKLIST|CANCELED
CREATE UNIQUE INDEX uk_member_no ON member_user (member_no);
CREATE UNIQUE INDEX uk_member_phone ON member_user (phone_hash);
CREATE INDEX idx_member_state ON member_user (member_state, tenant_id);
CREATE INDEX idx_member_idcard ON member_user (idcard_hash);

-- ---------------- 第三方身份绑定（微信 openId / 模拟提供方） ----------------
CREATE TABLE member_identity (
    id            BIGINT      NOT NULL,
    member_id     BIGINT      NOT NULL,
    provider      VARCHAR(16) NOT NULL,
    open_id       VARCHAR(64) NOT NULL,
    union_id      VARCHAR(64),
    provider_app  VARCHAR(64),
    bind_state    VARCHAR(16) NOT NULL DEFAULT 'BOUND',
    bound_at      DATETIME(3) NOT NULL,
    unbound_at    DATETIME(3),
    create_time   DATETIME,
    update_time   DATETIME,
    version       INT         NOT NULL DEFAULT 0,
    del_flag      INT         NOT NULL DEFAULT 0,
    tenant_id     BIGINT      DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_mid_provider CHECK (provider IN ('WECHAT', 'MOCK', 'APP')),
    CONSTRAINT ck_mid_state CHECK (bind_state IN ('BOUND', 'UNBOUND'))
);
-- @enum member_identity.provider: WECHAT|MOCK|APP
-- @enum member_identity.bind_state: BOUND|UNBOUND
CREATE UNIQUE INDEX uk_mid_open ON member_identity (provider, provider_app, open_id);
CREATE INDEX idx_mid_member ON member_identity (member_id, bind_state);

-- ---------------- 套餐定义 ----------------
CREATE TABLE swap_right_plan (
    id             BIGINT       NOT NULL,
    plan_code      VARCHAR(32)  NOT NULL,
    plan_name      VARCHAR(64)  NOT NULL,
    plan_type      VARCHAR(16)  NOT NULL,
    times_total    INT          NOT NULL DEFAULT 0,
    valid_days     INT          NOT NULL DEFAULT 0,
    price          DECIMAL(12, 2) NOT NULL DEFAULT 0,
    over_time_price DECIMAL(12, 2) NOT NULL DEFAULT 0,
    min_soc_granted INT,
    site_scope     VARCHAR(16)  NOT NULL DEFAULT 'ALL',
    enabled        INT          NOT NULL DEFAULT 1,
    create_by      BIGINT,
    create_time    DATETIME,
    update_by      BIGINT,
    update_time    DATETIME,
    remark         VARCHAR(255),
    version        INT          NOT NULL DEFAULT 0,
    del_flag       INT          NOT NULL DEFAULT 0,
    tenant_id      BIGINT       DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_plan_type CHECK (plan_type IN ('TIMES', 'MONTH', 'DAY', 'QUOTA')),
    CONSTRAINT ck_plan_times CHECK (times_total >= 0 AND valid_days >= 0),
    CONSTRAINT ck_plan_scope CHECK (site_scope IN ('ALL', 'SITE', 'TENANT'))
);
-- @enum swap_right_plan.plan_type: TIMES|MONTH|DAY|QUOTA
-- @enum swap_right_plan.site_scope: ALL|SITE|TENANT
CREATE UNIQUE INDEX uk_plan_code ON swap_right_plan (plan_code);
CREATE INDEX idx_plan_enabled ON swap_right_plan (enabled, tenant_id);

-- ---------------- 权益账户（快照，guard 的热路径读这里） ----------------
CREATE TABLE swap_right_account (
    id              BIGINT   NOT NULL,
    member_id       BIGINT   NOT NULL,
    plan_id         BIGINT,
    times_total     INT      NOT NULL DEFAULT 0,
    times_used      INT      NOT NULL DEFAULT 0,
    times_occupied  INT      NOT NULL DEFAULT 0,
    valid_from      DATETIME(3),
    valid_until     DATETIME(3),
    freeze_state    VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    auto_renew      INT      NOT NULL DEFAULT 0,
    create_by       BIGINT,
    create_time     DATETIME,
    update_by       BIGINT,
    update_time     DATETIME,
    remark          VARCHAR(255),
    version         INT      NOT NULL DEFAULT 0,
    del_flag        INT      NOT NULL DEFAULT 0,
    tenant_id       BIGINT   DEFAULT 1,
    PRIMARY KEY (id),
    -- 账面上的"不可为负"直接落 DB：扣减/预占的越界写不依赖代码判断
    CONSTRAINT ck_racc_times CHECK (times_used >= 0 AND times_occupied >= 0 AND times_total >= times_used),
    CONSTRAINT ck_racc_freeze CHECK (freeze_state IN ('NORMAL', 'FROZEN'))
);
-- @enum swap_right_account.freeze_state: NORMAL|FROZEN
CREATE UNIQUE INDEX uk_racc_member ON swap_right_account (member_id);
CREATE INDEX idx_racc_valid ON swap_right_account (valid_until, freeze_state);

-- ---------------- 权益流水（append-only，幂等键 (order_id, kind)） ----------------
CREATE TABLE swap_right_transaction (
    id             BIGINT      NOT NULL,
    member_id      BIGINT      NOT NULL,
    account_id     BIGINT      NOT NULL,
    order_id       BIGINT,
    kind           VARCHAR(16) NOT NULL,
    delta_times    INT         NOT NULL DEFAULT 0,
    balance_after  INT,
    occurred_at    DATETIME(3) NOT NULL,
    ts_millis      BIGINT      NOT NULL,
    reason         VARCHAR(64),
    operator_id    BIGINT,
    trace_id       VARCHAR(64),
    create_time    DATETIME,
    tenant_id      BIGINT      DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_rtx_kind CHECK (kind IN ('GRANT', 'OCCUPY', 'DEDUCT', 'RELEASE', 'REFUND', 'ADJUST'))
);
-- @enum swap_right_transaction.kind: GRANT|OCCUPY|DEDUCT|RELEASE|REFUND|ADJUST
CREATE UNIQUE INDEX uk_rtx_idem ON swap_right_transaction (order_id, kind);
CREATE INDEX idx_rtx_member ON swap_right_transaction (member_id, occurred_at);
CREATE INDEX idx_rtx_account ON swap_right_transaction (account_id, occurred_at);

-- ---------------- 样例种子（默认套餐 + 演示会员，clone 后可直接走 H5 主链路） ----------------
INSERT INTO swap_right_plan (id, plan_code, plan_name, plan_type, times_total, valid_days, price, over_time_price, site_scope, enabled, create_time, version, del_flag, tenant_id)
VALUES (1, 'PLAN-DEMO-60', '演示套餐（60 次 / 30 天）', 'MONTH', 60, 30, 99.00, 3.00, 'ALL', 1, CURRENT_TIMESTAMP, 0, 0, 1);

INSERT INTO member_user (id, member_no, nickname, realname_state, member_state, register_source, create_time, version, del_flag, tenant_id)
VALUES (1, 'M0000000001', '演示骑手', 'VERIFIED', 'NORMAL', 'H5', CURRENT_TIMESTAMP, 0, 0, 1);

INSERT INTO swap_right_account (id, member_id, plan_id, times_total, times_used, times_occupied, valid_from, valid_until, freeze_state, create_time, version, del_flag, tenant_id)
VALUES (1, 1, 1, 60, 0, 0, CURRENT_TIMESTAMP, '2099-12-31 00:00:00', 'NORMAL', CURRENT_TIMESTAMP, 0, 0, 1);

INSERT INTO swap_right_transaction (id, member_id, account_id, order_id, kind, delta_times, balance_after, occurred_at, ts_millis, reason, create_time, tenant_id)
VALUES (1, 1, 1, NULL, 'GRANT', 60, 60, CURRENT_TIMESTAMP, 1790000000000, 'SEED', CURRENT_TIMESTAMP, 1);
