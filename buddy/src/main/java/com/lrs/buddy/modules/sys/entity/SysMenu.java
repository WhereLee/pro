package com.lrs.buddy.modules.sys.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lrs.buddy.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 菜单（含按钮）。
 *
 * <p>菜单与权限合并在一张表：目录（M）、菜单（C）、按钮（F）都是树上的节点，
 * 只是类型不同。按钮节点的 {@code perms} 字段就是权限标识，
 * 前端据此控制按钮是否渲染，后端据此做接口鉴权，两端共用同一份数据源。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_menu")
public class SysMenu extends BaseEntity {

    private Long parentId;

    private String menuName;

    /** 类型：M 目录 / C 菜单 / F 按钮 */
    private String menuType;

    /** 前端路由地址（目录与菜单有效） */
    private String path;

    /** 前端组件路径（菜单有效） */
    private String component;

    /** 权限标识（按钮有效），如 sys:user:list */
    private String perms;

    private String icon;

    private Integer sort;

    /** 是否显示：0 显示，1 隐藏 */
    private Integer visible;
}
