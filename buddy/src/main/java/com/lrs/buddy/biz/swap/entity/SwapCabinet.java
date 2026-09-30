package com.lrs.buddy.biz.swap.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lrs.buddy.framework.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 柜机台账。一台柜机绑一台已注册设备（uk_cab_device），柜机不重复持有设备凭证。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("swap_cabinet")
public class SwapCabinet extends BaseEntity {

    private Long deviceRowId;
    private Long siteId;
    private String cabinetNo;
    private Integer slotCount;
    private String cabinetModel;
    private String lockType;
    private BigDecimal powerLimitKw;
    /** NORMAL｜FAULT｜DISABLED｜MAINTENANCE｜SAFETY_LOCKED */
    private String cabinetState;
    private String lockedReason;
    private LocalDateTime lastSwapAt;
}
