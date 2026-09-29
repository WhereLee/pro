package com.lrs.buddy.framework.modules.sys.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户与角色的关联表。
 *
 * <p>纯关联表不继承 {@link com.lrs.buddy.framework.common.model.BaseEntity}：
 * 它没有独立的业务生命周期，做逻辑删除反而会让"重新分配角色"变复杂，
 * 这里直接物理删除。
 *
 * <p>主键使用数据库自增而非雪花 ID：关联表的 ID 没有任何业务含义，
 * 且批量插入时由数据库生成比应用层生成更简单。
 */
@Data
@TableName("sys_user_role")
public class SysUserRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long roleId;
}
