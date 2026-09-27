/**
 * 手动实现的 SSE 客户端。
 *
 * 为什么不用浏览器原生的 EventSource：
 * EventSource 不支持自定义请求头，无法携带 Authorization，
 * 而本框架的鉴权依赖 Bearer Token。若为此把令牌拼在 URL 上，
 * 令牌会出现在网关日志、浏览器历史里，属于安全隐患。
 *
 * 因此改用 fetch + ReadableStream 读取事件流，自行按 SSE 协议解析。
 */
export interface SseOptions {
  url: string
  token: string
  onMessage: (data: any) => void
  onError?: (error: any) => void
}

export function createSseConnection(options: SseOptions): () => void {
  const controller = new AbortController()

  fetch(options.url, {
    method: 'GET',
    headers: {
      Authorization: `Bearer ${options.token}`,
      Accept: 'text/event-stream'
    },
    signal: controller.signal
  })
    .then(async (response) => {
      if (!response.ok || !response.body) {
        throw new Error(`SSE 连接失败：${response.status}`)
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        // SSE 以空行分隔事件，这里按行解析并保留未完整的最后一行
        const lines = buffer.split('\n')
        buffer = lines.pop() ?? ''

        for (const line of lines) {
          const trimmed = line.trim()
          // 冒号开头的是注释（服务端心跳），忽略
          if (!trimmed || trimmed.startsWith(':')) continue
          if (trimmed.startsWith('data:')) {
            const payload = trimmed.slice(5).trim()
            try {
              options.onMessage(JSON.parse(payload))
            } catch {
              // 非 JSON 数据忽略，避免单条脏数据中断整个流
            }
          }
        }
      }
    })
    .catch((error: any) => {
      // 主动 abort 时也会走到这里，不需要当作错误处理
      if (error?.name === 'AbortError') return
      options.onError?.(error)
    })

  return () => controller.abort()
}
