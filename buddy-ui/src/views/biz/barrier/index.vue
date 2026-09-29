<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="table-toolbar">
        <el-button v-if="hasPerm('barrier:manage')" type="primary" :icon="Plus" @click="openEdit()">
          新增杆
        </el-button>
        <div class="spacer" />
        <el-button :icon="Refresh" circle @click="loadData" />
      </div>

      <el-table v-loading="loading" :data="records" border stripe>
        <el-table-column prop="name" label="杆名称" min-width="140" />
        <el-table-column prop="location" label="位置" min-width="140" show-overflow-tooltip />
        <el-table-column label="当前状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="stateTagType(row.state)">{{ stateLabel(row.state) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="手动覆盖" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.override" size="small" type="warning">覆盖中</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="enabledTagType(row.enabled)">{{ enabledLabel(row.enabled) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="320" fixed="right">
          <template #default="{ row }">
            <el-button v-if="hasPerm('barrier:manual')" link type="success" @click="manual(row, 'OPEN')">
              开
            </el-button>
            <el-button v-if="hasPerm('barrier:manual')" link type="danger" @click="manual(row, 'CLOSE')">
              关
            </el-button>
            <el-button v-if="hasPerm('barrier:events:read')" link type="primary" @click="openEvents(row)">
              事件
            </el-button>
            <el-button v-if="hasPerm('barrier:manage')" link type="primary" @click="openEdit(row)">
              编辑
            </el-button>
            <el-button v-if="hasPerm('barrier:manage')" link type="warning" @click="toggleEnabled(row)">
              {{ row.enabled === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="hasPerm('barrier:manage')" link type="danger" @click="handleDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 事件流抽屉 -->
    <el-drawer v-model="eventsVisible" :title="`事件流 · ${eventsTarget?.name ?? ''}`" size="480px">
      <el-table v-loading="eventsLoading" :data="events" size="small" border>
        <el-table-column prop="occurredAt" label="时间" min-width="160" />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="stateTagType(row.state)">{{ stateLabel(row.state) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="80" align="center">
          <template #default="{ row }">{{ sourceLabel(row.source) }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- 新增/编辑对话框 -->
    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑杆' : '新增杆'" width="520px">
      <el-form ref="editRef" :model="editForm" :rules="editRules" label-width="90px">
        <el-form-item label="杆名称" prop="name">
          <el-input v-model="editForm.name" placeholder="如：1号杆" />
        </el-form-item>
        <el-form-item label="位置">
          <el-input v-model="editForm.location" placeholder="如：东门" />
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
  listBarriers,
  listBarrierEvents,
  manualBarrier,
  createBarrier,
  updateBarrier,
  setBarrierEnabled,
  deleteBarrier,
  type BarrierVO,
  type EventVO
} from '@/api/biz/barrier'
import { useUserStore } from '@/store/user'
import { stateLabel, stateTagType, sourceLabel, enabledLabel, enabledTagType } from './helpers'

const userStore = useUserStore()
const hasPerm = (perm: string) => userStore.hasPermission(perm)

const loading = ref(false)
const submitting = ref(false)
const records = ref<BarrierVO[]>([])

const eventsVisible = ref(false)
const eventsLoading = ref(false)
const events = ref<EventVO[]>([])
const eventsTarget = ref<BarrierVO | null>(null)

const editVisible = ref(false)
const editRef = ref<FormInstance>()
const editForm = reactive({ id: '', name: '', location: '', enabled: 1 })
const editRules: FormRules = {
  name: [{ required: true, message: '请输入杆名称', trigger: 'blur' }]
}

async function loadData() {
  loading.value = true
  try {
    records.value = await listBarriers()
  } finally {
    loading.value = false
  }
}

async function manual(row: BarrierVO, action: string) {
  // 冲突(409)由请求拦截器统一提示，这里成功后刷新状态即可
  await manualBarrier(row.id, action)
  ElMessage.success(action === 'OPEN' ? '已开启' : '已关闭')
  loadData()
}

async function openEvents(row: BarrierVO) {
  eventsTarget.value = row
  eventsVisible.value = true
  eventsLoading.value = true
  try {
    events.value = await listBarrierEvents(row.id, 50)
  } finally {
    eventsLoading.value = false
  }
}

function openEdit(row?: BarrierVO) {
  Object.assign(editForm, {
    id: row?.id ?? '',
    name: row?.name ?? '',
    location: row?.location ?? '',
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
    const payload = { name: editForm.name, location: editForm.location, enabled: editForm.enabled }
    if (editForm.id) {
      await updateBarrier(editForm.id, payload)
    } else {
      await createBarrier(payload)
    }
    ElMessage.success(editForm.id ? '修改成功' : '新增成功')
    editVisible.value = false
    loadData()
  } finally {
    submitting.value = false
  }
}

async function toggleEnabled(row: BarrierVO) {
  await setBarrierEnabled(row.id, row.enabled === 1 ? 0 : 1)
  ElMessage.success(row.enabled === 1 ? '已停用' : '已启用')
  loadData()
}

async function handleDelete(row: BarrierVO) {
  try {
    await ElMessageBox.confirm(`确定删除杆【${row.name}】吗？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  await deleteBarrier(row.id)
  ElMessage.success('删除成功')
  loadData()
}

onMounted(loadData)
</script>

<style scoped>
.muted {
  color: #a8abb2;
}
</style>
