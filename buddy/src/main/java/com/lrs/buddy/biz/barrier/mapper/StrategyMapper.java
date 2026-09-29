package com.lrs.buddy.biz.barrier.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lrs.buddy.biz.barrier.entity.Strategy;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface StrategyMapper extends BaseMapper<Strategy> {

    /** 绑定到某杆、且启用的策略（按优先级降序，供 StrategyResolver 选生效策略）。 */
    @Select("""
            SELECT s.id, s.name, s.priority, s.enabled
            FROM barrier_strategy s
            JOIN strategy_barrier sb ON sb.strategy_id = s.id
            WHERE sb.barrier_id = #{barrierId} AND s.enabled = 1 AND s.del_flag = 0
            ORDER BY s.priority DESC
            """)
    List<Strategy> selectBoundEnabled(@Param("barrierId") Long barrierId);

    /** 某策略绑定的杆 id 列表。 */
    @Select("SELECT barrier_id FROM strategy_barrier WHERE strategy_id = #{strategyId}")
    List<Long> selectBarrierIds(@Param("strategyId") Long strategyId);

    @Delete("DELETE FROM strategy_barrier WHERE strategy_id = #{strategyId}")
    void deleteBindings(@Param("strategyId") Long strategyId);

    @Insert("INSERT INTO strategy_barrier (strategy_id, barrier_id) VALUES (#{strategyId}, #{barrierId})")
    void bind(@Param("strategyId") Long strategyId, @Param("barrierId") Long barrierId);

    /** 某杆被多少策略绑定（删杆前校验）。 */
    @Select("SELECT COUNT(*) FROM strategy_barrier WHERE barrier_id = #{barrierId}")
    long countBindingsOfBarrier(@Param("barrierId") Long barrierId);
}
