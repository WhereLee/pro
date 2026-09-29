-- ============================================================
-- V7：物联网设备接入基座（framework.iot / framework.event）—— 与业务无关。
--
-- 定位：为 biz.swap（换电柜）提供设备台账、连接会话、指令、影子、消息留痕、
--       Outbox 与遥测存储的通用底座；barrier 样例不受影响，后续项目可直接复用。
--
-- 兼容约定（H2 2.2 MODE=MySQL 与 MySQL 8.0 共用一套脚本，逐项实测通过）：
--   1. 不写 ENGINE/CHARSET、不用列级 COMMENT、索引不加 IF NOT EXISTS（沿用 V3/V6 约定）。
--   2. 计算列一律 `AS (CASE WHEN ... THEN ... ELSE NULL END)`，**不写 STORED**
--      （MySQL 默认 VIRTUAL 且允许虚拟列上建唯一索引；H2 支持计算列建索引）。
--   3. **禁用 MySQL 的 IF() 函数**：H2 即使在 MODE=MySQL 下也不支持 IF()（实测语法错误），
--      条件表达式统一用 CASE WHEN。
--   4. "某状态下唯一"用生成列 + 唯一索引表达（H2 无部分索引、MySQL 亦无），见 I 编号注释。
--   5. append-only 表刻意不含 version/del_flag，且不建外键：
--      iot_message_log / iot_raw_payload / iot_telemetry / iot_msg_dedup。
--      原因：留痕与遥测须在父记录变更/删除后存活；且全局 logic-delete-field=delFlag
--      一旦在这些表建了列，MyBatis-Plus 会自动追加 del_flag=0 过滤，等于"历史可被隐删"。
--   6. 时间双写：DATETIME(3) 存可读值 + BIGINT epoch 毫秒供乱序/窗口判定（详见 swap-ddl.md §决定-7）。
--   7. tenant_id 一律建列并写入（默认 1），与 buddy.tenant.enabled 解耦：
--      开启多租户只是翻开关，不再需要 schema 迁移与数据回填。
-- ============================================================

-- ---------------- 产品（品类）与物模型 ----------------
-- 门槛参数默认值住这里；站点级覆盖只能"收紧不放宽"，该规则由 swap_site 的 CHECK 落 DB（V8）
CREATE TABLE iot_product (
    id                  BIGINT       NOT NULL,
    product_key         VARCHAR(64)  NOT NULL,
    product_name        VARCHAR(64)  NOT NULL,
    category            VARCHAR(16)  NOT NULL,
    model_version       INT          NOT NULL DEFAULT 1,
    min_soc             INT          NOT NULL DEFAULT 80,
    max_alloc_temp      DECIMAL(5, 1) NOT NULL DEFAULT 45.0,
    max_charge_temp     DECIMAL(5, 1) NOT NULL DEFAULT 55.0,
    telemetry_fresh_sec INT          NOT NULL DEFAULT 60,
    locate_fresh_sec    INT          NOT NULL DEFAULT 600,
    heartbeat_sec       INT          NOT NULL DEFAULT 120,
    enabled             INT          NOT NULL DEFAULT 1,
    create_by           BIGINT,
    create_time         DATETIME,
    update_by           BIGINT,
    update_time         DATETIME,
    remark              VARCHAR(255),
    version             INT          NOT NULL DEFAULT 0,
    del_flag            INT          NOT NULL DEFAULT 0,
    tenant_id           BIGINT       DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_iprod_soc CHECK (min_soc >= 0 AND min_soc <= 100),
    CONSTRAINT ck_iprod_cat CHECK (category IN ('CABINET', 'BATTERY', 'GATEWAY'))
);
-- @enum iot_product.category: CABINET|BATTERY|GATEWAY
CREATE UNIQUE INDEX uk_iprod_key ON iot_product (product_key);

-- 物模型：版本化 JSON（properties/metrics/events/commands）；已发布版本不可改，改则升版本
CREATE TABLE iot_thing_model (
    id            BIGINT       NOT NULL,
    product_key   VARCHAR(64)  NOT NULL,
    model_version INT          NOT NULL,
    spec_json     TEXT         NOT NULL,
    state         VARCHAR(16)  NOT NULL DEFAULT 'PUBLISHED',
    create_by     BIGINT,
    create_time   DATETIME,
    update_by     BIGINT,
    update_time   DATETIME,
    remark        VARCHAR(255),
    version       INT          NOT NULL DEFAULT 0,
    del_flag      INT          NOT NULL DEFAULT 0,
    tenant_id     BIGINT       DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_itm_state CHECK (state IN ('DRAFT', 'PUBLISHED', 'RETIRED'))
);
-- @enum iot_thing_model.state: DRAFT|PUBLISHED|RETIRED
CREATE UNIQUE INDEX uk_itm_ver ON iot_thing_model (product_key, model_version);

-- ---------------- 设备台账 ----------------
-- secret_cipher 存"主密钥密文"（AES-GCM，主密钥来自环境变量/KMS）；
-- connSecret / msgSecret 由运行时派生（HMAC(master,"conn"|"msg")），不落库、不打日志。
CREATE TABLE iot_device (
    id                BIGINT       NOT NULL,
    product_key       VARCHAR(64)  NOT NULL,
    device_id         VARCHAR(64)  NOT NULL,
    device_name       VARCHAR(64)  NOT NULL,
    gateway_row_id    BIGINT,
    secret_cipher     VARCHAR(512) NOT NULL,
    secret_version    INT          NOT NULL DEFAULT 1,
    firmware_version  VARCHAR(32),
    hardware_version  VARCHAR(32),
    ip_addr           VARCHAR(64),
    sim_no            VARCHAR(32),
    online_state      VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN',
    last_online_at    DATETIME(3),
    last_offline_at   DATETIME(3),
    last_seen_ts      BIGINT,
    offline_reason    VARCHAR(32),
    shadow_version    BIGINT       NOT NULL DEFAULT 0,
    activated_at      DATETIME(3),
    enabled           INT          NOT NULL DEFAULT 1,
    create_by         BIGINT,
    create_time       DATETIME,
    update_by         BIGINT,
    update_time       DATETIME,
    remark            VARCHAR(255),
    version           INT          NOT NULL DEFAULT 0,
    del_flag          INT          NOT NULL DEFAULT 0,
    tenant_id         BIGINT       DEFAULT 1,
    PRIMARY KEY (id),
    -- LWT 只加速感知，不作为唯一真相：UNKNOWN 是初始态（协议 §2.1）
    CONSTRAINT ck_idev_online CHECK (online_state IN ('ONLINE', 'OFFLINE', 'UNKNOWN', 'STALE')),
    CONSTRAINT ck_idev_gateway CHECK (gateway_row_id IS NULL OR gateway_row_id <> id)
);
-- @enum iot_device.online_state: ONLINE|OFFLINE|UNKNOWN|STALE
CREATE UNIQUE INDEX uk_idev_did ON iot_device (product_key, device_id);
CREATE INDEX idx_idev_online ON iot_device (online_state, last_seen_ts);
CREATE INDEX idx_idev_product ON iot_device (product_key, enabled);
CREATE INDEX idx_idev_gateway ON iot_device (gateway_row_id);
CREATE INDEX idx_idev_tenant ON iot_device (tenant_id);

-- ---------------- 连接会话（一次物理连接一行，防跨会话串单） ----------------
CREATE TABLE iot_device_session (
    id             BIGINT      NOT NULL,
    device_row_id  BIGINT      NOT NULL,
    session_id     VARCHAR(64) NOT NULL,
    node_id        VARCHAR(64) NOT NULL,
    client_ip      VARCHAR(64),
    keepalive_sec  INT         NOT NULL DEFAULT 120,
    clean_start    INT         NOT NULL DEFAULT 0,
    conn_state     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    connected_at   DATETIME(3) NOT NULL,
    disconnected_at DATETIME(3),
    close_reason   VARCHAR(32),
    last_hb_ts     BIGINT,
    active_device  BIGINT AS (CASE WHEN conn_state = 'ACTIVE' THEN device_row_id ELSE NULL END),
    create_time    DATETIME,
    update_time    DATETIME,
    version        INT         NOT NULL DEFAULT 0,
    del_flag       INT         NOT NULL DEFAULT 0,
    tenant_id      BIGINT      DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_isess_state CHECK (conn_state IN ('ACTIVE', 'CLOSED', 'EXPIRED'))
);
-- @enum iot_device_session.conn_state: ACTIVE|CLOSED|EXPIRED
-- I-DEV-SESSION：每台设备至多一条 ACTIVE 会话（重复登录/重连互踢的 DB 级防线）
CREATE UNIQUE INDEX uk_isess_active ON iot_device_session (active_device);
CREATE UNIQUE INDEX uk_isess_sid ON iot_device_session (session_id);
CREATE INDEX idx_isess_device ON iot_device_session (device_row_id, connected_at);

-- ---------------- 指令总线（生命周期见 swap-protocol.md §4.2） ----------------
CREATE TABLE iot_command (
    id             BIGINT       NOT NULL,
    cmd_id         CHAR(26)     NOT NULL,
    biz_type       VARCHAR(32)  NOT NULL,
    biz_id         BIGINT       NOT NULL,
    step_no        INT          NOT NULL,
    device_row_id  BIGINT       NOT NULL,
    product_key    VARCHAR(64)  NOT NULL,
    topic          VARCHAR(128) NOT NULL,
    cmd_code       VARCHAR(32)  NOT NULL,
    payload_json   TEXT,
    qos            TINYINT      NOT NULL DEFAULT 1,
    priority       INT          NOT NULL DEFAULT 100,
    cmd_state      VARCHAR(16)  NOT NULL DEFAULT 'CREATED',
    retry_left     INT          NOT NULL DEFAULT 0,
    retry_max      INT          NOT NULL DEFAULT 0,
    ttl_sec        INT          NOT NULL DEFAULT 60,
    session_id     VARCHAR(64),
    trace_id       VARCHAR(64),
    operator_id    BIGINT,
    sent_at        DATETIME(3),
    sent_ts        BIGINT,
    ack_at         DATETIME(3),
    reply_code     VARCHAR(16),
    reply_json     TEXT,
    confirmed_at   DATETIME(3),
    expire_at      DATETIME(3),
    deadline_ts    BIGINT,
    fail_reason    VARCHAR(255),
    active_step    VARCHAR(96) AS (CASE WHEN cmd_state IN ('CREATED', 'DISPATCHED', 'ACKED', 'UNCONFIRMED') THEN CONCAT(biz_type, '#', biz_id, '#', step_no) ELSE NULL END),
    create_by      BIGINT,
    create_time    DATETIME,
    update_by      BIGINT,
    update_time    DATETIME,
    remark         VARCHAR(255),
    version        INT          NOT NULL DEFAULT 0,
    del_flag       INT          NOT NULL DEFAULT 0,
    tenant_id      BIGINT       DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_icmd_state CHECK (cmd_state IN
        ('CREATED', 'DISPATCHED', 'ACKED', 'CONFIRMED', 'NACKED', 'TIMEOUT', 'EXPIRED', 'SUPERSEDED', 'UNCONFIRMED'))
);
-- @enum iot_command.cmd_state: CREATED|DISPATCHED|ACKED|CONFIRMED|NACKED|TIMEOUT|EXPIRED|SUPERSEDED|UNCONFIRMED
CREATE UNIQUE INDEX uk_icmd_cmid ON iot_command (cmd_id);
-- 兜底扫描主路径：status + deadline（协议 §7.4），必须走索引不得全表扫
CREATE INDEX idx_icmd_scan ON iot_command (cmd_state, deadline_ts);
CREATE INDEX idx_icmd_biz ON iot_command (biz_type, biz_id, step_no);
CREATE INDEX idx_icmd_device ON iot_command (device_row_id, create_time);
-- I-CMD-ACTIVE：同一业务步骤至多一条"在途"指令（重发前必须先把旧的置 SUPERSEDED）
CREATE UNIQUE INDEX uk_icmd_active ON iot_command (active_step);

-- ---------------- 影子（期望态/上报态/差异，属性类收敛配置专用） ----------------
CREATE TABLE iot_shadow (
    id             BIGINT      NOT NULL,
    device_row_id  BIGINT      NOT NULL,
    desired_json   TEXT,
    reported_json  TEXT,
    delta_json     TEXT,
    desired_ver    BIGINT      NOT NULL DEFAULT 0,
    reported_ver   BIGINT      NOT NULL DEFAULT 0,
    sync_state     VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    synced_at      DATETIME(3),
    create_time    DATETIME,
    update_time    DATETIME,
    version        INT         NOT NULL DEFAULT 0,
    del_flag       INT         NOT NULL DEFAULT 0,
    tenant_id      BIGINT      DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_ishd_sync CHECK (sync_state IN ('PENDING', 'IN_SYNC', 'DIVERGED'))
);
-- @enum iot_shadow.sync_state: PENDING|IN_SYNC|DIVERGED
CREATE UNIQUE INDEX uk_ishd_device ON iot_shadow (device_row_id);

-- ---------------- 消息留痕（append-only：协议排障与重放的唯一依据） ----------------
CREATE TABLE iot_message_log (
    id             BIGINT       NOT NULL,
    direction      VARCHAR(8)   NOT NULL,
    product_key    VARCHAR(64),
    device_row_id  BIGINT,
    topic          VARCHAR(128) NOT NULL,
    msg_id         CHAR(26),
    qos            TINYINT,
    session_id     VARCHAR(64),
    trace_id       VARCHAR(64),
    payload        TEXT,
    valid_flag     INT          NOT NULL DEFAULT 1,
    reject_code    VARCHAR(16),
    reject_step    VARCHAR(32),
    occurred_at    DATETIME(3)  NOT NULL,
    ts_millis      BIGINT       NOT NULL,
    create_time    DATETIME,
    tenant_id      BIGINT       DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_imsg_dir CHECK (direction IN ('UP', 'DOWN'))
);
-- @enum iot_message_log.direction: UP|DOWN
CREATE INDEX idx_imsg_device ON iot_message_log (device_row_id, ts_millis);
CREATE INDEX idx_imsg_msgid ON iot_message_log (msg_id);
CREATE INDEX idx_imsg_time ON iot_message_log (occurred_at);

-- 上行去重（协议 §11.1）：物理唯一约束兜住 QoS1 重复；过期行由清理 Job 删除
CREATE TABLE iot_msg_dedup (
    id             BIGINT      NOT NULL,
    device_row_id  BIGINT      NOT NULL,
    msg_id         CHAR(26)    NOT NULL,
    topic          VARCHAR(128),
    event_type     VARCHAR(40),
    received_at    DATETIME(3) NOT NULL,
    expire_at      DATETIME(3) NOT NULL,
    create_time    DATETIME,
    tenant_id      BIGINT      DEFAULT 1,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_idedup_msg ON iot_msg_dedup (device_row_id, msg_id);
CREATE INDEX idx_idedup_expire ON iot_msg_dedup (expire_at);

-- 不合规模型的原始报文必须留存（协议 §11.4：不得因数据不合规就丢报文）
CREATE TABLE iot_raw_payload (
    id             BIGINT       NOT NULL,
    device_row_id  BIGINT,
    product_key    VARCHAR(64),
    msg_id         CHAR(26),
    topic          VARCHAR(128),
    payload        TEXT         NOT NULL,
    reason         VARCHAR(32)  NOT NULL,
    detail         TEXT,
    received_at    DATETIME(3)  NOT NULL,
    create_time    DATETIME,
    tenant_id      BIGINT       DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_iraw_reason CHECK (reason IN ('MALFORMED', 'SCHEMA_VIOLATION', 'UNKNOWN_PRODUCT', 'UNKNOWN_FIELD_TYPE', 'OUT_OF_RANGE'))
);
-- @enum iot_raw_payload.reason: MALFORMED|SCHEMA_VIOLATION|UNKNOWN_PRODUCT|UNKNOWN_FIELD_TYPE|OUT_OF_RANGE
CREATE INDEX idx_iraw_device ON iot_raw_payload (device_row_id, received_at);

-- ---------------- Outbox（事务与消息的原子性，协议 §11.2） ----------------
CREATE TABLE outbox_event (
    id              BIGINT       NOT NULL,
    dedup_key       VARCHAR(128) NOT NULL,
    aggregate_type  VARCHAR(32)  NOT NULL,
    aggregate_id    BIGINT       NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    topic           VARCHAR(128) NOT NULL,
    payload         TEXT         NOT NULL,
    headers         TEXT,
    trace_id        VARCHAR(64),
    outbox_state    VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    retry_count     INT          NOT NULL DEFAULT 0,
    retry_max       INT          NOT NULL DEFAULT 8,
    next_retry_at   DATETIME(3),
    deadline_ts     BIGINT,
    occurred_at     DATETIME(3)  NOT NULL,
    sent_at         DATETIME(3),
    last_error      VARCHAR(500),
    create_time     DATETIME,
    update_time     DATETIME,
    version         INT          NOT NULL DEFAULT 0,
    tenant_id       BIGINT       DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_obx_state CHECK (outbox_state IN ('PENDING', 'SENT', 'DEAD'))
);
-- @enum outbox_event.outbox_state: PENDING|SENT|DEAD
CREATE UNIQUE INDEX uk_obx_dedup ON outbox_event (dedup_key);
CREATE INDEX idx_obx_scan ON outbox_event (outbox_state, next_retry_at);

-- ---------------- 遥测（高写入、可丢弃不影响主链路；分区在应用侧维护） ----------------
-- 复合主键含 occurred_at：为 MySQL RANGE COLUMNS 分区预留（分区键必须进每个唯一索引）。
-- 分区的新建/淘汰不放在 Flyway（一次性迁移无法承担持续滚动），由 M1 的 TelemetryPartitionJob 负责。
CREATE TABLE iot_telemetry (
    id             BIGINT       NOT NULL,
    device_row_id  BIGINT       NOT NULL,
    product_key    VARCHAR(64)  NOT NULL,
    metric_kind    VARCHAR(16)  NOT NULL,
    props_json     TEXT         NOT NULL,
    occurred_at    DATETIME(3)  NOT NULL,
    ts_millis      BIGINT       NOT NULL,
    seq_no         BIGINT,
    create_time    DATETIME,
    tenant_id      BIGINT       DEFAULT 1,
    PRIMARY KEY (id, occurred_at),
    CONSTRAINT ck_itl_kind CHECK (metric_kind IN ('CABINET', 'BATTERY', 'CHARGER', 'ENV'))
);
-- @enum iot_telemetry.metric_kind: CABINET|BATTERY|CHARGER|ENV
CREATE INDEX idx_itl_device ON iot_telemetry (device_row_id, ts_millis);
CREATE INDEX idx_itl_kind_time ON iot_telemetry (metric_kind, occurred_at);

-- ---------------- 换电柜样例种子（clone 即可接入模拟器） ----------------
INSERT INTO iot_product (id, product_key, product_name, category, model_version, min_soc, max_alloc_temp, max_charge_temp, telemetry_fresh_sec, locate_fresh_sec, heartbeat_sec, enabled, create_time, version, del_flag, tenant_id)
VALUES (1, 'SWAP-CAB-8', '8 仓换电柜（样例）', 'CABINET', 1, 80, 45.0, 55.0, 60, 600, 120, 1, CURRENT_TIMESTAMP, 0, 0, 1);
INSERT INTO iot_product (id, product_key, product_name, category, model_version, min_soc, max_alloc_temp, max_charge_temp, telemetry_fresh_sec, locate_fresh_sec, heartbeat_sec, enabled, create_time, version, del_flag, tenant_id)
VALUES (2, 'BAT-60V20AH', '60V20Ah 锂电池（样例）', 'BATTERY', 1, 80, 45.0, 55.0, 60, 600, 0, 1, CURRENT_TIMESTAMP, 0, 0, 1);

INSERT INTO iot_thing_model (id, product_key, model_version, spec_json, state, create_time, version, del_flag, tenant_id)
VALUES (1, 'SWAP-CAB-8', 1, '{"properties":{"cabinetTemp":{"type":"decimal","unit":"celsius","min":-40,"max":120},"power":{"type":"decimal","unit":"kW"}},"events":["door_open","door_close","door_fault","battery_detected","battery_taken","slot_occupied","slot_empty","charge_start","charge_stop","alarm","alarm_clear","swap_result","session_ready"],"commands":["QUERY_STATUS","SET_PARAMS","OPEN_SLOT","LOCK_SLOT","UNLOCK_SLOT","START_CHARGE","STOP_CHARGE","EMERGENCY_STOP","BATTERY_VERIFY","BUZZER","REBOOT","OTA_PUSH"]}', 'PUBLISHED', CURRENT_TIMESTAMP, 0, 0, 1);
INSERT INTO iot_thing_model (id, product_key, model_version, spec_json, state, create_time, version, del_flag, tenant_id)
VALUES (2, 'BAT-60V20AH', 1, '{"properties":{"soc":{"type":"int","unit":"percent","min":0,"max":100},"soh":{"type":"decimal","unit":"percent"},"voltage":{"type":"decimal","unit":"V"},"current":{"type":"decimal","unit":"A"},"maxCellTemp":{"type":"decimal","unit":"celsius"},"cycleCount":{"type":"int"},"faultCode":{"type":"string"},"interlock":{"type":"bool"}},"events":["battery_detected","charge_start","charge_stop","alarm"],"commands":["BATTERY_VERIFY"]}', 'PUBLISHED', CURRENT_TIMESTAMP, 0, 0, 1);
