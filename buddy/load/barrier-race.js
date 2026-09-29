// k6 压测：登录取 token → 并发手动开合撞同一根杆，验证"三层并发模型"（按杆锁 + ShedLock + @Version CAS）
// 在 HTTP 压力下不破：允许 CAS 冲突(业务码 409，正常的乐观锁让步)，但绝不允许 5xx / 业务码 500。
//
// 运行（后端起在 8200、dev/H2 或真库均可）：
//   k6 run -e BASE=http://localhost:8200/api -e BARRIER_ID=1 load/barrier-race.js
//
// 说明：后端统一 HTTP 200 + 响应体 code 承载语义（200 成功 / 409 冲突 / 500 异常）。
import http from 'k6/http'
import { check, sleep } from 'k6'
import { Counter, Rate } from 'k6/metrics'

const BASE = __ENV.BASE || 'http://localhost:8200/api'
const BARRIER_ID = __ENV.BARRIER_ID || '1'

const conflicts = new Counter('barrier_cas_conflicts')
const serverErrors = new Rate('barrier_server_errors')

export const options = {
  scenarios: {
    // 20 个并发用户各打 25 次手动开合 = 500 次写，全部撞同一根杆，制造 manual-vs-manual 竞态
    race: {
      executor: 'per-vu-iterations',
      vus: 20,
      iterations: 25,
      maxDuration: '90s'
    }
  },
  thresholds: {
    // 核心红线：并发写不得产生服务端错误；CAS 冲突是预期让步，不计入失败
    barrier_server_errors: ['rate==0'],
    http_req_failed: ['rate<0.01']
  }
}

export function setup() {
  const res = http.post(
    `${BASE}/auth/login`,
    JSON.stringify({ username: 'admin', password: 'Admin@123456' }),
    { headers: { 'Content-Type': 'application/json' } }
  )
  const token = res.json('data.token')
  if (!token) {
    throw new Error('登录失败，未取到 token：' + res.body)
  }
  return { token }
}

export default function (data) {
  // 奇偶 VU 分别开/关，最大化状态跃迁与 CAS 竞争
  const action = __VU % 2 === 0 ? 'OPEN' : 'CLOSE'
  const res = http.post(`${BASE}/barriers/${BARRIER_ID}/manual`, JSON.stringify({ action }), {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` }
  })

  const body = res.json()
  const bizCode = body ? body.code : -1
  if (bizCode === 409) conflicts.add(1)

  // 服务端错误 = HTTP 非 200，或业务码 500（409 冲突是正常的乐观锁让步，不算错误）
  const isServerError = res.status !== 200 || bizCode === 500
  if (isServerError) serverErrors.add(1)

  check(res, {
    'HTTP 200': () => res.status === 200,
    '业务码为成功或冲突(200/409)': () => bizCode === 200 || bizCode === 409
  })
  sleep(0.05)
}
