package com.lrs.sim;

import com.lrs.sim.device.CabinetDevice;
import com.lrs.sim.fault.FaultPolicy;
import com.lrs.sim.protocol.SimProtocol;
import com.lrs.sim.scenario.Scenario;
import com.lrs.sim.scenario.ScenarioRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FI 矩阵（设备侧 L2）：每一份 {@code protocol/scenarios/*.json} 都是一条自动化用例。
 *
 * 三条设计约定：
 * <ol>
 *   <li><b>矩阵覆盖度由文件集合保证，并且有一道断言盯着它</b>（见 {@link #matrixCoversFiCatalog}）：
 *       删掉一个场景文件会让用例静默变少，而"M3 门 A = FI 每项至少一条"就悄悄失守了。</li>
 *   <li><b>断言的是设备侧行为形状</b>（发没发、几条、什么码、门开没开），
 *       云侧效果由 buddy 的 {@code SwapFiCloudEffectsTest} 断言——两边合起来才是四维。</li>
 *   <li><b>同 seed 可重复</b>：随机抖动/延迟全部吃场景里的 seed，同一条跑两次的到达顺序必须一致，
 *       否则"这次红了是不是同一个原因"没法回答。</li>
 * </ol>
 */
class SimFiMatrixTest {

    private static final String PRODUCT_KEY = "SWAP-CAB-8";
    private static final String MASTER_SECRET = "fi-matrix-master-secret";

    private static SimBrokerHarness broker;

    @BeforeAll
    static void startBroker() throws Exception {
        broker = new SimBrokerHarness();
    }

    @AfterAll
    static void stopBroker() {
        broker.close();
    }

    static List<Path> scenarioFiles() {
        List<Path> files = Scenario.listAvailable();
        assertThat(files).as("仓库根 protocol/scenarios/ 下必须至少有一条场景").isNotEmpty();
        return files;
    }

    static Stream<Path> scenarioPaths() {
        return scenarioFiles().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarioPaths")
    @DisplayName("场景每一步的断言都必须成立（不只看有没有抛异常）")
    void scenarioPassesEveryStep(Path file) throws Exception {
        Scenario scenario = Scenario.read(file);
        long lostBefore = SimBrokerHarness.lostRouteCount();
        try (Harness harness = openHarness(scenario.seed(), "FI-" + scenario.name())) {
            ScenarioRunner runner = new ScenarioRunner(harness.device(), harness.cloud());
            List<ScenarioRunner.StepResult> steps = runner.run(scenario);
            List<ScenarioRunner.StepResult> failures = steps.stream().filter(step -> !step.ok()).toList();
            assertThat(failures).as("场景 " + scenario.name() + " 有步骤失败").isEmpty();
            // 至少要有一条 assert：只有注入没有断言的场景文件是"跑过了"而不是"验过了"
            assertThat(steps).as("场景 " + scenario.name() + " 没有任何 assert 步骤")
                    .anyMatch(step -> "assert".equals(step.doWhat()));
            // 这一条把“测试替身自己把消息弄丢”和“设备没处理”分开：
            // 没有它时，harness 的路由缺失只能表现为设备 received=0，归因方完全错。
            assertThat(SimBrokerHarness.lostRouteCount() - lostBefore)
                    .as("场景 " + scenario.name() + " 有下行报文未被路由到任何订阅者（问题在 harness，不在设备侧）")
                    .isZero();
        }
    }

    @ParameterizedTest(name = "重复：{0}")
    @MethodSource("scenarioPaths")
    @DisplayName("同 seed 跑两次：每一类上行的条数必须一致")
    void scenarioIsRepeatable(Path file) throws Exception {
        Scenario scenario = Scenario.read(file);
        String first = timelineOf(scenario, 1);
        String second = timelineOf(scenario, 2);
        assertThat(second).as("场景 " + scenario.name() + " 两次运行的时间线不同，随机没有吃 seed")
                .isEqualTo(first);
    }

    /**
     * 重复性断言比的是**每一类上行各到了几条**，不是它们的交错次序。
     *
     * 为什么不比次序：应答由设备的消费线程发、用户动作事件由场景执行线程发、遥测又是一条独立主题，
     * 跨线程/跨主题的本机交错本来就不由 seed 管；拿它做断言得的是与注入无关的随机红
     * （实测：swap-happy-path 两次跑出不同交错）。
     * 为什么又不能只比"事件次序"：FI-03/06/08/15 这几条根本没有事件（被拒了或只发遥测），
     * 只比事件会让它们的重复性断言变成空断言。
     * 这不是放宽：seed 要保证的是“注入产生的报文集合可复现”；顺序类断言仍由场景里的
     * {@code order} 字段在同一个发布线程内守（door_close 先于 door_open 就是那一类）。
     */
    private String timelineOf(Scenario scenario, int round) {
        try (Harness harness = openHarness(scenario.seed(), "REPEAT-" + scenario.name() + "-" + round)) {
            ScenarioRunner runner = new ScenarioRunner(harness.device(), harness.cloud());
            assertThat(runner.run(scenario)).allMatch(ScenarioRunner.StepResult::ok);
            java.util.Map<String, Long> tally = harness.cloud().received().stream()
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.groupingBy(SimFiMatrixTest::classify,
                            java.util.TreeMap::new, java.util.stream.Collectors.counting()));
            assertThat(tally).as("第 " + round + " 轮没观测到任何上行，重复性断言就成了空断言").isNotEmpty();
            return tally.toString();
        }
    }

    private static String classify(SimProtocol.Envelope envelope) {
        if (envelope.data() != null && envelope.data().has("eventType")) {
            return "event:" + envelope.data().path("eventType").asText();
        }
        if (envelope.code() != null) {
            return "reply:" + envelope.code();
        }
        return "telemetry";
    }

    @Test
    @DisplayName("矩阵覆盖度：FI-01..FI-15 每项至少有一条场景（FI-16 属云侧，不在这里）")
    void matrixCoversFiCatalog() {
        List<String> names = scenarioFiles().stream()
                .map(p -> p.getFileName().toString().replace(".json", ""))
                .toList();
        for (int fi = 1; fi <= 15; fi++) {
            String prefix = String.format("fi%02d-", fi);
            assertThat(names).as("FI-%02d 没有任何场景文件（设备侧矩阵失守一项）".formatted(fi))
                    .anyMatch(name -> name.startsWith(prefix));
        }
    }

    @Test
    @DisplayName("FI-10：重连后旧会话应答迟到，设备必须带着旧 sessionId 把它发出去")
    void staleSessionReplyCarriesOldSessionId() {
        // 这条不能用场景 DSL 表达：断言的是报文里的 sessionId 字段与"旧会话"相等，
        // 而 DSL 的 assert 词表里没有"字段等于另一条报文的字段"这种跳报文比对——
        // 为一条用例给 DSL 加语法正是计划里风险 R4 要避免的事。
        try (Harness harness = openHarness(42L, "FI-10-STALE")) {
            // 本用例不走场景（因此没有 {"do":"link","op":"connect"} 那一步），得自己把设备连上；
            // 忘了这步的症状是"应答永远等不到"，看上去像注入失效而不是没连接。
            harness.device().connect();
            harness.device().faults().add(new FaultPolicy.Trigger(FaultPolicy.Kind.STALE_SESSION_REPLY,
                    "OPEN_SLOT", 0, 1, null));
            harness.cloud().sendCommand("OPEN_SLOT", SimCloudDriver.data("slotNo", 1), 30);
            assertThat(harness.cloud().awaitReply("OPEN_SLOT", 2000)).as("第一次开门应有应答").isTrue();

            harness.device().reconnect();
            String previous = harness.device().previousSessionId();
            assertThat(previous).as("重连必须留下上一个会话号，否则伪造不出旧会话应答").isNotNull();

            harness.cloud().clearReceived();
            harness.cloud().sendCommand("OPEN_SLOT", SimCloudDriver.data("slotNo", 2), 30);
            assertThat(harness.cloud().awaitReply("OPEN_SLOT", 2000)).isTrue();

            List<String> sessionIds = harness.cloud().replies("OPEN_SLOT").stream()
                    .map(SimProtocol.Envelope::sessionId).toList();
            assertThat(sessionIds).as("新会话应答 + 一条带旧会话号的迟到应答").contains(previous);
            assertThat(harness.device().faults().fired())
                    .anyMatch(record -> record.startsWith("staleSessionReply:OPEN_SLOT"));
        }
    }

    // ---------------- 夹具 ----------------

    private static final java.util.concurrent.atomic.AtomicInteger SLOT_TAG =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 一条场景一套独立的设备与观测者。
     *
     * 设备号必须每跑不同：同一个 clientId 连上第二个会话时 Broker 会踢掉第一个，
     * 症状是"上一段场景的报文莫名丢了"，而看上去像设备侧故障。
     * 这里刻意不 connect 设备：场景第一步 {@code {"do":"link","op":"connect"}} 就是它的。
     */
    @Test
    @DisplayName("connect() 返回后的第一条指令不得丢（设备侧发布流注册时序回归）")
    void firstCommandAfterConnectIsNeverLost() throws Exception {
        // 只写这一条就能拖住一个真缺陷：旧实现把 publishes() 注册放在消费线程里，
        // connect() 返回与线程注册之间有窗口，CI 上固定丢第一条，本机因为线程快而全绿。
        // 这里不加任何 sleep：就赌“立即发”，否则测不到窗口。
        int rounds = 40;
        for (int round = 1; round <= rounds; round++) {
            try (Harness harness = openHarness(1000L + round, "FIRST-" + round)) {
                harness.device().connect();
                harness.cloud().connect();
                harness.cloud().sendCommand("OPEN_SLOT",
                        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("slotNo", 1), 30);
                long deadline = System.currentTimeMillis() + 3000;
                while (harness.device().commandsReceived() == 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }
                assertThat(harness.device().commandsReceived())
                        .as("第 " + round + " 轮：connect() 后立即下发的第一条指令未被设备收到")
                        .isEqualTo(1);
            }
        }
    }

    private Harness openHarness(long seed, String tag) {
        String deviceId = "CAB-FI-" + tag.replaceAll("[^A-Za-z0-9]", "") + "-" + SLOT_TAG.incrementAndGet();
        CabinetDevice cabinet = new CabinetDevice(deviceId, 8, 30.0);
        DeviceLink device = new DeviceLink("127.0.0.1", broker.port(), PRODUCT_KEY, deviceId, MASTER_SECRET,
                cabinet, new FaultPolicy(seed));
        SimCloudDriver cloud = new SimCloudDriver("127.0.0.1", broker.port(), PRODUCT_KEY, deviceId, MASTER_SECRET);
        cloud.connect();
        return new Harness(device, cloud);
    }

    private record Harness(DeviceLink device, SimCloudDriver cloud) implements AutoCloseable {

        @Override
        public void close() {
            cloud.close();
            device.close();
        }
    }
}
