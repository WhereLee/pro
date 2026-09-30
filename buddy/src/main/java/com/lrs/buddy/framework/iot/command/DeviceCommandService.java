package com.lrs.buddy.framework.iot.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrs.buddy.framework.common.util.Ulids;
import com.lrs.buddy.framework.iot.config.IotProperties;
import com.lrs.buddy.framework.iot.envelope.Envelope;
import com.lrs.buddy.framework.iot.envelope.JsonPayloadCodec;
import com.lrs.buddy.framework.iot.gateway.DeviceGateway;
import com.lrs.buddy.framework.iot.repo.CommandDao;
import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao;
import com.lrs.buddy.framework.iot.security.DeviceCredentialService;
import com.lrs.buddy.framework.iot.security.DeviceSecrets;
import com.lrs.buddy.framework.iot.session.EndpointRegistry;
import com.lrs.buddy.framework.iot.transport.MqttTopics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 指令总线：落库 → 下发 → 等应答（协议 §4、§7）。
 *
 * 三条不可省的实现规则：
 * 1 **先落库再下发**。反过来（先下发再落库）时，若设备秒回而落库还没完成，
 *   应答会找不到归属指令被丢进 unmatched_event —— 表现为"偶发的指令石沉大海"。
 * 2 **超时不等于未发生**，见 {@link CommandState}。
 * 3 内存延迟任务只是快路径，正确性靠 deadline 扫描兜底（发布与崩溃会丢内存任务）。
 */
@Slf4j
@RequiredArgsConstructor
public class DeviceCommandService {

    /** 业务侧监听器：指令进入终态或需要反查时回调。 */
    public interface CommandListener {
        void onCommandStateChanged(CommandRecord record, CommandState state);
    }

    public record CommandRecord(String cmdId, String bizType, Long bizId, Integer stepNo, Long deviceRowId,
                                String cmdCode, CommandState state, String replyCode, String traceId) {
    }

    /** 下发请求（业务侧只给这些，签名与时间戳由总线统一生成）。 */
    public record Issue(String bizType, long bizId, int stepNo, long deviceRowId, String productKey,
                        String deviceId, String cmdCode, ObjectNode data, int qos, int ttlSeconds,
                        int retryMax, boolean critical, String traceId, Long operatorId, long tenantId) {
    }

    private final CommandDao commandDao;
    private final DeviceDirectoryDao deviceDao;
    private final DeviceCredentialService credentials;
    private final DeviceSecrets secrets;
    private final JsonPayloadCodec codec;
    private final ObjectMapper objectMapper;
    private final DeviceGateway gateway;
    private final EndpointRegistry endpoints;
    private final IotProperties properties;
    private final MeterRegistry registry;
    private final List<CommandListener> listeners;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory());
    private final ConcurrentHashMap<String, CommandRecord> inFlight = new ConcurrentHashMap<>();

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "iot-cmd-timer");
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 下发一条指令。返回指令记录（可能已是 NOT_CONNECTED 状态下的 CREATED）。
     *
     * 注意：这里不抛"设备离线"异常。设备离线是**业务分支**而不是异常，
     * 抛出会让调用方把"稍后重试"和"参数错误"混成同一个 catch。
     */
    public CommandRecord issue(Issue issue) {
        String cmdId = Ulids.next();
        DeviceDirectoryDao.Device device = deviceDao.findDeviceById(issue.deviceRowId());
        if (device == null) {
            throw new IllegalArgumentException("设备不存在：" + issue.deviceRowId());
        }
        long now = System.currentTimeMillis();
        long deadline = now + issue.ttlSeconds() * 1000L;
        String topic = issue.critical()
                ? MqttTopics.downCritical(issue.productKey(), issue.deviceId())
                : MqttTopics.downCommand(issue.productKey(), issue.deviceId());
        String sessionId = endpoints.sessionIdOf(issue.deviceRowId());
        Envelope envelope = buildEnvelope(issue, cmdId, device, now, deadline, sessionId);
        String payloadJson = new String(codec.encode(envelope), java.nio.charset.StandardCharsets.UTF_8);

        CommandDao.Row row = new CommandDao.Row(null, cmdId, issue.bizType(), issue.bizId(), issue.stepNo(),
                issue.deviceRowId(), issue.productKey(), topic, issue.cmdCode(), payloadJson, issue.qos(),
                CommandState.CREATED, issue.retryMax(), issue.ttlSeconds(), sessionId, issue.traceId(),
                null, deadline, null, issue.tenantId());
        Long id = commandDao.insert(row, LocalDateTime.now());

        DeviceGateway.SendResult sent = gateway.send(new DeviceGateway.SendRequest(issue.deviceRowId(),
                issue.productKey(), issue.deviceId(), topic, codec.encode(envelope), issue.qos(),
                issue.critical(), sessionId));
        CommandState state = switch (sent) {
            case SENT -> CommandState.DISPATCHED;
            // 设备未连：保持在 CREATED，由上线钩子或业务重试再下发，
            // 不能置 EXPIRED —— 置了就只能重新建指令，丢掉幂等键
            case NOT_CONNECTED -> CommandState.CREATED;
            case REJECTED -> CommandState.NACKED;
        };
        if (state != CommandState.CREATED) {
            commandDao.transition(id, CommandState.CREATED, state, LocalDateTime.now(),
                    null, null, sent.name(), null);
        }
        CommandRecord record = new CommandRecord(cmdId, issue.bizType(), issue.bizId(), issue.stepNo(),
                issue.deviceRowId(), issue.cmdCode(), state, null, issue.traceId());
        if (state == CommandState.DISPATCHED) {
            inFlight.put(cmdId, record);
            registry.counter("cmd.dispatch.total", "cmd", issue.cmdCode(), "result", "sent").increment();
            scheduleTimeout(id, cmdId, deadline);
        } else {
            notify(record, state);
        }
        return record;
    }

    /** 设备应答入口（由 CMD_REPLY 监听器调用）。 */
    public void onReply(String cmdId, String code, JsonNode replyData, String sessionId) {
        CommandDao.Row row = commandDao.findByCmdId(cmdId);
        if (row == null) {
            // 找不到归属指令：可能是别的实例发的，或应答被重放到已清理的记录
            registry.counter("cmd.reply.orphan").increment();
            log.warn("收到无归属指令的应答：cmdId={}", cmdId);
            return;
        }
        boolean ok = code == null || "OK".equalsIgnoreCase(code);
        CommandState target = ok ? CommandState.ACKED : CommandState.NACKED;
        LocalDateTime now = LocalDateTime.now();
        boolean changed = false;
        if (row.state() == CommandState.DISPATCHED || row.state() == CommandState.CREATED) {
            changed = commandDao.transition(row.id(), row.state(), target, now, code,
                    replyData == null ? null : replyData.toString(), ok ? null : code, null);
        } else if (row.state() == CommandState.TIMEOUT) {
            // 迟到的应答：纠正回 ACKED，这是"超时不等于未发生"的落地点
            changed = commandDao.transition(row.id(), CommandState.TIMEOUT, CommandState.ACKED, now, code,
                    replyData == null ? null : replyData.toString(), null, null);
            if (changed) {
                log.info("超时后收到迟到应答，指令纠正为 ACKED：cmdId={}", cmdId);
            }
        }
        if (!changed) {
            registry.counter("cmd.reply.duplicate").increment();
            return;
        }
        Timer.builder("cmd.rtt").tag("cmd", row.cmdCode()).register(registry)
                .record(System.currentTimeMillis() - (row.sentTs() == null ? System.currentTimeMillis()
                        : row.sentTs()), TimeUnit.MILLISECONDS);
        CommandRecord record = toRecord(row, target);
        inFlight.remove(cmdId);
        notify(record, target);
        if (sessionId != null && row.sessionId() != null && !sessionId.equals(row.sessionId())) {
            registry.counter("iot.session.stale").increment();
        }
    }

    /** 业务确认：物理事件已匹配（步骤完成器调用），指令才进 CONFIRMED。 */
    public void confirm(String cmdId) {
        CommandDao.Row row = commandDao.findByCmdId(cmdId);
        if (row == null) {
            return;
        }
        if (commandDao.transition(row.id(), row.state(), CommandState.CONFIRMED, LocalDateTime.now(),
                null, null, null, null)) {
            inFlight.remove(cmdId);
            notify(toRecord(row, CommandState.CONFIRMED), CommandState.CONFIRMED);
        }
    }

    /**
     * 重发前置：把旧的在途指令置 SUPERSEDED，否则唯一索引 active_step 会拒绝新指令。
     *
     * 这个顺序不能反 —— 先插新指令会抛异常，而异常信息里不含"因为你忘了置 SUPERSEDED"这类原因。
     */
    public void supersedeInFlight(String bizType, long bizId, int stepNo) {
        for (CommandDao.Row row : commandDao.findInFlightByStep(bizType, bizId, stepNo)) {
            if (commandDao.transition(row.id(), row.state(), CommandState.SUPERSEDED, LocalDateTime.now(),
                    null, null, "REISSUED", null)) {
                inFlight.remove(row.cmdId());
                notify(toRecord(row, CommandState.SUPERSEDED), CommandState.SUPERSEDED);
            }
        }
    }

    /** 超时处理：内存快路径与兜底扫描共用同一实现，因此必须可重入且幂等。 */
    public void handleTimeout(long id, String cmdId) {
        CommandDao.Row row = commandDao.findByCmdId(cmdId);
        if (row == null || row.state().isTerminal() || row.state() == CommandState.TIMEOUT) {
            return;
        }
        // CREATED = 根本没送达设备（离线、抢不到连接），它的超时语义是"指令过期"而不是"送达了没回"。
        // 两者必须分开：EXPIRED 代表零物理动作（上层可走 REJECTED / 重下单），
        // TIMEOUT 代表"可能已经执行"（必须反查）。混掉就再也分不出这两种处置。
        CommandState target = row.state() == CommandState.CREATED ? CommandState.EXPIRED : CommandState.TIMEOUT;
        if (!commandDao.transition(row.id(), row.state(), target, LocalDateTime.now(),
                null, null, "NO_REPLY", null)) {
            return;
        }
        inFlight.remove(cmdId);
        registry.counter("cmd.timeout", "cmd", row.cmdCode(), "to", target.name()).increment();
        CommandRecord record = toRecord(row, target);
        if (row.retryLeft() != null && row.retryLeft() > 0 && row.state() == CommandState.DISPATCHED) {
            commandDao.decrementRetry(row.id());
            log.debug("指令超时且仍有重试额度：cmdId={}", cmdId);
        }
        // 超时后不自动重发副作用指令（协议 §4.1 铁律一），只回调让上层决定
        notify(record, target);
    }

    public List<CommandDao.Row> scanDue(int limit) {
        return commandDao.scanDue(System.currentTimeMillis(), limit);
    }

    public long inFlightCount() {
        return commandDao.countInFlight();
    }

    private void scheduleTimeout(Long id, String cmdId, long deadlineTs) {
        long delay = Math.max(0, deadlineTs - System.currentTimeMillis());
        scheduler.schedule(() -> {
            try {
                handleTimeout(id, cmdId);
            } catch (RuntimeException e) {
                log.error("指令超时处理异常：cmdId={}, err={}", cmdId, e.getMessage(), e);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private Envelope buildEnvelope(Issue issue, String cmdId, DeviceDirectoryDao.Device device,
                                    long now, long deadline, String sessionId) {
        ObjectNode data = issue.data() == null ? objectMapper.createObjectNode() : issue.data();
        Envelope unsigned = new Envelope("1.0", cmdId, now, deadline, Ulids.next(), issue.traceId(), sessionId,
                "buddy-cloud::" + properties.getNodeId(), null, null, issue.cmdCode(), null, data, null);
        String msgSecret = secrets.deriveMsgSecret(credentials.masterSecretOf(device));
        String sign = msgSecret == null ? null : secrets.sign(msgSecret, unsigned);
        return new Envelope("1.0", cmdId, now, deadline, unsigned.nonce(), issue.traceId(), sessionId,
                unsigned.from(), null, null, issue.cmdCode(), null, data, sign);
    }

    private static CommandRecord toRecord(CommandDao.Row row, CommandState state) {
        return new CommandRecord(row.cmdId(), row.bizType(), row.bizId(), row.stepNo(), row.deviceRowId(),
                row.cmdCode(), state, row.replyCode(), row.traceId());
    }

    private void notify(CommandRecord record, CommandState state) {
        for (CommandListener listener : listeners) {
            try {
                listener.onCommandStateChanged(record, state);
            } catch (RuntimeException e) {
                log.error("指令监听器异常：listener={}, cmdId={}", listener.getClass().getSimpleName(),
                        record.cmdId(), e);
            }
        }
    }
}
