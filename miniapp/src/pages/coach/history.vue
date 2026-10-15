<template>
  <view class="fa-page ch">
    <!-- ================= 列表模式 ================= -->
    <template v-if="mode === 'list'">
      <!-- 一行说明（不是卡片）：只读口径写清楚，细节归每一行与展开区 -->
      <text class="fa-hint ch-intro">
        服务端已持久化的问答与结果，点开只读查看事实卡片、来源与费用；本页不重跑、不补发模型调用，下拉可重新读取。
      </text>

      <StateBlock
        :state="listState"
        :failure="listFailure"
        loading-text="正在读取历史会话…"
        empty-title="还没有教练会话"
        empty-desc="到「AI 教练」页提一个问题就会创建会话。"
        @retry="reloadList"
      >
        <template #action>
          <view class="fa-btn fa-btn--primary" @tap="backToChat"><text>去提问</text></view>
        </template>

        <!-- 一行一个会话：日期 + 一行摘要 + 状态图形；状态同时给文字，不靠颜色辨认 -->
        <view
          v-for="item in conversations"
          :key="item.id"
          class="fa-card ch-row"
          hover-class="ch-row--hover"
          @tap="openDetail(item)"
        >
          <view class="ch-row__head">
            <view class="ch-row__when">
              <text class="ch-row__date">{{ rowDate(item) }}</text>
              <text v-if="rowTime(item)" class="fa-hint">最近提问 {{ rowTime(item) }}</text>
            </view>
            <view class="ch-row__status">
              <FaIcon :name="convIcon(item.status)" :tone="convTone(item.status)" size="36rpx" />
              <text class="ch-row__status-text">{{ conversationStatusText(item.status) }}</text>
            </view>
          </view>
          <text class="fa-strong ch-row__title">{{ rowTitle(item) }}</text>
        </view>

        <view v-if="hasNext" class="fa-btn fa-btn--block ch-more" :class="{ 'fa-btn--disabled': loadingMore }" @tap="loadMore">
          <text>{{ loadingMore ? '加载中…' : total === null ? `加载更多（已载入 ${conversations.length} 条）` : `加载更多（已载入 ${conversations.length} / ${total}）` }}</text>
        </view>
        <text v-else-if="conversations.length > 0" class="fa-muted ch-end">已加载全部 {{ total === null ? '' : total }} 个会话{{ total === null ? '（条数未知）' : '' }}</text>

        <view class="fa-btn-row ch-foot">
          <view class="fa-btn" @tap="backToChat">
            <FaIcon name="robot" tone="accent" size="40rpx" />
            <text>当前会话</text>
          </view>
        </view>
      </StateBlock>
    </template>

    <!-- ================= 只读查看模式 ================= -->
    <template v-else>
      <view class="fa-card ch-detail__head">
        <view class="ch-detail__top">
          <FaIcon :name="convIcon(detailConversation?.status)" :tone="convTone(detailConversation?.status)" size="44rpx" />
          <text class="fa-title ch-detail__title">{{ rowTitle(detailConversation) }}</text>
          <text class="fa-chip" :class="statusChipClass(detailConversation?.status)">{{ conversationStatusText(detailConversation?.status) }}</text>
        </view>
        <text v-if="detailConversation" class="fa-hint">最近提问 {{ instantToDisplay(detailConversation.lastMessageAt) }}</text>
        <view class="fa-btn-row ch-detail__actions">
          <view class="fa-btn ch-detail__btn" @tap="backToList">
            <FaIcon name="records" tone="accent" size="40rpx" />
            <text>列表</text>
          </view>
          <view class="fa-btn ch-detail__btn" @tap="backToChat">
            <FaIcon name="robot" tone="accent" size="40rpx" />
            <text>当前会话</text>
          </view>
        </view>
      </view>

      <StateBlock
        :state="detailState"
        :failure="detailFailure"
        loading-text="正在读取该会话的消息…"
        empty-title="这个会话还没有消息"
        empty-desc="会话已创建但没有提问记录；回到当前会话即可提问。"
        @retry="reloadDetail"
      >
        <template #action>
          <view class="fa-btn fa-btn--primary" @tap="backToChat"><text>回到当前会话</text></view>
        </template>

        <text class="fa-muted ch-readonly">只读视图：本页不会重新提交任何问题，也不会触发模型调用。</text>

        <view v-for="item in detailThread" :key="item.key" class="ch-msg" :class="`ch-msg--${item.role}`">
          <view class="ch-msg__head">
            <text class="fa-strong ch-msg__who">{{ item.role === 'user' ? '我' : 'AI 教练' }}</text>
            <text v-if="item.time" class="fa-muted ch-msg__time">{{ item.time }}</text>
          </view>
          <text class="ch-msg__text fa-prose">{{ item.text || '（服务端返回的正文为空）' }}</text>

          <view v-if="item.factCards.length > 0" class="ch-facts">
            <text class="fa-muted ch-facts__title">事实卡片（服务端值）：</text>
            <CoachFactCard v-for="(f, fi) in item.factCards" :key="`${item.key}-fact-${fi}`" :fact="f" />
          </view>

          <text v-if="item.missingText" class="ch-note fa-prose">{{ item.missingText }}</text>
          <text v-if="item.staleText" class="ch-note fa-prose">{{ item.staleText }}</text>
          <text v-if="item.fromRunPayload" class="fa-muted ch-msg__src">
            这条回答取自 run {{ item.runId }} 已持久化的结果，尚未作为会话消息落库。
          </text>
        </view>

        <!-- 任务卡：默认只有状态图形 + 状态文字 + 一行费用；runId、停止原因、费用明细、操作摘要收进展开区。
             展开后这些仍然是成行的文字——停止原因与费用都不靠颜色识别。 -->
        <view v-if="detailRunId !== ''" class="fa-card ch-run">
          <view class="ch-run__head">
            <view class="ch-run__badge">
              <FaIcon :name="runIcon" :tone="runTone" size="44rpx" />
              <text class="fa-strong">{{ runStatusText(detailRun?.status) }}</text>
            </view>
            <view class="fa-btn fa-btn--sm" @tap="showRunMeta = !showRunMeta">
              <FaIcon :name="showRunMeta ? 'chevron' : 'records'" tone="accent" size="36rpx" />
              <text>{{ showRunMeta ? '收起' : '任务详情' }}</text>
            </view>
          </view>

          <text class="ch-run__cost">本次已核算 {{ costKnownText }}</text>

          <!-- 真实/Fake 与「仍未结束」是口径提醒，留在默认视野里，不折叠 -->
          <view v-if="detailFakeBanner" class="fa-banner" :class="detailFakeBanner.cls">
            <text class="fa-prose">{{ detailFakeBanner.text }}</text>
          </view>

          <text v-if="detailNotTerminal" class="ch-note fa-prose">
            该任务仍未结束；历史页只读取当前状态、不做轮询。请回到当前会话查看进度或停止它。
          </text>

          <template v-if="showRunMeta">
            <view class="fa-row">
              <text class="fa-row__label">runId</text>
              <text class="fa-row__value ch-run__id">{{ detailRunId }}</text>
            </view>
            <text v-if="detailStopReason" class="ch-note fa-prose">{{ detailStopReason }}</text>
            <text v-if="detailProgress" class="fa-muted ch-run__progress">{{ detailProgress }}</text>

            <text class="fa-section-title ch-run__subtitle">操作摘要</text>
            <RunStepList :steps="detailSteps" empty-text="该任务没有留下操作摘要" />
            <text v-if="detailStepsNotice" class="ch-note fa-prose">{{ detailStepsNotice }}</text>

            <text class="fa-section-title ch-run__subtitle">费用明细</text>
            <view class="fa-kv">
              <text class="fa-muted">本次已核算</text>
              <text class="ch-run__value">{{ costKnownText }}</text>
            </view>
            <view class="fa-kv">
              <text class="fa-muted">存在用量不明</text>
              <text class="ch-run__value">{{ costUncertainText }}</text>
            </view>
            <text v-if="detailHasUncertain" class="ch-note fa-prose">
              有已发送但用量不明的调用：金额按估算保留在预算里，不清零、不重发。
            </text>
          </template>
        </view>

        <view v-if="detailRunNotice" class="fa-banner fa-banner--warn">
          <text class="fa-prose">{{ detailRunNotice.title }}：{{ detailRunNotice.detail }}</text>
          <text class="fa-muted">错误码：{{ detailRunNotice.code }}</text>
        </view>
      </StateBlock>
    </template>
  </view>
</template>

<script setup lang="ts">
/**
 * 历史会话页（docs/agent-v1-design.md §12：`pages/coach/history`）。
 *
 * - 列表：`GET /api/coach/conversations?page=&size=` 分页 + 状态 chip（数组字段是 `items`）；
 * - 只读打开：`GET /api/coach/conversations/{id}/messages` + 最近一个 run 的持久化结果
 *   （`GET /api/coach/runs/{id}` / `…/steps`）。**不轮询、不重发、不重新调用模型**；
 * - 「回到当前会话」跳转到 `pages/coach/chat`，由 chat 负责活动任务的轮询与取消；
 * - 五态经 `StateBlock`；会话失效由 `utils/request.ts` 集中处理，这里只如实呈现。
 *
 * UI 重排：列表行改成「日期 + 一行摘要 + 状态图形」，任务卡的 runId / 停止原因 / 费用明细 / 操作摘要
 * 收进「任务详情」展开区——折叠只挪位置，一条信息都没删，且状态与费用在展开后仍是成行的文字。
 */
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import FaIcon from '@/components/FaIcon.vue'
import StateBlock from '@/components/StateBlock.vue'
import CoachFactCard from '@/components/CoachFactCard.vue'
import RunStepList from '@/components/RunStepList.vue'
import {
  fetchConversations,
  fetchConversationMessages,
  fetchRun,
  fetchRunSteps,
  readListItems
} from '@/api/coach'
import { isApiError, pageMeta } from '@/utils/request'
import { briefError, toFailureView, type FailureView } from '@/utils/errorMessage'
import { instantToDisplay } from '@/utils/date'
import type { FaIconName, FaIconTone } from '@/styles/icon-names'
import type { PageState } from '@/types/api'
import { readFieldErrors } from '@/types/api'
import type {
  CoachConversation,
  CoachMessage,
  CoachThreadEntry,
  RunState,
  RunStep
} from '@/types/coach'
import {
  buildCoachThread,
  conversationStatusText,
  fakeModeOf,
  fakeModeText,
  formatMoney,
  isTerminalRunStatus,
  moneyOf,
  runStatusText,
  stopReasonText
} from '@/types/coach'

const LIST_SIZE = 20
const MESSAGE_LIMIT = 100

/**
 * 统一的失败视图：除 `ApiError.fields`（`data.fields`）外，也认后端把逐字段错误当作裸数组下发在 `data`
 * 的形状（`readFieldErrors` 取并集去重），与 `pages/mine/*` 的做法一致。
 */
function viewOf(raw: unknown): FailureView {
  const view = toFailureView(raw)
  view.fields = readFieldErrors(isApiError(raw) ? raw.data : null, view.fields)
  return view
}

const mode = ref<'list' | 'detail'>('list')

/* ---------- 列表 ---------- */
const conversations = ref<CoachConversation[]>([])
const page = ref(1)
const total = ref<number | null>(null)
const hasNext = ref(false)
const loading = ref(true)
const loadingMore = ref(false)
const loadedOnce = ref(false)
const listFailure = ref<FailureView | null>(null)

const listState = computed<PageState>(() => {
  if (listFailure.value?.sessionExpired === true) return 'sessionExpired'
  if (loading.value && conversations.value.length === 0) return 'loading'
  if (listFailure.value && conversations.value.length === 0) return 'error'
  if (loadedOnce.value && conversations.value.length === 0) return 'empty'
  return 'success'
})

/* ---------- 只读详情 ---------- */
const detailConversation = ref<CoachConversation | null>(null)
const detailMessages = ref<CoachMessage[]>([])
const detailLoading = ref(false)
const detailLoadedOnce = ref(false)
const detailFailure = ref<FailureView | null>(null)
const detailRunId = ref('')
const detailRun = ref<RunState | null>(null)
const detailSteps = ref<RunStep[]>([])
const detailStepsNotice = ref('')
const detailRunNotice = ref<FailureView | null>(null)
/** 纯展示开关：任务卡的 runId / 停止原因 / 费用明细 / 操作摘要默认折叠，切换会话时回到折叠态 */
const showRunMeta = ref(false)

const detailState = computed<PageState>(() => {
  if (detailFailure.value?.sessionExpired === true) return 'sessionExpired'
  if (detailLoading.value && detailMessages.value.length === 0) return 'loading'
  if (detailFailure.value && detailMessages.value.length === 0) return 'error'
  if (detailLoadedOnce.value && detailMessages.value.length === 0) return 'empty'
  return 'success'
})

const detailThread = computed<CoachThreadEntry[]>(() =>
  buildCoachThread(detailMessages.value, {
    displayTime: instantToDisplay,
    runId: detailRunId.value,
    answer: detailRun.value?.answer ?? null,
    runIsStale: detailRun.value?.isStale ?? false,
    runFinishedAt: detailRun.value?.finishedAt ?? null
  })
)

const detailStopReason = computed(() => {
  const reason = detailRun.value?.stopReason
  if (!reason) return ''
  return `停止原因：${stopReasonText(reason)}`
})

const detailProgress = computed(() => {
  const raw = detailRun.value?.progress
  if (typeof raw === 'string' && raw.trim()) return `服务端进度摘要：${raw.trim()}`
  if (raw && typeof raw === 'object') {
    return `服务端进度摘要：${Object.keys(raw)
      .map((k) => `${k}：${String((raw as Record<string, unknown>)[k] ?? '')}`)
      .join('，')}`
  }
  return ''
})

/** 只有真的读到了任务状态、且它不是终态，才说「仍未结束」；读不到状态时由 detailRunNotice 说明。 */
const detailNotTerminal = computed(
  () => detailRunId.value !== '' && detailRun.value !== null && !isTerminalRunStatus(detailRun.value.status)
)

const costKnownText = computed(() => {
  if (!detailRun.value) return '未取得任务状态'
  return formatMoney(detailRun.value.costKnown, detailRun.value.currency)
})

const costUncertainText = computed(() => {
  if (!detailRun.value) return '未取得任务状态'
  return formatMoney(detailRun.value.costUncertain, detailRun.value.currency)
})

const detailHasUncertain = computed(() => {
  const n = moneyOf(detailRun.value?.costUncertain)
  return n !== null && n > 0
})

const detailFakeBanner = computed<{ text: string; cls: string } | null>(() => {
  const flag = fakeModeOf(detailRun.value)
  if (flag === true) return { text: `${fakeModeText(true)}`, cls: 'fa-banner--error' }
  if (flag === false) return null
  const hasAnswer =
    detailThread.value.some((e) => e.role === 'assistant') || (detailRun.value?.answer ?? null) !== null
  if (!hasAnswer) return null
  return { text: fakeModeText(null), cls: 'fa-banner--warn' }
})

/* ==========================================================================
   数据读取
   ========================================================================== */

function rowTitle(item: CoachConversation | null): string {
  if (!item) return '会话'
  const title = (item.title ?? '').trim()
  return title.length > 0 ? title : `会话 ${item.id}`
}

/** 行首日期与时分只是把服务端 `instantToDisplay` 的结果拆开排版，不重新推算时区。 */
function rowDate(item: CoachConversation): string {
  return instantToDisplay(item.lastMessageAt).split(' ')[0]
}

function rowTime(item: CoachConversation): string {
  const parts = instantToDisplay(item.lastMessageAt).split(' ')
  return parts.length === 2 ? parts[1] : ''
}

/* 图形仅做识别辅助：会话/任务状态的文字照旧原样显示，未知取值退回 more + sub，不猜语义。 */
const CONV_ICONS: Record<string, FaIconName> = { ACTIVE: 'target', ARCHIVED: 'records' }

function convIcon(status: string | null | undefined): FaIconName {
  return CONV_ICONS[(status || '').toUpperCase()] ?? 'more'
}

function convTone(status: string | null | undefined): FaIconTone {
  return (status || '').toUpperCase() === 'ACTIVE' ? 'accent' : 'sub'
}

const RUN_ICONS: Record<string, FaIconName> = {
  QUEUED: 'clock',
  RUNNING: 'refresh',
  SUCCEEDED: 'target',
  FAILED: 'alert',
  CANCELLED: 'logout',
  BUDGET_STOPPED: 'wallet',
  LIMIT_STOPPED: 'alert',
  DEADLINE_STOPPED: 'clock',
  WAITING_CREDENTIAL: 'key',
  COST_UNKNOWN: 'wallet'
}

const RUN_TONES: Record<string, FaIconTone> = {
  QUEUED: 'sub',
  RUNNING: 'accent',
  SUCCEEDED: 'success',
  FAILED: 'danger',
  CANCELLED: 'sub',
  BUDGET_STOPPED: 'danger',
  LIMIT_STOPPED: 'sub',
  DEADLINE_STOPPED: 'sub',
  WAITING_CREDENTIAL: 'accent',
  COST_UNKNOWN: 'danger'
}

const runKey = computed(() => (detailRun.value?.status || '').toUpperCase())
const runIcon = computed<FaIconName>(() => RUN_ICONS[runKey.value] ?? 'more')
const runTone = computed<FaIconTone>(() => RUN_TONES[runKey.value] ?? 'sub')

/** 本轮只有 ACTIVE / ARCHIVED 两种真实形态：进行中给主色，其余用默认灰底（未知取值不做猜测）。 */
function statusChipClass(status: string | null | undefined): string {
  const s = (status || '').toUpperCase()
  return s === 'ACTIVE' ? 'fa-chip--primary' : ''
}

/** 消息里最大 `messageNo` 的那条带 runId 的行 —— 也就是这个会话最近一次任务。 */
function lastRunIdOf(list: CoachMessage[]): string {
  let bestNo = -1
  let bestId = ''
  list.forEach((m) => {
    const id = (m.runId ?? '').trim()
    if (!id) return
    const no = typeof m.messageNo === 'number' && Number.isFinite(m.messageNo) ? m.messageNo : -1
    if (no >= bestNo) {
      bestNo = no
      bestId = id
    }
  })
  return bestId
}

async function loadList(reset: boolean): Promise<void> {
  if (reset && loading.value) return
  if (reset) {
    page.value = 1
    loading.value = true
    listFailure.value = null
  } else {
    loadingMore.value = true
  }
  try {
    const res = await fetchConversations({ page: page.value, size: LIST_SIZE })
    const rows = readListItems<CoachConversation>(res)
    conversations.value = reset ? rows : conversations.value.concat(rows)
    const meta = pageMeta(res, page.value, LIST_SIZE)
    total.value = meta.total
    hasNext.value = meta.hasNext
    loadedOnce.value = true
    listFailure.value = null
  } catch (err) {
    const view = viewOf(err)
    listFailure.value = view
    if (view.sessionExpired) loadedOnce.value = true
    // 已有列表时（通常是「加载更多」失败）用 toast 提示，不静默吞掉服务端文案
    if (conversations.value.length > 0 && !view.sessionExpired) {
      uni.showToast({ title: briefError(err), icon: 'none' })
    }
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

function reloadList(): void {
  void loadList(true)
}

function loadMore(): void {
  if (!hasNext.value || loadingMore.value) return
  page.value += 1
  void loadList(false)
}

async function openDetail(item: CoachConversation): Promise<void> {
  mode.value = 'detail'
  detailConversation.value = item
  detailMessages.value = []
  detailRunId.value = ''
  detailRun.value = null
  detailSteps.value = []
  detailStepsNotice.value = ''
  detailRunNotice.value = null
  detailFailure.value = null
  detailLoadedOnce.value = false
  showRunMeta.value = false
  await reloadDetail()
}

async function reloadDetail(): Promise<void> {
  const conv = detailConversation.value
  if (!conv) return
  detailLoading.value = true
  detailFailure.value = null
  try {
    const list = await fetchConversationMessages(conv.id, undefined, MESSAGE_LIMIT)
    detailMessages.value = list
    detailLoadedOnce.value = true
    await loadDetailRun()
  } catch (err) {
    const view = viewOf(err)
    detailFailure.value = view
    if (view.sessionExpired) detailLoadedOnce.value = true
  } finally {
    detailLoading.value = false
  }
}

/** 只读取最近一个 run 的**已持久化**结果；不轮询、不重发（design §10、§12）。 */
async function loadDetailRun(): Promise<void> {
  const id = lastRunIdOf(detailMessages.value)
  if (!id) {
    detailRunId.value = ''
    detailRun.value = null
    detailSteps.value = []
    return
  }
  detailRunId.value = id
  try {
    detailRun.value = await fetchRun(id)
  } catch (err) {
    detailRun.value = null
    detailRunNotice.value = viewOf(err)
  }
  try {
    detailSteps.value = await fetchRunSteps(id)
    detailStepsNotice.value = ''
  } catch (err) {
    detailSteps.value = []
    const view = viewOf(err)
    detailStepsNotice.value = view.detail
      ? `操作摘要读取失败：${view.title}（${view.detail}，错误码 ${view.code}）`
      : `操作摘要读取失败：${view.title}（错误码 ${view.code}）`
  }
}

function backToList(): void {
  mode.value = 'list'
  void loadList(true)
}

function backToChat(): void {
  // 当前会话的轮询与取消都在 chat 页；这里 replace 掉历史页，避免页面栈里堆两层教练页
  uni.redirectTo({ url: '/pages/coach/chat' })
}

onShow(() => {
  // 首次进入与从聊天页返回都重新读一次：会话状态与结果以服务端为准
  if (mode.value === 'list') {
    void loadList(true)
    return
  }
  if (!detailLoadedOnce.value) void reloadDetail()
})

onPullDownRefresh(() => {
  if (mode.value === 'list') {
    void loadList(true).then(() => uni.stopPullDownRefresh())
    return
  }
  void reloadDetail().then(() => uni.stopPullDownRefresh())
})
</script>

<style lang="scss" scoped>
/* 顶部口径说明改成一行 hint，不再是一张卡（列表卡片本身才是主角） */
.ch-intro {
  display: block;
  margin-bottom: $fa-space-section;
}

/* 会话行：一张卡一个落点，日期打头，状态图形与文字同排 */
.ch-row {
  display: flex;
  flex-direction: column;
  gap: $fa-space-tight;
}

.ch-row--hover {
  border-color: $fa-control-border;
}

.ch-row__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: $fa-space-tight;
}

.ch-row__when {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.ch-row__date {
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  color: $fa-text;
}

.ch-row__status {
  display: flex;
  align-items: center;
  gap: 8rpx;
  flex: 0 0 auto;
}

/* 状态由「图形 + 文字」两件事共同表达，颜色只是第三重加强 */
.ch-row__status-text {
  font-size: $fa-font-ui;
  color: $fa-text-sub;
}

.ch-row__title {
  display: block;
  font-size: $fa-font-ui-lg;
  line-height: 1.5;
}

.ch-more,
.ch-end {
  margin: $fa-space-tight 0;
  text-align: center;
}

.ch-end {
  display: block;
  padding: $fa-space-tight 0;
}

.ch-foot {
  margin-top: $fa-space-tight;
}

.ch-detail__head {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.ch-detail__top {
  display: flex;
  align-items: center;
  gap: $fa-space-tight;
}

.ch-detail__title {
  flex: 1;
  min-width: 0;
}

.ch-detail__actions {
  margin-top: $fa-space-tight;
}

.ch-detail__btn {
  flex: 0 1 auto;
  min-width: 200rpx;
}

.ch-readonly {
  display: block;
  margin-bottom: $fa-space-tight;
}

.ch-msg {
  border-radius: $fa-radius;
  padding: $fa-space-card;
  margin-bottom: $fa-space-section;
  border: 1px solid $fa-line;
  background-color: $fa-surface;
}

.ch-msg--user {
  background-color: $fa-page-bg;
}

.ch-msg--assistant {
  border-left: 6rpx solid $fa-primary;
}

.ch-msg__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: $fa-space-tight;
  margin-bottom: 8rpx;
}

.ch-msg__text {
  display: block;
  white-space: pre-wrap;
  word-break: break-word;
}

.ch-msg__src {
  display: block;
  margin-top: 12rpx;
}

.ch-facts {
  margin-top: $fa-space-tight;
}

.ch-facts__title {
  display: block;
  margin-bottom: 12rpx;
}

.ch-note {
  display: block;
  margin-top: 12rpx;
  color: $fa-warning;
}

.ch-run__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $fa-space-tight;
}

.ch-run__badge {
  display: flex;
  align-items: center;
  gap: 12rpx;
  min-width: 0;
}

/* 折叠区外只留这一行费用：写成文字（"本次已核算 …"），不是一个色块 */
.ch-run__cost {
  display: block;
  margin-top: $fa-space-tight;
  font-size: $fa-font-ui;
  color: $fa-text-sub;
}

.ch-run__id {
  word-break: break-all;
}

.ch-run__subtitle {
  margin: $fa-space-tight 0 8rpx;
}

.ch-run__progress {
  display: block;
  margin-top: 8rpx;
}

.ch-run__value {
  font-size: $fa-font-ui;
  color: $fa-text;
  text-align: right;
  flex: 1;
  word-break: break-all;
}
</style>
