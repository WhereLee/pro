import axios, { type AxiosInstance } from 'axios'

/**
 * C 端（member 域）请求层。
 *
 * 为什么单独一个 axios 实例而不是复用 `utils/request.ts`：
 * 后台实例在 401 时会清 admin 登录态、卸载动态路由、跳 /login——
 * 如果 C 端页面共用它，**会员令牌过期会把管理员会话一起登出**（同一浏览器标签里
 * 两个域互相踢下线）。两个域的令牌存储、失效处理、跳转目标都必须彼此独立。
 *
 * 刷新策略：401 时用 refresh 令牌换一次新 access，成功就重试原请求，失败才要求重新登录。
 * 只重试一次：刷新失败还继续重试会形成请求风暴。
 */

const ACCESS_KEY = 'buddy_member_access'
const REFRESH_KEY = 'buddy_member_refresh'
// 登录时的原始手机号：实名阶段要拿它发码（服务端只存哈希与密文，接口也无法回传明文），
// 所以必须存在客户端；不存就会多一个“无法自助实名”的死路。
const PHONE_KEY = 'buddy_member_phone'

export interface MemberTokens {
  accessToken: string
  refreshToken: string
  sessionFamily: string
  memberId: number
}

export const memberTokenStore = {
  access(): string {
    return localStorage.getItem(ACCESS_KEY) || ''
  },
  refresh(): string {
    return localStorage.getItem(REFRESH_KEY) || ''
  },
  save(tokens: MemberTokens, phone?: string): void {
    localStorage.setItem(ACCESS_KEY, tokens.accessToken)
    localStorage.setItem(REFRESH_KEY, tokens.refreshToken)
    if (phone) {
      localStorage.setItem(PHONE_KEY, phone)
    }
  },
  phone(): string {
    return localStorage.getItem(PHONE_KEY) || ''
  },
  clear(): void {
    localStorage.removeItem(ACCESS_KEY)
    localStorage.removeItem(REFRESH_KEY)
    localStorage.removeItem(PHONE_KEY)
  },
  get loggedIn(): boolean {
    return !!this.access()
  }
}

/** C 端展示态：语义由后端 DisplayState 决定，这里只做类型与样式映射。 */
export type Tone = 'progress' | 'warn' | 'success' | 'danger'

export interface ProgressView {
  orderNo: string
  orderState: string
  displayState: string
  label: string
  tone: Tone
  hint: string
  terminal: boolean
  canStart: boolean
  canDeclareClosed: boolean
  canReorder: boolean
  returnSlotNo: number | null
  offerSlotNo: number | null
  steps: { stepNo: number; stepCode: string; stepState: string; label: string; done: boolean; active: boolean }[]
  right: { times_total: number; times_used: number; times_occupied: number; freeze_state: string }[]
}

export interface CabinetView {
  cabinetNo: string
  siteId: number
  slotCount: number | null
  cabinetState: string
  onlineState: string
  freeReturnSlots: number
  readyOfferSlots: number
  orderable: boolean
}

export interface CreateResult {
  orderNo: string
  orderId: number
  accepted: boolean
  orderState: string
  displayState: string
  label: string
  tone: Tone
  hint: string
  rejectReasons: string[]
}

export interface MemberView {
  memberId: number
  memberNo: string
  nickname: string
  maskPhone: string
  realnameState: string
  memberState: string
}

const http: AxiosInstance = axios.create({
  // 后端统一上下文路径 /api；dev 由 vite 代理转发
  baseURL: '/api',
  timeout: 20000
})

http.interceptors.request.use((config) => {
  const token = memberTokenStore.access()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/** 刷新进行中的共享 Promise：并发多个 401 只发起一次刷新请求。 */
let refreshing: Promise<boolean> | null = null

async function tryRefresh(): Promise<boolean> {
  const refreshToken = memberTokenStore.refresh()
  if (!refreshToken) {
    return false
  }
  if (!refreshing) {
    refreshing = axios
      .post('/api/member/auth/refresh', { refreshToken })
      .then((resp) => {
        if (resp.data?.code === 200 && resp.data.data?.accessToken) {
          memberTokenStore.save(resp.data.data)
          return true
        }
        memberTokenStore.clear()
        return false
      })
      .catch(() => {
        memberTokenStore.clear()
        return false
      })
      .finally(() => {
        refreshing = null
      })
  }
  return refreshing
}

function requireLogin(): void {
  memberTokenStore.clear()
  const path = window.location.pathname
  if (!path.startsWith('/h5/login')) {
    window.location.href = '/h5/login?redirect=' + encodeURIComponent(path)
  }
}

http.interceptors.response.use(
  async (response) => {
    const body = response.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 200) {
        return body.data
      }
      // 业务失败（HTTP 200 + code!=200）：抛给调用方，由页面自己决定怎么显示
      throw new Error(body.message || '操作失败，请稍后重试')
    }
    return response.data
  },
  async (error) => {
    const status = error?.response?.status
    const original = error?.config as { _retried?: boolean; url?: string } | undefined
    if (status === 401 && original && !original._retried && !original.url?.includes('/member/auth/')) {
      original._retried = true
      const refreshed = await tryRefresh()
      if (refreshed) {
        return http(original as never)
      }
    }
    if (status === 401) {
      requireLogin()
    }
    throw new Error(error?.response?.data?.message || error?.message || '网络异常')
  }
)

// ---------------- 身份 ----------------

/**
 * 类型化调用：拦截器已在运行时把响应解成 `data`，但 axios 的类型签名仍然返回
 * `AxiosResponse`。这里集中做一次 cast，与后台 `utils/request.ts` 的 `http<T>()` 同一做法，
 * 避免每个方法各自写一遍 `as unknown as`。
 */
function call<T>(config: { url: string; method: string; data?: unknown; params?: unknown }): Promise<T> {
  return http(config as never) as unknown as Promise<T>
}

export function sendSmsCode(phone: string, purpose: string): Promise<{ purpose: string; echoCode?: string }> {
  return call({ url: '/member/auth/sms-code', method: 'POST', data: { phone, purpose } })
}

export function memberLogin(payload: {
  phone: string
  code: string
  deviceType?: string
  deviceFingerprint?: string
}): Promise<MemberTokens> {
  return call<MemberTokens>({ url: '/member/auth/login', method: 'POST', data: { deviceType: 'H5', ...payload } }).then(
    (data) => {
      memberTokenStore.save(data, payload.phone)
      return data
    }
  )
}

export function memberLogout(): Promise<unknown> {
  return call({ url: '/member/auth/logout', method: 'POST' }).finally(() => memberTokenStore.clear())
}

export function memberMe(): Promise<MemberView> {
  return call({ url: '/member/me', method: 'GET' })
}

export function submitRealname(payload: { realName: string; idNo: string; smsCode: string }): Promise<string> {
  return call({ url: '/member/me/realname', method: 'POST', data: payload })
}

// ---------------- 换电 ----------------

export function listCabinets(limit = 20): Promise<CabinetView[]> {
  return call({ url: '/member/swap/cabinets', method: 'GET', params: { limit } })
}

export function getCabinet(cabinetNo: string): Promise<CabinetView> {
  return call({ url: `/member/swap/cabinets/${encodeURIComponent(cabinetNo)}`, method: 'GET' })
}

export function createOrder(cabinetNo: string): Promise<CreateResult> {
  return call({ url: '/member/swap/orders', method: 'POST', data: { cabinetNo } })
}

export function currentOrder(): Promise<ProgressView | null> {
  return call({ url: '/member/swap/orders/current', method: 'GET' })
}

export function orderProgress(orderNo: string): Promise<ProgressView> {
  return call({ url: `/member/swap/orders/${encodeURIComponent(orderNo)}`, method: 'GET' })
}

export function startReturn(orderNo: string): Promise<ProgressView> {
  return call({ url: `/member/swap/orders/${encodeURIComponent(orderNo)}/start`, method: 'POST' })
}

export function declareClosed(orderNo: string): Promise<{ outcome: string; progress: ProgressView }> {
  return call({ url: `/member/swap/orders/${encodeURIComponent(orderNo)}/declare-closed`, method: 'POST' })
}

export default http
