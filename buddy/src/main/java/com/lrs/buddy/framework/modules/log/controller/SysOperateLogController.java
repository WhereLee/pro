package com.lrs.buddy.framework.modules.log.controller;

import com.lrs.buddy.framework.common.model.PageResult;
import com.lrs.buddy.framework.common.response.R;
import com.lrs.buddy.framework.modules.log.entity.SysOperateLog;
import com.lrs.buddy.framework.modules.log.model.query.OperateLogQuery;
import com.lrs.buddy.framework.modules.log.service.OperateLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志查询。
 */
@Tag(name = "操作日志")
@RestController
@RequestMapping("/sys/operate-log")
@RequiredArgsConstructor
public class SysOperateLogController {

    private final OperateLogService operateLogService;

    @Operation(summary = "操作日志分页列表")
    @PreAuthorize("hasAuthority('sys:operateLog:list')")
    @PostMapping("/page")
    public R<PageResult<SysOperateLog>> page(@Valid @RequestBody OperateLogQuery query) {
        return R.ok(operateLogService.pageLogs(query));
    }
}
