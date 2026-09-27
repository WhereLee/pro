<template>
  <div class="page-container">
    <el-card shadow="never">
      <!-- 搜索区 -->
      <el-form :model="query" inline class="search-form" @submit.prevent>
        <el-form-item label="用户名">
          <el-input v-model="query.username" placeholder="请输入" clearable style="width: 160px" />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="query.nickname" placeholder="请输入" clearable style="width: 160px" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="query.phone" placeholder="请输入" clearable style="width: 160px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="正常" :value="0" />
            <el-option label="停用" :value="1" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 工具条 -->
      <div class="table-toolbar">
        <el-button
          v-if="hasPerm('sys:user:save')"
          type="primary"
          :icon="Plus"
          @click="openEdit()"
        >
          新增
        </el-button>
        <el-button
          v-if="hasPerm('sys:user:remove')"
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

      <!-- 表格 -->
      <el-table
        v-loading="loading"
        :data="records"
        border
        stripe
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="46" />
        <el-table-column prop="username" label="用户名" min-width="110" />
        <el-table-column prop="nickname" label="昵称" min-width="110" />
        <el-table-column prop="phone" label="手机号" min-width="130" />
        <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip />
        <el-table-column label="部门" min-width="110">
          <template #default="{ row }">
            <span v-if="row.deptName">{{ row.deptName }}</span>
            <span v-else class="text-muted">未分配</span>
          </template>
        </el-table-column>
        <el-table-column label="角色" min-width="160">
          <template #default="{ row }">
            <el-tag
              v-for="rid in row.roleIds"
              :key="rid"
              size="small"
              type="info"
              class="role-tag"
            >
              {{ roleNameOf(rid) }}
            </el-tag>
            <span v-if="!row.roleIds?.length" class="text-muted">未分配</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.status === 0"
              :disabled="!hasPerm('sys:user:update')"
              @change="(val: boolean) => toggleStatus(row, val)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" min-width="170" />
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="hasPerm('sys:user:update')"
              link
              type="primary"
              @click="openEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              v-if="hasPerm('sys:user:resetPwd')"
              link
              type="warning"
              @click="openResetPwd(row)"
            >
              重置密码
            </el-button>
            <el-button
              v-if="hasPerm('sys:user:remove')"
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

    <!-- 新增/编辑对话框 -->
    <el-dialog
      v-model="editVisible"
      :title="editForm.id ? '编辑用户' : '新增用户'"
      width="560px"
      @closed="onEditClosed"
    >
      <el-form ref="editRef" :model="editForm" :rules="editRules" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="editForm.username" :disabled="!!editForm.id" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item v-if="!editForm.id" label="密码" prop="password">
          <el-input v-model="editForm.password" type="password" show-password placeholder="请输入密码" />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="editForm.nickname" placeholder="请输入昵称" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="editForm.phone" placeholder="请输入手机号" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="editForm.email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="editForm.roleIds" multiple placeholder="请选择角色" style="width: 100%">
            <el-option
              v-for="role in roleOptions"
              :key="role.id"
              :label="role.roleName"
              :value="role.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="editForm.status">
            <el-radio :value="0">正常</el-radio>
            <el-radio :value="1">停用</el-radio>
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

    <!-- 重置密码对话框 -->
    <el-dialog v-model="pwdVisible" title="重置密码" width="420px">
      <el-form ref="pwdRef" :model="pwdForm" :rules="pwdRules" label-width="80px">
        <el-form-item label="用户名">
          <el-input :model-value="pwdForm.username" disabled />
        </el-form-item>
        <el-form-item label="新密码" prop="password">
          <el-input v-model="pwdForm.password" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取 消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitResetPwd">确 定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Search, Refresh, Plus, Delete } from '@element-plus/icons-vue'
import { pageUsers, saveUser, updateUser, removeUsers, resetPassword, listRoles } from '@/api/system'
import type { SysUserVO, SysRoleVO } from '@/api/types'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const hasPerm = (perm: string) => userStore.hasPermission(perm)

const loading = ref(false)
const submitting = ref(false)
const records = ref<SysUserVO[]>([])
const total = ref(0)
const selected = ref<SysUserVO[]>([])
const roleOptions = ref<SysRoleVO[]>([])

const query = reactive({
  username: '',
  nickname: '',
  phone: '',
  status: undefined as number | undefined,
  pageNum: 1,
  pageSize: 10
})

/* ---------- 列表 ---------- */
async function loadData() {
  loading.value = true
  try {
    const res = await pageUsers({ ...query })
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
  query.username = ''
  query.nickname = ''
  query.phone = ''
  query.status = undefined
  handleSearch()
}

function onSelectionChange(rows: SysUserVO[]) {
  selected.value = rows
}

function roleNameOf(roleId: string) {
  return roleOptions.value.find((r) => r.id === roleId)?.roleName ?? roleId
}

async function toggleStatus(row: SysUserVO, enabled: boolean) {
  await updateUser({ id: row.id, username: row.username, status: enabled ? 0 : 1 })
  ElMessage.success('状态已更新')
  loadData()
}

async function handleDelete(row?: SysUserVO) {
  const ids = row ? [row.id] : selected.value.map((u) => u.id)
  if (ids.length === 0) return
  try {
    await ElMessageBox.confirm(
      row ? `确定删除用户【${row.username}】吗？` : `确定删除选中的 ${ids.length} 个用户吗？`,
      '提示',
      { type: 'warning' }
    )
  } catch {
    return
  }
  await removeUsers(ids)
  ElMessage.success('删除成功')
  loadData()
}

/* ---------- 新增/编辑 ---------- */
const editVisible = ref(false)
const editRef = ref<FormInstance>()
const editForm = reactive({
  id: '',
  username: '',
  password: '',
  nickname: '',
  phone: '',
  email: '',
  status: 0,
  remark: '',
  roleIds: [] as string[]
})

const editRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 2, max: 50, message: '长度 2~50', trigger: 'blur' }
  ],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  phone: [{ pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}

function openEdit(row?: SysUserVO) {
  if (row) {
    Object.assign(editForm, {
      id: row.id,
      username: row.username,
      password: '',
      nickname: row.nickname ?? '',
      phone: row.phone ?? '',
      email: row.email ?? '',
      status: row.status,
      remark: row.remark ?? '',
      roleIds: [...(row.roleIds ?? [])]
    })
  } else {
    Object.assign(editForm, {
      id: '',
      username: '',
      password: '',
      nickname: '',
      phone: '',
      email: '',
      status: 0,
      remark: '',
      roleIds: []
    })
  }
  editVisible.value = true
}

function onEditClosed() {
  editRef.value?.clearValidate()
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
    const payload = { ...editForm }
    // 编辑时密码留空表示不修改，后端会忽略该字段
    if (payload.id && !payload.password) delete (payload as any).password
    if (payload.id) {
      await updateUser(payload)
    } else {
      await saveUser(payload)
    }
    ElMessage.success(payload.id ? '修改成功' : '新增成功')
    editVisible.value = false
    loadData()
  } finally {
    submitting.value = false
  }
}

/* ---------- 重置密码 ---------- */
const pwdVisible = ref(false)
const pwdRef = ref<FormInstance>()
const pwdForm = reactive({ userId: '', username: '', password: '' })
const pwdRules: FormRules = {
  password: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 100, message: '长度 6~100', trigger: 'blur' }
  ]
}

function openResetPwd(row: SysUserVO) {
  pwdForm.userId = row.id
  pwdForm.username = row.username
  pwdForm.password = ''
  pwdVisible.value = true
}

async function submitResetPwd() {
  if (!pwdRef.value) return
  try {
    await pwdRef.value.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    await resetPassword(pwdForm.userId, pwdForm.password)
    ElMessage.success('密码已重置')
    pwdVisible.value = false
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  roleOptions.value = await listRoles().catch(() => [])
  loadData()
})
</script>

<style scoped>
.search-form {
  margin-bottom: 0;
}

.role-tag {
  margin-right: 4px;
}

.text-muted {
  color: #c0c4cc;
}
</style>
