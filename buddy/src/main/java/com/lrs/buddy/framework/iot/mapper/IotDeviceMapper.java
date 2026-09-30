package com.lrs.buddy.framework.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lrs.buddy.framework.iot.entity.IotDevice;

/** 设备台账 Mapper（framework 层）。写入密钥密文请走定向 SQL，不经过本接口。 */
public interface IotDeviceMapper extends BaseMapper<IotDevice> {
}
