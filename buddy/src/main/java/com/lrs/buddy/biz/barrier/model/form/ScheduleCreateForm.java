package com.lrs.buddy.biz.barrier.model.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalTime;

@Data
public class ScheduleCreateForm {

    /** 所属策略；为空则归到默认策略(id=1) */
    private Long strategyId;

    @NotNull(message = "时刻不能为空")
    private LocalTime timeOfDay;

    /** OPEN / CLOSE(D) */
    @NotBlank(message = "目标状态不能为空")
    private String planState;

    /** 1 启用 0 停用，默认启用 */
    private Integer enabled;

    private String name;
}
