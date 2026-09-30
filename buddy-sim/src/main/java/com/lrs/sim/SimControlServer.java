package com.lrs.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrs.sim.device.CabinetDevice;
import com.lrs.sim.fault.FaultPolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 模拟器的最小 HTTP 控制面（JDK {@code HttpServer}，不引任何 Web 依赖）。
 *
 * 为什么必须有它：跨进程联跑与 FI 矩阵需要**在运行中**下单——"重启进程换一个故障参数"
 * 会让每个场景都付一次连接/注册/会话建立的代价，而且现场一旦重启就再也复现不出前一次的时序。
 *
 * 五个端点，职责互不重叠：
 * <ul>
 *   <li>{@code GET  /status}   —— 会话、仓位快照、已注入故障、已触发记录、四个计数器；</li>
 *   <li>{@code POST /fault}    —— 追加一个故障触发器（精确编排，见 {@link FaultPolicy}）；</li>
 *   <li>{@code POST /action}   —— 代用户执行物理动作（投入 / 取走 / 关门 / 上报）；</li>
 *   <li>{@code POST /link}     —— 断线与重连（FI-10 与混沌场景的开关）；</li>
 *   <li>{@code GET  /metrics}  —— Prometheus 文本格式的设备侧计数（双端归因的另一端）。</li>
 * </ul>
 *
 * 安全边界：本服务**只听 127.0.0.1**，且产物绝不进生产镜像（见 pom 注释）。
 * 它能伪造报文、错签、重放——这是测试设备需要的能力，放到可被外部访问的端口上就是攻击工具。
 */
public final class SimControlServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final Map<String, DeviceLink> devices;

    public SimControlServer(int port, Map<String, DeviceLink> devices) throws IOException {
        this.devices = devices;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        server.createContext("/status", this::status);
        server.createContext("/fault", this::fault);
        server.createContext("/action", this::action);
        server.createContext("/link", this::link);
        server.createContext("/metrics", this::metrics);
        server.setExecutor(null);
    }

    /** @return 实际监听端口（传 0 时由系统分配，测试就靠这个避免端口冲突） */
    public int port() {
        return server.getAddress().getPort();
    }

    public void start() {
        server.start();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---------------- 端点 ----------------

    private void status(HttpExchange exchange) throws IOException {
        DeviceLink link = pick(exchange);
        if (link == null) {
            reply(exchange, 404, "{\"error\":\"device not found\"}");
            return;
        }
        ObjectNode json = MAPPER.createObjectNode();
        json.put("deviceId", link.deviceId());
        json.put("productKey", link.productKey());
        json.put("sessionId", link.sessionId());
        json.put("previousSessionId", link.previousSessionId());
        json.put("connected", link.isConnected());
        json.put("commandsReceived", link.commandsReceived());
        json.put("repliesSent", link.repliesSent());
        json.put("eventsSent", link.eventsSent());
        json.put("telemetrySent", link.telemetrySent());
        json.put("rejectedInbound", link.rejectedInbound());
        json.put("cabinetTemp", link.cabinet().cabinetTemp());
        ArrayNode slots = json.putArray("slots");
        for (CabinetDevice.Slot slot : link.cabinet().slots()) {
            ObjectNode item = slots.addObject();
            item.put("no", slot.no);
            item.put("door", String.valueOf(slot.door));
            item.put("lock", String.valueOf(slot.lock));
            item.put("charge", String.valueOf(slot.charge));
            item.put("soc", slot.soc);
            item.put("batteryCode", slot.batteryCode);
        }
        ArrayNode injected = json.putArray("faultsInjected");
        link.faults().triggers().forEach(t -> injected.add(String.valueOf(t)));
        ArrayNode fired = json.putArray("faultFired");
        link.faults().fired().forEach(fired::add);
        reply(exchange, 200, MAPPER.writeValueAsString(json));
    }

    private void fault(HttpExchange exchange) throws IOException {
        DeviceLink link = pick(exchange);
        if (link == null) {
            reply(exchange, 404, "{\"error\":\"device not found\"}");
            return;
        }
        ObjectNode body = readBody(exchange);
        String kind = body.path("kind").asText(null);
        if (kind == null) {
            reply(exchange, 400, "{\"error\":\"kind 必填\"}");
            return;
        }
        FaultPolicy.Trigger trigger = new FaultPolicy.Trigger(
                FaultPolicy.Kind.valueOf(kind),
                body.hasNonNull("cmdCode") ? body.path("cmdCode").asText() : null,
                body.path("afterStep").asInt(0),
                body.path("repeatTimes").asInt(1),
                body.hasNonNull("errorCode") ? body.path("errorCode").asText() : null,
                body.path("times").asInt(0),
                body.hasNonNull("value") ? body.path("value").asText() : null);
        link.faults().add(trigger);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("added", trigger.toString());
        out.put("totalTriggers", link.faults().triggerCount());
        reply(exchange, 200, MAPPER.writeValueAsString(out));
    }

    private void action(HttpExchange exchange) throws IOException {
        DeviceLink link = pick(exchange);
        if (link == null) {
            reply(exchange, 404, "{\"error\":\"device not found\"}");
            return;
        }
        ObjectNode body = readBody(exchange);
        String type = body.path("action").asText(null);
        int slotNo = body.path("slotNo").asInt(0);
        if (type == null || slotNo <= 0) {
            reply(exchange, 400, "{\"error\":\"action 与 slotNo 必填\"}");
            return;
        }
        String battery = body.path("batteryCode").asText("BAT-USER-1");
        int soc = body.path("soc").asInt(30);
        switch (type) {
            case "insert" -> link.simulateUserInsert(slotNo, battery, soc);
            case "take" -> link.simulateUserTake(slotNo, battery, soc);
            case "close" -> {
                link.cabinet().closeDoor(slotNo);
                link.publishDoorClose(slotNo);
            }
            case "telemetry" -> {
                ObjectNode props = MAPPER.createObjectNode();
                body.withObject("/props").fields().forEachRemaining(e -> props.put(e.getKey(), e.getValue().asDouble()));
                link.publishTelemetry(props);
            }
            case "alarm" -> link.publishAlarmEvent(body.path("code").asText("TEMP_HIGH"),
                    body.path("level").asText("CRITICAL"));
            default -> {
                reply(exchange, 400, "{\"error\":\"未知 action：" + type + "\"}");
                return;
            }
        }
        reply(exchange, 200, "{\"ok\":true,\"action\":\"" + type + "\",\"slotNo\":" + slotNo + "}");
    }

    private void link(HttpExchange exchange) throws IOException {
        DeviceLink device = pick(exchange);
        if (device == null) {
            reply(exchange, 404, "{\"error\":\"device not found\"}");
            return;
        }
        String op = readBody(exchange).path("op").asText(null);
        if ("reconnect".equals(op)) {
            device.reconnect();
        } else if ("disconnect".equals(op)) {
            device.close();
        } else if ("connect".equals(op)) {
            device.connect();
        } else {
            reply(exchange, 400, "{\"error\":\"op 必须是 reconnect|disconnect|connect\"}");
            return;
        }
        reply(exchange, 200, "{\"ok\":true,\"sessionId\":\"" + device.sessionId()
                + "\",\"previousSessionId\":\"" + device.previousSessionId() + "\"}");
    }

    /**
     * Prometheus 文本 Exposition。
     *
     * 手写而不引 micrometer：本工程刻意零框架依赖（模拟器要能独立于云侧启动），
     * 而这里只有 5 个 counter，命名与云侧一致（前缀 {@code sim_}）以便在同一个 Prometheus 里做双端归因。
     */
    private void metrics(HttpExchange exchange) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("# HELP sim_device_commands_received 设备收到的下行指令条数\n")
                .append("# TYPE sim_device_commands_received counter\n");
        appendCounter(text, "sim_device_commands_received", link -> String.valueOf(link.commandsReceived()));
        text.append("# HELP sim_device_replies_sent 设备发出的应答条数（含重发与重放）\n")
                .append("# TYPE sim_device_replies_sent counter\n");
        appendCounter(text, "sim_device_replies_sent", link -> String.valueOf(link.repliesSent()));
        text.append("# HELP sim_device_events_sent 设备发出的物理事件条数\n")
                .append("# TYPE sim_device_events_sent counter\n");
        appendCounter(text, "sim_device_events_sent", link -> String.valueOf(link.eventsSent()));
        text.append("# HELP sim_device_telemetry_sent 设备发出的遥测条数\n")
                .append("# TYPE sim_device_telemetry_sent counter\n");
        appendCounter(text, "sim_device_telemetry_sent", link -> String.valueOf(link.telemetrySent()));
        text.append("# HELP sim_device_inbound_rejected 设备按校验链拒绝的指令条数（错签/重放/过期/未知）\n")
                .append("# TYPE sim_device_inbound_rejected counter\n");
        appendCounter(text, "sim_device_inbound_rejected", link -> String.valueOf(link.rejectedInbound()));
        text.append("# HELP sim_device_fault_fired 已触发的故障注入条数\n")
                .append("# TYPE sim_device_fault_fired counter\n");
        appendCounter(text, "sim_device_fault_fired", link -> String.valueOf(link.faults().fired().size()));
        reply(exchange, 200, text.toString());
    }

    private void appendCounter(StringBuilder text, String name, java.util.function.Function<DeviceLink, String> value) {
        devices.values().forEach(link -> text.append(name)
                .append("{device=\"").append(link.deviceId()).append("\"} ")
                .append(value.apply(link)).append('\n'));
    }

    // ---------------- 小工具 ----------------

    /** 单设备进程可以不传 device；多设备时必须显式给，否则"给哪台下的注入"就成了猜。 */
    private DeviceLink pick(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        String wanted = null;
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.startsWith("device=")) {
                    wanted = pair.substring("device=".length());
                }
            }
        }
        if (wanted != null) {
            return devices.get(wanted);
        }
        return devices.size() == 1 ? devices.values().iterator().next() : null;
    }

    private ObjectNode readBody(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (raw.isBlank()) {
            return MAPPER.createObjectNode();
        }
        return (ObjectNode) MAPPER.readTree(raw);
    }

    private void reply(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type",
                code == 200 && body.startsWith("#") ? "text/plain; version=0.0.4" : "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 便于 CLI 与测试直接构造：一台设备也走同一个入口。 */
    public static SimControlServer forDevice(int port, DeviceLink link) throws IOException {
        return new SimControlServer(port, Map.of(link.deviceId(), link));
    }

    static List<String> deviceIds(Map<String, DeviceLink> links) {
        return List.copyOf(links.keySet());
    }
}
