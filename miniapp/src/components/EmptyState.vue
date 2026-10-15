<template>
  <view class="empty-state">
    <FaIcon class="empty-state__icon" :name="icon" tone="sub" size="56rpx" />
    <text class="empty-state__title">{{ title }}</text>
    <text v-if="desc" class="empty-state__desc">{{ desc }}</text>
    <view v-if="$slots.action" class="empty-state__action">
      <slot name="action" />
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 无数据态（5 态之一）。契约里有多种「合法的空」：`GET /api/me/profile` 无档案返回 `data:null`（§4.1，是空状态不是 404）、
 * `GET /api/plans/active` 无生效计划（§7）、`GET /api/body-records/latest` 无记录（§5）。
 * 图形承载"这是空的"，标题两到四字，说明只留一句——长解释归页面详情，不归空状态。
 */
import type { FaIconName } from '@/styles/icon-names'
import FaIcon from './FaIcon.vue'

withDefaults(
  defineProps<{
    title: string
    desc?: string
    icon?: FaIconName
  }>(),
  { desc: '', icon: 'inbox' }
)
</script>

<style lang="scss" scoped>
.empty-state {
  padding: 40rpx 8rpx;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8rpx;
}

.empty-state__title {
  font-size: $fa-font-title;
  font-weight: 600;
  color: $fa-text;
}

.empty-state__desc {
  font-size: $fa-font-prose;
  line-height: 1.6;
  color: $fa-text-sub;
}

.empty-state__action {
  margin-top: $fa-space-tight;
  width: 100%;
}
</style>
