<template>
  <view class="fa-strip">
    <view
      v-for="cell in cells"
      :key="cell.key"
      class="fa-strip__cell"
      :class="{ 'fa-strip__cell--on': cell.key === modelValue }"
      hover-class="fa-strip__cell--hover"
      :hover-stay-time="60"
      @tap="pick(cell.key)"
    >
      <text class="fa-strip__label">{{ cell.label }}</text>
      <!-- 图形承载"这天有没有安排"：点=有动作，横线=休息日，空=未安排。文字仍给短口径，不让人猜 -->
      <view class="fa-strip__mark" :class="`fa-strip__mark--${cell.mark || 'off'}`" />
      <text class="fa-strip__sub">{{ cell.sub || (cell.mark === 'rest' ? '休' : cell.mark === 'on' ? '有' : '—') }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 七日条选（提示词 §5「一次只看一天」）。组件不碰接口：页面把 `PlanDay[]` 映射成 cells，
 * 这样计划详情（只读）与计划编辑（可增删日）能共用同一控件而不各写一套。
 */
import type { StripCell } from '@/types/ui'

const props = withDefaults(
  defineProps<{
    modelValue: string
    cells: StripCell[]
    disabled?: boolean
  }>(),
  { disabled: false }
)

const emit = defineEmits<{ (e: 'update:modelValue', key: string): void }>()

function pick(key: string): void {
  if (props.disabled || props.modelValue === key) return
  emit('update:modelValue', key)
}
</script>

<style lang="scss" scoped>
.fa-strip {
  display: flex;
  gap: 10rpx;
  margin-bottom: $fa-space-tight;
}

.fa-strip__cell {
  flex: 1;
  min-width: 0;
  min-height: 128rpx;
  padding: 14rpx 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
  border-radius: $fa-radius-sm;
  border: 1px solid $fa-line;
  background-color: $fa-surface;
}

.fa-strip__cell--on {
  border-color: $fa-control-border;
  background-color: $fa-primary;
}

.fa-strip__cell--hover {
  border-color: $fa-control-border;
}

.fa-strip__label {
  font-size: $fa-font-ui;
  font-weight: 600;
  color: $fa-text;
}

.fa-strip__mark {
  width: 16rpx;
  height: 16rpx;
  border-radius: 50%;
}

.fa-strip__mark--on {
  background-color: $fa-accent;
}

/* 休息日用横线而不是点：形状不同，不依赖颜色也能区分 */
.fa-strip__mark--rest {
  width: 22rpx;
  height: 4rpx;
  border-radius: 2rpx;
  background-color: $fa-accent;
}

.fa-strip__mark--off {
  border: 1px solid $fa-control-border;
  background-color: transparent;
}

.fa-strip__sub {
  font-size: $fa-font-ui;
  color: $fa-text-sub;
}
</style>
