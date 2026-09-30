package com.lrs.buddy.biz.swap.controller;

import com.lrs.buddy.biz.swap.provision.DeviceProvisionService;
import com.lrs.buddy.framework.common.response.R;
import com.lrs.buddy.framework.iot.entity.IotDevice;
import com.lrs.buddy.framework.modules.log.annotation.OperateLog;
import com.lrs.buddy.framework.modules.log.enums.BusinessType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备开通与凭证管理（后台）。真实路径含 context-path：/api/swap/devices。
 *
 * 一个必须写在这里的安全细节：`@OperateLog` 默认会把**返回结果**序列化落进
 * sys_operate_log.json_result。这两个接口的响应里就有一次性明文主密钥，
 * 如果按默认值记日志，等于"接口保护住了、审计表把密钥明文存了一份"。
 * 所以 register / rotate 必须显式 `logResult = false`。
 */
@RestController
@RequestMapping("/swap/devices")
@Validated
public class AdminSwapDeviceController {

    private final DeviceProvisionService provision;

    public AdminSwapDeviceController(DeviceProvisionService provision) {
        this.provision = provision;
    }

    @Data
    public static class RegisterForm {
        @NotBlank
        private String productKey;
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_-]{4,64}$", message = "deviceId 只允许字母数字与 - _，长度 4-64")
        private String deviceId;
        private String deviceName;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('iot:device:provision')")
    @OperateLog(title = "设备开通", businessType = BusinessType.INSERT, logResult = false)
    public R<DeviceProvisionService.Credential> register(@Validated @RequestBody RegisterForm form) {
        return R.ok(provision.register(form.getProductKey(), form.getDeviceId(), form.getDeviceName()),
                "开通成功，主密钥仅此一次返回");
    }

    @PostMapping("/{deviceId}/rotate-secret")
    @PreAuthorize("hasAuthority('iot:device:rotate')")
    @OperateLog(title = "设备密钥轮转", businessType = BusinessType.UPDATE, logResult = false)
    public R<DeviceProvisionService.Credential> rotate(@PathVariable String deviceId,
                                                       @RequestParam String productKey) {
        return R.ok(provision.rotate(productKey, deviceId), "已轮转，旧密钥即刻失效");
    }

    @GetMapping("/{deviceId}")
    @PreAuthorize("hasAuthority('iot:device:read')")
    public R<IotDevice> detail(@PathVariable String deviceId, @RequestParam String productKey) {
        // 返回的是实体，不含 secret_cipher（该列刻意不在实体里）
        return R.ok(provision.find(productKey, deviceId));
    }

    @PutMapping("/{rowId}/enabled")
    @PreAuthorize("hasAuthority('iot:device:manage')")
    @OperateLog(title = "设备启停", businessType = BusinessType.UPDATE)
    public R<Void> toggle(@PathVariable Long rowId, @RequestParam boolean enabled) {
        provision.toggleEnabled(rowId, enabled);
        return R.ok(null, enabled ? "已启用" : "已停用");
    }
}
