<template>
  <view class="fa-cal">
    <view class="fa-cal__grid">
      <view
        v-for="c in cells"
        :key="c.date"
        class="fa-cal__cell"
        :class="{ 'fa-cal__cell--today': c.isToday }"
        hover-class="fa-cal__cell--hover"
        :hover-stay-time="60"
        @tap="emit('pick', c.date)"
      >
        <text class="fa-cal__weekday">{{ c.weekLabel }}</text>
        <!-- 已确认训练日用实心块：它表示"这一天有已确认记录"，与计划页的「安排」条是两种语义，不共用样式 -->
        <view
          class="fa-cal__box"
          :class="{ 'fa-cal__box--done': c.trained, 'fa-cal__box--sel': c.date === selected }"
        >
          <text class="fa-cal__day">{{ c.dayOfMonth }}</text>
          <text v-if="c.trained" class="fa-cal__mark">{{ c.mark }}</text>
          <text v-else class="fa-cal__empty">未记</text>
        </view>
      </view>
    </view>
    <view class="fa-cal__legend">
      <view class="fa-cal__key">
        <view class="fa-cal__swatch fa-cal__swatch--done" />
        <text class="fa-cal__keytext">已确认训练</text>
      </view>
      <view class="fa-cal__key">
        <view class="fa-cal__swatch" />
        <text class="fa-cal__keytext">没有已确认记录</text>
      </view>
    </view>
    <text v-if="note" class="fa-cal__note">{{ note }}</text>
  </view>
</template>

<script setup lang="ts">
/**
 * 周训练日历（阶段 2，docs/stage2-design.md §6 与 §8）。
 *
 * 只显示**已确认场次**：服务端 `WeekSummary.days` 只包含有已确认记录的日期，所以七个格子由本组件
 * 按 `weekStartDate`（周一）补齐，缺的日期显示「未记」而不是空白——空白会被读成"这一天没发生任何事"。
 * 这里**不显示任何百分比**：周层面平均日完成度是假口径，只给场次/组数这类绝对量。
 *
 * ⚠ 与 `FaDayStrip` 是两套语义，不能互换：`FaDayStrip` 画的是**计划安排**（点=这天有动作、
 * 横线=休息日、空心=未安排），说的是"计划本打算让你练"；本组件画的是**已确认执行**，
 * 亮的唯一条件是服务端在这一天上找到了 `COMPLETED` 场次。安排永远不会自动变成练过（§1），
 * 所以两者也刻意用不同图形：那里是圆点/横线，这里是填色的方格 + 场次与组数。
 */
import { computed } from 'vue'
import { addDays, isoWeekday, parseDateValue, todayDateValue } from '@/utils/date'
import type { WeekDayRow } from '@/types/metrics'

const props = withDefaults(
  defineProps<{
    /** 所在周的周一（服务端强校验；不是周一时本页拒绝渲染） */
    weekStartDate: string
    days: WeekDayRow[]
    /** 高亮用：`YYYY-MM-DD`，默认取设备今天 */
    today?: string
    /** 当前选中的日期：日历一格 = 一天，选中态必须比底色更可辨（2px 实线描边） */
    selected?: string
    note?: string
  }>(),
  { today: '', selected: '', note: '' }
)

const emit = defineEmits<{ (e: 'pick', date: string): void }>()

interface CalCell {
  date: string
  dayOfMonth: string
  weekLabel: string
  trained: boolean
  mark: string
  isToday: boolean
}

const WEEK_LABELS = ['一', '二', '三', '四', '五', '六', '日']

const cells = computed<CalCell[]>(() => {
  const start = parseDateValue(props.weekStartDate)
  if (!start || isoWeekday(start) !== 1) return []
  const byDate = new Map<string, WeekDayRow>()
  props.days.forEach((d) => byDate.set(d.businessDate, d))
  const todayValue = props.today || todayDateValue()
  const out: CalCell[] = []
  for (let i = 0; i < 7; i += 1) {
    const date = addDays(props.weekStartDate, i)
    const row = byDate.get(date)
    const sessions = row?.sessionCount ?? 0
    out.push({
      date,
      dayOfMonth: date.slice(8),
      weekLabel: WEEK_LABELS[i],
      trained: sessions > 0,
      // 场次与组数都直接来自服务端计数，页面不做加法
      mark: row ? `${sessions}场${row.confirmedSetCount ?? 0}组` : '',
      isToday: todayValue === date
    })
  }
  return out
})
</script>

<style lang="scss" scoped>
.fa-cal__grid {
  display: flex;
  gap: 8rpx;
}

.fa-cal__cell {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6rpx;
}

.fa-cal__weekday {
  font-size: $fa-font-ui;
  color: $fa-text-mute;
}

.fa-cal__box {
  width: 100%;
  min-height: $fa-tap;
  padding: 8rpx 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2rpx;
  border: 1px solid $fa-line;
  border-radius: $fa-radius-sm;
  background-color: $fa-surface;
}

.fa-cal__box--done {
  border-color: $fa-accent;
  background-color: $fa-highlight;
}

.fa-cal__cell--today .fa-cal__box {
  border-width: 2px;
  border-color: $fa-control-border;
}

/* 选中态：加粗描边 + 反色文字，不靠单一底色（淡黄底上的选中很容易和"已训练"撞） */
.fa-cal__box--sel {
  border-width: 3px;
  border-color: $fa-text;
}

.fa-cal__box--sel .fa-cal__day {
  font-weight: 700;
}

.fa-cal__cell--hover .fa-cal__box {
  border-color: $fa-control-border;
}

.fa-cal__day {
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  color: $fa-text;
}

/* 「N场N组」五个字在 7 等分格里必然挤：允许两行，靠字号降级而不是裁掉 */
.fa-cal__mark {
  font-size: $fa-font-ui;
  color: $fa-accent;
  line-height: 1.3;
  text-align: center;
}

.fa-cal__empty {
  font-size: $fa-font-ui;
  color: $fa-text-mute;
}

.fa-cal__legend {
  display: flex;
  flex-wrap: wrap;
  gap: $fa-space-tight;
  margin-top: 12rpx;
}

.fa-cal__key {
  display: flex;
  align-items: center;
  gap: 8rpx;
}

.fa-cal__swatch {
  width: 20rpx;
  height: 20rpx;
  border: 1px solid $fa-line;
  border-radius: 4rpx;
  background-color: $fa-surface;
}

.fa-cal__swatch--done {
  border-color: $fa-accent;
  background-color: $fa-highlight;
}

.fa-cal__keytext {
  font-size: $fa-font-ui;
  color: $fa-text-sub;
}

.fa-cal__note {
  display: block;
  margin-top: 8rpx;
  font-size: $fa-font-ui;
  color: $fa-text-mute;
  line-height: 1.5;
}
</style>
