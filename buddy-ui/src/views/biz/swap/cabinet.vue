<template>
  <div class="app-container">
    <el-card shadow="never" class="mb">
      <el-form :inline="true" @submit.prevent>
        <el-form-item>
          <el-button type="primary" @click="reload">刷新柜机列表</el-button>
        </el-form-item>
        <el-form-item>
          <span class="hint">柜机与仓位是只读视图；建账、电池入仓、仓位停用在台账接口侧操作。</span>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" size="small">
        <el-table-column prop="cabinet_no" label="柜机编号" width="180" />
        <el-table-column prop="site_id" label="站点" width="110" />
        <el-table-column prop="cabinet_model" label="型号" width="130" />
        <el-table-column label="柜机状态" width="130">
          <template #default="{ row }">
            <el-tag :type="row.cabinet_state === 'NORMAL' ? 'success' : 'warning'" size="small">
              {{ row.cabinet_state }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="online_state" label="在线" width="100" />
        <el-table-column prop="slot_total" label="仓位数" width="90" />
        <el-table-column prop="charging_slots" label="在充" width="80" />
        <el-table-column prop="unavailable_slots" label="不可用" width="90" />
        <el-table-column prop="locked_reason" label="锁定原因" min-width="140" show-overflow-tooltip />
        <el-table-column prop="last_swap_at" label="最近换电" width="165" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openSlots(row.cabinet_no)">仓位明细</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="slotsVisible" size="55%" :title="'仓位明细 ' + currentCabinet">
      <el-table :data="slots" size="small">
        <el-table-column prop="slotNo" label="仓号" width="80" />
        <el-table-column prop="slotState" label="仓位状态" width="150" />
        <el-table-column prop="doorState" label="门" width="100" />
        <el-table-column prop="lockState" label="锁" width="100" />
        <el-table-column prop="chargeState" label="充电" width="120" />
        <el-table-column prop="batteryId" label="电池 ID" width="150" />
        <el-table-column prop="lastTemp" label="温度" width="90" />
        <el-table-column prop="faultCode" label="故障码" min-width="120" show-overflow-tooltip />
        <el-table-column prop="disabledFlag" label="停用" width="80" />
      </el-table>
      <p class="hint">
        这里给的是电池主键：电池编码、SOC/SOH、持有人与隔离原因在"电池资产"页查看（那一页敏感级别更高，权限码不同）。
      </p>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { cabinetSlots, listCabinets } from '@/api/swapOrder'

const rows = ref<Record<string, unknown>[]>([])
const slots = ref<Record<string, unknown>[]>([])
const loading = ref(false)
const slotsVisible = ref(false)
const currentCabinet = ref('')

async function reload(): Promise<void> {
  loading.value = true
  try {
    rows.value = await listCabinets(100)
  } finally {
    loading.value = false
  }
}

async function openSlots(cabinetNo: string): Promise<void> {
  currentCabinet.value = cabinetNo
  slots.value = await cabinetSlots(cabinetNo)
  slotsVisible.value = true
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
