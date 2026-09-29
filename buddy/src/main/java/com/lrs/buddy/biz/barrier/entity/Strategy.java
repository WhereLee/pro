package com.lrs.buddy.biz.barrier.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lrs.buddy.framework.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 策略：一组时间点的归属与优先级载体。定时任务驱动策略、策略作用到杆。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("barrier_strategy")
public class Strategy extends BaseEntity {

    private String name;
    private String description;
    /** 1=启用 0=停用；仅启用策略参与"生效策略"选择 */
    private Integer enabled;
    /** 优先级，越大越优先；同一根杆多策略启用时取最高 */
    private Integer priority;
}
