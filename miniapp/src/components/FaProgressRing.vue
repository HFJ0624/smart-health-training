<template>
  <view class="fa-ring">
    <!-- 没有真实目标（percent=null）时**不画环**：结构上就不存在"给个 0% 或 100% 看着完整"的可能 -->
    <view v-if="percent === null || percent === undefined" class="fa-ring__none">
      <FaIcon name="inbox" tone="sub" size="56rpx" />
      <text class="fa-ring__label">{{ label }}</text>
      <text class="fa-ring__note">{{ note || '暂无依据' }}</text>
    </view>

    <view v-else class="fa-ring__box" :style="{ width: size, height: size }">
      <canvas :id="nodeId" type="2d" class="fa-ring__canvas" :style="{ width: size, height: size }" />
      <view class="fa-ring__center">
        <text class="fa-ring__num">{{ shown }}</text>
        <text class="fa-ring__unit">{{ caption }}</text>
      </view>
    </view>
    <text v-if="percent !== null && percent !== undefined" class="fa-ring__label">{{ label }}</text>
    <text v-if="percent !== null && percent !== undefined && note" class="fa-ring__note">{{ note }}</text>
  </view>
</template>

<script setup lang="ts">
/**
 * 已记录完成度环（`canvas type="2d"`，与 `FaTrendChart` 同一套绘制约定）。
 *
 * 三条规则：
 *   1. `percent` 只能来自服务端 `Metric.value`（封顶口径），本组件**不做任何计算**；
 *   2. `null` 走「不画环」分支——无依据就诚实地显示为无依据，不用 0% 假装没练、也不用 100% 假装练完；
 *   3. 数值超过 100（理论不该出现）时**画满但照原值显示**，图形收敛不等于把数据改掉。
 */
import { computed, getCurrentInstance, nextTick, onMounted, onUnmounted, watch } from 'vue'
import { theme } from '@/styles/theme'
import FaIcon from './FaIcon.vue'

interface RingCanvasNode {
  width: number
  height: number
  getContext(kind: '2d'): CanvasRenderingContext2D | null
}

const props = withDefaults(
  defineProps<{
    /** 服务端百分比值（2 位小数的 number）；null = 无依据 */
    percent?: number | null
    label?: string
    caption?: string
    note?: string
    size?: string
  }>(),
  { percent: null, label: '完成度', caption: '已记录', note: '', size: '220rpx' }
)

let seq = 0
const nodeId = `fa-ring-${++seq}`
const instance = getCurrentInstance()
let alive = true

/** 显示用的整数值：环是概览图形，2 位小数留给下面的文字口径，不在环上挤小数。 */
const shown = computed(() => {
  const p = props.percent
  if (p === null || p === undefined) return '—'
  return String(Math.round(p))
})

onMounted(() => nextTick(draw))
watch(() => props.percent, () => nextTick(draw))
onUnmounted(() => {
  alive = false
})

function draw(): void {
  const p = props.percent
  if (!alive || p === null || p === undefined) return
  uni
    .createSelectorQuery()
    .in(instance)
    .select(`#${nodeId}`)
    .fields({ node: true, size: true }, () => undefined)
    .exec((res: unknown) => {
      const first = (res as Array<{ node?: RingCanvasNode; width?: number; height?: number } | undefined> | undefined)?.[0]
      const canvas = first?.node
      const w = first?.width ?? 0
      const h = first?.height ?? 0
      if (!canvas || w <= 0 || h <= 0) return
      const ctx = canvas.getContext('2d')
      if (!ctx) return
      const dpr = uni.getSystemInfoSync().pixelRatio || 2
      canvas.width = Math.floor(w * dpr)
      canvas.height = Math.floor(h * dpr)
      ctx.scale(dpr, dpr)
      render(ctx, w, h, Math.max(0, Math.min(p, 100)))
    })
}

function render(ctx: CanvasRenderingContext2D, w: number, h: number, pct: number): void {
  const line = Math.max(6, Math.min(w, h) * 0.09)
  const cx = w / 2
  const cy = h / 2
  const r = Math.max(1, Math.min(w, h) / 2 - line / 2 - 2)

  ctx.clearRect(0, 0, w, h)
  ctx.lineCap = 'round'

  ctx.beginPath()
  ctx.arc(cx, cy, r, 0, Math.PI * 2)
  ctx.strokeStyle = theme.line
  ctx.lineWidth = line
  ctx.stroke()

  if (pct > 0) {
    // 从 12 点方向顺时针：负角度起点，读图方向和进度语义一致
    ctx.beginPath()
    ctx.arc(cx, cy, r, -Math.PI / 2, -Math.PI / 2 + (pct / 100) * Math.PI * 2)
    ctx.strokeStyle = theme.accent
    ctx.lineWidth = line
    ctx.stroke()
  }
}
</script>

<style lang="scss" scoped>
.fa-ring {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
}

.fa-ring__box {
  position: relative;
}

.fa-ring__canvas {
  display: block;
}

/* 数值浮在环心上：文字留在 DOM 里才能吃到主题字号，不靠 canvas.fillText 造第二套排版 */
.fa-ring__center {
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  bottom: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2rpx;
}

.fa-ring__num {
  font-size: $fa-num-lg;
  font-weight: 600;
  color: $fa-text;
  line-height: 1.1;
}

.fa-ring__unit {
  font-size: $fa-font-ui;
  color: $fa-text-sub;
}

.fa-ring__none {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
  padding: $fa-space-card;
}

.fa-ring__label {
  font-size: $fa-font-ui;
  color: $fa-text-sub;
  text-align: center;
}

.fa-ring__note {
  font-size: $fa-font-ui;
  color: $fa-text-mute;
  text-align: center;
  line-height: 1.5;
  max-width: 420rpx;
}
</style>
