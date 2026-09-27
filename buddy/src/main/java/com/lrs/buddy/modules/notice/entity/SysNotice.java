package com.lrs.buddy.modules.notice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lrs.buddy.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 通知公告。
 *
 * <p>状态流转：草稿(0) → 已发布(1) → 已撤回(2)。
 * 只有"已发布"的公告对普通用户可见，撤回后重新变为不可见——
 * 用状态而不是物理删除，保证发布记录可追溯。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_notice")
public class SysNotice extends BaseEntity {

    private String title;

    private String content;

    /** 类型：1 通知，2 公告 */
    private Integer type;

    /** 状态：0 草稿，1 已发布，2 已撤回 */
    private Integer status;

    /** 定向类型：1 全体，2 指定角色，3 指定用户 */
    private Integer targetType;

    private LocalDateTime publishTime;
}
