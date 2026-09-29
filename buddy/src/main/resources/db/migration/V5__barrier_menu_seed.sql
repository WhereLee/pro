-- ============================================================
-- V5：升降杆样例的菜单与权限，并入框架 sys_menu。
-- 权限码与 biz.barrier 控制器的 @PreAuthorize 完全一致；前端据此做菜单/按钮级显隐。
-- superAdmin 经 selectPermsByUserId(userId,true) 自动获得全部 perms；
-- 同时显式绑定给 admin 角色(role_id=1)，非 superAdmin 判定路径下也能拿到。
-- 菜单 id 用 5000+ 段，避开框架 V2 种子（1000~4103）。
-- ============================================================

-- 一级目录：升降杆样例
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (5000, 0, '升降杆样例', 'M', '/barrier', NULL, NULL, 'Guide', 5, 0, 0);

-- 二级：杆管理（C）+ 其下按钮权限（F）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (5100, 5000, '杆管理', 'C', 'barrier', 'biz/barrier/index', 'barrier:status:read', 'Switch', 1, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (5101, 5100, '事件查看', 'F', NULL, NULL, 'barrier:events:read', NULL, 1, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (5102, 5100, '手动开合', 'F', NULL, NULL, 'barrier:manual', NULL, 2, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (5103, 5100, '杆增删改', 'F', NULL, NULL, 'barrier:manage', NULL, 3, 0, 0);

-- 二级：策略管理
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (5200, 5000, '策略管理', 'C', 'strategy', 'biz/barrier/strategy', 'strategy:manage', 'Files', 2, 0, 0);

-- 二级：计划点
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (5300, 5000, '计划点', 'C', 'schedule', 'biz/barrier/schedule', 'schedule:manage', 'Clock', 3, 0, 0);

-- 绑定给超级管理员角色（role_id=1）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 5000);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 5100);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 5101);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 5102);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 5103);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 5200);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 5300);
