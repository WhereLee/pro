package com.lrs.buddy.biz.barrier.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lrs.buddy.framework.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 一根杆（设备抽象）。多杆场景下每根独立调度、独立状态。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("barrier")
public class Barrier extends BaseEntity {

    private String name;
    private String location;
    /** 1 启用 0 停用；仅启用杆参与调度 */
    private Integer enabled;
}
