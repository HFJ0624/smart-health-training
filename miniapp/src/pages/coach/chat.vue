<template>
  <view class="fa-page cc">
    <!-- ================= 抬头 =================
         入口压成「图标 + 两字」：这页的主体是对话，抬头不能占首屏；
         运行状态同时挂在顶上，正文滚多远都不会丢掉「现在到底在不在跑」。 -->
    <view class="fa-toolbar cc-top">
      <view class="cc-top__main">
        <text class="fa-title cc-top__title">{{ headTitle }}</text>
        <view class="cc-top__meta">
          <text class="fa-chip cc-badge" :class="headChipClass">{{ headStatusText }}</text>
          <text v-if="runId !== ''" class="fa-chip cc-badge cc-badge--strong">{{ activeRunBadge }}</text>
          <text v-if="conversation" class="fa-muted cc-top__time">最近提问 {{ instantToDisplay(conversation.lastMessageAt) }}</text>
        </view>
      </view>
      <view class="fa-toolbar__group">
        <view
          class="cc-top__entry"
          :class="{ 'cc-top__entry--off': loading }"
          hover-class="cc-top__entry--hover"
          @tap="reload"
        >
          <FaIcon name="refresh" tone="accent" size="40rpx" />
          <text class="cc-top__label">{{ loading ? '读取' : '刷新' }}</text>
        </view>
        <view class="cc-top__entry" hover-class="cc-top__entry--hover" @tap="gotoHistory">
          <FaIcon name="records" tone="accent" size="40rpx" />
          <text class="cc-top__label">记录</text>
        </view>
        <view class="cc-top__entry" hover-class="cc-top__entry--hover" @tap="gotoModelSettings">
          <FaIcon name="settings" tone="accent" size="40rpx" />
          <text class="cc-top__label">设置</text>
        </view>
      </view>
    </view>

    <StateBlock
      :state="pageState"
      :failure="failure"
      :submit-failure="submitFailure"
      :loading-text="loadingText"
      empty-title="还没有教练会话"
      empty-desc="问一个问题就会自动创建会话；也可以先建一个空会话再从下方输入。个人数据只由服务端已注册的工具读取。"
      @retry="reload"
    >
      <template #action>
        <view class="fa-btn fa-btn--primary" :class="{ 'fa-btn--disabled': creating }" @tap="startNewConversation">
          <text>{{ creating ? '创建中' : '新建' }}</text>
        </view>
      </template>

      <!-- ================= Fake 模式标注（design §12）：属于「这次的回答能不能信」，不折叠 ================= -->
      <view v-if="fakeBanner" class="fa-banner" :class="fakeBanner.cls">
        <text class="fa-prose">{{ fakeBanner.text }}</text>
      </view>

      <!-- ================= 幂等 / 活动 run 冲突（design §7、§10）：说明为什么没提交，动作留在原位 ================= -->
      <view v-if="conflict" class="fa-banner fa-banner--warn">
        <text class="fa-prose">{{ conflict.message }}</text>
        <view class="fa-btn-row cc-inline">
          <view class="fa-btn cc-inline__btn" @tap="resendConflicted"><text>重发</text></view>
          <view class="fa-btn cc-inline__btn" @tap="dismissConflict"><text>知道了</text></view>
        </view>
      </view>

      <!-- ================= 任务状态读取中断：状态读不到属于「运行状态」，标题与下一步常驻；
             错误码 / traceId 是排查信息，收进次级折叠但一个不减 ================= -->
      <view v-if="runNotice" class="fa-banner fa-banner--warn">
        <text class="fa-prose">{{ runNotice.title }}：{{ runNotice.detail }}</text>
        <view class="fa-btn-row cc-inline">
          <view class="fa-btn cc-inline__btn" @tap="retryRunLookup">
            <FaIcon name="refresh" tone="accent" size="36rpx" />
            <text>重读</text>
          </view>
        </view>
        <view class="cc-fold">
          <view class="cc-fold__head" hover-class="cc-fold__head--hover" @tap="toggleDetail('notice')">
            <FaIcon class="cc-fold__chevron" name="chevron" tone="sub" size="32rpx" />
            <text class="cc-fold__label">错误详情</text>
            <text class="cc-fold__toggle">{{ isOpen('notice') ? '收起' : '展开' }}</text>
          </view>
          <view v-if="isOpen('notice')" class="cc-fold__body">
            <text class="cc-code">错误码：{{ runNotice.code }}</text>
            <text v-if="runNotice.traceId" class="cc-code">traceId：{{ runNotice.traceId }}</text>
            <text v-if="runNotice.retryAfter" class="cc-code">{{ runNotice.retryAfter }} 秒后可重试</text>
          </view>
        </view>
      </view>

      <!-- ================= 消息线程：气泡只留头像 + 正文 + 时间，服务端结构化证据一律进「详情」折叠 ================= -->
      <view v-for="item in thread" :key="item.key" class="cc-msg" :class="`cc-msg--${item.role}`">
        <FaIcon
          class="cc-msg__avatar"
          :name="item.role === 'user' ? 'user' : 'robot'"
          :tone="item.role === 'user' ? 'sub' : 'accent'"
          size="44rpx"
        />
        <view class="cc-msg__body">
          <view class="cc-msg__head">
            <text class="fa-strong cc-msg__who">{{ item.role === 'user' ? '我' : 'AI 教练' }}</text>
            <text v-if="item.time" class="fa-muted cc-msg__time">{{ item.time }}</text>
          </view>
          <text class="cc-msg__text fa-prose">{{ item.text || '（服务端返回的正文为空）' }}</text>

          <view v-if="hasMessageDetail(item)" class="cc-fold">
            <view class="cc-fold__head" hover-class="cc-fold__head--hover" @tap="toggleDetail(item.key)">
              <FaIcon class="cc-fold__chevron" name="chevron" tone="sub" size="32rpx" />
              <text class="cc-fold__label">详情</text>
              <text class="fa-muted cc-fold__count">{{ messageDetailText(item) }}</text>
              <text class="cc-fold__toggle">{{ isOpen(item.key) ? '收起' : '展开' }}</text>
            </view>
            <!-- 展开后仍是服务端原值：来源表 / 记录 ID / 日期 / 规则版本一条都不合并 -->
            <view v-if="isOpen(item.key)" class="cc-fold__body">
              <view v-if="item.factCards.length > 0" class="cc-facts">
                <text class="fa-muted cc-facts__title">事实卡片（数值全部来自服务端按引用组装，模型正文不参与）：</text>
                <CoachFactCard v-for="(f, fi) in item.factCards" :key="`${item.key}-fact-${fi}`" :fact="f" />
              </view>
              <text v-if="item.missingText" class="cc-note cc-note--warn fa-prose">{{ item.missingText }}</text>
              <text v-if="item.staleText" class="cc-note cc-note--warn fa-prose">{{ item.staleText }}</text>
              <text v-if="item.fromRunPayload" class="fa-muted cc-msg__src">
                这条回答取自 run {{ item.runId }} 已持久化的结果，尚未作为会话消息落库。
              </text>
              <text v-else-if="item.runId" class="fa-muted cc-msg__src">任务号 {{ item.runId }}</text>
            </view>
          </view>
        </view>
      </view>

      <!-- ================= 当前任务：状态、进度与「停止」永不折叠；步骤 / 费用 / 停止原因进次级卡 ================= -->
      <view v-if="runId !== ''" class="fa-card cc-run">
        <view class="cc-run__head">
          <text class="fa-strong cc-run__status">任务状态：{{ runStatusText(currentStatus) }}</text>
          <text v-if="runHazard" class="fa-chip cc-badge cc-badge--danger">{{ runHazard }}</text>
        </view>

        <text class="fa-prose cc-run__progress">{{ progressText }}</text>

        <view v-if="!isTerminalRunStatus(currentStatus)" class="cc-run__stop">
          <view class="fa-btn fa-btn--danger cc-run__stopbtn" :class="{ 'fa-btn--disabled': cancelling }" @tap="onCancelRun">
            <FaIcon name="alert" tone="danger" size="36rpx" />
            <text>{{ cancelLabel }}</text>
          </view>
          <text class="fa-muted cc-run__stopnote">
            离开页面或停止轮询都不会取消后台任务；只有这个按钮会调用取消接口。已发出的模型调用不会因取消而免费。
          </text>
        </view>

        <view class="cc-fold">
          <view class="cc-fold__head" hover-class="cc-fold__head--hover" @tap="toggleDetail('run')">
            <FaIcon class="cc-fold__chevron" name="chevron" tone="sub" size="32rpx" />
            <text class="cc-fold__label">运行详情</text>
            <text class="fa-muted cc-fold__count">{{ runDetailText }}</text>
            <text class="cc-fold__toggle">{{ isOpen('run') ? '收起' : '展开' }}</text>
          </view>

          <view v-if="isOpen('run')" class="cc-fold__body">
            <text v-if="serverProgress" class="fa-muted cc-line">服务端进度摘要：{{ serverProgress }}</text>

            <!-- 停止原因不能只有一行灰字：阻塞态要显眼，且能给出下一步动作；错误码必须露出来 -->
            <view v-if="stopReasonLabel" class="fa-banner" :class="stopReasonBlocking ? 'fa-banner--error' : 'fa-banner--warn'">
              <text class="fa-prose">{{ stopReasonLabel }}</text>
              <text v-if="run?.errorCode" class="fa-hint">错误码：{{ run.errorCode }}</text>
              <view v-if="stopActionLabel" class="fa-btn-row cc-run__actions">
                <view v-if="stopActionToSettings" class="fa-btn fa-btn--primary" @tap="gotoModelSettings">
                  <text>{{ stopActionLabel }}</text>
                </view>
                <text v-else class="fa-prose">{{ stopActionLabel }}</text>
              </view>
            </view>

            <view class="cc-blockhead">
              <text class="fa-section-title cc-blockhead__t">执行步骤</text>
              <text v-if="stepsLoading" class="fa-muted cc-blockhead__s">读取中</text>
            </view>
            <RunStepList :steps="steps" :empty-text="stepsEmptyText" />
            <text v-if="stepsNotice" class="cc-note cc-note--warn fa-prose">{{ stepsNotice }}</text>

            <text class="fa-section-title cc-blockhead__t">费用</text>
            <view class="cc-cost">
              <view class="fa-kv">
                <text class="fa-muted">发送前预估</text>
                <text class="cc-cost__v">{{ preSendEstimateText }}</text>
              </view>
              <view class="fa-kv">
                <text class="fa-muted">本次已核算</text>
                <text class="cc-cost__v">{{ costKnownText }}</text>
              </view>
              <view class="fa-kv">
                <text class="fa-muted">存在用量不明</text>
                <text class="cc-cost__v">{{ costUncertainText }}</text>
              </view>
            </view>
            <text v-if="costCurrencyNote" class="fa-muted cc-cost__note">{{ costCurrencyNote }}</text>
            <text v-if="hasUncertainCost" class="cc-note cc-note--warn fa-prose">
              有已发送但用量不明的调用：金额按估算保留在预算里，不会清零，也不会自动重发。
            </text>

            <view v-if="usageTexts.length > 0" class="cc-usage">
              <text class="fa-muted cc-usage__title">用量摘要（服务端原值）：</text>
              <text v-for="(u, ui) in usageTexts" :key="`usage-${ui}`" class="fa-muted cc-usage__line">{{ u }}</text>
            </view>

            <text v-if="runStaleText" class="cc-note cc-note--warn fa-prose">{{ runStaleText }}</text>
          </view>
        </view>
      </view>

      <!-- ================= 发送前预估（确认才提交）：问题原文 + 金额区间 + 确认动作是主界面，
             预算余额 / 凭据 / 假设 / 工具这些明细进次级折叠（预算归模型设置页，不在这里铺开） ================= -->
      <view v-if="pending" class="fa-card cc-est">
        <text class="fa-strong cc-est__title">发送前预估</text>
        <text class="fa-prose cc-est__q">问题：{{ pending.text }}</text>

        <text v-if="pending.estimating" class="fa-muted cc-est__loading">正在向服务端取预估与可用工具…</text>

        <template v-else-if="pending.estimate">
          <text class="cc-est__summary">预估费用：{{ estimateRangeText }}</text>
          <text class="cc-est__summary">最多模型调用：{{ intText(pending.estimate.maxModelCalls) }}</text>

          <text v-if="!credentialUsable" class="cc-note cc-note--warn fa-prose">
            当前没有可用的模型 API Key（凭据状态见「预估详情」）。现在发送不会真正调用模型；请到「我的 · 模型设置」保存 Key。
          </text>

          <view class="cc-fold">
            <view class="cc-fold__head" hover-class="cc-fold__head--hover" @tap="toggleDetail('estimate')">
              <FaIcon class="cc-fold__chevron" name="chevron" tone="sub" size="32rpx" />
              <text class="cc-fold__label">预估详情</text>
              <text class="fa-muted cc-fold__count">{{ estimateDetailText }}</text>
              <text class="cc-fold__toggle">{{ isOpen('estimate') ? '收起' : '展开' }}</text>
            </view>

            <view v-if="isOpen('estimate')" class="cc-fold__body">
              <text class="fa-section-title cc-blockhead__t">费用与预算</text>
              <view class="cc-est__grid">
                <view class="fa-kv">
                  <text class="fa-muted">预估费用区间</text>
                  <text class="cc-est__v">{{ estimateRangeText }}</text>
                </view>
                <view class="fa-kv">
                  <text class="fa-muted">最大模型调用次数</text>
                  <text class="cc-est__v">{{ intText(pending.estimate.maxModelCalls) }}</text>
                </view>
                <view class="fa-kv">
                  <text class="fa-muted">最大工具调用次数</text>
                  <text class="cc-est__v">{{ intText(pending.estimate.maxToolCalls) }}</text>
                </view>
                <view class="fa-kv">
                  <text class="fa-muted">本次任务剩余预算</text>
                  <text class="cc-est__v">{{ formatMoney(pending.estimate.budgetRemainingTask, budgetCurrency) }}</text>
                </view>
                <view class="fa-kv">
                  <text class="fa-muted">当日剩余预算</text>
                  <text class="cc-est__v">{{ formatMoney(pending.estimate.budgetRemainingDaily, budgetCurrency) }}</text>
                </view>
                <view class="fa-kv">
                  <text class="fa-muted">凭据状态</text>
                  <text class="cc-est__v">{{ credentialStatusText(pending.estimate.credentialStatus) }}</text>
                </view>
              </view>

              <view class="cc-est__section">
                <text class="fa-muted cc-est__sectiontitle">预估假设（与执行时的预占算法同源）</text>
                <text v-if="assumptionTexts.length === 0" class="fa-muted">服务端未返回假设说明。</text>
                <text v-for="(a, ai) in assumptionTexts" :key="`asm-${ai}`" class="cc-est__assumption fa-prose">· {{ a }}</text>
              </view>

              <view class="cc-est__section">
                <text class="fa-muted cc-est__sectiontitle">本轮可用工具</text>
                <view v-if="toolNames.length > 0" class="cc-est__chips">
                  <text v-for="(t, ti) in toolNames" :key="`tool-${ti}`" class="fa-chip">{{ toolLabel(t) }}</text>
                </view>
                <text v-else class="fa-muted">服务端未返回可用工具清单。</text>
              </view>
            </view>
          </view>

          <view class="fa-btn-row cc-est__actions">
            <view
              class="fa-btn fa-btn--primary"
              :class="{ 'fa-btn--disabled': pending.sending || !pending.conversationId }"
              @tap="confirmSend"
            >
              <text>{{ pending.sending ? '提交中' : (pending.retryable ? '重试提交' : '确认发送') }}</text>
            </view>
            <view class="fa-btn" @tap="cancelPending"><text>取消</text></view>
          </view>
          <text class="fa-hint cc-est__hint">确认后才提交任务并产生模型调用费用；点「取消」不花一分钱。</text>
          <text v-if="pending.retryable" class="fa-muted cc-est__keynote">
            上一次提交没有成功，重试使用同一个幂等键：{{ pending.key }}（同键同问题只会返回同一个任务，不会重复扣费）。
          </text>
        </template>

        <!-- 预估没拿到的兜底：不让这张卡变成死路，问题文本与幂等键都还在 -->
        <template v-else>
          <text class="fa-muted cc-est__loading">没有拿到发送前预估（错误原文见上方横幅）。</text>
          <view class="fa-btn-row cc-est__actions">
            <view class="fa-btn fa-btn--primary" @tap="retryEstimate"><text>重新预估</text></view>
            <view class="fa-btn" @tap="cancelPending"><text>取消</text></view>
          </view>
        </template>
      </view>

      <!-- ================= 能力边界说明（baseline §3.3）：整段是"为什么有些问题这里答不了"，
             属于次要说明，收成一块；快捷入口与工具的对应关系也写在这里，不占对话区 ================= -->
      <view class="cc-fold cc-note-fold">
        <view class="cc-fold__head" hover-class="cc-fold__head--hover" @tap="toggleDetail('scope')">
          <FaIcon class="cc-fold__chevron" name="shield" tone="sub" size="36rpx" />
          <text class="cc-fold__label">能力说明</text>
          <text class="cc-fold__toggle">{{ isOpen('scope') ? '收起' : '展开' }}</text>
        </view>
        <view v-if="isOpen('scope')" class="cc-fold__body">
          <text class="fa-prose">
            本轮 AI 教练只接入三个真实工具：档案与目标、当天训练计划、体重变化。
            它会先给出费用预估，确认后才提交任务。
          </text>
          <text class="fa-muted cc-line">
            仓库里还没有训练执行记录（打卡）业务，因此「今天完成度如何」这类问题本轮无工具可答，页面不提供该入口。
          </text>
          <text class="fa-muted cc-line">快捷入口对应的数据来源：{{ quickToolNote }}。</text>
          <text class="fa-muted cc-line">
            点「发送」只做一次无成本的预估；确认后才提交任务并轮询状态。个人数值只来自服务端工具结果。
          </text>
          <text class="fa-muted cc-line">预算与供应商余额请在「我的 · 模型设置」查看与调整，本页不重复铺开。</text>
        </view>
      </view>
    </StateBlock>

    <!-- ================= 快捷提问：chip 只留 2–4 字，点下去发的仍是完整问题原文（对应工具见「能力说明」） ================= -->
    <view class="cc-quick">
      <text class="fa-section-title cc-quick__title">快捷提问</text>
      <view class="cc-quick__chips">
        <view
          v-for="q in QUICK_QUESTIONS"
          :key="q.text"
          class="cc-quick__chip"
          :class="{ 'fa-btn--disabled': quickDisabled }"
          hover-class="cc-quick__chip--hover"
          @tap="ask(q.text)"
        >
          <FaIcon class="cc-quick__icon" :name="q.tool ? 'robot' : 'dict'" tone="accent" size="36rpx" />
          <text class="cc-quick__text">{{ q.label }}</text>
        </view>
      </view>
    </view>

    <!-- ================= 正文尾巴：底部操作条是 fixed，不留这段占位最后一句话会被压住 ================= -->
    <view class="fa-bottom-spacer" />

    <!-- ================= 输入区：输入框 + 「发送」固定在底部，安全区交给全局 .fa-bottom-bar ================= -->
    <view class="fa-bottom-bar">
      <view class="cc-bar">
        <view class="cc-bar__row">
          <textarea
            class="fa-textarea cc-bar__input"
            :value="draft"
            placeholder="例如：今天安排练什么？"
            @input="onDraftInput"
          />
          <view class="fa-btn fa-btn--primary cc-bar__send" :class="{ 'fa-btn--disabled': askDisabled }" @tap="ask()">
            <text>{{ sendLabel }}</text>
          </view>
        </view>
        <text class="fa-muted cc-bar__hint">{{ barHint }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * AI 教练会话页（docs/agent-v1-design.md §11、§12）。
 *
 * 关键约束：
 *  1. 快捷问题只由本轮真实存在的三个工具生成（baseline §3.3）；无工具可答的问题不给入口。
 *  2. 提交前必须先 estimate（不调用收费模型），并展示假设与最大调用次数；确认后才 POST runs。
 *  3. 一次只维护**一个**定时器：约 1.5s 起、按稳定状态与错误退避，终态 / 离开页面即停。
 *  4. 幂等键每个问题生成一次，提交失败重试**复用同一个键**；
 *     `IDEMPOTENCY_KEY_CONFLICT` / `RUN_ALREADY_ACTIVE` 显示状态并尝试接管活动 run，绝不静默重问。
 *  5. 重新进入页面：若最新会话有非终态 run 就恢复轮询同一个 runId，并读回服务端持久化结果，不自动重发。
 *  6. 费用区分「发送前预估 / 本次已核算 / 存在用量不明」；低于显示精度写「低于 0.0001 元」，未知不写 0。
 *  7. 客户端停止轮询 ≠ 取消；可见的「停止」按钮才调用 `POST /api/coach/runs/{id}/cancel`。
 *  8. 五态一律走 StateBlock；会话失效由封装层集中处理，这里不吞掉。
 *  9. UI 分层（本轮重排）：这页的主体是对话，常驻的只有「正文 + 真实运行状态 + 停止」；
 *     事实卡 / 执行步骤 / 费用 / 停止原因 / 错误码默认折叠 —— 减层级，不减字段。
 */
import { computed, ref } from 'vue'
import { onHide, onShow, onUnload } from '@dcloudio/uni-app'
import StateBlock from '@/components/StateBlock.vue'
import CoachFactCard from '@/components/CoachFactCard.vue'
import FaIcon from '@/components/FaIcon.vue'
import RunStepList from '@/components/RunStepList.vue'
import { inputValue } from '@/utils/events'
import {
  cancelRun,
  createConversation,
  estimateRun,
  fetchConversationMessages,
  fetchConversations,
  fetchRun,
  fetchRunSteps,
  readListItems,
  submitRun
} from '@/api/coach'
import { isApiError } from '@/utils/request'
import { briefError, toFailureView, type FailureView } from '@/utils/errorMessage'
import { instantToDisplay } from '@/utils/date'
import { uuidV4 } from '@/utils/uuid'
import type { PageState } from '@/types/api'
import { readFieldErrors } from '@/types/api'
import type {
  CoachConversation,
  CoachEstimate,
  CoachMessage,
  CoachThreadEntry,
  RunState,
  RunStep,
  RunSubmitResult
} from '@/types/coach'
import {
  assumptionLines,
  buildCoachThread,
  credentialIsUsable,
  credentialStatusText,
  conversationStatusText,
  fakeModeOf,
  fakeModeText,
  formatMoney,
  isAssistantMessage,
  isTerminalRunStatus,
  moneyOf,
  runStatusText,
  sameCurrency,
  stopReasonText, stopReasonAction, stopReasonIsBlocking,
  stringList,
  toolLabel,
  usageLines
} from '@/types/coach'

/* ==========================================================================
   失败视图
   ========================================================================== */

/**
 * 统一的失败视图：除了 `ApiError.fields`（后端 `data.fields` 形状），
 * 也认后端把逐字段错误当作**裸数组**放在 `data` 下发的形状（`readFieldErrors` 取并集去重）。
 * 「提交失败」态要求带服务端 message 原文 + `data.fields`，两者都在这里交给 StateBlock 渲染。
 */
function viewOf(raw: unknown): FailureView {
  const view = toFailureView(raw)
  view.fields = readFieldErrors(isApiError(raw) ? raw.data : null, view.fields)
  return view
}

/* ==========================================================================
   常量
   ========================================================================== */

/** 轮询基间隔约 1.5 s（design §12「普通 HTTP 轮询」）。 */
const BASE_POLL_MS = 1500
/** 状态一直没变化时逐步退避的上限，避免同一状态反复空转。 */
const MAX_IDLE_POLL_MS = 6000
/** 连续读取失败时的退避上限。 */
const MAX_ERROR_POLL_MS = 15000
/** 连续失败多少次就停止轮询并把错误交回界面（不静默）。 */
const MAX_CONSECUTIVE_POLL_ERRORS = 5
/** 单次前端轮询总时长上限：run 的服务端截止是 120 s，这里给排队留足余量。 */
const MAX_POLL_TOTAL_MS = 300000

const CONVERSATION_LIST_SIZE = 20
const MESSAGE_PAGE_LIMIT = 50

/**
 * 快捷问题严格对应本轮注册表里的三个工具（+ 一个不涉个人数据的常识问题）。
 * `text` 是**实际发送的完整问题**（不改），`label` 只是 chip 上的 2–4 字展示标签。
 */
const QUICK_QUESTIONS: Array<{ text: string; tool: string | null; label: string }> = [
  { text: '今天安排练什么？', tool: 'get_training_plan', label: '今日计划' },
  { text: '最近体重怎么变化？', tool: 'get_weight_trend', label: '体重趋势' },
  { text: '我的档案和目标是什么？', tool: 'get_profile', label: '档案目标' },
  { text: '新手一般怎么安排热身？', tool: null, label: '热身常识' }
]

/* ==========================================================================
   页面状态
   ========================================================================== */

interface PendingQuestion {
  seq: number
  text: string
  /** 每个用户问题只生成一次；提交失败重试必须复用 */
  key: string
  conversationId: string
  estimate: CoachEstimate | null
  estimating: boolean
  sending: boolean
  /** 提交请求本身失败（网络/超时/5xx）：可以带同一个键重试 */
  retryable: boolean
}

interface ConflictState {
  kind: 'IDEMPOTENCY_KEY_CONFLICT' | 'RUN_ALREADY_ACTIVE'
  message: string
  question: string
}

const loading = ref(true)
const loadedOnce = ref(false)
const failure = ref<FailureView | null>(null)
const submitFailure = ref<FailureView | null>(null)

const conversation = ref<CoachConversation | null>(null)
const messages = ref<CoachMessage[]>([])
const creating = ref(false)

const draft = ref('')
let pendingSeq = 0
const pending = ref<PendingQuestion | null>(null)

interface EstimateSnapshot {
  estimate: CoachEstimate
  /** 预估只对「同一个会话 + 同一句问题」有效 */
  question: string
  conversationId: string
  /** 本地时间戳：超过 ESTIMATE_REUSE_MS 就不再复用，重新取一次 */
  takenAtMs: number
}

/** 预估快照：pending 清空后仍用于「发送前预估」的展示，并支持短时间内复用。 */
const estimateSnapshot = ref<EstimateSnapshot | null>(null)
const ESTIMATE_REUSE_MS = 60000

const runId = ref('')
const run = ref<RunState | null>(null)
const submitResult = ref<RunSubmitResult | null>(null)
const steps = ref<RunStep[]>([])
const stepsLoading = ref(false)
const stepsNotice = ref('')
const runNotice = ref<FailureView | null>(null)
const conflict = ref<ConflictState | null>(null)
const cancelling = ref(false)

/* 非响应式：定时器与轮询内部计数（保证「同一时刻只有一个定时器」） */
let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollEpoch = 0
let pollingRunId = ''
let pollInFlight = false
let pollErrors = 0
let idleTicks = 0
let lastSignature = ''
let pollDeadlineAt = 0

/* ==========================================================================
   派生展示
   ========================================================================== */

const currentStatus = computed<string>(() => run.value?.status ?? submitResult.value?.status ?? '')

const showContent = computed<boolean>(
  () =>
    pending.value !== null ||
    runId.value !== '' ||
    submitFailure.value !== null ||
    conflict.value !== null ||
    messages.value.length > 0
)

const pageState = computed<PageState>(() => {
  if (failure.value?.sessionExpired === true) return 'sessionExpired'
  if (showContent.value) return 'success'
  if (loading.value) return 'loading'
  if (failure.value) return 'error'
  if (loadedOnce.value && conversation.value === null) return 'empty'
  return 'success'
})

const loadingText = computed(() => '正在读取最近的教练会话与任务状态…')

const headTitle = computed(() => conversation.value?.title?.trim() || 'AI 教练会话')
const headStatusText = computed(() =>
  conversation.value ? conversationStatusText(conversation.value.status) : '尚未创建会话'
)
const headChipClass = computed(() => {
  const s = (conversation.value?.status || '').toUpperCase()
  if (!s || s === 'ACTIVE') return 'fa-chip--primary'
  return ''
})

const hasActiveRun = computed(() => runId.value !== '' && !isTerminalRunStatus(currentStatus.value))

const quickDisabled = computed(() => hasActiveRun.value || pending.value !== null || loading.value)
const askDisabled = computed(
  () => draft.value.trim().length === 0 || pending.value !== null || hasActiveRun.value || creating.value
)
const askLabel = computed(() => {
  if (pending.value) return '上方有等待确认的提问'
  if (hasActiveRun.value) return '本会话已有进行中的任务'
  return '预估费用并提问'
})

const progressText = computed(() => {
  const s = (currentStatus.value || '').toUpperCase()
  if (s === 'QUEUED') return 'AI 正在排队等待处理…'
  if (s === 'RUNNING') return 'AI 正在查询/思考…'
  return runStatusText(currentStatus.value)
})

const serverProgress = computed<string>(() => {
  const raw = run.value?.progress
  if (raw === null || raw === undefined) return ''
  if (typeof raw === 'string') return raw.trim()
  if (typeof raw === 'object') {
    return Object.keys(raw)
      .map((k) => `${k}：${String((raw as Record<string, unknown>)[k] ?? '')}`)
      .join('，')
  }
  return ''
})

const stepsEmptyText = computed(() => {
  const s = (currentStatus.value || '').toUpperCase()
  if (s === 'QUEUED') return '任务还在排队，服务端尚未产生操作摘要'
  if (s === 'RUNNING') return '正在等待第一条操作摘要…'
  return '该任务没有留下操作摘要'
})

const stopReasonLabel = computed(() => {
  const reason = run.value?.stopReason
  if (!reason) return ''
  const text = stopReasonText(reason)
  return `停止原因：${text}`
})

const stopReasonBlocking = computed(() => stopReasonIsBlocking(run.value?.stopReason))
const stopActionLabel = computed(() => stopReasonAction(run.value?.stopReason))
/** 只有"去配置/改预算"这种真能解决的问题才做按钮，其余只显示文字说明。 */
const stopActionToSettings = computed(() =>
  ['NO_CREDENTIAL', 'CREDENTIAL_INVALID', 'BUDGET_STOPPED'].includes((run.value?.stopReason || '').toUpperCase())
)

function gotoModelSettings(): void {
  uni.navigateTo({ url: '/pages/mine/model-settings' })
}

const budgetCurrency = computed(
  () => activeEstimate()?.budgetCurrency ?? null
)

/** 当前可用于展示的预估：待确认问题自带的预估优先，其次最近一次的预估快照。 */
function activeEstimate(): CoachEstimate | null {
  return pending.value?.estimate ?? estimateSnapshot.value?.estimate ?? null
}

const estimateRangeText = computed(() => {
  const e = pending.value?.estimate ?? null
  if (!e) return '—'
  const low = formatMoney(e.estimatedCostLow, e.currency)
  const high = formatMoney(e.estimatedCostHigh, e.currency)
  if (low === high) return low
  return `${low} – ${high}`
})

const assumptionTexts = computed(() => assumptionLines(activeEstimate()))
const toolNames = computed<string[]>(() => stringList(activeEstimate()?.availableTools))
const credentialUsable = computed(() => {
  const e = activeEstimate()
  if (!e) return true
  return credentialIsUsable(e.credentialStatus)
})

const preSendEstimateText = computed(() => {
  const fromRun = run.value?.estimatedCost
  if (moneyOf(fromRun) !== null) return formatMoney(fromRun, run.value?.currency)
  const fromSubmit = submitResult.value?.estimatedCost
  if (moneyOf(fromSubmit) !== null) return formatMoney(fromSubmit, submitResult.value?.currency)
  const e = activeEstimate()
  if (e && (moneyOf(e.estimatedCostLow) !== null || moneyOf(e.estimatedCostHigh) !== null)) {
    return estimateRangeTextFor(e)
  }
  return '本页未保留发送前的预估值（重新进入页面只读服务端已核算金额）'
})

const costKnownText = computed(() => {
  if (!run.value) return '尚未取得任务状态'
  return formatMoney(run.value.costKnown, run.value.currency)
})

const costUncertainText = computed(() => {
  if (!run.value) return '尚未取得任务状态'
  return formatMoney(run.value.costUncertain, run.value.currency)
})

const hasUncertainCost = computed(() => {
  const n = moneyOf(run.value?.costUncertain)
  return n !== null && n > 0
})

const costCurrencyNote = computed(() => {
  const runCurrency = run.value?.currency ?? submitResult.value?.currency ?? null
  const estCurrency = activeEstimate()?.currency ?? null
  if (runCurrency && estCurrency && !sameCurrency(runCurrency, estCurrency)) {
    return `预估与核算金额币种不同（${estCurrency} / ${runCurrency}），分开列出，不做汇率折算。`
  }
  return '预算与费用金额为应用侧核算口径，不等于供应商账单；供应商余额请在「我的 · 模型设置」单独查看，币种不同时不与预算相加。'
})

const usageTexts = computed(() => usageLines(run.value?.answer?.usageSummary))

const runStaleText = computed(() => {
  if (!run.value || run.value.isStale !== true) return ''
  // 有回答时同样的说明已经挂在对应的那条回答下面，不重复一遍
  if (run.value.answer) return ''
  return '服务端标记本次任务的数据已变更（回答生成后个人数据又有写入），且本次没有可展示的事实快照。'
})

const fakeFlag = computed<boolean | null>(() => {
  const candidates: unknown[] = [run.value, submitResult.value, activeEstimate()]
  for (const payload of candidates) {
    const flag = fakeModeOf(payload)
    if (flag !== null) return flag
  }
  return null
})

const fakeBanner = computed<{ text: string; cls: string } | null>(() => {
  if (fakeFlag.value === true) return { text: `${fakeModeText(true)}`, cls: 'fa-banner--error' }
  if (fakeFlag.value === false) return null
  // 标记缺失：只有在真的有结果可谈时才提示，避免页面顶部一直挂一句无意义的话
  const hasResult = messages.value.some((m) => isAssistantMessage(m.role)) || run.value?.answer != null
  if (!hasResult) return null
  return { text: fakeModeText(null), cls: 'fa-banner--warn' }
})

/** 消息 + 当前 run 的持久化回答（回答没落成消息时才补一条，不重复渲染）。 */
const thread = computed<CoachThreadEntry[]>(() =>
  buildCoachThread(messages.value, {
    displayTime: instantToDisplay,
    runId: runId.value,
    answer: run.value?.answer ?? null,
    runIsStale: run.value?.isStale ?? false,
    runFinishedAt: run.value?.finishedAt ?? null
  })
)

/* ==========================================================================
   折叠层（纯展示）
   ========================================================================== */

/**
 * 「详情」的展开状态：只记 key 不记内容 —— 默认全部收起，让对话正文与运行状态先被看见；
 * 展开后渲染的还是原本摊在页面上的那批服务端字段，一处也没删。
 */
const openDetails = ref<string[]>([])

function isOpen(key: string): boolean {
  return openDetails.value.indexOf(key) >= 0
}

function toggleDetail(key: string): void {
  const at = openDetails.value.indexOf(key)
  if (at >= 0) openDetails.value.splice(at, 1)
  else openDetails.value.push(key)
}

/** 只有 assistant 回答才带服务端结构化证据；用户气泡没有可折叠的东西。 */
function hasMessageDetail(item: CoachThreadEntry): boolean {
  if (item.role !== 'assistant') return false
  return item.factCards.length > 0 || item.missingText.length > 0 || item.staleText.length > 0 || item.runId.length > 0
}

/** 折叠头先交代「里面有什么」，用户不展开也能判断这次值不值得点。 */
function messageDetailText(item: CoachThreadEntry): string {
  const parts: string[] = []
  if (item.factCards.length > 0) parts.push(`事实 ${item.factCards.length}`)
  if (item.missingText.length > 0) parts.push('缺失项')
  if (item.staleText.length > 0) parts.push('数据已变')
  if (item.fromRunPayload) parts.push('未落库')
  else if (item.runId.length > 0) parts.push('任务号')
  return parts.join(' · ')
}

/** 收起态也要认得出「这次出了什么问题」：结论放两字徽标，细节留在折叠里（形状由 .cc-badge--danger 的描边补）。 */
const runHazard = computed(() => {
  const reason = (run.value?.stopReason || '').toUpperCase()
  if (stopReasonBlocking.value) return '受阻'
  if (reason === 'CANCELLED') return '已取消'
  if (!reason || reason === 'COMPLETED') return ''
  return '已停止'
})

/** 顶栏的运行状态短标签：正文滚多远都不会丢掉「现在到底在不在跑」。 */
const activeRunBadge = computed(() => {
  const s = (currentStatus.value || '').toUpperCase()
  if (s === 'QUEUED') return '排队中'
  if (s === 'RUNNING') return '进行中'
  if (stopReasonBlocking.value) return '受阻'
  if (s === 'SUCCEEDED') return '已完成'
  if (s === 'CANCELLED') return '已取消'
  return runStatusText(currentStatus.value)
})

const runDetailText = computed(() => {
  const parts: string[] = [`步骤 ${steps.value.length}`]
  if (serverProgress.value) parts.push('进度摘要')
  if (stopReasonLabel.value) parts.push('停止原因')
  parts.push('费用')
  if (usageTexts.value.length > 0) parts.push(`用量 ${usageTexts.value.length}`)
  return parts.join(' · ')
})

const estimateDetailText = computed(
  () => `预算凭据 · 假设 ${assumptionTexts.value.length} · 工具 ${toolNames.value.length}`
)

/** 发送按钮只留两字：「为什么现在按不了」挪到按钮上方一行，长解释仍在上方卡片里。 */
const sendLabel = computed(() => {
  if (pending.value) return '待确认'
  if (hasActiveRun.value) return '任务中'
  return '发送'
})

const barHint = computed(() => (pending.value || hasActiveRun.value ? askLabel.value : '向 AI 教练提问'))

/** chip 标签压到 2–4 字之后，入口与工具的对应关系挪进「能力说明」，信息不丢。 */
const quickToolNote = computed(() =>
  QUICK_QUESTIONS.map((q) => `${q.label}：${q.tool ? toolLabel(q.tool) : '常识问题，不读个人数据'}`).join('；')
)

function estimateRangeTextFor(e: CoachEstimate): string {
  const low = formatMoney(e.estimatedCostLow, e.currency)
  const high = formatMoney(e.estimatedCostHigh, e.currency)
  return low === high ? low : `${low} – ${high}`
}

function intText(value: number | null | undefined): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '服务端未提供'
  return `${value} 次`
}

/* ==========================================================================
   消息 / 会话读取
   ========================================================================== */

function sortMessages(list: CoachMessage[]): CoachMessage[] {
  return list
    .map((m, index) => ({ m, index }))
    .sort((a, b) => {
      const an = typeof a.m.messageNo === 'number' ? a.m.messageNo : Number.MAX_SAFE_INTEGER
      const bn = typeof b.m.messageNo === 'number' ? b.m.messageNo : Number.MAX_SAFE_INTEGER
      if (an !== bn) return an - bn
      return a.index - b.index
    })
    .map((row) => row.m)
}

function messageKeyOf(m: CoachMessage): string {
  const id = (m.id ?? '').trim()
  return id.length > 0 ? `id:${id}` : `no:${m.messageNo}:${(m.role ?? '').toLowerCase()}`
}

function maxMessageNo(list: CoachMessage[]): number | undefined {
  const numbers = list.map((m) => m.messageNo).filter((n) => typeof n === 'number' && Number.isFinite(n))
  if (numbers.length === 0) return undefined
  return numbers.reduce((acc, n) => (n > acc ? n : acc), numbers[0])
}

async function loadMessages(reset: boolean): Promise<void> {
  const conv = conversation.value
  if (!conv) return
  const after = reset ? undefined : maxMessageNo(messages.value)
  const list = await fetchConversationMessages(conv.id, after, MESSAGE_PAGE_LIMIT)
  if (reset) {
    messages.value = sortMessages(list)
    return
  }
  const map = new Map<string, CoachMessage>()
  messages.value.forEach((m) => map.set(messageKeyOf(m), m))
  list.forEach((m) => map.set(messageKeyOf(m), m))
  messages.value = sortMessages(Array.from(map.values()))
}

function pickCurrentConversation(list: CoachConversation[]): CoachConversation | null {
  if (list.length === 0) return null
  const stampOf = (c: CoachConversation): number => {
    const t = Date.parse(c.lastMessageAt ?? '')
    return Number.isNaN(t) ? 0 : t
  }
  const sorted = list.slice().sort((a, b) => stampOf(b) - stampOf(a))
  const active = sorted.find((c) => (c.status || '').toUpperCase() !== 'ARCHIVED')
  return active ?? sorted[0]
}

async function loadAll(): Promise<void> {
  if (loading.value && loadedOnce.value) return
  loading.value = true
  failure.value = null
  try {
    const page = await fetchConversations({ page: 1, size: CONVERSATION_LIST_SIZE })
    const target = pickCurrentConversation(readListItems<CoachConversation>(page))
    if (!target) {
      conversation.value = null
      messages.value = []
      loadedOnce.value = true
      return
    }
    conversation.value = target
    await loadMessages(true)
    loadedOnce.value = true
    await resumeLatestRun()
  } catch (err) {
    const view = viewOf(err)
    if (view.sessionExpired) {
      stopPolling()
      failure.value = view
      loadedOnce.value = true
      return
    }
    if (messages.value.length > 0) {
      // 已有内容时不覆盖界面，但仍然把服务端错误原文告知用户
      uni.showToast({ title: briefError(err), icon: 'none' })
      failure.value = null
    } else {
      failure.value = view
    }
    loadedOnce.value = messages.value.length > 0
  } finally {
    loading.value = false
  }
}

/** 需求 5：重新进入时，若最新一条带 runId 的消息对应非终态 run 就恢复轮询同一个 runId。 */
async function resumeLatestRun(): Promise<void> {
  const candidate = lastRunIdInMessages()
  if (!candidate) return
  await adoptRun(candidate)
}

/** 取「`messageNo` 最大的那条带 runId 的消息」——也就是最近一次任务。 */
function lastRunIdInMessages(): string {
  let bestNo = -1
  let bestId = ''
  messages.value.forEach((m) => {
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

async function adoptRun(id: string): Promise<boolean> {
  if (!id) return false
  try {
    const state = await fetchRun(id)
    runId.value = id
    run.value = state
    if (submitResult.value && submitResult.value.runId !== id) submitResult.value = null
    runNotice.value = null
    await loadSteps(id)
    if (!isTerminalRunStatus(state.status)) {
      startPolling(id)
      await loadMessagesSafe()
    }
    return true
  } catch (err) {
    const view = viewOf(err)
    if (view.sessionExpired) {
      failure.value = view
      stopPolling()
    } else {
      runNotice.value = view
    }
    return false
  }
}

async function loadSteps(id: string): Promise<void> {
  if (!id) return
  stepsLoading.value = true
  stepsNotice.value = ''
  try {
    const list = await fetchRunSteps(id)
    if (runId.value && runId.value !== id) return
    steps.value = list
  } catch (err) {
    steps.value = []
    const view = viewOf(err)
    stepsNotice.value = view.detail
      ? `操作摘要读取失败：${view.title}（${view.detail}，错误码 ${view.code}）`
      : `操作摘要读取失败：${view.title}（错误码 ${view.code}）`
  } finally {
    stepsLoading.value = false
  }
}

/* ==========================================================================
   轮询：一次只有一个定时器
   ========================================================================== */

function clearTimer(): void {
  if (pollTimer !== null) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function stopPolling(): void {
  pollEpoch += 1
  clearTimer()
  pollingRunId = ''
  pollInFlight = false
  pollErrors = 0
  idleTicks = 0
  lastSignature = ''
}

function scheduleNext(delayMs: number, id: string): void {
  // 先清掉既有定时器：任何时刻最多只有一个待触发的轮询
  clearTimer()
  const epoch = pollEpoch
  pollTimer = setTimeout(() => {
    pollTimer = null
    if (epoch !== pollEpoch) return
    if (pollingRunId !== id) return
    void pollTick(id)
  }, Math.max(250, delayMs))
}

function startPolling(id: string): void {
  if (!id) return
  if (pollingRunId === id && (pollTimer !== null || pollInFlight)) return
  stopPolling()
  pollingRunId = id
  pollErrors = 0
  idleTicks = 0
  lastSignature = ''
  pollDeadlineAt = Date.now() + MAX_POLL_TOTAL_MS
  scheduleNext(0, id)
}

function idleDelay(): number {
  const grown = BASE_POLL_MS * Math.pow(1.5, idleTicks)
  return Math.min(Math.round(grown), MAX_IDLE_POLL_MS)
}

async function pollTick(id: string): Promise<void> {
  if (!id || pollingRunId !== id || pollInFlight) return
  pollInFlight = true
  try {
    const state = await fetchRun(id)
    if (pollingRunId !== id) return
    pollErrors = 0
    run.value = state
    runNotice.value = null

    if (isTerminalRunStatus(state.status)) {
      stopPolling()
      await loadSteps(id)
      await loadMessagesSafe()
      return
    }

    if ((state.status || '').toUpperCase() === 'RUNNING') await loadSteps(id)

    if (Date.now() > pollDeadlineAt) {
      stopPolling()
      runNotice.value = pollTimeoutView()
      return
    }

    const signature = `${state.status}|${stopReasonLabel.value}|${steps.value.length}`
    if (signature === lastSignature) idleTicks += 1
    else {
      idleTicks = 0
      lastSignature = signature
    }
    scheduleNext(idleDelay(), id)
  } catch (err) {
    if (pollingRunId !== id) return
    const view = viewOf(err)
    if (view.sessionExpired) {
      stopPolling()
      failure.value = view
      return
    }
    pollErrors += 1
    if (pollErrors >= MAX_CONSECUTIVE_POLL_ERRORS) {
      stopPolling()
      runNotice.value = view
      return
    }
    const backoff = Math.min(BASE_POLL_MS * Math.pow(2, pollErrors), MAX_ERROR_POLL_MS)
    scheduleNext(backoff, id)
  } finally {
    pollInFlight = false
  }
}

async function loadMessagesSafe(): Promise<void> {
  try {
    await loadMessages(false)
  } catch (err) {
    const view = viewOf(err)
    if (view.sessionExpired) {
      failure.value = view
      return
    }
    uni.showToast({ title: briefError(err), icon: 'none' })
  }
}

function pollTimeoutView(): FailureView {
  return {
    title: '任务状态读取超时',
    detail:
      '已停止轮询：超过 5 分钟没有拿到终态。停止轮询不等于取消，后台任务仍在继续；可用「停止」按钮真正取消，或重新读取状态。',
    code: 'POLL_TIMEOUT',
    traceId: null,
    fields: [],
    importErrors: [],
    importWarnings: [],
    sessionExpired: false,
    versionConflict: false,
    invalidTransition: false,
    activePlanExists: false,
    retryAfter: null
  }
}

function retryRunLookup(): void {
  const id = runId.value
  if (!id) {
    void loadAll()
    return
  }
  runNotice.value = null
  void adoptRun(id)
}

/* ==========================================================================
   提问 → 预估 → 提交
   ========================================================================== */

function onDraftInput(event: Event): void {
  draft.value = inputValue(event)
}

function ask(preset?: unknown): void {
  const fromPreset = typeof preset === 'string' ? preset.trim() : ''
  const text = (fromPreset || draft.value).trim()
  if (!text) {
    uni.showToast({ title: '请先输入问题', icon: 'none' })
    return
  }
  if (failure.value?.sessionExpired) {
    uni.showToast({ title: '登录已失效，请先重新登录', icon: 'none' })
    return
  }
  if (pending.value) {
    uni.showToast({ title: '上方已有等待确认的提问', icon: 'none' })
    return
  }
  if (hasActiveRun.value) {
    uni.showToast({ title: '本会话已有进行中的任务，先停止或等待完成', icon: 'none' })
    return
  }
  draft.value = ''
  conflict.value = null
  void beginQuestion(text)
}

async function beginQuestion(text: string): Promise<void> {
  submitFailure.value = null
  conflict.value = null
  const seq = ++pendingSeq
  // 幂等键在此刻生成，之后本次提问的所有提交重试都复用同一个值
  pending.value = {
    seq,
    text,
    key: uuidV4(),
    conversationId: conversation.value?.id ?? '',
    estimate: null,
    estimating: true,
    sending: false,
    retryable: false
  }
  try {
    if (!pending.value.conversationId) {
      const created = await ensureConversation()
      if (pending.value?.seq !== seq) return
      pending.value.conversationId = created.id
    }
    const conversationId = pending.value.conversationId
    const reusable = reusableEstimate(conversationId, text)
    if (reusable) {
      pending.value.estimate = reusable
      submitFailure.value = null
      return
    }
    const est = await estimateRun(conversationId, text)
    if (pending.value?.seq !== seq) return
    pending.value.estimate = est
    estimateSnapshot.value = { estimate: est, question: text, conversationId, takenAtMs: Date.now() }
    submitFailure.value = null
  } catch (err) {
    if (pending.value?.seq === seq) pending.value = null
    // 预估失败不把用户的问题弄丢：放回输入框，错误原文由 submitFailure 横幅呈现
    if (draft.value.trim().length === 0) draft.value = text
    handleSubmitError(err)
  } finally {
    if (pending.value?.seq === seq) pending.value.estimating = false
  }
}

/**
 * 「或复用一份新鲜的预估」：同一会话 + 同一句问题、且取用于 60 秒内时不再重复请求预估。
 * 预估本身不调用收费模型，但它同样依赖服务端当时的预算与凭据状态，所以只短距离复用。
 */
function reusableEstimate(conversationId: string, question: string): CoachEstimate | null {
  const snapshot = estimateSnapshot.value
  if (!snapshot) return null
  if (snapshot.conversationId !== conversationId || snapshot.question !== question) return null
  if (Date.now() - snapshot.takenAtMs > ESTIMATE_REUSE_MS) return null
  return snapshot.estimate
}

/** 预估没拿到时在原位重试：问题文本与幂等键都不重新生成（幂等键只在提交那一步被消费）。 */
async function retryEstimate(): Promise<void> {
  const p = pending.value
  if (!p || p.estimating || !p.conversationId) return
  const seq = p.seq
  patchPending((x) => {
    x.estimating = true
  })
  submitFailure.value = null
  try {
    const est = await estimateRun(p.conversationId, p.text)
    if (pending.value?.seq !== seq) return
    if (pending.value) {
      pending.value.estimate = est
    }
    estimateSnapshot.value = { estimate: est, question: p.text, conversationId: p.conversationId, takenAtMs: Date.now() }
  } catch (err) {
    handleSubmitError(err)
  } finally {
    if (pending.value?.seq === seq) pending.value.estimating = false
  }
}

async function ensureConversation(): Promise<CoachConversation> {
  creating.value = true
  try {
    const created = await createConversation()
    conversation.value = created
    messages.value = []
    runId.value = ''
    run.value = null
    submitResult.value = null
    steps.value = []
    stopPolling()
    return created
  } finally {
    creating.value = false
  }
}

function patchPending(mutator: (p: PendingQuestion) => void): void {
  const p = pending.value
  if (!p) return
  mutator(p)
}

async function confirmSend(): Promise<void> {
  const snapshot = pending.value
  if (!snapshot || snapshot.estimating || snapshot.sending || !snapshot.conversationId) return
  const item = snapshot
  patchPending((p) => {
    p.sending = true
  })
  submitFailure.value = null
  try {
    const res = await submitRun(item.conversationId, item.text, item.key)
    submitResult.value = res
    runId.value = res.runId
    run.value = null
    steps.value = []
    runNotice.value = null
    conflict.value = null
    pending.value = null
    await loadMessagesSafe()
    startPolling(res.runId)
  } catch (err) {
    const view = viewOf(err)
    submitFailure.value = view
    if (view.sessionExpired) {
      failure.value = view
      pending.value = null
      stopPolling()
      return
    }
    const code = (view.code || '').toUpperCase()
    if (code === 'IDEMPOTENCY_KEY_CONFLICT') {
      conflict.value = {
        kind: 'IDEMPOTENCY_KEY_CONFLICT',
        message:
          '这个幂等键已经被另一个问题用过（后端不会把另一个问题的结果当作本次回答）。你的提问没有提交；确认后可以作为一个新问题重新发送。',
        question: item.text
      }
      pending.value = null
      return
    }
    if (code === 'RUN_ALREADY_ACTIVE') {
      const adopted = await adoptActiveRun(err)
      conflict.value = {
        kind: 'RUN_ALREADY_ACTIVE',
        message: adopted
          ? '本会话已有一个进行中的任务，已为你切换到该任务的进度。你的这个问题没有被重复提交，等它结束后再发送即可。'
          : '本会话已有一个进行中的任务，但页面没能定位到它。你的这个问题没有提交；可以稍后刷新，或确认后作为新问题发送。',
        question: item.text
      }
      pending.value = null
      return
    }
    // 传输层失败 / 超时 / 服务端错误：保留同一个幂等键，交给用户显式重试
    patchPending((p) => {
      p.sending = false
      p.retryable = true
    })
  }
}

/** `RUN_ALREADY_ACTIVE`：优先用错误体里的 runId，其次用最近一条带 runId 的消息。 */
async function adoptActiveRun(err: unknown): Promise<boolean> {
  const fromError = runIdFromError(err)
  const candidates = [fromError, lastRunIdInMessages()].filter(
    (id, index, all) => id.length > 0 && all.indexOf(id) === index
  )
  for (const id of candidates) {
    try {
      const state = await fetchRun(id)
      if (isTerminalRunStatus(state.status)) continue
      runId.value = id
      run.value = state
      submitResult.value = null
      runNotice.value = null
      await loadSteps(id)
      startPolling(id)
      return true
    } catch {
      /* 换下一个候选；都不成立时由 conflict 文案如实说明 */
    }
  }
  return false
}

function runIdFromError(err: unknown): string {
  if (!isApiError(err)) return ''
  const data = err.data
  if (!data || typeof data !== 'object' || Array.isArray(data)) return ''
  const record = data as Record<string, unknown>
  for (const key of ['runId', 'activeRunId', 'currentRunId', 'id']) {
    const value = record[key]
    if (typeof value === 'string' && value.trim().length > 0) return value.trim()
    if (typeof value === 'number' && Number.isFinite(value)) return String(value)
  }
  return ''
}

function cancelPending(): void {
  pending.value = null
  submitFailure.value = null
}

function resendConflicted(): void {
  const question = conflict.value?.question ?? ''
  conflict.value = null
  if (!question) return
  if (hasActiveRun.value) {
    uni.showToast({ title: '本会话已有进行中的任务，先停止或等待完成', icon: 'none' })
    draft.value = question
    return
  }
  draft.value = ''
  void beginQuestion(question)
}

function dismissConflict(): void {
  conflict.value = null
}

/* ==========================================================================
   取消（真正调用后端）
   ========================================================================== */

const cancelLabel = computed(() => {
  if (cancelling.value) return '正在请求停止…'
  if (cancelRequested.value) return '已请求停止，等待后台确认'
  return '停止'
})

const cancelRequested = computed(() => (run.value?.status || '').toUpperCase() === 'CANCELLED')

async function onCancelRun(): Promise<void> {
  const id = runId.value
  if (!id || cancelling.value) return
  if (isTerminalRunStatus(currentStatus.value)) {
    uni.showToast({ title: '任务已结束，无需取消', icon: 'none' })
    return
  }
  cancelling.value = true
  submitFailure.value = null
  try {
    const state = await cancelRun(id)
    if (state && typeof state.status === 'string' && state.status) {
      run.value = state
      if (isTerminalRunStatus(state.status)) {
        stopPolling()
        await loadSteps(id)
        await loadMessagesSafe()
      }
    }
    uni.showToast({ title: '已请求停止该任务', icon: 'none' })
  } catch (err) {
    const view = viewOf(err)
    submitFailure.value = view
    if (view.sessionExpired) failure.value = view
  } finally {
    cancelling.value = false
  }
}

/* ==========================================================================
   其它入口
   ========================================================================== */

function startNewConversation(): void {
  if (creating.value) return
  void (async () => {
    try {
      await ensureConversation()
      loadedOnce.value = true
      failure.value = null
      submitFailure.value = null
      conflict.value = null
      uni.showToast({ title: '已新建会话', icon: 'none' })
    } catch (err) {
      const view = viewOf(err)
      submitFailure.value = view
      if (view.sessionExpired) failure.value = view
    }
  })()
}

function reload(): void {
  if (loading.value) return
  if (!loadedOnce.value) {
    void loadAll()
    return
  }
  void (async () => {
    await loadMessagesSafe()
    if (runId.value && !isTerminalRunStatus(currentStatus.value)) startPolling(runId.value)
  })()
}

function gotoHistory(): void {
  uni.navigateTo({ url: '/pages/coach/history' })
}

/* ==========================================================================
   生命周期
   ========================================================================== */

onShow(() => {
  if (!loadedOnce.value) {
    void loadAll()
    return
  }
  // 从其它页面返回：补一次增量消息，并在仍有活动 run 时恢复同一个 runId 的轮询
  void loadMessagesSafe()
  if (runId.value && !isTerminalRunStatus(currentStatus.value)) startPolling(runId.value)
})

// 离开页面只停止轮询，绝不冒充取消（design §10）
onHide(() => {
  stopPolling()
})

onUnload(() => {
  stopPolling()
})

function handleSubmitError(err: unknown): void {
  const view = viewOf(err)
  submitFailure.value = view
  if (view.sessionExpired) failure.value = view
}
</script>

<style lang="scss" scoped>
/* ---------- 顶部：图标 + 两字入口；运行状态常驻在抬头 ---------- */
.cc-top {
  align-items: flex-start;
}

.cc-top__main {
  flex: 1;
  min-width: 0;
}

.cc-top__title {
  display: block;
}

.cc-top__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8rpx;
  margin-top: 8rpx;
}

/* 长日期单独占一行：不让"最近提问"把右侧图标入口挤走 */
.cc-top__time {
  flex: 1 0 100%;
  font-size: $fa-font-ui;
}

/* 入口边界要能单独认出来，所以用 control-border 而不是只做装饰的 $fa-line */
.cc-top__entry {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4rpx;
  min-width: $fa-tap;
  min-height: $fa-tap;
  padding: 8rpx 12rpx;
  border: 1px solid $fa-control-border;
  border-radius: $fa-radius-sm;
  background-color: $fa-surface;
}

.cc-top__entry--hover {
  background-color: $fa-highlight;
}

.cc-top__entry--off {
  opacity: 0.55;
}

.cc-top__label {
  font-size: $fa-font-ui;
  font-weight: 600;
  color: $fa-accent;
}

/* 状态徽标：底色之外必须有描边/加粗，取反或色觉差异下仍然分得出"这是危险态" */
.cc-badge {
  border: 1px solid $fa-control-border;
  font-weight: 600;
  margin-bottom: 0;
}

.cc-badge--strong {
  background-color: $fa-highlight;
  color: $fa-accent;
}

.cc-badge--danger {
  background-color: $fa-danger-weak;
  border-color: $fa-danger;
  color: $fa-danger;
}

.cc-inline {
  margin-top: 8rpx;
}

.cc-inline__btn {
  flex: 0 1 auto;
  min-width: 176rpx;
}

/* ---------- 次级折叠：技术信息默认收起，展开后一条不减 ----------
   折叠头自带描边 + 「展开/收起」文字：折叠状态不能只靠一个颜色深浅表达。 */
.cc-fold {
  margin-top: $fa-space-tight;
  border: 1px solid $fa-control-border;
  border-radius: $fa-radius-sm;
  background-color: $fa-page-bg;
  overflow: hidden;
}

.cc-fold__head {
  display: flex;
  align-items: center;
  gap: 12rpx;
  min-height: $fa-tap;
  padding: 8rpx 20rpx;
}

.cc-fold__head--hover {
  background-color: $fa-surface;
}

.cc-fold__chevron {
  flex: 0 0 auto;
}

.cc-fold__label {
  flex: 0 0 auto;
  font-size: $fa-font-ui;
  font-weight: 600;
  color: $fa-accent;
}

.cc-fold__count {
  flex: 1;
  min-width: 0;
  word-break: break-all;
}

.cc-fold__toggle {
  /* 没有数量说明时也要贴到右边：折叠头两端对齐，一眼看出这是可点的 */
  flex: 0 0 auto;
  margin-left: auto;
  font-size: $fa-font-ui;
  font-weight: 600;
  color: $fa-accent;
}

.cc-fold__body {
  padding: $fa-space-tight 20rpx 20rpx;
  border-top: 1px solid $fa-line;
}

.cc-code,
.cc-line {
  display: block;
  margin-top: 8rpx;
  font-size: $fa-font-ui;
  color: $fa-text-mute;
  word-break: break-all;
}

/* ---------- 消息气泡：头像 + 正文是对话本体，服务端证据收进「详情」 ---------- */
.cc-msg {
  display: flex;
  align-items: flex-start;
  gap: $fa-space-tight;
  margin-bottom: $fa-space-section;
}

.cc-msg__avatar {
  flex: 0 0 auto;
  margin-top: 8rpx;
}

.cc-msg__body {
  flex: 1;
  min-width: 0;
  padding: $fa-space-card;
  border: 1px solid $fa-line;
  border-radius: $fa-radius;
  background-color: $fa-surface;
}

/* 说话人先看位置再看头像：用户气泡反向排列并换奶油底，不靠一条边框色承载 */
.cc-msg--user {
  flex-direction: row-reverse;
}

.cc-msg--user .cc-msg__body {
  background-color: $fa-page-bg;
}

.cc-msg--user .cc-msg__head {
  flex-direction: row-reverse;
}

.cc-msg--assistant .cc-msg__body {
  border-left: 6rpx solid $fa-primary;
}

.cc-msg__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: $fa-space-tight;
  margin-bottom: 8rpx;
}

.cc-msg__who {
  font-size: $fa-font-ui;
}

.cc-msg__time {
  font-size: $fa-font-ui;
}

.cc-msg__text {
  display: block;
  white-space: pre-wrap;
  word-break: break-word;
}

.cc-msg__src {
  display: block;
  margin-top: 8rpx;
  word-break: break-all;
}

.cc-facts {
  margin-top: 8rpx;
}

.cc-facts__title {
  display: block;
  margin-bottom: 8rpx;
}

.cc-note {
  display: block;
  margin-top: 8rpx;
}

.cc-note--warn {
  color: $fa-warning;
}

/* ---------- 当前任务：状态与「停止」在折叠之外，其余技术信息在折叠之内 ---------- */
.cc-run {
  border-left: 6rpx solid $fa-primary;
}

.cc-run__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: $fa-space-tight;
  margin-bottom: 8rpx;
}

.cc-run__status {
  font-size: $fa-font-ui-lg;
}

.cc-run__progress {
  display: block;
}

.cc-run__stop {
  margin-top: $fa-space-tight;
}

.cc-run__stopbtn {
  width: 100%;
}

.cc-run__stopnote {
  display: block;
  margin-top: 8rpx;
}

.cc-run__actions {
  margin-top: 8rpx;
}

.cc-blockhead {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.cc-blockhead__t {
  margin: $fa-space-tight 0 8rpx;
}

.cc-blockhead__s {
  font-size: $fa-font-ui;
}

.cc-cost {
  margin-top: 8rpx;
}

.cc-cost__v {
  font-size: $fa-font-ui;
  color: $fa-text;
  text-align: right;
  flex: 1;
  word-break: break-all;
}

.cc-cost__note {
  display: block;
  margin-top: 8rpx;
}

.cc-usage {
  margin-top: $fa-space-tight;
}

.cc-usage__title,
.cc-usage__line {
  display: block;
}

/* ---------- 发送前预估：花钱的决策点，问题原文 / 金额 / 两个按钮常驻，明细折叠 ---------- */
.cc-est__title {
  display: block;
  font-size: $fa-font-ui-lg;
}

.cc-est__q {
  display: block;
  margin-top: 8rpx;
  word-break: break-word;
}

.cc-est__summary {
  display: block;
  margin-top: 8rpx;
  font-size: $fa-font-prose;
  font-weight: 600;
  color: $fa-text;
}

.cc-est__loading {
  display: block;
  margin-top: 8rpx;
}

.cc-est__grid {
  margin-top: 8rpx;
}

.cc-est__v {
  font-size: $fa-font-ui;
  color: $fa-text;
  text-align: right;
  flex: 1;
  word-break: break-all;
}

.cc-est__section {
  margin-top: $fa-space-tight;
}

.cc-est__sectiontitle {
  display: block;
}

.cc-est__assumption {
  display: block;
  color: $fa-text-sub;
}

.cc-est__chips {
  display: flex;
  flex-wrap: wrap;
  margin-top: 8rpx;
}

.cc-est__actions {
  margin-top: $fa-space-tight;
}

.cc-est__hint {
  display: block;
  margin-top: 8rpx;
}

.cc-est__keynote {
  display: block;
  margin-top: 8rpx;
  word-break: break-all;
}

/* ---------- 能力说明：整段是次要说明，收在对话之后 ---------- */
.cc-note-fold {
  margin-top: $fa-space-section;
}

/* ---------- 快捷提问：2–4 字 chip 横向排，点下去发的仍是完整问题原文 ---------- */
.cc-quick {
  margin: $fa-space-section 0 $fa-space-tight;
}

.cc-quick__title {
  margin: 0 0 8rpx;
}

.cc-quick__chips {
  display: flex;
  flex-wrap: wrap;
  gap: $fa-space-tight;
}

.cc-quick__chip {
  display: flex;
  align-items: center;
  gap: 8rpx;
  min-height: $fa-tap;
  padding: 0 24rpx;
  border: 1px solid $fa-control-border;
  border-radius: $fa-radius-sm;
  background-color: $fa-surface;
}

.cc-quick__chip--hover {
  background-color: $fa-highlight;
}

.cc-quick__icon {
  flex: 0 0 auto;
}

.cc-quick__text {
  font-size: $fa-font-ui;
  font-weight: 600;
  color: $fa-accent;
}

/* ---------- 底部操作条：输入框 + 「发送」固定，正文用 .fa-bottom-spacer 让位 ---------- */
.cc-bar {
  flex: 1;
  min-width: 0;
}

.cc-bar__row {
  display: flex;
  align-items: flex-end;
  gap: $fa-space-tight;
}

.cc-bar__input {
  /* 全局 .fa-textarea 带 width:100%，这里用 flex-basis 0 抢回按钮要占的那一段 */
  flex: 1 1 0%;
  width: auto;
  min-width: 0;
  min-height: $fa-btn-h;
  max-height: 176rpx;
  padding: 16rpx 20rpx;
  border: 1px solid $fa-control-border;
  border-radius: $fa-radius-sm;
  background-color: $fa-surface;
  font-size: $fa-font-ui;
  color: $fa-text;
}

.cc-bar__send {
  flex: 0 0 auto;
  min-width: 176rpx;
}

.cc-bar__hint {
  display: block;
  margin-top: 8rpx;
}
</style>
