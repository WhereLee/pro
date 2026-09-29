-- ============================================================
-- V9：换电订单主线（biz.swap）——订单 / 步骤 / 事件 / 去重 / 补偿 / 差异。
--
-- 依据 swap-order-fsm.md：四套状态机分离（订单/步骤/指令/资产），
--   指令态见 V7.iot_command，资产态见 V8.swap_slot / swap_battery / swap_battery_binding。
--
-- 关键设计：
--   1. **swap_order_event 是事实源，status 只是可重建的投影（I5）**：
--      每次迁移先 append 事件再改状态；事件表 append-only，不含 version/del_flag、不加外键。
--   2. active_user 生成列把"B3 单用户在途至多 1 笔"落到 DB：
--      在途状态集合必须与 FSM §4.1 的"非终态"完全一致，由 SwapDdlContractTest 双向断言。
--   3. 扣减与所有权变更同事务（I3）：swap_right_* 在 V10，SETTLING 事务内一并写。
--   4. deadline 双写（DATETIME(3) 可读 + BIGINT 供扫描），扫描索引 (status, deadline_ts)（I4）。
-- ============================================================

-- ---------------- 换电订单 ----------------
CREATE TABLE swap_order (
    id               BIGINT      NOT NULL,
    order_no         VARCHAR(32) NOT NULL,
    user_id          BIGINT      NOT NULL,
    site_id          BIGINT      NOT NULL,
    cabinet_id       BIGINT      NOT NULL,
    return_slot_no   INT,
    offer_slot_no    INT,
    return_battery_id BIGINT,
    offer_battery_id BIGINT,
    plan_id          BIGINT,
    order_state      VARCHAR(24) NOT NULL DEFAULT 'CREATED',
    terminal_reason  VARCHAR(48),
    suspend_reason   VARCHAR(48),
    right_state      VARCHAR(16) NOT NULL DEFAULT 'NONE',
    evidence_level   VARCHAR(16) NOT NULL DEFAULT 'STRONG',
    self_resume_used INT         NOT NULL DEFAULT 0,
    source           VARCHAR(16) NOT NULL DEFAULT 'H5',
    trace_id         VARCHAR(64),
    operator_id      BIGINT,
    approved_by      BIGINT,
    authorized_at    DATETIME(3),
    returned_at      DATETIME(3),
    taken_at         DATETIME(3),
    settled_at       DATETIME(3),
    deadline_at      DATETIME(3),
    deadline_ts      BIGINT,
    closed_at        DATETIME(3),
    active_user      BIGINT AS (CASE WHEN order_state IN
        ('CREATED', 'AUTHORIZED', 'RETURNING', 'RETURNED', 'VERIFYING', 'OFFERING', 'TAKEN', 'SETTLING', 'SUSPENDED', 'UNCONFIRMED', 'ABORTING')
        THEN user_id ELSE NULL END),
    create_by        BIGINT,
    create_time      DATETIME,
    update_by        BIGINT,
    update_time      DATETIME,
    remark           VARCHAR(255),
    version          INT         NOT NULL DEFAULT 0,
    del_flag         INT         NOT NULL DEFAULT 0,
    tenant_id        BIGINT      DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_ord_state CHECK (order_state IN
        ('CREATED', 'AUTHORIZED', 'RETURNING', 'RETURNED', 'VERIFYING', 'OFFERING', 'TAKEN', 'SETTLING',
         'COMPLETED', 'SUSPENDED', 'UNCONFIRMED', 'ABORTING', 'REJECTED', 'ABORTED', 'FAILED_MANUAL')),
    CONSTRAINT ck_ord_right CHECK (right_state IN ('NONE', 'OCCUPIED', 'DEDUCTED', 'RELEASED', 'FROZEN')),
    CONSTRAINT ck_ord_evidence CHECK (evidence_level IN ('STRONG', 'DEGRADED', 'WEAK')),
    CONSTRAINT ck_ord_source CHECK (source IN ('H5', 'ADMIN', 'OPS', 'SIM'))
);
-- @enum swap_order.order_state: CREATED|AUTHORIZED|RETURNING|RETURNED|VERIFYING|OFFERING|TAKEN|SETTLING|COMPLETED|SUSPENDED|UNCONFIRMED|ABORTING|REJECTED|ABORTED|FAILED_MANUAL
-- @enum swap_order.right_state: NONE|OCCUPIED|DEDUCTED|RELEASED|FROZEN
-- @enum swap_order.evidence_level: STRONG|DEGRADED|WEAK
-- @enum swap_order.source: H5|ADMIN|OPS|SIM
-- B3：单用户在途订单至多 1 笔（DB 唯一约束，不依赖应用代码正确）
CREATE UNIQUE INDEX uk_ord_active_user ON swap_order (active_user);
CREATE UNIQUE INDEX uk_ord_no ON swap_order (order_no);
CREATE INDEX idx_ord_scan ON swap_order (order_state, deadline_ts);
CREATE INDEX idx_ord_cabinet ON swap_order (cabinet_id, order_state);
CREATE INDEX idx_ord_user ON swap_order (user_id, order_state);
CREATE INDEX idx_ord_time ON swap_order (create_time);
CREATE INDEX idx_ord_tenant ON swap_order (tenant_id);

-- ---------------- 步骤（物理动作的精确位置；FI-12 与 FI-13 在订单层同形，靠步骤区分） ----------------
-- break_* 四列为 M6"跨柜续作"预留：现在就定名，否则将来要加列并回填历史（最难做的一类迁移）
CREATE TABLE swap_order_step (
    id               BIGINT      NOT NULL,
    order_id         BIGINT      NOT NULL,
    step_no          INT         NOT NULL,
    step_code        VARCHAR(24) NOT NULL,
    expect_cmd       VARCHAR(32),
    expect_event     VARCHAR(48),
    slot_no          INT,
    battery_id       BIGINT,
    step_state       VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    dispatch_cmd_id  CHAR(26),
    session_id       VARCHAR(64),
    facts_json       TEXT,
    deadline_at      DATETIME(3),
    deadline_ts      BIGINT,
    started_at       DATETIME(3),
    finished_at      DATETIME(3),
    fail_code        VARCHAR(16),
    fail_reason      VARCHAR(255),
    break_cabinet_id BIGINT,
    break_slot_id    BIGINT,
    break_seq_no     BIGINT,
    break_at         DATETIME(3),
    create_by        BIGINT,
    create_time      DATETIME,
    update_by        BIGINT,
    update_time      DATETIME,
    remark           VARCHAR(255),
    version          INT         NOT NULL DEFAULT 0,
    del_flag         INT         NOT NULL DEFAULT 0,
    tenant_id        BIGINT      DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_step_state CHECK (step_state IN
        ('PENDING', 'DISPATCHED', 'OPEN_CONFIRMED', 'PHYSICS_DONE', 'VERIFIED', 'CONFIRM_PENDING', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_step_code CHECK (step_code IN
        ('OPEN_RETURN', 'WAIT_INSERT', 'VERIFY_RETURN', 'UNLOCK_OFFER', 'WAIT_TAKE', 'SETTLE'))
);
-- @enum swap_order_step.step_state: PENDING|DISPATCHED|OPEN_CONFIRMED|PHYSICS_DONE|VERIFIED|CONFIRM_PENDING|FAILED|SKIPPED
-- @enum swap_order_step.step_code: OPEN_RETURN|WAIT_INSERT|VERIFY_RETURN|UNLOCK_OFFER|WAIT_TAKE|SETTLE
CREATE UNIQUE INDEX uk_step_no ON swap_order_step (order_id, step_no);
CREATE INDEX idx_step_scan ON swap_order_step (step_state, deadline_ts);

-- ---------------- 订单事件流（append-only 事实源，I5：状态可重放出来） ----------------
CREATE TABLE swap_order_event (
    id           BIGINT      NOT NULL,
    order_id     BIGINT      NOT NULL,
    seq_no       BIGINT      NOT NULL,
    event_type   VARCHAR(48) NOT NULL,
    from_state   VARCHAR(24),
    to_state     VARCHAR(24),
    source       VARCHAR(16) NOT NULL,
    msg_id       CHAR(26),
    cmd_id       CHAR(26),
    session_id   VARCHAR(64),
    slot_no      INT,
    battery_id   BIGINT,
    detail       TEXT,
    occurred_at  DATETIME(3) NOT NULL,
    ts_millis    BIGINT      NOT NULL,
    operator_id  BIGINT,
    trace_id     VARCHAR(64),
    create_time  DATETIME,
    tenant_id    BIGINT      DEFAULT 1,
    PRIMARY KEY (id),
    -- 用户声明是最低优先级来源，只能触发反查、不能直接推进（FSM §3 / B2）
    CONSTRAINT ck_oevent_source CHECK (source IN ('DEVICE', 'QUERY', 'ACK', 'USER', 'TIMER', 'ADMIN', 'RECONCILE'))
);
-- @enum swap_order_event.source: DEVICE|QUERY|ACK|USER|TIMER|ADMIN|RECONCILE
CREATE UNIQUE INDEX uk_oevent_seq ON swap_order_event (order_id, seq_no);
CREATE INDEX idx_oevent_order ON swap_order_event (order_id, ts_millis);
CREATE INDEX idx_oevent_time ON swap_order_event (occurred_at);
CREATE INDEX idx_oevent_trace ON swap_order_event (trace_id);

-- ---------------- 上行事件幂等（I6：重复投递不产生第二次迁移） ----------------
CREATE TABLE swap_event_dedup (
    id          BIGINT      NOT NULL,
    order_id    BIGINT      NOT NULL,
    event_type  VARCHAR(48) NOT NULL,
    msg_id      CHAR(26)    NOT NULL,
    slot_no     INT,
    occurred_at DATETIME(3) NOT NULL,
    create_time DATETIME,
    tenant_id   BIGINT      DEFAULT 1,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_ededup ON swap_event_dedup (order_id, event_type, msg_id);
CREATE INDEX idx_ededup_time ON swap_event_dedup (occurred_at);

-- ---------------- 补偿台账（I8：补偿没做完不许进 ABORTED） ----------------
CREATE TABLE swap_compensation (
    id          BIGINT      NOT NULL,
    order_id    BIGINT      NOT NULL,
    action      VARCHAR(32) NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    target_id   BIGINT,
    comp_state  VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts    INT         NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3),
    last_error  VARCHAR(500),
    done_at     DATETIME(3),
    create_by   BIGINT,
    create_time DATETIME,
    update_by   BIGINT,
    update_time DATETIME,
    remark      VARCHAR(255),
    version     INT         NOT NULL DEFAULT 0,
    del_flag    INT         NOT NULL DEFAULT 0,
    tenant_id   BIGINT      DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_comp_action CHECK (action IN
        ('RELEASE_RESERVATION', 'LOCK_SLOT', 'LOCK_CABINET', 'BATTERY_TO_POOL', 'BATTERY_PENDING_PICKUP',
         'UNBIND_USER_BATTERY', 'REFUND_AUTO', 'REFUND_MANUAL', 'RELEASE_RIGHT', 'DEDUCT_RIGHT',
         'WRITE_DISCREPANCY', 'CREATE_WORK_ORDER', 'ESCALATE_ALARM', 'FREEZE_ORDER')),
    CONSTRAINT ck_comp_state CHECK (comp_state IN ('PENDING', 'DONE', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_comp_target CHECK (target_type IN ('ORDER', 'SLOT', 'CABINET', 'BATTERY', 'USER', 'RIGHT'))
);
-- @enum swap_compensation.action: RELEASE_RESERVATION|LOCK_SLOT|LOCK_CABINET|BATTERY_TO_POOL|BATTERY_PENDING_PICKUP|UNBIND_USER_BATTERY|REFUND_AUTO|REFUND_MANUAL|RELEASE_RIGHT|DEDUCT_RIGHT|WRITE_DISCREPANCY|CREATE_WORK_ORDER|ESCALATE_ALARM|FREEZE_ORDER
-- @enum swap_compensation.comp_state: PENDING|DONE|FAILED|SKIPPED
-- @enum swap_compensation.target_type: ORDER|SLOT|CABINET|BATTERY|USER|RIGHT
CREATE UNIQUE INDEX uk_comp_action ON swap_compensation (order_id, action, target_type, target_id);
CREATE INDEX idx_comp_scan ON swap_compensation (comp_state, next_retry_at);

-- ---------------- 账实差异（柜侧陈述与云端事实不一致的落点，X-02） ----------------
CREATE TABLE swap_discrepancy (
    id            BIGINT      NOT NULL,
    dedup_key     VARCHAR(96) NOT NULL,
    kind          VARCHAR(32) NOT NULL,
    order_id      BIGINT,
    cabinet_id    BIGINT,
    battery_id    BIGINT,
    user_id       BIGINT,
    expected_json TEXT,
    actual_json   TEXT,
    amount_impact DECIMAL(12, 2),
    auto_resolvable INT       NOT NULL DEFAULT 0,
    handle_state  VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    assignee_id   BIGINT,
    resolved_by   BIGINT,
    resolved_at   DATETIME(3),
    handle_result VARCHAR(500),
    create_by     BIGINT,
    create_time   DATETIME,
    update_by     BIGINT,
    update_time   DATETIME,
    remark        VARCHAR(255),
    version       INT         NOT NULL DEFAULT 0,
    del_flag      INT         NOT NULL DEFAULT 0,
    tenant_id     BIGINT      DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_disc_kind CHECK (kind IN
        ('SWAP_RESULT_MISMATCH', 'FACT_MISSING', 'IDENTITY_SUSPECT', 'ASSET_LEDGER', 'RIGHT_LEDGER', 'UNMATCHED_EVENT', 'OBSERVATION_CONFLICT')),
    CONSTRAINT ck_disc_state CHECK (handle_state IN ('OPEN', 'AUTO_RESOLVED', 'MANUAL_RESOLVED', 'DISMISSED'))
);
-- @enum swap_discrepancy.kind: SWAP_RESULT_MISMATCH|FACT_MISSING|IDENTITY_SUSPECT|ASSET_LEDGER|RIGHT_LEDGER|UNMATCHED_EVENT|OBSERVATION_CONFLICT
-- @enum swap_discrepancy.handle_state: OPEN|AUTO_RESOLVED|MANUAL_RESOLVED|DISMISSED
CREATE UNIQUE INDEX uk_disc_key ON swap_discrepancy (dedup_key);
CREATE INDEX idx_disc_state ON swap_discrepancy (handle_state, create_time);
CREATE INDEX idx_disc_order ON swap_discrepancy (order_id);
