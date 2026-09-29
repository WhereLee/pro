package com.lrs.buddy.framework.common.model;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 分页查询参数基类。
 *
 * <p>业务查询对象继承此类即可获得分页能力，例如：
 * <pre>{@code
 * public class UserQuery extends PageQuery {
 *     private String username;
 * }
 * }</pre>
 */
@Data
public class PageQuery {

    @Min(value = 1, message = "页码最小为 1")
    private Integer pageNum = 1;

    @Min(value = 1, message = "每页条数最小为 1")
    @Max(value = 500, message = "每页条数最大为 500")
    private Integer pageSize = 10;

    /**
     * 转为 MyBatis-Plus 分页对象。
     *
     * <p>pageNum 为 null 时兜底为 1：部分前端在首次查询时不传分页参数，
     * 直接传 null 会导致 MP 构造 Page 时 NPE。
     */
    public <T> Page<T> toPage() {
        int current = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int size = (pageSize == null || pageSize < 1) ? 10 : pageSize;
        return Page.of(current, size);
    }
}
