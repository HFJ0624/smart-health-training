<template>
  <view class="rsl">
    <text v-if="steps.length === 0" class="fa-muted rsl__empty">{{ emptyText }}</text>

    <view v-for="(step, idx) in orderedSteps" :key="stepKey(step, idx)" class="rsl__row">
      <text class="rsl__no">{{ stepNumber(step, idx) }}</text>
      <view class="rsl__body">
        <text class="fa-prose rsl__summary">{{ stepSummaryText(step) }}</text>
        <view class="rsl__tags">
          <text v-if="step.toolName" class="fa-chip rsl__chip">{{ toolLabel(step.toolName) }}</text>
          <text v-if="stepText(step.status)" class="fa-chip rsl__chip">{{ stepText(step.status) }}</text>
          <text v-if="step.errorCode" class="fa-chip fa-chip--danger rsl__chip">{{ step.errorCode }}</text>
          <text v-if="durationText(step) !== ''" class="fa-muted rsl__duration">{{ durationText(step) }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * run 操作摘要列表（docs/agent-v1-design.md §11 `GET /api/coach/runs/{id}/steps`）。
 *
 * 这里渲染的是服务端已经**脱敏**过的操作摘要（V21 `arguments_redacted` / `result_summary`），
 * 例如「查询当天计划」「读取体重记录」。模型的原始推理文本不会、也不应出现在这里；
 * 组件只做排序与原样展示，不改写文案、不推断步骤含义（未知取值直接显示机器码）。
 */
import { computed } from 'vue'
import { stepSummaryText, toolLabel, type RunStep } from '@/types/coach'

const props = withDefaults(
  defineProps<{
    steps: RunStep[]
    /** 没有步骤时给页面的解释文案（由调用方决定，组件不猜语义） */
    emptyText?: string
  }>(),
  { emptyText: '后端尚未返回操作摘要' }
)

/** 按 `stepNo` 升序；没有 stepNo 的行保持服务端顺序，不编号猜测。 */
const orderedSteps = computed<RunStep[]>(() => {
  if (props.steps.every((s) => typeof s.stepNo !== 'number')) return props.steps
  return props.steps
    .map((step, index) => ({ step, index }))
    .sort((a, b) => {
      const an = typeof a.step.stepNo === 'number' ? a.step.stepNo : Number.MAX_SAFE_INTEGER
      const bn = typeof b.step.stepNo === 'number' ? b.step.stepNo : Number.MAX_SAFE_INTEGER
      if (an !== bn) return an - bn
      return a.index - b.index
    })
    .map((row) => row.step)
})

function stepKey(step: RunStep, idx: number): string {
  return `${step.stepNo ?? 'x'}-${step.runId ?? ''}-${idx}`
}

function stepNumber(step: RunStep, idx: number): string {
  return typeof step.stepNo === 'number' ? `${step.stepNo}` : `${idx + 1}`
}

/** 状态/类型取值未进契约枚举：能认的给中文，认不出的原样显示。 */
function stepText(value: string | null | undefined): string {
  const raw = (value || '').trim()
  if (!raw) return ''
  const upper = raw.toUpperCase()
  if (upper === 'SUCCESS') return '成功'
  if (upper === 'FAILED') return '失败'
  if (upper === 'RUNNING') return '进行中'
  return raw
}

function durationText(step: RunStep): string {
  if (typeof step.durationMs !== 'number' || !Number.isFinite(step.durationMs) || step.durationMs < 0) return ''
  return `${(step.durationMs / 1000).toFixed(1)} 秒`
}
</script>

<style lang="scss" scoped>
.rsl__row {
  display: flex;
  gap: $fa-space-tight;
  padding: 12rpx 0;
  border-bottom: 1px solid $fa-line;
}

.rsl__row:last-child {
  border-bottom: none;
}

.rsl__no {
  flex: 0 0 auto;
  min-width: 22px;
  font-size: $fa-font-ui;
  color: $fa-text-mute;
}

.rsl__body {
  flex: 1;
}

.rsl__summary {
  display: block;
  color: $fa-text;
}

.rsl__tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  margin-top: 4px;
}

.rsl__chip {
  font-size: $fa-font-ui;
}

.rsl__duration {
  font-size: $fa-font-ui;
}

.rsl__empty {
  display: block;
  padding: 4px 0;
}
</style>
