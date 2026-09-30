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
import java.util.List;
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

    /** I8 只卡“阻塞类补偿”；旁路项（工单/告警）失败不得把订单锁在 ABORTING。 */
    private static final java.util.List<String> BLOCKING_COMPENSATIONS =
            java.util.Arrays.stream(com.lrs.buddy.biz.swap.compensation.CompensationAction.values())
                    .filter(com.lrs.buddy.biz.swap.compensation.CompensationAction::blocking)
                    .map(Enum::name).toList();
    private final DeviceCommandService commands;
    private final ObjectMapper objectMapper;
    private final MeterRegistry registry;
    private final OrderEventLocks locks;
    private final org.springframework.transaction.support.TransactionTemplate txTemplate;

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

    /**
     * 事件入口（由 SwapEventListener 在校验链通过后调用）。
     *
     * 两件事必须按这个顺序：**先定位订单 → 拿订单分段锁 → 再开事务**。
     * 为什么不直接并发消费：柜机常在 1ms 内连发 battery_detected 与 door_close，
     * 接入层是多线程的，后一条会在前一条提交前读到“还没投入事实”然后直接 return，
     * 这一单就永久停在 RETURNING（跨进程联跑实测到）。为什么锁不能在事务里拿：
     * 锁会先于提交释放，与建单那侧同一个层次约定。
     */
    public void onEvent(DeviceDirectoryDao.Device device, Envelope envelope) {
        JsonNode data = envelope.data();
        String eventType = data == null ? null : data.path("eventType").asText(null);
        if (eventType == null) {
            registry.counter("swap.event.no_type").increment();
            return;
        }
        if ("swap_result".equals(eventType)) {
            txTemplate.executeWithoutResult(status -> reconcile(device, envelope));
            return;
        }
        SwapOrderRepository.OrderRow found = repo.findInfightByDevice(device.id());
        if (found == null) {
            // 归属不到订单的事实必须留痕，不能丢
            registry.counter("swap.event.unmatched").increment();
            txTemplate.executeWithoutResult(status -> repo.insertDiscrepancy(IdWorker.getId(),
                    "UNMATCHED:" + envelope.msgId(), "UNMATCHED_EVENT", null, null, null, null, null,
                    data.toString(), "设备上报 " + eventType + " 但无在途订单", device.tenantId()));
            return;
        }
        long orderId = found.id();
        locks.underLock(orderId, () -> {
            txTemplate.executeWithoutResult(status -> consume(device, envelope, orderId));
            return null;
        });
    }

    /** 在订单锁 + 事务内消费一条事件（每一步都重新读订单，不拿旧快照去判分支）。 */
    private void consume(DeviceDirectoryDao.Device device, Envelope envelope, long orderId) {
        JsonNode data = envelope.data();
        String eventType = data.path("eventType").asText(null);
        Integer slotNo = data.hasNonNull("slotNo") ? data.get("slotNo").asInt() : null;
        LocalDateTime now = LocalDateTime.now();
        SwapOrderRepository.OrderRow order = repo.findOrder(orderId);
        if (order == null) {
            return;
        }
        if (!repo.insertEventDedup(IdWorker.getId(), order.id(), eventType, envelope.msgId(), slotNo, now,
                order.tenantId())) {
            registry.counter("swap.event.duplicate").increment();
            log.info("重复事件已丢弃：order={}, type={}, msgId={}", order.orderNo(), eventType, envelope.msgId());
            return;
        }
        // 跨进程联跑排障入口：没有这行就分不清“事件没到”与“到了但匹配不上”
        log.info("换电事件到达：order={}, type={}, slot={}, step1={}, step2={}",
                order.orderNo(), eventType, slotNo,
                stateOfStep(order.id(), StepCode.OPEN_RETURN.order()), stateOfStep(order.id(), StepCode.WAIT_INSERT.order()));
        switch (eventType) {
            case "door_open" -> onDoorOpen(order, slotNo, envelope, now);
            case "battery_detected" -> onBatteryDetected(order, slotNo, data, now);
            case "door_close" -> onDoorClose(order, slotNo, data, now);
            case "battery_taken" -> onBatteryTaken(order, slotNo, data, now);
            default -> registry.counter("swap.event.ignored", "type", eventType).increment();
        }
    }

    private String stateOfStep(long orderId, int stepNo) {
        Map<String, Object> step = repo.step(orderId, stepNo);
        return step == null ? "NULL" : String.valueOf(step.get("step_state"));
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
        } else if ((order.state().equals(OrderState.OFFERING.name())
                || order.state().equals(OrderState.TAKEN.name())) && eq(slotNo, order.offerSlotNo())) {
            Map<String, Object> step5 = repo.step(order.id(), StepCode.WAIT_TAKE.order());
            if (step5 == null || !"OPEN_CONFIRMED".equals(String.valueOf(step5.get("step_state")))) {
                return;
            }
            // 柜机常在 1ms 内连发 battery_taken + door_close：take 已把订单推进到 TAKEN，
            // 后到的关门事实不能再走状态机（TAKEN 上没有 door_close 的入边），
            // 但它仍是必须留档的事实——不补这一支，“取完电池就关门”的单会永久停在 TAKEN。
            boolean alreadyTaken = OrderState.TAKEN.name().equals(order.state());
            advanceStep(order.id(), StepCode.WAIT_TAKE.order(), StepState.OPEN_CONFIRMED.name(),
                    StepState.PHYSICS_DONE.name(), null, null, null);
            repo.setSlotDoor(order.cabinetId(), slotNo, "CLOSED", "LOCKED", now);
            if (alreadyTaken) {
                registry.counter("swap.event.late_close").increment();
                repo.appendEvent(IdWorker.getId(), order.id(), "door_close@offer_late",
                        OrderState.TAKEN.name(), OrderState.TAKEN.name(), "DEVICE", null, null, null,
                        slotNo, order.offerBatteryId(), null, now, null, null, order.tenantId());
            } else {
                fireOrder(order.id(), OrderState.OFFERING, OrderEvent.EVT_DOOR_CLOSE_OFFER, "door_close@offer",
                        slotNo, null, null);
            }
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

    /**
     * 拒收双分岔 R-A / R-B（§5.5 第 32 条）。
     *
     * <p>判定依据是步骤上的事实（关门且确实有投入 = 电池已在仓内），而不是笼统的"拒收"：
     * <ul>
     *   <li><b>R-A（电池已在仓内）</b>：锁仓 + 电池转待取回（那是用户的财产，不能进池被下一个人取走），
     *       两条都写进补偿台账，因此即使本次事务回滚，下一轮执行器仍会把它们做完。</li>
     *   <li><b>R-B（电池未入仓 / 门还开着）</b>：重开归还仓让用户取回，再回滚预占与权益。
     *       不锁仓（仓里本来就没东西），也不转待取回（电池还在用户手上）。</li>
     * </ul>
     */
    void rejectIntake(SwapOrderRepository.OrderRow order, LocalDateTime now, String reason) {
        fireOrder(order.id(), OrderState.VERIFYING, OrderEvent.VERIFY_FAIL_REJECT, "verify_reject_" + reason,
                null, order.returnBatteryId(), null);
        Map<String, Object> step2 = repo.step(order.id(), StepCode.WAIT_INSERT.order());
        boolean batteryInside = step2 != null
                && StepState.PHYSICS_DONE.name().equals(String.valueOf(step2.get("step_state")));
        if (batteryInside) {
            Long slotId = repo.slotRowId(order.cabinetId(), order.returnSlotNo());
            repo.insertCompensation(IdWorker.getId(), order.id(), "LOCK_SLOT", "SLOT", slotId, "PENDING", now,
                    order.tenantId());
            if (order.returnBatteryId() != null) {
                repo.insertCompensation(IdWorker.getId(), order.id(), "BATTERY_PENDING_PICKUP", "BATTERY",
                        order.returnBatteryId(), "PENDING", now, order.tenantId());
            }
            repo.insertDiscrepancy(IdWorker.getId(), "VERIFY:" + order.id(), "IDENTITY_SUSPECT", order.id(),
                    order.cabinetId(), order.returnBatteryId(), order.userId(), null,
                    "{\"return_slot\":" + order.returnSlotNo() + "}", reason + "：电池已入仓，锁仓并转待取回",
                    order.tenantId());
        } else {
            // 重开归还仓：不重开就等于把用户拒在"电池取不回来"的状态里
            try {
                redispatchReturnSlot(order.id());
            } catch (RuntimeException e) {
                log.warn("R-B 重开归还仓失败（转人工）：order={}, err={}", order.orderNo(), e.getMessage());
                repo.insertDiscrepancy(IdWorker.getId(), "REOPEN:" + order.id(), "FACT_MISSING", order.id(),
                        order.cabinetId(), order.returnBatteryId(), order.userId(), null, null,
                        reason + "：重开归还仓失败，需人工开门取回", order.tenantId());
            }
        }
        // 资金与预占是同步回退（同事务），物理动作（锁仓/待取回）挂补偿台账。
        compensate(order, reason, now);
        // 不在此处强推 ABORTED：阻塞补偿还没做完就落终态，I8 就成了空话。
        // 没有阻塞项时立即收尾；有则交给补偿执行器做完后收尾。
        if (repo.countOpenCompensation(order.id(), BLOCKING_COMPENSATIONS) == 0) {
            fireOrder(order.id(), OrderState.ABORTING, OrderEvent.ABORT_DONE, "abort_done", null, null, null);
        } else {
            registry.counter("swap.abort.waiting_compensation").increment();
            log.info("拒收后等待阻塞补偿完成再落终态：order={}, reason={}", order.orderNo(), reason);
        }
    }

    /** 门关之后：S3 核验 → 通过则下发 S4 开取电仓。 */
    private void verifyAndOffer(SwapOrderRepository.OrderRow order, Long returnBatteryId, LocalDateTime now) {
        fireOrder(order.id(), OrderState.RETURNED, OrderEvent.ENTER_S3, "enter_S3", null, null, null);
        advanceStep(order.id(), StepCode.VERIFY_RETURN.order(), StepState.PENDING.name(), StepState.DISPATCHED.name(),
                null, null, null);
        Map<String, Object> battery = returnBatteryId == null ? null : repo.batteryById(returnBatteryId);
        if (battery == null) {
            // 认不出归还电池：不能进取电阶段（否则下一步就是"拿一块不知道是谁的电池给人"）。
            // 分叉依据是现场事实而不是笼统的"拒收"：电池已入仓与还在门口，处置完全相反。
            rejectIntake(order, now, "RETURN_BATTERY_UNKNOWN");
            return;
        }
        Integer soc = battery.get("soc") == null ? null : ((Number) battery.get("soc")).intValue();
        if (soc != null && soc < 5) {
            // 极低 SOC 的电池不能入池充电，走拒收分岔（§5.5 第 32 条）
            rejectIntake(order, now, "VERIFY_REJECTED_LOW_SOC");
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
        // I8 的可执行形式：台账里还有 PENDING/FAILED 项就不让进 ABORTED。
        // 不这样做的话，“已完成补偿”这个字永远包不住信任，M3 的异步补偿一旦排队未执行就丢资产。
        int open = repo.countOpenCompensation(order.id(), BLOCKING_COMPENSATIONS);
        if (open > 0) {
            throw new IllegalStateException("I8：仍有 " + open + " 项阻塞补偿未完成，禁止进入 ABORTED");
        }
        // 补偿先做完再落 ABORTED（I8）：先落终态再慢慢补，会让"已完成"这个字包含不可信
        fireOrder(order.id(), OrderState.ABORTING, OrderEvent.ABORT_DONE, "abort_done", null, null, null);
    }

    private void compensate(SwapOrderRepository.OrderRow order, String reason, LocalDateTime now) {
        // 每一项补偿都落台账：没落笔的补偿等于没发生过的补偿，事后无法审计也无法重试
        List<Map<String, Object>> reservations = repo.reservations(order.id());
        int released = repo.releaseReservations(order.id(), reason, now);
        if (released > 0) {
            reservations.stream()
                    .filter(row -> "ACTIVE".equals(String.valueOf(row.get("resv_state"))))
                    .forEach(row -> repo.insertCompensation(IdWorker.getId(), order.id(), "RELEASE_RESERVATION",
                            "SLOT", ((Number) row.get("slot_id")).longValue(), "DONE", now, order.tenantId()));
        }
        repo.restoreSlotsOf(order.id(), now);
        if ("OCCUPIED".equals(order.rightState())) {
            SwapOrderRepository.AccountRow account = repo.findAccount(order.userId());
            if (account != null && repo.releaseRightOccupation(account.id())) {
                repo.insertRightTransaction(IdWorker.getId(), order.userId(), account.id(), order.id(), "RELEASE", -1,
                        null, now, reason, null, order.tenantId());
                repo.insertCompensation(IdWorker.getId(), order.id(), "RELEASE_RIGHT", "RIGHT", account.id(),
                        "DONE", now, order.tenantId());
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
        // 走 abort() 而不是自己拼 compensate + ABORT_DONE：I8 的 guard 只有一处实现，
        // 两处各写一遍就必然出现“一条路径有 guard、另一条没有”的不一致。
        abort(requireOrder(orderId), "SUSPENDED_TIMEOUT", now);
    }

    /**
     * 补偿执行器把最后一项做完后落终态（仍过 I8 guard）。
     *
     * 为什么要有这个入口：异步补偿的完成时刻在另一个线程/进程里，那一侧需要一个
     * “从 ABORTING 推到 ABORTED”的合法入口；没有它，将来只能再写一份 compensate+迁移，
     * 而两份实现必然出现“一份有 guard、一份没有”。
     */
    @Transactional
    public void finishAborting(long orderId) {
        SwapOrderRepository.OrderRow order = requireOrder(orderId);
        if (!OrderState.ABORTING.name().equals(order.state())) {
            throw new IllegalStateException("只有在 ABORTING 的单能补偿收尾，当前：" + order.state());
        }
        abort(order, "COMPENSATION_DONE", LocalDateTime.now());
    }

    /**
     * 安全联动触发的自动中止（不同于人工中止）。
     *
     * 两条路径的差别必须在状态机上可见：
     * {@code ALARM_SAFETY_LOCK} 是系统因安全事件自己把单送进 ABORTING（高温/烟感/紧急停充），
     * 而 {@code ADMIN_ABORT} 是人判断后的“待核资”。前者不经过双人复核，所以只能进
     * 要补偿的 ABORTING，绝不可直接落资金终态。
     */
    @Transactional
    public void safetyAbort(long orderId, String reason) {
        SwapOrderRepository.OrderRow order = requireOrder(orderId);
        OrderState from = OrderState.valueOf(order.state());
        if (from == OrderState.ABORTING || from.isTerminal()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        fireOrder(orderId, from, OrderEvent.ALARM_SAFETY_LOCK, "alarm_safety_lock", null, null, null);
        compensate(requireOrder(orderId), "SAFETY:" + reason, now);
        if (repo.countOpenCompensation(orderId, BLOCKING_COMPENSATIONS) == 0) {
            fireOrder(orderId, OrderState.ABORTING, OrderEvent.ABORT_DONE, "abort_done", null, null, null);
        } else {
            registry.counter("swap.abort.waiting_compensation").increment();
        }
    }

    // ---------------- 人工干预（双人复核通过后才调用）----------------

    /**
     * 人工判中止：落 {@code FAILED_MANUAL}（而不是直接 ABORTED）。
     *
     * 这不是多做一步：人工按下“中止”时，系统其实不知道到底是“没换成”还是“电池已被取走”，
     * 而这两个结论的资金与资产处置完全相反。所以 ADMIN_ABORT 只负责**冻结现场**（预占与权益保持原状），
     * 真正的方向由后续 {@code ADMIN_RESOLVE_COMPLETED} / {@code ADMIN_RESOLVE_ABORTED} 在复核后定。
     */
    @Transactional
    public void adminAbort(long orderId, String reason) {
        SwapOrderRepository.OrderRow order = requireOrder(orderId);
        fireOrder(orderId, OrderState.valueOf(order.state()), OrderEvent.ADMIN_ABORT, "admin_abort",
                null, null, null);
        log.info("人工判中止，进入待核资：order={}, reason={}", order.orderNo(), reason);
    }

    /**
     * 人工判定“换电已完成”（仅用于 FAILED_MANUAL 这种自动裁决不可达的单）。
     *
     * 做资金侧收尾（预占→实扣）+ 释放预占台账，同时候一条差异台账：
     * **新电池的资产归属不由这里改**——设备没报过“已被取走”这个事实，人工判完成
     * 不能伪造一个事实；归属需另走“归属核销”（swap:battery:reconcile，带自己审计）。
     * 这不是少做，而是把“人说了算”与“设备报过”两类事实分开存，否则事后无法归因。
     */
    @Transactional
    public void adminResolveCompleted(long orderId, String reason) {
        SwapOrderRepository.OrderRow order = requireOrder(orderId);
        LocalDateTime now = LocalDateTime.now();
        fireOrder(orderId, OrderState.valueOf(order.state()), OrderEvent.ADMIN_RESOLVE_COMPLETED,
                "admin_resolve_completed", null, null, null);
        SwapOrderRepository.OrderRow fresh = requireOrder(orderId);
        if ("OCCUPIED".equals(fresh.rightState())) {
            // I3：扣减与“人工判定完成”必须在同一个事务里要么都成、要么都不成。
            // 扣不动时绝不不得默默放过去：否则订单进了 COMPLETED 而额度仍是预占，
            // 而本方法已在上面把状态推到 COMPLETED——不抛就会留下一笔“没收到钱但已完成”的单。
            SwapOrderRepository.AccountRow account = repo.findAccount(fresh.userId());
            if (account == null || !repo.deductRight(account.id(), fresh.userId(), orderId, now, null,
                    fresh.tenantId())) {
                throw new IllegalStateException("人工判定完成但权益无法实扣（账户不存在或无预占可扣），已回滚");
            }
            repo.updateRightState(orderId, "DEDUCTED");
        }
        repo.releaseReservations(orderId, "ADMIN_RESOLVE_COMPLETED", now);
        repo.insertDiscrepancy(IdWorker.getId(), "ADMIN-COMP-" + orderId, "FACT_MISSING",
                orderId, fresh.cabinetId(), fresh.offerBatteryId(), fresh.userId(), null,
                "{\"operator_decision\":\"COMPLETED\"}", "人工落终且无设备事实支撑：" + reason, fresh.tenantId());
    }

    /** 人工判定“本次未发生换电”：先补偿（退预占/退权益/仓态回落），再落 ABORTED（I8）。 */
    @Transactional
    public void adminResolveAborted(long orderId, String reason) {
        SwapOrderRepository.OrderRow order = requireOrder(orderId);
        LocalDateTime now = LocalDateTime.now();
        // 传给补偿的是短码（台账原因字段只有 32 宽），人工长篇理由留在干预表与事件流里
        compensate(order, "ADMIN_RESOLVE_ABORTED", now);
        fireOrder(orderId, OrderState.valueOf(requireOrder(orderId).state()), OrderEvent.ADMIN_RESOLVE_ABORTED,
                "admin_resolve_aborted", null, null, null);
        log.info("人工判定未发生换电，已回滚并落 ABORTED：order={}, reason={}", order.orderNo(), reason);
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
