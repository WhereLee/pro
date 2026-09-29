package com.lrs.buddy.biz.barrier.model.vo;

import java.time.LocalDateTime;

public record EventVO(Long id, String state, String source, LocalDateTime occurredAt, Long operatorId) {
}
