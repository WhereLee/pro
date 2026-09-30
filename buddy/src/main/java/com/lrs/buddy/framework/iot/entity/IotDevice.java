package com.lrs.buddy.framework.iot.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lrs.buddy.framework.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 设备台账（framework 层，业务无关）。
 *
 * 两个字段刻意不映射进实体，避免它们被无意读出：
 * <ul>
 *   <li>{@code secret_cipher}：只在开通/轮转时写入、只在认证时由 CredentialService 读，
 *       任何 VO/列表接口都不该带它出去，所以不给实体字段，走 DAO 定向 SQL。</li>
 *   <li>{@code last_seen_ts}：epoch 毫秒，供乱序与静默判定，属于接入层内部口径。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iot_device")
public class IotDevice extends BaseEntity {

    private String productKey;
    private String deviceId;
    private String deviceName;
    private Long gatewayRowId;
    private Integer secretVersion;
    private String firmwareVersion;
    private String hardwareVersion;
    private String ipAddr;
    private String simNo;
    /** ONLINE / OFFLINE / STALE / UNKNOWN —— UNKNOWN 是初始态，LWT 不作为唯一真相 */
    private String onlineState;
    private LocalDateTime lastOnlineAt;
    private LocalDateTime lastOfflineAt;
    private String offlineReason;
    private Long shadowVersion;
    private LocalDateTime activatedAt;
    private Integer enabled;
}
