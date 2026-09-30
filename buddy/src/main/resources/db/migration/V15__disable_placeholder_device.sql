-- ============================================================
-- V15：把 M0-3 种子里留下的"假凭证设备"置为停用（M2-0 复核发现）。
--
-- 问题来历：V7/V8 为了"clone 即可接入模拟器"种了一台样例柜机
--   iot_device id=1 / device_id='CAB0000001'，但它的 secret_cipher 是字符串
--   'DEMO-CIPHER-REPLACE-IN-DEV'——不是任何密钥加密出来的密文，永远解不开。
--   同时 swap_cabinet(id=1) 又通过 uk_cab_device 唯一索引绑在这台设备上。
--
-- 后果不是"少一台能用设备"这么简单：
--   柜机台账看起来正常（NORMAL）、仓位齐全，但它的设备永远认证失败。
--   M1 的测试之所以没被发现，是因为每个测试都自己插了设备行，绕过了种子。
--   这类"数据在、状态像可用、实际永远连不上"的行留在种子里，
--   任何一次演示或回归都可能踩上，而且现场表现是"柜机离线"，排查方向会被带去网络。
--
-- 处置选择：
--   1 不删行：删了会让 swap_cabinet / swap_slot 变成孤儿，且 V8 是已推送迁移（Flyway checksum 不可改）。
--   2 不改 V8：改历史迁移等于让所有环境的校验和失效。
--   3 停用 + 写明原因（本文件）：enabled=0 后接入层认证直接失败且**语义明确**，
--      remark 让任何读到这台设备的人立刻知道它是占位数据，真实柜机走注册接口开通。
--
-- 重要：谓词故意写成"密文是占位串的所有设备"而不是"id=1"。
--   因为 V8 里这种占位密文共三行（柜机 id=1、电池子设备 id=11/12，后者挂在同一台柜机下）：
--   按 id 改会漏掉子设备，而“柜机能连、电池不能连”比“全部不能连”更难排查。
--   本迁移同时也是一次提醒：DeviceProvisionTest 里的占位密文断言就是用来接这类漏网的。
-- ============================================================

UPDATE iot_device
SET enabled = 0,
    offline_reason = 'SEED_PLACEHOLDER',
    remark = 'M0-3 样例占位行：secret_cipher 是不可解的占位串。真实设备请用 POST /api/swap/devices 开通。',
    update_time = CURRENT_TIMESTAMP,
    version = version + 1
WHERE secret_cipher IS NULL OR secret_cipher LIKE 'DEMO-CIPHER%' OR LENGTH(secret_cipher) < 24;

UPDATE swap_cabinet
SET cabinet_state = 'DISABLED',
    locked_reason = 'SEED_PLACEHOLDER_DEVICE',
    update_time = CURRENT_TIMESTAMP,
    version = version + 1
WHERE id = 1;
