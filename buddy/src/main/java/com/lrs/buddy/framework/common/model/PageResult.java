package com.lrs.buddy.framework.common.model;

import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.List;
import java.util.function.Function;

/**
 * 统一响应体（分页）。
 *
 * @param records  当前页数据
 * @param total    总记录数
 * @param pageNum  当前页码
 * @param pageSize 每页条数
 * @param pages    总页数
 */
public record PageResult<T>(List<T> records, long total, long pageNum, long pageSize, long pages) {

    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getRecords(), page.getTotal(),
                page.getCurrent(), page.getSize(), page.getPages());
    }

    /**
     * 分页后需要转换 VO 时使用，避免在 Service 里手写遍历。
     */
    public static <E, V> PageResult<V> of(IPage<E> page, Function<E, V> converter) {
        List<V> list = page.getRecords().stream().map(converter).toList();
        return new PageResult<>(list, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }
}
