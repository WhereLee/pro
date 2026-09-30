package com.lrs.buddy.biz.swap.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.lrs.buddy.biz.swap.alloc.SlotAllocator;
import com.lrs.buddy.biz.swap.order.OrderEvent;
import com.lrs.buddy.biz.swap.order.OrderState;
import com.lrs.buddy.biz.swap.order.SwapOrderFsm;
import com.lrs.buddy.biz.swap.repo.SwapOrderRepository;
import com.lrs.buddy.biz.swap.order.StepCode;
import com.lrs.buddy.framework.statemachine.StateMachine;
import com.lrs.buddy.framework.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 换电订单建单：guard 链 → 预占 → 步骤生成 → 事件流（§5.1 第 1 条 + §5.2 第 14 条）。
 *
 * 四条不可让一步的实现规则，以及为什么：
 *
 * 1 **能靠 DB 约束的绝不靠"先查后改"**。
 *    一人一单 = `active_user` 生成列唯一索引；抢仓 = `active_slot` 唯一索引；
 *    额度够不够 = 带条件的 UPDATE 影响行数。先查后改在两个并发请求下会双双通过，
 *    结果是同一仓开两次门或同一份权益被扣两次 —— 而这在功能测试里几乎测不出来。
 * 2 **guard 不过也要落一行 REJECTED 订单**。拒绝率是本项目的核心指标之一，
 *    不落库就只能靠日志估算；且 §5.2 要求"零物理动作"这一事实必须可被证明
 *    —— 证明方式是"这单没有 iot_command 记录、没有预占、没有权益流水"。
 * 3 **预占失败必须反向补偿已发生的预占**（权益已占则释放），否则用户被扣一次却什么都没拿到。
 * 4 **迁移合法性只问状态机**。这里不写 if 判"AUTHORIZED 能不能到 RETURNING"，
 *    未登记的组合由 {@link StateMachine} 抛异常 —— 让代码里出现第二份迁移判断，
 *    就等于给"两处不一致"预留了位置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SwapOrderService {

    private final SwapOrderRepository repo;

    /** @param rejectReasons guard 未通过时的逐条原因（含被排除仓位的仓号与原因），不是聚合结论 */
    public record CreateResult(long orderId, String orderNo, OrderState state, Integer returnSlotNo,
                              Integer offerSlotNo, Long offerBatteryId, List<String> rejectReasons) {

        public boolean accepted() {
            return state == OrderState.AUTHORIZED;
        }
    }

    @Transactional
    public CreateResult create(long userId, String cabinetNo, String source, String traceId) {
        LocalDateTime now = LocalDateTime.now();
        long tenantId = TenantContext.getTenantId() == null ? 1L : TenantContext.getTenantId();
        SwapOrderRepository.CabinetRow cabinet = repo.findCabinet(cabinetNo);
        if (cabinet == null) {
            throw new IllegalArgumentException("柜机不存在：" + cabinetNo);
        }

        // ---- 1) 建 CREATED 订单。一人一单在这里由 DB 判定，不是靠 count 查询 ----
        long orderId = IdWorker.getId();
        String orderNo = SwapOrderRepository.newOrderNo(now);
        try {
            repo.insertCreatedOrder(new SwapOrderRepository.CreateOrder(orderId, orderNo, userId, cabinet.siteId(),
                    cabinet.id(), null, null, null, null, null, OrderState.CREATED.name(), "NONE", "STRONG",
                    normalizeSource(source), traceId, now, tenantId,
                    now.plusSeconds(OrderState.CREATED.maxDwellSeconds())
                            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()));
        } catch (DuplicateKeyException e) {
            // active_user 唯一索引命中 = 该用户已有在途单（B3）。不落成第二行 REJECTED：
            // 若允许，用户可以用刷单把拒绝记录写满，拒绝率指标也会失真
            throw new IllegalStateException("你已有一笔换电订单未完成，请先处理：" + userId, e);
        }
        repo.appendEvent(IdWorker.getId(), orderId, "ORDER_CREATED", null, OrderState.CREATED.name(), "USER",
                null, null, null, null, null, "cabinet=" + cabinetNo, now, userId, traceId, tenantId);

        // ---- 2) guard 链：只读检查，逐条收集原因 ----
        List<String> reasons = new ArrayList<>();
        reasons.addAll(preGuard(userId, cabinet));

        SwapOrderRepository.ThresholdRow thresholds = repo.thresholds(cabinet.siteId());
        int minSoc = thresholds.siteMinSocOverride() == null ? thresholds.minSoc() : thresholds.siteMinSocOverride();
        var limits = new SlotAllocator.Thresholds(minSoc,
                thresholds.siteTempOverride() == null ? thresholds.maxAllocTemp() : thresholds.siteTempOverride(),
                repo.freshnessOf(thresholds.telemetryFreshSec()), now);
        List<SlotAllocator.Candidate> candidates = repo.candidates(cabinet.id());
        SlotAllocator.Decision offer = SlotAllocator.pickOffer(candidates, limits, repo.lastAllocationOf(userId));
        SlotAllocator.Decision back = SlotAllocator.pickReturn(candidates, now);
        if (!offer.available()) {
            reasons.add("NO_OFFER_SLOT " + offer.reasonSummary());
        }
        if (!back.available()) {
            reasons.add("NO_RETURN_SLOT " + back.reasonSummary());
        }
        SwapOrderRepository.AccountRow account = repo.findAccount(userId);
        if (account == null) {
            reasons.add("NO_RIGHT_ACCOUNT");
        }

        if (!reasons.isEmpty()) {
            return reject(orderId, orderNo, userId, reasons, now, traceId);
        }

        // ---- 3) 预占权益（条件全在 WHERE 里，影响行数 0 就是不够）----
        if (!repo.occupyRight(account.id())) {
            return reject(orderId, orderNo, userId, List.of("RIGHTS_INSUFFICIENT_OR_EXPIRED"), now, traceId);
        }
        repo.insertRightTransaction(IdWorker.getId(), userId, account.id(), orderId, "OCCUPY", 1,
                account.timesTotal() - account.timesUsed() - account.timesOccupied() - 1, now, "ORDER_CREATE",
                traceId, tenantId);
        repo.updateRightState(orderId, "OCCUPIED");

        // ---- 4) 抢两个仓位。任一失败就回退权益，不留半占状态 ----
        SlotAllocator.Candidate offerSlot = offer.chosenOrThrow();
        SlotAllocator.Candidate returnSlot = back.chosenOrThrow();
        // 预占表存的是行 id（swap_slot.id），而不是仓号：仓号只在柜机内唯一，
        // 跨柜冲突会让预占错到别人的仓上
        long returnSlotRowId = requireSlotId(cabinet.id(), returnSlot.slotNo());
        long offerSlotRowId = requireSlotId(cabinet.id(), offerSlot.slotNo());
        Long returnBatteryId = repo.batteryRowId(returnSlot.batteryCode());
        Long offerBatteryId = repo.batteryRowId(offerSlot.batteryCode());
        boolean reserved = repo.reserveSlot(IdWorker.getId(), returnSlotRowId, orderId, "RETURN", now, tenantId)
                && repo.reserveSlot(IdWorker.getId(), offerSlotRowId, orderId, "OFFER", now, tenantId);
        if (!reserved) {
            // 抢输了一个仓：不降级成"用默认仓"，也不静默重试，而是走同一个拒绝入口
            // （拒绝里的补偿会把已抢到的那个仓与已预占的权益一并回退）
            return reject(orderId, orderNo, userId, List.of("SLOT_RACE_LOST"), now, traceId);
        }
        repo.markSlotsReserved(returnSlotRowId, offerSlotRowId, orderId, now);

        // ---- 5) 订单推进到 AUTHORIZED（迁移合法性交给状态机）+ 生成 S1..S6 ----
        long deadlineTs = now.plusSeconds(OrderState.AUTHORIZED.maxDwellSeconds())
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (!repo.transition(orderId, OrderState.CREATED.name(), OrderState.AUTHORIZED.name(), now, deadlineTs,
                null, true, false, false, false, false)) {
            throw new IllegalStateException("订单状态在并发下已被改变：" + orderNo);
        }
        repo.appendEvent(IdWorker.getId(), orderId, OrderEvent.GUARD_PASS.name(), OrderState.CREATED.name(),
                OrderState.AUTHORIZED.name(), "USER", null, null, null, returnSlot.slotNo(), null,
                "offerSlot=" + offerSlot.slotNo() + ",returnBattery=" + offerSlot.batteryCode(), now, userId, traceId,
                tenantId);
        createSteps(orderId, returnSlot.slotNo(), offerSlot.slotNo(), returnBatteryId, offerBatteryId, now);

        log.info("订单已建：orderNo={}, cabinet={}, returnSlot={}, offerSlot={}", orderNo, cabinetNo,
                returnSlot.slotNo(), offerSlot.slotNo());
        return new CreateResult(orderId, orderNo, OrderState.AUTHORIZED, returnSlot.slotNo(), offerSlot.slotNo(),
                offerBatteryId, List.of());
    }

    /**
     * §5.2 第 14 条：零物理动作的拒绝。落 REJECTED 行 + 记原因，不开工单、不扣权益。
     * 补偿集中在本方法里做（而不是散在各个失败分支）：
     * 分支各自回退迟早会漏一条（"抢仓失败回退了权益但没释放已拿到的另一个仓"就是典型），
     * 改成按当前事实状态回退，才是可推理的写法。
     */
    private CreateResult reject(long orderId, String orderNo, long userId, List<String> reasons,
                                LocalDateTime now, String traceId) {
        String joined = String.join(";", reasons);
        SwapOrderRepository.OrderRow order = repo.findOrder(orderId);
        long tenantId = order == null ? 1L : order.tenantId();
        if (order != null && "OCCUPIED".equals(order.rightState())) {
            SwapOrderRepository.AccountRow account = repo.findAccount(userId);
            if (account != null && repo.releaseRightOccupation(account.id())) {
                repo.insertRightTransaction(IdWorker.getId(), userId, account.id(), orderId, "RELEASE", -1,
                        null, now, "ORDER_REJECT", traceId, order.tenantId());
            }
            repo.updateRightState(orderId, "RELEASED");
        }
        repo.releaseReservations(orderId, "GUARD_FAIL", now);
        repo.restoreSlotsOf(orderId, now);
        if (!repo.transition(orderId, OrderState.CREATED.name(), OrderState.REJECTED.name(), now, 0L,
                joined.substring(0, Math.min(48, joined.length())), false, false, false, false, true)) {
            log.warn("拒绝落终时状态已变，放弃覆盖：orderNo={}", orderNo);
        }
        repo.appendEvent(IdWorker.getId(), orderId, OrderEvent.GUARD_FAIL.name(), OrderState.CREATED.name(),
                OrderState.REJECTED.name(), "USER", null, null, null, null, null, joined, now, userId, traceId,
                tenantId);
        return new CreateResult(orderId, orderNo, OrderState.REJECTED, null, null, null, List.copyOf(reasons));
    }

    private List<String> preGuard(long userId, SwapOrderRepository.CabinetRow cabinet) {
        List<String> reasons = new ArrayList<>();
        SwapOrderRepository.MemberRow member = repo.findMember(userId);
        if (member == null) {
            reasons.add("MEMBER_NOT_FOUND");
        } else if (!"NORMAL".equals(member.state())) {
            reasons.add("MEMBER_" + member.state());
        } else if (member.riskFlag() != null && member.riskFlag() == 1) {
            reasons.add("MEMBER_RISK_FLAG");
        }
        if (!"NORMAL".equals(cabinet.cabinetState())) {
            reasons.add("CABINET_" + cabinet.cabinetState());
        }
        String online = repo.deviceOnlineState(cabinet.deviceRowId());
        if (online == null) {
            reasons.add("DEVICE_NOT_REGISTERED_OR_DISABLED");
        } else if (!"ONLINE".equals(online)) {
            // 在线判定不信 LWT（协议 §2.1），这里读的是接入层按报文静默轮数算出的权威态
            reasons.add("DEVICE_" + online);
        }
        return reasons;
    }

    private void createSteps(long orderId, int returnSlotNo, int offerSlotNo, Long returnBatteryId,
                            Long offerBatteryId, LocalDateTime now) {
        long tenantId = TenantContext.getTenantId() == null ? 1L : TenantContext.getTenantId();
        for (StepCode code : StepCode.values()) {
            Integer slotNo = switch (code) {
                case OPEN_RETURN, WAIT_INSERT, VERIFY_RETURN -> returnSlotNo;
                case UNLOCK_OFFER, WAIT_TAKE -> offerSlotNo;
                case SETTLE -> null;
            };
            Long batteryId = switch (code) {
                case OPEN_RETURN, WAIT_INSERT, VERIFY_RETURN -> returnBatteryId;
                case UNLOCK_OFFER, WAIT_TAKE -> offerBatteryId;
                case SETTLE -> null;
            };
            repo.insertStep(IdWorker.getId(), orderId, code.order(), code.name(), code.expectCmd(),
                    code.expectEvent(), slotNo, batteryId, now, code.deadlineSeconds(), tenantId);
        }
    }

    private long requireSlotId(long cabinetId, int slotNo) {
        Long id = repo.slotRowId(cabinetId, slotNo);
        if (id == null) {
            throw new IllegalStateException("仓位不存在：cabinetId=" + cabinetId + ", slotNo=" + slotNo);
        }
        return id;
    }

    private static String normalizeSource(String source) {
        if (source == null) {
            return "H5";
        }
        String upper = source.toUpperCase(java.util.Locale.ROOT);
        return switch (upper) {
            case "H5", "ADMIN", "OPS", "SIM" -> upper;
            default -> "H5";
        };
    }

    static StateMachine<OrderState, OrderEvent> fsm() {
        return SwapOrderFsm.machine();
    }
}
