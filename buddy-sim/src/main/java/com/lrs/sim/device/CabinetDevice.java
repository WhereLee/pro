package com.lrs.sim.device;

import java.util.ArrayList;
import java.util.List;

/**
 * 柜机内部模型：仓位、门磁、电控锁、充电器与电池。
 *
 * 仿的是**协议与状态语义**，不仿热/机/电物理效应（swap-simulator.md §5 正确性边界）：
 * 温度、SOC 都是可注入量，不由充电时长推导。仿真物理效应只会给出虚假的安全感。
 *
 * 状态迁移刻意做得严格：门没关就不能锁定、锁定中不能开门 ——
 * 这些约束若放宽，故障注入用例就会"总能成功"，测不出任何东西。
 */
public class CabinetDevice {

    public enum Door { CLOSED, OPEN, UNKNOWN, FAULT }
    public enum Lock { LOCKED, UNLOCKED, FAULT }
    public enum Charge { IDLE, CHARGING, FULL, FAULT, STOPPED }

    public static class Slot {
        public final int no;
        public Door door = Door.CLOSED;
        public Lock lock = Lock.LOCKED;
        public Charge charge = Charge.IDLE;
        public String batteryCode;
        public int soc;
        public double tempCelsius;

        public Slot(int no) {
            this.no = no;
        }

        public boolean occupied() {
            return batteryCode != null;
        }
    }

    public record Battery(String code, int soc, double soh, int cycles, double tempCelsius, String faultCode) {
    }

    private final String deviceId;
    private final List<Slot> slots = new ArrayList<>();
    private double cabinetTemp;
    private boolean smoke;

    public CabinetDevice(String deviceId, int slotCount, double cabinetTemp) {
        this.deviceId = deviceId;
        this.cabinetTemp = cabinetTemp;
        for (int i = 1; i <= slotCount; i++) {
            slots.add(new Slot(i));
        }
    }

    public String deviceId() {
        return deviceId;
    }

    public List<Slot> slots() {
        return List.copyOf(slots);
    }

    public Slot slot(int no) {
        Slot found = slots.stream().filter(slot -> slot.no == no).findFirst().orElse(null);
        if (found == null) {
            throw new IllegalArgumentException("仓位不存在：" + no);
        }
        return found;
    }

    /** 注：返回 true 才表示动作真的发生了；false 表示被物理前置条件挡住（对应 E3*）。 */
    public boolean openDoor(int no) {
        Slot target = slot(no);
        if (target.door == Door.FAULT || target.lock == Lock.FAULT) {
            return false;
        }
        target.lock = Lock.UNLOCKED;
        target.door = Door.OPEN;
        return true;
    }

    public boolean closeDoor(int no) {
        Slot target = slot(no);
        if (target.door == Door.FAULT) {
            return false;
        }
        target.door = Door.CLOSED;
        target.lock = Lock.LOCKED;
        return true;
    }

    /** 用户把电池投进已开的仓位。 */
    public Battery insert(int no, String code, int soc, double temp) {
        Slot target = slot(no);
        target.batteryCode = code;
        target.soc = soc;
        target.tempCelsius = temp;
        target.charge = Charge.CHARGING;
        return new Battery(code, soc, 98.0, 12, temp, null);
    }

    /** 用户从仓位取走电池（门状态不变，取走与关门是两件事）。 */
    public Battery take(int no) {
        Slot target = slot(no);
        if (!target.occupied()) {
            return null;
        }
        Battery battery = new Battery(target.batteryCode, target.soc, 98.0, 12, target.tempCelsius, null);
        target.batteryCode = null;
        target.soc = 0;
        target.charge = Charge.IDLE;
        return battery;
    }

    /** 时间推进：SOC 线性爬升。注意这是**状态推进脚本**，不是热模型。 */
    public void tickCharge(int minutes) {
        for (Slot target : slots) {
            if (target.charge == Charge.CHARGING) {
                target.soc = Math.min(100, target.soc + minutes);
                if (target.soc >= 100) {
                    target.charge = Charge.FULL;
                }
            }
        }
    }

    public void injectCabinetTemp(double value) {
        this.cabinetTemp = value;
    }

    public void injectSmoke(boolean value) {
        this.smoke = value;
    }

    public double cabinetTemp() {
        return cabinetTemp;
    }

    public boolean smoke() {
        return smoke;
    }

    /** 断充：安全联动要求即使云侧不可用也必须发生。 */
    public List<Integer> stopAllCharging() {
        List<Integer> stopped = new ArrayList<>();
        for (Slot target : slots) {
            if (target.charge == Charge.CHARGING) {
                target.charge = Charge.STOPPED;
                stopped.add(target.no);
            }
        }
        return stopped;
    }

    public void jamDoor(int no) {
        slot(no).door = Door.FAULT;
    }

    public void jamLock(int no) {
        slot(no).lock = Lock.FAULT;
    }
}
