package com.lrs.buddy.framework.event;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 进程间分发端口：本地用 Redis Stream，生产可换 RocketMQ/Kafka。
 *
 * 端口存在的意义：Outbox 只保证"事件一定被交给分发通道"，
 * 不保证通道可靠（Redis 会丢、会驱逐）。可靠性由消费侧幂等 + 每日对账兜住，
 * 这条边界必须写出来，否则会出现"用了 MQ 就以为不会丢"的错误假设。
 */
public interface StreamRelay {

    /** 把事件投递到通道；失败要抛异常，让 Outbox 走退避重试而不是标记成功。 */
    void publish(OutboxDao.Row row);

    @RequiredArgsConstructor
    class RedisStreamRelay implements StreamRelay {

        private static final String STREAM_PREFIX = "buddy:stream:";
        private final StringRedisTemplate redis;

        @Override
        public void publish(OutboxDao.Row row) {
            String stream = STREAM_PREFIX + row.topic();
            java.util.Map<String, String> body = new java.util.HashMap<>();
            body.put("event_type", row.eventType() == null ? "" : row.eventType());
            body.put("aggregate_type", row.aggregateType() == null ? "" : row.aggregateType());
            body.put("aggregate_id", String.valueOf(row.aggregateId()));
            body.put("trace_id", row.traceId() == null ? "" : row.traceId());
            body.put("payload", row.payload() == null ? "" : row.payload());
            redis.opsForStream().add(org.springframework.data.redis.connection.stream.MapRecord.create(stream, body));
        }
    }
}
