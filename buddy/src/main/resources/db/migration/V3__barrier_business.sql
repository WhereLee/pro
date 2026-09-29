-- ============================================================
-- V3：升降杆样例（biz.barrier）业务表 —— 多杆最终态。
-- 合并自 barrier 独立工程的 V1/V4/V6/V7（丢弃其 V3 安全表、V5 审计表：
-- buddy 框架已提供 sys_* 与 sys_operate_log）。
-- 兼容 H2(MODE=MySQL) 与 MySQL 8：不写 ENGINE/CHARSET、不用列级 COMMENT、索引不加 IF NOT EXISTS。
-- barrier/strategy/schedule/status 实体继承框架 BaseEntity（含审计/del_flag/version 列）；
-- barrier_event 为 append-only 历史，不含这些列；strategy_barrier 为纯关联表。
-- ============================================================

-- 杆（设备抽象）：每根独立调度、独立状态
CREATE TABLE barrier (
    id          BIGINT       NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    location    VARCHAR(255),
    enabled     INT          NOT NULL DEFAULT 1,
    create_by   BIGINT,
    create_time DATETIME,
    update_by   BIGINT,
    update_time DATETIME,
    remark      VARCHAR(255),
    version     INT          NOT NULL DEFAULT 0,
    del_flag    INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

-- 策略：一组计划点的归属与优先级载体
CREATE TABLE barrier_strategy (
    id           BIGINT       NOT NULL,
    name         VARCHAR(64)  NOT NULL,
    description  VARCHAR(255),
    enabled      INT          NOT NULL DEFAULT 1,
    priority     INT          NOT NULL DEFAULT 0,
    create_by    BIGINT,
    create_time  DATETIME,
    update_by    BIGINT,
    update_time  DATETIME,
    remark       VARCHAR(255),
    version      INT          NOT NULL DEFAULT 0,
    del_flag     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

-- 计划点：每天 time_of_day 期望杆处于 plan_state，归属某策略
CREATE TABLE barrier_schedule (
    id           BIGINT       NOT NULL,
    strategy_id  BIGINT       NOT NULL,
    time_of_day  TIME         NOT NULL,
    plan_state   VARCHAR(16)  NOT NULL,
    enabled      INT          NOT NULL DEFAULT 1,
    name         VARCHAR(64),
    create_by    BIGINT,
    create_time  DATETIME,
    update_by    BIGINT,
    update_time  DATETIME,
    remark       VARCHAR(255),
    version      INT          NOT NULL DEFAULT 0,
    del_flag     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);
CREATE INDEX idx_schedule_strategy ON barrier_schedule (strategy_id, enabled);

-- 每杆当前态（barrier_id 唯一）；version 供跨实例 CAS
CREATE TABLE barrier_status (
    id                    BIGINT      NOT NULL,
    barrier_id            BIGINT      NOT NULL,
    barrier_state         VARCHAR(16) NOT NULL,
    manual_override       INT         NOT NULL DEFAULT 0,
    last_scheduled_action VARCHAR(16),
    create_by             BIGINT,
    create_time           DATETIME,
    update_by             BIGINT,
    update_time           DATETIME,
    remark                VARCHAR(255),
    version               INT         NOT NULL DEFAULT 0,
    del_flag              INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_status_barrier ON barrier_status (barrier_id);

-- 事件：某杆一次生效切换，只追加不可变（operator_id 手动操作人，定时为 NULL）
CREATE TABLE barrier_event (
    id            BIGINT       NOT NULL,
    barrier_id    BIGINT       NOT NULL,
    barrier_state VARCHAR(16)  NOT NULL,
    source        VARCHAR(16)  NOT NULL,
    occurred_at   DATETIME     NOT NULL,
    message       VARCHAR(255),
    operator_id   BIGINT,
    create_time   DATETIME,
    PRIMARY KEY (id)
);
CREATE INDEX idx_event_time ON barrier_event (occurred_at);
CREATE INDEX idx_event_barrier ON barrier_event (barrier_id);

-- 策略-杆 N:M 绑定（纯关联，无审计语义）
CREATE TABLE strategy_barrier (
    strategy_id BIGINT NOT NULL,
    barrier_id  BIGINT NOT NULL,
    PRIMARY KEY (strategy_id, barrier_id)
);

-- 引用完整性：纯关联表加 DB 外键（ON DELETE CASCADE）；实体表软删由服务层守卫（删前查子表）。
-- barrier_event 刻意不加外键：append-only 历史须在父变更/删除后存活。
ALTER TABLE strategy_barrier ADD CONSTRAINT fk_sb_strategy FOREIGN KEY (strategy_id) REFERENCES barrier_strategy (id) ON DELETE CASCADE;
ALTER TABLE strategy_barrier ADD CONSTRAINT fk_sb_barrier  FOREIGN KEY (barrier_id)  REFERENCES barrier (id) ON DELETE CASCADE;

-- 样例种子：1 号杆 + 默认策略（08:00 开 / 20:00 关）+ 绑定，clone 后即可演示调度。
INSERT INTO barrier (id, name, location, enabled, version, del_flag) VALUES (1, '1号杆', '默认路口', 1, 0, 0);
INSERT INTO barrier_strategy (id, name, description, enabled, priority, version, del_flag) VALUES (1, '默认通行策略', '白天开、夜间关', 1, 100, 0, 0);
INSERT INTO barrier_schedule (id, strategy_id, time_of_day, plan_state, enabled, name, version, del_flag) VALUES (1, 1, '08:00:00', 'OPEN', 1, '早开', 0, 0);
INSERT INTO barrier_schedule (id, strategy_id, time_of_day, plan_state, enabled, name, version, del_flag) VALUES (2, 1, '20:00:00', 'CLOSED', 1, '晚关', 0, 0);
INSERT INTO strategy_barrier (strategy_id, barrier_id) VALUES (1, 1);
