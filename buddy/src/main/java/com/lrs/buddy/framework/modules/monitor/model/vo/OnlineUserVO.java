package com.lrs.buddy.framework.modules.monitor.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 在线用户视图对象。
 */
@Data
@Builder
public class OnlineUserVO {

    private Long userId;
    private String username;
    private String nickname;
    private String ip;
    private String userAgent;

    /** 登录时间，格式 yyyy-MM-dd HH:mm:ss */
    private String loginTime;

    /** 最后活跃时间 */
    private String lastActiveTime;
}
