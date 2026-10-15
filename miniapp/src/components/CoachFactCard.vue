<template>
  <view class="cfc">
    <view class="cfc__head">
      <text class="fa-strong cfc__label">{{ labelText }}</text>
      <text class="cfc__value" :class="{ 'cfc__value--unknown': valueText === '' }">{{ valueLine }}</text>
    </view>

    <!-- 来源与日期：一律服务端提供，缺失就写「未提供」，不补造 -->
    <view class="cfc__meta">
      <text class="fa-muted cfc__meta-item">{{ asOfText }}</text>
      <text class="fa-muted cfc__meta-item">{{ sourceText }}</text>
      <text class="fa-muted cfc__meta-item">{{ ruleText }}</text>
    </view>

    <text v-if="unknown" class="fa-muted cfc__note">该事实没有服务端确认值；页面不代为填写，也不会用 0 代替未知。</text>
  </view>
</template>

<script setup lang="ts">
/**
 * 事实卡片（docs/agent-v1-design.md §3 / §9）。
 *
 * 铁律：卡片内容**只能来自服务端组装的 `factCards`**（`AnswerAssembler` 按引用取工具提供的值/单位/日期）。
 * 模型的自由文本不是个人数值的来源；本组件不做任何计算、换算、补零或合成：
 * 值为 null / 空串就显示「无已确认记录」，`asOf` / 来源 / 规则版本缺失就显示「未提供」。
 */
import { computed } from 'vue'
import type { FactCard } from '@/types/coach'

const props = defineProps<{ fact: FactCard }>()

const NOT_PROVIDED = '未提供'

const labelText = computed(() => (props.fact.label || '').trim() || '未标注名称的事实')

/** 原始值文本：数字 0 是真实值，空/null 才是未知。 */
const valueText = computed(() => {
  const raw = props.fact.value
  if (raw === null || raw === undefined) return ''
  if (typeof raw === 'number') return Number.isFinite(raw) ? String(raw) : ''
  return String(raw).trim()
})

const unknown = computed(() => valueText.value === '')

const valueLine = computed(() => {
  if (unknown.value) return '无已确认记录'
  const unit = (props.fact.unit || '').trim()
  return unit ? `${valueText.value} ${unit}` : valueText.value
})

function orNotProvided(value: string | null | undefined): string {
  const t = (value || '').trim()
  return t.length > 0 ? t : NOT_PROVIDED
}

const asOfText = computed(() => `时间 ${orNotProvided(props.fact.asOf)}`)

const sourceText = computed(() => {
  const table = (props.fact.sourceTable || '').trim()
  const id = (props.fact.sourceId || '').trim()
  if (!table && !id) return `来源 ${NOT_PROVIDED}`
  return `来源 ${table || NOT_PROVIDED}${id ? `#${id}` : ''}`
})

const ruleText = computed(() => `规则版本 ${orNotProvided(props.fact.ruleVersion)}`)
</script>

<style lang="scss" scoped>
.cfc {
  border: 1px solid $fa-line;
  /* 左侧竖条承载"这是有来源的事实"，必须用能看见的 accent；淡黄做图形只有 1.4:1 */
  border-left: 3px solid $fa-accent;
  border-radius: $fa-radius-sm;
  padding: 16rpx 20rpx;
  margin-bottom: $fa-space-tight;
  background-color: $fa-surface;
}

.cfc__head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px;
}

.cfc__label {
  font-size: $fa-font-ui;
  color: $fa-text-sub;
}

.cfc__value {
  font-size: $fa-font-num;
  font-weight: 600;
  color: $fa-text;
}

.cfc__value--unknown {
  font-size: $fa-font-ui;
  font-weight: 400;
  color: $fa-text-mute;
}

.cfc__meta {
  margin-top: 4px;
}

.cfc__meta-item {
  display: block;
  word-break: break-all;
}

.cfc__note {
  display: block;
  margin-top: 4px;
}
</style>
