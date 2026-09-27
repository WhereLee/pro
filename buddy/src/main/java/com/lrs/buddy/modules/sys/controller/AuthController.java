package com.lrs.buddy.modules.sys.controller;

import cn.hutool.extra.servlet.JakartaServletUtil;
import com.lrs.buddy.common.R;
import com.lrs.buddy.modules.log.annotation.OperateLog;
import com.lrs.buddy.modules.log.enums.BusinessType;
import com.lrs.buddy.modules.sys.model.form.LoginForm;
import com.lrs.buddy.modules.sys.model.vo.LoginVO;
import com.lrs.buddy.modules.sys.model.vo.SysMenuVO;
import com.lrs.buddy.modules.sys.model.vo.UserInfoVO;
import com.lrs.buddy.modules.sys.service.SysAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 认证接口。
 */
@Slf4j
@Tag(name = "认证")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SysAuthService authService;

    /**
     * 登录。
     *
     * <p>{@code logParam = false}：请求体里就是密码，
     * 登录日志只需留下"谁在什么时候从哪个 IP 登录"，不需要也不应该记密码。
     */
    @Operation(summary = "登录")
    @OperateLog(title = "登录", businessType = BusinessType.LOGIN, logParam = false, logResult = false)
    @PostMapping("/login")
    public R<LoginVO> login(@Valid @RequestBody LoginForm form, HttpServletRequest request) {
        String ip = JakartaServletUtil.getClientIP(request);
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        return R.ok(authService.login(form, ip, userAgent), "登录成功");
    }

    @Operation(summary = "登出")
    @OperateLog(title = "登录", businessType = BusinessType.LOGOUT, logResult = false)
    @PostMapping("/logout")
    public R<Void> logout() {
        authService.logout();
        return R.ok(null, "已退出登录");
    }

    @Operation(summary = "当前用户信息（角色与权限）")
    @GetMapping("/info")
    public R<UserInfoVO> info() {
        return R.ok(authService.currentUser());
    }

    @Operation(summary = "当前用户可见菜单（前端动态路由）")
    @GetMapping("/routes")
    public R<List<SysMenuVO>> routes() {
        return R.ok(authService.currentRoutes());
    }
}
