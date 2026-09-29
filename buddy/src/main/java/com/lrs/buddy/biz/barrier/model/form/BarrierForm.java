package com.lrs.buddy.biz.barrier.model.form;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BarrierForm {

    @NotBlank(message = "杆名不能为空")
    private String name;

    private String location;

    /** 1 启用 0 停用，默认启用 */
    private Integer enabled;
}
