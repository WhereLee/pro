import axios, { type AxiosInstance, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import { performSoftLogout } from '@/utils/auth'
import type { R, PageResult } from '@/api/types'

// 响应体与分页类型集中定义在 api/types.ts，这里转出一份，
// 方便调用方从"发请求的地方"直接引入，避免同类型多处定义
export type { R, PageResult }

/**
 * 需要"静默失败"的错误码：这些场景不弹全局错误提示，
 * 由调用方自行决定交互（例如验证码错误在表单里就地展示）。
 */
const SILENT_CODES = new Set<number>([])

const request: AxiosInstance = axios.create({
  // 后端统一上下文路径为 /api，由 vite 代理转发到 8200 端口
  baseURL: '/api',
  timeout: 20000
})

request.interceptors.request.use((config) => {
  const userStore = useUserStore()
  if (userStore.token) {
    config.headers.Authorization = `Bearer ${userStore.token}`
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    const res = response.data as R
    const code = res.code

    if (code === 200) {
      return res.data
    }

    // 鉴权相关：清理登录态并跳转登录页
    // 1403 是"被强制下线"，需要明确告知用户原因
    if (code === 401 || code === 1401 || code === 1402 || code === 1403) {
      const message = res.message || '登录状态已失效'
      ElMessage.error(message)
      // 软登出：清登录态 + 卸载动态权限路由 + 跳登录（详见 utils/auth.ts）
      void performSoftLogout()
      return Promise.reject(new Error(message))
    }

    if (!SILENT_CODES.has(code)) {
      ElMessage.error(res.message || '请求失败')
    }
    return Promise.reject(new Error(res.message || '请求失败'))
  },
  (error) => {
    // 网络层错误（超时、断网、5xx 等）
    const message = error.message || '网络异常，请稍后重试'
    ElMessage.error(message)
    return Promise.reject(error)
  }
)

/**
 * 轻封装，让调用处直接拿到 data，无需层层 .data.data
 *
 * <p>需要一次类型断言：axios 的 static typing 认为返回值是 AxiosResponse，
 * 但响应拦截器已经把 response.data 拆开返回了，
 * 运行期拿到的就是业务数据本身，类型层面只能手动对齐。
 */
export function http<T = any>(config: AxiosRequestConfig): Promise<T> {
  return request(config) as unknown as Promise<T>
}

export default request
