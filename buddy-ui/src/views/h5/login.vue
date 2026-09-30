<template>
  <div class="h5-login">
    <h1 class="title">换电 H5</h1>
    <p class="subtitle">手机号验证码登录（未注册将自动创建账号）</p>

    <div class="field">
      <input v-model.trim="phone" type="tel" maxlength="11" placeholder="手机号" />
    </div>
    <div class="field row">
      <input v-model.trim="code" type="text" maxlength="6" placeholder="验证码" />
      <button class="ghost" :disabled="countdown > 0 || sending" @click="onSendCode">
        {{ countdown > 0 ? countdown + 's' : '获取验证码' }}
      </button>
    </div>

    <p v-if="echoHint" class="echo">{{ echoHint }}</p>
    <p v-if="error" class="error">{{ error }}</p>

    <button class="primary" :disabled="submitting || !canSubmit" @click="onLogin">
      {{ submitting ? '登录中…' : '登录 / 注册' }}
    </button>

    <p class="tips">开发环境使用 MOCK 短信通道，验证码会直接显示在页面上。</p>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { memberLogin, sendSmsCode } from '@/api/member'

const router = useRouter()
const route = useRoute()

const phone = ref('')
const code = ref('')
const error = ref('')
const echoHint = ref('')
const sending = ref(false)
const submitting = ref(false)
const countdown = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

const canSubmit = computed(() => /^1[3-9]\d{9}$/.test(phone.value) && code.value.length >= 4)

function startCountdown(seconds: number): void {
  countdown.value = seconds
  timer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0 && timer) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}

async function onSendCode(): Promise<void> {
  error.value = ''
  if (!/^1[3-9]\d{9}$/.test(phone.value)) {
    error.value = '请输入正确的手机号'
    return
  }
  sending.value = true
  try {
    const ticket = await sendSmsCode(phone.value, 'LOGIN')
    startCountdown(60)
    // 只有 MOCK 通道才回显；接真实短信后这个字段为空，提示行自然不显示
    echoHint.value = ticket.echoCode ? '开发通道验证码：' + ticket.echoCode : ''
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    sending.value = false
  }
}

async function onLogin(): Promise<void> {
  error.value = ''
  submitting.value = true
  try {
    await memberLogin({ phone: phone.value, code: code.value, deviceType: 'H5' })
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/h5/swap'
    router.replace(redirect)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  // 测试/联调便捷入口：URL 带 phone 时自动填号并请求验证码
  const preset = route.query.phone
  if (typeof preset === 'string' && /^1[3-9]\d{9}$/.test(preset)) {
    phone.value = preset
  }
})

onBeforeUnmount(() => {
  if (timer) {
    clearInterval(timer)
  }
})
</script>

<style scoped>
.h5-login {
  max-width: 420px;
  margin: 0 auto;
  padding: 32px 20px;
  font-family: system-ui, -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif;
}
.title {
  font-size: 22px;
  margin: 0 0 4px;
}
.subtitle {
  margin: 0 0 24px;
  color: #6b7280;
  font-size: 13px;
}
.field {
  margin-bottom: 12px;
}
.field.row {
  display: flex;
  gap: 8px;
}
input {
  flex: 1;
  height: 44px;
  padding: 0 12px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  font-size: 15px;
}
button {
  height: 44px;
  border-radius: 8px;
  border: none;
  font-size: 15px;
  cursor: pointer;
}
button.ghost {
  width: 120px;
  background: #eef2ff;
  color: #3730a3;
}
button.primary {
  width: 100%;
  background: #2563eb;
  color: #fff;
  margin-top: 8px;
}
button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.echo {
  color: #0f766e;
  font-size: 13px;
}
.error {
  color: #b91c1c;
  font-size: 13px;
}
.tips {
  margin-top: 20px;
  color: #9ca3af;
  font-size: 12px;
}
</style>
