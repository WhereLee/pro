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
        List<DeviceLink> links = new ArrayList<>();
        for (int i = 1; i <= options.count; i++) {
            String deviceId = options.prefix + String.format("%04d", i);
            CabinetDevice cabinet = new CabinetDevice(deviceId, options.slotCount, 30.0);
            FaultPolicy faults = new FaultPolicy(options.seed);
            DeviceLink link = new DeviceLink(options.host, options.port, options.productKey, deviceId,
                    options.masterSecret, cabinet, faults);
            link.connect();
            links.add(link);
            System.out.println("[sim] 已连接 " + deviceId + " sessionId=" + link.sessionId());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> links.forEach(DeviceLink::close)));
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

    record Options(String host, int port, String productKey, String prefix, String masterSecret, int count,
                   int slotCount, long seed, int runSeconds) {

        static Options parse(String[] args) {
            String host = "127.0.0.1";
            int port = 1883;
            String productKey = "SWAP-CAB-8";
            String prefix = "CAB";
            String secret = "dev-only-iot-device-master-key-change-me";
            int count = 1;
            int slotCount = 8;
            long seed = 42L;
            int runSeconds = 60;
            for (int i = 0; i < args.length - 1; i++) {
                switch (args[i]) {
                    case "--host" -> host = args[++i];
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--product" -> productKey = args[++i];
                    case "--prefix" -> prefix = args[++i];
                    case "--secret" -> secret = args[++i];
                    case "--count" -> count = Integer.parseInt(args[++i]);
                    case "--slots" -> slotCount = Integer.parseInt(args[++i]);
                    case "--seed" -> seed = Long.parseLong(args[++i]);
                    case "--run-seconds" -> runSeconds = Integer.parseInt(args[++i]);
                    default -> {
                        // 未知参数忽略，便于脚本向前兼容
                    }
                }
            }
            return new Options(host, port, productKey, prefix, secret, count, slotCount, seed, runSeconds);
        }
    }
}
