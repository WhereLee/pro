<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="table-toolbar">
        <el-text type="info" size="small">
          共 <b>{{ records.length }}</b> 个在线会话 · 数据来自 Redis 在线台账（ZSet 索引）
        </el-text>
        <div class="spacer" />
        <el-button :icon="Refresh" circle @click="loadData" />
      </div>

      <el-table v-loading="loading" :data="records" border stripe>
        <el-table-column type="index" label="#" width="56" align="center" />
        <el-table-column prop="username" label="用户名" min-width="110" />
        <el-table-column prop="nickname" label="昵称" min-width="110" />
        <el-table-column prop="ip" label="登录 IP" min-width="130" />
        <el-table-column prop="loginTime" label="登录时间" min-width="170" />
        <el-table-column prop="lastActiveTime" label="最后活跃" min-width="170" />
        <el-table-column prop="userAgent" label="浏览器" min-width="220" show-overflow-tooltip />
        <el-table-column label="操作" width="110" fixed="right" align="center">
          <template #default="{ row }">
            <el-button
              v-if="hasPerm('monitor:online:kick')"
              link
              type="danger"
              @click="handleKick(row)"
            >
              强制下线
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { onlineUsers, kickOut, type OnlineUserVO } from '@/api/monitor'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const hasPerm = (perm: string) => userStore.hasPermission(perm)

const loading = ref(false)
const records = ref<OnlineUserVO[]>([])

async function loadData() {
  loading.value = true
  try {
    records.value = await onlineUsers()
  } finally {
    loading.value = false
  }
}

async function handleKick(row: OnlineUserVO) {
  try {
    await ElMessageBox.confirm(
      `确定将【${row.username}】强制下线吗？对方会立即收到通知并被登出。`,
      '提示',
      { type: 'warning', confirmButtonText: '确定下线', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await kickOut(row.userId)
  ElMessage.success('已强制下线')
  loadData()
}

onMounted(loadData)
</script>
