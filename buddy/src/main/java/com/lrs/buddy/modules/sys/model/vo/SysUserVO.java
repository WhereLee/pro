package com.lrs.buddy.modules.sys.model.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户视图对象。
 *
 * <p>与实体的区别：不返回 password（哪怕是密文也不该出现在响应里），
 * 并把角色以 ID 列表形式带出，省去前端二次请求。
 */
@Data
public class SysUserVO {

    private Long id;
    private String username;
    private String nickname;
    private String email;
    private String phone;
    private String avatar;
    private Integer status;
    private LocalDateTime createTime;
    private String remark;

    /** 所属部门 */
    private Long deptId;
    private String deptName;

    /** 该用户拥有的角色 ID */
    private List<Long> roleIds;
}
