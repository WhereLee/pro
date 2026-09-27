package com.lrs.buddy.modules.notice.model.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 公告管理端视图对象。
 */
@Data
public class NoticeVO {

    private Long id;
    private String title;
    private String content;

    /** 1 通知 / 2 公告 */
    private Integer type;
    private String typeDesc;

    /** 0 草稿 / 1 已发布 / 2 已撤回 */
    private Integer status;
    private String statusDesc;

    /** 1 全体 / 2 指定角色 / 3 指定用户 */
    private Integer targetType;
    private String targetTypeDesc;

    /** 定向目标 ID（角色 ID 或用户 ID） */
    private List<Long> targetIds;

    private LocalDateTime publishTime;
    private LocalDateTime createTime;
}
