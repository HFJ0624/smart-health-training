<template>
  <view v-if="open" class="fa-more" @tap="close">
    <view class="fa-more__panel" @tap.stop>
      <text class="fa-more__title">{{ title }}</text>
      <view
        v-for="item in items"
        :key="item.key"
        class="fa-more__item"
        :class="{ 'fa-more__item--danger': item.danger }"
        hover-class="fa-more__item--hover"
        :hover-stay-time="60"
        @tap="choose(item.key)"
      >
        <FaIcon :name="item.icon" :tone="item.danger ? 'danger' : 'text'" size="48rpx" />
        <text class="fa-more__label">{{ item.label }}</text>
      </view>
      <view class="fa-btn fa-more__cancel" @tap="close">取消</view>
      <view class="fa-more__safe" />
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 次级操作收纳（复制/归档/删除/动作字典…）。提示词 §5 要求"每个区域只有一个突出主操作"，
 * 但功能一个都不能少——所以这里只做层级，不做隐藏：菜单项仍是完整文字 + 图标，
 * 危险项用 danger 色调并在页面上继续走原有的二次确认。
 */
import type { MoreItem } from '@/types/ui'
import FaIcon from './FaIcon.vue'

withDefaults(
  defineProps<{
    open: boolean
    items: MoreItem[]
    title?: string
  }>(),
  { title: '更多操作' }
)

const emit = defineEmits<{ (e: 'update:open', value: boolean): void; (e: 'select', key: string): void }>()

function choose(key: string): void {
  emit('update:open', false)
  emit('select', key)
}

function close(): void {
  emit('update:open', false)
}
</script>

<style lang="scss" scoped>
.fa-more {
  position: fixed;
  left: 0;
  right: 0;
  top: 0;
  bottom: 0;
  z-index: 60;
  background-color: rgba(51, 46, 36, 0.45);
  display: flex;
  align-items: flex-end;
}

.fa-more__panel {
  width: 100%;
  background-color: $fa-surface;
  border-radius: $fa-radius $fa-radius 0 0;
  padding: $fa-space-card $fa-space-page;
}

/* 安全区用单独一层高度撑开：sass 会把 calc(rpx + env()) 当数学式子求值，容易翻车 */
.fa-more__safe {
  height: env(safe-area-inset-bottom);
}

.fa-more__title {
  display: block;
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  color: $fa-text-sub;
  margin-bottom: $fa-space-tight;
}

.fa-more__item {
  display: flex;
  align-items: center;
  gap: 24rpx;
  min-height: 96rpx;
  padding: 0 8rpx;
  border-bottom: 1px solid $fa-line;
}

.fa-more__item--hover {
  background-color: $fa-highlight;
}

.fa-more__label {
  font-size: $fa-font-ui-lg;
  color: $fa-text;
}

.fa-more__item--danger .fa-more__label {
  color: $fa-danger;
}

.fa-more__cancel {
  margin-top: $fa-space-tight;
  background-color: $fa-page-bg;
}
</style>
