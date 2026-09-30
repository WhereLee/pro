package com.lrs.sim.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrs.sim.DeviceLink;
import com.lrs.sim.SimCloudDriver;
import com.lrs.sim.fault.FaultPolicy;
import com.lrs.sim.protocol.SimProtocol;

import java.util.ArrayList;
import java.util.List;

/**
 * 场景解释器：按顺序跑线性步骤，把断言结果收集成一张表。
 *
 * 三条实现约定，都是为了"场景跑过的东西真的被跑了"：
 * <ol>
 *   <li><b>未知步骤 = 失败</b>（解析阶段就拦，见 {@link Scenario}），不静默跳过；</li>
 *   <li><b>断言失败不抛异常中断</b>：跑完整份场景再一次性报，否则第一个失败会掩盖后面所有结论；</li>
 *   <li><b>失败信息必须带"期望 vs 实际"</b>：只说"断言失败"的编排脚本和没有脚本一样。</li>
 * </ol>
 */
public final class ScenarioRunner {

    /** 单步结果。 */
    public record StepResult(int index, String doWhat, boolean ok, String detail) {
    }

    private final DeviceLink device;
    private final SimCloudDriver cloud;
    private final List<StepResult> results = new ArrayList<>();

    public ScenarioRunner(DeviceLink device, SimCloudDriver cloud) {
        this.device = device;
        this.cloud = cloud;
    }

    public List<StepResult> run(Scenario scenario) {
        results.clear();
        // 每条场景从干净的观测窗口开始：上一条留下的报文会让这一条的计数莫名多一
        cloud.clearReceived();
        int index = 0;
        for (JsonNode step : scenario.steps()) {
            String doWhat = step.path("do").asText();
            try {
                execute(index, doWhat, step);
            } catch (RuntimeException e) {
                results.add(new StepResult(index, doWhat, false, "步骤执行抛异常：" + e));
            }
            index++;
        }
        return List.copyOf(results);
    }

    public boolean allPassed() {
        return results.stream().allMatch(StepResult::ok);
    }

    public List<StepResult> results() {
        return List.copyOf(results);
    }

    private void execute(int index, String doWhat, JsonNode step) {
        switch (doWhat) {
            case "fault" -> {
                FaultPolicy.Trigger trigger = new FaultPolicy.Trigger(
                        FaultPolicy.Kind.valueOf(step.path("kind").asText()),
                        step.hasNonNull("cmdCode") ? step.path("cmdCode").asText() : null,
                        step.path("afterStep").asInt(0),
                        step.path("repeatTimes").asInt(1),
                        step.hasNonNull("errorCode") ? step.path("errorCode").asText() : null,
                        step.path("times").asInt(0),
                        step.hasNonNull("value") ? step.path("value").asText() : null);
                device.faults().add(trigger);
                ok(index, doWhat, "已注入 " + trigger.kind() + " cmd=" + trigger.cmdCode());
            }
            case "send" -> {
                String msgId = cloud.sendCommand(step.path("cmd").asText(), data(step),
                        step.path("ttl").asInt(30), step.path("critical").asBoolean(false));
                ok(index, doWhat, "已下发 " + step.path("cmd").asText() + " msgId=" + msgId);
            }
            case "sendExpired" -> {
                String msgId = cloud.sendExpired(step.path("cmd").asText(), data(step),
                        step.path("expiredSeconds").asInt(120));
                ok(index, doWhat, "已下发过期指令 " + step.path("cmd").asText() + " msgId=" + msgId);
            }
            case "sendBadSign" -> {
                String msgId = cloud.sendBadSign(step.path("cmd").asText(), data(step));
                ok(index, doWhat, "已下发错签指令 " + step.path("cmd").asText() + " msgId=" + msgId);
            }
            case "sendReplayNonce" -> {
                String msgId = cloud.sendReusingNonce(step.path("cmd").asText(), data(step));
                ok(index, doWhat, "已下发重放报文（旧 nonce + 新 msgId）：" + step.path("cmd").asText()
                        + " msgId=" + msgId);
            }
            case "resend" -> {
                cloud.resend(step.path("cmd").asText());
                ok(index, doWhat, "已原样重发同 msgId 的报文（FI-02 形状）：" + step.path("cmd").asText());
            }
            case "action" -> {
                runAction(step);
                ok(index, doWhat, "已执行 " + step.path("action").asText());
            }
            case "link" -> {
                String op = step.path("op").asText();
                switch (op) {
                    case "connect" -> device.connect();
                    case "disconnect" -> device.close();
                    case "reconnect" -> device.reconnect();
                    default -> throw new IllegalArgumentException("未知 link op：" + op);
                }
                ok(index, doWhat, op + " 后 sessionId=" + device.sessionId());
            }
            case "wait" -> {
                long ms = step.path("ms").asLong(200);
                sleep(ms);
                ok(index, doWhat, "等待 " + ms + "ms");
            }
            case "log" -> ok(index, doWhat, step.path("message").asText(""));
            case "assert" -> assertStep(index, step);
            default -> throw new IllegalStateException("未知步骤：" + doWhat);
        }
    }

    private void runAction(JsonNode step) {
        String action = step.path("action").asText();
        int slotNo = step.path("slotNo").asInt(0);
        String battery = step.path("batteryCode").asText("BAT-USER-1");
        int soc = step.path("soc").asInt(30);
        switch (action) {
            case "insert" -> device.simulateUserInsert(slotNo, battery, soc);
            case "take" -> device.simulateUserTake(slotNo, battery, soc);
            case "close" -> {
                device.cabinet().closeDoor(slotNo);
                device.publishDoorClose(slotNo);
            }
            case "telemetry" -> {
                ObjectNode props = MAPPER.createObjectNode();
                props.put("cabinetTemp", step.path("cabinetTemp").asDouble(30.0));
                props.put("power", step.path("power").asDouble(1.2));
                device.publishTelemetry(props);
            }
            case "alarm" -> device.publishAlarmEvent(step.path("code").asText("TEMP_HIGH"),
                    step.path("level").asText("CRITICAL"));
            default -> throw new IllegalArgumentException("未知 action：" + action);
        }
    }

    private void assertStep(int index, JsonNode step) {
        long timeoutMs = step.path("timeoutMs").asLong(8000);
        long deadline = System.currentTimeMillis() + timeoutMs;
        List<String> failures;
        do {
            failures = checkAssertions(step);
            if (failures.isEmpty()) {
                // “缺席类断言”（absentEvents / replyCount=0）只在一个有限窗口内成立不能算数：
                // 满足后再复检一次，晚到的事件仍会被抓出来。
                sleep(step.path("recheckMs").asLong(600));
                failures = checkAssertions(step);
                break;
            }
            sleep(100);
        } while (System.currentTimeMillis() < deadline);
        // 上面这个循环是必需而不是优化：报文投递异步、CI 机器比本机慢得多，
        // “睡固定时长后看一眼”会把正常行为报成缺陷（实测 CI 34 条里红 15 条，本机全绿）。
        // 与 M3 阶段 0 修 FI-01 时是同一条纪律：副作用断言要等它发生，而不是猜时间。
        if (failures.isEmpty()) {
            ok(index, "assert", "断言通过");
        } else {
            results.add(new StepResult(index, "assert", false,
                    String.join(" | ", failures) + "（已轮询等待 " + timeoutMs + "ms）"));
        }
    }

    /** 一轮完整的断言求值（可重入：不改状态，只读观测）。 */
    private List<String> checkAssertions(JsonNode step) {
        List<String> failures = new ArrayList<>();
        String cmd = step.hasNonNull("cmd") ? step.path("cmd").asText() : null;

        if (step.hasNonNull("waitEvent")) {
            String eventType = step.path("waitEvent").asText();
            if (cloud.eventsOfType(eventType).isEmpty()) {
                failures.add("期望事件未出现：" + eventType + "；实际到达顺序=" + cloud.arrivalOrder());
            }
        }
        if (cmd != null && step.hasNonNull("replyCode")) {
            String expected = step.path("replyCode").asText();
            List<SimProtocol.Envelope> replies = cloud.replies(cmd);
            if (replies.isEmpty()) {
                failures.add("指令 " + cmd + " 没有任何应答；实际到达顺序=" + cloud.arrivalOrder());
            } else {
                String actual = replies.get(replies.size() - 1).code();
                if (!expected.equals(actual)) {
                    failures.add("应答码期望 " + expected + "，实际 " + actual);
                }
            }
        }
        // 被设备拒绝的报文不带 cmd（它不信一个验不过签的报文里的 cmd），所以只能按 code 断言
        if (step.hasNonNull("anyReplyCode")) {
            String expected = step.path("anyReplyCode").asText();
            if (!cloud.hasReplyCode(expected)) {
                failures.add("期望出现错误码应答 " + expected + "，实际收到的 code="
                        + cloud.codedReplies().stream().map(SimProtocol.Envelope::code).toList());
            }
        }
        JsonNode counts = step.path("eventCounts");
        if (counts.isObject()) {
            counts.fields().forEachRemaining(entry -> {
                String eventType = entry.getKey();
                int expected = entry.getValue().asInt();
                int actual = cloud.eventsOfType(eventType).size();
                if (expected != actual) {
                    failures.add("事件 " + eventType + " 条数期望 " + expected + "，实际 " + actual
                            + "（重放与抖动都不能让同一个事实被记多次）");
                }
            });
        }
        if (step.hasNonNull("replyCount") && cmd != null) {
            int expected = step.path("replyCount").asInt();
            int actual = cloud.replies(cmd).size();
            if (expected != actual) {
                failures.add("指令 " + cmd + " 的应答条数期望 " + expected + "，实际 " + actual);
            }
        }
        for (JsonNode present : step.path("events")) {
            String eventType = present.asText();
            if (cloud.eventsOfType(eventType).isEmpty()) {
                failures.add("期望出现的事件没有：" + eventType);
            }
        }
        for (JsonNode absent : step.path("absentEvents")) {
            String eventType = absent.asText();
            if (!cloud.eventsOfType(eventType).isEmpty()) {
                failures.add("期望不出现的事件出现了：" + eventType);
            }
        }
        if (step.path("order").isArray() && step.path("order").size() >= 2) {
            // 只比"第一次出现"的相对顺序：抖动与重发会让后续顺序本就无意义
            List<String> arrival = cloud.arrivalOrder();
            int previous = -1;
            for (JsonNode item : step.path("order")) {
                int at = arrival.indexOf(item.asText());
                if (at < 0) {
                    failures.add("顺序断言里的报文未出现：" + item.asText());
                    break;
                }
                if (previous >= 0 && at < previous) {
                    failures.add("顺序倒挂：期望先 " + step.path("order") + "，实际到达=" + arrival);
                    break;
                }
                previous = at;
            }
        }
        if (step.hasNonNull("faultFired")) {
            String needle = step.path("faultFired").asText();
            if (device.faults().fired().stream().noneMatch(f -> f.contains(needle))) {
                failures.add("注入未真的触发，期望 fired 含 \"" + needle + "\"，实际=" + device.faults().fired());
            }
        }
        if (step.hasNonNull("rejectedInboundAtLeast")) {
            long expected = step.path("rejectedInboundAtLeast").asLong();
            if (device.rejectedInbound() < expected) {
                failures.add("设备侧拒绝条数期望至少 " + expected + "，实际 " + device.rejectedInbound());
            }
        }

        return failures;
    }

    private void ok(int index, String doWhat, String detail) {
        results.add(new StepResult(index, doWhat, true, detail));
    }

    /** 等时长，但不能把中断当成可以忽略：恢复标志位后继续跑完余下步骤。 */
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static ObjectNode data(JsonNode step) {
        ObjectNode out = MAPPER.createObjectNode();
        JsonNode payload = step.path("data");
        if (payload.isObject()) {
            payload.fields().forEachRemaining(e -> out.set(e.getKey(), e.getValue()));
        }
        if (step.hasNonNull("slotNo") && !out.has("slotNo")) {
            out.put("slotNo", step.path("slotNo").asInt());
        }
        return out;
    }
}
