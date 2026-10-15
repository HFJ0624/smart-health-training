<template>
  <view class="fa-icon" :class="`fa-icon--${name}--${tone}`" :style="{ width: size, height: size }" />
</template>

<script setup lang="ts">
/**
 * 全站唯一的图标出口：色调只能取 5 个可作前景的 token（`primary`/`highlight` 是填充色，
 * 淡黄之上/之下的图标必须看得见，所以类型上就不给它们机会）。
 * 具体图形由 `scripts/gen-icons.mjs` 烘成 base64 SVG 类，微信端 `background-image` 实测可用。
 */
import type { FaIconName, FaIconTone } from '@/styles/icon-names'

withDefaults(
  defineProps<{
    name: FaIconName
    tone?: FaIconTone
    /** CSS 长度，默认对齐 $fa-icon-md（56rpx） */
    size?: string
  }>(),
  { tone: 'text', size: '56rpx' }
)
</script>

<style lang="scss" scoped>
.fa-icon {
  display: inline-block;
  flex: 0 0 auto;
  background-repeat: no-repeat;
  background-position: center;
  background-size: 100% 100%;
}
</style>
