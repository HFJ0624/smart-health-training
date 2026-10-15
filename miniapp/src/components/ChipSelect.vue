<template>
  <view class="chip-select">
    <view
      v-for="opt in options"
      :key="opt.value"
      class="fa-chip chip-select__chip"
      :class="{ 'fa-chip--on': modelValue === opt.value }"
      @tap="pick(opt.value)"
    >
      <text>{{ opt.label }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 枚举单选芯片组（高密度界面：芯片即触控目标，统一 44px，不用小程序默认 13px 的 radio 文案）。
 *
 * 值一律是契约里的大写字面量（`docs/p1-database.md` §5 补充规则 1：非大写字面量后端判
 * `INVALID_ENUM_VALUE`），所以这里的选项直接来自 `src/types/*` 的常量表，页面不再手写字符串。
 * 命中两处以上复用：档案页（训练经验、性别参数）、目标页（目标类型）。
 *
 * 刻意不内置「必填」判断：必填与取值合法性由页面在提交时自检 + 服务端 `VALIDATION_FAILED`
 * 兜底，组件只做选择。
 */
const props = withDefaults(
  defineProps<{
    modelValue: string
    options: Array<{ value: string; label: string }>
    disabled?: boolean
  }>(),
  { disabled: false }
)

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()

function pick(value: string): void {
  if (props.disabled) return
  emit('update:modelValue', value)
}
</script>

<style lang="scss" scoped>
.chip-select {
  display: flex;
  flex-wrap: wrap;
}

.chip-select__chip {
  min-height: $fa-tap;
  align-items: center;
}
</style>
