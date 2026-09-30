package com.lrs.buddy.biz.swap.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lrs.buddy.framework.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 仓位。资产的"物理位置"真相，订单只通过预占表引用它，不直接改它当"被谁占了"。
 *
 * slot_state 里 RESERVED_ORDER / PENDING_PICKUP / ISOLATED 三态都不可分配；
 * 其中 PENDING_PICKUP 是责任边界（用户的电池被平台暂存），绝不能被静默放回可分配池。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("swap_slot")
public class SwapSlot extends BaseEntity {

    private Long cabinetId;
    private Integer slotNo;
    /** IDLE_EMPTY｜IDLE_CHARGING｜RESERVED_ORDER｜OPEN_IN_USE｜PENDING_PICKUP｜FAULT｜DISABLED｜ISOLATED */
    private String slotState;
    private Long batteryId;
    private Long reservedOrderId;
    /** CLOSED｜OPEN｜UNKNOWN｜FAULT */
    private String doorState;
    /** LOCKED｜UNLOCKED｜UNKNOWN｜FAULT */
    private String lockState;
    /** IDLE｜CHARGING｜FULL｜FAULT｜STOPPED */
    private String chargeState;
    private BigDecimal lastTemp;
    private BigDecimal chargerTemp;
    private LocalDateTime lastDetectedAt;
    private LocalDateTime lastFullAt;
    private String faultCode;
    private Integer disabledFlag;
}
