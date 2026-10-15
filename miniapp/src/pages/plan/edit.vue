<template>
  <view class="fa-page">
    <StateBlock
      :state="pageState"
      :failure="failure"
      :submit-failure="submitFailure"
      loading-text="正在加载计划…"
      @retry="loadPlan"
    >
      <!-- 非 DRAFT：不能就地编辑，必须走 revise「另存为新草稿」（验收 A11，绝不覆盖已确认版本）。
           主操作（另存）走底部条，这里只留长句解释 -->
      <view v-if="mode === 'blocked' && loadedPlan" class="fa-banner fa-banner--warn">
        <text class="fa-prose">
          这是「{{ PLAN_STATUS_LABELS[loadedPlan.status] }}」版本（第 {{ loadedPlan.revisionNo }} 版），不能就地编辑。
          修改会基于它另存为同组的新草稿（revisionNo=max+1、basePlanId 指向本版），原版本保持不变。
        </text>
      </view>

      <template v-else-if="mode !== 'loading'">
        <!-- 计划头 -->
        <view class="fa-card">
          <text class="fa-section-title fa-section-title--first">{{ mode === 'new' ? '新建草稿' : '编辑草稿' }}</text>

          <view class="fa-field">
            <view class="fa-field__label fa-field__label--req"><text>计划标题（1–160 字符）</text></view>
            <input class="fa-input" :value="form.title" maxlength="160" placeholder="例如：下肢与上肢拉 · 减脂方向" @input="onTitleInput" />
          </view>

          <DateField v-model="form.startsOn" label="开始日期" required placeholder="选择开始日期" />

          <DateField
            :model-value="form.endsOn || ''"
            label="结束日期（可选，需 ≥ 开始日期）"
            placeholder="不设结束日期"
            clearable
            :start="form.startsOn"
            @update:model-value="(v) => (form.endsOn = v || null)"
          />
        </view>

        <!-- 单位换算提示（写死口径，docs/p1-plan-import.md §6）：整句口径仍在，逐条明细收进折叠区，
             表单第一眼先给"这天练什么"而不是五行规范 -->
        <view class="fa-card">
          <view class="pe-disc" hover-class="pe-disc--hover" @tap="hintsOpen = !hintsOpen">
            <view class="pe-disc__text">
              <text class="fa-section-title fa-section-title--first">填写口径（系统不做隐式换算）</text>
              <text class="fa-muted">时长只填秒、负重只填 kg、日期只填 YYYY-MM-DD、星期只填 1–7</text>
            </view>
            <view class="pe-disc__tail">
              <text class="fa-link">{{ hintsOpen ? '收起明细' : `展开 ${UNIT_CONVERSION_HINTS.length} 条明细` }}</text>
              <FaIcon class="pe-disc__chev" :class="{ 'pe-disc__chev--open': hintsOpen }" name="chevron" tone="accent" size="40rpx" />
            </view>
          </view>
          <view v-if="hintsOpen" class="pe-hints">
            <text v-for="(h, i) in UNIT_CONVERSION_HINTS" :key="`h-${i}`" class="pe-hints__line">· {{ h }}</text>
          </view>
        </view>

        <!-- 七日条 + 添加计划日：一次只展开一天（提示词 §5），条上的空心格用来对准「添加该星期」的目标 -->
        <view class="fa-card">
          <text class="fa-section-title fa-section-title--first">本周安排（{{ form.days.length }} / 7，每星期最多一日）</text>
          <FaDayStrip v-model="selectedWeekday" :cells="dayCells" />
          <text class="fa-hint">条内数字=该日动作数，「休」=休息日，「—」=还没安排；图形：实心点=有动作，横线=休息日，空心=未安排。</text>

          <view class="pe-addrow">
            <text class="fa-section-title pe-addrow__t">添加计划日</text>
            <text class="fa-muted pe-sub">选择星期后新增；已排过的星期会被标出且不能重复选。</text>
            <WeekdayPicker v-model="addWeekday" :used="usedWeekdays" @blocked="onBlockedWeekday" />
            <view class="fa-btn fa-btn--ghost pe-addbtn" @tap="addDay">
              <FaIcon name="plus" tone="accent" size="40rpx" />
              <text>添加{{ weekdayLabel(addWeekday) }}并排动作</text>
            </view>
          </view>
        </view>

        <!-- 当前选中日的展开表单（其余日在下面折叠成一行） -->
        <view v-if="currentDay" class="fa-card pe-day">
          <view class="pe-day__head">
            <view class="pe-day__title">
              <text class="pe-day__week">{{ weekdayLabel(currentDay.isoWeekday) }}</text>
              <text class="fa-muted pe-day__count">
                {{ currentDay.isRestDay ? '休息日 · 不安排动作' : `${currentDay.items.length} 个动作` }}
              </text>
            </view>
            <text v-if="dayIssueCount(currentDay.isoWeekday) > 0" class="fa-chip fa-chip--danger pe-day__chip">
              {{ dayIssueCount(currentDay.isoWeekday) }} 处待修正
            </text>
          </view>

          <view class="fa-field">
            <view class="fa-field__label"><text>本日标题（≤ 120 字符）</text></view>
            <input class="fa-input" :value="currentDay.title || ''" maxlength="120" placeholder="如：下肢与上肢拉" @input="onDayTitle(currentDay, $event)" />
          </view>

          <view class="pe-day__rest">
            <view class="pe-day__rest-text">
              <text class="fa-row__label pe-day__rest-label">休息日</text>
              <text class="fa-hint">休息日必须零动作；带动作切换会先问是否清空本日 {{ currentDay.items.length }} 个动作。</text>
            </view>
            <switch :checked="currentDay.isRestDay" :color="switchOnColor" @change="onRestChange(currentDay, $event)" />
          </view>

          <text v-if="currentDay.isRestDay" class="fa-prose pe-rest">本日已标为休息日，不安排训练动作；关闭上面的开关即可继续排动作。</text>

          <view v-else class="pe-items">
            <!-- 动作条目：收起时只给「#序号 + 动作名 + 组数×次数」这一行真实业务值，展开才摆出全部输入项 -->
            <view v-for="(it, ii) in currentDay.items" :key="it.key" class="pe-item">
              <view class="pe-item__head" hover-class="pe-item__head--hover" @tap="toggleItem(it.key)">
                <text class="pe-item__pos">#{{ it.positionNo }}</text>
                <view class="pe-item__pick">
                  <text class="fa-strong pe-item__name">{{ it.exerciseName || '（未选择动作）' }}</text>
                  <text class="fa-muted pe-item__target">{{ itemSummaryLine(it) }}</text>
                </view>
                <FaIcon
                  class="pe-item__chev"
                  :class="{ 'pe-item__chev--open': isItemOpen(it.key) }"
                  name="chevron"
                  tone="sub"
                  size="40rpx"
                />
              </view>

              <view v-if="isItemOpen(it.key)" class="pe-item__body">
                <view class="pe-item__meta">
                  <text class="fa-muted pe-item__code">{{ it.exerciseCode }} · {{ categoryLabel(it.category) }}</text>
                  <view class="pe-op pe-op--pick" @tap="openPicker(currentDay.key, 'replace', ii)">更换动作</view>
                </view>

                <view class="pe-item__ops">
                  <view class="pe-op" :class="{ 'pe-op--off': ii === 0 }" @tap="moveItem(currentDay, ii, -1)">↑ 上移</view>
                  <view class="pe-op" :class="{ 'pe-op--off': ii === currentDay.items.length - 1 }" @tap="moveItem(currentDay, ii, 1)">↓ 下移</view>
                  <view class="pe-op pe-op--del" @tap="removeItem(currentDay, ii)">删除动作</view>
                </view>

                <!-- STRENGTH / MOBILITY：组数 -->
                <view v-if="showSets(it.category)" class="pe-grid">
                  <NumberField
                    v-model="it.targetSets"
                    :label="it.category === 'STRENGTH' ? '组数 *' : '组数'"
                    unit="组"
                    integer-only
                    :max-decimals="0"
                    placeholder="如 4"
                  />
                </view>

                <!-- 次数（STRENGTH / MOBILITY） -->
                <view v-if="showReps(it.category)" class="pe-grid pe-grid--2">
                  <NumberField v-model="it.repsMin" class="pe-grid__cell" label="次数下限" unit="次" integer-only :max-decimals="0" placeholder="如 8" />
                  <NumberField v-model="it.repsMax" class="pe-grid__cell" label="次数上限" unit="次" integer-only :max-decimals="0" placeholder="如 10" hint="上限需 ≥ 下限" />
                </view>

                <!-- 负重（STRENGTH）：0=自重 -->
                <view v-if="showLoad(it.category)" class="pe-grid">
                  <NumberField v-model="it.targetExternalLoadKg" label="外部负重" unit="kg" :max-decimals="2" placeholder="如 60，0=自重" hint="仅 kg、最多 2 位小数；0 合法（自重），留空表示未设定" />
                </view>

                <!-- 时长（CARDIO 必填 / MOBILITY 可选） -->
                <view v-if="showDuration(it.category)" class="pe-grid">
                  <NumberField
                    v-model="it.targetDurationSeconds"
                    :label="it.category === 'CARDIO' ? '时长（秒）*' : '时长（秒）'"
                    unit="秒"
                    integer-only
                    :max-decimals="0"
                    placeholder="如 600"
                    hint="只接受秒：1 分钟 = 60 秒"
                  />
                </view>

                <!-- 组间休息（STRENGTH / MOBILITY；CARDIO 禁止） -->
                <view v-if="showRest(it.category)" class="pe-grid">
                  <NumberField v-model="it.restSeconds" label="组间休息（秒）" unit="秒" integer-only :max-decimals="0" placeholder="如 120" hint="0–3600 秒；1 分钟 = 60 秒" />
                </view>

                <view class="fa-field">
                  <view class="fa-field__label"><text>动作备注（≤ 800 字符）</text></view>
                  <input class="fa-input" :value="it.notes || ''" maxlength="800" placeholder="可选" @input="onItemNotes(it, $event)" />
                </view>
              </view>
            </view>

            <view v-if="currentDay.items.length === 0" class="fa-banner fa-banner--warn pe-emptyday">
              <text class="fa-prose">非休息日至少需要 1 个动作（EMPTY_TRAINING_DAY）。</text>
            </view>

            <view class="fa-btn fa-btn--ghost pe-additem" @tap="openPicker(currentDay.key, 'add', -1)">
              <FaIcon name="plus" tone="accent" size="40rpx" />
              <text>添加动作</text>
            </view>
          </view>

          <view class="fa-field">
            <view class="fa-field__label"><text>当日备注（≤ 800 字符）</text></view>
            <input class="fa-input" :value="currentDay.notes || ''" maxlength="800" placeholder="可选" @input="onDayNotes(currentDay, $event)" />
          </view>
        </view>

        <!-- 其余计划日折叠成一行：切换即"只展开一天"，但每天的动作数/休息标记/待修正数不藏 -->
        <view v-if="otherDays.length > 0" class="fa-card">
          <text class="fa-section-title fa-section-title--first">其他计划日（点按切换，一次只展开一天）</text>
          <view
            v-for="d in otherDays"
            :key="`fold-${d.key}`"
            class="pe-fold__row"
            hover-class="pe-fold__row--hover"
            @tap="selectDay(String(d.isoWeekday))"
          >
            <view class="pe-fold__text">
              <text class="pe-fold__week">{{ weekdayLabel(d.isoWeekday) }}</text>
              <text class="fa-muted pe-fold__sum">{{ dayFoldLine(d) }}</text>
            </view>
            <text v-if="dayIssueCount(d.isoWeekday) > 0" class="fa-chip fa-chip--danger pe-fold__chip">
              {{ dayIssueCount(d.isoWeekday) }} 处待修正
            </text>
            <FaIcon name="chevron" tone="sub" size="40rpx" />
          </view>
        </view>

        <view v-if="form.days.length === 0" class="fa-banner fa-banner--warn">
          <text class="fa-prose">还没有计划日：至少添加 1 个星期，最多 7 个。</text>
        </view>

        <!-- 本地校验问题清单（镜像后端矩阵，未通过不允许提交） -->
        <view v-if="issues.length > 0" class="pe-issues">
          <text class="fa-section-title pe-issues__t">校验未通过（{{ issues.length }} 处）</text>
          <view v-for="(iss, idx) in issues" :key="`iss-${idx}`" class="pe-issue">
            <text class="pe-issue__where">{{ iss.where }}</text>
            <text class="pe-issue__msg">{{ iss.message }}</text>
            <text class="pe-issue__code">{{ iss.code }}</text>
          </view>
        </view>

        <text class="fa-hint pe-savetip">提交会一次性替换整个聚合（计划头 + 计划日 + 动作），任一失败全部回滚。</text>
      </template>
    </StateBlock>

    <!-- 底部固定操作条：本页只有这一个主操作；删除本日 / 收起全部 / 返回详情进「更多」 -->
    <view v-if="pageState === 'success'" class="fa-bottom-bar">
      <view
        v-if="mode === 'blocked'"
        class="fa-btn fa-btn--primary"
        :class="{ 'fa-btn--disabled': revising }"
        @tap="reviseToDraft"
      >{{ revising ? '生成中…' : '另存为新草稿并编辑' }}</view>
      <view v-else class="fa-btn fa-btn--primary" :class="{ 'fa-btn--disabled': submitting }" @tap="submit">
        {{ submitting ? '保存中…' : mode === 'new' ? '保存草稿' : '保存修改' }}
      </view>
      <view class="pe-bottom-more" hover-class="pe-bottom-more--hover" @tap="moreOpen = true">
        <view class="fa-btn pe-bottom-more__btn">
          <FaIcon name="more" tone="text" size="40rpx" />
          <text>更多操作</text>
        </view>
      </view>
    </view>
    <view v-if="pageState === 'success'" class="fa-bottom-spacer" />

    <ExercisePicker v-model:visible="pickerVisible" @select="onExerciseSelected" />
    <FaMoreMenu v-model:open="moreOpen" :items="moreItems" title="更多操作" @select="onMoreSelect" />
  </view>
</template>

<script setup lang="ts">
/**
 * 计划聚合编辑器（docs/p1-api.md §7）。要点：
 * - 「一次调用整聚合提交」：新建走 `POST /api/plans`，编辑 DRAFT 走 `PUT /api/plans/{id}`（带 lockVersion，聚合替换）。
 * - 休息日必须零动作；非休息日 1–30 动作、`position_no` 连续（增删移后统一重排 1..n）。
 * - 每个动作按字典 `category` 走与服务端**同一套**类型×字段矩阵校验（docs/p1-plan-import.md §5）：
 *   STRENGTH 必填组数、禁时长；CARDIO 必填时长、禁组数/次数/负重/休息；MOBILITY 禁负重、至少一个目标；
 *   `repsMax>=repsMin`；负重 ≥0（0=自重，区别于未设定）；组间休息 0–3600。单位严格「秒 / kg」。
 * - 客户端校验只是「不弱于」服务端的预拦，绝不放松；提交前本地拦一次，服务端仍是权威。
 * - 载入到非 DRAFT（ACTIVE/ARCHIVED）→ 只给「另存为新草稿」，走 `revise` 端点，绝不 PUT 覆盖已确认版本（A11）。
 */
import { computed, reactive, ref } from 'vue'
import { detailValue, inputValue } from '@/utils/events'
import { onLoad } from '@dcloudio/uni-app'
import StateBlock from '@/components/StateBlock.vue'
import NumberField from '@/components/NumberField.vue'
import DateField from '@/components/DateField.vue'
import WeekdayPicker from '@/components/WeekdayPicker.vue'
import ExercisePicker from '@/components/ExercisePicker.vue'
import FaDayStrip from '@/components/FaDayStrip.vue'
import FaMoreMenu from '@/components/FaMoreMenu.vue'
import FaIcon from '@/components/FaIcon.vue'
import { createPlan, fetchPlan, revisePlan, updatePlan } from '@/api/training'
import { isSessionExpiredError } from '@/utils/request'
import { toFailureView, type FailureView } from '@/utils/errorMessage'
import { isDateValue, isEndsBeforeStarts, todayDateValue } from '@/utils/date'
import { formatDecimal, isDecimalWithin, secondsToHuman, UNIT_CONVERSION_HINTS } from '@/utils/units'
import { categoryLabel, type ExerciseCategory, type ExerciseItem } from '@/types/exercise'
import {
  ISO_WEEKDAYS,
  PLAN_STATUS_LABELS,
  WEEKDAY_LABELS,
  weekdayLabel,
  type PlanAggregateInput,
  type PlanDayInput,
  type PlanItemInput,
  type TrainingPlan
} from '@/types/training'
import { IMPORT_LIMITS } from '@/types/plan-import'
import { switchOnColor } from '@/styles/theme'
import type { MoreItem, StripCell } from '@/types/ui'
import type { PageState } from '@/types/api'

interface DraftItem {
  key: string
  positionNo: number
  exerciseCode: string
  exerciseName: string
  category: ExerciseCategory
  targetSets: number | null
  repsMin: number | null
  repsMax: number | null
  targetExternalLoadKg: number | null
  targetDurationSeconds: number | null
  restSeconds: number | null
  notes: string | null
}

interface DraftDay {
  key: string
  isoWeekday: number
  title: string | null
  isRestDay: boolean
  notes: string | null
  items: DraftItem[]
}

type Mode = 'loading' | 'new' | 'draft' | 'blocked'

let seq = 0
function mkKey(): string {
  seq += 1
  return `k${seq}`
}

const mode = ref<Mode>('loading')
const planId = ref('')
const lockVersion = ref(0)
const loadedPlan = ref<TrainingPlan | null>(null)

const form = reactive<{
  title: string
  startsOn: string
  endsOn: string | null
  days: DraftDay[]
}>({
  title: '',
  startsOn: '',
  endsOn: null,
  days: []
})

const addWeekday = ref<number>(1)
const loading = ref(false)
const submitting = ref(false)
/* 另存新草稿是写操作：没有忙碌标志时连点会生成两份草稿（reviseNo=max+1 每次都给新版） */
const revising = ref(false)
const failure = ref<FailureView | null>(null)
const submitFailure = ref<FailureView | null>(null)
const issues = ref<Array<{ where: string; message: string; code: string }>>([])

const pickerVisible = ref(false)
const pickerTarget = reactive<{ dayKey: string; action: 'add' | 'replace'; itemIndex: number }>({
  dayKey: '',
  action: 'add',
  itemIndex: -1
})

const pageState = computed<PageState>(() => {
  if (failure.value && failure.value.sessionExpired) return 'sessionExpired'
  if (mode.value === 'loading') return 'loading'
  if (failure.value) return 'error'
  return 'success'
})

const usedWeekdays = computed(() => form.days.map((d) => d.isoWeekday))

/* ---------- 纯展示状态（提示词 §5「一次只展开一天」+ §2.3 层级）：不碰校验、请求与提交路径 ---------- */
const activeDayKey = ref('')
const expandedItems = ref<string[]>([])
const moreOpen = ref(false)
const hintsOpen = ref(false)

/** 选中的星期（七日条的 key 就是 ISO 星期字符串）。未安排的格子不选，只把「添加该星期」的目标对准它。 */
const selectedWeekday = computed<string>({
  get: () => (currentDay.value ? String(currentDay.value.isoWeekday) : ''),
  set: (key) => selectDay(key)
})

/** 选中日被删掉时回落到第一天，避免出现「展开着一条不存在的一天」。 */
const currentDay = computed<DraftDay | null>(
  () => form.days.find((d) => String(d.isoWeekday) === activeDayKey.value) ?? form.days[0] ?? null
)

const otherDays = computed(() => {
  const cur = currentDay.value
  return form.days.filter((d) => d.key !== (cur ? cur.key : ''))
})

const dayCells = computed<StripCell[]>(() =>
  ISO_WEEKDAYS.map<StripCell>((iso) => {
    const d = form.days.find((x) => x.isoWeekday === iso)
    return {
      key: String(iso),
      label: WEEKDAY_LABELS[iso] ?? weekdayLabel(iso),
      sub: d ? (d.isRestDay ? '休' : String(d.items.length)) : '',
      mark: !d ? 'off' : d.isRestDay ? 'rest' : d.items.length > 0 ? 'on' : 'off'
    }
  })
)

/** 折叠区里也必须看得见"这天有几个动作、是不是休息、还有几处没修"，否则收起来就是在藏业务状态。 */
function dayFoldLine(d: DraftDay): string {
  const parts: string[] = []
  const t = (d.title ?? '').trim()
  if (t) parts.push(t)
  parts.push(d.isRestDay ? '休息日' : `${d.items.length} 个动作`)
  const bad = dayIssueCount(d.isoWeekday)
  if (bad > 0) parts.push(`${bad} 处待修正`)
  return parts.join(' · ')
}

function dayIssueCount(isoWeekday: number): number {
  const label = weekdayLabel(isoWeekday)
  return issues.value.filter((i) => i.where === label || i.where.startsWith(`${label} ·`)).length
}

/** 收起态的一行目标：组数×次数是业务内容，写全（含单位），不压成两字。 */
function itemSummaryLine(it: DraftItem): string {
  const parts: string[] = []
  if (it.category === 'CARDIO') {
    if (it.targetDurationSeconds != null) parts.push(secondsToHuman(it.targetDurationSeconds))
    if (it.notes && it.notes.trim()) parts.push('有备注')
    return parts.length > 0 ? parts.join(' · ') : '时长未填'
  }
  const setsText = it.targetSets != null ? `${it.targetSets} 组` : ''
  const repsText = repsSummaryLine(it)
  const core = setsText && repsText ? `${setsText} × ${repsText}` : setsText || repsText
  if (core) parts.push(core)
  if (it.targetExternalLoadKg != null) parts.push(formatDecimal(it.targetExternalLoadKg, 'kg'))
  if (it.targetDurationSeconds != null) parts.push(`时长 ${secondsToHuman(it.targetDurationSeconds)}`)
  if (it.restSeconds != null) parts.push(`组间 ${secondsToHuman(it.restSeconds)}`)
  if (parts.length === 0) return '目标未填'
  if (it.notes && it.notes.trim()) parts.push('有备注')
  return parts.join(' · ')
}

function repsSummaryLine(it: DraftItem): string {
  const min = it.repsMin
  const max = it.repsMax
  if (min == null && max == null) return ''
  if (min != null && max != null) return min === max ? `${min} 次` : `${min}–${max} 次`
  return min != null ? `≥${min} 次` : `≤${max} 次`
}

function isItemOpen(key: string): boolean {
  return expandedItems.value.includes(key)
}

function toggleItem(key: string): void {
  const idx = expandedItems.value.indexOf(key)
  if (idx >= 0) expandedItems.value.splice(idx, 1)
  else expandedItems.value.push(key)
}

function selectDay(key: string): void {
  const iso = Number(key)
  if (!Number.isInteger(iso) || iso < 1 || iso > 7) return
  if (!form.days.some((d) => d.isoWeekday === iso)) {
    if (usedWeekdays.value.includes(iso)) return
    addWeekday.value = iso
    uni.showToast({ title: `${weekdayLabel(iso)}还没安排，点下方「添加」按钮建这一日`, icon: 'none' })
    return
  }
  activeDayKey.value = key
}

const moreItems = computed<MoreItem[]>(() => {
  if (mode.value === 'blocked') return [{ key: 'back', label: '返回计划详情', icon: 'calendar' }]
  const out: MoreItem[] = []
  const cur = currentDay.value
  if (cur) out.push({ key: 'removeDay', label: `删除本日（${weekdayLabel(cur.isoWeekday)}）`, icon: 'alert', danger: true })
  if (expandedItems.value.length > 0) out.push({ key: 'collapse', label: '收起全部动作卡', icon: 'chevron' })
  out.push(
    loadedPlan.value
      ? { key: 'back', label: '返回计划详情', icon: 'calendar' }
      : { key: 'back', label: '返回上一页', icon: 'chevron' }
  )
  return out
})

function onMoreSelect(key: string): void {
  if (key === 'removeDay') {
    const cur = currentDay.value
    if (!cur) return
    const di = form.days.findIndex((d) => d.key === cur.key)
    if (di >= 0) removeDay(di)
    return
  }
  if (key === 'collapse') {
    expandedItems.value = []
    return
  }
  if (key === 'back') backToDetail()
}

/** 校验拦下来时必须把有问题的第一天摊开——「一次只展开一天」不能变成「看不见错在哪天」。 */
function revealFirstIssueDay(raw: RawIssue[]): void {
  const hit = raw.find((r) => /^days\[(\d+)\]/.test(r.path))
  const m = hit ? /^days\[(\d+)\]/.exec(hit.path) : null
  if (!m) return
  const di = Number(m[1])
  const day = form.days[di]
  if (!day) return
  activeDayKey.value = String(day.isoWeekday)
  const itemRe = new RegExp(`^days\\[${di}\\]\\.items\\[(\\d+)\\]`)
  raw.forEach((r) => {
    const im = itemRe.exec(r.path)
    const it = im ? day.items[Number(im[1])] : null
    if (it && !expandedItems.value.includes(it.key)) expandedItems.value.push(it.key)
  })
}

// ---- 字段可见性（按 category，与服务端矩阵一致）----
function showSets(cat: ExerciseCategory): boolean {
  return cat === 'STRENGTH' || cat === 'MOBILITY'
}
function showReps(cat: ExerciseCategory): boolean {
  return cat === 'STRENGTH' || cat === 'MOBILITY'
}
function showLoad(cat: ExerciseCategory): boolean {
  return cat === 'STRENGTH'
}
function showDuration(cat: ExerciseCategory): boolean {
  return cat === 'CARDIO' || cat === 'MOBILITY'
}
function showRest(cat: ExerciseCategory): boolean {
  return cat === 'STRENGTH' || cat === 'MOBILITY'
}

/** 按 category 清空该类型禁止的字段（改选动作、切换动作时调用，保证不把禁用值提交上去）。 */
function clearForbidden(it: DraftItem): void {
  if (it.category === 'STRENGTH') {
    it.targetDurationSeconds = null
  } else if (it.category === 'CARDIO') {
    it.targetSets = null
    it.repsMin = null
    it.repsMax = null
    it.targetExternalLoadKg = null
    it.restSeconds = null
  } else if (it.category === 'MOBILITY') {
    it.targetExternalLoadKg = null
  }
}

function renumber(day: DraftDay): void {
  day.items.forEach((it, idx) => {
    it.positionNo = idx + 1
  })
}

function norm(s: string | null | undefined): string | null {
  const t = (s ?? '').trim()
  return t.length > 0 ? t : null
}

// ---- 计划日增删 ----
function onBlockedWeekday(w: number): void {
  uni.showToast({ title: `${weekdayLabel(w)}已排，不能重复`, icon: 'none' })
}

function addDay(): void {
  if (form.days.length >= IMPORT_LIMITS.maxDays) {
    uni.showToast({ title: `最多 ${IMPORT_LIMITS.maxDays} 个计划日`, icon: 'none' })
    return
  }
  if (usedWeekdays.value.includes(addWeekday.value)) {
    uni.showToast({ title: '该星期已排', icon: 'none' })
    return
  }
  form.days.push({
    key: mkKey(),
    isoWeekday: addWeekday.value,
    title: null,
    isRestDay: false,
    notes: null,
    items: []
  })
  form.days.sort((a, b) => a.isoWeekday - b.isoWeekday)
  /* 刚建的一天是用户下一步要做的事：直接展开它，而不是让他再回七日条里找 */
  activeDayKey.value = String(addWeekday.value)
  const next = (ISO_WEEKDAYS as readonly number[]).find((w) => !usedWeekdays.value.includes(w))
  if (next != null) addWeekday.value = next
}

function removeDay(di: number): void {
  const day = form.days[di]
  if (!day) return
  uni.showModal({
    title: '删除计划日',
    content: `确认删除「${weekdayLabel(day.isoWeekday)}」及其 ${day.items.length} 个动作？`,
    success: (res) => {
      if (res.confirm) form.days.splice(di, 1)
    }
  })
}

function onRestChange(day: DraftDay, evt: Event): void {
  const wantRest = detailValue(evt) === true
  if (wantRest && day.items.length > 0) {
    uni.showModal({
      title: '休息日不能带动作',
      content: `切换为休息日会清空本日 ${day.items.length} 个动作，确认？`,
      success: (res) => {
        if (res.confirm) {
          day.items = []
          day.isRestDay = true
        }
      }
    })
    return
  }
  day.isRestDay = wantRest
}

// ---- 动作增删移 ----
function openPicker(dayKey: string, action: 'add' | 'replace', itemIndex: number): void {
  const day = form.days.find((d) => d.key === dayKey)
  if (!day) return
  if (day.isRestDay) {
    uni.showToast({ title: '休息日不能带动作', icon: 'none' })
    return
  }
  if (action === 'add' && day.items.length >= IMPORT_LIMITS.maxItemsPerDay) {
    uni.showToast({ title: `单日最多 ${IMPORT_LIMITS.maxItemsPerDay} 个动作`, icon: 'none' })
    return
  }
  pickerTarget.dayKey = dayKey
  pickerTarget.action = action
  pickerTarget.itemIndex = itemIndex
  pickerVisible.value = true
}

function onExerciseSelected(ex: ExerciseItem): void {
  const day = form.days.find((d) => d.key === pickerTarget.dayKey)
  if (!day) return
  const cat = ex.category
  if (pickerTarget.action === 'replace' && pickerTarget.itemIndex >= 0) {
    const it = day.items[pickerTarget.itemIndex]
    if (!it) return
    // 换动作可能改变 category：重置数值再按新类型清空禁止字段
    it.exerciseCode = ex.exerciseCode
    it.exerciseName = ex.name
    it.category = cat
    it.targetSets = null
    it.repsMin = null
    it.repsMax = null
    it.targetExternalLoadKg = null
    it.targetDurationSeconds = null
    it.restSeconds = null
    clearForbidden(it)
    return
  }
  const totalItems = form.days.reduce((sum, d) => sum + d.items.length, 0)
  if (totalItems >= IMPORT_LIMITS.maxItemsTotal) {
    uni.showToast({ title: `全计划最多 ${IMPORT_LIMITS.maxItemsTotal} 个动作`, icon: 'none' })
    return
  }
  const it: DraftItem = {
    key: mkKey(),
    positionNo: day.items.length + 1,
    exerciseCode: ex.exerciseCode,
    exerciseName: ex.name,
    category: cat,
    targetSets: null,
    repsMin: null,
    repsMax: null,
    targetExternalLoadKg: null,
    targetDurationSeconds: null,
    restSeconds: null,
    notes: null
  }
  clearForbidden(it)
  day.items.push(it)
  renumber(day)
  /* 选完动作就是要填目标：新条目自动展开，省一次「再点一下卡片」 */
  expandedItems.value.push(it.key)
}

function removeItem(day: DraftDay, ii: number): void {
  day.items.splice(ii, 1)
  renumber(day)
}

function moveItem(day: DraftDay, ii: number, dir: number): void {
  const target = ii + dir
  if (target < 0 || target >= day.items.length) return
  const arr = day.items
  const tmp = arr[ii]
  arr[ii] = arr[target]
  arr[target] = tmp
  renumber(day)
}

// 循环内输入用命名处理器，避免模板内联箭头带来的隐式 any
function onDayTitle(day: DraftDay, evt: Event): void {
  day.title = inputValue(evt)
}

function onDayNotes(day: DraftDay, evt: Event): void {
  day.notes = inputValue(evt)
}

function onItemNotes(it: DraftItem, evt: Event): void {
  it.notes = inputValue(evt)
}

// ---- 头部输入 ----
function onTitleInput(evt: Event): void {
  form.title = inputValue(evt)
}

// ---- 校验（镜像 docs/p1-plan-import.md §2–§5、§8）----
interface RawIssue {
  path: string
  message: string
  code: string
}

function validate(): RawIssue[] {
  const out: RawIssue[] = []
  const push = (path: string, code: string, message: string) => out.push({ path, code, message })

  const title = (form.title ?? '').trim()
  if (!title) push('title', 'REQUIRED', '计划标题不能为空')
  else if (title.length > IMPORT_LIMITS.maxTitleLength) push('title', 'TOO_LONG', `计划标题不超过 ${IMPORT_LIMITS.maxTitleLength} 字符`)

  if (!form.startsOn || !isDateValue(form.startsOn)) push('starts_on', 'INVALID_DATE_FORMAT', '开始日期需为 YYYY-MM-DD')
  if (form.endsOn) {
    if (!isDateValue(form.endsOn)) push('ends_on', 'INVALID_DATE_FORMAT', '结束日期需为 YYYY-MM-DD')
    else if (isEndsBeforeStarts(form.startsOn, form.endsOn)) push('ends_on', 'ENDS_BEFORE_STARTS', '结束日期不能早于开始日期')
  }

  if (form.days.length < IMPORT_LIMITS.minDays) push('days', 'REQUIRED', `至少安排 ${IMPORT_LIMITS.minDays} 个计划日`)
  if (form.days.length > IMPORT_LIMITS.maxDays) push('days', 'TOO_MANY_DAYS', `计划日数量需 1–${IMPORT_LIMITS.maxDays}`)

  const weekdaySeen = new Map<number, number>()
  let totalItems = 0

  form.days.forEach((day, i) => {
    if (day.isoWeekday < 1 || day.isoWeekday > 7) push(`days[${i}].iso_weekday`, 'INVALID_WEEKDAY', '星期需为 1–7')
    weekdaySeen.set(day.isoWeekday, (weekdaySeen.get(day.isoWeekday) ?? 0) + 1)
    if ((day.title ?? '').length > IMPORT_LIMITS.maxDayTitleLength) push(`days[${i}].title`, 'TOO_LONG', `本日标题不超过 ${IMPORT_LIMITS.maxDayTitleLength} 字符`)
    if ((day.notes ?? '').length > IMPORT_LIMITS.maxNotesLength) push(`days[${i}].notes`, 'TOO_LONG', `当日备注不超过 ${IMPORT_LIMITS.maxNotesLength} 字符`)

    if (day.isRestDay) {
      if (day.items.length > 0) push(`days[${i}].items`, 'REST_DAY_HAS_ITEMS', '休息日不能带动作')
      return
    }
    if (day.items.length < IMPORT_LIMITS.minItemsPerTrainingDay) push(`days[${i}].items`, 'EMPTY_TRAINING_DAY', '非休息日至少需要 1 个动作')
    if (day.items.length > IMPORT_LIMITS.maxItemsPerDay) push(`days[${i}].items`, 'TOO_MANY_ITEMS', `单日动作需 1–${IMPORT_LIMITS.maxItemsPerDay}`)
    totalItems += day.items.length

    day.items.forEach((it, j) => {
      const base = `days[${i}].items[${j}]`
      if (!it.exerciseCode) push(`${base}.exercise_code`, 'REQUIRED', '未选择动作')
      if (it.positionNo < 1) push(`${base}.position_no`, 'REQUIRED', '顺序号需 ≥1')
      if ((it.notes ?? '').length > IMPORT_LIMITS.maxNotesLength) push(`${base}.notes`, 'TOO_LONG', `动作备注不超过 ${IMPORT_LIMITS.maxNotesLength} 字符`)

      validateReps(it, base, push)
      validateRest(it, base, push)

      if (it.category === 'STRENGTH') {
        if (it.targetSets == null || it.targetSets < 1) push(`${base}.target_sets`, 'MUST_BE_POSITIVE', '力量动作必须填组数（≥1）')
        if (it.targetDurationSeconds != null) push(`${base}.target_duration_seconds`, 'STRENGTH_DURATION_FORBIDDEN', '力量动作不能设时长')
        if (it.targetExternalLoadKg != null) {
          if (it.targetExternalLoadKg < 0) push(`${base}.target_external_load_kg`, 'NEGATIVE_LOAD', '负重不能为负')
          else if (!isDecimalWithin(it.targetExternalLoadKg, 2)) push(`${base}.target_external_load_kg`, 'TOO_MANY_DECIMALS', '负重最多 2 位小数')
        }
      } else if (it.category === 'CARDIO') {
        if (it.targetDurationSeconds == null || it.targetDurationSeconds < 1) push(`${base}.target_duration_seconds`, 'MUST_BE_POSITIVE', '有氧动作必须填时长（秒，≥1）')
        if (it.targetSets != null) push(`${base}.target_sets`, 'CARDIO_SETS_FORBIDDEN', '有氧动作不能设组数')
        if (it.repsMin != null || it.repsMax != null) push(`${base}.reps_min`, 'CARDIO_REPS_FORBIDDEN', '有氧动作不能设次数')
        if (it.targetExternalLoadKg != null) push(`${base}.target_external_load_kg`, 'CARDIO_LOAD_FORBIDDEN', '有氧动作不能设负重')
        if (it.restSeconds != null) push(`${base}.rest_seconds`, 'CARDIO_REST_FORBIDDEN', '有氧动作无组间休息')
      } else if (it.category === 'MOBILITY') {
        if (it.targetExternalLoadKg != null) push(`${base}.target_external_load_kg`, 'MOBILITY_LOAD_FORBIDDEN', '柔韧动作不能设负重')
        if (it.targetSets != null && it.targetSets < 1) push(`${base}.target_sets`, 'MUST_BE_POSITIVE', '组数需 ≥1')
        if (it.targetDurationSeconds != null && it.targetDurationSeconds < 1) push(`${base}.target_duration_seconds`, 'MUST_BE_POSITIVE', '时长需 ≥1')
        const hasTarget = it.targetSets != null || it.repsMin != null || it.repsMax != null || it.targetDurationSeconds != null
        if (!hasTarget) push(base, 'MOBILITY_NO_TARGET', '柔韧动作至少给组数/次数/时长之一')
      }
    })
  })

  weekdaySeen.forEach((count, w) => {
    if (count > 1) push('days', 'DUPLICATE_WEEKDAY', `${weekdayLabel(w)}重复安排了 ${count} 日`)
  })
  if (totalItems > IMPORT_LIMITS.maxItemsTotal) push('days', 'TOO_MANY_ITEMS_TOTAL', `全计划动作总数不超过 ${IMPORT_LIMITS.maxItemsTotal}`)

  return out
}

function validateReps(it: DraftItem, base: string, push: (p: string, c: string, m: string) => void): void {
  if (it.category === 'CARDIO') return
  if (it.repsMin != null && it.repsMin < 1) push(`${base}.reps_min`, 'MUST_BE_POSITIVE', '次数下限需 ≥1')
  if (it.repsMax != null && it.repsMax < 1) push(`${base}.reps_max`, 'MUST_BE_POSITIVE', '次数上限需 ≥1')
  if (it.repsMin != null && it.repsMax != null && it.repsMax < it.repsMin) push(`${base}.reps_max`, 'REPS_MAX_LT_MIN', '次数上限不能小于下限')
}

function validateRest(it: DraftItem, base: string, push: (p: string, c: string, m: string) => void): void {
  if (it.category === 'CARDIO') return
  if (it.restSeconds != null && (it.restSeconds < 0 || it.restSeconds > IMPORT_LIMITS.maxRestSeconds)) {
    push(`${base}.rest_seconds`, 'REST_OUT_OF_RANGE', `组间休息需 0–${IMPORT_LIMITS.maxRestSeconds} 秒`)
  }
}

/** 把内部 path 转成人话定位（星期 / 第几项）。 */
function describeIssue(iss: RawIssue): { where: string; message: string; code: string } {
  const dm = /^days\[(\d+)\]/.exec(iss.path)
  const im = /\.items\[(\d+)\]/.exec(iss.path)
  let where = '计划'
  if (dm) {
    const di = Number(dm[1])
    const day = form.days[di]
    where = day ? weekdayLabel(day.isoWeekday) : `第 ${di + 1} 日`
    if (im && day) {
      const ii = Number(im[1])
      const it = day.items[ii]
      where += ` · 第 ${ii + 1} 项 ${it ? it.exerciseName || it.exerciseCode : ''}`.trimEnd()
    }
  } else if (iss.path === 'days') {
    where = '计划日整体'
  } else if (iss.path === 'title') {
    where = '计划标题'
  } else if (iss.path === 'starts_on') {
    where = '开始日期'
  } else if (iss.path === 'ends_on') {
    where = '结束日期'
  } else if (iss.path === 'notes') {
    where = '计划备注'
  }
  return { where, message: iss.message, code: iss.code }
}

function buildInput(): PlanAggregateInput {
  const days: PlanDayInput[] = form.days
    .slice()
    .sort((a, b) => a.isoWeekday - b.isoWeekday)
    .map((day) => {
      const items: PlanItemInput[] = day.isRestDay
        ? []
        : day.items.map((it) => ({
            positionNo: it.positionNo,
            exerciseCode: it.exerciseCode.trim(),
            targetSets: showSets(it.category) ? it.targetSets : null,
            repsMin: showReps(it.category) ? it.repsMin : null,
            repsMax: showReps(it.category) ? it.repsMax : null,
            targetExternalLoadKg: showLoad(it.category) ? it.targetExternalLoadKg : null,
            targetDurationSeconds: showDuration(it.category) ? it.targetDurationSeconds : null,
            restSeconds: showRest(it.category) ? it.restSeconds : null,
            notes: norm(it.notes)
          }))
      return {
        isoWeekday: day.isoWeekday,
        title: norm(day.title),
        isRestDay: day.isRestDay,
        notes: norm(day.notes),
        items
      }
    })
  return {
    title: (form.title ?? '').trim(),
    startsOn: form.startsOn,
    endsOn: form.endsOn || null,
    days
  }
}

async function submit(): Promise<void> {
  if (submitting.value) return
  const raw = validate()
  issues.value = raw.map(describeIssue)
  if (raw.length > 0) {
    revealFirstIssueDay(raw)
    uni.pageScrollTo({ scrollTop: 0, duration: 200 })
    return
  }
  submitting.value = true
  submitFailure.value = null
  try {
    const input = buildInput()
    const saved =
      mode.value === 'new'
        ? await createPlan(input)
        : await updatePlan(planId.value, { ...input, lockVersion: lockVersion.value })
    uni.showToast({ title: '已保存草稿', icon: 'success' })
    uni.redirectTo({ url: `/pages/plan/detail?id=${encodeURIComponent(saved.id)}` })
  } catch (err) {
    const view = toFailureView(err)
    submitFailure.value = view
    if (view.versionConflict || view.invalidTransition) {
      // 乐观锁过期或状态已变：必须先问，不能直接 loadPlan 覆盖用户这次已经填进去的内容
      askReloadAfterConflict()
    }
  } finally {
    submitting.value = false
  }
}

/**
 * 版本冲突后的选择权交给用户：确认才重读（丢弃当前编辑），取消就留在原地继续改。
 * 直接 loadPlan 会把用户刚填的动作、组数静默换成服务端旧内容。
 */
function askReloadAfterConflict(): void {
  uni.showModal({
    title: '版本已变化',
    content: '服务端这份计划已被更新（或状态已变）。是否放弃当前编辑内容并重新读取？取消则保留你现在填的内容，但本次保存没有写进服务端。',
    confirmText: '放弃并重读',
    cancelText: '保留我的编辑',
    success: (res) => {
      if (res.confirm) void loadPlan()
    }
  })
}

// ---- 载入 ----
function initBlank(): void {
  mode.value = 'new'
  form.title = ''
  form.startsOn = todayDateValue()
  form.endsOn = null
  form.days = []
  addWeekday.value = 1
}

function initFromPlan(p: TrainingPlan): void {
  form.title = p.title
  form.startsOn = p.startsOn
  form.endsOn = p.endsOn ?? null
  form.days = p.days
    .slice()
    .sort((a, b) => a.isoWeekday - b.isoWeekday)
    .map((d) => ({
      key: mkKey(),
      isoWeekday: d.isoWeekday,
      title: d.title,
      isRestDay: d.isRestDay,
      notes: d.notes,
      items: d.items
        .slice()
        .sort((a, b) => a.positionNo - b.positionNo)
        .map((it, idx) => ({
          key: mkKey(),
          positionNo: idx + 1,
          exerciseCode: it.exerciseCode,
          exerciseName: it.exerciseName,
          category: it.category,
          targetSets: it.targetSets,
          repsMin: it.repsMin,
          repsMax: it.repsMax,
          targetExternalLoadKg: it.targetExternalLoadKg,
          targetDurationSeconds: it.targetDurationSeconds,
          restSeconds: it.restSeconds,
          notes: it.notes
        }))
    }))
}

async function loadPlan(): Promise<void> {
  if (!planId.value) {
    initBlank()
    return
  }
  mode.value = 'loading'
  loading.value = true
  failure.value = null
  try {
    const p = await fetchPlan(planId.value)
    loadedPlan.value = p
    lockVersion.value = p.lockVersion
    if (p.status === 'DRAFT') {
      initFromPlan(p)
      mode.value = 'draft'
    } else {
      // ACTIVE/ARCHIVED/DISCARDED：不能就地编辑，只提供另存为新草稿
      mode.value = 'blocked'
    }
  } catch (err) {
    failure.value = toFailureView(err)
    if (isSessionExpiredError(err)) mode.value = 'loading'
    else mode.value = 'draft' // 让 StateBlock 走 error 分支
  } finally {
    loading.value = false
  }
}

function reviseToDraft(): void {
  if (!loadedPlan.value || revising.value) return
  const p = loadedPlan.value
  uni.showModal({
    title: '另存为新草稿',
    content: `基于第 ${p.revisionNo} 版生成同组新草稿（不改动当前版本）。确认？`,
    success: (res) => {
      if (!res.confirm) return
      void doRevise(p.id)
    }
  })
}

async function doRevise(id: string): Promise<void> {
  failure.value = null
  revising.value = true
  try {
    const draft = await revisePlan(id)
    uni.redirectTo({ url: `/pages/plan/edit?id=${encodeURIComponent(draft.id)}` })
  } catch (err) {
    failure.value = toFailureView(err)
  } finally {
    revising.value = false
  }
}

function backToDetail(): void {
  if (!loadedPlan.value) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: `/pages/plan/detail?id=${encodeURIComponent(loadedPlan.value.id)}` })
}

onLoad((query) => {
  planId.value = (query && query.id ? String(query.id) : '').trim()
  void loadPlan()
})
</script>

<style lang="scss" scoped>
/* ---------- 折叠区头（填写口径 / 动作卡）：整行可点，点击区不低于 $fa-tap ---------- */
.pe-disc {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: $fa-space-tight;
  min-height: $fa-tap;
}

.pe-disc--hover {
  background-color: $fa-highlight;
}

.pe-disc__text {
  flex: 1;
  min-width: 0;
}

.pe-disc__tail {
  display: flex;
  align-items: center;
  gap: 8rpx;
  flex: 0 0 auto;
  min-height: $fa-tap;
  font-size: $fa-font-ui;
}

.pe-hints {
  margin-top: $fa-space-tight;
  padding-top: $fa-space-tight;
  border-top: 1px solid $fa-line;
}

.pe-hints__line {
  display: block;
  line-height: 1.6;
  font-size: $fa-font-prose;
  color: $fa-text-sub;
}

/* ---------- 添加计划日 ---------- */
.pe-addrow {
  display: flex;
  flex-direction: column;
  gap: $fa-space-tight;
  margin-top: $fa-space-section;
  padding-top: $fa-space-tight;
  border-top: 1px solid $fa-line;
}

.pe-addrow__t {
  margin: 0;
}

.pe-sub {
  display: block;
}

.pe-addbtn {
  align-self: stretch;
}

/* ---------- 当前展开的一天 ---------- */
.pe-day__head {
  display: flex;
  align-items: center;
  gap: $fa-space-tight;
  margin-bottom: $fa-space-tight;
}

.pe-day__title {
  flex: 1;
  min-width: 0;
}

.pe-day__week {
  display: block;
  font-size: $fa-font-title;
  font-weight: 600;
  color: $fa-accent; /* 强调文字用深金，淡黄做前景不达标 */
}

.pe-day__count {
  display: block;
  margin-top: 4rpx;
}

.pe-day__chip {
  margin: 0;
  flex: 0 0 auto;
}

/* 开关与它的后果说明并排：切到休息日会清空动作，这句话必须出现在开关旁边 */
.pe-day__rest {
  display: flex;
  align-items: center;
  gap: $fa-space-tight;
  min-height: $fa-tap;
  padding: 20rpx 0;
  border-bottom: 1px solid $fa-line;
}

.pe-day__rest-text {
  flex: 1;
  min-width: 0;
}

.pe-day__rest-label {
  display: block;
  font-size: $fa-font-ui-lg;
  color: $fa-text;
  font-weight: 600;
  margin-right: 0;
}

.pe-rest {
  display: block;
  padding: $fa-space-tight 0;
}

/* ---------- 动作折叠卡 ---------- */
.pe-items {
  margin-top: $fa-space-tight;
}

.pe-item {
  border: 1px solid $fa-control-border;
  border-radius: $fa-radius-sm;
  padding: 0 $fa-space-card;
  margin-bottom: $fa-space-tight;
  background-color: $fa-page-bg;
}

.pe-item__head {
  display: flex;
  align-items: center;
  gap: 20rpx;
  min-height: 108rpx;
  padding: $fa-space-tight 0;
}

.pe-item__head--hover {
  background-color: $fa-highlight;
}

.pe-item__pos {
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  color: $fa-text-mute;
  flex: 0 0 auto;
}

.pe-item__pick {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

/* 动作名是业务内容：允许换行完整显示，不做省略号截断 */
.pe-item__name {
  font-size: $fa-font-ui-lg;
  line-height: 1.4;
}

.pe-item__target {
  font-size: $fa-font-ui;
  line-height: 1.5;
}

.pe-item__chev {
  flex: 0 0 auto;
  transition: transform 0.15s ease;
}

.pe-item__chev--open {
  transform: rotate(90deg);
}

.pe-item__body {
  padding: $fa-space-tight 0 $fa-space-card;
  border-top: 1px solid $fa-line;
}

.pe-item__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $fa-space-tight;
  padding-top: $fa-space-tight;
}

.pe-item__code {
  flex: 1;
  min-width: 0;
  font-size: $fa-font-ui;
  word-break: break-all;
}

.pe-item__ops {
  display: flex;
  flex-wrap: wrap;
  gap: $fa-space-tight;
  margin: $fa-space-tight 0;
}

.pe-op {
  min-height: $fa-tap;
  padding: 0 24rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid $fa-control-border;
  border-radius: $fa-radius-sm;
  font-size: $fa-font-ui;
  background-color: $fa-surface;
  color: $fa-text;
}

.pe-op--pick {
  flex: 0 0 auto;
  background-color: $fa-highlight;
  color: $fa-accent;
}

.pe-op--off {
  opacity: 0.4;
}

.pe-op--del {
  color: $fa-danger;
  border-color: $fa-danger;
}

.pe-grid {
  margin: $fa-space-tight 0;
}

.pe-grid--2 {
  display: flex;
  gap: $fa-space-grid;
}

/* WXSS 不接受「子选择器 + 通配星号」，DevTools 会直接报编译错误；两列布局改用显式 cell 类 */
.pe-grid--2 .pe-grid__cell {
  flex: 1;
  min-width: 0;
}

.pe-additem {
  margin-top: $fa-space-tight;
}

.pe-emptyday {
  margin: $fa-space-tight 0;
}

/* ---------- 其余日的一行摘要 ---------- */
.pe-fold__row {
  display: flex;
  align-items: center;
  gap: 20rpx;
  min-height: 108rpx;
  padding: $fa-space-tight 0;
  border-top: 1px solid $fa-line;
}

.pe-fold__row--hover {
  background-color: $fa-highlight;
}

.pe-fold__text {
  flex: 1;
  min-width: 0;
}

.pe-fold__week {
  display: block;
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  color: $fa-accent;
}

.pe-fold__sum {
  display: block;
  margin-top: 4rpx;
  line-height: 1.5;
}

.pe-fold__chip {
  margin: 0;
  flex: 0 0 auto;
}

/* ---------- 校验问题清单：错误详情属于成段阅读文本，走 19px ---------- */
.pe-issues {
  background-color: $fa-danger-weak;
  border: 1px solid $fa-danger;
  border-radius: $fa-radius;
  padding: $fa-space-card;
  margin-bottom: $fa-space-section;
}

.pe-issues__t {
  color: $fa-danger;
  margin: 0 0 $fa-space-tight;
}

.pe-issue {
  padding: $fa-space-tight 0;
  border-bottom: 1px solid $fa-danger;
}

.pe-issue:last-child {
  border-bottom: none;
}

.pe-issue__where {
  display: block;
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  color: $fa-danger;
}

.pe-issue__msg {
  display: block;
  font-size: $fa-font-prose;
  line-height: 1.6;
  color: $fa-text;
  margin-top: 8rpx;
}

.pe-issue__code {
  display: block;
  font-size: $fa-font-ui;
  color: $fa-text-sub;
  margin-top: 8rpx;
  word-break: break-all;
}

.pe-savetip {
  display: block;
  margin-bottom: $fa-space-section;
}

/* ---------- 底部条里的「更多操作」：不参与 flex:1，把宽度留给唯一的主操作 ---------- */
.pe-bottom-more {
  flex: 0 0 auto;
}

.pe-bottom-more--hover .pe-bottom-more__btn {
  background-color: $fa-highlight;
}

.pe-bottom-more__btn {
  min-width: 200rpx;
}
</style>
