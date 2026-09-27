<template>
  <div class="page-container">
    <el-card shadow="never">
      <el-form :model="query" inline class="search-form" @submit.prevent>
        <el-form-item label="角色名称">
          <el-input v-model="query.roleName" placeholder="请输入" clearable style="width: 160px" />
        </el-form-item>
        <el-form-item label="角色标识">
          <el-input v-model="query.roleKey" placeholder="如 admin" clearable style="width: 160px" />
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

      <div class="table-toolbar">
        <el-button v-if="hasPerm('sys:role:save')" type="primary" :icon="Plus" @click="openEdit()">
          新增
        </el-button>
        <el-button
          v-if="hasPerm('sys:role:remove')"
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
        <el-table-column prop="roleName" label="角色名称" min-width="130" />
        <el-table-column prop="roleKey" label="角色标识" min-width="130">
          <template #default="{ row }">
            <el-tag :type="row.roleKey === 'admin' ? 'danger' : 'info'" size="small">
              {{ row.roleKey }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="菜单权限" min-width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" type="success">{{ row.menuIds?.length ?? 0 }} 项</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sort" label="排序" width="80" align="center" />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'success' : 'danger'" size="small">
              {{ row.status === 0 ? '正常' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" min-width="170" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="hasPerm('sys:role:update')"
              link
              type="primary"
              @click="openEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              v-if="hasPerm('sys:role:update')"
              link
              type="success"
              @click="openAuth(row)"
            >
              分配权限
            </el-button>
            <el-button
              v-if="hasPerm('sys:role:remove')"
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

    <!-- 新增/编辑 -->
    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑角色' : '新增角色'" width="520px">
      <el-form ref="editRef" :model="editForm" :rules="editRules" label-width="80px">
        <el-form-item label="角色名称" prop="roleName">
          <el-input v-model="editForm.roleName" placeholder="如：系统管理员" />
        </el-form-item>
        <el-form-item label="角色标识" prop="roleKey">
          <el-input v-model="editForm.roleKey" placeholder="小写字母开头，如 admin" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="editForm.sort" :min="0" :max="999" />
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

    <!-- 分配权限 -->
    <el-dialog v-model="authVisible" title="分配权限" width="520px">
      <el-tree
        ref="treeRef"
        :data="menuTreeData"
        show-checkbox
        node-key="id"
        default-expand-all
        :props="{ label: 'menuName', children: 'children' }"
        class="menu-tree"
      >
        <template #default="{ data }">
          <span class="tree-node">
            <span>{{ data.menuName }}</span>
            <el-tag v-if="data.menuType === 'F'" size="small" type="warning" class="node-tag">
              {{ data.perms }}
            </el-tag>
            <el-tag v-else-if="data.perms" size="small" type="info" class="node-tag">
              {{ data.perms }}
            </el-tag>
          </span>
        </template>
      </el-tree>
      <template #footer>
        <el-button @click="authVisible = false">取 消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitAuth">确 定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, nextTick } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Search, Refresh, Plus, Delete } from '@element-plus/icons-vue'
import {
  pageRoles,
  saveRole,
  updateRole,
  grantRole,
  removeRoles,
  menuTree,
  roleMenuIds
} from '@/api/system'
import type { SysRoleVO, MenuVO } from '@/api/types'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const hasPerm = (perm: string) => userStore.hasPermission(perm)

const loading = ref(false)
const submitting = ref(false)
const records = ref<SysRoleVO[]>([])
const total = ref(0)
const selected = ref<SysRoleVO[]>([])
const menuTreeData = ref<MenuVO[]>([])

const query = reactive({
  roleName: '',
  roleKey: '',
  status: undefined as number | undefined,
  pageNum: 1,
  pageSize: 10
})

async function loadData() {
  loading.value = true
  try {
    const res = await pageRoles({ ...query })
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
  query.roleName = ''
  query.roleKey = ''
  query.status = undefined
  handleSearch()
}

function onSelectionChange(rows: SysRoleVO[]) {
  selected.value = rows
}

async function handleDelete(row?: SysRoleVO) {
  const ids = row ? [row.id] : selected.value.map((r) => r.id)
  if (ids.length === 0) return
  try {
    await ElMessageBox.confirm(
      row ? `确定删除角色【${row.roleName}】吗？` : `确定删除选中的 ${ids.length} 个角色吗？`,
      '提示',
      { type: 'warning' }
    )
  } catch {
    return
  }
  await removeRoles(ids)
  ElMessage.success('删除成功')
  loadData()
}

/* ---------- 新增/编辑 ---------- */
const editVisible = ref(false)
const editRef = ref<FormInstance>()
const editForm = reactive({
  id: '',
  roleName: '',
  roleKey: '',
  sort: 1,
  status: 0,
  remark: ''
})
const editRules: FormRules = {
  roleName: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
  roleKey: [
    { required: true, message: '请输入角色标识', trigger: 'blur' },
    { pattern: /^[a-z][a-z0-9_]{1,49}$/, message: '小写字母开头，仅含小写字母、数字、下划线', trigger: 'blur' }
  ]
}

function openEdit(row?: SysRoleVO) {
  Object.assign(editForm, {
    id: row?.id ?? '',
    roleName: row?.roleName ?? '',
    roleKey: row?.roleKey ?? '',
    sort: row?.sort ?? 1,
    status: row?.status ?? 0,
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
      // 不传 menuIds，后端不会改动已有授权——授权调整走"分配权限"入口
      await updateRole({ ...editForm, menuIds: undefined })
    } else {
      await saveRole({ ...editForm })
    }
    ElMessage.success(editForm.id ? '修改成功' : '新增成功')
    editVisible.value = false
    loadData()
  } finally {
    submitting.value = false
  }
}

/* ---------- 分配权限 ---------- */
const authVisible = ref(false)
const treeRef = ref<any>()
const currentRoleId = ref('')

async function openAuth(row: SysRoleVO) {
  currentRoleId.value = row.id
  if (menuTreeData.value.length === 0) {
    menuTreeData.value = await menuTree()
  }
  authVisible.value = true
  const checked = await roleMenuIds(row.id)
  // 必须等 tree 渲染完成后再回显，否则 setCheckedKeys 拿不到节点
  await nextTick()
  treeRef.value?.setCheckedKeys(checked, false)
}

async function submitAuth() {
  const tree = treeRef.value
  if (!tree) return
  // getCheckedKeys 只返回全选叶子；半选的父节点需要一并提交，
  // 否则父菜单被勾选但子菜单未全选时，父菜单记录会丢失
  const menuIds = [...tree.getCheckedKeys(), ...tree.getHalfCheckedKeys()]
  submitting.value = true
  try {
    // 走独立的授权接口：后端会以 GRANT 类型单独记一条日志，便于审计
    await grantRole({ id: currentRoleId.value, menuIds })
    ElMessage.success('权限已更新')
    authVisible.value = false
    loadData()
  } finally {
    submitting.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.menu-tree {
  max-height: 420px;
  overflow-y: auto;
}

.tree-node {
  display: flex;
  align-items: center;
  gap: 8px;
}

.node-tag {
  font-size: 11px;
}
</style>
