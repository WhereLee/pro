package com.lrs.buddy.biz.swap.flow;

import com.lrs.buddy.biz.swap.order.OrderEvent;
import com.lrs.buddy.biz.swap.order.OrderState;
import com.lrs.buddy.biz.swap.order.StepCode;
import com.lrs.buddy.biz.swap.order.StepState;
import com.lrs.buddy.biz.swap.repo.SwapOrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 订单超时与反查驱动（§5.3 / §5.4 / §7.4 双保险的第二条）。
 *
 * 反查在**没有真实硬件的条件下不能凭空产生新事实**，所以这里只有两个来源，且顺序固定：
 * 1 台账投影（{@code swap_slot.door_state}）——它回答的是"云端已经知道门是什么状态"，
 *    由 door_open / door_close 事件维护；投影要新鲜（last_detected_at 未过期）才作数。
 * 2 影子上报态（{@code iot_shadow.reported_json}）——由 {@code QUERY_STATUS} 的应答写入
 *    （见 {@link SwapShadowBridge}），含义是"我们主动问过设备、它怎么回答的"。
 * 两个来源都没结论时**只能判"不可断定"**。把"云端不知道"伪装成"门没开"会直接导致重复开仓，
 * 那是真实事故形状，不是理论风险。真机的第三条通道（仓内门磁/摄像头独立采集）属 M5，
 * 到时只是多一个来源，本类结构不变。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SwapTimeoutDriver {

    private final SwapOrderRepository repo;
    private final SwapFlowService flow;
    private final MeterRegistry registry;

    @Scheduled(fixedDelayString = "${swap.order.deadline-scan-ms:5000}")
    @SchedulerLock(name = "swap-order-deadline", lockAtMostFor = "PT30S", lockAtLeastFor = "PT1S")
    public void sweep() {
        sweepOnce();
    }

    /** 暴露给测试：用例自己造过期数据后直接调一次，不靠 sleep 等真实时钟。 */
    public int sweepOnce() {
        List<SwapOrderRepository.OrderRow> due = repo.scanDueOrders(System.currentTimeMillis(), 50);
        for (SwapOrderRepository.OrderRow order : due) {
            try {
                handle(order, LocalDateTime.now());
            } catch (RuntimeException e) {
                // 单笔失败不拖垮整批：deadline 未续，下一轮还会扫到它
                registry.counter("swap.deadline.error").increment();
                log.error("超时处理异常：order={}, err={}", order.orderNo(), e.getMessage(), e);
            }
        }
        return due.size();
    }

    private void handle(SwapOrderRepository.OrderRow order, LocalDateTime now) {
        OrderState state = OrderState.valueOf(order.state());
        switch (state) {
            case AUTHORIZED -> recoverAuthorized(order);
            case RETURNING -> returning(order);
            case OFFERING -> offering(order);
            case SUSPENDED -> suspended(order, now);
            case UNCONFIRMED -> flow.deadlineUnconfirmed(order.id());
            default -> registry.counter("swap.deadline.unhandled", "state", state.name()).increment();
        }
    }

    /** AUTHORIZED 超时 = 建单后没人下发（进程重启、请求半途崩）。从事实重建现场：步骤仍 PENDING 就是没下发过。 */
    private void recoverAuthorized(SwapOrderRepository.OrderRow order) {
        Map<String, Object> step1 = repo.step(order.id(), StepCode.OPEN_RETURN.order());
        if (step1 == null || !StepState.PENDING.name().equals(String.valueOf(step1.get("step_state")))) {
            registry.counter("swap.deadline.recover_skip").increment();
            return;
        }
        try {
            flow.startReturn(order.id());
            registry.counter("swap.deadline.recovered").increment();
        } catch (RuntimeException e) {
            registry.counter("swap.deadline.recover_blocked").increment();
            log.warn("AUTHORIZED 超时但补下发失败（设备不可达）：order={}, err={}", order.orderNo(), e.getMessage());
        }
    }

    private void returning(SwapOrderRepository.OrderRow order) {
        Map<String, Object> step1 = repo.step(order.id(), StepCode.OPEN_RETURN.order());
        String step1State = step1 == null ? null : String.valueOf(step1.get("step_state"));
        if (StepState.DISPATCHED.name().equals(step1State)) {
            probeReturnDoor(order);
            return;
        }
        // 门已开，在等用户投入 / 关门
        boolean inserted = hasFact(order.id(), StepCode.WAIT_INSERT.order(), "insertedBattery");
        flow.fireDeadline(order.id(), OrderState.RETURNING,
                inserted ? OrderEvent.DEADLINE_DOOR_NOT_CLOSED : OrderEvent.DEADLINE_NO_INSERT,
                inserted ? "deadline_door_not_closed" : "deadline_no_insert");
    }

    /** S1 超时的三条反查分岔：门已开 / 确实没开（允许重发一次）/ 无从断定。 */
    private void probeReturnDoor(SwapOrderRepository.OrderRow order) {
        String door = probeDoor(order.cabinetId(), order.returnSlotNo());
        if ("OPEN".equals(door)) {
            flow.advanceReturnDoorOpen(order.id());
            flow.fireDeadline(order.id(), OrderState.RETURNING, OrderEvent.DEADLINE_S1_DOOR_OPEN,
                    "deadline_S1_door_open");
            return;
        }
        if (door == null) {
            unknowable(order);
            return;
        }
        if (repo.countDispatched(order.id(), StepCode.OPEN_RETURN.order()) < 2) {
            // 只有"确实没开"才允许重发：OPEN_SLOT 是副作用指令，重复开仓就是重复动作
            flow.fireDeadline(order.id(), OrderState.RETURNING, OrderEvent.DEADLINE_S1_DOOR_CLOSED,
                    "deadline_S1_door_closed");
            flow.redispatchReturnSlot(order.id());
            return;
        }
        unknowable(order);
    }

    private void unknowable(SwapOrderRepository.OrderRow order) {
        flow.markConfirmPending(order.id(), StepCode.OPEN_RETURN.order());
        flow.fireDeadline(order.id(), OrderState.RETURNING, OrderEvent.DEADLINE_S1_UNKNOWABLE,
                "deadline_S1_unknowable");
    }

    private void offering(SwapOrderRepository.OrderRow order) {
        Map<String, Object> step4 = repo.step(order.id(), StepCode.UNLOCK_OFFER.order());
        String step4State = step4 == null ? null : String.valueOf(step4.get("step_state"));
        if (StepState.DISPATCHED.name().equals(step4State)) {
            String door = probeDoor(order.cabinetId(), order.offerSlotNo());
            if ("OPEN".equals(door)) {
                flow.advanceOfferDoorOpen(order.id());
                flow.fireDeadline(order.id(), OrderState.OFFERING, OrderEvent.EVT_DOOR_OPEN_OFFER,
                        "deadline_offer_door_open");
                return;
            }
            if (repo.countDispatched(order.id(), StepCode.UNLOCK_OFFER.order()) < 2) {
                flow.fireDeadline(order.id(), OrderState.OFFERING, OrderEvent.DEADLINE_S4_NOT_OPEN,
                        "deadline_S4_re_dispatch");
                flow.redispatchOfferSlot(order.id());
                return;
            }
        }
        boolean taken = hasFact(order.id(), StepCode.WAIT_TAKE.order(), "takenBattery");
        // 已取走但门没关：电池已经在用户手上，走完成分支而不是中止（§5.4 #29）
        flow.fireDeadline(order.id(), OrderState.OFFERING,
                taken ? OrderEvent.TAKEN_BUT_DOOR_OPEN : OrderEvent.DEADLINE_TAKE_NOT_TAKEN,
                taken ? "deadline_taken_but_open" : "deadline_not_taken");
    }

    private void suspended(SwapOrderRepository.OrderRow order, LocalDateTime now) {
        // §5.3 #24：挂起到期无人处理 → 用户的旧电池进入待取回（责任边界），再走中止补偿
        if (order.returnBatteryId() != null) {
            repo.markBatteryPendingPickup(order.returnBatteryId(), "ORDER_SUSPENDED_TIMEOUT", now);
        }
        flow.suspendTimeout(order.id());
    }

    private boolean hasFact(long orderId, int stepNo, String key) {
        Map<String, Object> step = repo.step(orderId, stepNo);
        if (step == null || step.get("facts_json") == null) {
            return false;
        }
        // 事实以 JSON 文本形式存在步骤上；这里只做包含式判断，不去反序列化——
        // 超时分支只需要"有没有这个事实"，反序化失败不该把超时处理本身带倒
        return String.valueOf(step.get("facts_json")).contains(key);
    }

    /** 反查：投影优先，投影不可信时看影子；都没结论返回 null（=不可断定）。 */
    private String probeDoor(long cabinetId, Integer slotNo) {
        if (slotNo == null) {
            return null;
        }
        LocalDateTime freshAfter = LocalDateTime.now().minusMinutes(5);
        SwapOrderRepository.DoorProbe probe = repo.probeSlotDoor(cabinetId, slotNo);
        if (probe != null && probe.lastDetectedAt() != null && probe.lastDetectedAt().isAfter(freshAfter)) {
            return probe.doorState();
        }
        Long deviceRowId = repo.deviceRowOfCabinet(cabinetId);
        SwapOrderRepository.ShadowRow shadow = deviceRowId == null ? null : repo.shadowReported(deviceRowId);
        if (shadow != null && shadow.reportedJson() != null && shadow.syncedAt() != null
                && shadow.syncedAt().isAfter(freshAfter)) {
            return shadowDoor(shadow.reportedJson(), slotNo);
        }
        return null;
    }

    private static String shadowDoor(String json, int slotNo) {
        try {
            com.fasterxml.jackson.databind.JsonNode slots =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).path("slots");
            for (com.fasterxml.jackson.databind.JsonNode node : slots) {
                String text = node.isTextual() ? node.asText() : node.toString();
                if (text.startsWith(slotNo + ":")) {
                    String[] parts = text.split(":");
                    return parts.length > 1 ? parts[1] : null;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }
}
