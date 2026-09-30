package com.lrs.buddy.biz.swap.service;

import com.lrs.buddy.biz.swap.display.DisplayState;
import com.lrs.buddy.biz.swap.flow.SwapFlowService;
import com.lrs.buddy.biz.swap.order.OrderState;
import com.lrs.buddy.biz.swap.order.StepCode;
import com.lrs.buddy.biz.swap.order.StepState;
import com.lrs.buddy.biz.swap.repo.SwapOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * C 端换电视图服务（找柜 → 建单 → 进度 → 结果）。
 *
 * 这个类的职责只有一件事：**把领域状态翻译成用户能行动的语言**，并且把"用户能点什么"
 * 判定清楚。它不改状态（改状态的入口全在 {@code SwapOrderService}/{@code SwapFlowService}），
 * 所以读接口可以被前端高频轮询而不会引入并发副作用。
 *
 * 两条刻意的约束：
 * 1 展示态一律走 {@link DisplayState}，前端不参与判断（未知态显示成失败就是从这里防住的）；
 * 2 所有单号入口都做**归属校验**，不是靠"会员看不到别人的单"这种隐含假设。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberSwapService {

    private final SwapOrderRepository repo;
    private final SwapOrderService orders;
    private final SwapFlowService flow;

    /** 柜机概况（找柜页与建单前的确认页共用）。 */
    public record CabinetView(String cabinetNo, Long siteId, Integer slotCount, String cabinetState,
                              String onlineState, int freeReturnSlots, int readyOfferSlots, boolean orderable) {
    }

    /** 用户可见的一步。 */
    public record StepView(int stepNo, String stepCode, String stepState, String label, boolean done, boolean active) {
    }

    /** 进度视图：展示态 + 可执行动作 + 步骤。 */
    public record ProgressView(String orderNo, String orderState, String displayState, String label, String tone,
                               String hint, boolean terminal, boolean canStart, boolean canDeclareClosed,
                               boolean canReorder, Integer returnSlotNo, Integer offerSlotNo,
                               List<StepView> steps, List<Map<String, Object>> right) {
    }

    public List<CabinetView> cabinets(Long memberId, int limit) {
        int minSoc = repo.defaultOfferMinSoc();
        List<CabinetView> views = new ArrayList<>();
        for (Map<String, Object> row : repo.availableCabinets(minSoc, limit)) {
            views.add(toCabinet(row));
        }
        return views;
    }

    public CabinetView cabinet(String cabinetNo) {
        Map<String, Object> row = repo.cabinetOverview(cabinetNo, repo.defaultOfferMinSoc());
        if (row == null) {
            throw new IllegalArgumentException("柜机不存在：" + cabinetNo);
        }
        return toCabinet(row);
    }

    /** 当前在途单（没有则返回 null，前端据此决定进入找柜页还是进度页）。 */
    public ProgressView current(long memberId) {
        SwapOrderRepository.OrderRow order = repo.findInfightByUser(memberId);
        return order == null ? null : progress(order.id(), memberId);
    }

    public ProgressView progress(long orderId, long memberId) {
        SwapOrderRepository.OrderRow order = repo.findOrder(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在：" + orderId);
        }
        return toProgress(order, memberId);
    }

    public ProgressView progressByNo(String orderNo, long memberId) {
        SwapOrderRepository.OrderRow order = repo.findByOrderNo(orderNo);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在：" + orderNo);
        }
        return toProgress(order, memberId);
    }

    /**
     * 建单。返回结果里带展示态与拒绝原因，前端不需要二次查询就能决定跳页还是提示。
     *
     * guard 不通过时**不是异常**：那是业务结论（资格/仓位不满足），异常只留给真正的系统故障。
     */
    public Map<String, Object> create(long memberId, String cabinetNo, String traceId) {
        SwapOrderService.CreateResult result = orders.create(memberId, cabinetNo, "H5", traceId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("orderNo", result.orderNo());
        view.put("orderId", result.orderId());
        view.put("accepted", result.accepted());
        view.put("orderState", result.state().name());
        DisplayState display = DisplayState.of(result.state());
        view.put("displayState", display.name());
        view.put("label", display.label());
        view.put("tone", display.tone());
        view.put("hint", display.hint());
        view.put("rejectReasons", result.rejectReasons());
        return view;
    }

    /** 开归还仓（S1 下发）。 */
    public ProgressView start(String orderNo, long memberId) {
        SwapOrderRepository.OrderRow order = requireOwned(orderNo, memberId);
        flow.startReturn(order.id());
        return progress(order.id(), memberId);
    }

    /** 声明"我已关好仓门"（只在挂起态有意义，且只允许一次）。 */
    public Map<String, Object> declareClosed(String orderNo, long memberId) {
        SwapOrderRepository.OrderRow order = requireOwned(orderNo, memberId);
        String outcome = flow.declareClosed(order.id(), memberId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("outcome", outcome);
        view.put("progress", progress(order.id(), memberId));
        return view;
    }

    private SwapOrderRepository.OrderRow requireOwned(String orderNo, long memberId) {
        SwapOrderRepository.OrderRow order = repo.findByOrderNo(orderNo);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在：" + orderNo);
        }
        if (!Objects.equals(order.userId(), memberId)) {
            // 归属校验必须在服务端做：前端"看不到别人的单"不是安全边界
            throw new IllegalStateException("订单不属于当前会员");
        }
        return order;
    }

    private ProgressView toProgress(SwapOrderRepository.OrderRow order, long memberId) {
        if (!Objects.equals(order.userId(), memberId)) {
            throw new IllegalStateException("订单不属于当前会员");
        }
        OrderState state = OrderState.valueOf(order.state());
        DisplayState display = DisplayState.of(state);
        List<StepView> steps = new ArrayList<>();
        for (Map<String, Object> row : repo.steps(order.id())) {
            int stepNo = ((Number) row.get("step_no")).intValue();
            String stepState = String.valueOf(row.get("step_state"));
            steps.add(toStep(stepNo, stepState));
        }
        boolean step1Pending = steps.stream().anyMatch(
                s -> s.stepNo() == StepCode.OPEN_RETURN.order() && "PENDING".equals(s.stepState()));
        boolean canStart = state == OrderState.AUTHORIZED && step1Pending;
        boolean canDeclare = state == OrderState.SUSPENDED && !repo.selfResumeUsed(order.id());
        return new ProgressView(order.orderNo(), order.state(), display.name(), display.label(), display.tone(),
                display.hint(), display.terminal(), canStart, canDeclare, display.canReorder(),
                order.returnSlotNo(), order.offerSlotNo(), steps, repo.rightSnapshot(order.userId()));
    }

    private static StepView toStep(int stepNo, String stepState) {
        StepCode code = StepCode.of(stepNo);
        StepState state = StepState.valueOf(stepState);
        // isDone 与 isTerminal 是两个不同问题（PHYSICS_DONE 不需要重发但尚未封口），
        // 进度条上必须分开：done 画对勾，active 表示还在进行
        return new StepView(stepNo, code == null ? "STEP_" + stepNo : code.name(), stepState,
                code == null ? "步骤 " + stepNo : code.userLabel(), state.isDone(), !state.isTerminal());
    }

    private static CabinetView toCabinet(Map<String, Object> row) {
        int free = ((Number) row.get("free_return_slots")).intValue();
        int ready = ((Number) row.get("ready_offer_slots")).intValue();
        String state = String.valueOf(row.get("cabinet_state"));
        String online = String.valueOf(row.get("online_state"));
        return new CabinetView(String.valueOf(row.get("cabinet_no")),
                ((Number) row.get("site_id")).longValue(),
                row.get("slot_count") == null ? null : ((Number) row.get("slot_count")).intValue(),
                state, online, free, ready,
                "NORMAL".equals(state) && "ONLINE".equals(online) && free > 0 && ready > 0);
    }
}
