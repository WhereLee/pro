package com.lrs.buddy.biz.swap.flow;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrs.buddy.biz.swap.order.OrderEvent;
import com.lrs.buddy.biz.swap.order.OrderState;
import com.lrs.buddy.biz.swap.order.StepCode;
import com.lrs.buddy.biz.swap.order.StepState;
import com.lrs.buddy.biz.swap.order.SwapOrderFsm;
import com.lrs.buddy.biz.swap.order.SwapStepFsm;
import com.lrs.buddy.biz.swap.repo.SwapOrderRepository;
import com.lrs.buddy.framework.iot.command.DeviceCommandService;
import com.lrs.buddy.framework.iot.envelope.Envelope;
import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 换电流程推进：物理事件 → 步骤 → 订单 → 下发 → 结算（M2 B4）。
 *
 * 四条实现立场，都是这个项目区别于"CRUD 项目"的地方：
 *
 * 1 **只有步骤被物理事件直接推进**，订单态由"步骤完成组合"推导（FSM §4.2）。
 * 2 **所有权变更与扣减同事务（I3）**。§5.1 第 10 条写着 `battery_taken` 时"电池=HELD_BY_USER"，
 *    但那条如果就地写台账，就会出现"电池已在用户手上而权益还没扣"的窗口。
 *    实现上把归属写入推迟到 TAKEN→SETTLING→COMPLETED 这一条事务里，与扣减同批；
 *    `battery_taken` 事件只往步骤 facts 记事实。这是有意的偏离，注释与本文都写明，不藏。
 * 3 **每一步迁移都是 CAS**：带 fromState 谓词。事件、超时任务、人工干预会在不同线程上
 *    命中同一订单，无谓词的写法会后写覆盖先写且不留痕迹。
 * 4 **无法归属到在途订单的事件不静默丢弃**：记 UNMATCHED_EVENT 差异 + 计数。
 *    它是"设备在做我没下单的事"的唯一线索（例如现场人工开门）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SwapFlowService {

    private static final String BIZ_TYPE = "SWAP_ORDER";

    private final SwapOrderRepository repo;
    private final DeviceCommandService commands;
    private final ObjectMapper objectMapper;
    private final MeterRegistry registry;

    /** 建单后下发 S1：开归还仓。 */
    @Transactional
    public void startReturn(long orderId) {
        SwapOrderRepository.OrderRow order = requireOrder(orderId);
        requireState(order, OrderState.AUTHORIZED);
        Map<String, Object> step = repo.step(orderId, StepCode.OPEN_RETURN.order());
        int slotNo = ((Number) step.get("slot_no")).intValue();
        DeviceCommandService.CommandRecord issued = dispatch(order, StepCode.OPEN_RETURN, slotNo, "OPEN_SLOT");
        if (issued.state() != com.lrs.buddy.framework.iot.command.CommandState.DISPATCHED) {
            // 一步都没发生就把订单推到 RETURNING，后面全部推导就建在假事实上；
            // 拒绝 + 回滚（同一事务里的指令行一并回滚，不留一条永远发不出去的指令）
            throw new IllegalStateException("柜机当前不可达，无法开始换电：" + order.orderNo());
        }
        String cmdId = issued.cmdId();
        advanceStep(orderId, StepCode.OPEN_RETURN.order(), StepState.PENDING.name(), StepState.DISPATCHED.name(),
                cmdId, null, null);
        fireOrder(orderId, OrderState.AUTHORIZED, OrderEvent.DISPATCH_S1, "DISPATCH_S1",
                slotNo, null, cmdId);
        log.info("S1 已下发：order={}, slot={}, cmd={}", order.orderNo(), slotNo, cmdId);
    }

    /** 事件入口（由 SwapEventListener 在校验链通过后调用）。 */
    @Transactional
    public void onEvent(DeviceDirectoryDao.Device device, Envelope envelope) {
        JsonNode data = envelope.data();
        String eventType = data == null ? null : data.path("eventType").asText(null);
        if (eventType == null) {
            registry.counter("swap.event.no_type").increment();
            return;
        }
        if ("swap_result".equals(eventType)) {
            reconcile(device, envelope);
            return;
        }
        Integer slotNo = data.hasNonNull("slotNo") ? data.get("slotNo").asInt() : null;
        LocalDateTime now = LocalDateTime.now();
        SwapOrderRepository.OrderRow order = repo.findInfightByDevice(device.id());
        if (order == null) {
            // 归属不到订单的事实必须留痕，不能丢
            registry.counter("swap.event.unmatched").increment();
            repo.insertDiscrepancy(IdWorker.getId(), "UNMATCHED:" + envelope.msgId(), "UNMATCHED_EVENT",
                    null, null, null, null, null, data.toString(),
                    "设备上报 " + eventType + " 但无在途订单", device.tenantId());
            return;
        }
        if (!repo.insertEventDedup(IdWorker.getId(), order.id(), eventType, envelope.msgId(), slotNo, now,
                order.tenantId())) {
            registry.counter("swap.event.duplicate").increment();
            return;
        }
        switch (eventType) {
            case "door_open" -> onDoorOpen(order, slotNo, envelope, now);
            case "battery_detected" -> onBatteryDetected(order, slotNo, data, now);
            case "door_close" -> onDoorClose(order, slotNo, data, now);
            case "battery_taken" -> onBatteryTaken(order, slotNo, data, now);
            default -> registry.counter("swap.event.ignored", "type", eventType).increment();
        }
    }

    private void onDoorOpen(SwapOrderRepository.OrderRow order, Integer slotNo, Envelope envelope,
                             LocalDateTime now) {
        if (order.state().equals(OrderState.RETURNING.name()) && eq(slotNo, order.returnSlotNo())) {
            repo.setSlotDoor(order.cabinetId(), slotNo, "OPEN", "UNLOCKED", now);
            advanceStep(order.id(), StepCode.OPEN_RETURN.order(), StepState.DISPATCHED.name(),
                    StepState.OPEN_CONFIRMED.name(), null, envelope.sessionId(), null);
            fireOrder(order.id(), OrderState.RETURNING, OrderEvent.EVT_DOOR_OPEN_RETURN, "door_open@return",
                    slotNo, null, envelope.msgId());
        } else if (order.state().equals(OrderState.OFFERING.name()) && eq(slotNo, order.offerSlotNo())) {
            repo.setSlotDoor(order.cabinetId(), slotNo, "OPEN", "UNLOCKED", now);
            advanceStep(order.id(), StepCode.UNLOCK_OFFER.order(), StepState.DISPATCHED.name(),
                    StepState.OPEN_CONFIRMED.name(), null, envelope.sessionId(), null);
            fireOrder(order.id(), OrderState.OFFERING, OrderEvent.EVT_DOOR_OPEN_OFFER, "door_open@offer",
                    slotNo, null, envelope.msgId());
        }
    }

    private void onBatteryDetected(SwapOrderRepository.OrderRow order, Integer slotNo, JsonNode data,
                                    LocalDateTime now) {
        if (!order.state().equals(OrderState.RETURNING.name()) || !eq(slotNo, order.returnSlotNo())) {
            return;
        }
        String code = data.path("batteryCode").asText(null);
        Map<String, Object> step2 = repo.step(order.id(), StepCode.WAIT_INSERT.order());
        ObjectNode facts = objectMapper.createObjectNode();
        if (step2 != null && step2.get("facts_json") != null) {
            try {
                JsonNode existing = objectMapper.readTree(String.valueOf(step2.get("facts_json")));
                existing.fields().forEachRemaining(entry -> facts.set(entry.getKey(), entry.getValue()));
            } catch (Exception ignored) {
                // 原文不合法时以本次事实为准，不让脏 facts 卡住流程
            }
        }
        facts.put("insertedBattery", code == null ? "" : code);
        repo.setStepFacts(order.id(), StepCode.WAIT_INSERT.order(), facts.toString(), now);
        advanceStep(order.id(), StepCode.WAIT_INSERT.order(), StepState.PENDING.name(),
                StepState.OPEN_CONFIRMED.name(), null, null, null);
        fireOrder(order.id(), OrderState.RETURNING, OrderEvent.EVT_BATTERY_DETECTED_RETURN, "battery_detected@return",
                slotNo, null, null);
    }

    private void onDoorClose(SwapOrderRepository.OrderRow order, Integer slotNo, JsonNode data,
                             LocalDateTime now) {
        if (order.state().equals(OrderState.RETURNING.name()) && eq(slotNo, order.returnSlotNo())) {
            Map<String, Object> step2 = repo.step(order.id(), StepCode.WAIT_INSERT.order());
            if (step2 == null || !"OPEN_CONFIRMED".equals(step2.get("step_state"))) {
                // 门关了但没有"电池已投入"的事实：不推进。这一步推进就等于承认归还完成，
                // 而真实世界存在"开了又关、什么都没投"的情况（FI-11 的邻居）
                log.info("归还仓关门但无投入事实，保持等待：order={}", order.orderNo());
                return;
            }
            String code = insertedBattery(step2);
            // 门关了就把台账投影改掉：它是后续反查的第一个来源，不改就永远是旧值
            repo.setSlotDoor(order.cabinetId(), slotNo, "CLOSED", "LOCKED", now);
            advanceStep(order.id(), StepCode.WAIT_INSERT.order(), StepState.OPEN_CONFIRMED.name(),
                    StepState.PHYSICS_DONE.name(), null, null, null);
            fireOrder(order.id(), OrderState.RETURNING, OrderEvent.EVT_DOOR_CLOSE_RETURN, "door_close@return",
                    slotNo, null, null);
            Long returnBattery = code == null ? null : batteryIdOf(code);
            repo.bindSlotsToOrder(order.id(), returnBattery, order.offerBatteryId());
            verifyAndOffer(order, returnBattery, now);
        } else if (order.state().equals(OrderState.OFFERING.name()) && eq(slotNo, order.offerSlotNo())) {
            Map<String, Object> step5 = repo.step(order.id(), StepCode.WAIT_TAKE.order());
            if (step5 == null || !"OPEN_CONFIRMED".equals(step5.get("step_state"))) {
                return;
            }
            advanceStep(order.id(), StepCode.WAIT_TAKE.order(), StepState.OPEN_CONFIRMED.name(),
                    StepState.PHYSICS_DONE.name(), null, null, null);
            repo.setSlotDoor(order.cabinetId(), slotNo, "CLOSED", "LOCKED", now);
            fireOrder(order.id(), OrderState.OFFERING, OrderEvent.EVT_DOOR_CLOSE_OFFER, "door_close@offer",
                    slotNo, null, null);
            settle(order, now);
        }
    }

    private void onBatteryTaken(SwapOrderRepository.OrderRow order, Integer slotNo, JsonNode data,
                                 LocalDateTime now) {
        if (!order.state().equals(OrderState.OFFERING.name()) || !eq(slotNo, order.offerSlotNo())) {
            return;
        }
        String code = data.path("batteryCode").asText(null);
        Map<String, Object> expected = order.offerBatteryId() == null ? null : repo.batteryById(order.offerBatteryId());
        if (expected != null && code != null && !code.equals(String.valueOf(expected.get("battery_code")))) {
            // 柜里说取走的是 A，云端分配的是 B：这是归属可疑的强信号，必须记差异而不是"以柜侧为准"
            repo.insertDiscrepancy(IdWorker.getId(), "TAKEN:" + order.id(), "IDENTITY_SUSPECT", order.id(),
                    order.cabinetId(), order.offerBatteryId(), order.userId(),
                    expected.get("battery_code").toString(), code, "取走电池与分配电池不一致", order.tenantId());
            registry.counter("swap.identity.suspect").increment();
            return;
        }
        Map<String, Object> step5 = repo.step(order.id(), StepCode.WAIT_TAKE.order());
        ObjectNode facts = objectMapper.createObjectNode().put("takenBattery", code == null ? "" : code);
        if (step5 != null && step5.get("facts_json") != null) {
            facts.put("previousFacts", String.valueOf(step5.get("facts_json")));
        }
        repo.setStepFacts(order.id(), StepCode.WAIT_TAKE.order(), facts.toString(), now);
        advanceStep(order.id(), StepCode.WAIT_TAKE.order(), StepState.PENDING.name(), StepState.OPEN_CONFIRMED.name(),
                null, null, null);
        fireOrder(order.id(), OrderState.OFFERING, OrderEvent.EVT_BATTERY_TAKEN_OFFER, "battery_taken@offer",
                slotNo, order.offerBatteryId(), null);
    }

    /** 门关之后：S3 核验 → 通过则下发 S4 开取电仓。 */
    private void verifyAndOffer(SwapOrderRepository.OrderRow order, Long returnBatteryId, LocalDateTime now) {
        fireOrder(order.id(), OrderState.RETURNED, OrderEvent.ENTER_S3, "enter_S3", null, null, null);
        advanceStep(order.id(), StepCode.VERIFY_RETURN.order(), StepState.PENDING.name(), StepState.DISPATCHED.name(),
                null, null, null);
        Map<String, Object> battery = returnBatteryId == null ? null : repo.batteryById(returnBatteryId);
        if (battery == null) {
            // 认不出归还电池：不能进取电阶段（否则下一步就是"拿一块不知道是谁的电池给人"）
            fireOrder(order.id(), OrderState.VERIFYING, OrderEvent.VERIFY_FAIL_REJECT, "verify_fail_unknown_battery",
                    null, returnBatteryId, null);
            abort(order, "RETURN_BATTERY_UNKNOWN", now);
            return;
        }
        Integer soc = battery.get("soc") == null ? null : ((Number) battery.get("soc")).intValue();
        if (soc != null && soc < 5) {
            // 极低 SOC 的电池不能入池充电，走拒收分岔（§5.5 第 32 条）
            fireOrder(order.id(), OrderState.VERIFYING, OrderEvent.VERIFY_FAIL_REJECT, "verify_reject", null,
                    returnBatteryId, null);
            abort(order, "VERIFY_REJECTED", now);
            return;
        }
        advanceStep(order.id(), StepCode.VERIFY_RETURN.order(), StepState.DISPATCHED.name(), StepState.VERIFIED.name(),
                null, null, null);
        fireOrder(order.id(), OrderState.VERIFYING, OrderEvent.VERIFY_OK, "verify_ok", null, returnBatteryId, null);

        Map<String, Object> step4 = repo.step(order.id(), StepCode.UNLOCK_OFFER.order());
        int offerSlotNo = ((Number) step4.get("slot_no")).intValue();
        String cmdId = dispatch(order, StepCode.UNLOCK_OFFER, offerSlotNo, "UNLOCK_SLOT").cmdId();
        advanceStep(order.id(), StepCode.UNLOCK_OFFER.order(), StepState.PENDING.name(), StepState.DISPATCHED.name(),
                cmdId, null, null);
        fireOrder(order.id(), OrderState.OFFERING, OrderEvent.DISPATCH_S4, "DISPATCH_S4", offerSlotNo, null, cmdId);
    }

    /**
     * 结算：归属变更 + 扣减 + 仓位台账，全在一条事务里（I3）。
     * 任一步失败整单回滚 —— 宁可让运维看到"卡在 SETTLING 可续跑"，也不能出现扣了次没给电池。
     */
    private void settle(SwapOrderRepository.OrderRow order, LocalDateTime now) {
        fireOrder(order.id(), OrderState.TAKEN, OrderEvent.ENTER_S6, "enter_S6", null, null, null);
        SwapOrderRepository.AccountRow account = repo.findAccount(order.userId());
        Long returnSlotId = slotId(order.cabinetId(), order.returnSlotNo());
        Long offerSlotId = slotId(order.cabinetId(), order.offerSlotNo());
        Long offerBatteryId = order.offerBatteryId();

        if (account == null || !repo.deductRight(account.id(), order.userId(), order.id(), now, null,
                order.tenantId())) {
            // 扣不动就整体回滚：订单留在 SETTLING（§5.5 第 34 条），由兜底扫描续跑而不是回退到 TAKEN
            throw new IllegalStateException("权益扣减失败，订单保持 SETTLING 可续：" + order.orderNo());
        }
        if (order.returnBatteryId() != null) {
            // 用户不再持有旧电池：先关旧绑定再改归属，顺序反了会同时存在两条 ACTIVE 绑定
            repo.closeActiveBindings(order.userId(), order.returnBatteryId(), "SWAP_RETURNED", now);
        }
        if (returnSlotId != null && order.returnBatteryId() != null) {
            repo.moveBattery(order.returnBatteryId(), "IN_CABINET_CHARGING", order.cabinetId(), returnSlotId, null,
                    "KNOWN", now);
            repo.settleSlot(returnSlotId, order.returnBatteryId(), "IDLE_CHARGING", "CHARGING", now);
        } else if (returnSlotId != null) {
            repo.settleSlot(returnSlotId, null, "IDLE_EMPTY", "IDLE", now);
        }
        if (offerSlotId != null) {
            repo.settleSlot(offerSlotId, null, "IDLE_EMPTY", "IDLE", now);
        }
        if (offerBatteryId != null) {
            repo.moveBattery(offerBatteryId, "HELD_BY_USER", null, null, order.userId(), "KNOWN", now);
            repo.insertBinding(IdWorker.getId(), offerBatteryId, order.userId(), order.id(), "STRONG", now,
                    order.tenantId());
        }
        if (returnSlotId != null) {
            repo.releaseReservationsOfSlot(returnSlotId, order.id(), now);
        }
        if (offerSlotId != null) {
            repo.releaseReservationsOfSlot(offerSlotId, order.id(), now);
        }
        advanceStep(order.id(), StepCode.SETTLE.order(), StepState.PENDING.name(), StepState.PHYSICS_DONE.name(),
                null, null, null);
        repo.updateRightState(order.id(), "DEDUCTED");
        fireOrder(order.id(), OrderState.SETTLING, OrderEvent.SETTLE_DONE, "settle_done", null, offerBatteryId, null);
        log.info("订单已结算：order={}", order.orderNo());
    }

    /**
     * 中止补偿的**最小实现**：释放预占、回滚仓态、退权益，然后 ABORTING→ABORTED。
     *
     * 这里刻意不做完整补偿台账（swap_compensation）：那是 M3 的主体（16 项 FI + 四维断言）。
     * 但"补偿集没做完不许进 ABORTED"（I8）这条从本批就开始遵守：
     * 顺序是"先做完回退、再落 ABORTED"，而不是"先落 ABORTED 再慢慢补"。
     */
    private void abort(SwapOrderRepository.OrderRow order, String reason, LocalDateTime now) {
        compensate(order, reason, now);
        // 补偿先做完再落 ABORTED（I8）：先落终态再慢慢补，会让"已完成"这个字包含不可信
        fireOrder(order.id(), OrderState.ABORTING, OrderEvent.ABORT_DONE, "abort_done", null, null, null);
    }

    private void compensate(SwapOrderRepository.OrderRow order, String reason, LocalDateTime now) {
        repo.releaseReservations(order.id(), reason, now);
        repo.restoreSlotsOf(order.id(), now);
        if ("OCCUPIED".equals(order.rightState())) {
            SwapOrderRepository.AccountRow account = repo.findAccount(order.userId());
            if (account != null && repo.releaseRightOccupation(account.id())) {
                repo.insertRightTransaction(IdWorker.getId(), order.userId(), account.id(), order.id(), "RELEASE", -1,
                        null, now, reason, null, order.tenantId());
            }
            repo.updateRightState(order.id(), "RELEASED");
        }
    }

    /** 柜侧 `swap_result` 与云端事实交叉核对：不一致就记差异，不改云端事实。 */
    private void reconcile(DeviceDirectoryDao.Device device, Envelope envelope) {
        JsonNode data = envelope.data();
        // 先按单号找（包含已完成的单），找不到再退而在途单——
        // 柜侧回结果往往晚于云端结算，只查在途单会把可比的陈述当成无关事件丢掉
        String orderNo = data.path("orderNo").asText(null);
        SwapOrderRepository.OrderRow order = orderNo == null ? null : repo.findByOrderNo(orderNo);
        if (order == null) {
            order = repo.findInfightByDevice(device.id());
        }
        if (order == null) {
            repo.insertDiscrepancy(IdWorker.getId(), "RESULT:" + envelope.msgId(), "UNMATCHED_EVENT", null, null,
                    null, null, null, data.toString(), "swap_result 无归属订单", device.tenantId());
            return;
        }
        String statedBattery = data.path("batteryCode").asText(null);
        Map<String, Object> expected = order.offerBatteryId() == null ? null : repo.batteryById(order.offerBatteryId());
        String expectedCode = expected == null ? null : String.valueOf(expected.get("battery_code"));
        if (statedBattery != null && expectedCode != null && !statedBattery.equals(expectedCode)) {
            repo.insertDiscrepancy(IdWorker.getId(), "SWAPRESULT:" + order.id(), "SWAP_RESULT_MISMATCH",
                    order.id(), order.cabinetId(), order.offerBatteryId(), order.userId(), expectedCode, statedBattery,
                    "柜侧陈述的电池与云端分配不一致", order.tenantId());
            registry.counter("swap.result.mismatch").increment();
        }
    }

    // ---------------- 超时驱动需要的入口（只暴露收敛动作，不暴露内部推进） ----------------

    /** 反查得到的事实驱动迁移：与事件入口同一套合法性判定与 CAS，不开第二份推进逻辑。 */
    @Transactional
    public void fireDeadline(long orderId, OrderState from, OrderEvent event, String eventType) {
        fireOrder(orderId, from, event, eventType, null, null, null);
    }

    @Transactional
    public void advanceReturnDoorOpen(long orderId) {
        SwapOrderRepository.OrderRow order = requireOrder(orderId);
        repo.setSlotDoor(order.cabinetId(), order.returnSlotNo(), "OPEN", "UNLOCKED", LocalDateTime.now());
        advanceStep(orderId, StepCode.OPEN_RETURN.order(), StepState.DISPATCHED.name(),
                StepState.OPEN_CONFIRMED.name(), null, null, null);
    }

    @Transactional
    public void advanceOfferDoorOpen(long orderId) {
        SwapOrderRepository.OrderRow order = requireOrder(orderId);
        repo.setSlotDoor(order.cabinetId(), order.offerSlotNo(), "OPEN", "UNLOCKED", LocalDateTime.now());
        advanceStep(orderId, StepCode.UNLOCK_OFFER.order(), StepState.DISPATCHED.name(),
                StepState.OPEN_CONFIRMED.name(), null, null, null);
    }

    /**
     * 把步骤标成"不可断定"。
     *
     * 不这么处理就只有两个选项：当成成功（可能门根本没开）或当成失败（可能已开门只是没上报）。
     * 两者都比"挂起等人/等反查"贵得多，而且都会谎改事实。
     */
    @Transactional
    public void markConfirmPending(long orderId, int stepNo) {
        LocalDateTime now = LocalDateTime.now();
        if (repo.advanceStep(orderId, stepNo, StepState.DISPATCHED.name(), StepState.CONFIRM_PENDING.name(),
                null, null, null, now)) {
            return;
        }
        repo.advanceStep(orderId, stepNo, StepState.OPEN_CONFIRMED.name(), StepState.CONFIRM_PENDING.name(),
                null, null, null, now);
    }

    /** 重发开归还仓：仅反查确认"没开"时调用（§5.3 #18）。 */
    @Transactional
    public void redispatchReturnSlot(long orderId) {
        SwapOrderRepository.OrderRow order = requireOrder(orderId);
        redispatch(order, StepCode.OPEN_RETURN, order.returnSlotNo(), "OPEN_SLOT");
    }

    /** 重发开取电仓：S4 无副作用（没开就是没开），所以重发安全（§5.4 #27）。 */
    @Transactional
    public void redispatchOfferSlot(long orderId) {
        SwapOrderRepository.OrderRow order = requireOrder(orderId);
        redispatch(order, StepCode.UNLOCK_OFFER, order.offerSlotNo(), "UNLOCK_SLOT");
    }

    private void redispatch(SwapOrderRepository.OrderRow order, StepCode code, Integer slotNo, String cmdCode) {
        if (slotNo == null) {
            return;
        }
        // 重发前必须先把旧的在途指令置 SUPERSEDED：`uk_icmd_active` 保证同一步骤只有一条在途指令，
        // 不先置就会被唯一索引拒掉——本行曾因为这个“重发”实际从没发出去过，异常被上层 catch 吞了。
        commands.supersedeInFlight(BIZ_TYPE, order.id(), code.order());
        String cmdId = dispatch(order, code, slotNo, cmdCode).cmdId();
        advanceStep(order.id(), code.order(), StepState.DISPATCHED.name(), StepState.DISPATCHED.name(),
                cmdId, null, null);
    }

    /** §5.3 #26：自动裁决不可达 → 人工。禁自动资金动作（包括禁自动退权益）。 */
    @Transactional
    public void deadlineUnconfirmed(long orderId) {
        SwapOrderRepository.OrderRow order = requireOrder(orderId);
        fireOrder(orderId, OrderState.valueOf(order.state()), OrderEvent.DEADLINE_UNCONFIRMED,
                "deadline_unconfirmed", null, null, null);
    }

    /** §5.3 #24：挂起到期无人处理 → ABORTING → 补偿 → ABORTED（I8：先补完再落终态）。 */
    @Transactional
    public void suspendTimeout(long orderId) {
        LocalDateTime now = LocalDateTime.now();
        fireOrder(orderId, OrderState.SUSPENDED, OrderEvent.DEADLINE_SUSPENDED, "deadline_suspended",
                null, null, null);
        SwapOrderRepository.OrderRow fresh = requireOrder(orderId);
        compensate(fresh, "SUSPENDED_TIMEOUT", now);
        fireOrder(orderId, OrderState.ABORTING, OrderEvent.ABORT_DONE, "abort_done", null, null, null);
    }

    /**
     * 用户声明“我把门关上了”（§5.3 #22/#23，B2）。
     *
     * 用户声明是最低可信度来源：**它只能触发反查，不能直接把订单推过 RETURNED**。
     * 否则现场会出现“为了拿回押金谎称已关门”的路子，而柜子里其实还开着门、电池躺在里面。
     *
     * 反查不通过、或者已经用过一次自助恢复（self_resume_used）时：锁仓 + 转人工，
     * 订单状态**故意不变**（登记为自迁移）—— 因为“没人确认”这件事本身就是要被看到的。
     */
    @Transactional
    public String declareClosed(long orderId, long userId) {
        SwapOrderRepository.OrderRow order = requireOrder(orderId);
        if (!OrderState.SUSPENDED.name().equals(order.state())) {
            throw new IllegalStateException("只有挂起中的订单能声明恢复，当前：" + order.state());
        }
        if (!java.util.Objects.equals(order.userId(), userId)) {
            throw new IllegalStateException("只能由下单人本人声明恢复");
        }
        LocalDateTime now = LocalDateTime.now();
        String inserted = insertedBattery(repo.step(orderId, StepCode.WAIT_INSERT.order()));
        SwapOrderRepository.DoorProbe probe = repo.probeSlotDoor(order.cabinetId(), order.returnSlotNo());
        boolean verified = probe != null && "CLOSED".equals(probe.doorState()) && inserted != null
                && !repo.selfResumeUsed(orderId);
        if (verified) {
            repo.markSelfResumeUsed(orderId);
            advanceStep(orderId, StepCode.WAIT_INSERT.order(), StepState.OPEN_CONFIRMED.name(),
                    StepState.PHYSICS_DONE.name(), null, null, null);
            fireOrder(orderId, OrderState.SUSPENDED, OrderEvent.USER_DECLARE_CLOSED_VERIFIED,
                    "user_declare_closed_verified", order.returnSlotNo(), null, null);
            Long returnBattery = batteryIdOf(inserted);
            repo.bindSlotsToOrder(orderId, returnBattery, order.offerBatteryId());
            verifyAndOffer(order, returnBattery, now);
            return "RESUMED";
        }
        repo.lockSlotForSafety(order.cabinetId(), order.returnSlotNo(), "USER_DECLARE_UNVERIFIED");
        fireOrder(orderId, OrderState.SUSPENDED, OrderEvent.USER_DECLARE_CLOSED_UNVERIFIED,
                "user_declare_closed_unverified", order.returnSlotNo(), null, null);
        log.info("声明未通过反查，锁仓转人工：order={}, probe={}", order.orderNo(),
                probe == null ? "null" : probe.doorState());
        return "NEED_MANUAL";
    }

    /**
     * 下发指令。副作用指令 retryMax=0（协议 §4.1 铁律一：不自动重试）。
     *
     * 调用方分两种处理：S1（还没动过任何东西）拿不到连接就整体拒绝回滚；
     * S4（旧电池已在柜内）不能因为下发未达就抹销已发生的事实，那交给超时与反查收敛。
     */
    private DeviceCommandService.CommandRecord dispatch(SwapOrderRepository.OrderRow order, StepCode code, int slotNo,
                                                        String cmdCode) {
        SwapOrderRepository.CabinetDeviceRow device = repo.deviceOfCabinet(order.cabinetId());
        ObjectNode data = objectMapper.createObjectNode().put("slotNo", slotNo).put("orderNo", order.orderNo());
        return commands.issue(new DeviceCommandService.Issue(BIZ_TYPE, order.id(), code.order(),
                device.deviceRowId(), device.productKey(), device.deviceId(), cmdCode, data, 1,
                code.deadlineSeconds(), 0, false, null, order.userId(), order.tenantId()));
    }

    private void advanceStep(long orderId, int stepNo, String fromState, String toState, String cmdId,
                             String sessionId, String factsJson) {
        SwapStepFsm.machine().fire(StepState.valueOf(fromState), toStepEvent(fromState, toState), null);
        if (!repo.advanceStep(orderId, stepNo, fromState, toState, cmdId, sessionId, factsJson,
                LocalDateTime.now())) {
            registry.counter("swap.step.cas_missed").increment();
            log.debug("步骤已被推进，忽略本次重复迁移：order={}, step={}", orderId, stepNo);
        }
    }

    private static SwapStepFsm.Event toStepEvent(String fromState, String toState) {
        if (StepState.PENDING.name().equals(fromState) && StepState.OPEN_CONFIRMED.name().equals(toState)) {
            // 无指令步骤的第一个事实（S2/S5），不能走 EVT_DOOR_OPEN
            return SwapStepFsm.Event.EVT_FACT_ARRIVED;
        }
        if (StepState.DISPATCHED.name().equals(fromState) && StepState.DISPATCHED.name().equals(toState)) {
            // 重发：目标态与源态相同，只按目标态映射会错配成 DISPATCHED 事件而被判非法
            return SwapStepFsm.Event.RE_DISPATCHED;
        }
        return switch (toState) {
            case "DISPATCHED" -> SwapStepFsm.Event.DISPATCHED;
            case "OPEN_CONFIRMED" -> SwapStepFsm.Event.EVT_DOOR_OPEN;
            case "PHYSICS_DONE" -> SwapStepFsm.Event.EVT_PHYSICS_DONE;
            case "VERIFIED" -> SwapStepFsm.Event.VERIFIED;
            case "FAILED" -> SwapStepFsm.Event.QUERY_EXHAUSTED_FAIL;
            default -> SwapStepFsm.Event.SUPERSEDED_SKIP;
        };
    }

    private void fireOrder(long orderId, OrderState from, OrderEvent event, String eventType, Integer slotNo,
                           Long batteryId, String msgId) {
        OrderState to = SwapOrderFsm.machine().fire(from, event, null);
        LocalDateTime now = LocalDateTime.now();
        // deadline_ts 与 deadline_at 必须同一口径（毫秒 vs 可读值），不一致会让兜底扫描提前或延后触发
        long deadlineTs = now.plusSeconds(Math.max(1, to.maxDwellSeconds()))
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (!repo.transition(orderId, from.name(), to.name(), now, to.isTerminal() ? 0L : deadlineTs, null,
                event == OrderEvent.GUARD_PASS, event == OrderEvent.EVT_DOOR_CLOSE_RETURN,
                event == OrderEvent.EVT_DOOR_CLOSE_OFFER, event == OrderEvent.SETTLE_DONE, to.isTerminal())) {
            throw new IllegalStateException("订单状态在并发下已被改变，放弃本次迁移：" + orderId);
        }
        repo.appendEvent(IdWorker.getId(), orderId, eventType, from.name(), to.name(), "DEVICE", msgId, null, null,
                slotNo, batteryId, null, now, null, null, tenantOf(orderId));
    }

    private long tenantOf(long orderId) {
        SwapOrderRepository.OrderRow row = repo.findOrder(orderId);
        return row == null ? 1L : row.tenantId();
    }

    private SwapOrderRepository.OrderRow requireOrder(long orderId) {
        SwapOrderRepository.OrderRow order = repo.findOrder(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在：" + orderId);
        }
        return order;
    }

    private static void requireState(SwapOrderRepository.OrderRow order, OrderState expected) {
        if (!expected.name().equals(order.state())) {
            throw new IllegalStateException("订单状态不是 " + expected + "，当前 " + order.state());
        }
    }

    private static boolean eq(Integer left, Integer right) {
        return left != null && left.equals(right);
    }

    private static String insertedBattery(Map<String, Object> step2) {
        Object facts = step2.get("facts_json");
        if (facts == null) {
            return null;
        }
        try {
            String code = new ObjectMapper().readTree(String.valueOf(facts)).path("insertedBattery").asText(null);
            return code == null || code.isEmpty() ? null : code;
        } catch (Exception e) {
            return null;
        }
    }

    private Long batteryIdOf(String code) {
        Map<String, Object> row = repo.batteryByCode(code);
        return row == null ? null : ((Number) row.get("id")).longValue();
    }

    private Long slotId(long cabinetId, Integer slotNo) {
        return slotNo == null ? null : repo.slotRowId(cabinetId, slotNo);
    }
}
