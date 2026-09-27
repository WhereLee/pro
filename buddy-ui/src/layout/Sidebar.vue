<template>
  <el-menu
    :default-active="activeMenu"
    :collapse="collapsed"
    :collapse-transition="false"
    unique-opened
    router
    class="sidebar-menu"
  >
    <!-- 首页是静态路由，所有登录用户都可见，这里固定渲染 -->
    <el-menu-item index="/dashboard">
      <el-icon><HomeFilled /></el-icon>
      <template #title>首页</template>
    </el-menu-item>

    <!-- 其余菜单由后端下发 -->
    <SidebarItem
      v-for="menu in permissionStore.menus"
      :key="menu.id"
      :item="menu"
    />
  </el-menu>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import SidebarItem from './SidebarItem.vue'
import { usePermissionStore } from '@/store/permission'

defineProps<{ collapsed: boolean }>()

const route = useRoute()
const permissionStore = usePermissionStore()

// 用完整路径作为高亮依据：菜单项的 path 是相对父级的，这里与路由表保持同一规则
const activeMenu = computed(() => route.path)
</script>

<style scoped>
.sidebar-menu {
  border-right: none;
  flex: 1;
  overflow-y: auto;
}
</style>
