<template>
  <div class="page-container">
    <el-card shadow="never">
      <el-form :model="query" inline class="search-form" @submit.prevent>
        <el-form-item label="操作模块">
          <el-input v-model="query.title" placeholder="如：用户管理" clearable style="width: 160px" />
        </el-form-item>
        <el-form-item label="操作类型">
          <el-select v-model="query.businessType" placeholder="全部" clearable style="width: 130px">
            <el-option v-for="t in businessTypes" :key="t.code" :label="t.desc" :value="t.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 110px">
            <el-option label="成功" :value="0" />
            <el-option label="失败" :value="1" />
          </el-select>
        </el-form-item>
        <el-form-item label="操作人">
          <el-input v-model="query.operatorName" placeholder="请输入" clearable style="width: 140px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="records" border stripe>
        <el-table-column prop="title" label="操作模块" min-width="110" />
        <el-table-column label="操作类型" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.businessType)">{{ typeDesc(row.businessType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="operatorName" label="操作人" width="100" />
        <el-table-column prop="operIp" label="操作地址" width="130" />
        <el-table-column prop="operUrl" label="请求地址" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 0 ? 'success' : 'danger'">
              {{ row.status === 0 ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90" align="center">
          <template #default="{ row }">
            <span :class="{ 'slow': row.costTime > 1000 }">{{ row.costTime }}ms</span>
          </template>
        </el-table-column>
        <el-table-column prop="operTime" label="操作时间" min-width="170" />
        <el-table-column label="操作" width="90" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
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

    <el-dialog v-model="detailVisible" title="日志详情" width="720px">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="操作模块">{{ current.title }}</el-descriptions-item>
        <el-descriptions-item label="操作类型">{{ typeDesc(current.businessType) }}</el-descriptions-item>
        <el-descriptions-item label="操作人">{{ current.operatorName }}</el-descriptions-item>
        <el-descriptions-item label="操作地址">{{ current.operIp }}</el-descriptions-item>
        <el-descriptions-item label="请求方式">{{ current.requestMethod }}</el-descriptions-item>
        <el-descriptions-item label="耗时">{{ current.costTime }}ms</el-descriptions-item>
        <el-descriptions-item label="请求地址" :span="2">{{ current.operUrl }}</el-descriptions-item>
        <el-descriptions-item label="操作方法" :span="2">{{ current.method }}</el-descriptions-item>
      </el-descriptions>

      <div class="detail-block">
        <div class="block-title">请求参数</div>
        <pre class="code-block">{{ current.operParam || '（无）' }}</pre>
      </div>
      <div class="detail-block">
        <div class="block-title">返回结果</div>
        <pre class="code-block">{{ current.jsonResult || '（无）' }}</pre>
      </div>
      <div v-if="current.errorMsg" class="detail-block">
        <div class="block-title error">异常信息</div>
        <pre class="code-block error">{{ current.errorMsg }}</pre>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Search, Refresh } from '@element-plus/icons-vue'
import { pageOperateLogs, type OperateLogVO } from '@/api/log'

const loading = ref(false)
const records = ref<OperateLogVO[]>([])
const total = ref(0)
const detailVisible = ref(false)
const current = ref<OperateLogVO>({} as OperateLogVO)

/** 与后端 BusinessType 枚举保持一致 */
const businessTypes = [
  { code: 0, desc: '其它' },
  { code: 1, desc: '新增' },
  { code: 2, desc: '修改' },
  { code: 3, desc: '删除' },
  { code: 4, desc: '导出' },
  { code: 5, desc: '导入' },
  { code: 6, desc: '授权' },
  { code: 7, desc: '登录' },
  { code: 8, desc: '登出' },
  { code: 9, desc: '强制下线' }
]

const query = reactive({
  title: '',
  businessType: undefined as number | undefined,
  status: undefined as number | undefined,
  operatorName: '',
  pageNum: 1,
  pageSize: 10
})

function typeDesc(code: number) {
  return businessTypes.find((t) => t.code === code)?.desc ?? '其它'
}

function tagType(code: number) {
  if (code === 3) return 'danger'
  if (code === 1 || code === 2) return 'primary'
  if (code === 6) return 'warning'
  return 'info'
}

async function loadData() {
  loading.value = true
  try {
    const res = await pageOperateLogs({ ...query })
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

function handleReset() {
  query.title = ''
  query.businessType = undefined
  query.status = undefined
  query.operatorName = ''
  handleSearch()
}

function openDetail(row: OperateLogVO) {
  current.value = row
  detailVisible.value = true
}

onMounted(loadData)
</script>

<style scoped>
.slow {
  color: #e6a23c;
  font-weight: 600;
}

.detail-block {
  margin-top: 14px;
}

.block-title {
  font-weight: 600;
  margin-bottom: 6px;
}

.block-title.error {
  color: #f56c6c;
}

.code-block {
  margin: 0;
  padding: 10px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 12px;
  max-height: 200px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.code-block.error {
  color: #f56c6c;
}
</style>
