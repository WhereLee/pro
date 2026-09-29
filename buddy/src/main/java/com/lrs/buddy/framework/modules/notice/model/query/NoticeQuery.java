package com.lrs.buddy.framework.modules.notice.model.query;

import com.lrs.buddy.framework.common.model.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 公告查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NoticeQuery extends PageQuery {

    private String title;

    private Integer type;

    private Integer status;
}
