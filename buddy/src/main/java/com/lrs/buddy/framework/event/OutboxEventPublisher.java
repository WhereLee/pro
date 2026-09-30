package com.lrs.buddy.framework.event;

import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Outbox 的写入端。
 *
 * 为什么必须在调用方的事务里（REQUIRED）而不是新事务：
 * 业务回滚了事件却留下，就会产生"没人做过这件事"的幽灵事件；
 * 反过来先提交再发，则崩在中间就永久丢事件。同事务是这类不一致的唯一解。
 *
 * 投递端见 {@link OutboxDispatcher}。
 */
@RequiredArgsConstructor
public class OutboxEventPublisher implements EventPublisher {

    private final OutboxDao outboxDao;

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void append(Event event) {
        outboxDao.append(event, LocalDateTime.now());
    }
}
