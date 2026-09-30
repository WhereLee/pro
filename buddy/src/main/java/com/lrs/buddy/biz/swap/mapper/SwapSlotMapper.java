package com.lrs.buddy.biz.swap.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lrs.buddy.biz.swap.entity.SwapSlot;

/** 仓位台账 Mapper。状态迁移的"合法与否"由 DB CHECK 与生成列唯一索引最终裁决。 */
public interface SwapSlotMapper extends BaseMapper<SwapSlot> {
}
