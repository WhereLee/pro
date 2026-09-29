-- ============================================================
-- V12：C 端会员身份域（M0-4）——会话与令牌族、短信验证码、实名认证申请。
--
-- 为什么是 V12 而不是改 V10：Flyway 铁律"已发布版本只增不改"。V10 已随上一次提交进入
-- buddy_it / 生产库的 flyway_schema_history 并带校验和，改它会让所有环境 validate 失败。
-- 这条规则对本次新写的迁移同样生效——不因为"刚写的"就例外。
--
-- 设计依据：docs/swap-member-auth.md（两域令牌隔离、401 而非 403、refresh 一次性轮换与复用检测、
-- 同设备类型互斥、注销前置校验、实名是换电前置）。
--
-- 兼容约定同 V7（不写 ENGINE/CHARSET/列 COMMENT/IF NOT EXISTS；生成列用 CASE WHEN 且不写 STORED；
-- H2 与 MySQL 共用一套）。**H2 不支持 IF() 函数**，条件表达式一律 CASE WHEN。
-- ============================================================

-- ---------------- 会员会话（令牌族 + refresh 轮换 + 复用检测的载体） ----------------
-- 一次登录 = 一个 session_family；每次 refresh 轮换新增一行并把旧行置 ROTATED。
-- 复用检测：再次出示已 ROTATED 的 refresh → 判定泄露 → 撤销该 family 全部会话。
CREATE TABLE member_session (
    id                 BIGINT       NOT NULL,
    member_id          BIGINT       NOT NULL,
    session_family     VARCHAR(64)  NOT NULL,
    device_type        VARCHAR(16)  NOT NULL,
    device_fp_hash     VARCHAR(64),
    access_jti         VARCHAR(64)  NOT NULL,
    refresh_hash       CHAR(64)     NOT NULL,
    rotated_from_hash  CHAR(64),
    sess_state         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    issued_at          DATETIME(3)  NOT NULL,
    access_expires_at  DATETIME(3)  NOT NULL,
    refresh_expires_at DATETIME(3)  NOT NULL,
    last_seen_at       DATETIME(3),
    last_ip            VARCHAR(64),
    user_agent         VARCHAR(255),
    revoked_reason     VARCHAR(48),
    revoked_by         BIGINT,
    active_family      VARCHAR(96) AS (CASE WHEN sess_state = 'ACTIVE' THEN CONCAT(member_id, '#', device_type) ELSE NULL END),
    create_by          BIGINT,
    create_time        DATETIME,
    update_by          BIGINT,
    update_time        DATETIME,
    remark             VARCHAR(255),
    version            INT          NOT NULL DEFAULT 0,
    del_flag           INT          NOT NULL DEFAULT 0,
    tenant_id          BIGINT       DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_msess_state CHECK (sess_state IN ('ACTIVE', 'ROTATED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_msess_device CHECK (device_type IN ('H5', 'APP', 'MINI', 'VEHICLE', 'OPS')),
    CONSTRAINT ck_msess_ttl CHECK (refresh_expires_at > access_expires_at AND access_expires_at > issued_at)
);
-- @enum member_session.sess_state: ACTIVE|ROTATED|REVOKED|EXPIRED
-- @enum member_session.device_type: H5|APP|MINI|VEHICLE|OPS
CREATE UNIQUE INDEX uk_msess_refresh ON member_session (refresh_hash);
CREATE UNIQUE INDEX uk_msess_jti ON member_session (access_jti);
-- I-MS-FAMILY：同一会员的同一设备类型至多一个生效会话族。
-- 语义是"多设备类型并存（手机 + 车机），同类型只保留最新"——新登录前必须把旧会话族置 REVOKED，
-- 而不是靠应用代码自觉（漏了就出现同类型两个会话族同时有效，强制下线只对其中一个生效）。
CREATE UNIQUE INDEX uk_msess_active_family ON member_session (active_family);
CREATE INDEX idx_msess_member ON member_session (member_id, sess_state);
CREATE INDEX idx_msess_family ON member_session (session_family);
CREATE INDEX idx_msess_expire ON member_session (sess_state, refresh_expires_at);

-- ---------------- 短信验证码（可替换通道；哈希存储，不存明文码） ----------------
CREATE TABLE member_sms_code (
    id             BIGINT      NOT NULL,
    phone_hash     CHAR(64)    NOT NULL,
    purpose        VARCHAR(16) NOT NULL,
    code_hash      CHAR(64)    NOT NULL,
    code_state     VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts       INT         NOT NULL DEFAULT 0,
    max_attempts   INT         NOT NULL DEFAULT 5,
    sent_at        DATETIME(3) NOT NULL,
    expires_at     DATETIME(3) NOT NULL,
    consumed_at    DATETIME(3),
    send_ip        VARCHAR(64),
    create_time    DATETIME,
    update_time    DATETIME,
    version        INT         NOT NULL DEFAULT 0,
    tenant_id      BIGINT      DEFAULT 1,
    active_key     VARCHAR(128) AS (CASE WHEN code_state = 'PENDING' THEN CONCAT(phone_hash, '#', purpose) ELSE NULL END),
    PRIMARY KEY (id),
    CONSTRAINT ck_msms_purpose CHECK (purpose IN ('LOGIN', 'REALNAME', 'CANCEL', 'REBIND', 'BIND')),
    CONSTRAINT ck_msms_state CHECK (code_state IN ('PENDING', 'CONSUMED', 'EXPIRED', 'FAILED')),
    CONSTRAINT ck_msms_attempts CHECK (attempts >= 0 AND max_attempts > 0),
    -- 验证码必须有有效期上限，防止"永不过期的万能码"
    CONSTRAINT ck_msms_ttl CHECK (expires_at > sent_at)
);
-- @enum member_sms_code.purpose: LOGIN|REALNAME|CANCEL|REBIND|BIND
-- @enum member_sms_code.code_state: PENDING|CONSUMED|EXPIRED|FAILED
-- I-MS-SMS：同一手机号同一用途至多一条待验证验证码（发新码前须让旧码失效）。
-- 这是"验证码轰炸"与"多码并存导致任一码均可通过"两类问题的 DB 级防线。
CREATE UNIQUE INDEX uk_msms_active ON member_sms_code (active_key);
CREATE UNIQUE INDEX uk_msms_hash ON member_sms_code (code_hash);
CREATE INDEX idx_msms_phone_time ON member_sms_code (phone_hash, sent_at);
CREATE INDEX idx_msms_expire ON member_sms_code (code_state, expires_at);

-- ---------------- 实名认证申请（真相；member_user.realname_state 是它的投影） ----------------
-- 实名是"一次申请一条记录"的可追溯过程：驳回后重新申请要新增行，不允许覆盖历史。
CREATE TABLE member_realname (
    id                 BIGINT      NOT NULL,
    member_id          BIGINT      NOT NULL,
    real_name_cipher   VARCHAR(255) NOT NULL,
    id_no_cipher       VARCHAR(512) NOT NULL,
    id_no_hash         CHAR(64)    NOT NULL,
    mask_name          VARCHAR(32),
    mask_id_no         VARCHAR(32),
    review_state       VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED',
    channel            VARCHAR(16) NOT NULL DEFAULT 'MOCK',
    submitted_at       DATETIME(3) NOT NULL,
    reviewed_at        DATETIME(3),
    reviewer_id        BIGINT,
    reject_reason      VARCHAR(255),
    active_member      BIGINT AS (CASE WHEN review_state IN ('SUBMITTED', 'REVIEWING') THEN member_id ELSE NULL END),
    create_by          BIGINT,
    create_time        DATETIME,
    update_by          BIGINT,
    update_time        DATETIME,
    remark             VARCHAR(255),
    version            INT         NOT NULL DEFAULT 0,
    del_flag           INT         NOT NULL DEFAULT 0,
    tenant_id          BIGINT      DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_mrn_state CHECK (review_state IN ('SUBMITTED', 'REVIEWING', 'VERIFIED', 'REJECTED')),
    CONSTRAINT ck_mrn_channel CHECK (channel IN ('MOCK', 'MANUAL', 'API'))
);
-- @enum member_realname.review_state: SUBMITTED|REVIEWING|VERIFIED|REJECTED
-- @enum member_realname.channel: MOCK|MANUAL|API
-- I-MRN：一个会员同时至多一条在审申请（防止重复提交堆出多条待审记录）
CREATE UNIQUE INDEX uk_mrn_active ON member_realname (active_member);
CREATE INDEX idx_mrn_member ON member_realname (member_id, review_state);
CREATE INDEX idx_mrn_state ON member_realname (review_state, submitted_at);
CREATE INDEX idx_mrn_idno ON member_realname (id_no_hash);

-- ---------------- 会员表补充：注销落点 ----------------
-- 注销前置校验（有在途订单 / 有 ACTIVE 电池绑定 → 拒绝）**无法做成跨表 CHECK**，
-- 因此落在服务层守卫 + 回归测试；此处只补"注销后不可复用"的落库字段。
ALTER TABLE member_user ADD COLUMN canceled_at DATETIME(3);
ALTER TABLE member_user ADD COLUMN cancel_reason VARCHAR(64);
ALTER TABLE member_user ADD COLUMN realname_verified_at DATETIME(3);
-- 同一身份证只能实名一个会员：哈希唯一（跨会员防冒领与一证多号）
CREATE UNIQUE INDEX uk_member_idcard ON member_user (idcard_hash);

-- ---------------- 后台权限码：实名审核与会话管理 ----------------
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6612, 6610, '实名审核', 'F', NULL, NULL, 'member:realname:review', NULL, 2, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6613, 6610, '会话查看', 'F', NULL, NULL, 'member:session:read', NULL, 3, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6614, 6610, '强制下线', 'F', NULL, NULL, 'member:session:revoke', NULL, 4, 0, 0);

INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6612);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6613);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6614);
