<template>
  <view
    class="fa-tile"
    :class="{ 'fa-tile--disabled': disabled }"
    hover-class="fa-tile--hover"
    :hover-stay-time="80"
    @tap="onTap"
  >
    <FaIcon class="fa-tile__icon" :name="icon" :tone="tone" size="64rpx" />
    <text class="fa-tile__label">{{ label }}</text>
    <text v-if="hint" class="fa-tile__hint">{{ hint }}</text>
  </view>
</template>

<script setup lang="ts">
/**
 * 功能入口：一个图标 + 一个一至两字标签 + **一个**落点（提示词 §2.1）。
 * 刻意不提供第二个点击区——新建/编辑/删除挤在同一张卡里正是本轮要消灭的东西；
 * 次级操作请放 FaMoreMenu 或对应详情页。
 */
import type { FaIconName, FaIconTone } from '@/styles/icon-names'
import FaIcon from './FaIcon.vue'

const props = withDefaults(
  defineProps<{
    icon: FaIconName
    label: string
    hint?: string
    tone?: FaIconTone
    disabled?: boolean
  }>(),
  { hint: '', tone: 'text', disabled: false }
)

const emit = defineEmits<{ (e: 'tap'): void }>()

function onTap(): void {
  if (props.disabled) return
  emit('tap')
}
</script>

<style lang="scss" scoped>
.fa-tile {
  min-height: $fa-tile-h;
  padding: $fa-space-card;
  border-radius: $fa-radius;
  background-color: $fa-surface;
  border: 1px solid $fa-line;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16rpx;
}

/* 按压态不能只靠颜色：加描边，色觉障碍与屏幕取反下也认得出来 */
.fa-tile--hover {
  border-color: $fa-control-border;
  background-color: $fa-highlight;
}

.fa-tile__label {
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  color: $fa-text;
}

.fa-tile__hint {
  font-size: $fa-font-ui;
  color: $fa-text-mute;
  text-align: center;
}

.fa-tile--disabled {
  opacity: 0.55;
}
</style>
