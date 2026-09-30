package com.lrs.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrs.sim.device.CabinetDevice;
import com.lrs.sim.fault.FaultPolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * 虚拟设备入口。
 *
 * 用法：java -jar buddy-sim.jar --host 127.0.0.1 --port 1883 --prefix CAB --count 3 --secret <主密钥>
 *
 * 为什么 CLI 与可被测的库接口并存：手工演示时要"起一台柜机跟着后台点"，
 * 而 M3 的故障矩阵与压测需要程序化编排 —— 只给 CLI 会逼测试去 fork 进程，
 * 只给库接口则演示时只能靠单测跑。
 */
public final class SwapSimApp {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SwapSimApp() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.listScenarios) {
            com.lrs.sim.scenario.Scenario.listAvailable().forEach(p -> System.out.println(p));
            return;
        }
        if (options.scenario != null) {
            System.exit(runScenario(options));
            return;
        }
        List<DeviceLink> links = new ArrayList<>();
        java.util.Map<String, DeviceLink> byId = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= options.count; i++) {
            // --device 是精确设备号（跨进程联跑用一个已开通的设备）；--prefix 是批量压测形态。
            // 两者必须分开：把前缀当设备号用，拼出来的 ID 与台账里的永远对不上，
            // 而失败形状只是“设备连上了但永远收不到自己的指令”，极难定位。
            String deviceId = options.deviceId != null ? options.deviceId
                    : options.prefix + String.format("%04d", i);
            CabinetDevice cabinet = new CabinetDevice(deviceId, options.slotCount, 30.0);
            if (options.offerBattery != null && options.offerSlot > 0) {
                // 把云侧已分配的那块满电电池放进内存模型的对应仓：
                // 否则柜机“取走”的是一个自己编的码，云侧身份校验会判不一致（那是正确行为，不是联跑目标）
                cabinet.insert(options.offerSlot, options.offerBattery, 98, 27.0);
            }
            FaultPolicy faults = new FaultPolicy(options.seed);
            DeviceLink link = new DeviceLink(options.host, options.port, options.productKey, deviceId,
                    options.masterSecret, cabinet, faults);
            if (options.autoSwap) {
                link.enableAutoSwap(options.oldBattery, options.oldSoc, options.offerBattery, options.swapDelayMs);
            }
            link.connect();
            links.add(link);
            byId.put(deviceId, link);
            System.out.println("[sim] 已连接 " + deviceId + " sessionId=" + link.sessionId()
                    + (options.autoSwap ? " autoSwap=on" : ""));
        }
        // 控制面只听 127.0.0.1：这个进程能伪造报文，开在对外接口上等于发布一个攻击工具
        SimControlServer control = null;
        if (options.controlPort > 0) {
            control = new SimControlServer(options.controlPort, byId);
            control.start();
            System.out.println("[sim] 控制面 http://127.0.0.1:" + control.port()
                    + "/status （devices=" + byId.size() + "，多设备时必须带 ?device=）");
        }
        final SimControlServer controlRef = control;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (controlRef != null) {
                controlRef.close();
            }
            links.forEach(DeviceLink::close);
        }));
        long deadline = System.currentTimeMillis() + options.runSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            for (DeviceLink link : links) {
                link.cabinet().tickCharge(1);
                ObjectNode props = MAPPER.createObjectNode();
                props.put("cabinetTemp", link.cabinet().cabinetTemp());
                props.put("power", 1.2);
                link.publishTelemetry(props);
            }
            Thread.sleep(2000);
        }
        links.forEach(DeviceLink::close);
    }

    /**
     * 场景模式：跑一份场景文件，逐行报结果，退出码 0/1。
     *
     * 为什么不让它常驻：CI 要的是"这个场景现在过不过"，常驻就得有人判超时；
     * 而失败时必须把**每一步的期望与实际**打到 stdout，只回一句"failed"的脚本等于没跑。
     */
    private static int runScenario(Options options) throws Exception {
        com.lrs.sim.scenario.Scenario scenario = com.lrs.sim.scenario.Scenario.readAny(options.scenario);
        long seed = scenario.seed();
        String deviceId = options.deviceId != null ? options.deviceId : options.prefix + "0001";
        CabinetDevice cabinet = new CabinetDevice(deviceId, options.slotCount, 30.0);
        if (options.offerBattery != null && options.offerSlot > 0) {
            cabinet.insert(options.offerSlot, options.offerBattery, 98, 27.0);
        }
        DeviceLink device = new DeviceLink(options.host, options.port, options.productKey, deviceId,
                options.masterSecret, cabinet, new FaultPolicy(seed));
        SimCloudDriver cloud = new SimCloudDriver(options.host, options.port, options.productKey, deviceId,
                options.masterSecret);
        cloud.connect();
        try {
            System.out.println("[scenario] " + scenario.name() + " seed=" + seed + "（场景内的 seed 覆盖进程参数）");
            System.out.println("[scenario] " + scenario.description());
            com.lrs.sim.scenario.ScenarioRunner runner = new com.lrs.sim.scenario.ScenarioRunner(device, cloud);
            int failed = 0;
            for (com.lrs.sim.scenario.ScenarioRunner.StepResult step : runner.run(scenario)) {
                System.out.printf("  [%s] #%02d %-16s %s%n", step.ok() ? "PASS" : "FAIL", step.index(),
                        step.doWhat(), step.detail());
                if (!step.ok()) {
                    failed++;
                }
            }
            System.out.println("[scenario] " + scenario.name() + " 结果："
                    + (failed == 0 ? "全部通过" : failed + " 步失败")
                    + "（设备计数 收=" + device.commandsReceived() + " 应=" + device.repliesSent()
                    + " 事件=" + device.eventsSent() + " 拒=" + device.rejectedInbound() + "）");
            return failed == 0 ? 0 : 1;
        } finally {
            cloud.close();
            device.close();
        }
    }

    record Options(String host, int port, String productKey, String prefix, String deviceId, String masterSecret,
                   int count,
                   int slotCount, long seed, int runSeconds, boolean autoSwap, String oldBattery, int oldSoc,
                   String offerBattery, int offerSlot, long swapDelayMs, int controlPort, String scenario,
                   boolean listScenarios) {

        static Options parse(String[] args) {
            String host = "127.0.0.1";
            int port = 1883;
            String productKey = "SWAP-CAB-8";
            String prefix = "CAB";
            String deviceId = null;
            String secret = "dev-only-iot-device-master-key-change-me";
            int count = 1;
            int slotCount = 8;
            long seed = 42L;
            int runSeconds = 60;
            boolean autoSwap = false;
            String oldBattery = "BAT-USER-1";
            int oldSoc = 30;
            String offerBattery = null;
            int offerSlot = 0;
            long swapDelayMs = 1500L;
            int controlPort = 0;
            String scenario = null;
            boolean listScenarios = false;
            // 边界必须是 args.length：原来写成 length-1 会让**末位参数永远读不到**，
            // 于是 `... --auto-swap` 写在最后时静默失效（症状是“模拟器不自动动作”，难查）。
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = args[++i];
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--product" -> productKey = args[++i];
                    case "--prefix" -> prefix = args[++i];
                    case "--device" -> deviceId = args[++i];
                    case "--secret" -> secret = args[++i];
                    case "--count" -> count = Integer.parseInt(args[++i]);
                    case "--slots" -> slotCount = Integer.parseInt(args[++i]);
                    case "--seed" -> seed = Long.parseLong(args[++i]);
                    case "--run-seconds" -> runSeconds = Integer.parseInt(args[++i]);
                    case "--auto-swap" -> autoSwap = true;
                    case "--old-battery" -> oldBattery = args[++i];
                    case "--old-soc" -> oldSoc = Integer.parseInt(args[++i]);
                    case "--offer-battery" -> offerBattery = args[++i];
                    case "--offer-slot" -> offerSlot = Integer.parseInt(args[++i]);
                    case "--swap-delay-ms" -> swapDelayMs = Long.parseLong(args[++i]);
                    case "--control-port" -> controlPort = Integer.parseInt(args[++i]);
                    case "--scenario" -> scenario = args[++i];
                    case "--list-scenarios" -> listScenarios = true;
                    default -> {
                        // 未知参数忽略，便于脚本向前兼容
                    }
                }
            }
            return new Options(host, port, productKey, prefix, deviceId, secret, count, slotCount, seed, runSeconds,
                    autoSwap, oldBattery, oldSoc, offerBattery, offerSlot, swapDelayMs, controlPort, scenario,
                    listScenarios);
        }
    }
}
