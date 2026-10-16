<template>
  <view class="fa-page">
    <!-- 状态筛选是「同一视图的口径切换」，所以用分段控件；四段里含三字标签，再塞图标会在一屏内放不下 -->
    <view class="hi-filter">
      <FaSegmented :model-value="statusFilter" :options="STATUS_OPTIONS" :disabled="loading" @update:model-value="setFilter" />
      <text class="fa-hint">已确认的记录才进统计；进行中的草稿只算你自己填了并勾了的那些组。</text>
    </view>

    <StateBlock
      :state="pageState"
      :failure="failure"
      :loading-text="loadingText"
      :empty-title="emptyTitle"
      :empty-desc="emptyDesc"
      @retry="reload"
    >
      <template #action>
        <view class="fa-btn-row">
          <view class="fa-btn fa-btn--primary" @tap="goRecord">记录</view>
          <view class="fa-btn" @tap="goStats">统计</view>
        </view>
      </template>

      <view v-for="row in rows" :key="row.id" class="fa-card hi-item">
        <!-- 一行一个主落点：草稿行去续填，其它行去详情页。
             两者都带 `sessionId` 走 GET：不带的话记录页会「开始或恢复」一场，
             那会让查看历史变成新建数据（pages/workout/record.vue 的 enterById）。 -->
        <view class="hi-item__head" hover-class="hi-item__head--hover" @tap="openDetail(row)">
          <FaIcon :name="statusIcon(row.status)" :tone="statusTone(row.status)" size="44rpx" />
          <view class="hi-item__main">
            <text class="fa-strong hi-item__date">{{ row.businessDate }} · {{ statusLabel(row.status) }}</text>
            <text class="fa-muted">{{ rowSummary(row) }}</text>
          </view>
          <text class="hi-item__action">{{ row.status === 'DRAFT' ? '继续' : '详情' }}</text>
          <FaIcon name="chevron" tone="sub" size="36rpx" />
        </view>
        <view class="hi-item__ops">
          <view class="fa-btn fa-btn--sm" @tap="goDayStats(row.businessDate)">
            <FaIcon name="target" tone="accent" size="36rpx" />
            <text>当天</text>
          </view>
          <text v-if="row.planId" class="fa-hint hi-item__plan">参考计划第 {{ row.planRevisionNo ?? '?' }} 版 · 场次 {{ row.id }}</text>
          <text v-else class="fa-hint hi-item__plan">无参考计划（自主记录）</text>
        </view>
      </view>

      <view v-if="hasNext" class="fa-btn fa-btn--block hi-more" :class="{ 'fa-btn--disabled': loadingMore }" @tap="loadMore">
        {{ loadingMore ? '加载中…' : total === null ? `加载更多（已载入 ${rows.length} 条）` : `加载更多（已载入 ${rows.length} / ${total}）` }}
      </view>
      <text v-else-if="rows.length > 0" class="fa-hint">
        已到最后一页{{ total === null ? '（条数未知）' : `（共 ${total} 条）` }}。
      </text>
    </StateBlock>
  </view>
</template>

<script setup lang="ts">
/**
 * 训练历史（阶段 2 第二片，docs/stage2-design.md §5、§8）：本人场次分页 + 状态筛选 + 进详情。
 *
 * 三条口径：
 *   1. 本页**只读列表**（`GET /api/workout/sessions`），不写任何数据，所以没有幂等键与乐观锁的提交路径；
 *      详情一律交回 `pages/workout/record?sessionId=…`，那一页按 ID 走 GET 打开，绝不新建场次。
 *   2. 列表里的 `durationSeconds` 是场次历时（`ended_at - started_at`），草稿态是 null，
 *      页面显示「未记」而不是 0 秒——未知与已知的零在契约里是两回事（§3 V25）。
 *   3. 换筛选/翻页用「请求代次」作废旧响应：慢回来的旧分页若 concat 到新结果后面，用户看到的是不存在的列表。
 */
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import FaIcon from '@/components/FaIcon.vue'
import FaSegmented from '@/components/FaSegmented.vue'
import StateBlock from '@/components/StateBlock.vue'
import { fetchWorkoutSessions } from '@/api/workout'
import {
  WORKOUT_STATUS_LABELS,
  type WorkoutSessionListQuery,
  type WorkoutSessionStatus,
  type WorkoutSessionSummary
} from '@/types/workout'
import type { FaIconName, FaIconTone } from '@/styles/icon-names'
import type { PageState } from '@/types/api'
import { secondsToHuman } from '@/utils/units'
import { toFailureView, type FailureView } from '@/utils/errorMessage'
import { isSessionExpiredError, pageItems, pageMeta } from '@/utils/request'

const STATUS_OPTIONS = [
  { value: '', label: '全部' },
  { value: 'DRAFT', label: '进行中' },
  { value: 'COMPLETED', label: '已确认' },
  { value: 'DISCARDED', label: '已丢弃' }
]

const rows = ref<WorkoutSessionSummary[]>([])
const total = ref<number | null>(null)
const hasNext = ref(false)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const loadingMore = ref(false)
const loadedOnce = ref(false)
const failure = ref<FailureView | null>(null)
const statusFilter = ref('')

const pageState = computed<PageState>(() => {
  if (failure.value?.sessionExpired) return 'sessionExpired'
  if (loading.value && rows.value.length === 0) return 'loading'
  if (failure.value && rows.value.length === 0) return 'error'
  if (loadedOnce.value && rows.value.length === 0) return 'empty'
  return 'success'
})

const loadingText = computed(() => (loadingMore.value ? '加载中…' : '正在加载训练记录…'))

const emptyTitle = computed(() => (statusFilter.value ? '没有符合条件的场次' : '还没有训练记录'))
const emptyDesc = computed(() =>
  statusFilter.value
    ? `「${STATUS_OPTIONS.find((o) => o.value === statusFilter.value)?.label ?? ''}」这一类下还没有记录，换一类或记一次训练。`
    : '从首页今日卡或训练页开始一场，勾完组点「结束」后就会出现在这里。'
)

function statusLabel(status: WorkoutSessionStatus): string {
  return WORKOUT_STATUS_LABELS[status] ?? status
}

function statusIcon(status: WorkoutSessionStatus): FaIconName {
  if (status === 'COMPLETED') return 'check'
  if (status === 'DRAFT') return 'clock'
  return 'alert'
}

function statusTone(status: WorkoutSessionStatus): FaIconTone {
  if (status === 'COMPLETED') return 'success'
  if (status === 'DRAFT') return 'accent'
  return 'sub'
}

/** 列表行的摘要：只报服务端算好的计数，页面不做加法也不给百分比。 */
function rowSummary(row: WorkoutSessionSummary): string {
  const duration = row.durationSeconds === null ? '历时未记' : `历时 ${secondsToHuman(row.durationSeconds)}`
  return `${row.entryCount} 个动作 · 已确认 ${row.completedSetCount} 组 · ${duration}`
}

/* ------------------------------------------------------------------ 列表加载 */

let epoch = 0

async function load(reset: boolean): Promise<void> {
  if (loading.value && !reset) return
  const token = ++epoch
  if (reset) {
    page.value = 1
    loading.value = true
    failure.value = null
  } else {
    loadingMore.value = true
  }
  const query: WorkoutSessionListQuery = {
    status: statusFilter.value as WorkoutSessionStatus | '',
    page: page.value,
    size: size.value
  }
  try {
    const res = await fetchWorkoutSessions(query)
    if (token !== epoch) return
    const data = pageItems(res)
    rows.value = reset ? data : rows.value.concat(data)
    const meta = pageMeta(res, page.value, size.value)
    total.value = meta.total
    hasNext.value = meta.hasNext
    loadedOnce.value = true
    failure.value = null
  } catch (err) {
    if (token !== epoch) return
    failure.value = toFailureView(err)
    if (isSessionExpiredError(err)) loadedOnce.value = true
    if (rows.value.length > 0) uni.showToast({ title: err instanceof Error ? err.message : '加载失败', icon: 'none' })
  } finally {
    if (token === epoch) {
      loading.value = false
      loadingMore.value = false
    }
  }
}

function reload(): void {
  void load(true)
}

function loadMore(): void {
  if (!hasNext.value || loadingMore.value) return
  page.value += 1
  void load(false)
}

function setFilter(value: string): void {
  if (value === statusFilter.value) return
  statusFilter.value = value
  reload()
}

/* ------------------------------------------------------------------ 进详情 */

/**
 * 详情与续填都落在 `pages/workout/record?sessionId=…`：那一页按 ID 只走 GET 打开这一场，
 * 不带 sessionId 的进入是「开始或恢复」，会把查看历史变成新建数据。
 */
function openDetail(row: WorkoutSessionSummary): void {
  uni.navigateTo({ url: `/pages/workout/record?sessionId=${encodeURIComponent(row.id)}` })
}

/* ------------------------------------------------------------------ 落点 */

function goRecord(): void {
  uni.navigateTo({ url: '/pages/workout/record' })
}

function goStats(): void {
  uni.navigateTo({ url: '/pages/workout/stats' })
}

function goDayStats(date: string): void {
  uni.navigateTo({ url: `/pages/workout/stats?mode=day&date=${encodeURIComponent(date)}` })
}

onLoad((query) => {
  const q = (query ?? {}) as Record<string, string | undefined>
  const status = (q.status || '').trim().toUpperCase()
  if (STATUS_OPTIONS.some((o) => o.value === status)) statusFilter.value = status
})

onShow(() => {
  // 从记录页返回必须重读：这一场可能刚被确认，列表里的状态与组数都是旧的
  reload()
})

onPullDownRefresh(() => {
  void load(true).then(() => uni.stopPullDownRefresh())
})
</script>

<style lang="scss" scoped>
.hi-filter {
  margin-bottom: $fa-space-section;
}

.hi-item__head {
  display: flex;
  align-items: center;
  gap: $fa-space-tight;
}

/* 按压底色用奶油而不是淡黄：这一行里有 muted 文字，淡黄上 muted 只有 4.34:1（同 body/records.vue） */
.hi-item__head--hover {
  background-color: $fa-page-bg;
}

.hi-item__main {
  flex: 1;
  min-width: 0;
}

.hi-item__date {
  display: block;
  font-size: $fa-font-ui-lg;
}

.hi-item__action {
  flex: 0 0 auto;
  font-size: $fa-font-ui;
  font-weight: 600;
  color: $fa-accent;
}

.hi-item__ops {
  display: flex;
  align-items: center;
  gap: $fa-space-tight;
  margin-top: $fa-space-tight;
  padding-top: $fa-space-tight;
  border-top: 1px solid $fa-line;
}

/* 场次 ID 是长数字串：只让它自己折行，不把整行布局撑破 */
.hi-item__plan {
  flex: 1;
  min-width: 0;
  word-break: break-all;
}

.hi-more {
  margin: $fa-space-tight 0;
}
</style>
