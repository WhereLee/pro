package com.lrs.buddy.modules.log.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lrs.buddy.common.PageResult;
import com.lrs.buddy.config.AsyncConfig;
import com.lrs.buddy.modules.log.entity.SysOperateLog;
import com.lrs.buddy.modules.log.mapper.SysOperateLogMapper;
import com.lrs.buddy.modules.log.model.OperateLogEvent;
import com.lrs.buddy.modules.log.model.query.OperateLogQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 操作日志服务。
 *
 * <p>落库走异步：日志属于"旁路"信息，不应该因为写日志变慢或失败而拖累主流程。
 * 这里显式指定线程池（而不是依赖默认 Executor），
 * 避免与框架其它异步任务抢同一批线程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperateLogService {

    private final SysOperateLogMapper operateLogMapper;

    /**
     * 异步保存日志。
     *
     * <p>入参是已经取好数据的事件对象，不依赖 ThreadLocal，
     * 因此异步线程里不会出现"读不到当前用户"的问题。
     */
    @Async(AsyncConfig.TASK_EXECUTOR)
    public void saveAsync(OperateLogEvent event) {
        try {
            SysOperateLog entity = new SysOperateLog();
            BeanUtils.copyProperties(event, entity);
            operateLogMapper.insert(entity);
        } catch (Exception e) {
            // 日志写入失败只记录，绝不向上抛——否则会把"记日志失败"变成业务失败
            log.error("操作日志保存失败：{}", e.getMessage(), e);
        }
    }

    public PageResult<SysOperateLog> pageLogs(OperateLogQuery query) {
        LambdaQueryWrapper<SysOperateLog> wrapper = new LambdaQueryWrapper<SysOperateLog>()
                .like(StringUtils.hasText(query.getTitle()), SysOperateLog::getTitle, query.getTitle())
                .eq(Objects.nonNull(query.getBusinessType()), SysOperateLog::getBusinessType, query.getBusinessType())
                .eq(Objects.nonNull(query.getStatus()), SysOperateLog::getStatus, query.getStatus())
                .like(StringUtils.hasText(query.getOperatorName()), SysOperateLog::getOperatorName, query.getOperatorName())
                .orderByDesc(SysOperateLog::getOperTime);

        IPage<SysOperateLog> page = operateLogMapper.selectPage(query.toPage(), wrapper);
        return PageResult.of(page);
    }
}
