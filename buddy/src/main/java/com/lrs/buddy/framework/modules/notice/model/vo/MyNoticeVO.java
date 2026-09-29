package com.lrs.buddy.framework.modules.notice.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户端看到的公告。
 *
 * <p>与 NoticeVO 分开：管理端需要状态、定向目标等编辑字段，
 * 用户端只需要"看什么"和"看没看过"，字段少也能避免把内部信息暴露出去。
 */
@Data
@Builder
public class MyNoticeVO {

    private Long id;
    private String title;
    private String content;
    private Integer type;
    private LocalDateTime publishTime;

    /** 当前用户是否已读 */
    private Boolean unread;
}
