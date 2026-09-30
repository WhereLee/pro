-- ============================================================
-- V18：安全联动与补偿/对账台账的权限码（M3 阶段 1）。
--
-- 为什么安全联动要一个独立权限码，而不复用 `iot:command:send`（远程指令下发）：
--   `iot:command:send` 是"能开一个仓、能重启一台柜"——单点、可回退、影响一台设备；
--   紧急停充是"整站柜机立刻退出充电、所有在途单被系统中止、权益按中止口径退回"——
--   批量、不可回退、直接动资金口径。把两者并成一个码，等于让能发单条指令的岗位
--   默认持有"停掉一个站点全部换电"的能力，而审计上也分不出这两种操作。
--
-- 与 `swap:order:intervene`（人工中止）同样不合并：
--   安全联动走 FSM 迁移 36（ALARM_SAFETY_LOCK，系统自动，禁自动资金动作），
--   人工中止走迁移 37（ADMIN_ABORT，需要 swap:order:intervene + 审计留痕）。
--   两条路径在状态机上是分开的，权限码也必须分开，否则事后回答不了"这单是谁停的"。
--
-- 补偿/对账两个读与处置码挂在 6350（账实差异台账）下，取未占用的 6352/6353：
--   swap:compensation:read   看补偿台账（谁在重试、哪条耗尽）
--   swap:discrepancy:replay  重放对账一次（只读核对 + 排补偿，不改数据）
--
-- 契约测试（SwapDdlContractTest）会校验控制器里的 hasAuthority 码必须在种子里存在。
-- ============================================================

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6104, 6100, '紧急停充联动', 'F', NULL, NULL, 'swap:safety:stop', NULL, 4, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6352, 6350, '补偿台账查看', 'F', NULL, NULL, 'swap:compensation:read', NULL, 2, 0, 0);
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, del_flag)
VALUES (6353, 6350, '对账重放', 'F', NULL, NULL, 'swap:discrepancy:replay', NULL, 3, 0, 0);

INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6104);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6352);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 6353);
