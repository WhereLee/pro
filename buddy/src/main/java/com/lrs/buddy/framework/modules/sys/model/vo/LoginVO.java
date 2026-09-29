package com.lrs.buddy.framework.modules.sys.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 登录结果。
 */
@Data
@Builder
public class LoginVO {

    /** 访问令牌 */
    private String token;

    /** 过期时间（秒），前端可据此设置刷新提醒 */
    private long expireSeconds;
}
