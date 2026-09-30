-- ============================================================
-- V16：订单人工干预申请单 + 复核权限码（M2 B3）。
--
-- 为什么"双人复核"必须落到表里而不是一个前端二次确认弹窗：
--   弹窗只能证明"有人点了两次"，证明不了"是两个人"。资金与资产归属的人工落终
--   （ADMIN_RESOLVE_*）必须由**另一个人**复核，所以要留下申请人、审批人、时间、
--   动作、理由、执行结果，事后能回答"谁批准的、什么时候批的、执行成功没有"。
--
-- 两条 DB 级约束（不靠应用自觉）：
--   1 `ck_iv_two_person`：审批人一旦填了，就必须不等于申请人——同账号自我复核直接在写入层失败；
--   2 `uk_iv_active` 生成列：同一笔订单同时只允许一条待处理（PENDING/APPROVED）申请，
--      否则会出现两条申请各自被批准、对同一单执行两次落终。
-- ============================================================

CREATE TABLE swap_intervention (
    id              BIGINT       NOT NULL,
    order_id        BIGINT       NOT NULL,
    order_no        VARCHAR(32)  NOT NULL,
    action          VARCHAR(32)  NOT NULL,
    reason          VARCHAR(255) NOT NULL,
    apply_state     VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    applicant_id    BIGINT       NOT NULL,
    applicant_name  VARCHAR(64)  NOT NULL,
    applied_at      DATETIME(3)  NOT NULL,
    approver_id     BIGINT,
    approver_name   VARCHAR(64),
    approved_at     DATETIME(3),
    reject_reason   VARCHAR(255),
    executed_at     DATETIME(3),
    exec_error      VARCHAR(255),
    create_by       BIGINT,
    create_time     DATETIME,
    update_by       BIGINT,
    update_time     DATETIME,
    remark          VARCHAR(255),
    version         INT          NOT NULL DEFAULT 0,
    del_flag        INT          NOT NULL DEFAULT 0,
    tenant_id       BIGINT       DEFAULT 1,
    active_order    BIGINT AS (CASE WHEN apply_state IN ('PENDING', 'APPROVED') THEN order_id ELSE NULL END),
    PRIMARY KEY (id),
    CONSTRAINT ck_iv_action CHECK (action IN ('ADMIN_ABORT', 'ADMIN_RESOLVE_COMPLETED', 'ADMIN_RESOLVE_ABORTED')),
    CONSTRAINT ck_iv_state CHECK (apply_state IN ('PENDING', 'APPROVED', 'REJECTED', 'EXECUTED', 'FAILED')),
    CONSTRAINT ck_iv_two_person CHECK (approver_id IS NULL OR approver_id <> applicant_id),
    -- 理由不能空：干预是"人说了算"的动作，无理由的干预在审计上等于无法追责
    CONSTRAINT ck_iv_reason CHECK (LENGTH(reason) >= 5)
);
-- @enum swap_intervention.action: ADMIN_ABORT|ADMIN_RESOLVE_COMPLETED|ADMIN_RESOLVE_ABORTED
-- @enum swap_intervention.apply_state: PENDING|APPROVED|REJECTED|EXECUTED|FAILED
CREATE UNIQUE INDEX uk_iv_active ON swap_intervention (active_order);
CREATE INDEX idx_iv_order ON swap_intervention (order_id, apply_state);
CREATE INDEX idx_iv_state ON swap_intervention (apply_state, applied_at);

-- 复核权限码必须与申请码分开：能提交干预申请的人不该默认拥有批准自己申请的权力。
-- V11 已种 swap:order:read / swap:order:intervene，这里只补现在真的有接口的那一个。
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6302, 6300, '干预复核', 'F', NULL, NULL, 'swap:order:approve', NULL, 2, 0, 0);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6302);
