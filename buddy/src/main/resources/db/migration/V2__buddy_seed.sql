-- ============================================================
-- Buddy 框架 初始化数据
--
-- 说明：这里只初始化"菜单 + 角色 + 两者关联"。
-- 管理员账号由 DataInitializer 在启动时创建——因为密码需要 BCrypt 加密，
-- 密文无法在静态 SQL 里预先写死（不同 salt 结果不同）。
-- ============================================================

-- ---------------- 菜单 ----------------
-- 一级：系统管理
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1000, 0, '系统管理', 'M', '/system', NULL, NULL, 'Setting', 1, 0, 0);

-- 二级：用户管理
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1100, 1000, '用户管理', 'C', 'user', 'system/user/index', 'sys:user:list', 'User', 1, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1101, 1100, '用户查询', 'F', NULL, NULL, 'sys:user:query', NULL, 1, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1102, 1100, '用户新增', 'F', NULL, NULL, 'sys:user:save', NULL, 2, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1103, 1100, '用户修改', 'F', NULL, NULL, 'sys:user:update', NULL, 3, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1104, 1100, '用户删除', 'F', NULL, NULL, 'sys:user:remove', NULL, 4, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1105, 1100, '重置密码', 'F', NULL, NULL, 'sys:user:resetPwd', NULL, 5, 0, 0);

-- 二级：角色管理
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1200, 1000, '角色管理', 'C', 'role', 'system/role/index', 'sys:role:list', 'UserFilled', 2, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1201, 1200, '角色新增', 'F', NULL, NULL, 'sys:role:save', NULL, 1, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1202, 1200, '角色修改', 'F', NULL, NULL, 'sys:role:update', NULL, 2, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1203, 1200, '角色删除', 'F', NULL, NULL, 'sys:role:remove', NULL, 3, 0, 0);

-- 二级：菜单管理
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1300, 1000, '菜单管理', 'C', 'menu', 'system/menu/index', 'sys:menu:list', 'Menu', 3, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1301, 1300, '菜单新增', 'F', NULL, NULL, 'sys:menu:save', NULL, 1, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1302, 1300, '菜单修改', 'F', NULL, NULL, 'sys:menu:update', NULL, 2, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1303, 1300, '菜单删除', 'F', NULL, NULL, 'sys:menu:remove', NULL, 3, 0, 0);

-- 二级：操作日志
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1400, 1000, '操作日志', 'C', 'operate-log', 'system/operate-log/index', 'sys:operateLog:list', 'Document', 4, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1401, 1400, '日志删除', 'F', NULL, NULL, 'sys:operateLog:remove', NULL, 1, 0, 0);

-- 二级：文件管理
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1500, 1000, '文件管理', 'C', 'file', 'system/file/index', 'sys:file:list', 'Folder', 5, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1501, 1500, '文件上传', 'F', NULL, NULL, 'sys:file:upload', NULL, 1, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1502, 1500, '文件删除', 'F', NULL, NULL, 'sys:file:remove', NULL, 2, 0, 0);

-- 二级：部门管理
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1600, 1000, '部门管理', 'C', 'dept', 'system/dept/index', 'sys:dept:list', 'OfficeBuilding', 6, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1601, 1600, '部门新增', 'F', NULL, NULL, 'sys:dept:save', NULL, 1, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1602, 1600, '部门修改', 'F', NULL, NULL, 'sys:dept:update', NULL, 2, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (1603, 1600, '部门删除', 'F', NULL, NULL, 'sys:dept:remove', NULL, 3, 0, 0);

-- 一级：系统监控
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (2000, 0, '系统监控', 'M', '/monitor', NULL, NULL, 'Monitor', 2, 0, 0);

-- 二级：服务器监控
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (2100, 2000, '服务器监控', 'C', 'server', 'monitor/server/index', 'monitor:server:list', 'Cpu', 1, 0, 0);

-- 二级：在线用户
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (2200, 2000, '在线用户', 'C', 'online', 'monitor/online/index', 'monitor:online:list', 'Connection', 2, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (2201, 2200, '强制下线', 'F', NULL, NULL, 'monitor:online:kick', NULL, 1, 0, 0);

-- 一级：通知公告
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (3000, 0, '通知公告', 'M', '/notice', NULL, NULL, 'Bell', 3, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (3100, 3000, '公告管理', 'C', 'index', 'notice/index', 'notice:list', 'Message', 1, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (3101, 3100, '公告新增', 'F', NULL, NULL, 'notice:save', NULL, 1, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (3102, 3100, '公告修改', 'F', NULL, NULL, 'notice:update', NULL, 2, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (3103, 3100, '公告删除', 'F', NULL, NULL, 'notice:remove', NULL, 3, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (3104, 3100, '发布/撤回', 'F', NULL, NULL, 'notice:publish', NULL, 4, 0, 0);

-- 一级：定时任务
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (4000, 0, '定时任务', 'M', '/job', NULL, NULL, 'Timer', 4, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (4100, 4000, '任务管理', 'C', 'index', 'job/index', 'sys:job:list', 'AlarmClock', 1, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (4101, 4100, '任务新增', 'F', NULL, NULL, 'sys:job:save', NULL, 1, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (4102, 4100, '任务修改', 'F', NULL, NULL, 'sys:job:update', NULL, 2, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (4103, 4100, '任务删除', 'F', NULL, NULL, 'sys:job:remove', NULL, 3, 0, 0);

-- ---------------- 角色 ----------------
-- data_scope：1 全部数据 / 5 仅本人（见 DataScopeType）
INSERT INTO sys_role (id, role_name, role_key, sort, data_scope, status, del_flag, remark)
VALUES (1, '超级管理员', 'admin', 1, 1, 0, 0, '拥有全部权限与全部数据可见性');
INSERT INTO sys_role (id, role_name, role_key, sort, data_scope, status, del_flag, remark)
VALUES (2, '普通用户', 'common', 2, 5, 0, 0, '仅可见本人数据');
INSERT INTO sys_role (id, role_name, role_key, sort, data_scope, status, del_flag, remark)
VALUES (3, '部门主管', 'manager', 3, 4, 0, 0, '可见本部门及以下数据');

-- ---------------- 部门 ----------------
INSERT INTO sys_dept (id, parent_id, dept_name, ancestors, sort, status, del_flag)
VALUES (100, 0, '总公司', '0', 1, 0, 0);
INSERT INTO sys_dept (id, parent_id, dept_name, ancestors, sort, status, del_flag)
VALUES (101, 100, '技术部', '0,100', 1, 0, 0);
INSERT INTO sys_dept (id, parent_id, dept_name, ancestors, sort, status, del_flag)
VALUES (102, 100, '运营部', '0,100', 2, 0, 0);
INSERT INTO sys_dept (id, parent_id, dept_name, ancestors, sort, status, del_flag)
VALUES (103, 101, '后端组', '0,100,101', 1, 0, 0);

-- ---------------- 角色-菜单关联 ----------------
-- 超级管理员：全部菜单
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE del_flag = 0;

-- 普通用户：暂不分配任何菜单（可通过角色管理界面自行授权）
