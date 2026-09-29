package com.lrs.buddy.framework.common.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实体基类，所有业务表实体继承它，保证审计字段口径统一。
 *
 * <p>三个关键机制：
 * <ul>
 *   <li>{@code delFlag} + {@code @TableLogic}：逻辑删除。MP 会自动把 delete 改写为
 *       update set del_flag=1，并把所有查询追加 del_flag=0 条件</li>
 *   <li>{@code createTime/updateTime} + {@code FieldFill}：由 MetaObjectHandler 统一填充，
 *       业务代码不再手写 setCreateTime</li>
 *   <li>{@code version} + {@code @Version}：乐观锁。更新时 MP 追加 where version=?，
 *       返回影响行数 0 即代表发生并发冲突</li>
 * </ul>
 */
@Data
public class BaseEntity implements Serializable {

    @TableId
    private Long id;

    /** 创建人 */
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新人 */
    private Long updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除标记：0 未删除，1 已删除 */
    @TableLogic
    @TableField(fill = FieldFill.INSERT)
    private Integer delFlag;

    /** 乐观锁版本号 */
    @Version
    @TableField(fill = FieldFill.INSERT)
    private Integer version;

    private String remark;
}
