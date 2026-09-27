package com.lrs.buddy.common.model;

import com.lrs.buddy.common.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 支持数据权限的查询基类。
 *
 * <p>{@code sqlFilter} 由 {@code DataScopeAspect} 在方法执行前注入，
 * 业务代码不需要感知它的存在。
 *
 * <p>安全说明：这个字段最终会以 {@code ${}} 形式拼进 SQL，
 * 因此<b>绝不能接收前端传入的值</b>。切面生成的内容只来自
 * 当前登录用户的角色配置（服务端可信数据），不含任何用户输入。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DataScopeQuery extends PageQuery {

    /**
     * 数据权限过滤片段，由切面写入，前端不应传此字段。
     *
     * <p>默认空串而不是 null：Mapper 里用 {@code ${sqlFilter}} 拼接，
     * 若为 null 会被 OGNL 求值成字符串 "null" 拼进 SQL，导致语法错误。
     * 给个空串兜底，即使切面因故没执行也不会让查询崩掉（只是退化为不过滤，
     * 实际由 @DataScope 保证，这里是最后一道防线）。
     */
    private String sqlFilter = "";
}
