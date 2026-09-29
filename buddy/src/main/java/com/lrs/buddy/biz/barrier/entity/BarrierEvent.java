package com.lrs.buddy.biz.barrier.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 事件：某根杆一次生效切换，只追加不可变。operatorId 为手动操作人（定时为 null）。 */
@Data
@TableName("barrier_event")
public class BarrierEvent {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 归属杆 */
    private Long barrierId;
    private String barrierState;
    /** SCHEDULED / MANUAL */
    private String source;
    private LocalDateTime occurredAt;
    private String message;
    /** 手动操作人用户 id；定时触发为 null */
    private Long operatorId;
    private LocalDateTime createTime;
}
