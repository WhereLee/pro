<template>
  <div class="page-container">
    <!-- 指标卡片 -->
    <el-row :gutter="16" class="metric-row">
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="metric">
            <div class="metric-label">CPU 使用率</div>
            <div class="metric-value">{{ metrics?.cpu?.total ?? 0 }}%</div>
            <el-progress
              :percentage="metrics?.cpu?.total ?? 0"
              :status="progressStatus(metrics?.cpu?.total ?? 0)"
              :show-text="false"
            />
            <div class="metric-sub">{{ metrics?.cpu?.cpuNum ?? 0 }} 核</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="metric">
            <div class="metric-label">内存使用率</div>
            <div class="metric-value">{{ metrics?.mem?.usage ?? 0 }}%</div>
            <el-progress
              :percentage="metrics?.mem?.usage ?? 0"
              :status="progressStatus(metrics?.mem?.usage ?? 0)"
              :show-text="false"
            />
            <div class="metric-sub">
              已用 {{ metrics?.mem?.used ?? 0 }} / 共 {{ metrics?.mem?.total ?? 0 }} GB
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="metric">
            <div class="metric-label">JVM 堆内存</div>
            <div class="metric-value">{{ metrics?.jvm?.usage ?? 0 }}%</div>
            <el-progress
              :percentage="metrics?.jvm?.usage ?? 0"
              :status="progressStatus(metrics?.jvm?.usage ?? 0)"
              :show-text="false"
            />
            <div class="metric-sub">
              已用 {{ metrics?.jvm?.used ?? 0 }} / 共 {{ metrics?.jvm?.total ?? 0 }} MB
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="metric">
            <div class="metric-label">数据更新</div>
            <div class="metric-value small">
              {{ lastUpdateText }}
            </div>
            <el-tag :type="connected ? 'success' : 'info'" size="small">
              {{ connected ? 'SSE 已连接' : '轮询模式' }}
            </el-tag>
            <div class="metric-sub">采样间隔 3 秒</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 仪表盘 + 趋势 -->
    <el-row :gutter="16">
      <el-col :span="10">
        <el-card shadow="never">
          <template #header><span class="card-title">CPU / 内存 仪表</span></template>
          <div ref="gaugeRef" class="chart gauge-chart" />
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>
            <span class="card-title">CPU 使用率趋势（最近 40 次采样）</span>
          </template>
          <div ref="lineRef" class="chart line-chart" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 磁盘 + 系统信息 -->
    <el-row :gutter="16" class="bottom-row">
      <el-col :span="14">
        <el-card shadow="never">
          <template #header><span class="card-title">磁盘状态</span></template>
          <el-table :data="metrics?.sysFiles ?? []" size="small" border>
            <el-table-column prop="dirName" label="盘符路径" min-width="90" />
            <el-table-column prop="total" label="总大小" width="100" />
            <el-table-column prop="used" label="已用" width="100" />
            <el-table-column prop="free" label="可用" width="100" />
            <el-table-column label="使用率" min-width="160">
              <template #default="{ row }">
                <el-progress
                  :percentage="row.usage"
                  :status="progressStatus(row.usage)"
                  :stroke-width="12"
                  :text-inside="true"
                />
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card shadow="never">
          <template #header><span class="card-title">服务器信息</span></template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="服务器名称">
              {{ metrics?.sys?.computerName }}
            </el-descriptions-item>
            <el-descriptions-item label="操作系统">
              {{ metrics?.sys?.osName }}
            </el-descriptions-item>
            <el-descriptions-item label="系统架构">
              {{ metrics?.sys?.osArch }}
            </el-descriptions-item>
            <el-descriptions-item label="JVM 名称">
              {{ metrics?.jvm?.name }}
            </el-descriptions-item>
            <el-descriptions-item label="Java 版本">
              {{ metrics?.jvm?.version }}
            </el-descriptions-item>
            <el-descriptions-item label="运行时长">
              {{ metrics?.jvm?.runTime }}
            </el-descriptions-item>
            <el-descriptions-item label="项目路径">
              {{ metrics?.sys?.userDir }}
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
/**
 * ECharts 按需引入。
 *
 * 直接 `import * as echarts from 'echarts'` 会把所有图表类型（含地图、3D 等）
 * 全部打进包里，体积约 1MB。这里只注册实际用到的仪表盘、折线图和画布渲染器，
 * 配合 vite 的 manualChunks 拆分，首屏不必加载完整 ECharts。
 */
import * as echarts from 'echarts/core'
import { GaugeChart, LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([GaugeChart, LineChart, GridComponent, TooltipComponent, CanvasRenderer])
import { serverMetrics } from '@/api/monitor'
import type { ServerMetrics } from '@/api/monitor'
import { createSseConnection } from '@/utils/sse'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()

const metrics = ref<ServerMetrics>()
const connected = ref(false)
const history = ref<number[]>([])

const gaugeRef = ref<HTMLDivElement>()
const lineRef = ref<HTMLDivElement>()
let gaugeChart: echarts.ECharts | undefined
let lineChart: echarts.ECharts | undefined
let closeSse: (() => void) | undefined

const lastUpdateText = computed(() => {
  if (!metrics.value?.timestamp) return '-'
  return new Date(metrics.value.timestamp).toLocaleTimeString('zh-CN', { hour12: false })
})

function progressStatus(value: number) {
  if (value >= 90) return 'exception'
  if (value >= 70) return 'warning'
  return 'success'
}

function applyMetrics(data: ServerMetrics) {
  metrics.value = data
  history.value.push(data.cpu?.total ?? 0)
  // 只保留最近 40 个点，避免数组无限增长导致内存与渲染开销
  if (history.value.length > 40) history.value.shift()
  renderCharts()
}

function renderCharts() {
  const usage = metrics.value?.mem?.usage ?? 0
  const cpu = metrics.value?.cpu?.total ?? 0

  gaugeChart?.setOption({
    series: [
      {
        type: 'gauge',
        min: 0,
        max: 100,
        radius: '92%',
        center: ['28%', '58%'],
        title: { fontSize: 12, offsetCenter: [0, '68%'] },
        detail: { fontSize: 18, offsetCenter: [0, '38%'], formatter: '{value}%' },
        data: [{ value: cpu, name: 'CPU' }]
      },
      {
        type: 'gauge',
        min: 0,
        max: 100,
        radius: '92%',
        center: ['74%', '58%'],
        title: { fontSize: 12, offsetCenter: [0, '68%'] },
        detail: { fontSize: 18, offsetCenter: [0, '38%'], formatter: '{value}%' },
        data: [{ value: usage, name: '内存' }]
      }
    ]
  })

  lineChart?.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 24, bottom: 28 },
    xAxis: {
      type: 'category',
      data: history.value.map((_, i) => i),
      show: false
    },
    yAxis: { type: 'value', max: 100, min: 0, splitNumber: 4 },
    series: [
      {
        name: 'CPU',
        type: 'line',
        smooth: true,
        showSymbol: false,
        areaStyle: { opacity: 0.18 },
        data: history.value
      }
    ]
  })
}

function initCharts() {
  if (gaugeRef.value) gaugeChart = echarts.init(gaugeRef.value)
  if (lineRef.value) lineChart = echarts.init(lineRef.value)
}

function onResize() {
  gaugeChart?.resize()
  lineChart?.resize()
}

onMounted(async () => {
  initCharts()
  window.addEventListener('resize', onResize)

  // 先取一次快照立即渲染，避免"打开页面先空白几秒"
  metrics.value = await serverMetrics()
  applyMetrics(metrics.value)

  // 再建立 SSE 推送；连接失败自动退回定时轮询，保证页面仍可用
  closeSse = createSseConnection({
    url: '/api/monitor/server/stream',
    token: userStore.token,
    onMessage: (data) => {
      connected.value = true
      applyMetrics(data)
    },
    onError: () => {
      connected.value = false
      pollTimer = window.setInterval(async () => {
        applyMetrics(await serverMetrics())
      }, 5000)
    }
  })
})

let pollTimer: number | undefined

onBeforeUnmount(() => {
  closeSse?.()
  if (pollTimer) window.clearInterval(pollTimer)
  window.removeEventListener('resize', onResize)
  gaugeChart?.dispose()
  lineChart?.dispose()
})
</script>

<style scoped>
.metric-row {
  margin-bottom: 16px;
}

.metric-label {
  font-size: 13px;
  color: #909399;
}

.metric-value {
  font-size: 26px;
  font-weight: 600;
  color: #303133;
  margin: 4px 0 8px;
  line-height: 1.1;
}

.metric-value.small {
  font-size: 20px;
}

.metric-sub {
  margin-top: 8px;
  font-size: 12px;
  color: #a8abb2;
}

.chart {
  width: 100%;
}

.gauge-chart {
  height: 260px;
}

.line-chart {
  height: 260px;
}

.bottom-row {
  margin-top: 16px;
}

.card-title {
  font-weight: 600;
}
</style>
