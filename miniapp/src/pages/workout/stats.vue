<template>
  <view class="fa-page">
    <!-- 一次一个主题：当天有分母、周层面禁止百分比，两套口径不叠在同一屏里对比 -->
    <view class="st-mode">
      <FaSegmented :model-value="mode" :options="MODE_OPTIONS" :disabled="busy" @update:model-value="setMode" />
      <text class="fa-hint">{{ modeHint }}</text>
    </view>

    <view class="st-pick">
      <DateField
        :model-value="dateValue"
        :label="mode === 'day' ? '业务日期' : '周内任一天'"
        :clearable="false"
        :end="todayValue"
        :hint="mode === 'day' ? '日期只接受 YYYY-MM-DD；未来日期后端没有数据，这里不让选。' : `周统计只接受整周：实际按所在周的周一 ${weekMonday} 取数。`"
        @update:model-value="setDate"
      />
      <view class="st-quick">
        <view class="fa-btn fa-btn--sm" @tap="useAnchor">{{ mode === 'day' ? '今天' : '本周' }}</view>
        <view class="fa-btn fa-btn--sm" @tap="usePrevious">{{ mode === 'day' ? '昨天' : '上周' }}</view>
        <view class="fa-btn fa-btn--sm" :class="{ 'fa-btn--disabled': busy }" @tap="reload">
          <FaIcon name="refresh" tone="accent" size="36rpx" />
          <text>重读</text>
        </view>
      </view>
    </view>

    <view v-if="invalidDateNote" class="fa-banner fa-banner--warn">
      <text class="fa-prose">{{ invalidDateNote }}</text>
    </view>
    <view v-if="revisionNote" class="fa-banner fa-banner--info">
      <text class="fa-prose">{{ revisionNote }}</text>
    </view>

    <StateBlock
      :state="pageState"
      :failure="failure"
      :loading-text="loadingText"
      :empty-title="emptyTitle"
      :empty-desc="emptyDesc"
      @retry="reload"
    >
      <template #action>
        <view class="fa-btn-row">
          <view class="fa-btn fa-btn--primary" @tap="goRecord">记录</view>
          <view class="fa-btn" @tap="goHistory">历史</view>
        </view>
      </template>

      <!-- ==================== 主题一：当天「计划安排 vs 已确认执行」 ==================== -->
      <template v-if="mode === 'day' && day">
        <view class="fa-card">
          <view class="st-head">
            <text class="fa-title">{{ dayTitle }}</text>
            <text class="fa-chip" :class="hasConfirmedSession ? 'fa-chip--success' : ''">
              {{ hasConfirmedSession ? '有已确认记录' : '无已确认记录' }}
            </text>
          </view>
          <text class="fa-hint">口径 {{ day.ruleVersion }} · 数据修订 {{ day.dataRevision || '未知' }}</text>

          <view v-if="day.recordSummary" class="st-summary">
            <view class="fa-metrics">
              <FaMetric class="fa-metric" :label="metricLabel('COMPLETED_SESSION_COUNT')" v-bind="countView(sessionCountMetric)" />
              <FaMetric class="fa-metric" :label="metricLabel('CONFIRMED_SETS_TOTAL')" v-bind="countView(confirmedSetsMetric)" />
            </view>
            <view class="fa-metrics st-metrics">
              <FaMetric class="fa-metric" :label="metricLabel('SESSION_ELAPSED_SECONDS')" v-bind="durationView(elapsedMetric)" />
              <FaMetric class="fa-metric" :label="metricLabel('RECORDED_MOVEMENT_SECONDS')" v-bind="durationView(movementMetric)" />
            </view>
            <!-- §6：两个时长各自成立，相加会得出一个谁都没记过的「总活动时长」 -->
            <text class="fa-hint">场次历时含组间休息，与动作时长分列，不相加。</text>
            <text v-if="daySpan" class="fa-hint">{{ daySpan }}</text>
            <view v-for="(line, i) in missingLines(day.recordSummary.missingFields)" :key="`dm-${i}`" class="st-missing">
              <FaIcon name="alert" tone="accent" size="36rpx" />
              <text class="fa-hint">{{ line }}</text>
            </view>
          </view>

          <view v-else class="fa-banner fa-banner--warn">
            <text class="fa-prose">服务端这次没给当天记录摘要，页面不猜：下面只按计划版本显示可比对的部分。</text>
          </view>
        </view>

        <view class="fa-section-title">计划版本</view>
        <text v-if="day.planVersions.length === 0" class="fa-muted">
          这天没有可对比的计划版本{{ day.missingCodes.length > 0 ? '，原因见下面「缺失说明」' : '' }}。
        </text>
        <!-- 没安排 + 没已确认场次 + 没自主动作条目：这是契约里合法的「空」，给一条真去处而不是留白（§8）。
             只说「没有可比对的记录」，不说「这天什么都没做」——休息日与漏记在数据上长得一样，只有用户知道。 -->
        <view v-if="dayEmptyCase" class="fa-card">
          <EmptyState
            title="这天没有可比对的记录"
            desc="计划里这天没安排，也没有「结束并确认」的训练。想练就直接记一场，自主记录同样进统计。"
            icon="calendar"
          >
            <template #action>
              <view class="fa-btn-row">
                <view class="fa-btn fa-btn--primary" @tap="goRecord">记录</view>
                <view class="fa-btn" @tap="goHistory">历史</view>
              </view>
            </template>
          </EmptyState>
        </view>

        <!-- 不同版本的分母不合并（§6）：一个版本一张卡，进度环与两组数字也各算各的 -->
        <view v-for="group in day.planVersions" :key="`${group.planId}|${group.planDayId || ''}`" class="fa-card">
          <view class="st-head">
            <text class="fa-title">{{ group.planTitle || `计划 ${group.planId}` }}</text>
            <text class="fa-chip" :class="group.scheduledForThisDate ? 'fa-chip--primary' : ''">
              {{ group.scheduledForThisDate ? '今日安排' : '历史参考' }}
            </text>
          </view>
          <text class="fa-hint">
            第 {{ group.planRevisionNo === null ? '?' : group.planRevisionNo }} 版 · {{ planStatusText(group.planStatus) }} ·
            {{ group.items.length }} 个条目 · 来源 {{ group.sourceSessionIds.length }} 场
          </text>

          <view class="st-ring">
            <FaProgressRing :percent="ringPercent(group)" :label="ringLabel(group)" :note="ringNote(group)" caption="已记录" />
          </view>

          <view class="fa-metrics st-metrics">
            <FaMetric class="fa-metric" :label="metricLabel('PLANNED_TARGET_SETS')" v-bind="countView(groupMetric(group, 'PLANNED_TARGET_SETS'))" />
            <FaMetric class="fa-metric" :label="metricLabel('CONFIRMED_SETS_CAPPED')" v-bind="countView(groupMetric(group, 'CONFIRMED_SETS_CAPPED'))" />
          </view>
          <view class="fa-metrics st-metrics">
            <FaMetric class="fa-metric" :label="metricLabel('EXTRA_SETS')" v-bind="countView(groupMetric(group, 'EXTRA_SETS'))" />
            <FaMetric class="fa-metric" :label="metricLabel('RECORDED_MOVEMENT_SECONDS')" v-bind="durationView(groupMetric(group, 'RECORDED_MOVEMENT_SECONDS'))" />
          </view>
          <text class="fa-hint">额外组与自主动作都单列，不抵消其它条目的缺口。</text>

          <view v-for="item in group.items" :key="item.planItemId" class="st-item">
            <view class="st-item__head">
              <text class="fa-strong">{{ item.exerciseName || '动作字典里已查不到这个动作' }}</text>
              <text class="fa-muted">第 {{ item.positionNo }} 项</text>
            </view>
            <view class="st-cols">
              <text class="fa-muted st-cols__label">计划</text>
              <text class="st-cols__text">{{ plannedText(item) }}</text>
            </view>
            <view class="st-cols">
              <text class="fa-muted st-cols__label">实际</text>
              <text class="st-cols__text">{{ actualText(item) }}</text>
            </view>
            <view class="st-cols">
              <text class="fa-muted st-cols__label">次数</text>
              <text class="st-cols__text">{{ repsText(item) }}</text>
            </view>
            <view v-for="(line, i) in missingLines(item.missingFields)" :key="`im-${i}`" class="st-missing">
              <FaIcon name="alert" tone="accent" size="36rpx" />
              <text class="fa-hint">{{ line }}</text>
            </view>
          </view>

          <view v-for="(line, i) in missingLines(group.missingFields)" :key="`gm-${i}`" class="st-missing">
            <FaIcon name="alert" tone="accent" size="36rpx" />
            <text class="fa-hint">{{ line }}</text>
          </view>
        </view>

        <!-- 自主动作单列：它没有分母，也就无从谈完成度（§6） -->
        <view v-if="day.selfInitiated && day.selfInitiated.entries.length > 0" class="fa-card">
          <text class="fa-section-title fa-section-title--first">自主动作</text>
          <view class="fa-metrics">
            <FaMetric class="fa-metric" :label="metricLabel('SELF_INITIATED_ENTRY_COUNT')" v-bind="countView(selfMetric('SELF_INITIATED_ENTRY_COUNT'))" />
            <FaMetric class="fa-metric" :label="metricLabel('SELF_INITIATED_CONFIRMED_SETS')" v-bind="countView(selfMetric('SELF_INITIATED_CONFIRMED_SETS'))" />
          </view>
          <view v-for="entry in day.selfInitiated.entries" :key="entry.entryId" class="st-item">
            <view class="st-item__head">
              <text class="fa-strong">{{ entry.exerciseName || '动作字典里已查不到这个动作' }}</text>
              <text class="fa-muted">第 {{ entry.positionNo }} 项</text>
            </view>
            <text class="fa-hint">{{ selfEntryText(entry) }}</text>
          </view>
          <view v-for="(line, i) in missingLines(day.selfInitiated.missingFields)" :key="`sm-${i}`" class="st-missing">
            <FaIcon name="alert" tone="accent" size="36rpx" />
            <text class="fa-hint">{{ line }}</text>
          </view>
        </view>

        <view v-if="day.missingCodes.length > 0" class="fa-card">
          <text class="fa-section-title fa-section-title--first">缺失说明</text>
          <view v-for="(line, i) in day.missingCodes" :key="`dc-${i}`" class="st-missing">
            <FaIcon name="alert" tone="accent" size="36rpx" />
            <text class="fa-hint">{{ line }}</text>
          </view>
        </view>
      </template>

      <!-- ==================== 主题二：本周（只有绝对量，页面上不出现任何百分比） ==================== -->
      <template v-if="mode === 'week' && week">
        <view class="fa-card">
          <view class="st-head">
            <text class="fa-title">{{ week.weekStartDate }} 起一周</text>
            <text class="fa-chip">{{ week.weekEndDate }} 止</text>
          </view>
          <text class="fa-hint">口径 {{ week.ruleVersion }} · 数据修订 {{ week.dataRevision || '未知' }}</text>

          <view class="fa-metrics st-metrics">
            <FaMetric class="fa-metric" :label="metricLabel('COMPLETED_SESSION_COUNT')" v-bind="countView(weekMetric('COMPLETED_SESSION_COUNT'))" />
            <FaMetric class="fa-metric" :label="metricLabel('DISTINCT_TRAINING_DAY_COUNT')" v-bind="countView(weekMetric('DISTINCT_TRAINING_DAY_COUNT'))" />
          </view>
          <view class="fa-metrics st-metrics">
            <FaMetric class="fa-metric" :label="metricLabel('CONFIRMED_SETS_TOTAL')" v-bind="countView(weekMetric('CONFIRMED_SETS_TOTAL'))" />
            <FaMetric class="fa-metric" label="时长" v-bind="durationView(weekMetric('SESSION_ELAPSED_SECONDS'))" />
          </view>
          <!-- 上面这个「时长」是场次历时；动作时长是另一个量，单独一行给，绝不与它相加 -->
          <view class="st-cols st-motion">
            <text class="fa-muted st-cols__label">{{ metricLabel('EVIDENCED_MOVEMENT_SECONDS') }}</text>
            <text class="st-cols__text">{{ movementLine }}</text>
          </view>
        </view>

        <view class="fa-card">
          <text class="fa-section-title fa-section-title--first">训练日</text>
          <FaTrainingCalendar :week-start-date="week.weekStartDate" :days="week.days" :today="todayValue" :note="calendarNote" />
        </view>

        <view v-if="bars.length > 0" class="fa-card">
          <text class="fa-section-title fa-section-title--first">每天已确认组数</text>
          <view class="st-bars">
            <view v-for="bar in bars" :key="bar.date" class="st-bar">
              <view class="st-bar__slot">
                <view v-if="bar.height" class="st-bar__fill" :style="{ height: bar.height }" />
              </view>
              <text class="st-bar__text">{{ bar.text }}</text>
              <text class="st-bar__label">{{ bar.label }}</text>
            </view>
          </view>
          <text class="fa-hint">{{ barsNote }}</text>
        </view>

        <view v-if="week.days.length === 0" class="fa-card">
          <EmptyState
            title="本周没有已确认记录"
            desc="这一周还没有「结束并确认」的训练；进行中的草稿不计入统计。记一场并确认后回来就能看到。"
          >
            <template #action>
              <view class="fa-btn-row">
                <view class="fa-btn fa-btn--primary" @tap="goRecord">记录</view>
                <view class="fa-btn" @tap="goPreviousWeek">上周</view>
              </view>
            </template>
          </EmptyState>
        </view>

        <view v-if="missingLines(week.missingFields).length > 0" class="fa-card">
          <text class="fa-section-title fa-section-title--first">缺失说明</text>
          <view v-for="(line, i) in missingLines(week.missingFields)" :key="`wm-${i}`" class="st-missing">
            <FaIcon name="alert" tone="accent" size="36rpx" />
            <text class="fa-hint">{{ line }}</text>
          </view>
        </view>

        <text class="fa-hint st-rule">周层面只给场次、天数、组数与时长：把每天的完成度百分比再平均是假口径，所以这里没有百分比。</text>
      </template>
    </StateBlock>
  </view>
</template>

<script setup lang="ts">
/**
 * 训练统计（阶段 2 第二片，docs/stage2-design.md §6、§8）。
 *
 * 四条实现要点：
 *   1. **一个数字都不在前端算**：进度环、组数、时长一律按 code 从服务端 `metrics[]` 里取，
 *      取不到就说取不到——小程序、Agent 工具与后端验收看的必须是同一个数。
 *   2. `value === null` 配 `unavailableReason` 显示为「暂无依据」，**不显示 0% 也不显示 100%**；
 *      但 0 场、0 组这类直接计数是真实的零，照原样显示，两者不混。
 *   3. 一天引用多个计划版本时按版本分卡组（不合并分母），自主动作单独一卡（没有分母）。
 *   4. `dataRevision` 是「底层数据变了没有」的唯一依据：`onShow` 静默重取，修订号不同才提示并替换，
 *      避免从记录页回来还展示上一版数字。
 */
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import DateField from '@/components/DateField.vue'
import EmptyState from '@/components/EmptyState.vue'
import FaIcon from '@/components/FaIcon.vue'
import FaMetric from '@/components/FaMetric.vue'
import FaProgressRing from '@/components/FaProgressRing.vue'
import FaSegmented from '@/components/FaSegmented.vue'
import FaTrainingCalendar from '@/components/FaTrainingCalendar.vue'
import StateBlock from '@/components/StateBlock.vue'
import { fetchTrainingDay, fetchTrainingWeek, mondayOf } from '@/api/metrics'
import {
  METRIC_REASON_LABELS,
  metricLabel,
  type DayComparison,
  type ItemComparison,
  type Metric,
  type PlanVersionComparison,
  type SelfInitiatedEntry,
  type WeekSummary
} from '@/types/metrics'
import { PLAN_STATUS_LABELS, weekdayLabel, type PlanStatus } from '@/types/training'
import type { FaIconName } from '@/styles/icon-names'
import type { PageState } from '@/types/api'
import { addDays, instantToDisplay, isDateValue, todayDateValue } from '@/utils/date'
import { secondsToHuman } from '@/utils/units'
import { toFailureView, type FailureView } from '@/utils/errorMessage'
import { isSessionExpiredError } from '@/utils/request'

const MODE_OPTIONS: Array<{ value: string; label: string; icon: FaIconName }> = [
  { value: 'day', label: '当天', icon: 'calendar' },
  { value: 'week', label: '本周', icon: 'chart' }
]

const todayValue = todayDateValue()
const mode = ref('day')
/** 视图锚点日期：day = 业务日期本身；week = 周内任一天，发请求前才换算成周一（服务端强校验周起始日）。 */
const dateValue = ref(todayValue)

/** `missingFields` 已经翻成可读行（`missingCodes`）：模板不再自己拆字符串。 */
type DayView = Omit<DayComparison, 'missingFields'> & { missingCodes: string[] }

const day = ref<DayView | null>(null)
const week = ref<WeekSummary | null>(null)

const busy = ref(false)
const loadedOnce = ref(false)
const failure = ref<FailureView | null>(null)
const invalidDateNote = ref('')
const revisionNote = ref('')
const loadedRevision = ref<string | null | undefined>(undefined)

const modeHint = computed(() =>
  mode.value === 'day'
    ? '当天看「计划安排 vs 已确认执行」：只有勾了完成的组才算数，计划本身永远不会自动变成已完成。'
    : '本周只看场次、天数、组数与时长这些绝对量，不给周完成度百分比。'
)

const weekMonday = computed(() => mondayOf(dateValue.value))

const hasContent = computed(() => (mode.value === 'day' ? !!day.value : !!week.value))

const pageState = computed<PageState>(() => {
  if (failure.value?.sessionExpired) return 'sessionExpired'
  if (hasContent.value) return 'success'
  if (busy.value) return 'loading'
  if (failure.value) return 'error'
  return loadedOnce.value ? 'empty' : 'loading'
})

const loadingText = computed(() => (mode.value === 'day' ? '正在读取当天对比…' : '正在读取本周统计…'))

const emptyTitle = computed(() => (mode.value === 'day' ? '这天读不到对比' : '这周读不到统计'))
const emptyDesc = computed(() =>
  mode.value === 'day'
    ? '换一个日期，或先记一场训练并点「结束」确认。'
    : '换一周试试；进行中的草稿不计入周统计，确认后才有数。'
)

/* ------------------------------------------------------------------ 取数 */

async function load(silent: boolean): Promise<void> {
  if (busy.value) return
  const target = mode.value === 'day' ? dateValue.value : weekMonday.value
  if (!isDateValue(target)) {
    invalidDateNote.value = '日期不是合法的 YYYY-MM-DD，没有发请求。选一个日期再看。'
    return
  }
  invalidDateNote.value = ''
  busy.value = true
  if (!silent) failure.value = null
  try {
    if (mode.value === 'day') {
      const res = await fetchTrainingDay(target)
      day.value = { ...res, missingCodes: missingLines(res.missingFields) }
      applyRevision(res.dataRevision, silent)
    } else {
      const res = await fetchTrainingWeek(target)
      week.value = res
      applyRevision(res.dataRevision, silent)
    }
    failure.value = null
  } catch (err) {
    if (!silent) failure.value = toFailureView(err)
    else uni.showToast({ title: err instanceof Error ? err.message : '重读失败', icon: 'none' })
    if (isSessionExpiredError(err)) loadedOnce.value = true
  } finally {
    loadedOnce.value = true
    busy.value = false
  }
}

/**
 * 修订号变了才提示：静默重取回来的数字如果和屏上同一修订，页面不该闪一条"数据已更新"的假提醒。
 * 修订号读不到时是 null（后端 data_revision 读取失败按未知处理），与 0 也不是一回事。
 */
function applyRevision(revision: string | null, silent: boolean): void {
  const previous = loadedRevision.value
  if (previous !== undefined && previous !== revision) {
    revisionNote.value = `数据修订号已从 ${previous ?? '未知'} 变为 ${revision ?? '未知'}，上面的数字按最新记录重取。`
  } else if (!silent) {
    revisionNote.value = ''
  }
  loadedRevision.value = revision
}

function reload(): void {
  void load(false)
}

/** 切主题清掉另一套内容：两个主题口径不同，留着旧卡会被读成同一份数据的两面。 */
function setMode(value: string): void {
  if (value === mode.value) return
  mode.value = value
  day.value = null
  week.value = null
  loadedRevision.value = undefined
  revisionNote.value = ''
  void load(false)
}

function setDate(value: string): void {
  if (!isDateValue(value)) {
    invalidDateNote.value = `「${value}」不是合法的 YYYY-MM-DD，没有发请求。`
    return
  }
  dateValue.value = value
  day.value = null
  week.value = null
  loadedRevision.value = undefined
  revisionNote.value = ''
  void load(false)
}

function useAnchor(): void {
  setDate(todayValue)
}

function usePrevious(): void {
  setDate(addDays(todayValue, mode.value === 'day' ? -1 : -7))
}

function goPreviousWeek(): void {
  setDate(addDays(weekMonday.value, -7))
}

function goRecord(): void {
  uni.navigateTo({ url: '/pages/workout/record' })
}

function goHistory(): void {
  uni.navigateTo({ url: '/pages/workout/history' })
}

/* ------------------------------------------------------------------ 当天：按 code 取数 */

const dayTitle = computed(() => {
  const d = day.value
  if (!d) return '当天对比'
  return `${d.businessDate} · ${weekdayLabel(d.isoWeekday)}`
})

const hasConfirmedSession = computed(() => (day.value?.recordSummary?.completedSessionCount ?? 0) > 0)

/** 当天整页「什么都没有」的判定：没安排、没已确认场次、也没有自主动作条目。 */
const dayEmptyCase = computed(() => {
  const d = day.value
  if (!d) return false
  return d.planVersions.length === 0 && !hasConfirmedSession.value && (d.selfInitiated?.entries.length ?? 0) === 0
})

function pick(list: Metric[] | undefined, code: string): Metric | undefined {
  return (list || []).find((m) => m.code === code)
}

const sessionCountMetric = computed(() => pick(day.value?.recordSummary?.metrics, 'COMPLETED_SESSION_COUNT'))
const confirmedSetsMetric = computed(() => pick(day.value?.recordSummary?.metrics, 'CONFIRMED_SETS_TOTAL'))
const elapsedMetric = computed(() => pick(day.value?.recordSummary?.metrics, 'SESSION_ELAPSED_SECONDS'))
const movementMetric = computed(() => pick(day.value?.recordSummary?.metrics, 'RECORDED_MOVEMENT_SECONDS'))

function selfMetric(code: string): Metric | undefined {
  return pick(day.value?.selfInitiated?.metrics, code)
}

function groupMetric(group: PlanVersionComparison, code: string): Metric | undefined {
  return pick(group.metrics, code)
}

function weekMetric(code: string): Metric | undefined {
  return pick(week.value?.metrics, code)
}

const daySpan = computed(() => {
  const s = day.value?.recordSummary
  if (!s || (!s.firstStartedAt && !s.lastEndedAt)) return ''
  return `最早开始 ${instantToDisplay(s.firstStartedAt)} · 最晚结束 ${instantToDisplay(s.lastEndedAt)}`
})

/** 环只在 `SET_COMPLETION_PCT` 有值时出现（§8：无目标或无已确认数据就不显示进度），null 走未知态。 */
function ringPercent(group: PlanVersionComparison): number | null {
  const m = groupMetric(group, 'SET_COMPLETION_PCT')
  return m && m.value !== null && m.value !== undefined ? m.value : null
}

function ringLabel(group: PlanVersionComparison): string {
  return group.scheduledForThisDate ? '已记录完成情况' : '该版本已记录情况'
}

function ringNote(group: PlanVersionComparison): string {
  const m = groupMetric(group, 'SET_COMPLETION_PCT')
  if (!m) return '这个口径本轮没有返回'
  if (m.value !== null && m.value !== undefined) return '已确认组先跨场次汇总，再按计划组数封顶后相除'
  return reasonText(m.unavailableReason)
}

function planStatusText(status: string | null): string {
  if (!status) return '计划状态未知'
  return PLAN_STATUS_LABELS[status as PlanStatus] || status
}

function nullable(n: number | null | undefined, word: string): string {
  return n === null || n === undefined ? word : String(n)
}

function reasonText(reason: string | null | undefined): string {
  if (!reason) return '暂无依据'
  return METRIC_REASON_LABELS[reason] || reason
}

function plannedText(item: ItemComparison): string {
  const p = item.planned
  if (!p) return '计划侧没有值'
  const sets = p.targetSets === null ? '组数未给' : `${p.targetSets} 组`
  const reps = p.repsMin === null && p.repsMax === null ? '次数区间未给' : `${nullable(p.repsMin, '?')}–${nullable(p.repsMax, '?')} 次`
  const load = p.targetExternalLoadKg === null ? '负重未给' : `${p.targetExternalLoadKg} kg`
  const duration = p.targetDurationSeconds === null ? '时长未给' : secondsToHuman(p.targetDurationSeconds)
  return `${sets} · ${reps} · ${load} · ${duration}${p.unavailableReason ? ` （${reasonText(p.unavailableReason)}）` : ''}`
}

function actualText(item: ItemComparison): string {
  const a = item.actual
  if (!a) return '这一版没有记到已确认的组'
  const capped = a.cappedSets === null ? '计入未定' : `计入 ${a.cappedSets} 组`
  const extra = a.extraSets === null ? '' : ` · 额外 ${a.extraSets} 组`
  const duration = a.recordedDurationSeconds === null ? '动作时长未记' : secondsToHuman(a.recordedDurationSeconds)
  return `已确认 ${nullable(a.confirmedSets, '未记')} 组 · ${capped}${extra} · ${duration}${a.durationUnavailableReason ? ` （${reasonText(a.durationUnavailableReason)}）` : ''}`
}

/** 次数是否落在区间是单独一组口径（§6），不参与「这组有没有被确认」。 */
function repsText(item: ItemComparison): string {
  const r = item.reps
  if (!r) return '这个口径本轮没有返回'
  if (r.inRangePercent !== null && r.inRangePercent !== undefined) {
    return `已确认 ${nullable(r.confirmedSets, '0')} 组，其中记了次数 ${nullable(r.setsWithReps, '0')} 组、落在区间 ${nullable(r.setsInRepsRange, '0')} 组（${r.inRangePercent}%）`
  }
  return reasonText(r.unavailableReason)
}

function selfEntryText(entry: SelfInitiatedEntry): string {
  const duration = entry.recordedDurationSeconds === null ? '时长未记' : secondsToHuman(entry.recordedDurationSeconds)
  const distance = entry.distanceMeters === null ? '' : ` · 距离 ${entry.distanceMeters} m`
  return `已确认 ${nullable(entry.confirmedSets, '未记')} 组 · ${duration}${distance} · 来自场次 ${entry.sessionId}`
}

/* ------------------------------------------------------------------ 本周 */

const calendarNote = computed(() => {
  const w = week.value
  if (!w) return ''
  return `只有「有已确认场次」的日期才亮（本周 ${w.days.length} 天）。不亮只表示没有已确认记录，不代表你什么都没做。`
})

const movementLine = computed(() => {
  const m = weekMetric('EVIDENCED_MOVEMENT_SECONDS')
  if (!m) return '这个口径本轮没有返回'
  if (m.value === null || m.value === undefined) return reasonText(m.unavailableReason)
  return `${secondsToHuman(m.value)}（只计证据完整的动作时长，不与场次历时相加）`
})

interface WeekBar {
  date: string
  label: string
  height: string
  text: string
}

/** 柱高按这一周里最大的日组数线性缩放——纯展示比例，不是训练口径；七天全对齐，缺的日子不画柱。 */
const bars = computed<WeekBar[]>(() => {
  const w = week.value
  if (!w) return []
  const values = w.days.map((d) => d.confirmedSetCount ?? 0)
  const max = values.length > 0 ? Math.max(...values) : 0
  if (max <= 0) return []
  const byDate = new Map<string, number | null>()
  w.days.forEach((d) => byDate.set(d.businessDate, d.confirmedSetCount))
  const out: WeekBar[] = []
  for (let i = 0; i < 7; i += 1) {
    const date = addDays(w.weekStartDate, i)
    const value = byDate.get(date)
    out.push({
      date,
      label: date.slice(5),
      height: value === undefined || value === null || value <= 0 ? '' : `${Math.round((value / max) * 100)}%`,
      text: value === undefined ? '—' : value === null ? '未记' : String(value)
    })
  }
  return out
})

const barsNote = computed(() => {
  const w = week.value
  if (!w) return ''
  const unread = w.days.filter((d) => d.recordedMovementSeconds === null).length
  const base = '柱高是「这一天已确认的组数」，只作横向比较，不是完成度；「—」是这天没有已确认记录。'
  return unread > 0 ? `${base} 另有 ${unread} 天的动作时长不完整，见缺失说明。` : base
})

/* ------------------------------------------------------------------ 通用格式化 */

interface MetricView {
  value: string | null
  unit: string
  caption: string
  unknownText: string
}

/** 计数类：0 是真实的零（场次数、组数），null 才是「无依据」——两者的显示文案必须不同。 */
function countView(m: Metric | undefined): MetricView {
  if (!m) return { value: null, unit: '', caption: '这个口径本轮没有返回', unknownText: '没有返回' }
  if (m.value === null || m.value === undefined) {
    return { value: null, unit: '', caption: reasonText(m.unavailableReason), unknownText: '暂无依据' }
  }
  return { value: String(m.value), unit: unitText(m.unit), caption: '', unknownText: '暂无依据' }
}

function durationView(m: Metric | undefined): MetricView {
  if (!m) return { value: null, unit: '', caption: '这个口径本轮没有返回', unknownText: '没有返回' }
  if (m.value === null || m.value === undefined) {
    return { value: null, unit: '', caption: reasonText(m.unavailableReason), unknownText: '暂无依据' }
  }
  return { value: secondsToHuman(m.value), unit: '', caption: '', unknownText: '暂无依据' }
}

function unitText(unit: string): string {
  if (unit === 'SETS') return '组'
  if (unit === 'SESSIONS') return '场'
  if (unit === 'DAYS') return '天'
  if (unit === 'ENTRIES') return '个'
  if (unit === 'SECONDS') return '秒'
  if (unit === 'PERCENT') return '%'
  return ''
}

/** 后端 `missingFields` 形如 `CODE:detail`（`MetricsRules` 的码 + mark() 的补充）：码翻成人话，detail 原样保留不改写。 */
function missingLines(fields: string[] | undefined): string[] {
  return (fields || [])
    .filter((f) => !!f)
    .map((raw) => {
      const at = raw.indexOf(':')
      const code = at < 0 ? raw : raw.slice(0, at)
      const detail = at < 0 ? '' : raw.slice(at + 1)
      const label = METRIC_REASON_LABELS[code] || code
      return detail ? `${label}（${detail}）` : label
    })
}

onLoad((query) => {
  const q = (query ?? {}) as Record<string, string | undefined>
  const nextMode = (q.mode || '').trim()
  if (nextMode === 'week' || nextMode === 'day') mode.value = nextMode
  const raw = (q.date || '').trim()
  if (raw) {
    if (isDateValue(raw)) dateValue.value = raw
    else invalidDateNote.value = `入口带的日期「${raw}」不是合法的 YYYY-MM-DD，已按今天取数。`
  }
  void load(false)
})

onShow(() => {
  // 首屏由 onLoad 取数；从记录页/历史页回来时静默重取，靠 dataRevision 判断屏上数字是否已经过期
  if (loadedOnce.value) void load(true)
})

onPullDownRefresh(() => {
  void load(false).then(() => uni.stopPullDownRefresh())
})
</script>

<style lang="scss" scoped>
.st-mode {
  margin-bottom: $fa-space-section;
}

.st-pick {
  background-color: $fa-surface;
  border: 1px solid $fa-line;
  border-radius: $fa-radius;
  padding: $fa-space-card;
  margin-bottom: $fa-space-section;
}

.st-quick {
  display: flex;
  flex-wrap: wrap;
  gap: $fa-space-tight;
  margin-top: $fa-space-tight;
}

.st-quick .fa-btn {
  flex: 1 1 30%;
}

.st-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $fa-space-tight;
  margin-bottom: 8rpx;
}

.st-summary {
  margin-top: $fa-space-tight;
}

.st-metrics {
  margin-top: $fa-space-tight;
}

.st-ring {
  padding: $fa-space-tight 0;
}

.st-item {
  padding: $fa-space-tight 0;
  border-top: 1px solid $fa-line;
}

.st-item__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $fa-space-tight;
}

.st-cols {
  display: flex;
  align-items: flex-start;
  gap: 12rpx;
  margin-top: 6rpx;
}

.st-cols__label {
  flex: 0 0 auto;
  min-width: 128rpx;
}

.st-cols__text {
  flex: 1;
  min-width: 0;
  font-size: $fa-font-ui;
  color: $fa-text;
  line-height: 1.55;
}

.st-motion {
  margin-top: $fa-space-tight;
  padding-top: $fa-space-tight;
  border-top: 1px solid $fa-line;
}

.st-missing {
  display: flex;
  align-items: flex-start;
  gap: 10rpx;
  margin-top: 6rpx;
}

.st-bars {
  display: flex;
  align-items: flex-end;
  gap: 10rpx;
  padding-top: $fa-space-tight;
}

.st-bar {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6rpx;
}

/* 容器高度是展示尺度；柱的实际高度由本组件按周内的日值算出，不写死数字 */
.st-bar__slot {
  height: 240rpx;
  width: 100%;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.st-bar__fill {
  width: 60%;
  border-radius: 8rpx 8rpx 0 0;
  background-color: $fa-primary;
  border: 1px solid $fa-control-border;
}

.st-bar__text {
  font-size: $fa-font-ui;
  color: $fa-text;
}

.st-bar__label {
  font-size: $fa-font-ui;
  color: $fa-text-mute;
}

.st-rule {
  display: block;
  margin-top: $fa-space-tight;
  line-height: 1.6;
}
</style>
