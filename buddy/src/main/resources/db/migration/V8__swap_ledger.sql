-- ============================================================
-- V8：换电柜台账与资产（biz.swap）——站点 / 柜机 / 仓位 / 电池 + 绑定 / 观测 / 冲突。
--
-- 关键设计（详见 swap-ddl.md）：
--   1. 柜机不重复存设备字段：iot_device 是设备主数据，swap_cabinet 只存换电特有属性，
--      以 device_row_id 1:1 关联（框架包边界：framework/iot 不得出现"柜"语义）。
--   2. 仓位不建为设备：无独立通信身份，是柜机的子资源，按 cabinet_id + slot_no 寻址。
--   3. 电池是独立设备（productKey=BAT-*），"在哪个仓 / 在谁手上"是运行期归属，
--      归 swap_battery_binding（多行历史）而非 swap_battery（当前态投影）。
--   4. **swap_battery.holder_user_id / current_slot_id 是投影缓存，唯一真相是 binding + 事件流**；
--      投影不一致由日终对账（I7）发现并修复，不允许业务代码绕过 binding 直接改投影。
--   5. "站点只能收紧门槛"的规则要能在 DB 表达，就必须把型号默认值**冗余一份到本表**
--      （CHECK 不能跨表引用）。代价：型号默认值变更时冗余列会漂移，
--      由 M1 的阈值一致性校验（漂移即告警）兜住，不假装它不存在。
-- ============================================================

-- ---------------- 站点 ----------------
CREATE TABLE swap_site (
    id                    BIGINT         NOT NULL,
    site_no               VARCHAR(32)    NOT NULL,
    site_name             VARCHAR(64)    NOT NULL,
    province              VARCHAR(32),
    city                  VARCHAR(32),
    district              VARCHAR(32),
    address               VARCHAR(255),
    lng                   DECIMAL(10, 6),
    lat                   DECIMAL(10, 6),
    fence_radius_m        INT            NOT NULL DEFAULT 200,
    product_key           VARCHAR(64)    NOT NULL,
    product_min_soc       INT            NOT NULL DEFAULT 80,
    site_min_soc          INT,
    product_max_alloc_temp DECIMAL(5, 1) NOT NULL DEFAULT 45.0,
    site_max_alloc_temp   DECIMAL(5, 1),
    offline_swap_allowed  INT            NOT NULL DEFAULT 0,
    contact_name          VARCHAR(32),
    contact_phone_cipher  VARCHAR(255),
    enabled               INT            NOT NULL DEFAULT 1,
    create_by             BIGINT,
    create_time           DATETIME,
    update_by             BIGINT,
    update_time           DATETIME,
    remark                VARCHAR(255),
    version               INT            NOT NULL DEFAULT 0,
    del_flag              INT            NOT NULL DEFAULT 0,
    tenant_id             BIGINT         DEFAULT 1,
    PRIMARY KEY (id),
    -- 站点覆盖只允许收紧（放宽等于站点单方降低安全底线，必须是总部权限）
    CONSTRAINT ck_site_minsoc CHECK (site_min_soc IS NULL OR (site_min_soc >= product_min_soc AND site_min_soc <= 100)),
    CONSTRAINT ck_site_temp CHECK (site_max_alloc_temp IS NULL OR (site_max_alloc_temp <= product_max_alloc_temp AND site_max_alloc_temp > -40)),
    CONSTRAINT ck_site_offline CHECK (offline_swap_allowed IN (0, 1))
);
CREATE UNIQUE INDEX uk_site_no ON swap_site (site_no);
CREATE INDEX idx_site_tenant ON swap_site (tenant_id, enabled);

-- ---------------- 柜机（1:1 关联 iot_device） ----------------
CREATE TABLE swap_cabinet (
    id              BIGINT      NOT NULL,
    device_row_id   BIGINT      NOT NULL,
    site_id         BIGINT      NOT NULL,
    cabinet_no      VARCHAR(32) NOT NULL,
    slot_count      INT         NOT NULL DEFAULT 8,
    cabinet_model   VARCHAR(64),
    lock_type       VARCHAR(32),
    power_limit_kw  DECIMAL(6, 2),
    cabinet_state   VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    locked_reason   VARCHAR(64),
    last_swap_at    DATETIME(3),
    create_by       BIGINT,
    create_time     DATETIME,
    update_by       BIGINT,
    update_time     DATETIME,
    remark          VARCHAR(255),
    version         INT         NOT NULL DEFAULT 0,
    del_flag        INT         NOT NULL DEFAULT 0,
    tenant_id       BIGINT      DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_cab_state CHECK (cabinet_state IN ('NORMAL', 'FAULT', 'DISABLED', 'MAINTENANCE', 'SAFETY_LOCKED')),
    CONSTRAINT ck_cab_slots CHECK (slot_count > 0 AND slot_count <= 64)
);
-- @enum swap_cabinet.cabinet_state: NORMAL|FAULT|DISABLED|MAINTENANCE|SAFETY_LOCKED
CREATE UNIQUE INDEX uk_cab_device ON swap_cabinet (device_row_id);
CREATE UNIQUE INDEX uk_cab_no ON swap_cabinet (cabinet_no);
CREATE INDEX idx_cab_site ON swap_cabinet (site_id, cabinet_state);

-- ---------------- 仓位（柜机子资源；单行单态 ⇒ "一仓至多一笔在途"天然成立） ----------------
-- 说明：本表的 I2 不变式由"一仓一行 + 状态迁移走 @Version CAS"保证；
--       需要 DB 唯一约束兜住的是"预占台账"（同一仓多条 ACTIVE），见 swap_slot_reservation。
CREATE TABLE swap_slot (
    id                BIGINT      NOT NULL,
    cabinet_id        BIGINT      NOT NULL,
    slot_no           INT         NOT NULL,
    slot_state        VARCHAR(24) NOT NULL DEFAULT 'IDLE_EMPTY',
    battery_id        BIGINT,
    reserved_order_id BIGINT,
    door_state        VARCHAR(16) NOT NULL DEFAULT 'CLOSED',
    lock_state        VARCHAR(16) NOT NULL DEFAULT 'LOCKED',
    charge_state      VARCHAR(16) NOT NULL DEFAULT 'IDLE',
    last_temp         DECIMAL(5, 1),
    charger_temp      DECIMAL(5, 1),
    last_detected_at  DATETIME(3),
    last_full_at      DATETIME(3),
    fault_code        VARCHAR(32),
    disabled_flag     INT         NOT NULL DEFAULT 0,
    create_by         BIGINT,
    create_time       DATETIME,
    update_by         BIGINT,
    update_time       DATETIME,
    remark            VARCHAR(255),
    version           INT         NOT NULL DEFAULT 0,
    del_flag          INT         NOT NULL DEFAULT 0,
    tenant_id         BIGINT      DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_slot_state CHECK (slot_state IN
        ('IDLE_EMPTY', 'IDLE_CHARGING', 'RESERVED_ORDER', 'OPEN_IN_USE', 'PENDING_PICKUP', 'FAULT', 'DISABLED', 'ISOLATED')),
    CONSTRAINT ck_slot_door CHECK (door_state IN ('CLOSED', 'OPEN', 'UNKNOWN', 'FAULT')),
    CONSTRAINT ck_slot_lock CHECK (lock_state IN ('LOCKED', 'UNLOCKED', 'UNKNOWN', 'FAULT')),
    CONSTRAINT ck_slot_charge CHECK (charge_state IN ('IDLE', 'CHARGING', 'FULL', 'FAULT', 'STOPPED'))
);
-- @enum swap_slot.slot_state: IDLE_EMPTY|IDLE_CHARGING|RESERVED_ORDER|OPEN_IN_USE|PENDING_PICKUP|FAULT|DISABLED|ISOLATED
-- @enum swap_slot.door_state: CLOSED|OPEN|UNKNOWN|FAULT
-- @enum swap_slot.lock_state: LOCKED|UNLOCKED|UNKNOWN|FAULT
-- @enum swap_slot.charge_state: IDLE|CHARGING|FULL|FAULT|STOPPED
CREATE UNIQUE INDEX uk_slot_no ON swap_slot (cabinet_id, slot_no);
CREATE INDEX idx_slot_state ON swap_slot (slot_state);
CREATE INDEX idx_slot_order ON swap_slot (reserved_order_id);
CREATE INDEX idx_slot_battery ON swap_slot (battery_id);

-- 预占台账（同时承担两件事：仓位占用的审计历史 + I2 的 DB 唯一约束落点）
CREATE TABLE swap_slot_reservation (
    id             BIGINT       NOT NULL,
    slot_id        BIGINT       NOT NULL,
    order_id       BIGINT       NOT NULL,
    use_role       VARCHAR(16)  NOT NULL,
    resv_state     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    reserved_at    DATETIME(3)  NOT NULL,
    released_at    DATETIME(3),
    release_reason VARCHAR(32),
    active_slot    BIGINT AS (CASE WHEN resv_state = 'ACTIVE' THEN slot_id ELSE NULL END),
    create_time    DATETIME,
    update_time    DATETIME,
    version        INT          NOT NULL DEFAULT 0,
    del_flag       INT          NOT NULL DEFAULT 0,
    tenant_id      BIGINT       DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_resv_role CHECK (use_role IN ('RETURN', 'OFFER')),
    CONSTRAINT ck_resv_state CHECK (resv_state IN ('ACTIVE', 'RELEASED', 'EXPIRED'))
);
-- @enum swap_slot_reservation.use_role: RETURN|OFFER
-- @enum swap_slot_reservation.resv_state: ACTIVE|RELEASED|EXPIRED
-- I2：一个仓位同一时刻至多一笔生效预占（并发抢仓的 DB 级最终防线，绕不过去）
CREATE UNIQUE INDEX uk_resv_active ON swap_slot_reservation (active_slot);
CREATE INDEX idx_resv_order ON swap_slot_reservation (order_id, resv_state);

-- ---------------- 电池档案与资产态 ----------------
CREATE TABLE swap_battery (
    id                BIGINT      NOT NULL,
    device_row_id     BIGINT,
    battery_code      VARCHAR(64) NOT NULL,
    product_key       VARCHAR(64) NOT NULL,
    voltage_v         DECIMAL(5, 1),
    capacity_ah       DECIMAL(5, 1),
    interface_type    VARCHAR(32),
    battery_state     VARCHAR(24) NOT NULL DEFAULT 'IN_STOCK',
    own_type          VARCHAR(16) NOT NULL DEFAULT 'PLATFORM',
    holder_user_id    BIGINT,
    current_cabinet_id BIGINT,
    current_slot_id   BIGINT,
    location_state    VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    soc               INT,
    soh               DECIMAL(5, 1),
    cycle_count       INT         NOT NULL DEFAULT 0,
    last_full_at      DATETIME(3),
    last_report_at    DATETIME(3),
    fault_code        VARCHAR(32),
    isolated_reason   VARCHAR(64),
    activated_at      DATETIME(3),
    scrapped_at       DATETIME(3),
    create_by         BIGINT,
    create_time       DATETIME,
    update_by         BIGINT,
    update_time       DATETIME,
    remark            VARCHAR(255),
    version           INT         NOT NULL DEFAULT 0,
    del_flag          INT         NOT NULL DEFAULT 0,
    tenant_id         BIGINT      DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_batt_state CHECK (battery_state IN
        ('IN_STOCK', 'IN_CABINET_CHARGING', 'HELD_BY_USER', 'PENDING_PICKUP', 'ISOLATED', 'MAINTENANCE', 'LOST', 'SCRAPPED')),
    CONSTRAINT ck_batt_own CHECK (own_type IN ('PLATFORM', 'USER_OWNED')),
    CONSTRAINT ck_batt_loc CHECK (location_state IN ('KNOWN', 'UNKNOWN')),
    CONSTRAINT ck_batt_soc CHECK (soc IS NULL OR (soc >= 0 AND soc <= 100)),
    CONSTRAINT ck_batt_soh CHECK (soh IS NULL OR (soh >= 0 AND soh <= 100))
);
-- @enum swap_battery.battery_state: IN_STOCK|IN_CABINET_CHARGING|HELD_BY_USER|PENDING_PICKUP|ISOLATED|MAINTENANCE|LOST|SCRAPPED
-- @enum swap_battery.own_type: PLATFORM|USER_OWNED
-- @enum swap_battery.location_state: KNOWN|UNKNOWN
CREATE UNIQUE INDEX uk_batt_code ON swap_battery (battery_code);
CREATE INDEX idx_batt_state ON swap_battery (battery_state, product_key);
CREATE INDEX idx_batt_cabinet ON swap_battery (current_cabinet_id);
CREATE INDEX idx_batt_holder ON swap_battery (holder_user_id);
CREATE INDEX idx_batt_tenant ON swap_battery (tenant_id);

-- ---------------- 使用权绑定（多行历史；I1/I9 的 DB 落点） ----------------
-- M6 放开"一人多电池"时：只需去掉 active_user 索引并改生成列口径，表结构不动。
CREATE TABLE swap_battery_binding (
    id            BIGINT      NOT NULL,
    battery_id    BIGINT      NOT NULL,
    user_id       BIGINT      NOT NULL,
    bind_state    VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    bind_source   VARCHAR(16) NOT NULL,
    order_id      BIGINT,
    evidence      VARCHAR(16) NOT NULL DEFAULT 'STRONG',
    start_at      DATETIME(3) NOT NULL,
    end_at        DATETIME(3),
    end_reason    VARCHAR(32),
    active_battery BIGINT AS (CASE WHEN bind_state = 'ACTIVE' THEN battery_id ELSE NULL END),
    active_user   BIGINT AS (CASE WHEN bind_state = 'ACTIVE' THEN user_id ELSE NULL END),
    create_by     BIGINT,
    create_time   DATETIME,
    update_by     BIGINT,
    update_time   DATETIME,
    remark        VARCHAR(255),
    version       INT         NOT NULL DEFAULT 0,
    del_flag      INT         NOT NULL DEFAULT 0,
    tenant_id     BIGINT      DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_bind_state CHECK (bind_state IN ('ACTIVE', 'HIST')),
    -- 归属变更只能由订单终态或人工核销触发（FSM §4.5.4 / I10）：枚举层面就排除"观测写入"
    CONSTRAINT ck_bind_source CHECK (bind_source IN ('ORDER', 'MANUAL', 'IMPORT')),
    CONSTRAINT ck_bind_evidence CHECK (evidence IN ('STRONG', 'DEGRADED'))
);
-- @enum swap_battery_binding.bind_state: ACTIVE|HIST
-- @enum swap_battery_binding.bind_source: ORDER|MANUAL|IMPORT
-- @enum swap_battery_binding.evidence: STRONG|DEGRADED
-- I1/I9：一块电池至多一条生效绑定；本期 B3 下"一人至多一块"同样由 DB 兜住
CREATE UNIQUE INDEX uk_bind_active_batt ON swap_battery_binding (active_battery);
CREATE UNIQUE INDEX uk_bind_active_user ON swap_battery_binding (active_user);
CREATE INDEX idx_bind_user ON swap_battery_binding (user_id, bind_state);
CREATE INDEX idx_bind_order ON swap_battery_binding (order_id);

-- ---------------- 观测流水（append-only；**永远不得改归属**） ----------------
CREATE TABLE swap_battery_observation (
    id               BIGINT      NOT NULL,
    battery_id       BIGINT      NOT NULL,
    source           VARCHAR(16) NOT NULL,
    via_cabinet_id   BIGINT,
    device_row_id    BIGINT,
    msg_id           CHAR(26),
    order_id         BIGINT,
    observed_slot_id BIGINT,
    soc              INT,
    soh              DECIMAL(5, 1),
    max_cell_temp    DECIMAL(5, 1),
    voltage          DECIMAL(6, 2),
    fault_code       VARCHAR(32),
    lng              DECIMAL(10, 6),
    lat              DECIMAL(10, 6),
    accuracy_m       INT,
    fresh_until_ts   BIGINT,
    observed_at      DATETIME(3) NOT NULL,
    ts_millis        BIGINT      NOT NULL,
    create_time      DATETIME,
    tenant_id        BIGINT      DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_obs_source CHECK (source IN ('SELF', 'CABINET', 'CLOUD_LAST'))
);
-- @enum swap_battery_observation.source: SELF|CABINET|CLOUD_LAST
CREATE INDEX idx_obs_batt ON swap_battery_observation (battery_id, ts_millis);
CREATE INDEX idx_obs_msg ON swap_battery_observation (msg_id);

-- ---------------- 跨源观测冲突（FSM §4.5.3 O2：不覆盖、不择一、不猜测） ----------------
CREATE TABLE swap_battery_conflict (
    id             BIGINT      NOT NULL,
    battery_id     BIGINT      NOT NULL,
    window_key     VARCHAR(64) NOT NULL,
    self_obs_id    BIGINT,
    cabinet_obs_id BIGINT,
    detail         TEXT,
    handle_state   VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    raised_by      VARCHAR(16) NOT NULL DEFAULT 'RECONCILE',
    assignee_id    BIGINT,
    resolved_by    BIGINT,
    resolved_at    DATETIME(3),
    handle_result  VARCHAR(255),
    create_by      BIGINT,
    create_time    DATETIME,
    update_by      BIGINT,
    update_time    DATETIME,
    remark         VARCHAR(255),
    version        INT         NOT NULL DEFAULT 0,
    del_flag       INT         NOT NULL DEFAULT 0,
    tenant_id      BIGINT      DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_conf_state CHECK (handle_state IN ('OPEN', 'RESOLVED', 'DISMISSED')),
    CONSTRAINT ck_conf_raiser CHECK (raised_by IN ('RECONCILE', 'SWAP', 'GEOFENCE', 'MANUAL'))
);
-- @enum swap_battery_conflict.handle_state: OPEN|RESOLVED|DISMISSED
-- @enum swap_battery_conflict.raised_by: RECONCILE|SWAP|GEOFENCE|MANUAL
CREATE UNIQUE INDEX uk_conf_window ON swap_battery_conflict (battery_id, window_key);
CREATE INDEX idx_conf_state ON swap_battery_conflict (handle_state, create_time);

-- ---------------- 样例种子（1 站点 + 1 柜 + 8 仓 + 2 块电池 + 1 台柜设备记录） ----------------
INSERT INTO swap_site (id, site_no, site_name, province, city, district, address, lng, lat, fence_radius_m, product_key, product_min_soc, site_min_soc, product_max_alloc_temp, site_max_alloc_temp, offline_swap_allowed, enabled, create_time, version, del_flag, tenant_id)
VALUES (1, 'SITE-0001', '演示站点', '浙江省', '杭州市', '西湖区', '文一西路 1 号', 120.080000, 30.280000, 200, 'SWAP-CAB-8', 80, NULL, 45.0, NULL, 0, 1, CURRENT_TIMESTAMP, 0, 0, 1);

INSERT INTO iot_device (id, product_key, device_id, device_name, gateway_row_id, secret_cipher, secret_version, firmware_version, online_state, shadow_version, activated_at, enabled, create_time, version, del_flag, tenant_id)
VALUES (1, 'SWAP-CAB-8', 'CAB0000001', '1 号换电柜（样例）', NULL, 'DEMO-CIPHER-REPLACE-IN-DEV', 1, '1.0.0', 'UNKNOWN', 0, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, 0, 0, 1);

INSERT INTO swap_cabinet (id, device_row_id, site_id, cabinet_no, slot_count, cabinet_model, lock_type, power_limit_kw, cabinet_state, create_time, version, del_flag, tenant_id)
VALUES (1, 1, 1, 'CAB0000001', 8, 'SWAP-CAB-8', 'ELECTROMAGNET', 3.50, 'NORMAL', CURRENT_TIMESTAMP, 0, 0, 1);

INSERT INTO swap_slot (id, cabinet_id, slot_no, slot_state, door_state, lock_state, charge_state, create_time, version, del_flag, tenant_id) VALUES (101, 1, 1, 'IDLE_EMPTY', 'CLOSED', 'LOCKED', 'IDLE', CURRENT_TIMESTAMP, 0, 0, 1);
INSERT INTO swap_slot (id, cabinet_id, slot_no, slot_state, door_state, lock_state, charge_state, create_time, version, del_flag, tenant_id) VALUES (102, 1, 2, 'IDLE_EMPTY', 'CLOSED', 'LOCKED', 'IDLE', CURRENT_TIMESTAMP, 0, 0, 1);
INSERT INTO swap_slot (id, cabinet_id, slot_no, slot_state, door_state, lock_state, charge_state, create_time, version, del_flag, tenant_id) VALUES (103, 1, 3, 'IDLE_EMPTY', 'CLOSED', 'LOCKED', 'IDLE', CURRENT_TIMESTAMP, 0, 0, 1);
INSERT INTO swap_slot (id, cabinet_id, slot_no, slot_state, door_state, lock_state, charge_state, create_time, version, del_flag, tenant_id) VALUES (104, 1, 4, 'IDLE_EMPTY', 'CLOSED', 'LOCKED', 'IDLE', CURRENT_TIMESTAMP, 0, 0, 1);
INSERT INTO swap_slot (id, cabinet_id, slot_no, slot_state, door_state, lock_state, charge_state, create_time, version, del_flag, tenant_id) VALUES (105, 1, 5, 'IDLE_EMPTY', 'CLOSED', 'LOCKED', 'IDLE', CURRENT_TIMESTAMP, 0, 0, 1);
INSERT INTO swap_slot (id, cabinet_id, slot_no, slot_state, door_state, lock_state, charge_state, create_time, version, del_flag, tenant_id) VALUES (106, 1, 6, 'IDLE_EMPTY', 'CLOSED', 'LOCKED', 'IDLE', CURRENT_TIMESTAMP, 0, 0, 1);
INSERT INTO swap_slot (id, cabinet_id, slot_no, slot_state, door_state, lock_state, charge_state, create_time, version, del_flag, tenant_id) VALUES (107, 1, 7, 'IDLE_EMPTY', 'CLOSED', 'LOCKED', 'IDLE', CURRENT_TIMESTAMP, 0, 0, 1);
INSERT INTO swap_slot (id, cabinet_id, slot_no, slot_state, door_state, lock_state, charge_state, create_time, version, del_flag, tenant_id) VALUES (108, 1, 8, 'IDLE_EMPTY', 'CLOSED', 'LOCKED', 'IDLE', CURRENT_TIMESTAMP, 0, 0, 1);

INSERT INTO iot_device (id, product_key, device_id, device_name, gateway_row_id, secret_cipher, secret_version, online_state, shadow_version, activated_at, enabled, create_time, version, del_flag, tenant_id)
VALUES (11, 'BAT-60V20AH', 'BAT0000001', '样例电池 1', 1, 'DEMO-CIPHER-REPLACE-IN-DEV', 1, 'UNKNOWN', 0, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, 0, 0, 1);
INSERT INTO iot_device (id, product_key, device_id, device_name, gateway_row_id, secret_cipher, secret_version, online_state, shadow_version, activated_at, enabled, create_time, version, del_flag, tenant_id)
VALUES (12, 'BAT-60V20AH', 'BAT0000002', '样例电池 2', 1, 'DEMO-CIPHER-REPLACE-IN-DEV', 1, 'UNKNOWN', 0, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, 0, 0, 1);

INSERT INTO swap_battery (id, device_row_id, battery_code, product_key, voltage_v, capacity_ah, interface_type, battery_state, own_type, current_cabinet_id, current_slot_id, location_state, soc, soh, cycle_count, activated_at, create_time, version, del_flag, tenant_id)
VALUES (201, 11, 'BAT0000001', 'BAT-60V20AH', 60.0, 20.0, 'GBS-2020', 'IN_CABINET_CHARGING', 'PLATFORM', 1, 101, 'KNOWN', 95, 98.0, 12, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 1);
INSERT INTO swap_battery (id, device_row_id, battery_code, product_key, voltage_v, capacity_ah, interface_type, battery_state, own_type, current_cabinet_id, current_slot_id, location_state, soc, soh, cycle_count, activated_at, create_time, version, del_flag, tenant_id)
VALUES (202, 12, 'BAT0000002', 'BAT-60V20AH', 60.0, 20.0, 'GBS-2020', 'IN_CABINET_CHARGING', 'PLATFORM', 1, 102, 'KNOWN', 88, 97.0, 15, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 1);

UPDATE swap_slot SET battery_id = 201, slot_state = 'IDLE_CHARGING', charge_state = 'CHARGING' WHERE id = 101;
UPDATE swap_slot SET battery_id = 202, slot_state = 'IDLE_CHARGING', charge_state = 'CHARGING' WHERE id = 102;
