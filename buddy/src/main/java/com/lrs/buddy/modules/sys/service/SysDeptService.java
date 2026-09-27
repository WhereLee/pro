package com.lrs.buddy.modules.sys.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.lrs.buddy.modules.sys.entity.SysDept;
import com.lrs.buddy.modules.sys.model.vo.SysDeptVO;

import java.util.List;

/**
 * 部门服务。
 */
public interface SysDeptService extends IService<SysDept> {

    /** 部门树 */
    List<SysDeptVO> tree();

    void saveDept(SysDept dept);

    void updateDept(SysDept dept);

    /** 删除：存在子部门或被用户占用时拒绝 */
    void removeDept(Long deptId);
}
