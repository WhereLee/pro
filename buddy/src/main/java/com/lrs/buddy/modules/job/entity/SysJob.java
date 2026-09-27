package com.lrs.buddy.modules.job.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lrs.buddy.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 定时任务定义。
 *
 * <p>任务定义存在自己的表里，而不是只用 Quartz 的 QRTZ_ 表：
 * QRTZ_ 表是 Quartz 的内部存储，字段都是二进制与缩写，
 * 业务侧要查询、展示、关联业务数据都很别扭。
 * 自己的表负责"业务语义"，Quartz 只负责"到点触发"。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_job")
public class SysJob extends BaseEntity {

    private String jobName;

    /** 任务分组，便于按模块归类 */
    private String jobGroup;

    /** 执行体的 Spring Bean 名称 */
    private String beanName;

    /** 传给执行体的参数 */
    private String params;

    private String cronExpression;

    /** 状态：0 正常，1 暂停 */
    private Integer status;

    /**
     * 是否允许并发执行：0 允许，1 禁止。
     *
     * 禁止并发用于"上一次还没跑完就不该再跑"的任务（如数据同步），
     * 实现方式是 {@code @DisallowConcurrentExecution}。
     */
    private Integer concurrent;

    /** 执行策略：1 立即执行 2 执行一次 3 放弃执行 */
    private Integer misfirePolicy;

    /** 最近一次执行状态：0 成功，1 失败 */
    private Integer lastStatus;

    private String lastMessage;
}
