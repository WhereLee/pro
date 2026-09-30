package com.lrs.buddy.biz.swap.controller;

import com.lrs.buddy.biz.swap.repo.SwapAdminReadRepository;
import com.lrs.buddy.framework.common.response.R;
import com.lrs.buddy.framework.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 后台资产与差异的只读列表（柜机监控 / 电池资产 / 账实差异）。
 *
 * 权限码各用各的（`swap:cabinet:read` / `swap:battery:read` / `swap:discrepancy:read`），
 * 不合并成一个"换电查看"码：电池台账含持有人与隔离原因，能看到它就等于能追人，
 * 与"看柜机有几个仓"不是同一敏感级别。
 *
 * 这里只读不写：写操作全在 `AdminSwapLedgerController`（建账/入仓/停用），
 * 分开是为了让"谁改了资产"和"谁看了资产"在审计日志里天然分家。
 */
@RestController
@RequestMapping("/swap")
public class AdminSwapReadController {

    private final SwapAdminReadRepository readRepo;

    public AdminSwapReadController(SwapAdminReadRepository readRepo) {
        this.readRepo = readRepo;
    }

    @GetMapping("/cabinets")
    @PreAuthorize("hasAuthority('swap:cabinet:read')")
    public R<List<Map<String, Object>>> cabinets(@RequestParam(defaultValue = "100") int limit) {
        long tenantId = TenantContext.getTenantId() == null ? 1L : TenantContext.getTenantId();
        return R.ok(readRepo.cabinets(tenantId, limit));
    }

    @GetMapping("/batteries")
    @PreAuthorize("hasAuthority('swap:battery:read')")
    public R<List<Map<String, Object>>> batteries(@RequestParam(required = false) String state,
                                                  @RequestParam(defaultValue = "100") int limit) {
        return R.ok(readRepo.batteries(state, limit));
    }

    @GetMapping("/discrepancies")
    @PreAuthorize("hasAuthority('swap:discrepancy:read')")
    public R<List<Map<String, Object>>> discrepancies(@RequestParam(required = false) String handleState,
                                                      @RequestParam(defaultValue = "100") int limit) {
        return R.ok(readRepo.discrepancies(handleState, limit));
    }
}
