package com.lrs.buddy.modules.sys.model.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 角色新增/修改表单。
 */
@Data
public class SysRoleForm {

    /** 修改时必填 */
    private Long id;

    @NotBlank(message = "角色名称不能为空")
    @Size(max = 50, message = "角色名称长度不能超过 50")
    private String roleName;

    /**
     * 角色标识：只允许小写字母、数字和下划线。
     * 限制字符集的理由是它会被写进代码做判断（如 admin），
     * 允许特殊字符会让后续维护变复杂。
     */
    @NotBlank(message = "角色标识不能为空")
    @Pattern(regexp = "^[a-z][a-z0-9_]{1,49}$", message = "角色标识需以小写字母开头，仅含小写字母、数字、下划线")
    private String roleKey;

    private Integer sort;

    /** 状态：0 正常，1 停用 */
    private Integer status;

    private String remark;

    /** 该角色拥有的菜单（含按钮权限） */
    private List<Long> menuIds;
}
