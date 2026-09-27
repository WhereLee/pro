package com.lrs.buddy.modules.sys;

import com.lrs.buddy.modules.sys.entity.SysUser;
import com.lrs.buddy.modules.sys.entity.SysUserRole;
import com.lrs.buddy.modules.sys.mapper.SysUserMapper;
import com.lrs.buddy.modules.sys.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 初始化管理员账号。
 *
 * <p>为什么不把账号写进 data.sql：密码必须以 BCrypt 密文存储，
 * 而 BCrypt 每次加密都会生成不同的随机盐，同一个明文不存在固定的密文，
 * 无法预先写死在 SQL 里。因此改为启动时由程序加密后写入。
 *
 * <p>幂等：已存在同名账号则跳过，重复启动不会覆盖已修改过的密码。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private static final long ADMIN_USER_ID = 1L;
    private static final long ADMIN_ROLE_ID = 1L;

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${buddy.init.admin-username:admin}")
    private String adminUsername;

    @Value("${buddy.init.admin-password:Admin@123456}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        if (userMapper.selectByUsername(adminUsername) != null) {
            log.debug("管理员账号已存在，跳过初始化");
            return;
        }

        SysUser admin = new SysUser();
        admin.setId(ADMIN_USER_ID);
        admin.setUsername(adminUsername);
        admin.setPassword(passwordEncoder.encode(adminPassword));
        admin.setNickname("超级管理员");
        admin.setStatus(0);
        // 挂到总公司：数据权限过滤依赖它（超级管理员不受限，但字段不能为空）
        admin.setDeptId(100L);
        admin.setRemark("框架初始化自动创建");
        userMapper.insert(admin);

        SysUserRole binding = new SysUserRole();
        binding.setUserId(ADMIN_USER_ID);
        binding.setRoleId(ADMIN_ROLE_ID);
        userRoleMapper.insert(binding);

        log.info("已初始化管理员账号：{} / {}（请登录后在第一时间修改密码）",
                adminUsername, adminPassword);
    }
}
