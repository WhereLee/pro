package com.lrs.buddy.biz.barrier.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lrs.buddy.framework.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalTime;

/** 一个计划时间点：每天 timeOfDay 时，期望杆处于 planState。归属于某个策略。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("barrier_schedule")
public class SchedulePoint extends BaseEntity {

    /** 所属策略 */
    private Long strategyId;
    private LocalTime timeOfDay;
    /** OPEN / CLOSED */
    private String planState;
    /** 1=启用 0=停用 */
    private Integer enabled;
    private String name;
}
