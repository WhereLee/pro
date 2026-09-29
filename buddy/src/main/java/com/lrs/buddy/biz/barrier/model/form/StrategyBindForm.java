package com.lrs.buddy.biz.barrier.model.form;

import lombok.Data;

import java.util.List;

@Data
public class StrategyBindForm {

    /** 该策略要绑定的杆 id 列表（整体替换）。 */
    private List<Long> barrierIds;
}
