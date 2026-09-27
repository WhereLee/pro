package com.lrs.buddy.persistence;

import com.lrs.buddy.AbstractIntegrationTest;
import com.lrs.buddy.modules.sys.entity.SysUser;
import com.lrs.buddy.modules.sys.mapper.SysUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * BaseEntity 三机制的持久层集成测试（H2 + MyBatis-Plus）：
 * 自动填充、乐观锁版本号、逻辑删除。真实走 MetaObjectHandler 与拦截器。
 */
class BaseEntityTest extends AbstractIntegrationTest {

    @Autowired
    private SysUserMapper userMapper;

    private SysUser newPersistedUser() {
        SysUser u = new SysUser();
        u.setUsername("bt_" + System.currentTimeMillis());
        u.setPassword("x");
        u.setNickname("base");
        u.setStatus(0);
        u.setDeptId(100L);
        userMapper.insert(u);
        return u;
    }

    @Test
    @Transactional
    @DisplayName("新增：审计时间/ delFlag=0 / version=0 由 MetaObjectHandler 自动填充")
    void insertAutoFill() {
        SysUser u = newPersistedUser();
        SysUser db = userMapper.selectById(u.getId());
        assertNotNull(db.getCreateTime(), "createTime 应被自动填充");
        assertNotNull(db.getUpdateTime(), "updateTime 应被自动填充");
        assertEquals(0, db.getDelFlag());
        assertEquals(0, db.getVersion());
    }

    @Test
    @Transactional
    @DisplayName("更新：乐观锁 version 递增")
    void optimisticLockIncrementsVersion() {
        SysUser u = newPersistedUser();
        SysUser db = userMapper.selectById(u.getId());
        assertEquals(0, db.getVersion());

        db.setNickname("renamed");
        int affected = userMapper.updateById(db);
        assertEquals(1, affected);

        SysUser after = userMapper.selectById(u.getId());
        assertEquals(1, after.getVersion(), "version 应从 0 递增到 1");
        assertEquals("renamed", after.getNickname());
    }

    @Test
    @Transactional
    @DisplayName("逻辑删除：deleteById 置 del_flag=1，selectById 因 @TableLogic 查不到")
    void logicalDelete() {
        SysUser u = newPersistedUser();
        userMapper.deleteById(u.getId());
        assertNull(userMapper.selectById(u.getId()), "逻辑删除后按 MP 规则应查不到");
    }
}
