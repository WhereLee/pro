package com.lrs.buddy.biz.swap.order;

/**
 * 订单事件（驱动 §5 迁移的输入）。
 *
 * **一条重要的实现口径**：文档 §5 里有些行是"同一状态收到同一类超时，按反查结果分岔"
 * （如 17/18/19 都是 deadline_S1）。如果事件名保持粗粒度，那么一条 `(state,event)`
 * 就会对应三个不同目标态，"未列出的组合即非法"这条硬约束就退化成"运行时再说"。
 * 所以这里把事件拆到**事实粒度**：反查得到什么事实，就投什么事件；
 * 一条 `(state,event)` 唯一确定一个目标态，迁移表可以被穷举测试。
 * 分岔的责任因此从"FSM 内部再判一次"前移到"反查产出事实"，判错地方也更好归因。
 */
public enum OrderEvent {

    // ---- 建单与前置 ----
    GUARD_PASS,
    GUARD_FAIL,

    // ---- 主线 ----
    DISPATCH_S1,
    EVT_DOOR_OPEN_RETURN,
    EVT_BATTERY_DETECTED_RETURN,
    EVT_DOOR_CLOSE_RETURN,
    ENTER_S3,
    VERIFY_OK,
    DISPATCH_S4,
    EVT_DOOR_OPEN_OFFER,
    EVT_BATTERY_TAKEN_OFFER,
    EVT_DOOR_CLOSE_OFFER,
    ENTER_S6,
    SETTLE_DONE,

    // ---- 拒收与未执行 ----
    CMD_NACK_NOT_EXECUTED,
    CMD_EXPIRED,
    VERIFY_FAIL_REJECT,

    // ---- S1 超时的三种反查事实（不是"一种超时三种结果"）----
    DEADLINE_S1_DOOR_OPEN,
    DEADLINE_S1_DOOR_CLOSED,
    DEADLINE_S1_UNKNOWABLE,

    // ---- 归还阶段其它超时 ----
    DEADLINE_NO_INSERT,
    DEADLINE_DOOR_NOT_CLOSED,

    // ---- 用户自助恢复（B2：用户声明是最低可信度来源，必须先反查）----
    USER_DECLARE_CLOSED_VERIFIED,
    USER_DECLARE_CLOSED_UNVERIFIED,
    DEADLINE_SUSPENDED,

    // ---- 不可断定收敛 ----
    QUERY_CONSISTENT_RETURNED,
    QUERY_CONSISTENT_TAKEN,
    DEADLINE_UNCONFIRMED,

    // ---- 取电阶段 ----
    DEADLINE_S4_NOT_OPEN,
    DEADLINE_TAKE_NOT_TAKEN,
    TAKEN_BUT_DOOR_OPEN,
    SLOT_EMPTY_WITHOUT_TAKE,

    // ---- 中止与结算异常 ----
    ABORT_DONE,
    SETTLE_FAIL,
    SETTLE_RETRY_EXHAUSTED,
    LATE_VERIFY_FAIL,

    // ---- 安全联动与人工干预 ----
    ALARM_SAFETY_LOCK,
    ADMIN_ABORT,
    ADMIN_RESOLVE_COMPLETED,
    ADMIN_RESOLVE_ABORTED
}
