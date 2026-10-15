<template>
  <view class="fa-trend">
    <canvas v-if="points.length > 0" :id="nodeId" type="2d" class="fa-trend__canvas" :style="{ height }" />
    <view v-else class="fa-trend__empty">
      <FaIcon name="inbox" tone="sub" size="56rpx" />
      <text class="fa-trend__note">这个区间里没有测量记录</text>
    </view>
    <!-- 覆盖范围与点数由页面给（口径不能由图表自己编），单点时也必须说明"不成线" -->
    <text v-if="note" class="fa-trend__note">{{ note }}</text>
  </view>
</template>

<script setup lang="ts">
/**
 * 真实体重折线（`canvas type="2d"`，微信基础库 2.9+；H5 端 uni-app 不提供 node 句柄，
 * 因此图表只在微信端渲染，文字口径两端都在）。
 *
 * 三条不能让步的规则：
 *   1) 横轴按**时间戳**定位，不按记录序号——补录造成的稀疏区间必须看出疏，不能被画成等间隔；
 *   2) 只有 1 个点时只画点不连线，绝不为了"看起来像趋势"造第二个点；
 *   3) 每个实例用唯一 canvas id 并在卸载后停止绘制，页面进出/多实例共用组件时不会互相覆盖。
 */
import { computed, getCurrentInstance, nextTick, onMounted, onUnmounted, watch } from 'vue'
import { theme } from '@/styles/theme'
import type { TrendPoint } from '@/types/ui'
import { formatDecimal } from '@/utils/units'
import FaIcon from './FaIcon.vue'

/** `canvas` 节点在 @dcloudio/types 里没有 2d 声明，这里只描述用到的三个成员。 */
interface FaCanvasNode {
  width: number
  height: number
  getContext(kind: '2d'): CanvasRenderingContext2D | null
}

const props = withDefaults(
  defineProps<{
    points: TrendPoint[]
    height?: string
    note?: string
  }>(),
  { height: '300rpx', note: '' }
)

/* 模块级自增：同一页面出现两张趋势图时 canvas id 不能撞 */
let seq = 0
const nodeId = `fa-trend-${++seq}`
const instance = getCurrentInstance()
let alive = true

const sorted = computed<TrendPoint[]>(() =>
  [...props.points].filter((p) => Number.isFinite(p.t) && Number.isFinite(p.value)).sort((a, b) => a.t - b.t)
)

onMounted(() => nextTick(draw))
watch(() => [props.points.length, sorted.value.map((p) => `${p.t}:${p.value}`).join(',')], () => nextTick(draw))
onUnmounted(() => {
  alive = false
})

function draw(): void {
  const pts = sorted.value
  if (!alive || pts.length === 0) return
  uni
    .createSelectorQuery()
    .in(instance)
    .select(`#${nodeId}`)
    .fields({ node: true, size: true }, () => undefined)
    .exec((res: unknown) => {
      const first = (res as Array<{ node?: FaCanvasNode; width?: number; height?: number } | undefined> | undefined)?.[0]
      const canvas = first?.node
      const w = first?.width ?? 0
      const h = first?.height ?? 0
      if (!canvas || w <= 0 || h <= 0) return
      const ctx = canvas.getContext('2d')
      if (!ctx) return
      const dpr = uni.getSystemInfoSync().pixelRatio || 2
      // 设置 width/height 会重置上下文状态，所以这里可以直接 scale，不必先 resetTransform
      canvas.width = Math.floor(w * dpr)
      canvas.height = Math.floor(h * dpr)
      ctx.scale(dpr, dpr)
      render(ctx, w, h, pts)
    })
}

const PAD = { left: 52, right: 14, top: 20, bottom: 36 }

function render(ctx: CanvasRenderingContext2D, w: number, h: number, pts: TrendPoint[]): void {
  const iw = Math.max(1, w - PAD.left - PAD.right)
  const ih = Math.max(1, h - PAD.top - PAD.bottom)
  const values = pts.map((p) => p.value)
  let lo = Math.min(...values)
  let hi = Math.max(...values)
  if (lo === hi) {
    lo -= 1
    hi += 1
  }
  const span = hi - lo
  const t0 = pts[0].t
  const tSpan = pts[pts.length - 1].t - t0 || 1
  const x = (t: number): number => PAD.left + ((t - t0) / tSpan) * iw
  const y = (v: number): number => PAD.top + (1 - (v - lo) / span) * ih

  ctx.clearRect(0, 0, w, h)

  ctx.strokeStyle = theme.line
  ctx.lineWidth = 1
  ctx.beginPath()
  ctx.moveTo(PAD.left, PAD.top + ih)
  ctx.lineTo(PAD.left + iw, PAD.top + ih)
  ctx.stroke()

  ctx.fillStyle = theme.textSub
  ctx.font = '14px sans-serif'
  ctx.textAlign = 'left'
  ctx.fillText(formatDecimal(hi), 2, PAD.top + 6)
  ctx.fillText(formatDecimal(lo), 2, PAD.top + ih + 4)

  if (pts.length > 1) {
    ctx.strokeStyle = theme.accent
    ctx.lineWidth = 2
    ctx.lineJoin = 'round'
    ctx.lineCap = 'round'
    ctx.beginPath()
    pts.forEach((p, i) => (i === 0 ? ctx.moveTo(x(p.t), y(p.value)) : ctx.lineTo(x(p.t), y(p.value))))
    ctx.stroke()
  }

  pts.forEach((p, i) => {
    ctx.beginPath()
    ctx.arc(x(p.t), y(p.value), i === pts.length - 1 ? 4.5 : 3, 0, Math.PI * 2)
    ctx.fillStyle = theme.surface
    ctx.fill()
    ctx.strokeStyle = theme.accent
    ctx.lineWidth = 2
    ctx.stroke()
  })

  ctx.fillStyle = theme.textSub
  ctx.textAlign = 'left'
  ctx.fillText(pts[0].label, PAD.left, h - 12)
  ctx.textAlign = 'right'
  if (pts.length > 1) ctx.fillText(pts[pts.length - 1].label, PAD.left + iw, h - 12)
  ctx.textAlign = 'left'
}
</script>

<style lang="scss" scoped>
.fa-trend__canvas {
  width: 100%;
  display: block;
}

.fa-trend__empty {
  display: flex;
  align-items: center;
  gap: 16rpx;
  min-height: 120rpx;
}

.fa-trend__note {
  display: block;
  font-size: $fa-font-ui;
  color: $fa-text-mute;
  margin-top: 8rpx;
}
</style>
