<template>
  <div class="h5-swap">
    <header class="bar">
      <span class="who">{{ me ? me.nickname + '（' + me.maskPhone + '）' : '加载中…' }}</span>
      <button class="link" @click="onLogout">退出</button>
    </header>

    <!-- 未实名引导：换电的硬前置，页面必须能自己走完这一步，而不是让用户去后台 -->
    <section v-if="me && me.realnameState !== 'VERIFIED'" class="card">
      <h2>需要实名认证</h2>
      <p class="muted">换电会把可上路行驶的电池交付给你，未实名不能下单。</p>
      <input v-model.trim="realName" placeholder="真实姓名" />
      <input v-model.trim="idNo" placeholder="身份证号" maxlength="18" />
      <div class="row">
        <input v-model.trim="realnameCode" placeholder="实名验证码" maxlength="6" />
        <button class="ghost" :disabled="rnSending || rnCountdown > 0 || !memberPhone" @click="onRealnameCode">
          {{ rnCountdown > 0 ? rnCountdown + 's' : '获取验证码' }}
        </button>
      </div>
      <p v-if="!memberPhone" class="muted small">拿不到登录手机号，请退出重新登录后再实名。</p>
      <p v-if="rnEcho" class="echo">{{ rnEcho }}</p>
      <button class="primary" :disabled="submittingRn" @click="onSubmitRealname">提交实名</button>
      <p v-if="rnError" class="error">{{ rnError }}</p>
    </section>

    <!-- 进度 / 结果：同一块区域按是否有在途单切换 -->
    <section v-if="progress" class="card" :class="toneClass(progress.tone)">
      <div class="headline" data-testid="h5-display-label">{{ progress.label }}</div>
      <p class="hint">{{ progress.hint }}</p>
      <p class="slots">
        归还仓 <b>{{ progress.returnSlotNo ?? '—' }}</b> · 取电仓 <b>{{ progress.offerSlotNo ?? '—' }}</b>
        <span v-if="progress.orderNo" class="mono">单号 {{ progress.orderNo }}</span>
      </p>

      <ol class="steps">
        <li v-for="step in progress.steps" :key="step.stepNo" :class="{ done: step.done, active: step.active }">
          <span class="idx">{{ step.stepNo }}</span>
          <span class="txt">{{ step.label }}</span>
          <span class="st">{{ step.stepState }}</span>
        </li>
      </ol>

      <button
        class="primary"
        :disabled="action.type === 'wait' || acting"
        data-testid="h5-action"
        @click="onAction"
      >
        {{ action.label }}
      </button>
      <p v-if="actionError" class="error">{{ actionError }}</p>
      <p v-if="progress.displayState === 'UNKNOWN'" class="muted small">
        柜机回传不完整时不会显示为失败，系统正在核实；请勿重复下单。
      </p>
    </section>

    <!-- 建单被拒：负向终态才用 danger 样式，且原因逐条列出 -->
    <section v-if="rejected" class="card is-danger">
      <div class="headline">未能下单</div>
      <ul class="reasons">
        <li v-for="reason in rejected.rejectReasons" :key="reason">{{ reasonText(reason) }}</li>
      </ul>
      <button class="ghost" @click="rejected = null">返回选柜</button>
    </section>

    <section v-if="!progress && !rejected" class="card">
      <h2>选择柜机</h2>
      <p v-if="loadingCabinets" class="muted">加载中…</p>
      <p v-else-if="!cabinets.length" class="muted">附近暂时没有可换电的柜机</p>
      <ul class="cabinets">
        <li v-for="cab in cabinets" :key="cab.cabinetNo">
          <div class="cab-main">
            <b>{{ cab.cabinetNo }}</b>
            <span class="muted">{{ cab.freeReturnSlots }} 空仓 / {{ cab.readyOfferSlots }} 满电</span>
          </div>
          <button class="primary slim" :disabled="!cab.orderable || creating" @click="onCreate(cab)">
            {{ cab.orderable ? '开始换电' : '暂不可用' }}
          </button>
        </li>
      </ul>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  createOrder,
  currentOrder,
  declareClosed,
  listCabinets,
  memberLogout,
  memberMe,
  memberTokenStore,
  orderProgress,
  sendSmsCode,
  startReturn,
  submitRealname,
  type CabinetView,
  type CreateResult,
  type MemberView,
  type ProgressView
} from '@/api/member'
import { nextAction, reasonText, shouldPoll, toneClass } from './display'

const me = ref<MemberView | null>(null)
const progress = ref<ProgressView | null>(null)
const rejected = ref<CreateResult | null>(null)
const cabinets = ref<CabinetView[]>([])
const loadingCabinets = ref(false)
const creating = ref(false)
const acting = ref(false)
const actionError = ref('')

const realName = ref('')
const idNo = ref('')
const realnameCode = ref('')
const rnEcho = ref('')
const rnError = ref('')
const rnSending = ref(false)
const rnCountdown = ref(0)
const submittingRn = ref(false)

const action = computed(() => (progress.value ? nextAction(progress.value) : { type: 'wait', label: '请稍候' }))
const memberPhone = computed(() => memberTokenStore.phone())

let pollTimer: ReturnType<typeof setInterval> | null = null
let rnTimer: ReturnType<typeof setInterval> | null = null

async function loadMe(): Promise<void> {
  try {
    me.value = await memberMe()
  } catch (e) {
    actionError.value = (e as Error).message
  }
}

async function loadCabinets(): Promise<void> {
  loadingCabinets.value = true
  try {
    cabinets.value = await listCabinets(20)
  } catch (e) {
    actionError.value = (e as Error).message
  } finally {
    loadingCabinets.value = false
  }
}

async function loadCurrent(): Promise<void> {
  try {
    progress.value = await currentOrder()
    if (progress.value) {
      ensurePolling()
    }
  } catch (e) {
    actionError.value = (e as Error).message
  }
}

async function onCreate(cab: CabinetView): Promise<void> {
  creating.value = true
  actionError.value = ''
  try {
    const result = await createOrder(cab.cabinetNo)
    if (result.accepted) {
      progress.value = await orderProgress(result.orderNo)
      ensurePolling()
    } else {
      rejected.value = result
    }
  } catch (e) {
    actionError.value = (e as Error).message
  } finally {
    creating.value = false
  }
}

async function onAction(): Promise<void> {
  if (!progress.value || action.value.type === 'wait') {
    return
  }
  acting.value = true
  actionError.value = ''
  try {
    const orderNo = progress.value.orderNo
    if (action.value.type === 'start') {
      progress.value = await startReturn(orderNo)
    } else if (action.value.type === 'declare') {
      const result = await declareClosed(orderNo)
      progress.value = result.progress
    } else if (action.value.type === 'reorder') {
      progress.value = null
      stopPolling()
      await loadCabinets()
    }
    ensurePolling()
  } catch (e) {
    // 设备不可达等场景是业务结论，展示原文而不伪装成"完成"
    actionError.value = (e as Error).message
  } finally {
    acting.value = false
  }
}

async function onRealnameCode(): Promise<void> {
  rnSending.value = true
  rnError.value = ''
  try {
    const ticket = await sendSmsCode(memberPhone.value, 'REALNAME')
    rnEcho.value = ticket.echoCode ? '开发通道验证码：' + ticket.echoCode : ''
    rnCountdown.value = 60
    rnTimer = setInterval(() => {
      rnCountdown.value -= 1
      if (rnCountdown.value <= 0 && rnTimer) {
        clearInterval(rnTimer)
        rnTimer = null
      }
    }, 1000)
  } catch (e) {
    rnError.value = (e as Error).message
  } finally {
    rnSending.value = false
  }
}

async function onSubmitRealname(): Promise<void> {
  submittingRn.value = true
  rnError.value = ''
  try {
    await submitRealname({ realName: realName.value, idNo: idNo.value, smsCode: realnameCode.value })
    await loadMe()
  } catch (e) {
    rnError.value = (e as Error).message
  } finally {
    submittingRn.value = false
  }
}

async function onLogout(): Promise<void> {
  try {
    await memberLogout()
  } catch {
    // 登出失败也要清本地令牌：否则用户被一个刷不动的会话卡死
  }
  window.location.href = '/h5/login'
}

/** 脱敏手机号不能用来发码：发码用登录时存下来的原始号码（见 memberTokenStore.phone）。 */

function ensurePolling(): void {
  if (pollTimer || !shouldPoll(progress.value)) {
    return
  }
  pollTimer = setInterval(async () => {
    if (!progress.value) {
      return
    }
    if (!shouldPoll(progress.value)) {
      stopPolling()
      return
    }
    try {
      progress.value = await orderProgress(progress.value.orderNo)
    } catch {
      // 轮询失败静默重试：一次网络抖动不该把进度页变成错误页
    }
  }, 3000)
}

function stopPolling(): void {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

onMounted(async () => {
  await loadMe()
  await loadCurrent()
  if (!progress.value) {
    await loadCabinets()
  }
})

onBeforeUnmount(() => {
  stopPolling()
  if (rnTimer) {
    clearInterval(rnTimer)
  }
})
</script>

<style scoped>
.h5-swap {
  max-width: 460px;
  margin: 0 auto;
  padding: 12px 14px 40px;
  font-family: system-ui, -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  color: #111827;
}
.bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  color: #4b5563;
  margin-bottom: 12px;
}
.card {
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 14px;
  background: #fff;
}
.card.is-progress {
  border-color: #bfdbfe;
  background: #f5f9ff;
}
.card.is-warn {
  border-color: #fcd34d;
  background: #fffbeb;
}
.card.is-success {
  border-color: #86efac;
  background: #f0fdf4;
}
.card.is-danger {
  border-color: #fca5a5;
  background: #fef2f2;
}
.headline {
  font-size: 20px;
  font-weight: 600;
  margin-bottom: 6px;
}
.hint {
  margin: 0 0 8px;
  font-size: 14px;
  color: #374151;
}
.slots {
  margin: 0 0 10px;
  font-size: 13px;
  color: #4b5563;
}
.mono {
  display: block;
  margin-top: 4px;
  font-family: ui-monospace, Menlo, monospace;
  font-size: 12px;
  color: #6b7280;
}
.steps {
  list-style: none;
  margin: 0 0 14px;
  padding: 0;
}
.steps li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px dashed #f3f4f6;
  font-size: 14px;
  color: #6b7280;
}
.steps li.done {
  color: #047857;
}
.steps li.active {
  color: #1d4ed8;
  font-weight: 600;
}
.idx {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #e5e7eb;
  color: #374151;
  font-size: 12px;
  display: grid;
  place-items: center;
}
.steps li.done .idx {
  background: #047857;
  color: #fff;
}
.txt {
  flex: 1;
}
.st {
  font-size: 11px;
  color: #9ca3af;
}
.reasons {
  margin: 0 0 12px;
  padding-left: 18px;
  font-size: 14px;
  color: #b91c1c;
}
.cabinets {
  list-style: none;
  margin: 0;
  padding: 0;
}
.cabinets li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px solid #f3f4f6;
}
.cab-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 14px;
}
input {
  width: 100%;
  height: 42px;
  padding: 0 12px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  margin-bottom: 8px;
  font-size: 14px;
  box-sizing: border-box;
}
.row {
  display: flex;
  gap: 8px;
}
button {
  height: 42px;
  border-radius: 8px;
  border: none;
  font-size: 14px;
  cursor: pointer;
}
button.primary {
  background: #2563eb;
  color: #fff;
  width: 100%;
}
button.primary.slim {
  width: 116px;
  flex: none;
}
button.ghost {
  background: #eef2ff;
  color: #3730a3;
  padding: 0 14px;
}
button.link {
  background: none;
  color: #2563eb;
  height: auto;
  font-size: 13px;
}
button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.muted {
  color: #6b7280;
  font-size: 13px;
}
.muted.small {
  font-size: 12px;
  margin-top: 8px;
}
.echo {
  color: #0f766e;
  font-size: 12px;
}
.error {
  color: #b91c1c;
  font-size: 13px;
}
h2 {
  font-size: 16px;
  margin: 0 0 8px;
}
</style>
