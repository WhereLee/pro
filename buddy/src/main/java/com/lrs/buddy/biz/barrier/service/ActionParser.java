package com.lrs.buddy.biz.barrier.service;

import com.lrs.buddy.framework.common.exception.BusinessException;
import com.lrs.buddy.biz.barrier.core.BarrierState;

/** 手动/计划状态词解析：兼容 OPEN / CLOSE / CLOSED；非法值以业务异常返回（避免枚举反序列化直接 500）。 */
public final class ActionParser {

    private ActionParser() {
    }

    public static BarrierState parse(String raw) {
        if (raw == null) {
            throw new BusinessException("状态不能为空");
        }
        return switch (raw.trim().toUpperCase()) {
            case "OPEN" -> BarrierState.OPEN;
            case "CLOSE", "CLOSED" -> BarrierState.CLOSED;
            default -> throw new BusinessException("非法状态：" + raw + "（仅 OPEN/CLOSE）");
        };
    }
}
