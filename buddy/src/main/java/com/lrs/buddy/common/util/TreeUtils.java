package com.lrs.buddy.common.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 通用树构建工具。
 *
 * <p>常见写法是按父子关系递归查询数据库（每层一次 SQL），
 * 数据量大时会退化成 N+1 查询。这里改为<b>一次性加载全部节点后在内存里组装</b>：
 * 先建索引，再一次遍历挂到父节点上，时间复杂度 O(n)。
 *
 * <p>用法：
 * <pre>{@code
 * List<SysMenuVO> tree = TreeUtils.build(
 *         voList,
 *         SysMenuVO::getId,
 *         SysMenuVO::getParentId,
 *         SysMenuVO::getChildren,
 *         0L);
 * }</pre>
 *
 * <p>注意 {@code childrenGetter} 返回的必须是<b>可修改且已初始化</b>的列表
 * （VO 中直接 {@code new ArrayList<>()}），否则挂接时抛 NPE。
 */
public final class TreeUtils {

    private TreeUtils() {
    }

    /**
     * @param nodes          全部节点（平铺）
     * @param idGetter       取节点 ID
     * @param parentGetter   取父节点 ID
     * @param childrenGetter 取子节点集合（必须是已初始化的可变列表）
     * @param rootId         根节点父 ID 的值；父 ID 等于它或指向不存在的节点时视为根
     */
    public static <T, ID> List<T> build(List<T> nodes,
                                        Function<T, ID> idGetter,
                                        Function<T, ID> parentGetter,
                                        Function<T, List<T>> childrenGetter,
                                        ID rootId) {
        if (nodes == null || nodes.isEmpty()) {
            return new ArrayList<>();
        }

        Map<ID, T> index = new HashMap<>(nodes.size() * 2);
        for (T node : nodes) {
            index.put(idGetter.apply(node), node);
        }

        List<T> roots = new ArrayList<>();
        for (T node : nodes) {
            ID parentId = parentGetter.apply(node);
            if (parentId == null || Objects.equals(parentId, rootId) || !index.containsKey(parentId)) {
                roots.add(node);
            } else {
                T parent = index.get(parentId);
                List<T> children = childrenGetter.apply(parent);
                if (children != null) {
                    children.add(node);
                }
            }
        }
        return roots;
    }
}
