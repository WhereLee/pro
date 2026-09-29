package com.lrs.buddy.framework.modules.notice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lrs.buddy.framework.modules.notice.entity.SysNotice;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SysNoticeMapper extends BaseMapper<SysNotice> {

    /**
     * 查询对指定用户可见的已发布公告。
     *
     * <p>可见规则：
     * <ul>
     *   <li>targetType=1 全体可见</li>
     *   <li>targetType=2 按角色：用户的角色 ID 命中定向列表</li>
     *   <li>targetType=3 按用户：用户 ID 命中定向列表</li>
     * </ul>
     *
     * @param userId  当前用户
     * @param roleIds 用户的角色 ID，用于按角色定向的匹配
     */
    List<SysNotice> selectVisible(@Param("userId") Long userId, @Param("roleIds") List<Long> roleIds);

    /**
     * 可见且未读的数量（角标用）。
     */
    long countUnread(@Param("userId") Long userId, @Param("roleIds") List<Long> roleIds);
}
