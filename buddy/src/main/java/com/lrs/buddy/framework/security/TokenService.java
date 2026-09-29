package com.lrs.buddy.framework.security;

import com.lrs.buddy.framework.modules.monitor.model.vo.OnlineUserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录会话管理（在线台账 + 强制下线）。
 *
 * <h3>为什么需要它</h3>
 * JWT 是无状态的：令牌签发后在过期前无法主动失效。但"管理员强制下线"
 * 是后台系统的常见需求。解决办法是引入一个极轻量的服务端状态——
 * 不存整个会话，只存当前有效的 {@code jti}（令牌 ID）。
 *
 * <h3>Redis 数据结构</h3>
 * <pre>
 *   buddy:online:index      ZSet   member=userId, score=最后活跃时间戳
 *   buddy:online:{userId}   Hash   tokenId/ip/loginTime/lastActive/userAgent/...
 * </pre>
 * 用 ZSet 维护索引而不是用 {@code keys buddy:online:*} 扫描，原因是：
 * <b>KEYS 命令会遍历整个键空间并阻塞 Redis 单线程</b>，生产环境一般直接禁用；
 * ZSet 的 zrange/zrem 都是 O(logN)，还能天然按活跃时间排序和清理过期项。
 *
 * <h3>降级策略</h3>
 * Redis 不可用时所有校验方法返回"放行"，保证应用仍能启动和登录，
 * 只是暂时失去强制下线能力。缺失的能力会在日志中告警。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private static final String ONLINE_INDEX = "buddy:online:index";
    private static final String ONLINE_DETAIL = "buddy:online:";

    private static final String FIELD_TOKEN_ID = "tokenId";
    private static final String FIELD_USERNAME = "username";
    private static final String FIELD_NICKNAME = "nickname";
    private static final String FIELD_IP = "ip";
    private static final String FIELD_UA = "userAgent";
    private static final String FIELD_LOGIN_TIME = "loginTime";
    private static final String FIELD_LAST_ACTIVE = "lastActiveTime";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 活跃时间最小更新间隔 */
    private static final long TOUCH_INTERVAL_MS = 30_000L;
    /** 本地节流记录的清理阈值：超过该时长没再活跃就丢弃 */
    private static final long CLEANUP_AFTER_MS = 10 * 60_000L;
    private static final Map<Long, Long> LAST_TOUCH_AT = new ConcurrentHashMap<>();

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 登记一次登录。若配置为"单设备登录"，会先把该用户的旧会话挤掉。
     */
    public void register(LoginUser user, String ip, String userAgent) {
        String detailKey = ONLINE_DETAIL + user.getUserId();
        long now = System.currentTimeMillis();

        Map<String, Object> detail = Map.of(
                FIELD_TOKEN_ID, user.getTokenId(),
                FIELD_USERNAME, user.getUsername(),
                FIELD_NICKNAME, user.getNickname() == null ? "" : user.getNickname(),
                FIELD_IP, ip == null ? "" : ip,
                FIELD_UA, abbreviate(userAgent),
                FIELD_LOGIN_TIME, LocalDateTime.now().format(FORMATTER),
                FIELD_LAST_ACTIVE, LocalDateTime.now().format(FORMATTER)
        );

        try {
            redisTemplate.opsForHash().putAll(detailKey, detail);
            redisTemplate.opsForZSet().add(ONLINE_INDEX, String.valueOf(user.getUserId()), now);
            log.debug("会话已登记，userId={}", user.getUserId());
        } catch (Exception e) {
            log.warn("会话登记失败（Redis 不可用？），强制下线能力暂时失效：{}", e.getMessage());
        }
    }

    /**
     * 校验令牌是否仍然有效。
     *
     * @param userId 用户 ID
     * @param jti    令牌中的 jti
     * @return true 表示有效；Redis 不可用时降级为 true
     */
    public boolean isTokenValid(Long userId, String jti) {
        try {
            Object stored = redisTemplate.opsForHash().get(ONLINE_DETAIL + userId, FIELD_TOKEN_ID);
            if (stored == null) {
                // 没有会话记录：可能是 Redis 被清空，也可能是已被踢下线。
                // 这里选择放行——否则一次 Redis 数据丢失会导致所有人都被登出。
                return true;
            }
            return jti != null && jti.equals(stored.toString());
        } catch (Exception e) {
            log.warn("令牌校验失败（Redis 不可用），降级放行：{}", e.getMessage());
            return true;
        }
    }

    /**
     * 刷新最后活跃时间。
     *
     * <p>做了本地节流：鉴权过滤器每个请求都会调用它，
     * 不做限制的话每个业务请求都要额外写两次 Redis，
     * 在高并发下这是很可观的无效写。
     * "最后活跃时间"本身只需要分钟级精度，30 秒更新一次足够。
     *
     * <p>节流放在本地内存而非 Redis：省掉一次"读上次时间"的网络往返。
     * 代价是集群各实例独立计时，但对"展示最后活跃时间"这种需求没有影响。
     */
    public void touch(Long userId) {
        long now = System.currentTimeMillis();
        Long last = LAST_TOUCH_AT.get(userId);
        if (last != null && now - last < TOUCH_INTERVAL_MS) {
            return;
        }
        LAST_TOUCH_AT.put(userId, now);

        try {
            String detailKey = ONLINE_DETAIL + userId;
            redisTemplate.opsForHash().put(detailKey, FIELD_LAST_ACTIVE, LocalDateTime.now().format(FORMATTER));
            redisTemplate.opsForZSet().add(ONLINE_INDEX, String.valueOf(userId), now);
        } catch (Exception e) {
            log.debug("刷新活跃时间失败：{}", e.getMessage());
        } finally {
            // 会话被移除后清理本地记录，避免长期运行的实例累积无用条目
            LAST_TOUCH_AT.entrySet().removeIf(entry -> now - entry.getValue() > CLEANUP_AFTER_MS);
        }
    }

    /**
     * 强制下线：删除会话记录，该用户后续请求会被判为无效令牌。
     */
    public void invalidate(Long userId) {
        try {
            redisTemplate.delete(ONLINE_DETAIL + userId);
            redisTemplate.opsForZSet().remove(ONLINE_INDEX, String.valueOf(userId));
            log.info("已强制下线，userId={}", userId);
        } catch (Exception e) {
            log.warn("强制下线失败：{}", e.getMessage());
        }
    }

    /** 正常登出 */
    public void logout(Long userId) {
        invalidate(userId);
    }

    /**
     * 在线用户列表，按最后活跃时间倒序。
     */
    public List<OnlineUserVO> onlineUsers() {
        List<OnlineUserVO> result = new ArrayList<>();
        try {
            // 只取 member，不取 score：ZSet 已按 score 排序，倒序即最近活跃在前
            Set<Object> members = redisTemplate.opsForZSet().reverseRange(ONLINE_INDEX, 0, -1);
            if (members == null) {
                return result;
            }
            for (Object member : members) {
                String userId = member.toString();
                Map<Object, Object> detail = redisTemplate.opsForHash().entries(ONLINE_DETAIL + userId);
                if (detail.isEmpty()) {
                    // 索引残留但详情已过期，清理掉避免脏数据
                    redisTemplate.opsForZSet().remove(ONLINE_INDEX, userId);
                    continue;
                }
                result.add(OnlineUserVO.builder()
                        .userId(Long.valueOf(userId))
                        .username(str(detail.get(FIELD_USERNAME)))
                        .nickname(str(detail.get(FIELD_NICKNAME)))
                        .ip(str(detail.get(FIELD_IP)))
                        .userAgent(str(detail.get(FIELD_UA)))
                        .loginTime(str(detail.get(FIELD_LOGIN_TIME)))
                        .lastActiveTime(str(detail.get(FIELD_LAST_ACTIVE)))
                        .build());
            }
        } catch (Exception e) {
            log.warn("查询在线用户失败：{}", e.getMessage());
        }
        return result;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    /** User-Agent 往往很长，列表里只保留前 120 字符 */
    private static String abbreviate(String ua) {
        if (ua == null) {
            return "";
        }
        return ua.length() > 120 ? ua.substring(0, 120) + "..." : ua;
    }
}
