package com.lrs.buddy.modules.notice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 公告定向目标。
 * targetType=2 时 targetId 是角色 ID，targetType=3 时是用户 ID。
 */
@Data
@TableName("sys_notice_target")
public class SysNoticeTarget {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long noticeId;

    private Long targetId;
}
