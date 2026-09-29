-- ============================================================
-- V11：换电柜（biz.swap）与设备接入（framework.iot）的菜单与权限码种子。
--
-- 约定（与 V5 barrier 样例一致）：
--   1. 权限码与后续控制器的 @PreAuthorize 完全一致；前端据此做菜单/按钮级显隐。
--   2. superAdmin 经 selectPermsByUserId(userId,true) 自动获得全部权限；
--      同时显式绑定给 admin 角色（role_id=1），覆盖非 superAdmin 判定路径。
--   3. 菜单 id 用 6000+ 段，避开框架 V2 种子（1000~4103）与 barrier V5（5000~5300）。
--   4. M3~M6 的告警/工单/账务/租户菜单在对应里程碑新增 V12+，不在本文件预支
--      （预支会产生"菜单存在但接口不存在"的假可用状态）。
-- ============================================================

-- ---------------- 换电运营 ----------------
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6000, 0, '换电运营', 'M', '/swap', NULL, NULL, 'Cpu', 6, 0, 0);

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6100, 6000, '柜机监控', 'C', 'cabinet', 'biz/swap/cabinet', 'swap:cabinet:read', 'Grid', 1, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6101, 6100, '仓位管理', 'F', NULL, NULL, 'swap:slot:manage', NULL, 1, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6102, 6100, '远程指令下发', 'F', NULL, NULL, 'iot:command:send', NULL, 2, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6103, 6100, '柜机增删改', 'F', NULL, NULL, 'swap:cabinet:manage', NULL, 3, 0, 0);

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6200, 6000, '电池资产', 'C', 'battery', 'biz/swap/battery', 'swap:battery:read', 'Coin', 2, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6201, 6200, '电池维护', 'F', NULL, NULL, 'swap:battery:manage', NULL, 1, 0, 0);
-- 归属核销：FSM §4.5.4 允许人工改绑定的唯一入口，权限必须独立可回收
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6202, 6200, '归属核销', 'F', NULL, NULL, 'swap:battery:reconcile', NULL, 2, 0, 0);

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6300, 6000, '换电订单', 'C', 'order', 'biz/swap/order', 'swap:order:read', 'Document', 3, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6301, 6300, '订单干预', 'F', NULL, NULL, 'swap:order:intervene', NULL, 1, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6350, 6300, '账实差异台账', 'C', 'discrepancy', 'biz/swap/discrepancy', 'swap:discrepancy:read', 'Warning', 2, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6351, 6350, '差异处理', 'F', NULL, NULL, 'swap:discrepancy:resolve', NULL, 1, 0, 0);

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6400, 6000, '站点管理', 'C', 'site', 'biz/swap/site', 'swap:site:read', 'MapLocation', 4, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6401, 6400, '站点维护', 'F', NULL, NULL, 'swap:site:manage', NULL, 1, 0, 0);

-- ---------------- 设备接入（framework.iot） ----------------
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6500, 0, '设备接入', 'M', '/iot', NULL, NULL, 'Connection', 7, 0, 0);

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6510, 6500, '设备台账', 'C', 'device', 'iot/device', 'iot:device:read', 'Monitor', 1, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6511, 6510, '设备维护', 'F', NULL, NULL, 'iot:device:manage', NULL, 1, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6520, 6500, '消息留痕', 'C', 'message', 'iot/message', 'iot:message:read', 'ChatLineSquare', 2, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6530, 6500, '遥测查询', 'C', 'telemetry', 'iot/telemetry', 'iot:telemetry:read', 'DataLine', 3, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6540, 6500, '影子与参数', 'C', 'shadow', 'iot/shadow', 'iot:shadow:read', 'Setting', 4, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6550, 6540, '参数下发', 'F', NULL, NULL, 'iot:shadow:write', NULL, 1, 0, 0);

-- ---------------- 会员与权益（biz.swap member 域，后台侧） ----------------
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6600, 0, '会员与权益', 'M', '/member', NULL, NULL, 'User', 8, 0, 0);

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6610, 6600, '会员管理', 'C', 'user', 'member/user', 'member:user:read', 'UserFilled', 1, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6611, 6610, '会员维护', 'F', NULL, NULL, 'member:user:manage', NULL, 1, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6620, 6600, '权益查询', 'C', 'right', 'member/right', 'member:right:read', 'Tickets', 2, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6621, 6620, '权益调整', 'F', NULL, NULL, 'member:right:adjust', NULL, 1, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6630, 6600, '套餐管理', 'C', 'plan', 'member/plan', 'member:plan:manage', 'Files', 3, 0, 0);

-- ---------------- 绑定给超级管理员角色（role_id=1） ----------------
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6000);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6100);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6101);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6102);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6103);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6200);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6201);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6202);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6300);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6301);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6350);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6351);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6400);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6401);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6500);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6510);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6511);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6520);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6530);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6540);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6550);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6600);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6610);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6611);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6620);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6621);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6630);
