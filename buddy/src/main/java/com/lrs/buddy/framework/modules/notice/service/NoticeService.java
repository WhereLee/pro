package com.lrs.buddy.framework.modules.notice.service;

import com.lrs.buddy.framework.common.model.PageResult;
import com.lrs.buddy.framework.modules.notice.model.form.NoticeForm;
import com.lrs.buddy.framework.modules.notice.model.query.NoticeQuery;
import com.lrs.buddy.framework.modules.notice.model.vo.MyNoticeVO;
import com.lrs.buddy.framework.modules.notice.model.vo.NoticeVO;

import java.util.List;

/**
 * 通知公告服务。
 */
public interface NoticeService {

    /** 管理端分页 */
    PageResult<NoticeVO> pageNotices(NoticeQuery query);

    /** 新增（默认草稿状态），返回公告 ID，便于前端"保存并发布"一步完成 */
    Long saveNotice(NoticeForm form);

    void updateNotice(NoticeForm form);

    void removeNotices(List<Long> ids);

    /** 发布：置为已发布并推送给在线用户 */
    void publish(Long noticeId);

    /** 撤回 */
    void revoke(Long noticeId);

    /** 当前用户可见的公告 */
    List<MyNoticeVO> myNotices(Long userId);

    /** 未读数量 */
    long unreadCount(Long userId);

    /** 标记已读 */
    void markRead(Long userId, Long noticeId);
}
