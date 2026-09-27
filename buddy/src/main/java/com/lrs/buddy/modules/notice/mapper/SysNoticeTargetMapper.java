package com.lrs.buddy.modules.notice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lrs.buddy.modules.notice.entity.SysNoticeTarget;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SysNoticeTargetMapper extends BaseMapper<SysNoticeTarget> {

    int deleteByNoticeId(@Param("noticeId") Long noticeId);

    int insertBatch(@Param("list") List<SysNoticeTarget> list);
}
