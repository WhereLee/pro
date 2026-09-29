-- ============================================================
-- V4：ShedLock 分布式定时任务锁表。
-- biz.barrier 的心跳 reconcile 在多实例部署时保证集群内单实例执行（锁记录落此表，与业务同库，无需额外中间件）。
-- 框架表、非领域实体，无 BaseEntity 审计列（同 flyway_schema_history）。
-- DDL 采用 ShedLock 官方 MySQL 规范，H2(MODE=MySQL) 亦兼容。
-- ============================================================
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
