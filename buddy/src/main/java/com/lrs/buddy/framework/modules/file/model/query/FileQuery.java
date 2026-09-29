package com.lrs.buddy.framework.modules.file.model.query;

import com.lrs.buddy.framework.common.model.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文件查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FileQuery extends PageQuery {

    private String originalName;

    private String directory;
}
