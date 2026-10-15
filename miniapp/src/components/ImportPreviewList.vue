<template>
  <view class="ipl">
    <!-- 顶部：错误 / 警告定位清单（errors 非空即不能确认；warnings 不阻断）。path 原样渲染，便于用户对照 -->
    <view v-if="errors.length > 0" class="ipl__section">
      <text class="fa-section-title">校验错误（{{ errors.length }}）· 点击可定位到对应条目</text>
      <view
        v-for="(e, i) in errors"
        :key="`err-${i}`"
        class="ipl__issue"
        :class="{ 'ipl__issue--active': activePath === e.path }"
        @tap="focusIssue(e.path)"
      >
        <text class="ipl__issue-path">{{ e.path || '（无 path）' }}</text>
        <text class="ipl__issue-msg">{{ e.message }}</text>
        <text class="ipl__issue-code">{{ e.code }}</text>
      </view>
    </view>

    <view v-if="warnings.length > 0" class="ipl__section">
      <text class="fa-section-title">提示 / 警告（{{ warnings.length }}）· 不阻断保存</text>
      <view
        v-for="(w, i) in warnings"
        :key="`warn-${i}`"
        class="ipl__issue ipl__issue--warn"
        :class="{ 'ipl__issue--active': activePath === w.path }"
        @tap="focusIssue(w.path)"
      >
        <text class="ipl__issue-path">{{ w.path || '（无 path）' }}</text>
        <text class="ipl__issue-msg">{{ w.message }}</text>
        <text class="ipl__issue-code">{{ w.code }}</text>
      </view>
    </view>

    <!-- 规范化预览：无 plan 说明结构层已失败，仅能看上面的错误清单 -->
    <view v-if="!view" class="ipl__noplan">
      <text class="fa-prose">结构校验未通过，后端未返回可预览的规范化计划；请先按上面的错误修正后重新校验。</text>
    </view>

    <view v-else class="ipl__section">
      <text class="fa-section-title">规范化预览</text>

      <view id="pvtop" class="ipl__top" :class="topCls">
        <view class="ipl__top-row">
          <text class="fa-row__label">标题</text>
          <text class="fa-row__value" :class="{ 'ipl__field--err': topHas('title') }">{{ view?.title || '—' }}</text>
        </view>
        <view class="ipl__top-row">
          <text class="fa-row__label">起止日期</text>
          <text class="fa-row__value" :class="{ 'ipl__field--err': topHas('starts_on') || topHas('ends_on') }">
            {{ view?.startsOn || '—' }} ~ {{ view?.endsOn || '不设结束' }}
          </text>
        </view>
        <view v-if="view?.notes" class="ipl__top-row">
          <text class="fa-row__label">备注</text>
          <text class="fa-row__value" :class="{ 'ipl__field--err': topHas('notes') }">{{ view?.notes }}</text>
        </view>
        <text v-if="topHas('schema_version')" class="ipl__field-err-tip">schema_version 有误：{{ pathMessages('schema_version') }}</text>
      </view>

      <scroll-view class="ipl__scroller" scroll-y :scroll-into-view="intoView" scroll-with-animation>
        <view
          v-for="d in daysList"
          :id="`pvday-${d.index}`"
          :key="`pvday-${d.index}`"
          class="ipl__day"
          :class="{ 'ipl__day--err': d.hasError, 'ipl__is-active': activeTargetsId(`pvday-${d.index}`) }"
        >
          <view class="ipl__day-head">
            <text class="ipl__day-week">{{ weekdayLabel(d.isoWeekday ?? 0) }}</text>
            <text v-if="d.title" class="ipl__day-title" :class="{ 'ipl__field--err': d.errFields.has('title') }">{{ d.title }}</text>
            <text v-if="d.isRestDay" class="fa-chip fa-chip--primary">休息日</text>
            <text v-if="d.errFields.has('iso_weekday')" class="ipl__day-tag ipl__day-tag--err">星期非法</text>
          </view>

          <text v-if="d.isRestDay" class="ipl__rest fa-prose">休息日 · 无动作</text>
          <text v-else-if="d.items.length === 0" class="ipl__noitems fa-muted">无条目（{{ pathMessages(`days[${d.index}].items`) || '空训练日' }}）</text>

          <view
            v-for="it in d.items"
            :id="`pvitem-${d.index}-${it.index}`"
            :key="`pvitem-${d.index}-${it.index}`"
            class="ipl__item"
            :class="{ 'ipl__item--err': it.hasError, 'ipl__is-active': activeTargetsId(`pvitem-${d.index}-${it.index}`) }"
          >
            <view class="ipl__item-head">
              <text class="ipl__item-pos" :class="{ 'ipl__field--err': it.err.has('position') }">#{{ it.positionNo ?? it.index + 1 }}</text>
              <text class="fa-strong ipl__item-name" :class="{ 'ipl__field--err': it.err.has('code') }">{{ it.name }}</text>
              <text v-if="it.code" class="fa-muted ipl__item-code" :class="{ 'ipl__field--err': it.err.has('code') }">{{ it.code }}</text>
              <text v-if="it.category" class="fa-chip ipl__item-cat">{{ categoryLabel(it.category) }}</text>
            </view>

            <view class="ipl__metrics">
              <view v-for="row in it.rows" :key="row.group" class="ipl__metric" :class="{ 'ipl__metric--err': it.err.has(row.group) }">
                <text class="ipl__metric-k">{{ row.label }}</text>
                <text class="ipl__metric-v">{{ row.text }}</text>
              </view>
              <text v-if="it.rows.length === 0" class="ipl__metric-empty">（无数值字段）</text>
            </view>

            <text v-if="it.notes || it.err.has('notes')" class="ipl__item-note fa-prose" :class="{ 'ipl__field--err': it.err.has('notes') }">
              备注：{{ it.notes || '（备注有误）' }}
            </text>
          </view>

          <text v-if="d.notes || d.errFields.has('notes')" class="ipl__day-note fa-prose" :class="{ 'ipl__field--err': d.errFields.has('notes') }">
            当日备注：{{ d.notes || '（当日备注有误）' }}
          </text>
          <text v-if="d.errFields.has('items')" class="ipl__day-tip">items：{{ pathMessages(`days[${d.index}].items`) }}</text>
          <text v-if="d.errFields.has('is_rest_day')" class="ipl__day-tip">is_rest_day：{{ pathMessages(`days[${d.index}].is_rest_day`) }}</text>
        </view>
      </scroll-view>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 导入预览 + 逐字段定位错误清单的渲染组件（docs/p1-plan-import.md §8「错误定位格式」）。
 *
 * - 上半部分是 errors / warnings：`path` 用 JSON 路径、数组带下标，与下方预览一一对应；
 *   点击一条会把对应的星期卡 / 动作条目滚动进入视野并高亮；无法定位时至少原样渲染 `path`。
 * - 下半部分是后端返回的「规范化预览」（`ImportPreviewResult.plan`）：星期 → 有序动作 → 组次/负重/时长/休息。
 * - `errors` 非空即「不能确认」（由父页面据此禁用按钮）；`warnings` 不阻断（docs/p1-plan-import.md §8）。
 * - `plan` 为 null（结构层短路失败）时，只渲染错误清单并说明无法预览。
 */
import { computed, nextTick, ref } from 'vue'
import type { ImportIssue, ImportNormalizedPlan } from '@/types/plan-import'
import { weekdayLabel } from '@/types/training'
import { categoryLabel } from '@/types/exercise'
import { secondsToHuman, formatDecimal, formatNumber } from '@/utils/units'

const props = withDefaults(
  defineProps<{
    plan: ImportNormalizedPlan | null
    errors: ImportIssue[]
    warnings: ImportIssue[]
  }>(),
  { plan: null, errors: () => [], warnings: () => [] }
)

/** item 字段 snake_case → 预览分组键。 */
const ITEM_FIELD_GROUP: Record<string, string> = {
  exercise_code: 'code',
  position_no: 'position',
  target_sets: 'sets',
  reps_min: 'reps',
  reps_max: 'reps',
  target_external_load_kg: 'load',
  target_duration_seconds: 'duration',
  rest_seconds: 'rest',
  notes: 'notes'
}

interface Parsed {
  level: 'top' | 'day' | 'item'
  dayIndex: number | null
  itemIndex: number | null
  field: string | null
}

function parsePath(path: string): Parsed {
  const m = /^days\[(\d+)\](?:\.items\[(\d+)\])?(?:\.([A-Za-z0-9_]+))?$/.exec(path)
  if (!m) return { level: 'top', dayIndex: null, itemIndex: null, field: path }
  const dayIndex = Number(m[1])
  const itemIndex = m[2] != null ? Number(m[2]) : null
  const field = m[3] ?? null
  if (itemIndex != null) return { level: 'item', dayIndex, itemIndex, field }
  return { level: 'day', dayIndex, itemIndex: null, field }
}

/** 每个 field 分组一行展示。 */
interface ItemRow {
  group: string
  label: string
  text: string
}

interface ViewItem {
  index: number
  positionNo: number | null
  name: string
  code: string
  category: string | null
  notes: string | null
  rows: ItemRow[]
  err: Set<string>
  hasError: boolean
}

interface ViewDay {
  index: number
  isoWeekday: number | null
  title: string | null
  isRestDay: boolean
  notes: string | null
  items: ViewItem[]
  errFields: Set<string>
  hasError: boolean
}

interface PlanView {
  title: string | null
  startsOn: string | null
  endsOn: string | null
  notes: string | null
  days: ViewDay[]
}

/** errors/warnings 按 path 聚合到分组，供高亮。 */
const errorGroups = computed(() => {
  const top = new Set<string>()
  const day = new Map<number, Set<string>>()
  const item = new Map<string, Set<string>>()
  const msgByPath = new Map<string, string>()

  const absorb = (issues: ImportIssue[], isErr: boolean) => {
    issues.forEach((iss) => {
      if (isErr || !msgByPath.has(iss.path)) {
        const prev = msgByPath.get(iss.path)
        msgByPath.set(iss.path, prev ? `${prev}；${iss.message}` : iss.message)
      }
      const p = parsePath(iss.path)
      if (p.level === 'top') {
        top.add(p.field ?? '')
        return
      }
      if (p.level === 'day') {
        const set = day.get(p.dayIndex as number) ?? new Set<string>()
        set.add(p.field ?? 'items')
        day.set(p.dayIndex as number, set)
        return
      }
      // item
      const key = `${p.dayIndex}:${p.itemIndex}`
      const set = item.get(key) ?? new Set<string>()
      const group = p.field ? ITEM_FIELD_GROUP[p.field] ?? null : null
      set.add(group ?? 'item')
      item.set(key, set)
      // 让所属星期也标出，方便滚动定位时看得见
      const dset = day.get(p.dayIndex as number) ?? new Set<string>()
      day.set(p.dayIndex as number, dset)
    })
  }

  absorb(props.errors, true)
  absorb(props.warnings, false)
  return { top, day, item, msgByPath }
})

const view = computed<PlanView | null>(() => {
  const plan = props.plan
  if (!plan) return null
  const days: ViewDay[] = (plan.days ?? []).map((raw, i) => {
    const errFields = errorGroups.value.day.get(i) ?? new Set<string>()
    const items: ViewItem[] = (raw.items ?? []).map((it, j) => {
      const err = errorGroups.value.item.get(`${i}:${j}`) ?? new Set<string>()
      const rows: ItemRow[] = []
      if (it.targetSets != null || err.has('sets')) rows.push({ group: 'sets', label: '组数', text: `${formatNumber(it.targetSets, '组')}` })
      const repsText = buildReps(it.repsMin ?? null, it.repsMax ?? null)
      if (repsText || err.has('reps')) rows.push({ group: 'reps', label: '次数', text: repsText || '—' })
      if (it.targetExternalLoadKg != null || err.has('load')) {
        rows.push({ group: 'load', label: '负重', text: it.targetExternalLoadKg != null ? formatDecimal(it.targetExternalLoadKg, 'kg') : '—' })
      }
      if (it.targetDurationSeconds != null || err.has('duration')) {
        rows.push({ group: 'duration', label: '时长', text: secondsToHuman(it.targetDurationSeconds) })
      }
      if (it.restSeconds != null || err.has('rest')) {
        rows.push({ group: 'rest', label: '组间休息', text: secondsToHuman(it.restSeconds) })
      }
      const name = it.exerciseName && it.exerciseName.trim().length > 0 ? it.exerciseName.trim() : it.exerciseCode ? `（未解析：${it.exerciseCode}）` : '未命名动作'
      return {
        index: j,
        positionNo: it.positionNo ?? null,
        name,
        code: it.exerciseCode ?? '',
        category: it.category ?? null,
        notes: it.notes ?? null,
        rows,
        err,
        hasError: err.size > 0
      }
    })
    return {
      index: i,
      isoWeekday: raw.isoWeekday ?? null,
      title: raw.title ?? null,
      isRestDay: raw.isRestDay === true,
      notes: raw.notes ?? null,
      items,
      errFields,
      hasError: errFields.size > 0 || items.some((x) => x.hasError)
    }
  })
  return {
    title: plan.title ?? null,
    startsOn: plan.startsOn ?? null,
    endsOn: plan.endsOn ?? null,
    notes: plan.notes ?? null,
    days
  }
})

function buildReps(min: number | null, max: number | null): string {
  if (min == null && max == null) return ''
  if (min != null && max != null) return min === max ? `${min} 次` : `${min}–${max} 次`
  if (min != null) return `≥${min} 次`
  return `≤${max} 次`
}

const daysList = computed<ViewDay[]>(() => view.value?.days ?? [])

function topHas(field: string): boolean {
  return errorGroups.value.top.has(field)
}

const topCls = computed(() => (errorGroups.value.top.size > 0 ? 'ipl__top--err' : ''))

function pathMessages(path: string): string {
  return errorGroups.value.msgByPath.get(path) ?? ''
}

// ---- 滚动定位 ----
const activePath = ref('')
const intoView = ref('')

function targetIdOf(path: string): string {
  const p = parsePath(path)
  if (p.level === 'item') return `pvitem-${p.dayIndex}-${p.itemIndex}`
  if (p.level === 'day') return `pvday-${p.dayIndex}`
  return 'pvtop'
}

function activeTargetsId(id: string): boolean {
  return activePath.value.length > 0 && targetIdOf(activePath.value) === id
}

function focusIssue(path: string): void {
  if (!path) return
  activePath.value = path
  const id = targetIdOf(path)
  intoView.value = ''
  nextTick(() => {
    intoView.value = id
  })
}
</script>

<style lang="scss" scoped>
.ipl__section {
  margin-bottom: 12px;
}

.ipl__issue {
  background-color: $fa-danger-weak;
  border: 1px solid $fa-danger;
  border-radius: 8px;
  padding: 8px 10px;
  margin-bottom: 6px;
}

.ipl__issue--warn {
  background-color: $fa-warning-weak;
  border-color: $fa-warning;
}

.ipl__issue--active {
  outline: 2px solid $fa-primary;
}

.ipl__issue-path {
  display: block;
  font-size: $fa-font-ui;
  font-weight: 600;
  color: $fa-danger;
  word-break: break-all;
}

.ipl__issue--warn .ipl__issue-path {
  color: $fa-warning;
}

.ipl__issue-msg {
  display: block;
  font-size: $fa-font-prose;
  line-height: 1.5;
  color: $fa-text;
  margin-top: 2px;
}

.ipl__issue-code {
  display: block;
  font-size: $fa-font-ui;
  color: $fa-text-mute;
  margin-top: 2px;
  word-break: break-all;
}

.ipl__noplan {
  background-color: $fa-card;
  border: 1px solid $fa-line;
  border-radius: 10px;
  padding: 12px;
}

.ipl__scroller {
  max-height: 60vh;
}

.ipl__top {
  background-color: $fa-card;
  border: 1px solid $fa-line;
  border-radius: 10px;
  padding: 10px;
  margin-bottom: 8px;
}

.ipl__top--err {
  border-color: $fa-danger;
}

.ipl__top-row {
  display: flex;
  justify-content: space-between;
  padding: 3px 0;
}

.ipl__field-err-tip {
  display: block;
  margin-top: 4px;
  font-size: $fa-font-ui;
  color: $fa-danger;
}

.ipl__field--err {
  color: $fa-danger;
  font-weight: 600;
}

.ipl__day {
  background-color: $fa-card;
  border: 1px solid $fa-line;
  border-radius: 10px;
  padding: 10px;
  margin-bottom: 8px;
}

.ipl__day--err {
  border-color: $fa-danger;
}

.ipl__is-active {
  outline: 2px solid $fa-primary;
}

.ipl__day-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 6px;
}

.ipl__day-week {
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  color: $fa-accent; /* 强调文字用深金，淡黄做前景不达标 */
}

.ipl__day-title {
  font-size: $fa-font-ui-lg;
  flex: 1;
}

.ipl__day-tag {
  font-size: $fa-font-ui;
}

.ipl__day-tag--err {
  color: $fa-danger;
}

.ipl__rest {
  color: $fa-text-sub;
}

.ipl__noitems {
  display: block;
  padding: 4px 0;
}

.ipl__item {
  border-top: 1px solid $fa-line;
  padding: 8px 0;
}

.ipl__item--err {
  background-color: $fa-danger-weak;
}

.ipl__item-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.ipl__item-pos {
  font-size: $fa-font-ui;
  color: $fa-text-mute;
  font-weight: 600;
}

.ipl__item-name {
  font-size: $fa-font-ui-lg;
}

.ipl__item-code {
  font-size: $fa-font-ui;
}

.ipl__item-cat {
  margin: 0;
}

.ipl__metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  margin-top: 6px;
}

.ipl__metric {
  display: flex;
  gap: 4px;
}

.ipl__metric--err {
  color: $fa-danger;
}

.ipl__metric-k {
  font-size: $fa-font-ui;
  color: $fa-text-mute;
}

.ipl__metric--err .ipl__metric-k,
.ipl__metric--err .ipl__metric-v {
  color: $fa-danger;
  font-weight: 600;
}

.ipl__metric-v {
  font-size: $fa-font-ui;
  color: $fa-text;
}

.ipl__metric-empty {
  font-size: $fa-font-ui;
  color: $fa-text-mute;
}

.ipl__item-note,
.ipl__day-note {
  display: block;
  margin-top: 4px;
  color: $fa-text-sub;
}

.ipl__day-tip {
  display: block;
  margin-top: 4px;
  font-size: $fa-font-ui;
  color: $fa-danger;
}
</style>
