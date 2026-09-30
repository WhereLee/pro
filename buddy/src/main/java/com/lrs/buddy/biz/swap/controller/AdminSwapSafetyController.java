package com.lrs.buddy.biz.swap.controller;

import com.lrs.buddy.biz.swap.compensation.AssetLedgerReconcileJob;
import com.lrs.buddy.biz.swap.compensation.SwapCompensationExecutor;
import com.lrs.buddy.biz.swap.repo.SwapAdminReadRepository;
import com.lrs.buddy.biz.swap.safety.SwapSafetyLinkageService;
import com.lrs.buddy.framework.common.response.R;
import com.lrs.buddy.framework.modules.log.annotation.OperateLog;
import com.lrs.buddy.framework.modules.log.enums.BusinessType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 安全联动与补偿/对账台账（后台）。真实路径含 context-path：/api/swap/safety、/api/swap/compensations。
 *
 * 三个权限码是三条不同的责任线，刻意不合并：
 * <ul>
 *   <li>{@code swap:safety:stop} 停掉一个站点的全部换电（批量、不可回退、动资金口径）；</li>
 *   <li>{@code swap:compensation:read} 只看"哪些事还没做完"；</li>
 *   <li>{@code swap:discrepancy:replay} 手动触发一次对账（会排补偿，属于处置动作而不是查看）。</li>
 * </ul>
 *
 * 这里的紧急停充是 M3 的**受控接口**：本阶段的触发源只有两个——人工在此下指令、以及 FI-14 故障注入。
 * 阈值判定与升级规则引擎属 M5（见 swap-plan.md §边界），不要误以为"接上就会自动停"。
 */
@RestController
@RequestMapping("/swap")
@Validated
public class AdminSwapSafetyController {

    private final SwapSafetyLinkageService safety;
    private final AssetLedgerReconcileJob reconcile;
    private final SwapCompensationExecutor executor;
    private final SwapAdminReadRepository readRepo;

    public AdminSwapSafetyController(SwapSafetyLinkageService safety, AssetLedgerReconcileJob reconcile,
                                     SwapCompensationExecutor executor, SwapAdminReadRepository readRepo) {
        this.safety = safety;
        this.reconcile = reconcile;
        this.executor = executor;
        this.readRepo = readRepo;
    }

    @Data
    public static class StopForm {
        @Min(value = 1, message = "站点必填")
        private Long siteId;
        /** 触发依据：联动本身也是要被审计的动作，事后必须能回答"当时为什么停整个站" */
        @NotBlank
        @Size(min = 5, max = 200, message = "必须写明触发依据（哪个告警、哪块电池温度）")
        private String reason;
        /** 关联告警号，可空（人工判断的联动没有告警可挂） */
        private String alarmRef;
    }

    @PostMapping("/safety/emergency-stop")
    @PreAuthorize("hasAuthority('swap:safety:stop')")
    @OperateLog(title = "紧急停充联动", businessType = BusinessType.UPDATE)
    public R<SwapSafetyLinkageService.LinkageResult> emergencyStop(@Validated @RequestBody StopForm form) {
        return R.ok(safety.emergencyStopSite(form.getSiteId(), form.getReason(), form.getAlarmRef()),
                "联动已执行，成功/失败台数看返回值；在途单走系统中止路径");
    }

    @PostMapping("/safety/compensation/sweep")
    @PreAuthorize("hasAuthority('swap:discrepancy:replay')")
    @OperateLog(title = "补偿立即执行一批", businessType = BusinessType.OTHER)
    public R<Integer> sweepCompensation() {
        // 调度器每 15s 自己会跑；这个入口是给"刚把 M4 执行者补上，想立刻验证一遍"的人用的，
        // 返回本轮取到的条数而不是"执行成功与否"，因为执行结果在台账里，不在这里。
        return R.ok(executor.runOnce(), "本轮取到的补偿项条数（结果以台账为准）");
    }

    @PostMapping("/safety/reconcile")
    @PreAuthorize("hasAuthority('swap:discrepancy:replay')")
    @OperateLog(title = "资产台账对账", businessType = BusinessType.OTHER)
    public R<Integer> reconcileNow() {
        return R.ok(reconcile.reconcileOnce(), "本次发现的差异条数（证据完整的已排入补偿）");
    }

    @GetMapping("/compensations")
    @PreAuthorize("hasAuthority('swap:compensation:read')")
    public R<List<Map<String, Object>>> compensations(@RequestParam(required = false) String compState,
                                                     @RequestParam(defaultValue = "100") int limit) {
        return R.ok(readRepo.compensations(compState, limit));
    }
}
