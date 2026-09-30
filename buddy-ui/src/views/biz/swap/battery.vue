<template>
  <div class="app-container">
    <el-card shadow="never" class="mb">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="电池状态">
          <el-select v-model="state" placeholder="全部" clearable style="width: 200px">
            <el-option v-for="s in STATES" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="reload">查询</el-button>
        </el-form-item>
        <el-form-item>
          <span class="hint">本视图含持有人与隔离原因，权限码与柜机台账分开（`swap:battery:read`）。</span>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" size="small">
        <el-table-column prop="battery_code" label="电池编码" width="180" />
        <el-table-column prop="product_key" label="产品" width="150" show-overflow-tooltip />
        <el-table-column label="电池状态" width="170">
          <template #default="{ row }">
            <el-tag :type="tagType(row.battery_state)" size="small">{{ row.battery_state }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="own_type" label="归属类型" width="110" />
        <el-table-column prop="holder_user_id" label="持有人" width="130" />
        <el-table-column prop="cabinet_no" label="所在柜机" width="150" />
        <el-table-column prop="slot_no" label="仓号" width="80" />
        <el-table-column prop="location_state" label="位置可信度" width="120" />
        <el-table-column prop="soc" label="SOC" width="80" />
        <el-table-column prop="soh" label="SOH" width="80" />
        <el-table-column prop="cycle_count" label="循环" width="80" />
        <el-table-column prop="fault_code" label="故障码" width="120" show-overflow-tooltip />
        <el-table-column prop="isolated_reason" label="隔离原因" min-width="160" show-overflow-tooltip />
        <el-table-column prop="last_report_at" label="最后上报" width="165" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listBatteries } from '@/api/swapOrder'

/**
 * `location_state = LOCATION_UNKNOWN` 的行要一眼看见：
 * 这类电池不可分配（分配引擎的硬门槛），运营看到的应该是"位置不明"而不是"在仓里"。
 */
const STATES = [
  'IN_STOCK', 'IN_CABINET_READY', 'IN_CABINET_CHARGING', 'HELD_BY_USER',
  'PENDING_PICKUP', 'IN_TRANSIT', 'FAULT', 'SCRAPPED'
]

const rows = ref<Record<string, unknown>[]>([])
const state = ref('')
const loading = ref(false)

function tagType(value: string): 'success' | 'warning' | 'info' | 'danger' {
  if (value === 'HELD_BY_USER' || value === 'IN_CABINET_READY') {
    return 'success'
  }
  if (value === 'FAULT' || value === 'SCRAPPED') {
    return 'danger'
  }
  if (value === 'PENDING_PICKUP') {
    return 'warning'
  }
  return 'info'
}

async function reload(): Promise<void> {
  loading.value = true
  try {
    rows.value = await listBatteries(state.value || undefined, 200)
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
.hint {
  color: #6b7280;
  font-size: 12px;
}
</style>
