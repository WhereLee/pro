<template>
  <div class="app-container">
    <el-card shadow="never" class="mb">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="处理状态">
          <el-select v-model="handleState" placeholder="全部" clearable style="width: 180px">
            <el-option label="OPEN（待处理）" value="OPEN" />
            <el-option label="AUTO_RESOLVED" value="AUTO_RESOLVED" />
            <el-option label="MANUAL_RESOLVED" value="MANUAL_RESOLVED" />
            <el-option label="IGNORED" value="IGNORED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="reload">查询差异</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-alert
      class="mb"
      type="warning"
      :closable="false"
      title="差异台账是「账与实不一致」的唯一入口：它不自动修改任何事实，只负责被看见、被处理、被追责"
    />

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" size="small">
        <el-table-column prop="kind" label="差异类型" width="180" />
        <el-table-column prop="dedup_key" label="去重键" width="200" show-overflow-tooltip />
        <el-table-column prop="order_id" label="订单" width="150" />
        <el-table-column prop="cabinet_id" label="柜机" width="140" />
        <el-table-column prop="battery_id" label="电池" width="140" />
        <el-table-column prop="handle_state" label="处理状态" width="150" />
        <el-table-column prop="auto_resolvable" label="可自动裁决" width="120" />
        <el-table-column prop="expected_json" label="期望" min-width="180" show-overflow-tooltip />
        <el-table-column prop="actual_json" label="实际" min-width="180" show-overflow-tooltip />
        <el-table-column prop="remark" label="说明" min-width="200" show-overflow-tooltip />
        <el-table-column prop="create_time" label="产生时间" width="165" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listDiscrepancies } from '@/api/swapOrder'

const rows = ref<Record<string, unknown>[]>([])
const handleState = ref('')
const loading = ref(false)

async function reload(): Promise<void> {
  loading.value = true
  try {
    rows.value = await listDiscrepancies(handleState.value || undefined, 200)
  } finally {
    loading.value = false
  }
}

onMounted(reload)
</script>

<style scoped>
.mb {
  margin-bottom: 14px;
}
</style>
