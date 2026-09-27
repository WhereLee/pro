<template>
  <!-- 目录：有子菜单，渲染为可展开分组 -->
  <el-sub-menu v-if="hasChildren" :index="String(item.id)">
    <template #title>
      <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
      <span>{{ item.menuName }}</span>
    </template>
    <SidebarItem
      v-for="child in visibleChildren"
      :key="child.id"
      :item="child"
      :parent-path="currentPath"
    />
  </el-sub-menu>

  <!-- 菜单：叶子节点，渲染为可跳转项 -->
  <el-menu-item v-else :index="currentPath">
    <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
    <template #title>{{ item.menuName }}</template>
  </el-menu-item>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { MenuVO } from '@/api/types'

const props = defineProps<{
  item: MenuVO
  parentPath?: string
}>()

// 按钮（F）不出现在菜单里，它只作为权限标识存在
const visibleChildren = computed(() =>
  (props.item.children ?? []).filter((child) => child.menuType !== 'F')
)

const hasChildren = computed(() => visibleChildren.value.length > 0)

/**
 * 计算当前项的完整路由。
 * 后端存的是相对路径（如 user），父级目录存 /system，
 * 拼接规则必须与 permission store 里的 buildRoutes 完全一致，否则菜单点了跳不到页面。
 */
const currentPath = computed(() => {
  const raw = props.item.path || ''
  if (raw.startsWith('/')) return raw
  return `${props.parentPath ?? ''}/${raw}`
})
</script>
