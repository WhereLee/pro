package com.lrs.buddy.modules.notice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lrs.buddy.common.BusinessException;
import com.lrs.buddy.common.PageResult;
import com.lrs.buddy.common.sse.SseEmitterManager;
import com.lrs.buddy.modules.notice.entity.SysNotice;
import com.lrs.buddy.modules.notice.entity.SysNoticeRead;
import com.lrs.buddy.modules.notice.entity.SysNoticeTarget;
import com.lrs.buddy.modules.notice.mapper.SysNoticeMapper;
import com.lrs.buddy.modules.notice.mapper.SysNoticeReadMapper;
import com.lrs.buddy.modules.notice.mapper.SysNoticeTargetMapper;
import com.lrs.buddy.modules.notice.model.form.NoticeForm;
import com.lrs.buddy.modules.notice.model.query.NoticeQuery;
import com.lrs.buddy.modules.notice.model.vo.MyNoticeVO;
import com.lrs.buddy.modules.notice.model.vo.NoticeVO;
import com.lrs.buddy.modules.notice.service.NoticeService;
import com.lrs.buddy.modules.sys.entity.SysRole;
import com.lrs.buddy.modules.sys.service.SysRoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 通知公告服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoticeServiceImpl extends ServiceImpl<SysNoticeMapper, SysNotice> implements NoticeService {

    private static final int STATUS_DRAFT = 0;
    private static final int STATUS_PUBLISHED = 1;
    private static final int STATUS_REVOKED = 2;

    private static final int TARGET_ALL = 1;
    private static final int TARGET_ROLE = 2;
    private static final int TARGET_USER = 3;

    private final SysNoticeTargetMapper noticeTargetMapper;
    private final SysNoticeReadMapper noticeReadMapper;
    private final SysRoleService roleService;
    private final SseEmitterManager sseEmitterManager;

    @Override
    public PageResult<NoticeVO> pageNotices(NoticeQuery query) {
        LambdaQueryWrapper<SysNotice> wrapper = new LambdaQueryWrapper<SysNotice>()
                .like(StringUtils.hasText(query.getTitle()), SysNotice::getTitle, query.getTitle())
                .eq(Objects.nonNull(query.getType()), SysNotice::getType, query.getType())
                .eq(Objects.nonNull(query.getStatus()), SysNotice::getStatus, query.getStatus())
                .orderByDesc(SysNotice::getId);

        IPage<SysNotice> page = page(query.toPage(), wrapper);
        return PageResult.of(page, this::toVO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveNotice(NoticeForm form) {
        validateTarget(form);
        SysNotice notice = new SysNotice();
        BeanUtils.copyProperties(form, notice);
        // 新增一律是草稿，必须显式发布才对用户可见
        notice.setStatus(STATUS_DRAFT);
        save(notice);
        saveTargets(notice.getId(), form);
        return notice.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateNotice(NoticeForm form) {
        validateTarget(form);
        SysNotice exist = getById(form.getId());
        if (exist == null) {
            throw new BusinessException("公告不存在");
        }
        SysNotice notice = new SysNotice();
        BeanUtils.copyProperties(form, notice);
        // 已发布的公告不允许直接改内容：应先撤回再改，避免"悄悄改掉已发出的公告"
        if (STATUS_PUBLISHED == exist.getStatus()) {
            throw new BusinessException("公告已发布，请先撤回再修改");
        }
        notice.setStatus(exist.getStatus());
        updateById(notice);
        saveTargets(notice.getId(), form);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeNotices(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        removeByIds(ids);
        ids.forEach(noticeTargetMapper::deleteByNoticeId);
        ids.forEach(noticeReadMapper::deleteByNoticeId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(Long noticeId) {
        SysNotice notice = getById(noticeId);
        if (notice == null) {
            throw new BusinessException("公告不存在");
        }
        notice.setStatus(STATUS_PUBLISHED);
        notice.setPublishTime(LocalDateTime.now());
        updateById(notice);

        // 发布即刻推送，让在线用户无需刷新就能看到
        sseEmitterManager.broadcast("notice", Map.of(
                "type", "NOTICE",
                "id", notice.getId(),
                "title", notice.getTitle()
        ));
        log.info("公告已发布，id={}，title={}", noticeId, notice.getTitle());
    }

    @Override
    public void revoke(Long noticeId) {
        SysNotice notice = getById(noticeId);
        if (notice == null) {
            throw new BusinessException("公告不存在");
        }
        notice.setStatus(STATUS_REVOKED);
        updateById(notice);
    }

    @Override
    public List<MyNoticeVO> myNotices(Long userId) {
        List<Long> roleIds = roleIdsOf(userId);
        List<SysNotice> notices = baseMapper.selectVisible(userId, roleIds);
        if (notices.isEmpty()) {
            return List.of();
        }

        List<Long> readIds = readNoticeIds(userId);
        return notices.stream()
                .map(n -> MyNoticeVO.builder()
                        .id(n.getId())
                        .title(n.getTitle())
                        .content(n.getContent())
                        .type(n.getType())
                        .publishTime(n.getPublishTime())
                        .unread(!readIds.contains(n.getId()))
                        .build())
                .toList();
    }

    @Override
    public long unreadCount(Long userId) {
        return baseMapper.countUnread(userId, roleIdsOf(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long userId, Long noticeId) {
        long exist = noticeReadMapper.selectCount(new LambdaQueryWrapper<SysNoticeRead>()
                .eq(SysNoticeRead::getNoticeId, noticeId)
                .eq(SysNoticeRead::getUserId, userId));
        if (exist > 0) {
            // 唯一索引已保证不会重复，这里提前返回避免无谓的插入
            return;
        }
        SysNoticeRead read = new SysNoticeRead();
        read.setNoticeId(noticeId);
        read.setUserId(userId);
        read.setReadTime(LocalDateTime.now());
        noticeReadMapper.insert(read);
    }

    /** 定向类型为"指定角色/用户"时，目标列表不能为空 */
    private void validateTarget(NoticeForm form) {
        if (form.getTargetType() == null) {
            throw new BusinessException("定向类型不能为空");
        }
        if ((form.getTargetType() == TARGET_ROLE || form.getTargetType() == TARGET_USER)
                && CollectionUtils.isEmpty(form.getTargetIds())) {
            throw new BusinessException("指定角色/用户发布时，必须选择发布对象");
        }
    }

    private void saveTargets(Long noticeId, NoticeForm form) {
        noticeTargetMapper.deleteByNoticeId(noticeId);
        if (form.getTargetType() == TARGET_ALL || CollectionUtils.isEmpty(form.getTargetIds())) {
            return;
        }
        List<SysNoticeTarget> targets = new ArrayList<>(form.getTargetIds().size());
        for (Long targetId : form.getTargetIds()) {
            SysNoticeTarget target = new SysNoticeTarget();
            target.setNoticeId(noticeId);
            target.setTargetId(targetId);
            targets.add(target);
        }
        noticeTargetMapper.insertBatch(targets);
    }

    private List<Long> roleIdsOf(Long userId) {
        return roleService.rolesByUserId(userId).stream().map(SysRole::getId).toList();
    }

    private List<Long> readNoticeIds(Long userId) {
        return noticeReadMapper.selectList(new LambdaQueryWrapper<SysNoticeRead>()
                        .eq(SysNoticeRead::getUserId, userId))
                .stream()
                .map(SysNoticeRead::getNoticeId)
                .toList();
    }

    private NoticeVO toVO(SysNotice notice) {
        NoticeVO vo = new NoticeVO();
        BeanUtils.copyProperties(notice, vo);
        vo.setTypeDesc(notice.getType() == 2 ? "公告" : "通知");
        vo.setStatusDesc(switch (notice.getStatus()) {
            case STATUS_PUBLISHED -> "已发布";
            case STATUS_REVOKED -> "已撤回";
            default -> "草稿";
        });
        vo.setTargetTypeDesc(switch (notice.getTargetType()) {
            case TARGET_ROLE -> "指定角色";
            case TARGET_USER -> "指定用户";
            default -> "全体用户";
        });
        vo.setTargetIds(noticeTargetMapper.selectList(
                        new LambdaQueryWrapper<SysNoticeTarget>().eq(SysNoticeTarget::getNoticeId, notice.getId()))
                .stream()
                .map(SysNoticeTarget::getTargetId)
                .toList());
        return vo;
    }
}
