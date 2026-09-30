package com.lrs.buddy.framework.iot.command;

import com.lrs.buddy.framework.iot.envelope.Envelope;
import com.lrs.buddy.framework.iot.transport.InboundRouter;
import com.lrs.buddy.framework.iot.transport.MqttTopics;
import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao;
import com.lrs.buddy.framework.iot.security.DeviceSecrets;
import lombok.RequiredArgsConstructor;

/**
 * 指令应答入管道：把 CMD_REPLY 主题的上行交给指令总线。
 *
 * 能进到这里说明协议 §6 的十步校验已经全通过（含验签与会话隔离），
 * 所以本类只关心归属，不做任何安全判定 —— 安全判定重复一处就可能两处不一致。
 */
@RequiredArgsConstructor
public class CommandReplyListener implements InboundRouter.InboundListener {

    private final DeviceCommandService commands;

    @Override
    public boolean supports(MqttTopics.Kind kind) {
        return kind == MqttTopics.Kind.CMD_REPLY;
    }

    @Override
    public void onInbound(MqttTopics.Inbound topic, Envelope envelope, DeviceDirectoryDao.Device device) {
        String related = DeviceSecrets.dataText(envelope.data(), "cmdId");
        String cmdId = related != null ? related : envelope.cmd();
        commands.onReply(cmdId, envelope.code(), envelope.data(), envelope.sessionId());
    }
}
