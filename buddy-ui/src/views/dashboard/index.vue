<template>
  <div class="page-container">
    <!-- 欢迎区 -->
    <el-card class="welcome-card" shadow="never">
      <div class="welcome">
        <el-avatar :size="52" class="welcome-avatar">
          {{ displayName.charAt(0) }}
        </el-avatar>
        <div class="welcome-text">
          <div class="hello">{{ greeting }}，{{ displayName }}</div>
          <div class="roles">
            <el-tag
              v-for="role in userStore.roles"
              :key="role"
              size="small"
              :type="role === 'admin' ? 'danger' : 'info'"
            >
              {{ role }}
            </el-tag>
            <span class="perm-count">共 {{ userStore.permissions.length }} 项权限</span>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 统计卡片 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :xs="12" :sm="12" :md="6" :lg="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat">
            <el-icon class="stat-icon" style="background: #ecf5ff; color: #409eff">
              <User />
            </el-icon>
            <div>
              <div class="stat-value">{{ stats.userCount }}</div>
              <div class="stat-label">系统用户</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6" :lg="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat">
            <el-icon class="stat-icon" style="background: #fdf6ec; color: #e6a23c">
              <UserFilled />
            </el-icon>
            <div>
              <div class="stat-value">{{ stats.roleCount }}</div>
              <div class="stat-label">角色数量</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6" :lg="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat">
            <el-icon class="stat-icon" style="background: #f0f9eb; color: #67c23a">
              <Menu />
            </el-icon>
            <div>
              <div class="stat-value">{{ stats.menuCount }}</div>
              <div class="stat-label">菜单权限</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6" :lg="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat">
            <el-icon class="stat-icon" style="background: #fef0f0; color: #f56c6c">
              <Monitor />
            </el-icon>
            <div>
              <div class="stat-value">{{ stats.onlineCount }}</div>
              <div class="stat-label">当前在线</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 图表区：资源使用率仪表 + 系统概览柱状（均为一次快照，无实时流） -->
    <el-row :gutter="16" class="chart-row">
      <el-col :xs="24" :md="10">
        <el-card shadow="never">
          <template #header><span class="card-title">资源使用率</span></template>
          <div ref="gaugeRef" class="chart gauge-chart" />
        </el-card>
      </el-col>
      <el-col :xs="24" :md="14">
        <el-card shadow="never">
          <template #header><span class="card-title">系统概览</span></template>
          <div ref="barRef" class="chart bar-chart" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="notice-row">
      <el-col :span="24">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span class="card-title">最新公告</span>
              <el-button link type="primary" size="small" @click="$router.push('/notice/index')">
                管理公告
              </el-button>
            </div>
          </template>
          <div v-if="latestNotices.length === 0" class="empty-tip">暂无公告</div>
          <div v-else class="notice-list">
            <div
              v-for="item in latestNotices"
              :key="item.id"
              class="notice-line"
              :class="{ unread: item.unread }"
            >
              <el-tag size="small" :type="item.type === 2 ? 'warning' : 'info'">
                {{ item.type === 2 ? '公告' : '通知' }}
              </el-tag>
              <span class="notice-title-text">{{ item.title }}</span>
              <span v-if="item.unread" class="unread-dot" />
              <span class="notice-time">{{ (item.publishTime || '').replace('T', ' ').slice(0, 16) }}</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 技术栈说明 -->
    <el-card shadow="never" class="stack-card">
      <template #header>
        <span class="card-title">框架能力</span>
      </template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="后端">
          Spring Boot 3.5 · Spring Security 6 · MyBatis-Plus 3.5 · JWT
        </el-descriptions-item>
        <el-descriptions-item label="前端">
          Vue 3 · TypeScript · Vite · Element Plus
        </el-descriptions-item>
        <el-descriptions-item label="鉴权方式">
          无状态 JWT + RBAC 动态权限（菜单表 perms 字段驱动）
        </el-descriptions-item>
        <el-descriptions-item label="会话控制">
          Redis 在线台账（ZSet + Hash），支持强制下线
        </el-descriptions-item>
        <el-descriptions-item label="数据层">
          逻辑删除 · 自动填充 · 乐观锁 · 防全表更新
        </el-descriptions-item>
        <el-descriptions-item label="接口文档">
          springdoc-openapi（OpenAPI 3），替代已停更的 springfox
        </el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { User, UserFilled, Menu, Monitor } from '@element-plus/icons-vue'
/**
 * ECharts 按需引入（与监控页一致）：只注册柱状图、仪表盘与画布渲染器，
 * 避免 `import * as echarts from 'echarts'` 全量引入 ~1MB 拖慢首屏。
 */
import * as echarts from 'echarts/core'
import { BarChart, GaugeChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([BarChart, GaugeChart, GridComponent, TooltipComponent, CanvasRenderer])
import { useUserStore } from '@/store/user'
import { pageUsers, listRoles, menuTree } from '@/api/system'
import { onlineUsers, serverMetrics } from '@/api/monitor'
import type { ServerMetrics } from '@/api/monitor'
import { myNotices, type MyNoticeVO } from '@/api/notice'

const userStore = useUserStore()

const stats = reactive({
  userCount: 0,
  roleCount: 0,
  menuCount: 0,
  onlineCount: 0
})

const latestNotices = ref<MyNoticeVO[]>([])

const metrics = ref<ServerMetrics>()
const gaugeRef = ref<HTMLDivElement>()
const barRef = ref<HTMLDivElement>()
let gaugeChart: echarts.ECharts | undefined
let barChart: echarts.ECharts | undefined

const displayName = computed(
  () => userStore.userInfo?.nickname || userStore.userInfo?.username || '访客'
)

/** 按当前时间给出不同问候语 */
const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '凌晨好'
  if (hour < 12) return '早上好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

/** 递归统计菜单节点总数（含按钮） */
function countMenus(menus: any[]): number {
  return menus.reduce((sum, item) => sum + 1 + countMenus(item.children ?? []), 0)
}

/** 使用率颜色：<70 蓝、70-90 橙、>=90 红，与监控页阈值口径一致 */
function usageColor(value: number): string {
  if (value >= 90) return '#f56c6c'
  if (value >= 70) return '#e6a23c'
  return '#409eff'
}

/** 三个使用率仪表：CPU / 内存 / JVM（快照值，无实时流） */
function renderGauges() {
  const cpu = metrics.value?.cpu?.total ?? 0
  const mem = metrics.value?.mem?.usage ?? 0
  const jvm = metrics.value?.jvm?.usage ?? 0
  // 半径随容器宽度自适应：保证三个环的水平间距(≈32% 宽)不被相邻环压叠（窄屏防重叠）
  const cw = gaugeRef.value?.clientWidth ?? 360
  const ratio = Math.min(0.62, (0.32 * cw) / 260)
  const radius = `${Math.round(ratio * 100)}%`
  const mk = (center: string[], name: string, value: number) => ({
    type: 'gauge' as const,
    min: 0,
    max: 100,
    radius,
    center,
    progress: { show: true, width: 10, itemStyle: { color: usageColor(value) } },
    axisLine: { lineStyle: { width: 10 } },
    pointer: { show: false },
    axisTick: { show: false },
    splitLine: { show: false },
    axisLabel: { show: false },
    title: { fontSize: 12, offsetCenter: [0, '78%'] },
    detail: { fontSize: 16, offsetCenter: [0, '42%'], formatter: '{value}%' },
    data: [{ value, name }]
  })
  gaugeChart?.setOption({
    series: [
      mk(['18%', '55%'], 'CPU', cpu),
      mk(['50%', '55%'], '内存', mem),
      mk(['82%', '55%'], 'JVM', jvm)
    ]
  })
}

/** 系统概览柱状：用户 / 角色 / 菜单 / 在线 */
function renderBar() {
  barChart?.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 24, bottom: 28 },
    xAxis: { type: 'category', data: ['用户', '角色', '菜单', '在线'] },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        type: 'bar',
        barWidth: '45%',
        itemStyle: { color: '#409eff', borderRadius: [4, 4, 0, 0] },
        data: [stats.userCount, stats.roleCount, stats.menuCount, stats.onlineCount]
      }
    ]
  })
}

function initCharts() {
  if (gaugeRef.value) gaugeChart = echarts.init(gaugeRef.value)
  if (barRef.value) barChart = echarts.init(barRef.value)
}

/** 尺寸变化（窗口 resize 或侧边栏折叠改变容器宽度）时重绘；仪表半径需按新宽度重算 */
function onResize() {
  gaugeChart?.resize()
  barChart?.resize()
  renderGauges()
}

let resizeObserver: ResizeObserver | undefined

onMounted(async () => {
  initCharts()
  // 用 ResizeObserver 观察图表容器：window.resize 捕获不到侧边栏折叠引起的宽度变化
  resizeObserver = new ResizeObserver(() => onResize())
  if (gaugeRef.value) resizeObserver.observe(gaugeRef.value)
  if (barRef.value) resizeObserver.observe(barRef.value)

  // 各项统计相互独立，并发拉取；单项失败不影响页面其它部分
  const [users, roles, menus, online, snap] = await Promise.all([
    pageUsers({ pageNum: 1, pageSize: 1 }).catch(() => ({ total: 0 }) as any),
    listRoles().catch(() => []),
    menuTree().catch(() => []),
    onlineUsers().catch(() => []),
    serverMetrics().catch(() => undefined)
  ])
  stats.userCount = users?.total ?? 0
  stats.roleCount = roles?.length ?? 0
  stats.menuCount = countMenus(menus ?? [])
  stats.onlineCount = online?.length ?? 0
  metrics.value = snap
  renderGauges()
  renderBar()

  // 首页只展示最近 5 条，完整列表在公告管理页
  const notices = await myNotices().catch(() => [])
  latestNotices.value = (notices ?? []).slice(0, 5)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  gaugeChart?.dispose()
  barChart?.dispose()
})
</script>

<style scoped>
.welcome-card {
  margin-bottom: 16px;
}

.welcome {
  display: flex;
  align-items: center;
  gap: 16px;
}

.welcome-avatar {
  background-color: #409eff;
  font-size: 22px;
}

.hello {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.roles {
  margin-top: 6px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.perm-count {
  font-size: 12px;
  color: #909399;
}

.stat-row {
  margin-bottom: 16px;
}

.stat {
  display: flex;
  align-items: center;
  gap: 14px;
}

.stat-icon {
  font-size: 24px;
  border-radius: 8px;
  padding: 12px;
}

.stat-value {
  font-size: 24px;
  font-weight: 600;
  color: #303133;
}

.stat-label {
  font-size: 13px;
  color: #909399;
  margin-top: 2px;
}

.notice-row {
  margin-bottom: 16px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.empty-tip {
  text-align: center;
  color: #c0c4cc;
  padding: 20px 0;
  font-size: 13px;
}

.notice-line {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 2px;
  border-bottom: 1px solid #f5f7fa;
}

.notice-line.unread .notice-title-text {
  font-weight: 600;
}

.notice-title-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.unread-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background-color: #f56c6c;
}

.notice-time {
  font-size: 12px;
  color: #a8abb2;
}

.chart-row {
  margin-bottom: 16px;
}

.chart {
  width: 100%;
}

.gauge-chart,
.bar-chart {
  height: 260px;
}

.card-title {
  font-weight: 600;
}
</style>
