package com.lrs.buddy.biz.swap;

import com.lrs.buddy.biz.swap.entity.SwapBattery;
import com.lrs.buddy.biz.swap.entity.SwapCabinet;
import com.lrs.buddy.biz.swap.entity.SwapSlot;
import com.lrs.buddy.biz.swap.provision.DeviceProvisionService;
import com.lrs.buddy.biz.swap.service.SwapLedgerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 台账建立与资产双向一致（M2 第一步）。
 *
 * 这里刻意把"必须先注册设备才能建柜机"当成断言对象：
 * V15 刚处理过一台凭证不可解却被当成正常柜机的占位行，
 * 准入条件不卡住，同类数据就会再被造出来，而且现场表现为"柜机离线"。
 */
@SpringBootTest
@ActiveProfiles("test")
class SwapLedgerTest {

    private static final String PRODUCT_KEY = "SWAP-CAB-8";

    @Autowired
    private SwapLedgerService ledger;
    @Autowired
    private DeviceProvisionService provision;
    @Autowired
    private JdbcTemplate jdbc;

    private String uniqueId(String prefix) {
        return prefix + System.nanoTime();
    }

    private DeviceProvisionService.Credential newDevice() {
        return provision.register(PRODUCT_KEY, uniqueId("CAB-LED-"), "台账测试柜");
    }

    @Test
    @DisplayName("建柜机一次生成全部仓位，初始态全部为可归还的空仓")
    void createCabinetGeneratesAllSlots() {
        var device = newDevice();
        String cabinetNo = uniqueId("CABNO-");

        SwapCabinet cabinet = ledger.createCabinet(7201L, PRODUCT_KEY, cabinetNo, device.deviceId(),
                8, "ELECTROMAGNET", new BigDecimal("3.50"));

        assertThat(cabinet.getDeviceRowId()).isEqualTo(device.deviceRowId());
        List<SwapSlot> slots = ledger.slots(cabinetNo);
        assertThat(slots).hasSize(8);
        assertThat(slots).extracting(SwapSlot::getSlotNo).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(slots).allMatch(slot -> "IDLE_EMPTY".equals(slot.getSlotState())
                && "CLOSED".equals(slot.getDoorState()) && "LOCKED".equals(slot.getLockState()));
    }

    @Test
    @DisplayName("未注册或未启用的设备不能建柜机")
    void cabinetRequiresRegisteredEnabledDevice() {
        var device = newDevice();
        // 把刚注册的设备停用，模拟 V15 那类"凭证不可用"的行
        jdbc.update("UPDATE iot_device SET enabled = 0 WHERE id = ?", device.deviceRowId());

        assertThatThrownBy(() -> ledger.createCabinet(7201L, PRODUCT_KEY, uniqueId("CABNO-"),
                device.deviceId(), 8, null, null))
                .as("柜机不能绑一台接不进来的设备")
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> ledger.createCabinet(7201L, PRODUCT_KEY, uniqueId("CABNO-"),
                "CAB-NOT-EXIST", 8, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("电池入仓：仓位与电池两侧必须同事务一致，且占用的仓不能再导入")
    void batteryImportKeepsBothSidesConsistent() {
        var device = newDevice();
        String cabinetNo = uniqueId("CABNO-");
        ledger.createCabinet(7201L, PRODUCT_KEY, cabinetNo, device.deviceId(), 4, null, null);
        String batteryCode = uniqueId("BAT-");

        SwapBattery battery = ledger.registerBattery(cabinetNo, 2, batteryCode, "BAT-60V20AH",
                88, new BigDecimal("28.0"), new BigDecimal("20.0"), new BigDecimal("60.0"));

        SwapSlot slot = ledger.slots(cabinetNo).get(1);
        assertThat(slot.getSlotNo()).isEqualTo(2);
        assertThat(slot.getBatteryId()).isEqualTo(battery.getId());
        assertThat(slot.getSlotState()).isEqualTo("IDLE_CHARGING");
        assertThat(slot.getChargeState()).isEqualTo("CHARGING");
        // 反向引用一致：电池说自己在哪个仓，仓位就得正指着这块电池
        assertThat(battery.getCurrentSlotId()).isEqualTo(slot.getId());
        assertThat(battery.getCurrentCabinetId()).isEqualTo(slot.getCabinetId());
        assertThat(battery.getLocationState()).isEqualTo("KNOWN");
        assertThat(battery.getBatteryState()).isEqualTo("IN_CABINET_CHARGING");

        assertThatThrownBy(() -> ledger.registerBattery(cabinetNo, 2, uniqueId("BAT-"), "BAT-60V20AH",
                90, new BigDecimal("28.0"), null, null))
                .as("仓内有电池就不能再导入第二块")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("重复电池码与重复柜机编号都被唯一索引挡住，并转成可读异常")
    void duplicateLedgerCodesAreRejected() {
        var device = newDevice();
        String cabinetNo = uniqueId("CABNO-");
        ledger.createCabinet(7201L, PRODUCT_KEY, cabinetNo, device.deviceId(), 2, null, null);
        String batteryCode = uniqueId("BAT-");
        ledger.registerBattery(cabinetNo, 1, batteryCode, "BAT-60V20AH", 90, new BigDecimal("25.0"), null, null);

        assertThatThrownBy(() -> ledger.registerBattery(cabinetNo, 2, batteryCode, "BAT-60V20AH",
                90, new BigDecimal("25.0"), null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.createCabinet(7201L, PRODUCT_KEY, cabinetNo,
                newDevice().deviceId(), 2, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("仓内有电池时不允许直接停用；空仓停用后不再出现在可归还集合")
    void disableSlotRespectsOccupancy() {
        var device = newDevice();
        String cabinetNo = uniqueId("CABNO-");
        ledger.createCabinet(7201L, PRODUCT_KEY, cabinetNo, device.deviceId(), 3, null, null);
        ledger.registerBattery(cabinetNo, 1, uniqueId("BAT-"), "BAT-60V20AH", 90, new BigDecimal("25.0"),
                null, null);

        assertThatThrownBy(() -> ledger.disableSlot(cabinetNo, 1, "OPS"))
                .as("直接停用会把电池锁在里面无人知晓")
                .isInstanceOf(IllegalStateException.class);

        ledger.disableSlot(cabinetNo, 3, "OPS");
        assertThat(ledger.slots(cabinetNo).get(2).getSlotState()).isEqualTo("DISABLED");
    }

    @Test
    @DisplayName("非法仓位状态被 DB CHECK 拒绝（约束在库里，不只在校验代码里）")
    void illegalSlotStateIsRejectedByDatabase() {
        var device = newDevice();
        String cabinetNo = uniqueId("CABNO-");
        SwapCabinet cabinet = ledger.createCabinet(7201L, PRODUCT_KEY, cabinetNo, device.deviceId(), 2,
                null, null);

        assertThatThrownBy(() -> jdbc.update("UPDATE swap_slot SET slot_state = 'NOT_A_STATE' "
                + "WHERE cabinet_id = ? AND slot_no = 1", cabinet.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
