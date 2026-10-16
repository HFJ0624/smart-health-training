<template>
  <view class="fa-page">
    <!-- 工具条：两个主入口 + 一个「更多」，不再并排三个长按钮 -->
    <view class="fa-toolbar">
      <view class="fa-toolbar__group">
        <view class="fa-btn fa-btn--primary fa-btn--sm" @tap="gotoCreate">
          <FaIcon name="plus" tone="text" size="40rpx" />
          <text>新建</text>
        </view>
        <view class="fa-btn fa-btn--sm" @tap="gotoImport">
          <FaIcon name="upload" tone="accent" size="40rpx" />
          <text>导入</text>
        </view>
      </view>
      <view class="fa-btn fa-btn--sm" @tap="moreOpen = true">
        <FaIcon name="more" tone="sub" size="40rpx" />
        <text>更多</text>
      </view>
    </view>

    <FaMoreMenu v-model:open="moreOpen" :items="MORE_ITEMS" @select="onMoreSelect" />

    <!-- 训练模块的二级结构：计划 / 记录 / 统计。本页恒为「计划」，点另两段是离开本页去对应页面，
         所以控件的 model-value 不跟随点击变化（否则会出现"选中了记录却还在看计划"的假状态） -->
    <view class="pl-sections">
      <FaSegmented :model-value="'PLAN'" :options="SECTION_OPTIONS" @update:model-value="gotoSection" />
      <text class="fa-hint">记录=你自己填并已确认的场次，统计=服务端按口径算出的对比；两者都不等于这里的计划安排。</text>
    </view>

    <!-- 过滤 + 排序 -->
    <view class="pl-filters">
      <view class="pl-chips">
        <view
          v-for="opt in STATUS_OPTIONS"
          :key="opt.value"
          class="fa-chip"
          :class="{ 'fa-chip--on': selectedStatus.includes(opt.value) }"
          @tap="toggleStatus(opt.value)"
        >
          <text>{{ opt.label }}</text>
        </view>
      </view>

      <view class="pl-sort">
        <text class="fa-row__label">排序</text>
        <picker mode="selector" :range="SORT_LABELS" :value="sortIndex" @change="onSortChange">
          <view class="fa-input pl-sort__value">{{ SORT_LABELS[sortIndex] }}</view>
        </picker>
        <view class="fa-btn pl-sort__reload" @tap="reload">应用</view>
      </view>
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
        <view class="fa-btn fa-btn--primary" @tap="gotoCreate">新建第一个计划</view>
      </template>

      <view v-for="group in groups" :key="group.uuid" class="pl-group">
        <view class="pl-group__head">
          <text class="fa-strong pl-group__title">{{ group.latestTitle }}</text>
          <text class="fa-muted pl-group__meta">{{ group.revisions.length }} 个版本 · {{ group.uuidShort }}</text>
        </view>
        <view
          v-for="row in group.revisions"
          :key="row.id"
          class="pl-row"
          @tap="gotoDetail(row.id)"
        >
          <view class="pl-row__main">
            <text class="fa-strong pl-row__title">{{ row.title }}</text>
            <view class="pl-row__tags">
              <text class="fa-chip" :class="statusChipClass(row.status)">{{ PLAN_STATUS_LABELS[row.status] }}</text>
              <text class="fa-chip">修订 {{ row.revisionNo }}</text>
              <text class="fa-chip">{{ SOURCE_LABELS[row.source] }}</text>
            </view>
            <text class="fa-muted pl-row__date">更新 {{ instantToDisplay(row.updatedAt) }}</text>
          </view>
          <FaIcon class="pl-row__chev" name="chevron" tone="sub" size="40rpx" />
        </view>
      </view>

      <view v-if="hasNext" class="fa-btn fa-btn--block pl-more" @tap="loadMore">
        {{ loadingMore ? '加载中…' : total === null ? `加载更多（已载入 ${rows.length} 条）` : `加载更多（已载入 ${rows.length} / ${total}）` }}
      </view>
      <view v-else-if="rows.length > 0" class="pl-end fa-muted">已加载全部 {{ total === null ? '' : total }} 个版本{{ total === null ? '（条数未知）' : '' }}</view>
    </StateBlock>
  </view>
</template>

<script setup lang="ts">
/**
 * 计划版本列表（docs/p1-api.md §7 `GET /api/plans`）：
 * - 分页 + 按 `planGroupUuid` 分组展示同组多个 `revisionNo`；
 * - 过滤按 `status`（多选逗号分隔，白名单），排序走后端白名单 `updatedAt`/`createdAt`/`revisionNo`；
 * - 每行只读，点击进入详情页做状态机动作；顶部提供「新建 / 导入 / 动作字典」入口。
 * 五态齐全（docs/p1-scope.md §4）；一切数据经 `api/training.ts`，无 mock。
 */
import { computed, ref } from 'vue'
import { detailValue } from '@/utils/events'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import StateBlock from '@/components/StateBlock.vue'
import FaIcon from '@/components/FaIcon.vue'
import FaMoreMenu from '@/components/FaMoreMenu.vue'
import FaSegmented from '@/components/FaSegmented.vue'
import type { MoreItem } from '@/types/ui'
import { fetchPlans } from '@/api/training'
import { pageItems, pageMeta, isSessionExpiredError } from '@/utils/request'
import { toFailureView, briefError, type FailureView } from '@/utils/errorMessage'
import { instantToDisplay } from '@/utils/date'
import { PLAN_STATUS_LABELS, type PlanSource, type PlanStatus, type PlanSummary } from '@/types/training'
import type { PageState } from '@/types/api'

const STATUS_OPTIONS: Array<{ value: PlanStatus; label: string }> = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'ACTIVE', label: '生效中' },
  { value: 'ARCHIVED', label: '已归档' },
  { value: 'DISCARDED', label: '已丢弃' }
]

const SOURCE_LABELS: Record<PlanSource, string> = {
  MANUAL: '手动',
  IMPORT: '导入',
  AI: 'AI'
}

/** 排序白名单（docs/p1-api.md §7）：字段 ∈ {updatedAt,createdAt,revisionNo}，方向 asc/desc。 */
const SORT_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'updatedAt,desc', label: '最近更新优先' },
  { value: 'updatedAt,asc', label: '最久未更新优先' },
  { value: 'createdAt,desc', label: '最新创建优先' },
  { value: 'createdAt,asc', label: '最早创建优先' },
  { value: 'revisionNo,desc', label: '修订号高→低' },
  { value: 'revisionNo,asc', label: '修订号低→高' }
]
const SORT_LABELS = SORT_OPTIONS.map((o) => o.label)

const rows = ref<PlanSummary[]>([])
const total = ref<number | null>(null)
const hasNext = ref(false)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const loadingMore = ref(false)
const failure = ref<FailureView | null>(null)
const loadedOnce = ref(false)

const selectedStatus = ref<PlanStatus[]>([])
const sortIndex = ref(0)

const pageState = computed<PageState>(() => {
  if (failure.value && isSessionExpired(failure.value)) return 'sessionExpired'
  if (loading.value && rows.value.length === 0) return 'loading'
  if (failure.value && rows.value.length === 0) return 'error'
  if (loadedOnce.value && rows.value.length === 0) return 'empty'
  return 'success'
})

const loadingText = computed(() => (loadingMore.value ? '加载中…' : '正在加载训练计划…'))
const emptyTitle = computed(() => (selectedStatus.value.length > 0 ? '没有符合条件的计划' : '还没有训练计划'))
const emptyDesc = computed(() =>
  selectedStatus.value.length > 0
    ? '换一个状态筛选或清除筛选试试；也可以新建或导入一份计划。'
    : '新建一份计划，或把标准模板发到微信聊天后导入。'
)

function isSessionExpired(view: FailureView | null): boolean {
  return view?.sessionExpired === true
}

interface GroupView {
  uuid: string
  uuidShort: string
  latestTitle: string
  revisions: PlanSummary[]
}

const groups = computed<GroupView[]>(() => {
  const order: string[] = []
  const map = new Map<string, PlanSummary[]>()
  rows.value.forEach((row) => {
    const uuid = row.planGroupUuid || row.id
    if (!map.has(uuid)) {
      map.set(uuid, [])
      order.push(uuid)
    }
    map.get(uuid)!.push(row)
  })
  return order.map((uuid) => {
    const revisions = map.get(uuid)!.slice().sort((a, b) => b.revisionNo - a.revisionNo)
    return {
      uuid,
      uuidShort: shortenUuid(uuid),
      latestTitle: revisions[0]?.title ?? '（无标题）',
      revisions
    }
  })
})

function shortenUuid(uuid: string): string {
  if (uuid.length <= 13) return uuid
  return `${uuid.slice(0, 8)}…${uuid.slice(-4)}`
}

function statusChipClass(status: PlanStatus): string {
  if (status === 'ACTIVE') return 'fa-chip--primary'
  if (status === 'DRAFT') return 'fa-chip--warning'
  if (status === 'DISCARDED') return 'fa-chip--danger'
  return ''
}

function toggleStatus(value: PlanStatus): void {
  const i = selectedStatus.value.indexOf(value)
  if (i >= 0) selectedStatus.value.splice(i, 1)
  else selectedStatus.value.push(value)
  reload()
}

// 换排序必须立刻重取：只改 sortIndex 会让标签变成新排序、列表却还是旧顺序
function onSortChange(evt: Event): void {
  const raw = detailValue(evt)
  const idx = Number(Array.isArray(raw) ? raw[0] : raw)
  const next = Number.isFinite(idx) ? idx : 0
  if (next === sortIndex.value) return
  sortIndex.value = next
  reload()
}

function statusParam(): string {
  // 固定顺序，保证同一次筛选的 URL 稳定
  return STATUS_OPTIONS.filter((o) => selectedStatus.value.includes(o.value))
    .map((o) => o.value)
    .join(',')
}

/* 请求代次：筛选/排序触发的 reset 不能被"正在加载"直接丢弃（那会让用户以为筛选生效了），
   而慢回来的旧请求必须作废，否则旧分页会 concat 到新结果后面。 */
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
  try {
    const res = await fetchPlans({
      status: statusParam() || undefined,
      page: page.value,
      size: size.value,
      sort: SORT_OPTIONS[sortIndex.value].value
    })
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
    // 已有数据时（通常是「加载更多」失败）用 toast 提示，不静默吞掉
    if (rows.value.length > 0) uni.showToast({ title: briefError(err), icon: 'none' })
  } finally {
    // 只有当下最新的那次请求才允许收尾，否则旧请求会把新请求的加载态清掉
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

function gotoDetail(id: string): void {
  uni.navigateTo({ url: `/pages/plan/detail?id=${encodeURIComponent(id)}` })
}

function gotoCreate(): void {
  uni.navigateTo({ url: '/pages/plan/edit' })
}

function gotoImport(): void {
  uni.navigateTo({ url: '/pages/plan/import' })
}

function gotoExercises(): void {
  uni.navigateTo({ url: '/pages/plan/exercises' })
}

/** 「更多」只收纳低频入口，动作本身一个都没少（提示词 §5：主操作独立，其余进工具区）。 */
const moreOpen = ref(false)
const MORE_ITEMS: MoreItem[] = [{ key: 'exercises', label: '动作字典', icon: 'dict' }]

/** 训练模块二级结构：计划（本页）/ 记录（历史）/ 统计（当天与本周）。 */
const SECTION_OPTIONS = [
  { value: 'PLAN', label: '计划', icon: 'calendar' as const },
  { value: 'RECORD', label: '记录', icon: 'dumbbell' as const },
  { value: 'STATS', label: '统计', icon: 'chart' as const }
]

function gotoSection(value: string): void {
  if (value === 'RECORD') void uni.navigateTo({ url: '/pages/workout/history' })
  else if (value === 'STATS') void uni.navigateTo({ url: '/pages/workout/stats' })
}

function onMoreSelect(key: string): void {
  if (key === 'exercises') gotoExercises()
}

// 从详情/编辑返回后刷新，避免展示过期的状态与版本（docs/p1-api.md §7：动作后以刷新为准）
onShow(() => {
  reload()
})

onPullDownRefresh(() => {
  void load(true).then(() => uni.stopPullDownRefresh())
})
</script>

<style lang="scss" scoped>
.pl-sections {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  margin-bottom: $fa-space-section;
}

.pl-filters {
  background-color: $fa-surface;
  border: 1px solid $fa-line;
  border-radius: $fa-radius;
  padding: $fa-space-card;
  margin-bottom: $fa-space-section;
}

.pl-chips {
  display: flex;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

.pl-sort {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pl-sort__value {
  flex: 1;
}

.pl-sort__reload {
  flex: 0 0 auto;
  min-width: 68px;
}

.pl-group {
  background-color: $fa-surface;
  border: 1px solid $fa-line;
  border-radius: 10px;
  padding: 10px 12px;
  margin-bottom: 10px;
}

.pl-group__head {
  border-bottom: 1px solid $fa-line;
  padding-bottom: 6px;
  margin-bottom: 6px;
}

.pl-group__title {
  display: block;
  font-size: $fa-font-ui-lg;
}

.pl-group__meta {
  display: block;
  word-break: break-all;
}

.pl-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: $fa-tap;
  padding: 8px 0;
  border-bottom: 1px solid $fa-line;
}

.pl-row:last-child {
  border-bottom: none;
}

.pl-row__main {
  flex: 1;
}

.pl-row__title {
  display: block;
  font-size: $fa-font-ui-lg;
}

.pl-row__tags {
  display: flex;
  flex-wrap: wrap;
  margin-top: 4px;
}

.pl-row__date {
  display: block;
  margin-top: 2px;
}

.pl-row__chev {
  flex: 0 0 auto;
  align-self: center;
}

.pl-more,
.pl-end {
  margin: 8px 0 16px;
  text-align: center;
}

.pl-end {
  padding: 8px;
}
</style>
