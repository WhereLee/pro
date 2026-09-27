<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="table-toolbar">
        <el-button v-if="hasPerm('sys:job:save')" type="primary" :icon="Plus" @click="openEdit()">
          新增
        </el-button>
        <el-button
          v-if="hasPerm('sys:job:remove')"
          type="danger"
          :icon="Delete"
          :disabled="selected.length === 0"
          @click="handleDelete()"
        >
          批量删除
        </el-button>
        <div class="spacer" />
        <el-button :icon="Refresh" circle @click="loadData" />
      </div>

      <el-table v-loading="loading" :data="records" border stripe @selection-change="onSelectionChange">
        <el-table-column type="selection" width="46" />
        <el-table-column prop="jobName" label="任务名称" min-width="140" />
        <el-table-column prop="jobGroup" label="分组" width="100" />
        <el-table-column prop="beanName" label="执行体" min-width="120" />
        <el-table-column prop="params" label="参数" min-width="100" show-overflow-tooltip />
        <el-table-column prop="cronExpression" label="cron 表达式" min-width="150" />
        <el-table-column label="并发" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.concurrent === 0 ? 'warning' : 'info'">
              {{ row.concurrent === 0 ? '允许' : '禁止' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 0 ? 'success' : 'info'">
              {{ row.status === 0 ? '运行中' : '已暂停' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="hasPerm('sys:job:update')"
              link
              type="success"
              @click="runOnce(row)"
            >
              执行一次
            </el-button>
            <el-button
              v-if="hasPerm('sys:job:update')"
              link
              type="warning"
              @click="toggleStatus(row)"
            >
              {{ row.status === 0 ? '暂停' : '恢复' }}
            </el-button>
            <el-button v-if="hasPerm('sys:job:update')" link type="primary" @click="openEdit(row)">
              编辑
            </el-button>
            <el-button v-if="hasPerm('sys:job:remove')" link type="danger" @click="handleDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑任务' : '新增任务'" width="600px">
      <el-form ref="editRef" :model="editForm" :rules="editRules" label-width="100px">
        <el-form-item label="任务名称" prop="jobName">
          <el-input v-model="editForm.jobName" placeholder="如：数据同步" />
        </el-form-item>
        <el-form-item label="执行体" prop="beanName">
          <el-select v-model="editForm.beanName" placeholder="请选择" style="width: 100%">
            <el-option v-for="b in beanNames" :key="b" :label="b" :value="b" />
          </el-select>
          <div class="form-tip">列表来自容器内所有 ITask 实现类</div>
        </el-form-item>
        <el-form-item label="cron 表达式" prop="cronExpression">
          <el-input v-model="editForm.cronExpression" placeholder="如 0/10 * * * * ?（每 10 秒）" />
          <div class="form-tip">格式：秒 分 时 日 月 周 年（年可省略）</div>
        </el-form-item>
        <el-form-item label="参数">
          <el-input v-model="editForm.params" placeholder="会原样传给执行体" />
        </el-form-item>
        <el-form-item label="分组">
          <el-input v-model="editForm.jobGroup" />
        </el-form-item>
        <el-form-item label="并发">
          <el-radio-group v-model="editForm.concurrent">
            <el-radio :value="1">禁止并发</el-radio>
            <el-radio :value="0">允许并发</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editForm.remark" type="textarea" :rows="2" />
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
import { Refresh, Plus, Delete } from '@element-plus/icons-vue'
import {
  pageJobs,
  taskBeans,
  saveJob,
  updateJob,
  removeJobs,
  pauseJob,
  resumeJob,
  runJob,
  type SysJob
} from '@/api/job'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const hasPerm = (perm: string) => userStore.hasPermission(perm)

const loading = ref(false)
const submitting = ref(false)
const records = ref<SysJob[]>([])
const selected = ref<SysJob[]>([])
const beanNames = ref<string[]>([])

const editVisible = ref(false)
const editRef = ref<FormInstance>()
const editForm = reactive({
  id: '',
  jobName: '',
  jobGroup: 'DEFAULT',
  beanName: '',
  params: '',
  cronExpression: '',
  concurrent: 1,
  misfirePolicy: 1,
  remark: ''
})

const editRules: FormRules = {
  jobName: [{ required: true, message: '请输入任务名称', trigger: 'blur' }],
  beanName: [{ required: true, message: '请选择执行体', trigger: 'change' }],
  cronExpression: [{ required: true, message: '请输入 cron 表达式', trigger: 'blur' }]
}

async function loadData() {
  loading.value = true
  try {
    const res = await pageJobs({ pageNum: 1, pageSize: 100 })
    records.value = res.records
  } finally {
    loading.value = false
  }
}

function onSelectionChange(rows: SysJob[]) {
  selected.value = rows
}

function openEdit(row?: SysJob) {
  Object.assign(editForm, {
    id: row?.id ?? '',
    jobName: row?.jobName ?? '',
    jobGroup: row?.jobGroup ?? 'DEFAULT',
    beanName: row?.beanName ?? '',
    params: row?.params ?? '',
    cronExpression: row?.cronExpression ?? '',
    concurrent: row?.concurrent ?? 1,
    misfirePolicy: row?.misfirePolicy ?? 1,
    remark: row?.remark ?? ''
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
    if (editForm.id) {
      await updateJob({ ...editForm })
    } else {
      await saveJob({ ...editForm })
    }
    ElMessage.success(editForm.id ? '修改成功' : '新增成功')
    editVisible.value = false
    loadData()
  } finally {
    submitting.value = false
  }
}

async function runOnce(row: SysJob) {
  await runJob(row.id)
  ElMessage.success('已触发，请在后端日志查看执行结果')
}

async function toggleStatus(row: SysJob) {
  if (row.status === 0) {
    await pauseJob(row.id)
    ElMessage.success('已暂停')
  } else {
    await resumeJob(row.id)
    ElMessage.success('已恢复')
  }
  loadData()
}

async function handleDelete(row?: SysJob) {
  const ids = row ? [row.id] : selected.value.map((j) => j.id)
  if (ids.length === 0) return
  try {
    await ElMessageBox.confirm(
      row ? `确定删除任务【${row.jobName}】吗？` : `确定删除选中的 ${ids.length} 个任务吗？`,
      '提示',
      { type: 'warning' }
    )
  } catch {
    return
  }
  await removeJobs(ids)
  ElMessage.success('删除成功')
  loadData()
}

onMounted(async () => {
  beanNames.value = await taskBeans().catch(() => [])
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
