-- ============================================================
-- V6：多租户框架能力的 schema 预留 —— 为所有"租户私有"表增加 tenant_id 列。
--
-- 设计要点：
--   1. 列一律 `BIGINT DEFAULT 1`：H2 与 MySQL 在 ADD COLUMN 带 DEFAULT 时都会用默认值回填既有行，
--      故历史数据自动归入默认租户 1；下方再补一条 UPDATE 作跨引擎/跨版本的兜底回填。
--   2. 不加 NOT NULL 约束：避免 H2(DEFAULT...NOT NULL) 与 MySQL(NOT NULL...DEFAULT) 的列定义顺序差异，
--      租户值由应用层（TenantLineInnerInterceptor 注入 / DB 默认值）保证非空。
--   3. 该迁移与开关解耦：无论 buddy.tenant.enabled 真假都执行——表结构始终"多租户就绪"，
--      启用多租户只是翻配置开关，无需再迁移数据库（符合框架"单租户预留、按需开启"的定位）。
--
-- 不参与租户过滤的表（全局/基础设施，见 TenantProperties.ignoreTables 与 handler 前缀兜底）：
--   sys_menu（全局菜单/权限目录）、flyway_schema_history、shedlock、QRTZ_*（Quartz 自管）。
-- ============================================================

-- ---------------- 框架：租户私有表 ----------------
ALTER TABLE sys_user          ADD COLUMN tenant_id BIGINT DEFAULT 1;
ALTER TABLE sys_role          ADD COLUMN tenant_id BIGINT DEFAULT 1;
ALTER TABLE sys_dept          ADD COLUMN tenant_id BIGINT DEFAULT 1;
ALTER TABLE sys_file          ADD COLUMN tenant_id BIGINT DEFAULT 1;
ALTER TABLE sys_job           ADD COLUMN tenant_id BIGINT DEFAULT 1;
ALTER TABLE sys_notice        ADD COLUMN tenant_id BIGINT DEFAULT 1;
ALTER TABLE sys_notice_target ADD COLUMN tenant_id BIGINT DEFAULT 1;
ALTER TABLE sys_notice_read   ADD COLUMN tenant_id BIGINT DEFAULT 1;
ALTER TABLE sys_operate_log   ADD COLUMN tenant_id BIGINT DEFAULT 1;
ALTER TABLE sys_user_role     ADD COLUMN tenant_id BIGINT DEFAULT 1;
ALTER TABLE sys_role_dept     ADD COLUMN tenant_id BIGINT DEFAULT 1;
ALTER TABLE sys_role_menu     ADD COLUMN tenant_id BIGINT DEFAULT 1;

-- ---------------- 样例：升降杆业务表 ----------------
ALTER TABLE barrier          ADD COLUMN tenant_id BIGINT DEFAULT 1;
ALTER TABLE barrier_strategy ADD COLUMN tenant_id BIGINT DEFAULT 1;
ALTER TABLE barrier_schedule ADD COLUMN tenant_id BIGINT DEFAULT 1;
ALTER TABLE barrier_status   ADD COLUMN tenant_id BIGINT DEFAULT 1;
ALTER TABLE barrier_event    ADD COLUMN tenant_id BIGINT DEFAULT 1;
ALTER TABLE strategy_barrier ADD COLUMN tenant_id BIGINT DEFAULT 1;

-- ---------------- 兜底回填（既有行若为 NULL 归入默认租户 1） ----------------
UPDATE sys_user          SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE sys_role          SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE sys_dept          SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE sys_file          SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE sys_job           SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE sys_notice        SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE sys_notice_target SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE sys_notice_read   SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE sys_operate_log   SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE sys_user_role     SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE sys_role_dept     SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE sys_role_menu     SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE barrier           SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE barrier_strategy  SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE barrier_schedule  SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE barrier_status    SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE barrier_event     SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE strategy_barrier  SET tenant_id = 1 WHERE tenant_id IS NULL;

-- ---------------- 租户列索引（高频过滤表；其余表按项目实际查询模式再补复合索引） ----------------
CREATE INDEX idx_sys_user_tenant    ON sys_user (tenant_id);
CREATE INDEX idx_operate_log_tenant ON sys_operate_log (tenant_id);
CREATE INDEX idx_barrier_tenant     ON barrier (tenant_id);
CREATE INDEX idx_barrier_status_tenant ON barrier_status (tenant_id);
CREATE INDEX idx_barrier_event_tenant  ON barrier_event (tenant_id);
