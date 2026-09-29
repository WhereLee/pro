package com.lrs.buddy.framework.modules.log.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作日志。
 *
 * <p>不继承 BaseEntity：日志只增不改不删，不需要乐观锁和逻辑删除，
 * 继承反而会给每条日志多写三个无用字段。
 */
@Data
@TableName("sys_operate_log")
public class SysOperateLog implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 操作模块 */
    private String title;

    /** 操作类型，见 BusinessType */
    private Integer businessType;

    /** 请求方法全限定名 */
    private String method;

    private String requestMethod;

    private Long operatorId;

    private String operatorName;

    private String operUrl;

    private String operIp;

    /** 请求参数（超长会被截断） */
    private String operParam;

    /** 返回结果（超长会被截断） */
    private String jsonResult;

    /** 状态：0 成功，1 失败 */
    private Integer status;

    private String errorMsg;

    /** 耗时（毫秒） */
    private Long costTime;

    private LocalDateTime operTime;
}
