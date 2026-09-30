package com.lrs.buddy.biz.swap.flow;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 订单级分段锁：串行化"同一笔订单的事件消费"。
 *
 * 为什么需要它（跨进程联跑实测出来的，同 JVM 测试测不到）：
 * 柜机经常在**同一毫秒**连发 `battery_detected` 与 `door_close`（投入并关门是一个动作）。
 * 接入层的 ingest 线程池是多线程的，两条消息会被并发消费；后一条会在前一条事务提交之前
 * 去读步骤状态，读到的是"还没有投入事实"，于是直接 return——**这一单永久卡在 RETURNING**，
 * 而两边日志都"看起来正常"。加锁把同一订单的事件消费排成序列，配合 DB 的 CAS 才完整。
 *
 * 与 {@code CabinetLocks} 分开两把数组：那边管"同一柜机的分配读改写窗口"，
 * 这边管"同一订单的事件顺序"。二者键空间不同（cabinetId / orderId），合并只会让
 * 读代码的人误以为它们是同一件事。仍然用固定分段而不是按 id 建锁：订单 id 无上限，
 * 按 id 缓存锁就是内存泄漏。
 */
@Component
public class OrderEventLocks {

    private static final int STRIPES = 64;
    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    public OrderEventLocks() {
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new ReentrantLock(true);
        }
    }

    /** 在订单分段锁内执行；调用方必须保证锁内的工作在自己的事务里完成。 */
    public <T> T underLock(long orderId, Supplier<T> action) {
        ReentrantLock lock = locks[(int) Math.floorMod(orderId, STRIPES)];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public ReentrantLock lockFor(long orderId) {
        return locks[(int) Math.floorMod(orderId, STRIPES)];
    }
}
