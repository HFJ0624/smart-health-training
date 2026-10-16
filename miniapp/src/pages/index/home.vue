<template>
  <view class="fa-page home">
    <!-- ========== 区域一：轻量顶部（招呼 + 日期 + 一个档案入口，不放产品说明） ========== -->
    <view class="home__top">
      <view class="home__top-main">
        <text class="home__hello">{{ greeting }}</text>
        <text class="home__date">{{ dateText }}</text>
      </view>
      <view class="home__top-entry" hover-class="home__top-entry--hover" @tap="goProfile">
        <FaIcon name="user" tone="accent" size="44rpx" />
        <text class="home__top-label">档案</text>
      </view>
    </view>

    <!-- ========== 区域二：今日主卡（整张卡一个落点，一个主操作） ========== -->
    <StateBlock
      :state="todayState"
      :failure="todayFailure"
      empty-title="未设计划"
      empty-desc="建立或导入一份每周计划并确认生效后，这里会出现今天的安排。"
      @retry="loadToday"
    >
      <template #action>
        <view class="fa-btn-row home__empty-actions">
          <view class="fa-btn fa-btn--primary" @tap="goNewPlan">新建</view>
          <view class="fa-btn" @tap="goPlans">计划</view>
        </view>
      </template>

      <view class="home__hero" hover-class="home__hero--hover" @tap="onHeroTap">
        <FaIcon :name="heroIcon" tone="accent" size="64rpx" />
        <view class="home__hero-text">
          <text class="home__hero-title">{{ heroTitle }}</text>
          <text v-if="heroDesc" class="home__hero-desc">{{ heroDesc }}</text>
        </view>
        <view class="fa-btn fa-btn--primary home__hero-btn" @tap.stop="onHeroTap">
          {{ heroAction }}
        </view>
      </view>
      <!-- 进行中记录读不到时不猜：明说读不到，主操作仍按今日计划给 -->
      <text v-if="draftHint" class="fa-hint home__draft-hint">{{ draftHint }}</text>
    </StateBlock>

    <!-- ========== 区域三：四个常用图标入口（一个图标 + 两字标签 + 一个落点） ========== -->
    <view class="fa-tiles">
      <FaIconTile icon="weight" label="体重" :hint="weightTileHint" @tap="goWeight" />
      <FaIconTile icon="calendar" label="计划" hint="本周安排" @tap="goPlans" />
      <FaIconTile icon="robot" label="教练" hint="问一句" @tap="goCoach" />
      <!-- 导入入口没有消失：它留在训练计划页的工具条里（pages/plan/list.vue） -->
      <FaIconTile icon="dumbbell" label="训练" :hint="workoutTileHint" @tap="goWorkout" />
    </view>

    <!-- ========== 区域四：两个轻量数据组件（点按进对应详情） ========== -->
    <view class="fa-section-title">体重</view>
    <StateBlock
      :state="weightState"
      :failure="weightFailure"
      empty-title="没有记录"
      empty-desc="记一次体重，这里就有折线。"
      @retry="loadWeight"
    >
      <template #action>
        <view class="fa-btn fa-btn--primary" @tap="goWeight">记录</view>
      </template>
      <view class="fa-card home__trend" @tap="goWeight">
        <view class="fa-metrics">
          <FaMetric class="fa-metric" label="最新" :value="latestWeight" unit="kg" :caption="weightCaption" />
        </view>
        <FaTrendChart :points="trendPoints" :note="trendNote" height="260rpx" />
      </view>
    </StateBlock>

    <view class="fa-section-title">本周</view>
    <view class="fa-card home__week" @tap="goPlans">
      <FaDayStrip v-if="weekCells.length > 0" :cells="weekCells" model-value="" disabled />
      <text v-else class="fa-muted">{{ weekEmptyText }}</text>
      <text class="fa-hint">点=有安排，横线=休息日，空心=未安排。这是计划，不代表实际练过。</text>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 首页（UI 重排 §4）：只做"今天该干什么 + 去哪"，四块内容各自独立请求、独立五态，
 * 任何一块失败都不该把整页变成空白或假数据。
 *
 * 阶段 2 之后今日主卡的唯一主操作由真实状态推导（docs/stage2-design.md §8）：
 * 有进行中草稿=继续 → 今天有安排且无草稿=开始 → 休息日=休息 → 无生效计划=计划/新建。
 * 仍然不画进度环：`workout_session` 的统计口径归 metrics，本轮首页只报"有没有正在记的这一场"。
 */
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import FaDayStrip from '@/components/FaDayStrip.vue'
import FaIcon from '@/components/FaIcon.vue'
import FaIconTile from '@/components/FaIconTile.vue'
import FaMetric from '@/components/FaMetric.vue'
import FaTrendChart from '@/components/FaTrendChart.vue'
import StateBlock from '@/components/StateBlock.vue'
import { fetchBodyRecords } from '@/api/body'
import { fetchPlan, fetchTodayPlan } from '@/api/training'
import { fetchDraftWorkoutSession } from '@/api/workout'
import { WEEKDAY_LABELS } from '@/types/training'
import type { BodyRecord } from '@/types/body'
import type { TodayPlanResult } from '@/types/training'
import type { WorkoutSessionSummary } from '@/types/workout'
import type { StripCell, TrendPoint } from '@/types/ui'
import type { FaIconName } from '@/styles/icon-names'
import type { PageState } from '@/types/api'
import { addDays, formatDateValue, instantToDisplay, todayDateValue, todayIsoWeekday } from '@/utils/date'
import { pageItems, ApiError } from '@/utils/request'
import { formatDecimal } from '@/utils/units'
import { toFailureView, type FailureView } from '@/utils/errorMessage'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()

/** 首页趋势窗口：30 天。窗口口径写在卡片文案里，不假装是"全部历史"。 */
const TREND_WINDOW_DAYS = 30

const today = ref<TodayPlanResult | null>(null)
const records = ref<BodyRecord[]>([])
const weekCells = ref<StripCell[]>([])
const weekEmptyText = ref('')
/** 唯一进行中草稿（数据库 `UNIQUE(active_user_id)` 保证至多一条）；null 也可能是"没读到"，见 draftHint。 */
const draft = ref<WorkoutSessionSummary | null>(null)
const draftHint = ref('')

const todayState = ref<PageState>('loading')
const weightState = ref<PageState>('loading')
const todayFailure = ref<FailureView | null>(null)
const weightFailure = ref<FailureView | null>(null)

onShow(() => {
  session.restore()
  if (!session.hasToken) {
    uni.reLaunch({ url: '/pages/auth/login' })
    return
  }
  loadToday()
  loadWeight()
})

async function loadToday(): Promise<void> {
  todayState.value = 'loading'
  todayFailure.value = null
  try {
    const result = await fetchTodayPlan()
    today.value = result
    await loadDraft()
    // 有草稿就算成功态：否则计划被归档后「继续」这条路会从首页消失
    todayState.value = result.activePlanId || draft.value ? 'success' : 'empty'
    await loadWeek(result.activePlanId)
  } catch (err) {
    todayFailure.value = toFailureView(err)
    todayState.value = failState(err)
  }
}

/** 草稿这一块尽力而为：读失败只降级成一行说明，不把今日主卡整块变成错误态。 */
async function loadDraft(): Promise<void> {
  draft.value = null
  draftHint.value = ''
  try {
    draft.value = await fetchDraftWorkoutSession()
  } catch (err) {
    if (err instanceof ApiError && (err.code === 'UNAUTHENTICATED' || err.code === 'SESSION_EXPIRED')) throw err
    draftHint.value = '进行中的训练记录读取失败，重进本页可再试；下面按钮按今日计划显示。'
  }
}

/** 本周点阵要多读一次整份计划；失败只降级这一小块，不影响主卡已经拿到的今天数据。 */
async function loadWeek(planId: string | null): Promise<void> {
  weekCells.value = []
  if (!planId) return
  try {
    const plan = await fetchPlan(planId)
    const byWeekday = new Map<number, number>()
    plan.days.forEach((d) => byWeekday.set(d.isoWeekday, d.isRestDay ? 0 : d.items.length))
    weekCells.value = [1, 2, 3, 4, 5, 6, 7].map<StripCell>((iso) => {
      const count = byWeekday.get(iso)
      return {
        key: String(iso),
        label: WEEKDAY_LABELS[iso],
        mark: count === undefined ? 'off' : count === 0 ? 'rest' : 'on'
      }
    })
    weekEmptyText.value = ''
  } catch {
    weekEmptyText.value = '本周安排读取失败，点进去看计划'
  }
}

async function loadWeight(): Promise<void> {
  weightState.value = 'loading'
  weightFailure.value = null
  try {
    const res = await fetchBodyRecords({
      from: addDays(todayDateValue(), -(TREND_WINDOW_DAYS - 1)),
      to: todayDateValue(),
      page: 1,
      size: 100,
      sort: 'measuredAt,asc'
    })
    records.value = pageItems(res)
    weightState.value = records.value.length > 0 ? 'success' : 'empty'
  } catch (err) {
    weightFailure.value = toFailureView(err)
    weightState.value = failState(err)
  }
}

function failState(err: unknown): PageState {
  return err instanceof ApiError && (err.code === 'UNAUTHENTICATED' || err.code === 'SESSION_EXPIRED')
    ? 'sessionExpired'
    : 'error'
}

/* ---------- 今日主卡文案：状态由真实字段推导，缺就明说缺 ---------- */
/** 草稿优先于计划：正在记的那一场比"今天计划练什么"更是用户下一步要做的事。 */
const heroIcon = computed<FaIconName>(() => {
  if (draft.value) return 'dumbbell'
  const t = today.value
  if (!t || !t.activePlanId) return 'calendar'
  if (!t.day) return 'calendar'
  return t.day.isRestDay ? 'moon' : 'dumbbell'
})

const heroTitle = computed(() => {
  const d = draft.value
  if (d) return `训练进行中 · ${d.businessDate}`
  const t = today.value
  if (!t) return '今天'
  const day = t.isoWeekday ? WEEKDAY_LABELS[t.isoWeekday] ?? '今天' : '今天'
  if (!t.activePlanId) return '还没有生效计划'
  if (!t.day) return `${day} · 计划里没安排`
  if (t.day.isRestDay) return `${day} · 休息`
  return `${day} · ${t.day.title || '训练日'}`
})

const heroDesc = computed(() => {
  const d = draft.value
  if (d) {
    return d.entryCount === 0
      ? '这一场还没记动作，点「继续」逐组勾'
      : `${d.entryCount} 个动作 · 已确认 ${d.completedSetCount} 组（未勾的不算完成）`
  }
  const t = today.value
  if (!t) return ''
  if (!t.activePlanId) return '先建立或导入一份计划，确认生效后这里会出现今天的安排'
  if (!t.day) return `版本 v${t.revisionNo}，今天不在周期内`
  if (t.day.isRestDay) return `${t.day.notes ? `${t.day.notes} · ` : ''}今天不练；想练的话从下方「训练」自主记录`
  return `${t.day.items.length} 个动作 · 版本 v${t.revisionNo}`
})

/** 唯一主操作：继续 / 开始 / 休息 / 计划（无计划时的空态另有「新建」）。 */
const heroAction = computed(() => {
  if (draft.value) return '继续'
  const t = today.value
  if (!t || !t.activePlanId) return '计划'
  if (!t.day) return '计划'
  return t.day.isRestDay ? '休息' : '开始'
})

function onHeroTap(): void {
  if (draft.value) {
    goWorkout()
    return
  }
  const t = today.value
  if (!t || !t.activePlanId) {
    goPlans()
    return
  }
  if (t.day && !t.day.isRestDay) {
    goWorkout()
    return
  }
  // 休息日与"今天没安排"都只有一个去处：看整周安排（训练 tab / 计划详情）
  goPlanDetail(t.activePlanId)
}

/* ---------- 体重区 ---------- */
const latestWeight = computed(() => (records.value.length > 0 ? records.value[records.value.length - 1].weightKg : null))

const weightCaption = computed(() => {
  const last = records.value[records.value.length - 1]
  return last ? `测量于 ${instantToDisplay(last.measuredAt)}` : `${TREND_WINDOW_DAYS} 天内没有测量`
})

const weightTileHint = computed(() =>
  latestWeight.value === null ? '记一次' : `${formatDecimal(latestWeight.value)} kg`
)

const trendPoints = computed<TrendPoint[]>(() =>
  records.value.map((r) => ({ t: Date.parse(r.measuredAt), label: r.businessDate.slice(5), value: r.weightKg }))
)

/** 单点不成线：提示词 §7 明确禁止把一个点拼成"趋势"。 */
const trendNote = computed(() => {
  const n = records.value.length
  if (n === 0) return ''
  if (n === 1) return `近 ${TREND_WINDOW_DAYS} 天只有 1 次测量，只画点不连线`
  return `近 ${TREND_WINDOW_DAYS} 天 · ${n} 次测量`
})

/* ---------- 顶部招呼 ---------- */
const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 11) return '早上好'
  if (h < 14) return '中午好'
  if (h < 19) return '下午好'
  return '晚上好'
})

const dateText = computed(() => {
  const v = todayDateValue()
  const week = WEEKDAY_LABELS[todayIsoWeekday()] ?? ''
  return `${formatDateValue(new Date())} ${week}`.trim() || v
})

/* ---------- 落点：一个入口一个目的地 ---------- */
function goProfile(): void {
  uni.navigateTo({ url: '/pages/mine/profile' })
}

function goWeight(): void {
  uni.navigateTo({ url: '/pages/mine/body/records' })
}

function goPlans(): void {
  uni.switchTab({ url: '/pages/plan/list' })
}

function goCoach(): void {
  uni.switchTab({ url: '/pages/coach/chat' })
}

function goWorkout(): void {
  uni.navigateTo({ url: workoutUrl() })
}

/**
 * 「开始」与「继续」都落在训练记录页，区别只在带不带参考：
 * 已有草稿时**不带** planId/planDayId——服务端单草稿闸门会回同一场，
 * 而带上参考会让跨天再进的请求先在「业务日期星期 ≠ 计划日星期」上被撞掉（WorkoutService.resolveReference）。
 */
function workoutUrl(): string {
  if (draft.value) return '/pages/workout/record'
  const t = today.value
  if (t && t.activePlanId && t.day && !t.day.isRestDay) {
    return `/pages/workout/record?planId=${encodeURIComponent(t.activePlanId)}&planDayId=${encodeURIComponent(t.day.id)}`
  }
  return '/pages/workout/record'
}

const workoutTileHint = computed(() => {
  if (draft.value) return '继续这一场'
  const t = today.value
  return t && t.day && !t.day.isRestDay ? '开始今天' : '自主记录'
})

function goNewPlan(): void {
  uni.navigateTo({ url: '/pages/plan/edit' })
}

function goPlanDetail(id: string | null): void {
  if (!id) return
  uni.navigateTo({ url: `/pages/plan/detail?id=${encodeURIComponent(id)}` })
}
</script>

<style lang="scss" scoped>
.home__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: $fa-space-section;
}

.home__hello {
  display: block;
  font-size: $fa-font-title;
  font-weight: 600;
}

.home__date {
  display: block;
  font-size: $fa-font-ui;
  color: $fa-text-sub;
}

.home__top-entry {
  display: flex;
  align-items: center;
  gap: 8rpx;
  min-height: $fa-tap;
  padding: 0 20rpx;
  border-radius: $fa-radius-sm;
  border: 1px solid $fa-control-border;
  background-color: $fa-surface;
}

.home__top-entry--hover {
  background-color: $fa-highlight;
}

.home__top-label {
  font-size: $fa-font-ui;
  font-weight: 600;
  color: $fa-accent;
}

.home__hero {
  display: flex;
  align-items: center;
  gap: $fa-space-tight;
  padding: $fa-space-card;
  margin-bottom: $fa-space-section;
  border-radius: $fa-radius;
  background-color: $fa-highlight;
  border: 1px solid $fa-line;
  min-height: 176rpx;
}

.home__hero--hover {
  border-color: $fa-control-border;
}

.home__hero-text {
  flex: 1;
  min-width: 0;
}

.home__hero-title {
  display: block;
  font-size: $fa-font-title;
  font-weight: 600;
  color: $fa-text;
}

.home__hero-desc {
  display: block;
  font-size: $fa-font-ui;
  color: $fa-text-sub;
  margin-top: 6rpx;
}

/* 主卡里只允许这一个按钮 */
.home__hero-btn {
  flex: 0 0 auto;
}

/* 空态给两个真去处：新建（主）/ 计划（次），都是真实落点，没有占位按钮 */
.home__empty-actions {
  width: 100%;
}

.home__draft-hint {
  display: block;
  margin: 0 0 $fa-space-section;
}

.home__trend,
.home__week {
  padding-bottom: $fa-space-card;
}

.home__week {
  margin-bottom: $fa-space-section;
}
</style>
