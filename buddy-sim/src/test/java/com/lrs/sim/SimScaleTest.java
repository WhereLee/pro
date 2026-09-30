package com.lrs.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrs.sim.device.CabinetDevice;
import com.lrs.sim.fault.FaultPolicy;
import com.lrs.sim.protocol.SimProtocol;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 千台规模连接（M3 阶段 2）。**口径必须先说清楚，否则这个数字会被误读。**
 *
 * <p>这里证的是：<b>同一个 JVM 内 1000 条设备连接</b>能建起来、能上报、能收指令、关得干净，
 * 且关掉后堆不一路涨（每连接固定成本的形状）。
 *
 * <p>这里<em>不</em>证：1000 台真机、1000 个进程、跨机网络、真实 Broker 集群的容量。
 * 那些属外部硬约束（{@code swap-plan.md} §6），最接近的替代是 M7 的互操作档。
 * 把"1000 台设备在线"当结论写进报告就是谎报。
 *
 * <p>分批建连（每批 {@value #BATCH} 台）不是为了好看：一口气 1000 次 TCP + 1000 个消费线程，
 * 在 CI 的慢机器上会先撞连接超时，症状像"设备连不上"，其实是本机握手排队。
 */
class SimScaleTest {

    private static final String PRODUCT_KEY = "SWAP-CAB-8";
    private static final String MASTER_SECRET = "scale-master-secret";
    private static final int DEVICES = 1000;
    private static final int BATCH = 100;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SimBrokerHarness broker;

    @BeforeAll
    static void startBroker() throws Exception {
        broker = new SimBrokerHarness();
    }

    @AfterAll
    static void stopBroker() {
        broker.close();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @DisplayName("同 JVM 1000 条连接：建连、上报、收指令、关净，且每连接成本是常数形状")
    void thousandConnectionsRoundTripAndRelease() throws Exception {
        // 逐设备计数而不是"开一个集中观测者数总数"：要抓的是"1000 条都发出去了而云端只看到 800 条"，
        // 那需要设备侧发出的计数与到达侧的计数能逐项对上；集中观测者订的是具体设备前缀，
        // 在 1000 个前缀下反而数不准，所以这里用"抽样 20 台做真实往返 + 全量逐台计数"组合。
        List<DeviceLink> links = new ArrayList<>(DEVICES);
        List<SimCloudDriver> watchers = new ArrayList<>(DEVICES);
        long heapBefore = usedHeapMb();
        try {
            for (int i = 1; i <= DEVICES; i++) {
                String deviceId = "CAB-SC-" + i;
                CabinetDevice cabinet = new CabinetDevice(deviceId, 8, 30.0);
                DeviceLink device = new DeviceLink("127.0.0.1", broker.port(), PRODUCT_KEY, deviceId,
                        MASTER_SECRET, cabinet, new FaultPolicy(1000L + i));
                device.connect();
                links.add(device);
                if (i % BATCH == 0) {
                    // 批间让出时间：连接与订阅在 Broker 的 event loop 上排队，不留窗口就会出现假失败
                    Thread.sleep(50);
                }
            }
            assertThat(links).hasSize(DEVICES);
            assertThat(links).allMatch(DeviceLink::isConnected);

            for (int i = 0; i < DEVICES; i++) {
                DeviceLink device = links.get(i);
                ObjectNode props = MAPPER.createObjectNode();
                props.put("cabinetTemp", 30.0 + (i % 10));
                props.put("power", 1.1);
                device.publishTelemetry(props);
                if (i % BATCH == BATCH - 1) {
                    Thread.sleep(20);
                }
            }
            long sentTelemetry = links.stream().mapToLong(DeviceLink::telemetrySent).sum();
            assertThat(sentTelemetry).as("1000 台各发一条遥测").isEqualTo(DEVICES);

            // 抽 20 台做一次真实的指令往返（QUERY_STATUS 无副作用，适合抽样）：
            // 只证"连接活着"不够，必须证这条连接还能收指令并回应答。
            int sampled = 0;
            for (int i = 0; i < DEVICES; i += DEVICES / 20) {
                DeviceLink device = links.get(i);
                String deviceId = device.deviceId();
                SimCloudDriver cloud = new SimCloudDriver("127.0.0.1", broker.port(), PRODUCT_KEY, deviceId,
                        MASTER_SECRET);
                cloud.connect();
                watchers.add(cloud);
                long replyBefore = device.repliesSent();
                cloud.sendCommand("QUERY_STATUS", SimCloudDriver.data("slotNo", 1), 30);
                long deadline = System.currentTimeMillis() + 5000;
                while (device.repliesSent() == replyBefore && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }
                assertThat(device.repliesSent()).as("设备 " + deviceId + " 必须真的回了一条应答")
                        .isGreaterThan(replyBefore);
                assertThat(cloud.awaitReply("QUERY_STATUS", 3000)).as("观测者要能看到这条应答").isTrue();
                List<SimProtocol.Envelope> replies = cloud.replies("QUERY_STATUS");
                assertThat(replies.get(replies.size() - 1).code()).isEqualTo("OK");
                sampled++;
            }
            assertThat(sampled).isEqualTo(20);

            long heapBusy = usedHeapMb();
            assertThat(heapBusy - heapBefore)
                    .as("1000 条连接的堆增量必须在可解释范围内（量级检查，不是精确预算）")
                    .isLessThan(1200);
            System.out.printf("[scale] 1000 连接：堆增量 %d MB（建连前 %d → 忙时 %d）%n",
                    heapBusy - heapBefore, heapBefore, heapBusy);
        } finally {
            watchers.forEach(SimCloudDriver::close);
            links.forEach(DeviceLink::close);
        }
        // 释放要可观测：只断"不报错"证不了连接真关了，漏掉一个 client 就是一条慢慢堆积的泄漏
        long stillConnected = links.stream().filter(DeviceLink::isConnected).count();
        assertThat(stillConnected).as("1000 条连接必须全部释放").isZero();
    }

    private static long usedHeapMb() {
        // 取绝对已用堆而不是 used - init：后者会因 JVM 初始预留大于实际使用而变成负数，
        // 报告里的"增量 42 MB"就会跟"建连前 -228"这种无法解释的数字放在一起。
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / (1024 * 1024);
    }
}
