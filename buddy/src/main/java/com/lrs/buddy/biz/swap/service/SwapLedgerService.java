package com.lrs.buddy.biz.swap.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.lrs.buddy.biz.swap.entity.SwapBattery;
import com.lrs.buddy.biz.swap.entity.SwapCabinet;
import com.lrs.buddy.biz.swap.entity.SwapSlot;
import com.lrs.buddy.biz.swap.mapper.SwapBatteryMapper;
import com.lrs.buddy.biz.swap.mapper.SwapCabinetMapper;
import com.lrs.buddy.biz.swap.mapper.SwapSlotMapper;
import com.lrs.buddy.framework.iot.entity.IotDevice;
import com.lrs.buddy.framework.iot.mapper.IotDeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 柜机与资产台账建立（M2 第一步，联跑与主链路的前置）。
 *
 * 为什么这一步要卡"设备必须已注册且启用"：
 * V15 刚处理过一个同类问题——种子里的柜机绑了一台凭证不可解的设备，
 * 台账正常、仓位齐全、但柜机永远连不上，表现是"柜机离线"，排查方向会被带去网络。
 * 所以建柜机时就把设备状态当作**准入条件**，而不是等运行期才发现。
 *
 * 两条在事务里必须同时成立的资产口径（缺一处就会有"幽灵电池"）：
 * 1 仓位有电池 ⇔ slot.battery_id 非空 且 slot_state ≠ IDLE_EMPTY；
 * 2 电池位置 = battery.current_cabinet_id/current_slot_id 与上面那个仓位**双向一致**。
 * 两次写放在同一事务里，中途失败整体回滚；单写一边会留下"仓位显示有电池但电池说自己在别处"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SwapLedgerService {

    private final SwapCabinetMapper cabinetMapper;
    private final SwapSlotMapper slotMapper;
    private final SwapBatteryMapper batteryMapper;
    private final IotDeviceMapper deviceMapper;

    /** 建柜机并同时生成仓位。仓位一次性按 1..slotCount 建齐，不允许后续"补一个仓"造成编号空洞。 */
    @Transactional
    public SwapCabinet createCabinet(long siteId, String productKey, String cabinetNo, String deviceId,
                                     int slotCount, String lockType, BigDecimal powerLimitKw) {
        if (slotCount < 1 || slotCount > 64) {
            throw new IllegalArgumentException("仓位数量必须在 1..64：" + slotCount);
        }
        IotDevice device = requireUsableDevice(productKey, deviceId);
        if (findCabinetByNo(cabinetNo) != null) {
            throw new IllegalStateException("柜机编号已存在：" + cabinetNo);
        }
        SwapCabinet cabinet = new SwapCabinet();
        cabinet.setId(IdWorker.getId());
        cabinet.setDeviceRowId(device.getId());
        cabinet.setSiteId(siteId);
        cabinet.setCabinetNo(cabinetNo);
        cabinet.setSlotCount(slotCount);
        cabinet.setCabinetModel(productKey);
        cabinet.setLockType(lockType == null ? "ELECTROMAGNET" : lockType);
        cabinet.setPowerLimitKw(powerLimitKw);
        cabinet.setCabinetState("NORMAL");
        try {
            cabinetMapper.insert(cabinet);
        } catch (DuplicateKeyException e) {
            // 唯一索引是最终裁判（并发下两个请求同时建同编号），先查只是为了给可读错误
            throw new IllegalStateException("柜机编号已存在：" + cabinetNo, e);
        }
        for (int slotNo = 1; slotNo <= slotCount; slotNo++) {
            slotMapper.insert(newSlot(cabinet.getId(), slotNo));
        }
        log.info("柜机已建：cabinetNo={}, slots={}, deviceRowId={}", cabinetNo, slotCount, device.getId());
        return cabinet;
    }

    /** 电池入仓：仓位必须空闲，否则是"把别人的电池塞进去"。 */
    @Transactional
    public SwapBattery registerBattery(String cabinetNo, int slotNo, String batteryCode, String productKey,
                                       Integer soc, BigDecimal temp, BigDecimal capacityAh, BigDecimal voltageV) {
        SwapCabinet cabinet = requireCabinet(cabinetNo);
        SwapSlot slot = requireSlot(cabinet.getId(), slotNo);
        if (!"IDLE_EMPTY".equals(slot.getSlotState()) || slot.getBatteryId() != null) {
            throw new IllegalStateException("仓位不可用，不能导入电池：" + cabinetNo + "#" + slotNo
                    + "，当前状态=" + slot.getSlotState());
        }
        if (findBatteryByCode(batteryCode) != null) {
            throw new IllegalStateException("电池码已存在：" + batteryCode);
        }
        LocalDateTime now = LocalDateTime.now();
        SwapBattery battery = new SwapBattery();
        battery.setId(IdWorker.getId());
        battery.setBatteryCode(batteryCode);
        battery.setProductKey(productKey);
        battery.setBatteryState("IN_CABINET_CHARGING");
        battery.setOwnType("PLATFORM");
        battery.setCurrentCabinetId(cabinet.getId());
        battery.setCurrentSlotId(slot.getId());
        battery.setLocationState("KNOWN");
        battery.setSoc(soc == null ? 0 : soc);
        battery.setSoh(BigDecimal.valueOf(100));
        battery.setCycleCount(0);
        battery.setCapacityAh(capacityAh);
        battery.setVoltageV(voltageV);
        battery.setLastReportAt(now);
        battery.setActivatedAt(now);
        try {
            batteryMapper.insert(battery);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("电池码已存在：" + batteryCode, e);
        }
        // 反向引用与状态同事务更新：拆成两次提交就会留下单边的幽灵数据
        slot.setBatteryId(battery.getId());
        slot.setSlotState("IDLE_CHARGING");
        slot.setChargeState("CHARGING");
        slot.setLastTemp(temp);
        slot.setLastDetectedAt(now);
        slotMapper.updateById(slot);
        return battery;
    }

    /** 仓位停用（运维/故障）。停用后分配硬门槛直接排除，不需要额外开关。 */
    @Transactional
    public void disableSlot(String cabinetNo, int slotNo, String reason) {
        SwapCabinet cabinet = requireCabinet(cabinetNo);
        SwapSlot slot = requireSlot(cabinet.getId(), slotNo);
        if (slot.getBatteryId() != null) {
            throw new IllegalStateException("仓内有电池，不能直接停用，请先转移资产：" + cabinetNo + "#" + slotNo);
        }
        slot.setSlotState("DISABLED");
        slot.setDisabledFlag(1);
        slot.setFaultCode(reason);
        slotMapper.updateById(slot);
    }

    public List<SwapSlot> slots(String cabinetNo) {
        return slotMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SwapSlot>()
                .eq(SwapSlot::getCabinetId, requireCabinet(cabinetNo).getId())
                .orderByAsc(SwapSlot::getSlotNo));
    }

    public SwapCabinet requireCabinet(String cabinetNo) {
        SwapCabinet cabinet = findCabinetByNo(cabinetNo);
        if (cabinet == null) {
            throw new IllegalArgumentException("柜机不存在：" + cabinetNo);
        }
        return cabinet;
    }

    private SwapCabinet findCabinetByNo(String cabinetNo) {
        return cabinetMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<SwapCabinet>().eq(SwapCabinet::getCabinetNo, cabinetNo));
    }

    private SwapSlot requireSlot(Long cabinetId, int slotNo) {
        SwapSlot slot = slotMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<SwapSlot>().eq(SwapSlot::getCabinetId, cabinetId)
                .eq(SwapSlot::getSlotNo, slotNo));
        if (slot == null) {
            throw new IllegalArgumentException("仓位不存在：cabinetId=" + cabinetId + ", slotNo=" + slotNo);
        }
        return slot;
    }

    private SwapBattery findBatteryByCode(String batteryCode) {
        return batteryMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<SwapBattery>().eq(SwapBattery::getBatteryCode, batteryCode));
    }

    /** 设备不存在/未启用/型号不匹配都拒绝：柜机不能绑一台接不进来的设备。 */
    private IotDevice requireUsableDevice(String productKey, String deviceId) {
        List<IotDevice> devices = deviceMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<IotDevice>().eq(IotDevice::getProductKey, productKey)
                .eq(IotDevice::getDeviceId, deviceId));
        if (devices.isEmpty()) {
            throw new IllegalArgumentException("设备未注册，请先调用设备开通接口：" + productKey + "/" + deviceId);
        }
        IotDevice device = devices.get(0);
        if (device.getEnabled() == null || device.getEnabled() != 1) {
            throw new IllegalStateException("设备已停用，不能建柜机台账：" + deviceId);
        }
        return device;
    }

    private SwapSlot newSlot(Long cabinetId, int slotNo) {
        SwapSlot slot = new SwapSlot();
        slot.setId(IdWorker.getId());
        slot.setCabinetId(cabinetId);
        slot.setSlotNo(slotNo);
        slot.setSlotState("IDLE_EMPTY");
        slot.setDoorState("CLOSED");
        slot.setLockState("LOCKED");
        slot.setChargeState("IDLE");
        slot.setDisabledFlag(0);
        return slot;
    }
}
