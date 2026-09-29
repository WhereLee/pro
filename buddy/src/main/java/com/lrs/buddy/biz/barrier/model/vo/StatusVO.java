package com.lrs.buddy.biz.barrier.model.vo;

/** 当前态视图。 */
public record StatusVO(String state, boolean manualOverride, String lastScheduled) {
}
