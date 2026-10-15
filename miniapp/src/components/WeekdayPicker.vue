<template>
  <view class="weekday-picker">
    <view
      v-for="w in WEEKDAYS"
      :key="w"
      class="weekday-picker__cell"
      :class="{
        'weekday-picker__cell--on': modelValue === w,
        'weekday-picker__cell--used': isUsed(w)
      }"
      @tap="pick(w)"
    >
      <text>{{ weekdayLabel(w) }}</text>
      <text v-if="isUsed(w)" class="weekday-picker__tag">已排</text>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 星期单选器：ISO 1–7（1=周一）。
 * 同一计划内 `iso_weekday` 必须唯一（docs/p1-plan-import.md §3；DB `UNIQUE(plan_id, iso_weekday)`），
 * 所以已被占用的星期在这里标出并阻止再选。
 */
import { weekdayLabel } from '@/types/training'

const props = withDefaults(
  defineProps<{
    modelValue: number
    /** 已被占用的星期：仍可见，但选它会给出提示而不是静默失败 */
    used?: number[]
  }>(),
  { used: () => [] }
)

const emit = defineEmits<{ (e: 'update:modelValue', value: number): void; (e: 'blocked', value: number): void }>()

const WEEKDAYS = [1, 2, 3, 4, 5, 6, 7]

function isUsed(w: number): boolean {
  return props.used.includes(w) && props.modelValue !== w
}

function pick(w: number): void {
  if (isUsed(w)) {
    emit('blocked', w)
    return
  }
  emit('update:modelValue', w)
}
</script>

<style lang="scss" scoped>
.weekday-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

.weekday-picker__cell {
  flex: 1 1 22%;
  min-height: $fa-tap;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border: 1px solid $fa-control-border;
  border-radius: $fa-radius-sm;
  background-color: $fa-surface;
  font-size: $fa-font-ui;
  color: $fa-text;
  padding: 2px 0;
}

.weekday-picker__cell--on {
  /* 选中态：淡黄填充 + 深色文字 + 描边，三个线索一起，不靠单一颜色表达"已选" */
  background-color: $fa-primary;
  border-color: $fa-control-border;
  color: $fa-text;
  font-weight: 600;
}

.weekday-picker__cell--used {
  opacity: 0.45;
}

.weekday-picker__tag {
  font-size: $fa-font-ui;
  color: inherit;
}
</style>
