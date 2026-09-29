package com.lrs.buddy.framework.modules.log.model.query;

import com.lrs.buddy.framework.common.model.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 操作日志查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OperateLogQuery extends PageQuery {

    private String title;

    private Integer businessType;

    /** 0 成功，1 失败 */
    private Integer status;

    private String operatorName;
}
