package com.lrs.buddy.framework.modules.notice.model.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 公告新增/修改表单。
 */
@Data
public class NoticeForm {

    /** 修改时必填 */
    private Long id;

    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题长度不能超过 100")
    private String title;

    @Size(max = 2000, message = "内容长度不能超过 2000")
    private String content;

    /** 1 通知 / 2 公告 */
    private Integer type;

    /**
     * 定向类型：1 全体 / 2 指定角色 / 3 指定用户。
     * 选择 2 或 3 时 targetIds 不能为空——这个一致性校验在 Service 里做，
     * 因为它依赖另一个字段的值，用 Bean Validation 的单字段注解表达不了。
     */
    @NotNull(message = "定向类型不能为空")
    private Integer targetType;

    private List<Long> targetIds;
}
