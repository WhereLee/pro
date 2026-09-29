package com.lrs.buddy.framework.modules.notice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公告已读记录。
 *
 * <p>(notice_id, user_id) 建了唯一索引，因此重复点击"已读"不会产生脏数据，
 * 插入冲突时直接忽略即可。
 */
@Data
@TableName("sys_notice_read")
public class SysNoticeRead {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long noticeId;

    private Long userId;

    private LocalDateTime readTime;
}
