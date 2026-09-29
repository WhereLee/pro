package com.lrs.buddy.framework.modules.sys.model.form;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重置密码参数。
 */
@Data
public class ResetPasswordForm {

    @NotNull(message = "用户 ID 不能为空")
    private Long userId;

    @Size(min = 6, max = 100, message = "密码长度需在 6~100 之间")
    private String password;
}
