package com.lrs.buddy.biz.swap.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lrs.buddy.framework.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 电池资产台账（投影）。
 *
 * 注意 soc/soh/current_slot_id 等都是**投影**，真相是观测记录与事件流（FSM §4.5）：
 * 柜机上报与电池自报可能冲突，冲突时按五级证据裁决，不允许"最后写的人说了算"。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("swap_battery")
public class SwapBattery extends BaseEntity {

    private Long deviceRowId;
    private String batteryCode;
    private String productKey;
    private BigDecimal voltageV;
    private BigDecimal capacityAh;
    private String interfaceType;
    /** IN_STOCK｜IN_CABINET_CHARGING｜HELD_BY_USER｜PENDING_PICKUP｜ISOLATED｜MAINTENANCE｜LOST｜SCRAPPED */
    private String batteryState;
    /** PLATFORM｜USER_OWNED */
    private String ownType;
    private Long holderUserId;
    private Long currentCabinetId;
    private Long currentSlotId;
    /** KNOWN｜UNKNOWN —— UNKNOWN 一律不可分配（O5） */
    private String locationState;
    private Integer soc;
    private BigDecimal soh;
    private Integer cycleCount;
    private LocalDateTime lastFullAt;
    private LocalDateTime lastReportAt;
    private String faultCode;
    private String isolatedReason;
    private LocalDateTime activatedAt;
    private LocalDateTime scrappedAt;
}
