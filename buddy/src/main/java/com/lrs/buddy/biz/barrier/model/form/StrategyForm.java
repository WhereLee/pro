package com.lrs.buddy.biz.barrier.model.form;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class StrategyForm {

    @NotBlank(message = "策略名不能为空")
    private String name;

    private String description;

    /** 1 启用 0 停用，默认启用 */
    private Integer enabled;

    /** 优先级，越大越优先；默认 0 */
    private Integer priority;
}
