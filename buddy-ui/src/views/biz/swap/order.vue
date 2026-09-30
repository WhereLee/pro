<template>
  <div class="app-container">
    <el-card shadow="never" class="mb">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="订单号">
          <el-input v-model.trim="query.orderNo" placeholder="模糊匹配" clearable style="width: 200px" />
        </el-form-item>
        <el-form-item label="订单状态">
          <el-select v-model="query.state" placeholder="全部" clearable style="width: 180px">
            <el-option v-for="s in STATES" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="reload">查询</el-button>
          <el-button @click="loadPending">刷新复核队列</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="mb">
      <template #header>待复核的干预申请（{{ pending.length }}）</template>
      <el-alert
        type="info"
        :closable="false"
        title="申请人不能复核自己的申请：这一条同时由服务端校验与数据库 CHECK 约束兜住"
        class="mb"
      />
      <el-table :data="pending" size="small" empty-text="没有待复核申请">
        <el-table-column prop="orderNo" label="订单号" width="190" />
        <el-table-column prop="action" label="动作" width="210" />
        <el-table-column prop="reason" label="理由" min-width="220" show-overflow-tooltip />
        <el-table-column prop="applicantName" label="申请人" width="110" />
        <el-table-column prop="appliedAt" label="申请时间" width="165" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="success" @click="onApprove(row)">通过并执行</el-button>
            <el-button link type="danger" @click="openReject(row)">驳回</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>换电订单</template>
      <el-table v-loading="loading" :data="rows" size="small">
        <el-table-column prop="order_no" label="订单号" width="190" />
        <el-table-column prop="cabinet_id" label="柜机" width="120" />
        <el-table-column label="仓位" width="130">
          <template #default="{ row }">归还 {{ row.return_slot_no ?? '—' }} / 取 {{ row.offer_slot_no ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="order_state" label="订单态" width="140" />
        <el-table-column label="展示态" width="140">
          <template #default="{ row }">
            <el-tag :type="tagType(row.displayState)" size="small">{{ row.displayState }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="right_state" label="权益" width="110" />
        <el-table-column prop="create_time" label="创建时间" width="165" />
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.order_no)">详情</el-button>
            <el-button link type="warning" @click="openApply(row.order_no)">申请干预</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        class="pager"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="query.pageSize"
        :current-page="query.pageNum"
        @current-change="onPage"
      />
    </el-card>

    <el-drawer v-model="detailVisible" size="62%" :title="'订单 ' + (detail?.orderNo || '')">
      <div v-if="detail">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="订单态">{{ detail.orderState }}</el-descriptions-item>
          <el-descriptions-item label="展示态">{{ detail.displayState }}</el-descriptions-item>
          <el-descriptions-item label="用户所见">{{ detail.label }}</el-descriptions-item>
          <el-descriptions-item label="权益状态">{{ detail.rightState }}</el-descriptions-item>
          <el-descriptions-item label="归还仓">{{ detail.returnSlotNo ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="取电仓">{{ detail.offerSlotNo ?? '—' }}</el-descriptions-item>
        </el-descriptions>

        <h4>步骤</h4>
        <el-table :data="detail.steps" size="small">
          <el-table-column prop="step_no" label="序号" width="70" />
          <el-table-column prop="step_code" label="步骤码" width="150" />
          <el-table-column prop="step_state" label="状态" width="150" />
          <el-table-column prop="facts_json" label="事实" show-overflow-tooltip />
        </el-table>

        <h4>事件流（可重放依据，按 seq 升序）</h4>
        <el-table :data="detail.events" size="small" max-height="260">
          <el-table-column prop="seq_no" label="seq" width="70" />
          <el-table-column prop="event_type" label="事件" width="220" />
          <el-table-column prop="from_state" label="从" width="140" />
          <el-table-column prop="to_state" label="到" width="140" />
          <el-table-column prop="create_time" label="时间" width="165" />
        </el-table>

        <h4>本单干预历史</h4>
        <el-table :data="detail.interventions" size="small">
          <el-table-column prop="action" label="动作" width="210" />
          <el-table-column prop="applyState" label="状态" width="110" />
          <el-table-column prop="applicantName" label="申请人" width="110" />
          <el-table-column prop="approverName" label="复核人" width="110" />
          <el-table-column prop="reason" label="理由" min-width="180" show-overflow-tooltip />
          <el-table-column prop="execError" label="失败原因" min-width="180" show-overflow-tooltip />
        </el-table>
      </div>
    </el-drawer>

    <el-dialog v-model="applyVisible" title="申请人工干预" width="480px">
      <el-form label-width="88px">
        <el-form-item label="订单号">
          <el-input v-model="applyForm.orderNo" disabled />
        </el-form-item>
        <el-form-item label="动作">
          <el-select v-model="applyForm.action" placeholder="选择干预动作" style="width: 100%">
            <el-option label="ADMIN_ABORT（冻结现场，转待核资）" value="ADMIN_ABORT" />
            <el-option label="ADMIN_RESOLVE_COMPLETED（人工判定已完成）" value="ADMIN_RESOLVE_COMPLETED" />
            <el-option label="ADMIN_RESOLVE_ABORTED（人工判定未发生，全额回滚）" value="ADMIN_RESOLVE_ABORTED" />
          </el-select>
        </el-form-item>
        <el-form-item label="理由">
          <el-input v-model="applyForm.reason" type="textarea" :rows="3" maxlength="255" show-word-limit
            placeholder="不少于 5 个字：写清现场看到了什么、依据是什么" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applyVisible = false">取消</el-button>
        <el-button type="primary" :disabled="applyForm.reason.trim().length < 5" @click="onApply">提交申请</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rejectVisible" title="驳回干预申请" width="420px">
      <el-input v-model="rejectReason" type="textarea" :rows="3" placeholder="驳回理由（不少于 5 个字）" />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :disabled="rejectReason.trim().length < 5" @click="onReject">确认驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  approveIntervention,
  applyIntervention,
  orderDetail,
  pageOrders,
  pendingInterventions,
  rejectIntervention,
  type InterventionRow,
  type OrderDetail,
  type OrderRow
} from '@/api/swapOrder'

const STATES = [
  'CREATED', 'AUTHORIZED', 'RETURNING', 'RETURNED', 'VERIFYING', 'OFFERING', 'TAKEN',
  'SETTLING', 'COMPLETED', 'SUSPENDED', 'UNCONFIRMED', 'ABORTING', 'REJECTED', 'ABORTED', 'FAILED_MANUAL'
]

const rows = ref<OrderRow[]>([])
const pending = ref<InterventionRow[]>([])
const total = ref(0)
const loading = ref(false)
const detail = ref<OrderDetail | null>(null)
const detailVisible = ref(false)
const applyVisible = ref(false)
const rejectVisible = ref(false)
const rejectReason = ref('')
const rejecting = ref<InterventionRow | null>(null)

const query = reactive({ orderNo: '', state: '', pageNum: 1, pageSize: 20 })
const applyForm = reactive({ orderNo: '', action: '', reason: '' })

/**
 * 展示态 → 标签颜色：这里只是样式映射，不重新判断语义。
 * UNKNOWN / NEED_CONFIRM 故意不是 danger（与 C 端同一套口径）。
 */
function tagType(displayState: string): 'success' | 'warning' | 'info' | 'primary' | 'danger' {
  if (displayState === 'SUCCESS') {
    return 'success'
  }
  if (displayState === 'REJECTED' || displayState === 'CANCELLED') {
    return 'danger'
  }
  if (displayState === 'UNKNOWN' || displayState === 'NEED_CONFIRM' || displayState === 'REVIEWING') {
    return 'warning'
  }
  return 'info'
}

async function reload(): Promise<void> {
  loading.value = true
  try {
    const page = await pageOrders({
      orderNo: query.orderNo || undefined,
      state: query.state || undefined,
      pageNum: query.pageNum,
      pageSize: query.pageSize
    })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function loadPending(): Promise<void> {
  pending.value = await pendingInterventions(50)
}

function onPage(page: number): void {
  query.pageNum = page
  void reload()
}

async function openDetail(orderNo: string): Promise<void> {
  detail.value = await orderDetail(orderNo)
  detailVisible.value = true
}

function openApply(orderNo: string): void {
  applyForm.orderNo = orderNo
  applyForm.action = ''
  applyForm.reason = ''
  applyVisible.value = true
}

async function onApply(): Promise<void> {
  await applyIntervention({ orderNo: applyForm.orderNo, action: applyForm.action, reason: applyForm.reason })
  ElMessage.success('申请已提交，需由他人复核')
  applyVisible.value = false
  await loadPending()
}

async function onApprove(row: InterventionRow): Promise<void> {
  await ElMessageBox.confirm(
    `确认通过并立即执行「${row.action}」？执行后订单状态会改变且不可自动回退。`,
    '复核确认',
    { type: 'warning' }
  )
  const state = await approveIntervention(row.id)
  ElMessage.success('干预已执行，订单当前状态：' + state)
  await loadPending()
  await reload()
}

function openReject(row: InterventionRow): void {
  rejecting.value = row
  rejectReason.value = ''
  rejectVisible.value = true
}

async function onReject(): Promise<void> {
  if (!rejecting.value) {
    return
  }
  await rejectIntervention(rejecting.value.id, rejectReason.value)
  ElMessage.success('已驳回')
  rejectVisible.value = false
  await loadPending()
}

onMounted(() => {
  void reload()
  void loadPending()
})
</script>

<style scoped>
.mb {
  margin-bottom: 14px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
h4 {
  margin: 18px 0 8px;
}
</style>
