package com.lrs.buddy.framework.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TreeUtils 纯单测：一次性平铺组装 O(n)，验证根识别、挂接、孤儿节点兜底。
 */
class TreeUtilsTest {

    /** 最小树节点 */
    static class Node {
        Long id;
        Long parentId;
        List<Node> children = new ArrayList<>();

        Node(Long id, Long parentId) {
            this.id = id;
            this.parentId = parentId;
        }

        Long getId() {
            return id;
        }

        Long getParentId() {
            return parentId;
        }

        List<Node> getChildren() {
            return children;
        }
    }

    @Test
    @DisplayName("空输入返回空列表")
    void empty() {
        assertTrue(TreeUtils.build(new ArrayList<>(), Node::getId, Node::getParentId,
                Node::getChildren, 0L).isEmpty());
    }

    @Test
    @DisplayName("正常父子关系挂接，root 的 parentId=0")
    void buildsTree() {
        List<Node> nodes = new ArrayList<>(List.of(
                new Node(1L, 0L),
                new Node(11L, 1L),
                new Node(12L, 1L),
                new Node(111L, 11L)));

        List<Node> roots = TreeUtils.build(nodes, Node::getId, Node::getParentId,
                Node::getChildren, 0L);

        assertEquals(1, roots.size());
        assertEquals(1L, roots.get(0).id);
        assertEquals(2, roots.get(0).children.size()); // 11、12
        Node n11 = roots.get(0).children.stream().filter(n -> n.id == 11L).findFirst().orElseThrow();
        assertEquals(1, n11.children.size()); // 111
        assertEquals(111L, n11.children.get(0).id);
    }

    @Test
    @DisplayName("父不存在（孤儿）视为根，避免数据丢失")
    void orphanAsRoot() {
        List<Node> nodes = new ArrayList<>(List.of(new Node(9L, 999L)));
        List<Node> roots = TreeUtils.build(nodes, Node::getId, Node::getParentId,
                Node::getChildren, 0L);
        assertEquals(1, roots.size());
        assertEquals(9L, roots.get(0).id);
    }
}
