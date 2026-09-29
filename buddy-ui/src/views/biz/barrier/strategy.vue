<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="table-toolbar">
        <el-button v-if="hasPerm('strategy:manage')" type="primary" :icon="Plus" @click="openEdit()">
          新增策略
        </el-button>
        <div class="spacer" />
        <el-button :icon="Refresh" circle @click="loadData" />
      </div>

      <el-table v-loading="loading" :data="records" border stripe>
        <el-table-column prop="name" label="策略名" min-width="140" />
        <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip />
        <el-table-column prop="priority" label="优先级" width="100" align="center" />
        <el-table-column label="启用" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="enabledTagType(row.enabled)">{{ enabledLabel(row.enabled) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button v-if="hasPerm('strategy:manage')" link type="primary" @click="openBind(row)">
              绑杆
            </el-button>
            <el-button v-if="hasPerm('strategy:manage')" link type="primary" @click="openEdit(row)">
              编辑
            </el-button>
            <el-button v-if="hasPerm('strategy:manage')" link type="warning" @click="toggleEnabled(row)">
              {{ row.enabled === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="hasPerm('strategy:manage')" link type="danger" @click="handleDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 绑杆（N:M 整体替换） -->
    <el-dialog v-model="bindVisible" :title="`绑定杆 · ${bindTarget?.name ?? ''}`" width="620px">
      <el-transfer
        v-model="boundIds"
        :data="transferData"
        :titles="['可选杆', '已绑定']"
        filterable
        filter-placeholder="搜索杆名"
      />
      <template #footer>
        <el-button @click="bindVisible = false">取 消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitBind">保 存</el-button>
      </template>
    </el-dialog>

    <!-- 新增/编辑 -->
    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑策略' : '新增策略'" width="520px">
      <el-form ref="editRef" :model="editForm" :rules="editRules" label-width="90px">
        <el-form-item label="策略名" prop="name">
          <el-input v-model="editForm.name" placeholder="如：工作日策略" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="editForm.priority" :min="0" :max="9999" />
          <span class="form-tip" style="margin-left: 8px">数值越大越优先</span>
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
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh, Plus } from '@element-plus/icons-vue'
import {
  listStrategies,
  createStrategy,
  updateStrategy,
  setStrategyEnabled,
  deleteStrategy,
  getStrategyBarriers,
  setStrategyBarriers,
  listBarriers,
  type StrategyVO,
  type BarrierVO
} from '@/api/biz/barrier'
import { useUserStore } from '@/store/user'
import { enabledLabel, enabledTagType } from './helpers'

const userStore = useUserStore()
const hasPerm = (perm: string) => userStore.hasPermission(perm)

const loading = ref(false)
const submitting = ref(false)
const records = ref<StrategyVO[]>([])

const barriers = ref<BarrierVO[]>([])
const transferData = computed(() => barriers.value.map((b) => ({ key: b.id, label: b.name })))

const bindVisible = ref(false)
const bindTarget = ref<StrategyVO | null>(null)
const boundIds = ref<string[]>([])

const editVisible = ref(false)
const editRef = ref<FormInstance>()
const editForm = reactive({ id: '', name: '', description: '', priority: 0, enabled: 1 })
const editRules: FormRules = {
  name: [{ required: true, message: '请输入策略名', trigger: 'blur' }]
}

async function loadData() {
  loading.value = true
  try {
    records.value = await listStrategies()
  } finally {
    loading.value = false
  }
}

async function openBind(row: StrategyVO) {
  bindTarget.value = row
  bindVisible.value = true
  // 杆列表用于穿梭框数据源；无 barrier 读权限时容错为空
  barriers.value = await listBarriers().catch(() => [])
  boundIds.value = await getStrategyBarriers(row.id)
}

async function submitBind() {
  if (!bindTarget.value) return
  submitting.value = true
  try {
    await setStrategyBarriers(bindTarget.value.id, boundIds.value)
    ElMessage.success('绑定已更新')
    bindVisible.value = false
  } finally {
    submitting.value = false
  }
}

function openEdit(row?: StrategyVO) {
  Object.assign(editForm, {
    id: row?.id ?? '',
    name: row?.name ?? '',
    description: row?.description ?? '',
    priority: row?.priority ?? 0,
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
      name: editForm.name,
      description: editForm.description,
      priority: editForm.priority,
      enabled: editForm.enabled
    }
    if (editForm.id) {
      await updateStrategy(editForm.id, payload)
    } else {
      await createStrategy(payload)
    }
    ElMessage.success(editForm.id ? '修改成功' : '新增成功')
    editVisible.value = false
    loadData()
  } finally {
    submitting.value = false
  }
}

async function toggleEnabled(row: StrategyVO) {
  await setStrategyEnabled(row.id, row.enabled === 1 ? 0 : 1)
  ElMessage.success(row.enabled === 1 ? '已停用' : '已启用')
  loadData()
}

async function handleDelete(row: StrategyVO) {
  try {
    await ElMessageBox.confirm(`确定删除策略【${row.name}】吗？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  await deleteStrategy(row.id)
  ElMessage.success('删除成功')
  loadData()
}

onMounted(loadData)
</script>

<style scoped>
.form-tip {
  font-size: 12px;
  color: #a8abb2;
}
</style>
