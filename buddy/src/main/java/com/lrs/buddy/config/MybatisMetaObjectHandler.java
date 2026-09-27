package com.lrs.buddy.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.lrs.buddy.security.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 字段自动填充。
 *
 * <p>把 createTime / updateTime / createBy / updateBy / delFlag / version
 * 从业务代码中彻底抽离：新增或更新时无需手工 set，也不会出现"某处忘了设置时间"
 * 导致脏数据的情况。
 *
 * <p>使用 strictInsertFill / strictUpdateFill 而不是 setFieldValByName：
 * 前者只会填充"字段存在且当前值为 null"的情况，避免把调用方显式设置的值覆盖掉。
 * （MP 3.5.17 起旧的 strictFill 四参重载已被移除。）
 */
@Slf4j
@Component
public class MybatisMetaObjectHandler implements MetaObjectHandler {

    private static final String CREATE_TIME = "createTime";
    private static final String UPDATE_TIME = "updateTime";
    private static final String CREATE_BY = "createBy";
    private static final String UPDATE_BY = "updateBy";
    private static final String DEL_FLAG = "delFlag";
    private static final String VERSION = "version";

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        Long userId = UserContext.getUserId();

        strictInsertFill(metaObject, CREATE_TIME, LocalDateTime.class, now);
        strictInsertFill(metaObject, UPDATE_TIME, LocalDateTime.class, now);
        // createBy 允许为空：定时任务、系统内部调用时没有登录人
        if (userId != null) {
            strictInsertFill(metaObject, CREATE_BY, Long.class, userId);
            strictInsertFill(metaObject, UPDATE_BY, Long.class, userId);
        }
        // 逻辑删除标记与乐观锁版本号的初始值
        strictInsertFill(metaObject, DEL_FLAG, Integer.class, 0);
        strictInsertFill(metaObject, VERSION, Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        Long userId = UserContext.getUserId();
        strictUpdateFill(metaObject, UPDATE_TIME, LocalDateTime.class, LocalDateTime.now());
        if (userId != null) {
            strictUpdateFill(metaObject, UPDATE_BY, Long.class, userId);
        }
    }
}
