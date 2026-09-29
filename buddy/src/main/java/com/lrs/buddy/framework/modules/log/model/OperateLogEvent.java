package com.lrs.buddy.framework.modules.log.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志事件。
 *
 * <p>为什么要它：日志是异步落库的，而切面里的用户信息来自
 * {@code UserContext}（ThreadLocal），异步线程读不到。
 * 与其去折腾上下文传递，不如在切面的同步阶段就把需要的数据全部取出，
 * 打包成不可变对象交给异步方法——简单且不会出错。
 */
@Data
@Builder
public class OperateLogEvent {

    private String title;
    private Integer businessType;
    private String method;
    private String requestMethod;
    private Long operatorId;
    private String operatorName;
    private String operUrl;
    private String operIp;
    private String operParam;
    private String jsonResult;
    private Integer status;
    private String errorMsg;
    private Long costTime;
    private LocalDateTime operTime;
}
