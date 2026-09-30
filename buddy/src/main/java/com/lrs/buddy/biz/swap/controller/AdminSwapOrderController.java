package com.lrs.buddy.biz.swap.controller;

import com.lrs.buddy.biz.swap.display.DisplayState;
import com.lrs.buddy.biz.swap.intervention.SwapInterventionService;
import com.lrs.buddy.biz.swap.repo.SwapOrderRepository;
import com.lrs.buddy.framework.common.model.PageResult;
import com.lrs.buddy.framework.common.response.R;
import com.lrs.buddy.framework.modules.log.annotation.OperateLog;
import com.lrs.buddy.framework.modules.log.enums.BusinessType;
import com.lrs.buddy.framework.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台换电订单管理与人工干预（M2 B3）。
 *
 * 权限码分三个，不合并：
 * <ul>
 *   <li>{@code swap:order:read} 看列表与详情（含事件流）</li>
 *   <li>{@code swap:order:intervene} **提交**干预申请</li>
 *   <li>{@code swap:order:approve} **复核**申请并执行</li>
 * </ul>
 * 把后两个合成一个码，就等于"能申请的人也能自己批"，双人复核退化成一个人点两次；
 * 而"自己不能批自己"这条还另外钉在 DB CHECK 上（代码回滚也不会丢掉防线）。
 */
@RestController
@RequestMapping("/swap/orders")
@Validated
public class AdminSwapOrderController {

    private final SwapOrderRepository repo;
    private final SwapInterventionService interventions;

    public AdminSwapOrderController(SwapOrderRepository repo, SwapInterventionService interventions) {
        this.repo = repo;
        this.interventions = interventions;
    }

    @Data
    public static class InterventionForm {
        @NotBlank(message = "订单号不能为空")
        private String orderNo;
        /** ADMIN_ABORT / ADMIN_RESOLVE_COMPLETED / ADMIN_RESOLVE_ABORTED */
        @NotBlank(message = "干预动作不能为空")
        private String action;
        @Size(min = 5, max = 255, message = "干预理由不能少于 5 个字")
        private String reason;
    }

    @Data
    public static class RejectForm {
        @Size(min = 5, max = 255, message = "驳回理由不能少于 5 个字")
        private String reason;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('swap:order:read')")
    public R<PageResult<Map<String, Object>>> page(@RequestParam(required = false) String state,
                                                   @RequestParam(required = false) String orderNo,
                                                   @RequestParam(defaultValue = "1") int pageNum,
                                                   @RequestParam(defaultValue = "20") int pageSize) {
        int size = Math.max(1, Math.min(pageSize, 100));
        int page = Math.max(1, pageNum);
        List<Map<String, Object>> rows = repo.pageOrders(state, orderNo, (page - 1) * size, size);
        // 列表带上展示态：运营看的是"这单在用户眼里是什么"，而不是只看内部状态码
        rows.forEach(row -> row.put("displayState", DisplayState.of(String.valueOf(row.get("order_state"))).name()));
        long total = repo.countOrders(state, orderNo);
        return R.ok(new PageResult<>(rows, total, page, size, (total + size - 1) / size));
    }

    @GetMapping("/{orderNo}")
    @PreAuthorize("hasAuthority('swap:order:read')")
    public R<Map<String, Object>> detail(@PathVariable String orderNo) {
        SwapOrderRepository.OrderRow order = repo.findByOrderNo(orderNo);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在：" + orderNo);
        }
        DisplayState display = DisplayState.of(order.state());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("orderNo", order.orderNo());
        view.put("orderState", order.state());
        view.put("displayState", display.name());
        view.put("label", display.label());
        view.put("tone", display.tone());
        view.put("userId", order.userId());
        view.put("cabinetId", order.cabinetId());
        view.put("returnSlotNo", order.returnSlotNo());
        view.put("offerSlotNo", order.offerSlotNo());
        view.put("returnBatteryId", order.returnBatteryId());
        view.put("offerBatteryId", order.offerBatteryId());
        view.put("rightState", order.rightState());
        view.put("steps", repo.steps(order.id()));
        view.put("events", repo.events(order.id()));
        view.put("reservations", repo.reservations(order.id()));
        view.put("interventions", interventions.ofOrder(orderNo));
        return R.ok(view);
    }

    /** 待处理的干预申请队列（复核工作台）。 */
    @GetMapping("/interventions/pending")
    @PreAuthorize("hasAuthority('swap:order:approve')")
    public R<List<SwapInterventionService.InterventionView>> pending(@RequestParam(defaultValue = "50") int limit) {
        return R.ok(interventions.byStatus("PENDING", limit));
    }

    @PostMapping("/interventions")
    @PreAuthorize("hasAuthority('swap:order:intervene')")
    @OperateLog(title = "提交订单干预申请", businessType = BusinessType.INSERT)
    public R<Long> apply(@Validated @RequestBody InterventionForm form) {
        return R.ok(interventions.apply(form.getOrderNo(), form.getAction(), form.getReason(),
                SecurityUtils.getUserId(), SecurityUtils.getUsername()), "干预申请已提交，等待他人复核");
    }

    @PostMapping("/interventions/{id}/approve")
    @PreAuthorize("hasAuthority('swap:order:approve')")
    @OperateLog(title = "复核通过并执行干预", businessType = BusinessType.UPDATE)
    public R<String> approve(@PathVariable long id) {
        String state = interventions.approveAndExecute(id, SecurityUtils.getUserId(), SecurityUtils.getUsername());
        return R.ok(state, "干预已执行，订单当前状态：" + state);
    }

    @PostMapping("/interventions/{id}/reject")
    @PreAuthorize("hasAuthority('swap:order:approve')")
    @OperateLog(title = "驳回干预申请", businessType = BusinessType.UPDATE)
    public R<Void> reject(@PathVariable long id, @Validated @RequestBody RejectForm form) {
        interventions.reject(id, SecurityUtils.getUserId(), SecurityUtils.getUsername(), form.getReason());
        return R.ok(null, "已驳回");
    }
}
