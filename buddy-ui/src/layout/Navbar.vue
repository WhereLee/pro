<template>
  <div class="navbar">
    <div class="left">
      <el-icon class="fold-btn" @click="toggle">
        <component :is="collapsed ? 'Expand' : 'Fold'" />
      </el-icon>
      <el-breadcrumb separator="/" class="breadcrumb">
        <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
        <el-breadcrumb-item v-if="route.meta.title">
          {{ route.meta.title }}
        </el-breadcrumb-item>
      </el-breadcrumb>
    </div>

    <div class="right">
      <!-- 公告铃铛：未读数角标 + 下拉列表 -->
      <el-popover
        v-model:visible="noticeVisible"
        placement="bottom-end"
        :width="380"
        trigger="click"
        @show="loadNotices"
      >
        <template #reference>
          <div class="notice-bell">
            <el-badge :value="unread" :hidden="unread === 0" :max="99">
              <el-icon :size="18"><Bell /></el-icon>
            </el-badge>
          </div>
        </template>

        <div class="notice-panel">
          <div class="notice-head">
            <span>通知公告</span>
            <el-button v-if="unread > 0" link type="primary" size="small" @click="readAll">
              全部已读
            </el-button>
          </div>
          <el-scrollbar max-height="320px">
            <div v-if="notices.length === 0" class="notice-empty">暂无公告</div>
            <div
              v-for="item in notices"
              :key="item.id"
              class="notice-item"
              :class="{ unread: item.unread }"
              @click="openNotice(item)"
            >
              <div class="notice-title">
                <el-tag size="small" :type="item.type === 2 ? 'warning' : 'info'">
                  {{ item.type === 2 ? '公告' : '通知' }}
                </el-tag>
                <span class="title-text">{{ item.title }}</span>
                <span v-if="item.unread" class="dot" />
              </div>
              <div class="notice-time">{{ formatTime(item.publishTime) }}</div>
            </div>
          </el-scrollbar>
        </div>
      </el-popover>

      <el-dropdown @command="handleCommand">
        <span class="user-info">
          <el-avatar :size="28" class="avatar">
            {{ displayName.charAt(0) }}
          </el-avatar>
          <span class="username">{{ displayName }}</span>
          <el-icon><ArrowDown /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="logout" divided>
              <el-icon><SwitchButton /></el-icon>
              退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import { myNotices, unreadCount, markRead, type MyNoticeVO } from '@/api/notice'
import { createSseConnection } from '@/utils/sse'
import { performSoftLogout } from '@/utils/auth'

const props = defineProps<{ collapsed: boolean }>()
const emit = defineEmits<{ (e: 'update:collapsed', value: boolean): void }>()

const route = useRoute()
const userStore = useUserStore()

const displayName = computed(
  () => userStore.userInfo?.nickname || userStore.userInfo?.username || '未登录'
)

function toggle() {
  emit('update:collapsed', !props.collapsed)
}

/* ---------- 公告 ---------- */
const noticeVisible = ref(false)
const notices = ref<MyNoticeVO[]>([])
const unread = ref(0)
let closeNoticeSse: (() => void) | undefined

async function loadNotices() {
  const [list, count] = await Promise.all([
    myNotices().catch(() => []),
    unreadCount().catch(() => 0)
  ])
  notices.value = list
  unread.value = Number(count ?? 0)
}

async function refreshUnread() {
  unread.value = Number((await unreadCount().catch(() => 0)) ?? 0)
}

async function openNotice(item: MyNoticeVO) {
  if (item.unread) {
    await markRead(item.id).catch(() => undefined)
    item.unread = false
    unread.value = Math.max(0, unread.value - 1)
  }
  noticeVisible.value = false
  ElMessageBox.alert(item.content || '（无正文）', item.title, {
    confirmButtonText: '知道了'
  })
}

async function readAll() {
  for (const item of notices.value.filter((n) => n.unread)) {
    await markRead(item.id).catch(() => undefined)
    item.unread = false
  }
  unread.value = 0
  ElMessage.success('已全部标记为已读')
}

function formatTime(time?: string) {
  if (!time) return ''
  return time.replace('T', ' ').slice(0, 16)
}

/* ---------- 退出 ---------- */
async function handleCommand(command: string) {
  if (command === 'logout') {
    try {
      await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
        type: 'warning',
        confirmButtonText: '确定',
        cancelButtonText: '取消'
      })
    } catch {
      return
    }
    await performSoftLogout()
    ElMessage.success('已退出登录')
  }
}

onMounted(() => {
  refreshUnread()
  // 订阅公告推送：管理员发布后，未读数与列表即时更新，无需刷新页面
  closeNoticeSse = createSseConnection({
    url: '/api/notice/stream',
    token: userStore.token,
    onMessage: () => {
      refreshUnread()
      if (noticeVisible.value) loadNotices()
    }
  })
})

onBeforeUnmount(() => closeNoticeSse?.())
</script>

<style scoped>
.navbar {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
}

.left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.fold-btn {
  font-size: 20px;
  cursor: pointer;
  color: #606266;
}

.fold-btn:hover {
  color: #409eff;
}

.right {
  display: flex;
  align-items: center;
  gap: 18px;
}

.notice-bell {
  cursor: pointer;
  display: flex;
  align-items: center;
  color: #606266;
}

.notice-bell:hover {
  color: #409eff;
}

.notice-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  padding-bottom: 8px;
  border-bottom: 1px solid #f0f2f5;
}

.notice-empty {
  text-align: center;
  color: #c0c4cc;
  padding: 24px 0;
  font-size: 13px;
}

.notice-item {
  padding: 10px 4px;
  border-bottom: 1px solid #f5f7fa;
  cursor: pointer;
}

.notice-item:hover {
  background-color: #f5f7fa;
}

.notice-item.unread .title-text {
  font-weight: 600;
}

.notice-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.title-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background-color: #f56c6c;
  flex-shrink: 0;
}

.notice-time {
  margin-top: 4px;
  font-size: 12px;
  color: #a8abb2;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}

.avatar {
  background-color: #409eff;
}

.username {
  font-size: 14px;
  color: #303133;
}
</style>
