package com.lrs.buddy.biz.swap.flow;

import com.lrs.buddy.biz.swap.repo.SwapOrderRepository;
import com.lrs.buddy.framework.iot.command.CommandState;
import com.lrs.buddy.framework.iot.command.DeviceCommandService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 把 {@code QUERY_STATUS} 的应答写进影子的上报态，供超时反查读取。
 *
 * 为什么单独一个桥接类而不是塞进 {@code DeviceCommandService}：
 * 指令总线是框架层，它只知道"ACK 到了、把 reply_json 存下来"；
 * "哪个指令码构成设备状态、要不要因此更新影子"是换电业务的判断。
 * 混进框架层就会让 framework 反过来认识业务指令码，可复用性当场作废。
 *
 * 只认 QUERY_STATUS 是刻意的白名单：OPEN_SLOT 之类的应答 data 里只有 cmdId/slotNo，
 * 拿它覆盖上报态会把"云端以为的设备状态"污染成一条无关回执。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SwapShadowBridge implements DeviceCommandService.CommandListener {

    private static final String PROBE_COMMAND = "QUERY_STATUS";

    private final SwapOrderRepository repo;
    private final MeterRegistry registry;

    @Override
    public void onCommandStateChanged(DeviceCommandService.CommandRecord record, CommandState state) {
        if (state != CommandState.ACKED || !PROBE_COMMAND.equals(record.cmdCode())
                || !"SWAP_ORDER".equals(record.bizType()) || record.bizId() == null) {
            return;
        }
        Map<String, Object> command = repo.commandByCode(record.bizId(), PROBE_COMMAND);
        Object reply = command == null ? null : command.get("reply_json");
        if (reply == null || record.deviceRowId() == null) {
            return;
        }
        Long deviceRowId = record.deviceRowId();
        SwapOrderRepository.OrderRow order = repo.findOrder(record.bizId());
        repo.upsertShadowReported(deviceRowId, String.valueOf(reply), LocalDateTime.now(),
                order == null ? 1L : order.tenantId());
        registry.counter("swap.shadow.probe").increment();
        log.debug("影子上报态已更新：device={}, order={}", deviceRowId, order == null ? "?" : order.orderNo());
    }
}
