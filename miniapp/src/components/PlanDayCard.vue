<template>
  <view class="pdc">
    <!-- 计划日抬头：星期 + 标题 + 休息日标记 -->
    <view class="pdc__head">
      <text class="pdc__weekday">{{ weekdayLabel(day.isoWeekday) }}</text>
      <text v-if="dayTitleText" class="pdc__title">{{ dayTitleText }}</text>
      <text v-if="day.isRestDay" class="fa-chip fa-chip--primary pdc__restchip">休息日</text>
    </view>

    <!-- 休息日形态：必须为「不安排动作」，展示文案与备注 -->
    <template v-if="day.isRestDay">
      <view class="pdc__rest-row">
        <FaIcon name="moon" tone="accent" size="44rpx" />
        <text class="fa-prose pdc__rest">休息日 · 本日不安排训练动作</text>
      </view>
      <text v-if="day.notes" class="pdc__note fa-prose">当日备注：{{ day.notes }}</text>
    </template>

    <!-- 训练日形态：按顺序展示动作条目 -->
    <template v-else>
      <text v-if="items.length === 0" class="fa-muted pdc__empty">本日暂无动作</text>

      <view v-for="(it, idx) in items" :key="`pdc-it-${idx}`" class="pdc__item">
        <view class="pdc__item-head">
          <text class="pdc__pos">{{ positionOf(it, idx) }}</text>
          <text class="fa-strong pdc__name">{{ nameOf(it) }}</text>
          <text v-if="it.exerciseCode" class="fa-muted pdc__code">{{ it.exerciseCode }}</text>
          <text v-if="it.category" class="fa-chip pdc__cat">{{ categoryLabel(it.category) }}</text>
        </view>

        <view class="pdc__metrics">
          <view v-if="it.targetSets != null" class="pdc__metric">
            <text class="pdc__k">组数</text>
            <text class="pdc__v">{{ it.targetSets }} 组</text>
          </view>
          <view v-if="repsText(it)" class="pdc__metric">
            <text class="pdc__k">次数</text>
            <text class="pdc__v">{{ repsText(it) }}</text>
          </view>
          <view v-if="it.targetExternalLoadKg != null" class="pdc__metric">
            <text class="pdc__k">负重</text>
            <text class="pdc__v">{{ loadText(it) }}</text>
          </view>
          <view v-if="it.targetDurationSeconds != null" class="pdc__metric">
            <text class="pdc__k">时长</text>
            <text class="pdc__v">{{ secondsToHuman(it.targetDurationSeconds) }}</text>
          </view>
          <view v-if="it.restSeconds != null" class="pdc__metric">
            <text class="pdc__k">组间休息</text>
            <text class="pdc__v">{{ secondsToHuman(it.restSeconds) }}</text>
          </view>
          <!-- 一条目标都没有时不能整块空白：那是"未设定"，不是"没有这个动作" -->
          <view v-if="!hasAnyTarget(it)" class="pdc__metric">
            <text class="pdc__k">目标</text>
            <text class="pdc__v">未设定组数/次数/时长</text>
          </view>
        </view>

        <text v-if="it.notes" class="pdc__note fa-prose">动作备注：{{ it.notes }}</text>
      </view>

      <text v-if="day.notes" class="pdc__note fa-prose">当日备注：{{ day.notes }}</text>
    </template>
  </view>
</template>

<script setup lang="ts">
/**
 * 单个计划日的只读渲染卡：抬头（星期/标题/休息日标记）、休息日形态、训练日的有序动作条目。
 * 供 `plan/detail`、`plan/edit` 预览区与首页「今日计划」复用。
 *
 * 输入既接受读取态 `PlanDay`（`exerciseName` 恒有值），也接受导入 preview 的规范化条目
 * （`exerciseName` 可能为 null、`category` 为字符串）——用宽松的结构类型承接，多余字段忽略即可。
 *
 * 展示口径（docs/p1-plan-import.md §6）：时长/组间休息一律「秒」，用 `secondsToHuman` 仅作可读展示；
 * 负重单位固定 kg，`0` 有意义（自重），与「未设定 null」区分：null 不渲染该指标行。
 */
import { computed } from 'vue'
import FaIcon from '@/components/FaIcon.vue'
import { weekdayLabel } from '@/types/training'
import { categoryLabel } from '@/types/exercise'
import { secondsToHuman, formatDecimal } from '@/utils/units'

interface CardItem {
  positionNo?: number | null
  exerciseCode?: string | null
  exerciseName?: string | null
  category?: string | null
  targetSets?: number | null
  repsMin?: number | null
  repsMax?: number | null
  targetExternalLoadKg?: number | null
  targetDurationSeconds?: number | null
  restSeconds?: number | null
  notes?: string | null
}

interface CardDay {
  isoWeekday?: number | null
  title?: string | null
  isRestDay?: boolean
  notes?: string | null
  items?: CardItem[]
}

const props = defineProps<{ day: CardDay }>()

const items = computed<CardItem[]>(() => (props.day.items ?? []).slice())

const dayTitleText = computed(() => {
  const t = props.day.title
  return typeof t === 'string' && t.trim().length > 0 ? t.trim() : ''
})

function positionOf(it: CardItem, idx: number): string {
  const p = it.positionNo
  return `#${p != null && Number.isFinite(p) ? p : idx + 1}`
}

/** 该条目是否给出过任何一种目标值（组数/次数/负重/时长/组间休息）。 */
function hasAnyTarget(it: CardItem): boolean {
  return it.targetSets != null || !!repsText(it) || it.targetExternalLoadKg != null ||
    it.targetDurationSeconds != null || it.restSeconds != null
}

function nameOf(it: CardItem): string {
  const n = it.exerciseName
  if (typeof n === 'string' && n.trim().length > 0) return n.trim()
  return it.exerciseCode ? `（未解析：${it.exerciseCode}）` : '未命名动作'
}

function repsText(it: CardItem): string {
  const min = it.repsMin
  const max = it.repsMax
  if (min == null && max == null) return ''
  if (min != null && max != null) return min === max ? `${min} 次` : `${min}–${max} 次`
  if (min != null) return `≥${min} 次`
  return `≤${max} 次`
}

function loadText(it: CardItem): string {
  // 0 合法（自重），必须与 null（未设定）区分，故只在 null 时不渲染
  return formatDecimal(it.targetExternalLoadKg ?? null, 'kg')
}
</script>

<style lang="scss" scoped>
.pdc {
  /* 长动作名与长代码在窄屏必须换行，不能把整行撑出卡片 */
  word-break: break-all;
  background-color: $fa-surface;
  border: 1px solid $fa-line;
  border-radius: $fa-radius;
  padding: $fa-space-card;
  margin-bottom: $fa-space-section;
}

.pdc__rest-row {
  display: flex;
  align-items: center;
  gap: $fa-space-tight;
}

.pdc__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: $fa-space-tight;
  margin-bottom: $fa-space-tight;
}

.pdc__weekday {
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  color: $fa-accent; /* 强调文字用深金，淡黄做前景不达标 */
}

.pdc__title {
  font-size: $fa-font-ui-lg;
  color: $fa-text;
  flex: 1;
}

.pdc__restchip {
  margin: 0;
}

.pdc__rest {
  display: block;
  color: $fa-text-sub;
}

.pdc__empty {
  display: block;
  padding: 4px 0;
}

.pdc__item {
  border-top: 1px solid $fa-line;
  padding: 8px 0;
}

.pdc__item-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.pdc__pos {
  font-size: $fa-font-ui;
  color: $fa-text-mute;
  font-weight: 600;
}

.pdc__name {
  font-size: $fa-font-ui-lg;
}

.pdc__code {
  font-size: $fa-font-ui;
}

.pdc__cat {
  margin: 0;
}

.pdc__metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  margin-top: 6px;
}

.pdc__metric {
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.pdc__k {
  font-size: $fa-font-ui;
  color: $fa-text-mute;
}

.pdc__v {
  font-size: $fa-font-ui;
  color: $fa-text;
  font-weight: 600;
}

.pdc__note {
  display: block;
  margin-top: 4px;
  color: $fa-text-sub;
}
</style>
