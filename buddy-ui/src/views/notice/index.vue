<template>
  <div class="page-container">
    <el-card shadow="never">
      <el-form :model="query" inline class="search-form" @submit.prevent>
        <el-form-item label="标题">
          <el-input v-model="query.title" placeholder="请输入" clearable style="width: 180px" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="query.type" placeholder="全部" clearable style="width: 120px">
            <el-option label="通知" :value="1" />
            <el-option label="公告" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="草稿" :value="0" />
            <el-option label="已发布" :value="1" />
            <el-option label="已撤回" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>

      <div class="table-toolbar">
        <el-button v-if="hasPerm('notice:save')" type="primary" :icon="Plus" @click="openEdit()">
          新增
        </el-button>
        <el-button
          v-if="hasPerm('notice:remove')"
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
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column label="类型" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.type === 2 ? 'warning' : 'info'">
              {{ row.typeDesc }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.status === 1 ? 'success' : row.status === 2 ? 'danger' : 'info'"
            >
              {{ row.statusDesc }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="targetTypeDesc" label="发布范围" min-width="110" />
        <el-table-column prop="publishTime" label="发布时间" min-width="170" />
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="hasPerm('notice:publish') && row.status !== 1"
              link
              type="success"
              @click="handlePublish(row)"
            >
              发布
            </el-button>
            <el-button
              v-if="hasPerm('notice:publish') && row.status === 1"
              link
              type="warning"
              @click="handleRevoke(row)"
            >
              撤回
            </el-button>
            <el-button v-if="hasPerm('notice:update')" link type="primary" @click="openEdit(row)">
              编辑
            </el-button>
            <el-button v-if="hasPerm('notice:remove')" link type="danger" @click="handleDelete(row)">
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
    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑公告' : '新增公告'" width="640px">
      <el-form ref="editRef" :model="editForm" :rules="editRules" label-width="90px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="editForm.title" placeholder="请输入标题" />
        </el-form-item>
        <el-form-item label="内容" prop="content">
          <el-input v-model="editForm.content" type="textarea" :rows="5" placeholder="请输入正文" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="editForm.type">
            <el-radio :value="1">通知</el-radio>
            <el-radio :value="2">公告</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="发布范围" prop="targetType">
          <el-radio-group v-model="editForm.targetType">
            <el-radio :value="1">全体用户</el-radio>
            <el-radio :value="2">指定角色</el-radio>
            <el-radio :value="3">指定用户</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="editForm.targetType === 2" label="选择角色" prop="targetIds">
          <el-select v-model="editForm.targetIds" multiple style="width: 100%">
            <el-option v-for="r in roleOptions" :key="r.id" :label="r.roleName" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="editForm.targetType === 3" label="选择用户" prop="targetIds">
          <el-select
            v-model="editForm.targetIds"
            multiple
            filterable
            remote
            :remote-method="searchUsers"
            :loading="userSearching"
            style="width: 100%"
          >
            <el-option
              v-for="u in userOptions"
              :key="u.id"
              :label="`${u.username}（${u.nickname}）`"
              :value="u.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取 消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitEdit">保存为草稿</el-button>
        <el-button type="success" :loading="submitting" @click="submitAndPublish">
          保存并发布
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Search, Refresh, Plus, Delete } from '@element-plus/icons-vue'
import {
  pageNotices,
  saveNotice,
  updateNotice,
  removeNotices,
  publishNotice,
  revokeNotice,
  type NoticeVO
} from '@/api/notice'
import { listRoles, pageUsers } from '@/api/system'
import type { SysRoleVO, SysUserVO } from '@/api/types'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const hasPerm = (perm: string) => userStore.hasPermission(perm)

const loading = ref(false)
const submitting = ref(false)
const userSearching = ref(false)
const records = ref<NoticeVO[]>([])
const total = ref(0)
const selected = ref<NoticeVO[]>([])
const roleOptions = ref<SysRoleVO[]>([])
const userOptions = ref<SysUserVO[]>([])

const query = reactive({
  title: '',
  type: undefined as number | undefined,
  status: undefined as number | undefined,
  pageNum: 1,
  pageSize: 10
})

async function loadData() {
  loading.value = true
  try {
    const res = await pageNotices({ ...query })
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
  query.title = ''
  query.type = undefined
  query.status = undefined
  handleSearch()
}

function onSelectionChange(rows: NoticeVO[]) {
  selected.value = rows
}

/** 远程搜索用户：用户表可能很大，不能一次性全量拉到前端 */
async function searchUsers(keyword: string) {
  if (!keyword) return
  userSearching.value = true
  try {
    const res = await pageUsers({ username: keyword, pageNum: 1, pageSize: 20 })
    userOptions.value = res.records
  } finally {
    userSearching.value = false
  }
}

async function handlePublish(row: NoticeVO) {
  await publishNotice(row.id)
  ElMessage.success('已发布，在线用户会立即收到提醒')
  loadData()
}

async function handleRevoke(row: NoticeVO) {
  try {
    await ElMessageBox.confirm('撤回后该公告将不再对用户可见，确定吗？', '提示', {
      type: 'warning'
    })
  } catch {
    return
  }
  await revokeNotice(row.id)
  ElMessage.success('已撤回')
  loadData()
}

async function handleDelete(row?: NoticeVO) {
  const ids = row ? [row.id] : selected.value.map((n) => n.id)
  if (ids.length === 0) return
  try {
    await ElMessageBox.confirm(
      row ? `确定删除公告【${row.title}】吗？` : `确定删除选中的 ${ids.length} 条公告吗？`,
      '提示',
      { type: 'warning' }
    )
  } catch {
    return
  }
  await removeNotices(ids)
  ElMessage.success('删除成功')
  loadData()
}

/* ---------- 新增/编辑 ---------- */
const editVisible = ref(false)
const editRef = ref<FormInstance>()
const editForm = reactive({
  id: '',
  title: '',
  content: '',
  type: 1,
  targetType: 1,
  targetIds: [] as string[]
})

const editRules: FormRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  content: [{ required: true, message: '请输入内容', trigger: 'blur' }],
  targetIds: [
    {
      validator: (_rule, value, callback) => {
        if (editForm.targetType !== 1 && (!value || value.length === 0)) {
          callback(new Error('指定角色/用户时必须选择发布对象'))
        } else {
          callback()
        }
      },
      trigger: 'change'
    }
  ]
}

function openEdit(row?: NoticeVO) {
  Object.assign(editForm, {
    id: row?.id ?? '',
    title: row?.title ?? '',
    content: row?.content ?? '',
    type: row?.type ?? 1,
    targetType: row?.targetType ?? 1,
    targetIds: row?.targetIds ? [...row.targetIds] : []
  })
  editVisible.value = true
}

/**
 * 提交表单，返回公告 ID。
 * 返回 undefined 表示校验未通过（或表单未就绪），调用方据此终止后续操作。
 */
async function submitForm(): Promise<string | undefined> {
  if (!editRef.value) return undefined
  try {
    await editRef.value.validate()
  } catch {
    return undefined
  }
  submitting.value = true
  try {
    const payload = { ...editForm }
    if (payload.id) {
      await updateNotice(payload)
      return payload.id
    }
    // 新增接口返回新 ID，方便紧接着调用发布
    return await saveNotice(payload)
  } finally {
    submitting.value = false
  }
}

async function submitEdit() {
  const id = await submitForm()
  if (id === undefined) return
  ElMessage.success('已保存为草稿')
  editVisible.value = false
  loadData()
}

async function submitAndPublish() {
  const id = await submitForm()
  if (id === undefined) return
  await publishNotice(id)
  ElMessage.success('已发布，在线用户会立即收到提醒')
  editVisible.value = false
  loadData()
}

onMounted(async () => {
  roleOptions.value = await listRoles().catch(() => [])
  loadData()
})
</script>
