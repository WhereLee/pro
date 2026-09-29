package com.lrs.buddy.biz.barrier.model.form;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 手动（非定时）指令：OPEN / CLOSE。 */
@Data
public class ManualCommandForm {

    @NotBlank(message = "action 不能为空")
    private String action;
}
