package com.lrs.buddy.biz.swap.flow;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 柜机分段锁（四层并发的第一层，swap-order-fsm §9）。
 *
 * 四层各管一段，缺一层就会漏：
 * <ul>
 *   <li>进程内分段锁：同一台柜机的"读候选→分配→写预占"这段读改写窗口不与他人交错</li>
 *   <li>ShedLock：跨实例的定时任务不重复跑</li>
 *   <li>{@code @Version} CAS：单行的并发更新不丢</li>
 *   <li>DB 生成列唯一索引：{@code active_slot}/{@code active_user} 是最终裁判</li>
 * </ul>
 *
 * 既然唯一索引已经能保证不双占，为什么还要这把锁？两个理由，都不是"性能优化"：
 * 1 **没有它，冲突变成常态而不是偶发**。同柜并发下单时两个线程都会选中同一个最优仓，
 *    然后一个抢到、一个拿到 `SLOT_RACE_LOST` 拒单——用户看到"明明有空仓却被拒"。
 *    锁住之后第二个线程会重新分配次优仓，只有真没仓时才拒。
 * 2 **失败原因的可解释性**。锁内重试过的拒绝，理由才是"确实没仓"；
 *    没锁时的拒绝理由可能是"运气不好撞上了"，这种订单在统计与现场都无法解释。
 *
 * 分 64 段而不是按 cabinetId 建无限个锁：柜机数量会增长，`ConcurrentHashMap<Long,Lock>`
 * 无上限保留就是把内存泄漏藏在正确性代码里；取模分段在柜机很多时冲突概率也足够低。
 */
@Component
public class CabinetLocks {

    private static final int STRIPES = 64;
    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    public CabinetLocks() {
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new ReentrantLock(true);
        }
    }

    public ReentrantLock lockFor(long cabinetId) {
        // cabinetId 是雪花 id，低位分布均匀；取模即可，不需要 hash 再扰动
        return locks[(int) Math.floorMod(cabinetId, STRIPES)];
    }

    /**
     * 在柜机分段锁内执行。锁必须在事务**外面**：
     * 反过来（先开事务再拿锁）会出现"事务已提交但锁还被别人持有"的窗口，
     * 唯一索引的裁决在提交时刻生效，而锁的保护在释放时刻结束，两者错开就会漏。
     */
    public <T> T underLock(long cabinetId, java.util.function.Supplier<T> action) {
        ReentrantLock lock = lockFor(cabinetId);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
