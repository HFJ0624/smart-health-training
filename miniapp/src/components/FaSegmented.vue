<template>
  <view class="fa-segmented" role="tablist">
    <view
      v-for="opt in options"
      :key="opt.value"
      class="fa-segmented__item"
      :class="{ 'fa-segmented__item--on': modelValue === opt.value }"
      hover-class="fa-segmented__item--hover"
      :hover-stay-time="60"
      @tap="pick(opt.value)"
    >
      <FaIcon v-if="opt.icon" :name="opt.icon" :tone="modelValue === opt.value ? 'accent' : 'sub'" size="40rpx" />
      <text class="fa-segmented__label">{{ opt.label }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 少量互斥选项的分段控件（时间窗、动作分类）。与 ChipSelect 的区别：分段控件是**同一视图的口径切换**，
 * 不改变数据本身；ChipSelect 是表单取值。选中态除底色外还有描边与加粗，不靠颜色单独表达。
 */
import type { FaIconName } from '@/styles/icon-names'
import FaIcon from './FaIcon.vue'

const props = withDefaults(
  defineProps<{
    modelValue: string
    options: Array<{ value: string; label: string; icon?: FaIconName }>
    disabled?: boolean
  }>(),
  { disabled: false }
)

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()

function pick(value: string): void {
  if (props.disabled || props.modelValue === value) return
  emit('update:modelValue', value)
}
</script>

<style lang="scss" scoped>
.fa-segmented {
  display: flex;
  align-self: flex-start;
  padding: 6rpx;
  border-radius: $fa-radius-sm;
  border: 1px solid $fa-control-border;
  background-color: $fa-surface;
}

.fa-segmented__item {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
  min-height: 72rpx;
  padding: 0 28rpx;
  border-radius: 12rpx;
  border: 1px solid transparent;
}

.fa-segmented__item--on {
  background-color: $fa-primary;
  border-color: $fa-control-border;
}

.fa-segmented__item--hover {
  border-color: $fa-control-border;
}

.fa-segmented__label {
  font-size: $fa-font-ui;
  font-weight: 600;
  color: $fa-text;
}
</style>
