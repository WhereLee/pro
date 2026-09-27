<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="table-toolbar">
        <el-upload
          v-if="hasPerm('sys:file:upload')"
          :action="uploadUrl()"
          :headers="uploadHeaders"
          :show-file-list="false"
          :on-success="onUploadSuccess"
          :on-error="onUploadError"
          multiple
        >
          <el-button type="primary" :icon="Upload">上传文件</el-button>
        </el-upload>
        <el-button
          v-if="hasPerm('sys:file:remove')"
          type="danger"
          :icon="Delete"
          :disabled="selected.length === 0"
          @click="handleDelete()"
        >
          批量删除
        </el-button>
        <div class="spacer" />
        <el-text type="info" size="small">存储方式：{{ storageHint }}</el-text>
        <el-button :icon="Refresh" circle @click="loadData" />
      </div>

      <el-form :model="query" inline class="search-form" @submit.prevent>
        <el-form-item label="文件名">
          <el-input v-model="query.originalName" placeholder="请输入" clearable style="width: 180px" />
        </el-form-item>
        <el-form-item label="目录">
          <el-input v-model="query.directory" placeholder="如 test" clearable style="width: 140px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="records" border stripe @selection-change="onSelectionChange">
        <el-table-column type="selection" width="46" />
        <el-table-column prop="originalName" label="文件名" min-width="200" show-overflow-tooltip />
        <el-table-column prop="directory" label="目录" width="100" />
        <el-table-column prop="contentType" label="类型" min-width="140" show-overflow-tooltip />
        <el-table-column label="大小" width="100" align="center">
          <template #default="{ row }">{{ formatSize(row.size) }}</template>
        </el-table-column>
        <el-table-column prop="storageType" label="存储" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small">{{ row.storageType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="上传时间" min-width="170" />
        <el-table-column label="操作" width="140" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="download(row)">下载</el-button>
            <el-button
              v-if="hasPerm('sys:file:remove')"
              link
              type="danger"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="loadData"
          @current-change="loadData"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Upload, Delete } from '@element-plus/icons-vue'
import { pageFiles, removeFiles, uploadUrl, downloadUrl, formatSize, type SysFile } from '@/api/file'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const hasPerm = (perm: string) => userStore.hasPermission(perm)

// el-upload 不会走 axios 拦截器，需要自己把令牌带上
const uploadHeaders = computed(() => ({ Authorization: `Bearer ${userStore.token}` }))
const storageHint = 'local（切换对象存储只需替换 StorageService 实现）'

const loading = ref(false)
const records = ref<SysFile[]>([])
const total = ref(0)
const selected = ref<SysFile[]>([])

const query = reactive({
  originalName: '',
  directory: '',
  pageNum: 1,
  pageSize: 10
})

async function loadData() {
  loading.value = true
  try {
    const res = await pageFiles({ ...query })
    records.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  loadData()
}

function onSelectionChange(rows: SysFile[]) {
  selected.value = rows
}

function onUploadSuccess(response: any) {
  if (response?.code === 200) {
    ElMessage.success('上传成功')
    loadData()
  } else {
    ElMessage.error(response?.message || '上传失败')
  }
}

function onUploadError() {
  ElMessage.error('上传失败，请检查文件类型与大小限制')
}

function download(row: SysFile) {
  // 直接跳转让浏览器处理下载，不经过 axios（避免二进制被 JSON 解析破坏）
  window.open(downloadUrl(row.id))
}

async function handleDelete(row?: SysFile) {
  const ids = row ? [row.id] : selected.value.map((f) => f.id)
  if (ids.length === 0) return
  try {
    await ElMessageBox.confirm(
      row ? `确定删除文件【${row.originalName}】吗？` : `确定删除选中的 ${ids.length} 个文件吗？`,
      '提示',
      { type: 'warning' }
    )
  } catch {
    return
  }
  await removeFiles(ids)
  ElMessage.success('删除成功')
  loadData()
}

onMounted(loadData)
</script>
