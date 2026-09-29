<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="table-toolbar">
        <el-button v-if="hasPerm('schedule:manage')" type="primary" :icon="Plus" @click="openEdit()">
          新增计划点
        </el-button>
        <div class="spacer" />
        <el-button :icon="Refresh" circle @click="loadData" />
      </div>

      <el-table v-loading="loading" :data="records" border stripe>
        <el-table-column prop="timeOfDay" label="时刻" width="120" align="center" />
        <el-table-column label="目标状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.planState === 'OPEN' ? 'success' : 'danger'">
              {{ planStateLabel(row.planState) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="所属策略" min-width="140">
          <template #default="{ row }">{{ strategyName(row.strategyId) }}</template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="启用" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="enabledTagType(row.enabled)">{{ enabledLabel(row.enabled) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button v-if="hasPerm('schedule:manage')" link type="primary" @click="openEdit(row)">
              编辑
            </el-button>
            <el-button v-if="hasPerm('schedule:manage')" link type="warning" @click="toggleEnabled(row)">
              {{ row.enabled === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="hasPerm('schedule:manage')" link type="danger" @click="handleDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑计划点' : '新增计划点'" width="520px">
      <el-form ref="editRef" :model="editForm" :rules="editRules" label-width="90px">
        <el-form-item label="时刻" prop="timeOfDay">
          <el-time-picker
            v-model="editForm.timeOfDay"
            value-format="HH:mm:ss"
            placeholder="选择每日时刻"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="目标状态" prop="planState">
          <el-select v-model="editForm.planState" placeholder="请选择" style="width: 100%">
            <el-option v-for="o in PLAN_STATE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属策略">
          <el-select v-model="editForm.strategyId" placeholder="默认策略" clearable style="width: 100%">
            <el-option v-for="s in strategies" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
          <div class="form-tip">留空则归入默认策略</div>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="editForm.name" placeholder="如：早高峰开杆" />
        </el-form-item>
        <el-form-item label="启用">
          <el-radio-group v-model="editForm.enabled">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取 消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitEdit">确 定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh, Plus } from '@element-plus/icons-vue'
import {
  listSchedules,
  createSchedule,
  updateSchedule,
  setScheduleEnabled,
  deleteSchedule,
  listStrategies,
  type ScheduleVO,
  type StrategyVO
} from '@/api/biz/barrier'
import { useUserStore } from '@/store/user'
import { planStateLabel, enabledLabel, enabledTagType, PLAN_STATE_OPTIONS } from './helpers'

const userStore = useUserStore()
const hasPerm = (perm: string) => userStore.hasPermission(perm)

const loading = ref(false)
const submitting = ref(false)
const records = ref<ScheduleVO[]>([])
const strategies = ref<StrategyVO[]>([])

const editVisible = ref(false)
const editRef = ref<FormInstance>()
const editForm = reactive({
  id: '',
  timeOfDay: '',
  planState: 'OPEN',
  strategyId: '' as string | '',
  name: '',
  enabled: 1
})
const editRules: FormRules = {
  timeOfDay: [{ required: true, message: '请选择时刻', trigger: 'change' }],
  planState: [{ required: true, message: '请选择目标状态', trigger: 'change' }]
}

function strategyName(id: string | null): string {
  if (!id) return '默认策略'
  return strategies.value.find((s) => s.id === id)?.name ?? `#${id}`
}

async function loadData() {
  loading.value = true
  try {
    records.value = await listSchedules()
  } finally {
    loading.value = false
  }
}

function openEdit(row?: ScheduleVO) {
  Object.assign(editForm, {
    id: row?.id ?? '',
    timeOfDay: row?.timeOfDay ?? '',
    planState: row?.planState ?? 'OPEN',
    strategyId: row?.strategyId ?? '',
    name: row?.name ?? '',
    enabled: row?.enabled ?? 1
  })
  editVisible.value = true
}

async function submitEdit() {
  if (!editRef.value) return
  try {
    await editRef.value.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    const payload = {
      timeOfDay: editForm.timeOfDay,
      planState: editForm.planState,
      strategyId: editForm.strategyId || null,
      name: editForm.name,
      enabled: editForm.enabled
    }
    if (editForm.id) {
      await updateSchedule(editForm.id, payload)
    } else {
      await createSchedule(payload)
    }
    ElMessage.success(editForm.id ? '修改成功' : '新增成功')
    editVisible.value = false
    loadData()
  } finally {
    submitting.value = false
  }
}

async function toggleEnabled(row: ScheduleVO) {
  await setScheduleEnabled(row.id, row.enabled === 1 ? 0 : 1)
  ElMessage.success(row.enabled === 1 ? '已停用' : '已启用')
  loadData()
}

async function handleDelete(row: ScheduleVO) {
  try {
    await ElMessageBox.confirm(`确定删除计划点【${row.timeOfDay}】吗？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  await deleteSchedule(row.id)
  ElMessage.success('删除成功')
  loadData()
}

onMounted(async () => {
  // 策略下拉用于展示/归属；无 strategy:manage 权限时容错为空
  strategies.value = await listStrategies().catch(() => [])
  loadData()
})
</script>

<style scoped>
.form-tip {
  font-size: 12px;
  color: #a8abb2;
  margin-top: 4px;
  line-height: 1.4;
}
</style>
