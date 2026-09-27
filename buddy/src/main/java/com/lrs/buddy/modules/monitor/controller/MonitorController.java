package com.lrs.buddy.modules.monitor.controller;

import com.lrs.buddy.common.R;
import com.lrs.buddy.common.sse.SseEmitterManager;
import com.lrs.buddy.modules.log.annotation.OperateLog;
import com.lrs.buddy.modules.log.enums.BusinessType;
import com.lrs.buddy.modules.monitor.model.dto.ServerMetrics;
import com.lrs.buddy.modules.monitor.model.vo.OnlineUserVO;
import com.lrs.buddy.modules.monitor.service.MonitorService;
import com.lrs.buddy.security.SecurityUtils;
import com.lrs.buddy.security.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 系统监控接口。
 */
@Slf4j
@Tag(name = "系统监控")
@RestController
@RequestMapping("/monitor")
@RequiredArgsConstructor
public class MonitorController {

    /** SSE 主题：服务器指标 */
    private static final String TOPIC_SERVER = "monitor-server";
    /** SSE 主题：强制下线通知 */
    private static final String TOPIC_FORCE_LOGOUT = "force-logout";

    private final MonitorService monitorService;
    private final TokenService tokenService;
    private final SseEmitterManager sseEmitterManager;

    @Operation(summary = "服务器指标快照")
    @PreAuthorize("hasAuthority('monitor:server:list')")
    @GetMapping("/server")
    public R<ServerMetrics> server() {
        return R.ok(monitorService.snapshot());
    }

    /**
     * 服务器指标实时推送（SSE）。
     *
     * <p>与快照接口配合：页面打开时先调 /server 拿一份立即渲染，
     * 再订阅本接口持续接收更新，避免"首屏空白等指标"和"定时轮询"两个问题。
     */
    @Operation(summary = "服务器指标实时推送（SSE）")
    @PreAuthorize("hasAuthority('monitor:server:list')")
    @GetMapping(value = "/server/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter serverStream() {
        return sseEmitterManager.subscribe(TOPIC_SERVER, SecurityUtils.getUserId());
    }

    @Operation(summary = "在线用户列表")
    @PreAuthorize("hasAuthority('monitor:online:list')")
    @GetMapping("/online")
    public R<List<OnlineUserVO>> online() {
        return R.ok(tokenService.onlineUsers());
    }

    /**
     * 强制下线。
     *
     * <p>两件事必须一起做：
     * <ol>
     *   <li>删除会话记录——用户下一个请求会被判为无效令牌（兜底）</li>
     *   <li>SSE 主动推送——让对方<b>立刻</b>收到通知并跳转登录页，
     *       而不是等到下次请求才被动失效</li>
     * </ol>
     */
    @Operation(summary = "强制用户下线")
    @OperateLog(title = "在线用户", businessType = BusinessType.KICK)
    @PreAuthorize("hasAuthority('monitor:online:kick')")
    @DeleteMapping("/online/{userId}")
    public R<Void> kickOut(@PathVariable Long userId) {
        tokenService.invalidate(userId);
        sseEmitterManager.sendToUser(userId, Map.of(
                "type", "FORCE_LOGOUT",
                "message", "您已被管理员强制下线"
        ));
        log.info("管理员 {} 强制下线用户 {}", SecurityUtils.getUsername(), userId);
        return R.ok(null, "已强制该用户下线");
    }

    /**
     * 订阅强制下线通知。
     * 前端登录后应立即建立该订阅，才能被"踢下线"时实时感知。
     */
    @Operation(summary = "订阅强制下线通知（SSE）")
    @GetMapping(value = "/force-logout/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter forceLogoutStream() {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            throw new com.lrs.buddy.common.BusinessException("未登录");
        }
        return sseEmitterManager.subscribe(TOPIC_FORCE_LOGOUT, userId);
    }
}
