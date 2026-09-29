-- ============================================================
-- Buddy 框架 表结构
--
-- 兼容性说明：
-- 本脚本需同时兼容 MySQL 8 与 H2（开发/演示使用），因此刻意避开了
-- 数据库专有语法：
--   - 不写 ENGINE=InnoDB / DEFAULT CHARSET（H2 不识别）
--   - 不使用列级 COMMENT 'xxx'（H2 不支持 MySQL 风格的列注释）
--   - 时间统一用 DATETIME（H2 中作为 TIMESTAMP 的别名）
--   - 建表加 IF NOT EXISTS，便于重复执行
--
-- 业务表主键（id）由应用层雪花算法生成，故不加 AUTO_INCREMENT；
-- 纯关联表无业务含义，使用数据库自增更简单。
-- ============================================================

-- ---------------- 用户 ----------------
CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT       NOT NULL PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL,
    password    VARCHAR(100) NOT NULL,
    nickname    VARCHAR(50),
    email       VARCHAR(100),
    phone       VARCHAR(20),
    avatar      VARCHAR(255),
    dept_id     BIGINT,
    status      INT          DEFAULT 0,
    create_by   BIGINT,
    create_time DATETIME,
    update_by   BIGINT,
    update_time DATETIME,
    del_flag    INT          DEFAULT 0,
    version     INT          DEFAULT 0,
    remark      VARCHAR(500)
);
CREATE UNIQUE INDEX uk_sys_user_username ON sys_user (username, del_flag);

-- ---------------- 角色 ----------------
CREATE TABLE IF NOT EXISTS sys_role (
    id         BIGINT      NOT NULL PRIMARY KEY,
    role_name  VARCHAR(50) NOT NULL,
    role_key   VARCHAR(50) NOT NULL,
    sort       INT         DEFAULT 0,
    data_scope INT         DEFAULT 1,
    status     INT         DEFAULT 0,
    create_by  BIGINT,
    create_time DATETIME,
    update_by  BIGINT,
    update_time DATETIME,
    del_flag   INT         DEFAULT 0,
    version    INT         DEFAULT 0,
    remark     VARCHAR(500)
);

-- ---------------- 菜单（含按钮权限） ----------------
CREATE TABLE IF NOT EXISTS sys_menu (
    id         BIGINT       NOT NULL PRIMARY KEY,
    parent_id  BIGINT       DEFAULT 0,
    menu_name  VARCHAR(50)  NOT NULL,
    menu_type  VARCHAR(1)   DEFAULT 'C',
    path       VARCHAR(200),
    component  VARCHAR(255),
    perms      VARCHAR(100),
    icon       VARCHAR(100),
    sort       INT          DEFAULT 0,
    visible    INT          DEFAULT 0,
    create_by  BIGINT,
    create_time DATETIME,
    update_by  BIGINT,
    update_time DATETIME,
    del_flag   INT          DEFAULT 0,
    version    INT          DEFAULT 0,
    remark     VARCHAR(500)
);

-- ---------------- 用户-角色关联 ----------------
CREATE TABLE IF NOT EXISTS sys_user_role (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL
);
CREATE INDEX idx_ur_user ON sys_user_role (user_id);
CREATE INDEX idx_ur_role ON sys_user_role (role_id);

-- ---------------- 上传文件 ----------------
CREATE TABLE IF NOT EXISTS sys_file (
    id            BIGINT       NOT NULL PRIMARY KEY,
    original_name VARCHAR(255),
    stored_name   VARCHAR(255),
    relative_path VARCHAR(500),
    content_type  VARCHAR(100),
    size          BIGINT,
    storage_type  VARCHAR(20),
    directory     VARCHAR(50),
    create_by     BIGINT,
    create_time   DATETIME,
    update_by     BIGINT,
    update_time   DATETIME,
    del_flag      INT          DEFAULT 0,
    version       INT          DEFAULT 0,
    remark        VARCHAR(500)
);

-- ---------------- 操作日志 ----------------
-- 不继承 BaseEntity：日志只增不改不删，无需乐观锁与逻辑删除字段
CREATE TABLE IF NOT EXISTS sys_operate_log (
    id             BIGINT       NOT NULL PRIMARY KEY,
    title          VARCHAR(100),
    business_type  INT,
    method         VARCHAR(200),
    request_method VARCHAR(10),
    operator_id    BIGINT,
    operator_name  VARCHAR(50),
    oper_url       VARCHAR(500),
    oper_ip        VARCHAR(50),
    oper_param     VARCHAR(2000),
    json_result    VARCHAR(2000),
    status         INT          DEFAULT 0,
    error_msg      VARCHAR(2000),
    cost_time      BIGINT,
    oper_time      DATETIME
);
CREATE INDEX idx_ol_time ON sys_operate_log (oper_time);
CREATE INDEX idx_ol_operator ON sys_operate_log (operator_id);

-- ---------------- 定时任务 ----------------
CREATE TABLE IF NOT EXISTS sys_job (
    id              BIGINT       NOT NULL PRIMARY KEY,
    job_name        VARCHAR(100),
    job_group       VARCHAR(50),
    bean_name       VARCHAR(100),
    params          VARCHAR(500),
    cron_expression VARCHAR(100),
    status          INT          DEFAULT 0,
    concurrent      INT          DEFAULT 1,
    misfire_policy  INT          DEFAULT 1,
    last_status     INT,
    last_message    VARCHAR(500),
    create_by       BIGINT,
    create_time     DATETIME,
    update_by       BIGINT,
    update_time     DATETIME,
    del_flag        INT          DEFAULT 0,
    version         INT          DEFAULT 0,
    remark          VARCHAR(500)
);

-- ---------------- 部门 ----------------
CREATE TABLE IF NOT EXISTS sys_dept (
    id         BIGINT      NOT NULL PRIMARY KEY,
    parent_id  BIGINT      DEFAULT 0,
    dept_name  VARCHAR(50) NOT NULL,
    ancestors  VARCHAR(200),
    sort       INT         DEFAULT 0,
    leader     VARCHAR(50),
    phone      VARCHAR(20),
    status     INT         DEFAULT 0,
    create_by  BIGINT,
    create_time DATETIME,
    update_by  BIGINT,
    update_time DATETIME,
    del_flag   INT         DEFAULT 0,
    version    INT         DEFAULT 0,
    remark     VARCHAR(500)
);

-- ---------------- 角色-部门关联（数据范围为"自定义"时使用） ----------------
CREATE TABLE IF NOT EXISTS sys_role_dept (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id BIGINT NOT NULL,
    dept_id BIGINT NOT NULL
);
CREATE INDEX idx_rd_role ON sys_role_dept (role_id);

-- ---------------- 通知公告 ----------------
-- status: 0 草稿 / 1 已发布 / 2 已撤回
-- target_type: 1 全体 / 2 指定角色 / 3 指定用户
CREATE TABLE IF NOT EXISTS sys_notice (
    id           BIGINT       NOT NULL PRIMARY KEY,
    title        VARCHAR(100) NOT NULL,
    content      VARCHAR(2000),
    type         INT          DEFAULT 1,
    status       INT          DEFAULT 0,
    target_type  INT          DEFAULT 1,
    publish_time DATETIME,
    create_by    BIGINT,
    create_time  DATETIME,
    update_by    BIGINT,
    update_time  DATETIME,
    del_flag     INT          DEFAULT 0,
    version      INT          DEFAULT 0,
    remark       VARCHAR(500)
);

-- 定向发布的目标（target_type=2 时存角色 ID，=3 时存用户 ID）
CREATE TABLE IF NOT EXISTS sys_notice_target (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    notice_id BIGINT NOT NULL,
    target_id BIGINT NOT NULL
);
CREATE INDEX idx_nt_notice ON sys_notice_target (notice_id);

-- 已读记录：一个用户对一条公告最多一条，用于计算未读数
CREATE TABLE IF NOT EXISTS sys_notice_read (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    notice_id BIGINT NOT NULL,
    user_id   BIGINT NOT NULL,
    read_time DATETIME
);
CREATE UNIQUE INDEX uk_nr_notice_user ON sys_notice_read (notice_id, user_id);

-- ---------------- 角色-菜单关联 ----------------
CREATE TABLE IF NOT EXISTS sys_role_menu (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL
);
CREATE INDEX idx_rm_role ON sys_role_menu (role_id);
CREATE INDEX idx_rm_menu ON sys_role_menu (menu_id);
