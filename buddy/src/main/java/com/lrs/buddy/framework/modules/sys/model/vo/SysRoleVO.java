package com.lrs.buddy.framework.modules.sys.model.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 角色视图对象。
 */
@Data
public class SysRoleVO {

    private Long id;
    private String roleName;
    private String roleKey;
    private Integer sort;
    private Integer status;
    private LocalDateTime createTime;
    private String remark;

    /** 该角色拥有的菜单 ID（含按钮） */
    private List<Long> menuIds;
}
