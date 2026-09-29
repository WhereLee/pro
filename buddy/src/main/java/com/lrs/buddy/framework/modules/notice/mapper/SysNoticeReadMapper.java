package com.lrs.buddy.framework.modules.notice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lrs.buddy.framework.modules.notice.entity.SysNoticeRead;
import org.apache.ibatis.annotations.Param;

public interface SysNoticeReadMapper extends BaseMapper<SysNoticeRead> {

    /**
     * 标记已读。
     *
     * <p>用 INSERT IGNORE 语义（H2 用 MERGE / MySQL 用 ON DUPLICATE KEY）
     * 两种库语法不同，因此这里统一用"先查后插"在 Service 层处理更简单可靠；
     * 本方法仅用于按公告删除已读记录。
     */
    int deleteByNoticeId(@Param("noticeId") Long noticeId);
}
