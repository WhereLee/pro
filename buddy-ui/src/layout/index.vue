<template>
  <el-container class="layout-container">
    <el-aside :width="collapsed ? '64px' : '210px'" class="layout-aside">
      <div class="logo" @click="$router.push('/')">
        <span v-if="!collapsed" class="logo-text">Buddy</span>
        <span v-else class="logo-text">B</span>
      </div>
      <Sidebar :collapsed="collapsed" />
    </el-aside>

    <el-container>
      <el-header class="layout-header">
        <Navbar v-model:collapsed="collapsed" />
      </el-header>
      <el-main class="layout-main">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import Sidebar from './Sidebar.vue'
import Navbar from './Navbar.vue'
import { createSseConnection } from '@/utils/sse'
import { useUserStore } from '@/store/user'
import { performSoftLogout } from '@/utils/auth'

const userStore = useUserStore()

const collapsed = ref(false)

/**
 * 订阅"强制下线"通知。
 *
 * 订阅放在布局层而不是某个具体页面：管理员可以在用户停留在任意页面时把他踢下线，
 * 只有全局订阅才能保证用户无论在哪都能立刻收到通知。
 */
let closeSse: (() => void) | undefined

onMounted(() => {
  closeSse = createSseConnection({
    url: '/api/monitor/force-logout/stream',
    token: userStore.token,
    onMessage: async (data) => {
      if (data?.type !== 'FORCE_LOGOUT') return
      try {
        await ElMessageBox.alert(data.message ?? '您已被管理员强制下线', '下线通知', {
          type: 'warning',
          confirmButtonText: '重新登录'
        })
      } catch {
        // 用户直接关闭弹窗也要登出
      }
      await performSoftLogout()
    }
  })
})

onBeforeUnmount(() => closeSse?.())
</script>

<style scoped>
.layout-container {
  height: 100%;
}

.layout-aside {
  background-color: #fff;
  border-right: 1px solid #e4e7ed;
  transition: width 0.25s;
  overflow-x: hidden;
  display: flex;
  flex-direction: column;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  border-bottom: 1px solid #f0f2f5;
  flex-shrink: 0;
}

.logo-text {
  font-size: 20px;
  font-weight: 700;
  color: #409eff;
  letter-spacing: 1px;
}

.layout-header {
  padding: 0;
  height: 60px;
  background-color: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.layout-main {
  padding: 0;
  overflow-y: auto;
  background-color: #f5f7fa;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.18s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
