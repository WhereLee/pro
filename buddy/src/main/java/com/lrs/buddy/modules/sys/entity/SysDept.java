package com.lrs.buddy.modules.sys.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lrs.buddy.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 部门。
 *
 * <p>{@code ancestors} 存祖级 ID 链（如 {@code 0,100,101}），
 * 这是一种"空间换时间"的树查询优化：判断"某部门的所有子孙"时
 * 用一次 LIKE 就能命中，不必递归查库。
 * 代价是移动部门时要同步更新所有子孙的 ancestors——写少读多的场景很划算。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dept")
public class SysDept extends BaseEntity {

    private Long parentId;

    private String deptName;

    /** 祖级列表，逗号分隔，如 0,100,101 */
    private String ancestors;

    private Integer sort;

    /** 状态：0 正常，1 停用 */
    private Integer status;

    private String leader;

    private String phone;
}
