<template>
  <view class="fa-field">
    <view class="fa-field__label" :class="{ 'fa-field__label--req': required }">
      <text>{{ label }}</text>
    </view>
    <view class="date-field__row">
      <picker class="date-field__picker" mode="date" :value="pickerValue" :start="start" :end="end" @change="onChange">
        <view class="fa-input date-field__value" :class="{ 'date-field__value--empty': !modelValue }">
          {{ modelValue || placeholder }}
        </view>
      </picker>
      <view v-if="clearable && modelValue" class="fa-btn date-field__clear" @tap="clear">清除</view>
    </view>
    <text v-if="hint" class="fa-hint">{{ hint }}</text>
    <text v-if="error" class="date-field__error">{{ error }}</text>
  </view>
</template>

<script setup lang="ts">
/**
 * `YYYY-MM-DD` 日期选择（契约 §1「时间」：业务日期只有 `YYYY-MM-DD` 一种形态，不接受时间戳/带 Z 的值）。
 *
 * 只做「选值 + 回传字符串」，不猜任何业务含义：
 *   - `end`（不晚于今天）由页面按契约条款显式传入，例如出生日期对应后端 `@PastOrPresent`；
 *   - 可选字段用 `clearable` 显式清空成 `''`，页面再把 `''` 翻成 `null` 提交（「省略即清空」是
 *     后端 PUT 的全量替换语义，见 `UserProfileService.applyWritableFields`）。
 * 命中三处复用：档案页（出生日期）、目标页（起止日期）、体重页（测量日期）。
 */
import { computed } from 'vue'
import { detailValue } from '@/utils/events'
import { todayDateValue } from '@/utils/date'

const props = withDefaults(
  defineProps<{
    modelValue: string
    label: string
    placeholder?: string
    hint?: string
    error?: string
    required?: boolean
    clearable?: boolean
    /** 可选下界 `YYYY-MM-DD`，空串表示不限制 */
    start?: string
    /** 可选上界 `YYYY-MM-DD`，空串表示不限制 */
    end?: string
  }>(),
  {
    placeholder: '未选择',
    hint: '',
    error: '',
    required: false,
    clearable: true,
    start: '',
    end: ''
  }
)

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()

/** picker 打开时定位到的日期：没选过就定位到今天，避免默认落在 1970。 */
const pickerValue = computed(() => props.modelValue || todayDateValue())

function onChange(evt: Event): void {
  const picked = detailValue(evt)
  const raw = Array.isArray(picked) ? picked[0] : picked
  emit('update:modelValue', String(raw ?? ''))
}

function clear(): void {
  emit('update:modelValue', '')
}
</script>

<style lang="scss" scoped>
.date-field__row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.date-field__picker {
  flex: 1;
}

.date-field__value {
  min-height: $fa-tap;
  display: flex;
  align-items: center;
}

.date-field__value--empty {
  color: $fa-text-mute;
}

.date-field__clear {
  flex: 0 0 auto;
  min-width: 64px;
  min-height: $fa-tap;
  font-size: $fa-font-ui;
  color: $fa-text-sub;
}

.date-field__error {
  display: block;
  margin-top: 4px;
  font-size: $fa-font-ui;
  color: $fa-danger;
}
</style>
