package com.lrs.buddy.modules.sys.model.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单视图对象（树形）。
 *
 * <p>{@code children} 默认初始化为空列表而不是 null：
 * 前端递归渲染时不必到处判空，序列化后也不会出现 "children": null。
 */
@Data
public class SysMenuVO {

    private Long id;
    private Long parentId;
    private String menuName;
    private String menuType;
    private String path;
    private String component;
    private String perms;
    private String icon;
    private Integer sort;
    private Integer visible;

    private List<SysMenuVO> children = new ArrayList<>();
}
