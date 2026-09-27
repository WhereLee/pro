<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="table-toolbar">
        <el-button v-if="hasPerm('sys:dept:save')" type="primary" :icon="Plus" @click="openEdit()">
          新增
        </el-button>
        <div class="spacer" />
        <el-text type="info" size="small">
          ancestors 字段冗余存祖级链，用于"本部门及以下"的数据权限快速过滤
        </el-text>
        <el-button :icon="Refresh" circle @click="loadData" />
      </div>

      <el-table
        v-loading="loading"
        :data="treeData"
        row-key="id"
        border
        default-expand-all
        :tree-props="{ children: 'children' }"
      >
        <el-table-column prop="deptName" label="部门名称" min-width="180" />
        <el-table-column prop="ancestors" label="祖级链" min-width="140" />
        <el-table-column prop="sort" label="排序" width="70" align="center" />
        <el-table-column prop="leader" label="负责人" width="100" />
        <el-table-column prop="phone" label="联系电话" width="130" />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 0 ? 'success' : 'danger'">
              {{ row.status === 0 ? '正常' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="hasPerm('sys:dept:save')"
              link
              type="primary"
              @click="openEdit(undefined, row)"
            >
              新增下级
            </el-button>
            <el-button v-if="hasPerm('sys:dept:update')" link type="primary" @click="openEdit(row)">
              编辑
            </el-button>
            <el-button v-if="hasPerm('sys:dept:remove')" link type="danger" @click="handleDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑部门' : '新增部门'" width="520px">
      <el-form ref="editRef" :model="editForm" :rules="editRules" label-width="90px">
        <el-form-item label="上级部门" prop="parentId">
          <el-tree-select
            v-model="editForm.parentId"
            :data="treeData"
            :props="{ label: 'deptName', children: 'children' }"
            value-key="id"
            check-strictly
            default-expand-all
            placeholder="根目录"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="部门名称" prop="deptName">
          <el-input v-model="editForm.deptName" placeholder="如：技术部" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="editForm.sort" :min="0" :max="999" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-input v-model="editForm.leader" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="editForm.phone" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="editForm.status">
            <el-radio :value="0">正常</el-radio>
            <el-radio :value="1">停用</el-radio>
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
import { deptTree, saveDept, updateDept, removeDept, type SysDeptVO } from '@/api/system'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const hasPerm = (perm: string) => userStore.hasPermission(perm)

const loading = ref(false)
const submitting = ref(false)
const treeData = ref<SysDeptVO[]>([])

const editVisible = ref(false)
const editRef = ref<FormInstance>()
const editForm = reactive({
  id: '',
  parentId: '0',
  deptName: '',
  sort: 1,
  leader: '',
  phone: '',
  status: 0
})

const editRules: FormRules = {
  deptName: [{ required: true, message: '请输入部门名称', trigger: 'blur' }]
}

async function loadData() {
  loading.value = true
  try {
    treeData.value = await deptTree()
  } finally {
    loading.value = false
  }
}

function openEdit(row?: SysDeptVO, parent?: SysDeptVO) {
  Object.assign(editForm, {
    id: row?.id ?? '',
    parentId: row?.parentId ?? parent?.id ?? '0',
    deptName: row?.deptName ?? '',
    sort: row?.sort ?? 1,
    leader: row?.leader ?? '',
    phone: row?.phone ?? '',
    status: row?.status ?? 0
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
      await updateDept({ ...editForm })
    } else {
      await saveDept({ ...editForm })
    }
    ElMessage.success(editForm.id ? '修改成功' : '新增成功')
    editVisible.value = false
    loadData()
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row: SysDeptVO) {
  try {
    await ElMessageBox.confirm(`确定删除部门【${row.deptName}】吗？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  await removeDept(row.id)
  ElMessage.success('删除成功')
  loadData()
}

onMounted(loadData)
</script>
