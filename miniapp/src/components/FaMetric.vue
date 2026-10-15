<template>
  <view class="fa-metric">
    <text class="fa-metric__label">{{ label }}</text>
    <view class="fa-metric__row">
      <text class="fa-metric__value" :class="{ 'fa-metric__value--unknown': isUnknown }">{{ shown }}</text>
      <text v-if="!isUnknown && unit" class="fa-unit">{{ unit }}</text>
    </view>
    <text v-if="caption" class="fa-metric__caption">{{ caption }}</text>
    <slot />
  </view>
</template>

<script setup lang="ts">
/**
 * 关键数字 + 单位 + 短标题，替代"当前体重是 68.5 公斤"这类整句。
 * `value` 为 null/undefined 时显示 unknownText，**不显示 0**——契约里"没有测量"与"测得 0"是两回事。
 */
import { computed } from 'vue'
import { formatDecimal } from '@/utils/units'

const props = withDefaults(
  defineProps<{
    label: string
    value: number | string | null | undefined
    unit?: string
    caption?: string
    unknownText?: string
  }>(),
  { unit: '', caption: '', unknownText: '未设定' }
)

const isUnknown = computed(
  () => props.value === null || props.value === undefined || props.value === ''
)
/* formatDecimal 对非数值返回 '—'，与"未设定"区分开：前者是拿到 0 长度数据，后者是根本没有值 */
const shown = computed(() =>
  isUnknown.value ? props.unknownText : typeof props.value === 'number' ? formatDecimal(props.value) : String(props.value)
)
</script>

<style lang="scss" scoped>
.fa-metric {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.fa-metric__label {
  font-size: $fa-font-ui;
  color: $fa-text-sub;
}

.fa-metric__row {
  display: flex;
  align-items: baseline;
}

.fa-metric__value {
  font-size: $fa-num-lg;
  font-weight: 600;
  line-height: 1.15;
  color: $fa-text;
}

.fa-metric__value--unknown {
  font-size: $fa-font-title;
  color: $fa-text-mute;
  font-weight: 400;
}

.fa-metric__caption {
  font-size: $fa-font-ui;
  color: $fa-text-mute;
}
</style>
