-- ============================================================
-- V14：设备开通与密钥轮转的权限码（M2-0b）。
--
-- 与 V11 的关系：V11 已经种了 `iot:device:read`（台账查看）与 `iot:device:manage`（台账维护），
-- 所以这里**不另起一套命名**（重复建设会让"谁到底有什么权限"没人能说清），
-- 只补两个 V11 里没有、且风险等级确实不同的动作码：
--   iot:device:provision  新增设备并发放首把密钥
--   iot:device:rotate     让一台在用设备的旧密钥立刻失效
-- 为什么必须拆开而不并进 manage：
--   "能新增设备"和"能让一台正在换电的柜机当场掉线"不是同一件事。
--   合并成一个 manage 码，就等于批量开通的运维岗默认持有远程断服务能力，
--   而且审计上分不出"他新增了设备"和"他把某台柜机踢下线了"。
--
-- 菜单 id 挂在 6510（设备台账）下，取 V11 未占用的 6512/6513。
-- 契约测试（SwapDdlContractTest）会校验控制器里的 hasAuthority 码必须在种子里存在。
-- ============================================================

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6512, 6510, '设备开通与发密钥', 'F', NULL, NULL, 'iot:device:provision', NULL, 2, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6513, 6510, '密钥轮转', 'F', NULL, NULL, 'iot:device:rotate', NULL, 3, 0, 0);

INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6512);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6513);
