package com.lrs.buddy.biz.member.controller;

import com.lrs.buddy.biz.member.security.MemberJwtAuthenticationFilter;
import com.lrs.buddy.biz.member.service.MemberAuthService;
import com.lrs.buddy.framework.common.response.R;
import com.lrs.buddy.framework.modules.log.annotation.OperateLog;
import com.lrs.buddy.framework.modules.log.enums.BusinessType;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端身份接口（注册即登录、令牌轮换、实名提交）。真实路径含 context-path：/api/member/...
 *
 * 与后台接口同一条安全约定：**返回里有令牌或验证码就必须 logResult=false**。
 * 否则一次性凭证会作为 JSON 长期留在 sys_operate_log.json_result 里，
 * 等于"接口保护住了、审计表存了一份可重放的凭证"。
 */
@RestController
@RequestMapping("/member")
@Validated
public class MemberAuthController {

    private final MemberAuthService auth;

    public MemberAuthController(MemberAuthService auth) {
        this.auth = auth;
    }

    @Data
    public static class SmsCodeForm {
        @NotBlank(message = "手机号不能为空")
        private String phone;
        /** LOGIN / REALNAME / CANCEL / REBIND / BIND */
        private String purpose;
    }

    @Data
    public static class LoginForm {
        @NotBlank(message = "手机号不能为空")
        private String phone;
        @NotBlank(message = "验证码不能为空")
        private String code;
        /** H5 / APP / MINI / VEHICLE / OPS；同类型互斥，不同类型可并存 */
        private String deviceType;
        private String deviceFingerprint;
    }

    @Data
    public static class RefreshForm {
        @NotBlank(message = "刷新令牌不能为空")
        private String refreshToken;
    }

    @Data
    public static class RealnameForm {
        @NotBlank(message = "姓名不能为空")
        private String realName;
        @NotBlank(message = "身份证号不能为空")
        private String idNo;
        @NotBlank(message = "验证码不能为空")
        private String smsCode;
    }

    @PostMapping("/auth/sms-code")
    @OperateLog(title = "C端验证码", businessType = BusinessType.INSERT, logResult = false)
    public R<MemberAuthService.CodeTicket> sendCode(@Validated @RequestBody SmsCodeForm form) {
        return R.ok(auth.sendCode(form.getPhone(), form.getPurpose()));
    }

    @PostMapping("/auth/login")
    @OperateLog(title = "C端登录", businessType = BusinessType.OTHER, logResult = false)
    public R<MemberAuthService.TokenPair> login(@Validated @RequestBody LoginForm form,
                                                jakarta.servlet.http.HttpServletRequest request) {
        return R.ok(auth.login(form.getPhone(), form.getCode(), form.getDeviceType(),
                form.getDeviceFingerprint(), request.getRemoteAddr()));
    }

    @PostMapping("/auth/refresh")
    @OperateLog(title = "C端令牌轮换", businessType = BusinessType.UPDATE, logResult = false)
    public R<MemberAuthService.TokenPair> refresh(@Validated @RequestBody RefreshForm form) {
        return R.ok(auth.refresh(form.getRefreshToken(), null));
    }

    @PostMapping("/auth/logout")
    public R<Void> logout() {
        var principal = MemberJwtAuthenticationFilter.currentPrincipal();
        auth.logout(principal.memberId(), principal.sessionFamily());
        return R.ok(null, "已退出登录");
    }

    /** 当前会员信息：手机号只回脱敏值，密文与哈希都不出库。 */
    @GetMapping("/me")
    public R<MemberAuthService.MemberView> me() {
        return R.ok(auth.me(MemberJwtAuthenticationFilter.currentMemberId()));
    }

    @PostMapping("/me/realname")
    @OperateLog(title = "C端实名提交", businessType = BusinessType.INSERT)
    public R<String> submitRealname(@Validated @RequestBody RealnameForm form) {
        return R.ok(auth.submitRealname(MemberJwtAuthenticationFilter.currentMemberId(),
                form.getRealName(), form.getIdNo(), form.getSmsCode()), "实名已提交");
    }
}
