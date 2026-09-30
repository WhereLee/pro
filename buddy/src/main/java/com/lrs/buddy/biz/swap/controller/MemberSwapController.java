package com.lrs.buddy.biz.swap.controller;

import com.lrs.buddy.biz.member.security.MemberJwtAuthenticationFilter;
import com.lrs.buddy.biz.swap.service.MemberSwapService;
import com.lrs.buddy.framework.common.response.R;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * C 端换电接口（找柜 → 建单 → 开仓 → 进度 → 结果）。真实路径含 context-path：/api/member/swap/...
 *
 * 这里**不写 hasAuthority**：member 域没有权限码体系，安全性来自"当前登录会员"这个身份
 * 与"归属校验"（订单是不是你的）。用权限码当数据隔离是常见误区——权限码管的是"能不能用这个功能"，
 * 管不了"这条记录是不是你的"。
 *
 * 进度接口是给前端轮询用的，因此全部只读（除 start / declare-closed 两个用户动作）：
 * 高频刷新不可能引入状态副作用，这一点由"读方法不加 @Transactional 改状态"保证。
 */
@RestController
@RequestMapping("/member/swap")
@Validated
public class MemberSwapController {

    private final MemberSwapService swap;

    public MemberSwapController(MemberSwapService swap) {
        this.swap = swap;
    }

    @Data
    public static class CreateForm {
        @NotBlank(message = "柜机编号不能为空")
        private String cabinetNo;
    }

    @GetMapping("/cabinets")
    public R<List<MemberSwapService.CabinetView>> cabinets(@RequestParam(defaultValue = "20") int limit) {
        return R.ok(swap.cabinets(MemberJwtAuthenticationFilter.currentMemberId(), limit));
    }

    @GetMapping("/cabinets/{cabinetNo}")
    public R<MemberSwapService.CabinetView> cabinet(@PathVariable String cabinetNo) {
        return R.ok(swap.cabinet(cabinetNo));
    }

    @PostMapping("/orders")
    public R<Map<String, Object>> create(@Validated @RequestBody CreateForm form) {
        long memberId = MemberJwtAuthenticationFilter.currentMemberId();
        return R.ok(swap.create(memberId, form.getCabinetNo(), "h5-" + java.util.UUID.randomUUID()));
    }

    /** 当前在途单：无在途单返回 null，前端据此决定落在找柜页还是进度页。 */
    @GetMapping("/orders/current")
    public R<MemberSwapService.ProgressView> current() {
        return R.ok(swap.current(MemberJwtAuthenticationFilter.currentMemberId()));
    }

    @GetMapping("/orders/{orderNo}")
    public R<MemberSwapService.ProgressView> progress(@PathVariable String orderNo) {
        return R.ok(swap.progressByNo(orderNo, MemberJwtAuthenticationFilter.currentMemberId()));
    }

    @PostMapping("/orders/{orderNo}/start")
    public R<MemberSwapService.ProgressView> start(@PathVariable String orderNo) {
        return R.ok(swap.start(orderNo, MemberJwtAuthenticationFilter.currentMemberId()));
    }

    @PostMapping("/orders/{orderNo}/declare-closed")
    public R<Map<String, Object>> declareClosed(@PathVariable String orderNo) {
        return R.ok(swap.declareClosed(orderNo, MemberJwtAuthenticationFilter.currentMemberId()));
    }
}
