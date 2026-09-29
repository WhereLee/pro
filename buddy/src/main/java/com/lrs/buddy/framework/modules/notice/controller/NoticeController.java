package com.lrs.buddy.framework.modules.notice.controller;

import com.lrs.buddy.framework.common.response.R;
import com.lrs.buddy.framework.common.annotation.RepeatSubmit;
import com.lrs.buddy.framework.modules.log.annotation.OperateLog;
import com.lrs.buddy.framework.modules.log.enums.BusinessType;
import com.lrs.buddy.framework.modules.notice.model.form.NoticeForm;
import com.lrs.buddy.framework.modules.notice.model.query.NoticeQuery;
import com.lrs.buddy.framework.modules.notice.model.vo.MyNoticeVO;
import com.lrs.buddy.framework.modules.notice.model.vo.NoticeVO;
import com.lrs.buddy.framework.modules.notice.service.NoticeService;
import com.lrs.buddy.framework.common.model.PageResult;
import com.lrs.buddy.framework.common.sse.SseEmitterManager;
import com.lrs.buddy.framework.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 通知公告接口。
 *
 * <p>分成两类：
 * <ul>
 *   <li>管理端（/notice/page、增删改、发布撤回）—— 需要 notice:* 权限</li>
 *   <li>用户端（/notice/mine、未读数、已读）—— 登录即可，不要求额外权限</li>
 * </ul>
 */
@Tag(name = "通知公告")
@RestController
@RequestMapping("/notice")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;
    private final SseEmitterManager sseEmitterManager;

    /* ================= 管理端 ================= */

    @Operation(summary = "公告分页列表")
    @PreAuthorize("hasAuthority('notice:list')")
    @PostMapping("/page")
    public R<PageResult<NoticeVO>> page(@Valid @RequestBody NoticeQuery query) {
        return R.ok(noticeService.pageNotices(query));
    }

    @Operation(summary = "新增公告（默认草稿）")
    @OperateLog(title = "公告管理", businessType = BusinessType.INSERT)
    @RepeatSubmit(interval = 3000)
    @PreAuthorize("hasAuthority('notice:save')")
    @PostMapping
    public R<Long> save(@Valid @RequestBody NoticeForm form) {
        Long noticeId = noticeService.saveNotice(form);
        return R.ok(noticeId, "新增成功，当前为草稿状态");
    }

    @Operation(summary = "修改公告（已发布的需先撤回）")
    @OperateLog(title = "公告管理", businessType = BusinessType.UPDATE)
    @PreAuthorize("hasAuthority('notice:update')")
    @PutMapping
    public R<Void> update(@Valid @RequestBody NoticeForm form) {
        noticeService.updateNotice(form);
        return R.ok(null, "修改成功");
    }

    @Operation(summary = "删除公告（支持批量）")
    @OperateLog(title = "公告管理", businessType = BusinessType.DELETE)
    @PreAuthorize("hasAuthority('notice:remove')")
    @DeleteMapping
    public R<Void> remove(@RequestBody List<Long> ids) {
        noticeService.removeNotices(ids);
        return R.ok(null, "删除成功");
    }

    @Operation(summary = "发布公告")
    @OperateLog(title = "公告管理", businessType = BusinessType.GRANT)
    @PreAuthorize("hasAuthority('notice:publish')")
    @PutMapping("/publish/{id}")
    public R<Void> publish(@PathVariable Long id) {
        noticeService.publish(id);
        return R.ok(null, "已发布");
    }

    @Operation(summary = "撤回公告")
    @OperateLog(title = "公告管理", businessType = BusinessType.GRANT)
    @PreAuthorize("hasAuthority('notice:publish')")
    @PutMapping("/revoke/{id}")
    public R<Void> revoke(@PathVariable Long id) {
        noticeService.revoke(id);
        return R.ok(null, "已撤回");
    }

    /* ================= 用户端 ================= */

    @Operation(summary = "我可见的公告")
    @GetMapping("/mine")
    public R<List<MyNoticeVO>> mine() {
        return R.ok(noticeService.myNotices(requireUserId()));
    }

    @Operation(summary = "未读公告数量")
    @GetMapping("/unread-count")
    public R<Long> unreadCount() {
        return R.ok(noticeService.unreadCount(requireUserId()));
    }

    @Operation(summary = "标记公告已读")
    @PutMapping("/read/{id}")
    public R<Void> read(@PathVariable Long id) {
        noticeService.markRead(requireUserId(), id);
        return R.ok(null, "已标记已读");
    }

    /** 订阅公告发布提醒 */
    @Operation(summary = "订阅公告推送（SSE）")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        Long userId = requireUserId();
        return sseEmitterManager.subscribe("notice", userId);
    }

    private Long requireUserId() {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            throw new com.lrs.buddy.framework.common.exception.BusinessException("未登录");
        }
        return userId;
    }
}
