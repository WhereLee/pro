package com.lrs.buddy.biz.swap.flow;

import com.lrs.buddy.framework.iot.envelope.Envelope;
import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao;
import com.lrs.buddy.framework.iot.transport.InboundRouter;
import com.lrs.buddy.framework.iot.transport.MqttTopics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 把校验链通过后的设备事件接进换电流程。
 *
 * 只登记 EVENT 类主题：指令应答由 M1 的 CommandReplyListener 处理，遥测由遥测管道处理。
 * 这里刻意不做任何安全判定 —— 能到这里说明协议 §6 的校验链已全部通过，
 * 安全判定重复一处就可能两处不一致。
 */
@Component
@RequiredArgsConstructor
public class SwapEventListener implements InboundRouter.InboundListener {

    private final SwapFlowService flow;

    @Override
    public boolean supports(MqttTopics.Kind kind) {
        return kind == MqttTopics.Kind.EVENT;
    }

    @Override
    public void onInbound(MqttTopics.Inbound topic, Envelope envelope, DeviceDirectoryDao.Device device) {
        flow.onEvent(device, envelope);
    }
}
