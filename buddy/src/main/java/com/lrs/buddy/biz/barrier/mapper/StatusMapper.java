package com.lrs.buddy.biz.barrier.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lrs.buddy.biz.barrier.entity.BarrierStatus;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface StatusMapper extends BaseMapper<BarrierStatus> {

    /**
     * 乐观锁 CAS 更新某杆状态：仅当当前 version == expectedVersion 时才写，并把 version+1。
     * 返回影响行数；0 表示版本已被其他实例改动（冲突），调用方据此回滚事务、不记孤儿事件。
     *
     * <p>用原生 SQL 而非 {@code updateById}：以 barrier_id 定位（领域视图不带主键 id），
     * 且显式把 version 作为 WHERE 谓词、version+1 自增，语义比"忽略 updateById 返回值"清晰可靠。
     */
    @Update("UPDATE barrier_status SET barrier_state = #{state}, manual_override = #{override}, "
            + "last_scheduled_action = #{lastScheduled}, version = version + 1, update_time = #{now} "
            + "WHERE barrier_id = #{barrierId} AND version = #{expectedVersion} AND del_flag = 0")
    int casUpdate(@Param("barrierId") Long barrierId,
                  @Param("state") String state,
                  @Param("override") int override,
                  @Param("lastScheduled") String lastScheduled,
                  @Param("now") LocalDateTime now,
                  @Param("expectedVersion") Long expectedVersion);
}
