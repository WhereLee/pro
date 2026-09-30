package com.lrs.buddy.framework.event;

/**
 * 事件发布端口 + 本地消息表（Transactional Outbox）。
 *
 * 为什么必须有 Outbox 而不是"直接发消息"：
 * 业务事务提交与消息投递是两步，任何一步失败都会造成两边不一致 ——
 * 先发消息后提交，消息可能被消费到并不存在的事实；先提交后发，进程崩在中间就永久丢事件。
 * 唯一可靠的做法是把"要发的事件"和业务数据写进同一事务同一库，由独立投递器负责送出与重试。
 *
 * 这是本地实现（Redis Stream 分发），生产换 RocketMQ 事务消息时业务代码不变（swap-plan.md §7 A4）。
 */
public interface EventPublisher {

    /**
     * 在**当前业务事务内**追加一条待发事件。
     *
     * @param dedupKey 幂等键，DB 唯一索引；重复追加会被拒 —— 防止重试导致同一事实被投递两次
     */
    void append(Event event);

    /** 一条领域事件。 */
    record Event(String dedupKey, String aggregateType, long aggregateId, String eventType, String topic,
                 String payload, String traceId, long tenantId) {
    }
}
