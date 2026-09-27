<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="table-toolbar">
        <el-button v-if="hasPerm('sys:menu:save')" type="primary" :icon="Plus" @click="openEdit()">
          新增
        </el-button>
        <el-button :icon="Refresh" circle @click="loadData" />
        <div class="spacer" />
        <el-text type="info" size="small">
          M=目录 / C=菜单 / F=按钮（按钮的"权限标识"同时用于后端接口鉴权）
        </el-text>
      </div>

      <el-table
        v-loading="loading"
        :data="treeData"
        row-key="id"
        border
        default-expand-all
        :tree-props="{ children: 'children' }"
      >
        <el-table-column prop="menuName" label="菜单名称" min-width="180" />
        <el-table-column label="类型" width="80" align="center">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.menuType === 'M' ? '' : row.menuType === 'C' ? 'success' : 'warning'"
            >
              {{ typeLabel(row.menuType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="icon" label="图标" width="90" align="center">
          <template #default="{ row }">
            <el-icon v-if="row.icon"><component :is="row.icon" /></el-icon>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="path" label="路由地址" min-width="120" />
        <el-table-column prop="component" label="组件路径" min-width="180" show-overflow-tooltip />
        <el-table-column prop="perms" label="权限标识" min-width="160" />
        <el-table-column prop="sort" label="排序" width="70" align="center" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="hasPerm('sys:menu:save') && row.menuType !== 'F'"
              link
              type="primary"
              @click="openEdit(undefined, row)"
            >
              新增子项
            </el-button>
            <el-button v-if="hasPerm('sys:menu:update')" link type="primary" @click="openEdit(row)">
              编辑
            </el-button>
            <el-button v-if="hasPerm('sys:menu:remove')" link type="danger" @click="handleDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑菜单' : '新增菜单'" width="560px">
      <el-form ref="editRef" :model="editForm" :rules="editRules" label-width="90px">
        <el-form-item label="上级菜单" prop="parentId">
          <el-tree-select
            v-model="editForm.parentId"
            :data="treeData"
            :props="{ label: 'menuName', children: 'children' }"
            value-key="id"
            check-strictly
            default-expand-all
            placeholder="根目录"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="菜单类型" prop="menuType">
          <el-radio-group v-model="editForm.menuType">
            <el-radio value="M">目录</el-radio>
            <el-radio value="C">菜单</el-radio>
            <el-radio value="F">按钮</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="菜单名称" prop="menuName">
          <el-input v-model="editForm.menuName" placeholder="如：用户管理" />
        </el-form-item>
        <el-form-item v-if="editForm.menuType !== 'F'" label="路由地址" prop="path">
          <el-input v-model="editForm.path" placeholder="目录填 /system，菜单填 user" />
        </el-form-item>
        <el-form-item v-if="editForm.menuType === 'C'" label="组件路径" prop="component">
          <el-input v-model="editForm.component" placeholder="如 system/user/index" />
        </el-form-item>
        <el-form-item label="权限标识">
          <el-input v-model="editForm.perms" placeholder="如 sys:user:list" />
        </el-form-item>
        <el-form-item v-if="editForm.menuType !== 'F'" label="图标">
          <el-input v-model="editForm.icon" placeholder="Element Plus 图标名，如 User" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="editForm.sort" :min="0" :max="999" />
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
import { menuTree, saveMenu, updateMenu, removeMenu } from '@/api/system'
import type { MenuVO } from '@/api/types'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const hasPerm = (perm: string) => userStore.hasPermission(perm)

const loading = ref(false)
const submitting = ref(false)
const treeData = ref<MenuVO[]>([])

const editVisible = ref(false)
const editRef = ref<FormInstance>()
const editForm = reactive({
  id: '',
  parentId: '0',
  menuName: '',
  menuType: 'C' as 'M' | 'C' | 'F',
  path: '',
  component: '',
  perms: '',
  icon: '',
  sort: 1,
  visible: 0
})

const editRules: FormRules = {
  menuName: [{ required: true, message: '请输入菜单名称', trigger: 'blur' }]
}

function typeLabel(type: string) {
  return type === 'M' ? '目录' : type === 'C' ? '菜单' : '按钮'
}

async function loadData() {
  loading.value = true
  try {
    treeData.value = await menuTree()
  } finally {
    loading.value = false
  }
}

function openEdit(row?: MenuVO, parent?: MenuVO) {
  Object.assign(editForm, {
    id: row?.id ?? '',
    parentId: row?.parentId ?? parent?.id ?? '0',
    menuName: row?.menuName ?? '',
    menuType: row?.menuType ?? 'C',
    path: row?.path ?? '',
    component: row?.component ?? '',
    perms: row?.perms ?? '',
    icon: row?.icon ?? '',
    sort: row?.sort ?? 1,
    visible: row?.visible ?? 0
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
      await updateMenu({ ...editForm })
    } else {
      await saveMenu({ ...editForm })
    }
    ElMessage.success(editForm.id ? '修改成功' : '新增成功')
    editVisible.value = false
    loadData()
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row: MenuVO) {
  try {
    await ElMessageBox.confirm(`确定删除菜单【${row.menuName}】吗？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  await removeMenu(row.id)
  ElMessage.success('删除成功')
  loadData()
}

onMounted(loadData)
</script>
