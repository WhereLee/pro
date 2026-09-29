package com.lrs.buddy.framework.common.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE（Server-Sent Events）连接管理器。
 *
 * <h3>为什么选 SSE 而不是 WebSocket</h3>
 * 系统的三类推送——监控指标、公告提醒、强制下线通知——都是<b>单向</b>的
 * （只有服务端→客户端），不需要客户端向服务端推送。
 * SSE 基于普通 HTTP、浏览器原生支持自动重连，Spring 侧只需要一个 SseEmitter，
 * 比引入 WebSocket 协议栈（握手、心跳帧、代理配置）轻得多。
 * 双向场景才值得用 WebSocket，这里用它是杀鸡用牛刀。
 *
 * <h3>连接组织方式</h3>
 * 同时按"主题"和"用户"两个维度建立索引：
 * <ul>
 *   <li>按主题广播：监控指标推给所有订阅者</li>
 *   <li>按用户推送：强制下线只推给被踢的那个人</li>
 * </ul>
 */
@Slf4j
@Service
public class SseEmitterManager {

    /** 主题 → 该主题下的全部连接 */
    private final Map<String, Set<SseEmitter>> topicEmitters = new ConcurrentHashMap<>();
    /** 用户 ID → 该用户的全部连接（一个人可能开多个标签页） */
    private final Map<Long, Set<SseEmitter>> userEmitters = new ConcurrentHashMap<>();

    /**
     * 建立订阅。
     *
     * @param topic  主题，如 monitor-server / notice / force-logout
     * @param userId 订阅者；为 null 表示匿名（仅按主题接收）
     */
    public SseEmitter subscribe(String topic, Long userId) {
        // 超时设为 0：不过期。连接断开由 onCompletion/onError 回收，
        // 存活期间靠 heartbeat 维持（防止中间层代理因空闲断开）
        SseEmitter emitter = new SseEmitter(0L);

        topicEmitters.computeIfAbsent(topic, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        if (userId != null) {
            userEmitters.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        }

        Runnable cleanup = () -> remove(topic, userId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        sendComment(emitter, "connected");
        log.debug("SSE 订阅建立，topic={}, userId={}，当前连接数={}",
                topic, userId, topicEmitters.getOrDefault(topic, Set.of()).size());
        return emitter;
    }

    /** 向某个主题广播 */
    public void broadcast(String topic, Object data) {
        Set<SseEmitter> emitters = topicEmitters.get(topic);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            send(emitter, data);
        }
    }

    /** 向指定用户推送（其所有连接都会收到） */
    public void sendToUser(Long userId, Object data) {
        if (userId == null) {
            return;
        }
        Set<SseEmitter> emitters = userEmitters.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            send(emitter, data);
        }
    }

    /**
     * 心跳：向所有连接发送一行注释。
     *
     * <p>某些反向代理（如 Nginx 默认 60s）会断开长时间无数据的连接，
     * 定期发送注释行可以保活，且注释不会被浏览器当作消息事件。
     */
    public void heartbeat() {
        topicEmitters.values().forEach(emitters -> {
            for (SseEmitter emitter : emitters) {
                sendComment(emitter, "ping");
            }
        });
    }

    private void remove(String topic, Long userId, SseEmitter emitter) {
        Set<SseEmitter> byTopic = topicEmitters.get(topic);
        if (byTopic != null) {
            byTopic.remove(emitter);
            if (byTopic.isEmpty()) {
                topicEmitters.remove(topic);
            }
        }
        if (userId != null) {
            Set<SseEmitter> byUser = userEmitters.get(userId);
            if (byUser != null) {
                byUser.remove(emitter);
                if (byUser.isEmpty()) {
                    userEmitters.remove(userId);
                }
            }
        }
    }

    private void send(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // 客户端已断开：这里只记日志，真正的清理交给 onCompletion 回调
            log.debug("SSE 推送失败，连接可能已关闭：{}", e.getMessage());
        }
    }

    private void sendComment(SseEmitter emitter, String comment) {
        try {
            emitter.send(SseEmitter.event().comment(comment));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 心跳发送失败：{}", e.getMessage());
        }
    }
}
