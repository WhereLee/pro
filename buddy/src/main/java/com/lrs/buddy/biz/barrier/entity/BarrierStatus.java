package com.lrs.buddy.biz.barrier.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lrs.buddy.framework.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 某根杆的当前态（每杆一行，barrier_id 唯一）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("barrier_status")
public class BarrierStatus extends BaseEntity {

    /** 归属杆 */
    private Long barrierId;
    private String barrierState;
    /** 是否处于人工覆盖：1 是 0 否 */
    private Integer manualOverride;
    /** 最近一次已应用的定时目标状态（OPEN/CLOSED/NULL），用于识别边界跨越 */
    private String lastScheduledAction;
}
