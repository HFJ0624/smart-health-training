<template>
  <view class="number-field">
    <view class="fa-field__label" :class="{ 'fa-field__label--req': required }">
      <text>{{ label }}</text>
      <text v-if="unit" class="number-field__unit">（{{ unit }}）</text>
    </view>
    <input
      class="fa-input"
      :value="textValue"
      :type="integerOnly ? 'number' : 'digit'"
      :placeholder="placeholder"
      :disabled="disabled"
      @input="onInput"
      @blur="onBlur"
    />
    <text v-if="hint" class="fa-hint">{{ hint }}</text>
  </view>
</template>

<script setup lang="ts">
/**
 * 带单位与小数位保护的数字输入。
 * 契约里的小数口径写死：`DECIMAL(6,2)`/`DECIMAL(5,2)` → 最多 2 位小数（docs/p1-api.md §4.2、§5），
 * 组数/次数/秒数是整数。这里在输入阶段就收敛格式，避免提交后才收到 `VALIDATION_FAILED`。
 */
import { computed, ref, watch } from 'vue'
import { inputValue } from '@/utils/events'
import { sanitizeDecimalInput } from '@/utils/units'

const props = withDefaults(
  defineProps<{
    modelValue: number | null
    label: string
    unit?: string
    required?: boolean
    disabled?: boolean
    /** 小数位上限：体重/负重/身高用 2；组次/秒数用 0 */
    maxDecimals?: number
    integerOnly?: boolean
    placeholder?: string
    hint?: string
  }>(),
  {
    unit: '',
    required: false,
    disabled: false,
    maxDecimals: 2,
    integerOnly: false,
    placeholder: '未填写',
    hint: ''
  }
)

const emit = defineEmits<{ (e: 'update:modelValue', value: number | null): void }>()

const raw = ref('')
watch(
  () => props.modelValue,
  (value) => {
    const next = value === null || value === undefined ? '' : String(value)
    if (next !== raw.value) raw.value = next
  },
  { immediate: true }
)

const textValue = computed(() => raw.value)

function commit(s: string): void {
  raw.value = s
  if (s === '' || s === '.') {
    emit('update:modelValue', null)
    return
  }
  const n = Number(s)
  emit('update:modelValue', Number.isFinite(n) ? n : null)
}

function onInput(evt: Event): void {
  commit(sanitizeDecimalInput(inputValue(evt), { maxDecimals: props.maxDecimals, allowNegative: false }))
}

function onBlur(evt: Event): void {
  const s = sanitizeDecimalInput(inputValue(evt), { maxDecimals: props.maxDecimals, allowNegative: false })
  commit(s)
}
</script>

<style lang="scss" scoped>
.number-field__unit {
  color: $fa-text-mute;
  margin-left: 2px;
}
</style>
