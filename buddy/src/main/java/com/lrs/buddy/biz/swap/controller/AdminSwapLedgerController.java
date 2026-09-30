package com.lrs.buddy.biz.swap.controller;

import com.lrs.buddy.biz.swap.entity.SwapBattery;
import com.lrs.buddy.biz.swap.entity.SwapCabinet;
import com.lrs.buddy.biz.swap.entity.SwapSlot;
import com.lrs.buddy.biz.swap.service.SwapLedgerService;
import com.lrs.buddy.framework.common.response.R;
import com.lrs.buddy.framework.modules.log.annotation.OperateLog;
import com.lrs.buddy.framework.modules.log.enums.BusinessType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 柜机与资产台账（后台）。真实路径含 context-path：/api/swap/cabinets。
 *
 * 权限码复用 V11 已有的 swap:cabinet:manage 与 swap:battery:manage，不新起命名。
 */
@RestController
@RequestMapping("/swap/cabinets")
@Validated
public class AdminSwapLedgerController {

    private final SwapLedgerService ledger;

    public AdminSwapLedgerController(SwapLedgerService ledger) {
        this.ledger = ledger;
    }

    @Data
    public static class CabinetForm {
        @Min(value = 1, message = "站点必填")
        private Long siteId;
        @NotBlank
        private String productKey;
        @NotBlank
        private String cabinetNo;
        /** 必须是已通过设备开通接口注册且启用的 deviceId：柜机不能绑一台接不进来的设备 */
        @NotBlank
        private String deviceId;
        @Min(value = 1, message = "仓位数量至少 1")
        private Integer slotCount;
        private String lockType;
        private BigDecimal powerLimitKw;
    }

    @Data
    public static class BatteryForm {
        @NotBlank
        private String batteryCode;
        @NotBlank
        private String productKey;
        @Min(value = 1, message = "仓位号从 1 开始")
        private Integer slotNo;
        private Integer soc;
        private BigDecimal temp;
        private BigDecimal capacityAh;
        private BigDecimal voltageV;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('swap:cabinet:manage')")
    @OperateLog(title = "柜机建账", businessType = BusinessType.INSERT)
    public R<String> createCabinet(@Validated @RequestBody CabinetForm form) {
        SwapCabinet cabinet = ledger.createCabinet(form.getSiteId(), form.getProductKey(), form.getCabinetNo(),
                form.getDeviceId(), form.getSlotCount(), form.getLockType(), form.getPowerLimitKw());
        return R.ok(cabinet.getCabinetNo(), "建账成功，已生成 " + cabinet.getSlotCount() + " 个仓位");
    }

    @PostMapping("/{cabinetNo}/batteries")
    @PreAuthorize("hasAuthority('swap:battery:manage')")
    @OperateLog(title = "电池入仓", businessType = BusinessType.INSERT)
    public R<Long> registerBattery(@PathVariable String cabinetNo, @Validated @RequestBody BatteryForm form) {
        SwapBattery battery = ledger.registerBattery(cabinetNo, form.getSlotNo(), form.getBatteryCode(),
                form.getProductKey(), form.getSoc(), form.getTemp(), form.getCapacityAh(), form.getVoltageV());
        return R.ok(battery.getId(), "电池已入仓");
    }

    @PutMapping("/{cabinetNo}/slots/{slotNo}/disable")
    @PreAuthorize("hasAuthority('swap:slot:manage')")
    @OperateLog(title = "仓位停用", businessType = BusinessType.UPDATE)
    public R<Void> disableSlot(@PathVariable String cabinetNo, @PathVariable int slotNo, String reason) {
        ledger.disableSlot(cabinetNo, slotNo, reason == null ? "OPS" : reason);
        return R.ok(null, "仓位已停用");
    }

    @GetMapping("/{cabinetNo}/slots")
    @PreAuthorize("hasAuthority('swap:cabinet:read')")
    public R<List<SwapSlot>> slots(@PathVariable String cabinetNo) {
        // 仓位状态是运营口径，不含密钥类字段，可直接返回实体
        return R.ok(ledger.slots(cabinetNo));
    }
}
