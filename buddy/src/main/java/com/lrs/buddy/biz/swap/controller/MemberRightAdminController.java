package com.lrs.buddy.biz.swap.controller;

import com.lrs.buddy.biz.swap.service.MemberRightAdminService;
import com.lrs.buddy.framework.common.response.R;
import com.lrs.buddy.framework.modules.log.annotation.OperateLog;
import com.lrs.buddy.framework.modules.log.enums.BusinessType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 后台权益接口（查询 + 发放/补发）。
 *
 * 权限复用 V11 已种的 `member:right:read` 与 `member:right:adjust`，不另起一套命名：
 * 同一件事两个码，最后没人知道"到底哪个才是真权限"。
 * 也不复用 `swap:order:intervene`——能改订单状态的人不该默认能给自己加次数。
 */
@RestController
@RequestMapping("/swap/rights")
@Validated
public class MemberRightAdminController {

    private final MemberRightAdminService rights;

    public MemberRightAdminController(MemberRightAdminService rights) {
        this.rights = rights;
    }

    @Data
    public static class GrantForm {
        @NotNull(message = "会员 ID 不能为空")
        private Long memberId;
        @NotNull @Min(value = 1, message = "发放次数必须大于 0")
        private Integer times;
        @NotNull @Min(value = 1, message = "有效期天数必须大于 0")
        private Integer validDays;
        /** 发放依据（工单号、活动名、客服记录等）；少于 5 字会被拒 */
        @NotNull(message = "备注不能为空")
        private String remark;
    }

    @GetMapping("/{memberId}")
    @PreAuthorize("hasAuthority('member:right:read')")
    public R<Map<String, Object>> account(@PathVariable long memberId) {
        return R.ok(rights.accountOf(memberId));
    }

    @PostMapping("/grant")
    @PreAuthorize("hasAuthority('member:right:adjust')")
    @OperateLog(title = "发放换电次数", businessType = BusinessType.INSERT)
    public R<MemberRightAdminService.GrantView> grant(@Validated @RequestBody GrantForm form) {
        return R.ok(rights.grant(form.getMemberId(), form.getTimes(), form.getValidDays(), form.getRemark()),
                "发放完成");
    }
}
