<template>
  <view class="fa-page">
    <!-- 载入失败 / 会话失效（还没有拿到任何数据）：整页交给 StateBlock -->
    <StateBlock v-if="!plan" :state="pageState" :failure="failure" loading-text="正在加载计划详情…" @retry="loadPlan" />

    <template v-else>
      <!-- 版本信息：只留读得懂的四项，UUID/lockVersion 这类调试字段收进「版本详情」 -->
      <view class="pd-head">
        <view class="pd-head__top">
          <text class="fa-title pd-head__title">{{ plan.title }}</text>
          <text class="fa-chip pd-chip" :class="statusChipClass(plan.status)">{{ PLAN_STATUS_LABELS[plan.status] }}</text>
        </view>
        <view class="pd-kv"><text class="fa-muted">来源</text><text>{{ SOURCE_LABELS[plan.source] }}</text></view>
        <view class="pd-kv"><text class="fa-muted">版本</text><text>第 {{ plan.revisionNo }} 版</text></view>
        <view class="pd-kv"><text class="fa-muted">起止</text><text>{{ plan.startsOn }} ~ {{ plan.endsOn || '不设结束' }}</text></view>
        <view class="pd-kv"><text class="fa-muted">计划日</text><text>{{ orderedDays.length }} 天</text></view>
        <view class="fa-btn fa-btn--sm pd-toggle" @tap="showVersionMeta = !showVersionMeta">
          <FaIcon :name="showVersionMeta ? 'chevron' : 'records'" tone="accent" size="36rpx" />
          <text>{{ showVersionMeta ? '收起' : '版本详情' }}</text>
        </view>

        <view v-if="showVersionMeta" class="pd-meta">
          <view class="pd-kv"><text class="fa-muted">计划组</text><text class="pd-mono">{{ plan.planGroupUuid }}</text></view>
          <view class="pd-kv"><text class="fa-muted">派生自</text><text class="pd-mono">{{ plan.basePlanId || '—（首个版本）' }}</text></view>
          <view class="pd-kv"><text class="fa-muted">确认生效时间</text><text>{{ instantToDisplay(plan.confirmedAt) }}</text></view>
          <view class="pd-kv"><text class="fa-muted">乐观锁版本</text><text>{{ plan.lockVersion }}</text></view>
          <view class="pd-kv"><text class="fa-muted">创建 / 更新</text><text>{{ instantToDisplay(plan.createdAt) }} / {{ instantToDisplay(plan.updatedAt) }}</text></view>
        </view>

        <view v-if="plan.goalSnapshot" class="pd-goal">
          <text class="fa-section-title pd-goal__t">目标快照</text>
          <view class="pd-kv"><text class="fa-muted">目标类型</text><text>{{ goalTypeLabel(plan.goalSnapshot.goalType) }}</text></view>
          <view class="pd-kv"><text class="fa-muted">快照时间</text><text>{{ instantToDisplay(plan.goalSnapshot.capturedAt) }}</text></view>
          <view class="pd-kv">
            <text class="fa-muted">缺失字段</text>
            <text>{{ plan.goalSnapshot.absentFields.length > 0 ? plan.goalSnapshot.absentFields.join('、') : '无' }}</text>
          </view>
        </view>
      </view>

      <!-- 动作后的一次性成功提示 -->
      <view v-if="successTip" class="fa-banner fa-banner--info">
        <text class="fa-prose">{{ successTip }}</text>
      </view>

      <!-- 动作失败与刷新失败必须在计划日区域之外也看得见：
           StateBlock 的 empty 分支不渲染默认插槽，零计划日的草稿会把 409 提示整个吞掉 -->
      <view v-if="actionFailure" class="fa-banner fa-banner--error">
        <text class="fa-prose">{{ actionFailure.title }}</text>
        <text v-if="actionFailure.detail" class="fa-prose">{{ actionFailure.detail }}</text>
        <text class="fa-hint">错误码：{{ actionFailure.code }}{{ actionFailure.traceId ? ` · traceId：${actionFailure.traceId}` : '' }}</text>
      </view>

      <!-- 状态机允许的动作：主操作只有一个，其余进「更多」（迁移矩阵不变，一个动作都不藏） -->
      <view class="pd-actions fa-toolbar">
        <view
          v-if="primaryAction"
          class="fa-btn fa-btn--primary"
          :class="{ 'fa-btn--disabled': acting }"
          @tap="runAction(primaryAction)"
        >
          <text>{{ acting ? `${ACTION_LABELS[primaryAction]}…` : ACTION_LABELS[primaryAction] }}</text>
        </view>
        <view v-if="secondaryActions.length > 0" class="fa-btn fa-btn--sm pd-actions__more" @tap="moreOpen = true">
          <FaIcon name="more" tone="sub" size="40rpx" />
          <text>更多</text>
        </view>
        <FaMoreMenu v-model:open="moreOpen" :items="moreItems" @select="runAction($event)" />
      </view>
      <text v-if="isReadOnly" class="fa-muted pd-readonly">当前状态为只读；如需修改请复制为新计划。</text>

      <!-- 计划日区域：走 StateBlock，实现 空/提交失败/成功 -->
      <StateBlock
        :state="daysState"
        :submit-failure="submitFailure"
        empty-title="还没有计划日"
        empty-desc="这份草稿还没有安排任何星期，点「编辑」添加计划日与动作。"
      >
        <template #action>
          <view v-if="plan.status === 'DRAFT'" class="fa-btn fa-btn--primary" @tap="gotoEdit">编辑</view>
        </template>

        <view class="pd-days">
          <text class="fa-section-title">计划日</text>
          <!-- 一次只看一天：整周表单同时展开就是本轮要消灭的拥挤形态 -->
          <FaDayStrip v-model="activeKey" :cells="dayCells" />
          <PlanDayCard v-if="activeDay" :key="activeDay.id" :day="activeDay" />
          <text v-else class="fa-muted">这个星期没有安排，点其它日看内容。</text>
          <text class="fa-hint">条选只影响这一屏的显示，不改变计划内容。</text>
        </view>
      </StateBlock>
    </template>
  </view>
</template>

<script setup lang="ts">
/**
 * 计划详情（docs/p1-api.md §7 `GET /api/plans/{id}`）：
 * - 完整聚合按星期渲染（复用 `PlanDayCard`）+ 版本信息（group/revision/base/source/confirmedAt/lockVersion）；
 * - 动作按钮严格依据状态迁移矩阵 `PLAN_ACTIONS[status]` 出现：
 *   DRAFT 编辑/生效/丢弃/复制；ACTIVE 归档/另存新草稿/复制；ARCHIVED 另存新草稿/复制；DISCARDED 仅复制（只读）。
 * - 每次动作后「重新拉取详情」而不是本地打补丁（乐观锁与状态以服务端为准）；
 * - 激活遇到 `ACTIVE_PLAN_EXISTS` → 二次确认后带 `replace` 重试（契约未写明该键名，见 types/training.ts 注释）。
 * 五态齐全；一切经 `api/training.ts`。
 */
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import StateBlock from '@/components/StateBlock.vue'
import PlanDayCard from '@/components/PlanDayCard.vue'
import FaIcon from '@/components/FaIcon.vue'
import FaMoreMenu from '@/components/FaMoreMenu.vue'
import type { FaIconName } from '@/styles/icon-names'
import type { MoreItem, StripCell } from '@/types/ui'
import {
  activatePlan,
  archivePlan,
  copyPlan,
  discardPlan,
  fetchPlan,
  revisePlan
} from '@/api/training'
import { isSessionExpiredError } from '@/utils/request'
import { toFailureView, type FailureView } from '@/utils/errorMessage'
import { instantToDisplay, todayIsoWeekday } from '@/utils/date'
import { goalTypeLabel } from '@/types/profile'
import {
  PLAN_ACTIONS,
  PLAN_STATUS_LABELS,
  WEEKDAY_LABELS,
  type PlanSource,
  type PlanStatus,
  type TrainingPlan
} from '@/types/training'
import type { PageState } from '@/types/api'

const ACTION_LABELS: Record<string, string> = {
  edit: '编辑',
  activate: '确认生效',
  discard: '丢弃草稿',
  archive: '归档',
  revise: '另存为新草稿',
  copy: '复制为新计划'
}

/** 「更多」菜单里的图标只是识别辅助，动作本身仍由状态机迁移矩阵决定。 */
const ACTION_ICONS: Record<string, FaIconName> = {
  edit: 'edit',
  activate: 'target',
  discard: 'alert',
  archive: 'records',
  revise: 'copy',
  copy: 'copy'
}

const SOURCE_LABELS: Record<PlanSource, string> = {
  MANUAL: '手动',
  IMPORT: '导入',
  AI: 'AI'
}

const planId = ref('')
const plan = ref<TrainingPlan | null>(null)
const loading = ref(false)
const failure = ref<FailureView | null>(null)
const submitFailure = ref<FailureView | null>(null)
const successTip = ref('')
const acting = ref(false)
const loadedOnce = ref(false)
const showVersionMeta = ref(false)

const pageState = computed<PageState>(() => {
  if (failure.value && failure.value.sessionExpired) return 'sessionExpired'
  if (!loadedOnce.value && !plan.value) return 'loading'
  if (failure.value && !plan.value) return 'error'
  return 'success'
})

const actions = computed<string[]>(() => (plan.value ? PLAN_ACTIONS[plan.value.status] : []))
const isReadOnly = computed(() => plan.value?.status === 'DISCARDED')

/** 主操作只留迁移矩阵里的第一个，其余进「更多」——动作集合与顺序仍完全由状态机决定，没有增删。 */
const primaryAction = computed<string>(() => actions.value[0] ?? '')
const secondaryActions = computed<string[]>(() => actions.value.slice(1))
const moreOpen = ref(false)
const moreItems = computed<MoreItem[]>(() =>
  secondaryActions.value.map((action) => ({
    key: action,
    label: ACTION_LABELS[action] ?? action,
    icon: ACTION_ICONS[action] ?? ('more' as FaIconName),
    danger: action === 'discard'
  }))
)

const orderedDays = computed(() => {
  if (!plan.value) return []
  return plan.value.days.slice().sort((a, b) => a.isoWeekday - b.isoWeekday)
})

/** 条选固定 7 格：计划里没有的星期也要看得见，否则用户以为一周被截断了。 */
const dayCells = computed<StripCell[]>(() => {
  const byWeekday = new Map(orderedDays.value.map((d) => [d.isoWeekday, d]))
  return [1, 2, 3, 4, 5, 6, 7].map<StripCell>((iso) => {
    const day = byWeekday.get(iso)
    return {
      key: String(iso),
      label: WEEKDAY_LABELS[iso],
      sub: day ? (day.isRestDay ? '休' : `${day.items.length}`) : '—',
      mark: !day ? 'off' : day.isRestDay ? 'rest' : 'on'
    }
  })
})

const activeKey = ref('')
const activeDay = computed(() => orderedDays.value.find((d) => String(d.isoWeekday) === activeKey.value) ?? null)

// 计划日区域的状态：有日=成功、无日=空（版本信息与动作按钮在其上方始终展示）
const daysState = computed<PageState>(() => (orderedDays.value.length === 0 ? 'empty' : 'success'))

/** 当前可见的失败：迁移动作的提交失败优先，其次是详情刷新失败（已有计划时 pageState 仍是 success）。 */
const actionFailure = computed<FailureView | null>(() => submitFailure.value ?? (plan.value ? failure.value : null))

function statusChipClass(status: PlanStatus): string {
  if (status === 'ACTIVE') return 'fa-chip--primary'
  if (status === 'DRAFT') return 'fa-chip--warning'
  if (status === 'DISCARDED') return 'fa-chip--danger'
  return ''
}

async function loadPlan(): Promise<void> {
  if (!planId.value) {
    failure.value = {
      title: '缺少计划 id',
      detail: '请从计划列表进入详情页。',
      code: 'CLIENT_ERROR',
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
    loadedOnce.value = true
    return
  }
  if (loading.value) return
  loading.value = true
  try {
    const data = await fetchPlan(planId.value)
    plan.value = data
    failure.value = null
    // 默认落在今天：进详情页第一眼看的是"今天练什么"，而不是一长串 KV
    if (!activeKey.value) activeKey.value = String(todayIsoWeekday())
    loadedOnce.value = true
  } catch (err) {
    failure.value = toFailureView(err)
    if (isSessionExpiredError(err)) loadedOnce.value = true
  } finally {
    loading.value = false
  }
}

function clearTipSoon(msg: string): void {
  successTip.value = msg
  setTimeout(() => {
    successTip.value = ''
  }, 2600)
}

function gotoEdit(): void {
  if (!plan.value) return
  uni.navigateTo({ url: `/pages/plan/edit?id=${encodeURIComponent(plan.value.id)}` })
}

/** 状态迁移动作：每次成功后重新拉取详情（不本地打补丁）。 */
async function runAction(action: string): Promise<void> {
  if (!plan.value || acting.value) return
  const p = plan.value
  if (action === 'edit') {
    gotoEdit()
    return
  }
  if (action === 'copy') {
    void doCopy()
    return
  }
  if (action === 'revise') {
    void doRevise()
    return
  }
  if (action === 'activate') {
    uni.showModal({
      title: '确认生效',
      content: '生效后这份草稿将成为当前计划；若已有生效计划，旧计划会被归档。确认继续？',
      success: (res) => {
        if (res.confirm) void doActivate()
      }
    })
    return
  }
  if (action === 'archive') {
    uni.showModal({
      title: '归档当前计划',
      content: '归档后首页将显示「未设置计划」。确认归档？',
      success: (res) => {
        if (res.confirm) void doSimple(() => archivePlan(p.id, { lockVersion: p.lockVersion }), '已归档')
      }
    })
    return
  }
  if (action === 'discard') {
    uni.showModal({
      title: '丢弃草稿',
      content: '草稿将被置为「已丢弃」终态（记录保留、不可就地编辑）。确认丢弃？',
      success: (res) => {
        if (res.confirm) void doSimple(() => discardPlan(p.id, { lockVersion: p.lockVersion }), '已丢弃草稿')
      }
    })
  }
}

async function doActivate(): Promise<void> {
  if (!plan.value) return
  const p = plan.value
  acting.value = true
  submitFailure.value = null
  try {
    // 请求体只有 lockVersion：后端 ActivateRequest 就这一个字段，多传任何键都会被
    // 全局 FAIL_ON_UNKNOWN_PROPERTIES 判成 422 UNKNOWN_FIELD。
    // 「归档旧 ACTIVE 再激活新版本」是后端在同一个事务里无条件做的，
    // 所以这里不需要 replace 开关——确认弹窗已经在调用前告知过会被替换。
    await activatePlan(p.id, { lockVersion: p.lockVersion })
    clearTipSoon('已确认生效')
    await loadPlan()
  } catch (err) {
    const view = toFailureView(err)
    submitFailure.value = view
    // 状态机不允许 / 乐观锁冲突 / 罕见的并发激活冲突：刷新详情，让用户看到最新状态与 lockVersion
    if (view.invalidTransition || view.versionConflict || view.activePlanExists) await loadPlan()
  } finally {
    acting.value = false
  }
}

async function doSimple(op: () => Promise<TrainingPlan>, tip: string): Promise<void> {
  acting.value = true
  submitFailure.value = null
  try {
    await op()
    clearTipSoon(tip)
    await loadPlan()
  } catch (err) {
    const view = toFailureView(err)
    submitFailure.value = view
    if (view.invalidTransition || view.versionConflict) await loadPlan()
  } finally {
    acting.value = false
  }
}

/** 另存为新草稿：基于 ACTIVE/ARCHIVED 生成同组新 DRAFT，随后进入该草稿编辑（docs/p1-api.md §7、验收 A11）。 */
async function doRevise(): Promise<void> {
  if (!plan.value) return
  const p = plan.value
  acting.value = true
  submitFailure.value = null
  try {
    const draft = await revisePlan(p.id)
    uni.navigateTo({ url: `/pages/plan/edit?id=${encodeURIComponent(draft.id)}` })
  } catch (err) {
    const view = toFailureView(err)
    submitFailure.value = view
    if (view.invalidTransition || view.versionConflict) await loadPlan()
  } finally {
    acting.value = false
  }
}

async function doCopy(): Promise<void> {
  if (!plan.value) return
  const p = plan.value
  acting.value = true
  submitFailure.value = null
  try {
    const copy = await copyPlan(p.id)
    clearTipSoon('已复制为新计划（新计划组）')
    uni.navigateTo({ url: `/pages/plan/detail?id=${encodeURIComponent(copy.id)}` })
  } catch (err) {
    submitFailure.value = toFailureView(err)
  } finally {
    acting.value = false
  }
}

onLoad((query) => {
  planId.value = (query && query.id ? String(query.id) : '').trim()
  void loadPlan()
})

// 从编辑页返回后刷新，展示服务端最新的聚合与 lockVersion
onShow(() => {
  if (planId.value && loadedOnce.value) void loadPlan()
})
</script>

<style lang="scss" scoped>
.pd-head {
  background-color: $fa-surface;
  border: 1px solid $fa-line;
  border-radius: $fa-radius;
  padding: $fa-space-card;
  margin-bottom: $fa-space-section;
}

.pd-toggle {
  margin-top: $fa-space-tight;
  width: 200rpx;
}

.pd-meta {
  margin-top: $fa-space-tight;
  padding-top: $fa-space-tight;
  border-top: 1px solid $fa-line;
}

.pd-head__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.pd-head__title {
  flex: 1;
}

.pd-chip {
  margin: 0;
}

.pd-kv {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 3px 0;
  font-size: $fa-font-ui;
}

.pd-mono {
  word-break: break-all;
  text-align: right;
  flex: 1;
}

.pd-goal {
  border-top: 1px solid $fa-line;
  margin-top: 8px;
  padding-top: 4px;
}

.pd-goal__t {
  margin: 6px 2px;
}

.pd-notes {
  border-top: 1px solid $fa-line;
  margin-top: 8px;
  padding-top: 6px;
}

.pd-notes__label {
  display: block;
  margin-bottom: 2px;
}

.pd-actions {
  margin-bottom: $fa-space-tight;
}

.pd-actions__more {
  margin-left: auto;
}

.pd-readonly {
  display: block;
  width: 100%;
  margin-top: 4px;
}

.pd-days {
  margin-top: 6px;
}
</style>
