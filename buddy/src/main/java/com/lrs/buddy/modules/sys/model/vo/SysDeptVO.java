package com.lrs.buddy.modules.sys.model.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 部门视图对象（树形）。
 */
@Data
public class SysDeptVO {

    private Long id;
    private Long parentId;
    private String deptName;
    private String ancestors;
    private Integer sort;
    private String leader;
    private String phone;
    private Integer status;

    private List<SysDeptVO> children = new ArrayList<>();
}
