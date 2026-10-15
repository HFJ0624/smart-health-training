<template>
  <view class="fa-page">
    <StateBlock
      :state="state"
      :failure="failure"
      :success-tip="successTip"
      loading-text="正在读取目标…"
      :empty-title="filter === '' ? '还没有目标' : '该状态下没有目标'"
      :empty-desc="
        filter === ''
          ? '目标可选填目标体重与每周训练次数，起止日期只有开始日期必填。新建时可一并选择立即激活。'
          : '换一个状态筛选，或直接新建一个目标。'
      "
      @retry="reload"
    >
      <template #action>
        <view class="fa-btn fa-btn--primary" @tap="startCreate">
          <FaIcon name="plus" tone="text" size="40rpx" />
          <text>新建</text>
        </view>
      </template>

      <!-- 列表级失败/状态迁移失败：服务端 message + 错误码 + traceId + 未归属到控件的逐字段错误 -->
      <view v-if="topFailure" class="fa-banner fa-banner--error">
        <text class="goal-block-title">{{ topFailure.title }}</text>
        <text v-if="topFailure.detail" class="fa-prose">{{ topFailure.detail }}</text>
        <text class="goal-code">错误码：{{ topFailure.code }}</text>
        <text v-if="topFailure.traceId" class="goal-code">traceId：{{ topFailure.traceId }}</text>
        <view v-for="(e, i) in topFailure.fields" :key="`tf-${i}`" class="goal-fielderr">
          <text class="goal-fielderr__name">{{ e.field }}</text>
          <text class="fa-prose">{{ e.message }}</text>
          <text class="goal-code">{{ e.code }}</text>
        </view>
        <view class="fa-btn-row goal-actions">
          <view class="fa-btn fa-btn--primary" @tap="reload">重新读取</view>
        </view>
      </view>

      <!-- 当前生效目标：状态图形 + 关键数字承载，「data:null 不是 404」这类接口口径收进点按展开的「为什么」 -->
      <view class="fa-card">
        <view class="goal-head">
          <view class="goal-badge">
            <FaIcon :name="active ? 'target' : 'clock'" :tone="active ? 'success' : 'sub'" size="40rpx" />
          </view>
          <text class="fa-title goal-head__title">当前生效目标</text>
          <text class="fa-chip" :class="active ? 'fa-chip--primary' : ''">{{ active ? '生效中' : '未设定' }}</text>
        </view>
        <template v-if="active">
          <view class="fa-metrics goal-metrics">
            <FaMetric class="fa-metric" label="目标体重" :value="active.targetWeightKg" unit="kg" />
            <FaMetric class="fa-metric" label="每周训练" :value="active.weeklySessionsTarget" unit="次" />
          </view>
          <view class="fa-row">
            <text class="fa-row__label">类型</text>
            <text class="fa-row__value">{{ goalTypeLabel(active.goalType) }}</text>
          </view>
          <view class="fa-row">
            <text class="fa-row__label">起止日期</text>
            <text class="fa-row__value">{{ active.startsOn }} → {{ active.endsOn || '未设结束' }}</text>
          </view>
        </template>
        <text v-else class="fa-prose">现在还没有生效中的目标，激活任意草稿即可生效。</text>

        <view class="fa-btn fa-btn--sm goal-toggle" @tap="showActiveWhy = !showActiveWhy">
          <FaIcon :name="showActiveWhy ? 'chevron' : 'records'" tone="accent" size="36rpx" />
          <text>{{ showActiveWhy ? '收起' : '为什么' }}</text>
        </view>
        <view v-if="showActiveWhy" class="goal-why">
          <text class="fa-prose">「当前生效目标」的唯一来源是 GET /api/me/goals/active，页面不在本地推断谁在生效。</text>
          <text class="fa-prose">本轮每用户最多一个 ACTIVE 目标，所以现在没有时接口返回 data:null（不是 404）——这是空状态而不是错误。</text>
        </view>
      </view>

      <!-- 列表区唯一的两个控件：重读（次级、带图标）+ 新建（唯一主操作） -->
      <view class="fa-toolbar">
        <text class="fa-section-title fa-section-title--first">目标</text>
        <view class="fa-toolbar__group">
          <view class="fa-btn fa-btn--sm" :class="{ 'fa-btn--disabled': busy }" @tap="reload">
            <FaIcon name="refresh" tone="accent" size="40rpx" />
            <text>重读</text>
          </view>
          <view class="fa-btn fa-btn--primary fa-btn--sm" @tap="startCreate">
            <FaIcon name="plus" tone="text" size="40rpx" />
            <text>新建</text>
          </view>
        </view>
      </view>

      <view class="goal-filters">
        <view
          v-for="opt in statusOptions"
          :key="`f-${opt.value}`"
          class="fa-chip goal-chip"
          :class="{ 'fa-chip--on': filter === opt.value }"
          @tap="setFilter(opt.value)"
        >
          <text>{{ opt.label }}</text>
        </view>
      </view>

      <view class="fa-btn fa-btn--sm goal-toggle" @tap="showListWhy = !showListWhy">
        <FaIcon :name="showListWhy ? 'chevron' : 'records'" tone="accent" size="36rpx" />
        <text>{{ showListWhy ? '收起' : '为什么' }}</text>
      </view>
      <view v-if="showListWhy" class="goal-why">
        <text class="fa-prose">列表来自 GET /api/me/goals，按 createdAt,desc 分页加载。</text>
        <text class="fa-prose">上方的「当前生效目标」读的是另一个接口，不受这里的筛选影响。</text>
      </view>

      <text v-if="goals.length === 0" class="fa-hint">
        「{{ filter === '' ? '全部状态' : goalStatusLabel(filter) }}」这一页没有目标记录，
        但上方的「当前生效目标」是单独读取的，不受筛选影响。
      </text>

      <!-- 一行只留「状态图形 + 短标签 + 日期」+ 状态机允许的一个主操作，其余动作与调试字段进层级 -->
      <view v-for="g in goals" :key="g.id" class="fa-card goal-item">
        <view class="goal-item__head">
          <view class="goal-badge">
            <FaIcon :name="STATUS_ICONS[g.status]" :tone="STATUS_TONES[g.status]" size="40rpx" />
          </view>
          <view class="goal-item__text">
            <text class="goal-item__title">{{ goalTypeLabel(g.goalType) }}</text>
            <text class="goal-item__meta">{{ goalStatusLabel(g.status) }} · {{ g.startsOn }} → {{ g.endsOn || '未设结束' }}</text>
          </view>
          <view v-if="secondaryActionsOf(g).length > 0" class="fa-btn fa-btn--sm goal-item__more" @tap="openMore(g)">
            <FaIcon name="more" tone="sub" size="40rpx" />
            <text>更多</text>
          </view>
        </view>

        <view class="goal-item__ops">
          <view
            v-if="hasPrimaryAction(g)"
            class="fa-btn fa-btn--primary goal-item__primary"
            :class="{ 'fa-btn--disabled': busy }"
            @tap="onPrimaryTap(g)"
          >
            <text>{{ primaryLabel(g) }}</text>
          </view>
          <text v-else class="fa-muted goal-item__readonly">只读历史</text>
          <view class="fa-btn fa-btn--sm" @tap="toggleDetail(g.id)">
            <FaIcon :name="expandedId === g.id ? 'chevron' : 'records'" tone="accent" size="36rpx" />
            <text>{{ expandedId === g.id ? '收起' : '详情' }}</text>
          </view>
        </view>

        <view v-if="expandedId === g.id" class="goal-item__detail">
          <view class="fa-row">
            <text class="fa-row__label">目标体重</text>
            <text class="fa-row__value">{{ formatDecimal(g.targetWeightKg, 'kg') }}</text>
          </view>
          <view class="fa-row">
            <text class="fa-row__label">每周次数</text>
            <text class="fa-row__value">{{ g.weeklySessionsTarget === null ? '未设定' : g.weeklySessionsTarget + ' 次' }}</text>
          </view>
          <view class="fa-row">
            <text class="fa-row__label">创建时间</text>
            <text class="fa-row__value">{{ instantToDisplay(g.createdAt) }}</text>
          </view>
          <view class="fa-row">
            <text class="fa-row__label">ID / 版本</text>
            <text class="fa-row__value">{{ g.id }} / {{ g.lockVersion }}</text>
          </view>
          <text v-if="g.status === 'DRAFT'" class="fa-hint">草稿：可以激活、编辑或删除。</text>
          <text v-else-if="g.status === 'ACTIVE'" class="fa-hint">生效中：可以编辑或归档；激活别的目标会把它归档。</text>
          <text v-else class="fa-hint">已归档：历史保留，服务端不允许修改或删除。</text>
        </view>
      </view>

      <!-- 状态机允许的其余动作一个都不少，只是从每行成排按钮换成菜单项（点击后仍走原来的二次确认） -->
      <FaMoreMenu v-model:open="moreOpen" :items="moreItems" @select="onMoreSelect" />

      <view v-if="hasNext" class="fa-btn fa-btn--block goal-more" :class="{ 'fa-btn--disabled': busy }" @tap="loadMore">
        {{ busy ? '加载中…' : total === null ? `加载更多（已载入 ${goals.length} 条）` : `加载更多（已载入 ${goals.length} / ${total}）` }}
      </view>
      <text v-else-if="goals.length > 0" class="fa-hint">已到最后一页{{ total === null ? '（条数未知）' : `（共 ${total} 条）` }}。</text>
    </StateBlock>

    <!-- 新建/编辑表单：状态由列表接口决定，这里只负责提交与逐字段错误 -->
    <view v-if="showForm" class="fa-card goal-form">
      <view class="goal-head">
        <view class="goal-badge">
          <FaIcon :name="editing ? 'edit' : 'plus'" tone="accent" size="40rpx" />
        </view>
        <text class="fa-title goal-head__title">{{ editing ? '编辑目标' : '新建目标' }}</text>
        <text class="fa-chip" :class="editing ? 'fa-chip--warning' : 'fa-chip--primary'">
          {{ editing ? '修改' : '新建' }}
        </text>
      </view>
      <text class="fa-hint">
        {{
          editing
            ? '保存会带上刚读到的版本号，服务端用它防止覆盖你在别处的改动；版本已变时这次提交不写入。'
            : '保存后就是一条真实目标记录；勾选「立即激活」时服务端会先归档当前生效目标。'
        }}
      </text>

      <view v-if="conflictText" class="fa-banner fa-banner--warn">
        <text class="goal-block-title">版本已变化，这次提交没有写入服务端</text>
        <text class="fa-prose">{{ conflictText }}</text>
        <view class="fa-btn-row goal-actions">
          <view class="fa-btn fa-btn--primary" @tap="reload">重新读取列表</view>
        </view>
      </view>

      <view v-if="submitFailure" class="fa-banner fa-banner--error">
        <text class="goal-block-title">{{ submitFailure.title }}</text>
        <text v-if="submitFailure.detail" class="fa-prose">{{ submitFailure.detail }}</text>
        <text class="goal-code">错误码：{{ submitFailure.code }}</text>
        <text v-if="submitFailure.traceId" class="goal-code">traceId：{{ submitFailure.traceId }}</text>
        <view v-for="(e, i) in submitFailure.fields" :key="`gf-${i}`" class="goal-fielderr">
          <text class="goal-fielderr__name">{{ e.field }}</text>
          <text class="fa-prose">{{ e.message }}</text>
          <text class="goal-code">{{ e.code }}</text>
        </view>
      </view>

      <view class="fa-field">
        <view class="fa-field__label"><text>目标类型</text></view>
        <ChipSelect :model-value="form.goalType" :options="GOAL_TYPE_OPTIONS" @update:model-value="setGoalType" />
        <text v-if="err('goalType')" class="goal-error">{{ err('goalType') }}</text>
      </view>

      <view class="fa-field">
        <NumberField
          v-model="form.targetWeightKg"
          label="目标体重"
          unit="kg"
          :max-decimals="2"
          placeholder="可留空"
          hint="可选：大于 0、最多 2 位小数（DECIMAL(6,2)）。不做体重医学区间判定。"
        />
        <text v-if="err('targetWeightKg')" class="goal-error">{{ err('targetWeightKg') }}</text>
      </view>

      <view class="fa-field">
        <NumberField
          v-model="form.weeklySessionsTarget"
          label="每周训练次数"
          unit="次/周"
          integer-only
          :max-decimals="0"
          placeholder="可留空"
          hint="可选：整数 0–255（后端 @Min(0) @Max(255)）。"
        />
        <text v-if="err('weeklySessionsTarget')" class="goal-error">{{ err('weeklySessionsTarget') }}</text>
      </view>

      <DateField
        v-model="form.startsOn"
        label="开始日期"
        required
        placeholder="YYYY-MM-DD"
        hint="必填，只有 YYYY-MM-DD 一种形态。"
      />
      <text v-if="err('startsOn')" class="goal-error">{{ err('startsOn') }}</text>

      <DateField
        v-model="form.endsOn"
        label="结束日期"
        placeholder="未设结束"
        :start="form.startsOn"
        hint="可选，且不早于开始日期（服务端会判 ENDS_BEFORE_STARTS）。"
      />
      <text v-if="err('endsOn')" class="goal-error">{{ err('endsOn') }}</text>

      <view v-if="!editing" class="fa-field">
        <view class="fa-field__label"><text>保存后的动作</text></view>
        <view class="fa-chip goal-chip" :class="{ 'fa-chip--on': form.activate }" @tap="toggleActivate">
          <text>{{ form.activate ? '立即激活（当前生效目标会被归档）' : '仅存为草稿' }}</text>
        </view>
        <text class="fa-hint">不勾选就是草稿，可以稍后再激活；勾选即由服务端在同一次保存里完成归档与激活。</text>
      </view>

      <view class="fa-btn fa-btn--sm goal-toggle" @tap="showFormWhy = !showFormWhy">
        <FaIcon :name="showFormWhy ? 'chevron' : 'records'" tone="accent" size="36rpx" />
        <text>{{ showFormWhy ? '收起' : '为什么' }}</text>
      </view>
      <view v-if="showFormWhy" class="goal-why">
        <text class="fa-prose">新建走 POST /api/me/goals；编辑走 PUT /api/me/goals/目标ID，并带上刚读取的 lockVersion（当前 {{ editing ? editing.lockVersion : '无（新建）' }}）。</text>
        <text class="fa-prose">勾选「立即激活」即请求里 activate=true：服务端在同一事务内先把旧的 ACTIVE 置为 ARCHIVED 再激活本条，UNIQUE(active_user_id) 是最终防线。提交后本页重新读取列表，不在本地改状态。</text>
        <text class="fa-prose">表单字段对应的请求键名：goalType、targetWeightKg、weeklySessionsTarget、startsOn、endsOn。</text>
      </view>

      <view class="fa-btn-row">
        <view class="fa-btn fa-btn--primary" :class="{ 'fa-btn--disabled': busy }" @tap="onSubmit">
          {{ busy ? '提交中…' : editing ? '保存修改' : '创建目标' }}
        </view>
        <view class="fa-btn" @tap="closeForm">取消</view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 目标管理页（docs/p1-api.md §4.3、docs/p1-scope.md §3.2）。
 *
 * 服务端规则（已核对 `UserGoalService.java`）在页面上的落点：
 *   - 状态机 DRAFT → ACTIVE → ARCHIVED；就地改 DRAFT/ACTIVE，**ARCHIVED 只读**（改历史=伪造历史）；
 *   - `DELETE` 只允许 DRAFT，其余 `INVALID_STATE_TRANSITION`；
 *   - 激活的唯一路径是「锁账户行 → 归档旧 ACTIVE → 激活本草稿」，所以**激活后必须重新读取列表**
 *     才能如实反映旧目标变成 ARCHIVED，页面不本地改状态；
 *   - 所有写操作带 `lockVersion`，409 只提示重读，不覆盖。
 *
 * 五态：加载中 / 无目标（含按状态筛选后为空）/ 读取或提交失败（服务端 message + 码 + traceId + 逐字段）/
 * 成功（真实分页数据）/ 登录失效。
 *
 * UI 重排只动层级不动动作：每行的动作集合仍等于原来那组 v-if（DRAFT 激活/编辑/删除、ACTIVE 归档/编辑、
 * ARCHIVED 只读），只是第一个当主操作、其余进「更多」，二次确认与错误显示原样保留。
 */
import { computed, reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import StateBlock from '@/components/StateBlock.vue'
import ChipSelect from '@/components/ChipSelect.vue'
import DateField from '@/components/DateField.vue'
import NumberField from '@/components/NumberField.vue'
import FaIcon from '@/components/FaIcon.vue'
import FaMetric from '@/components/FaMetric.vue'
import FaMoreMenu from '@/components/FaMoreMenu.vue'
import type { FaIconName, FaIconTone } from '@/styles/icon-names'
import type { MoreItem } from '@/types/ui'
import {
  GOAL_STATUS_LABELS,
  GOAL_TYPE_OPTIONS,
  goalStatusLabel,
  goalTypeLabel,
  type GoalCreateRequest,
  type GoalStatus,
  type GoalType,
  type UserGoal
} from '@/types/profile'
import { fieldErrorText, readFieldErrors, type ApiFieldError, type PageState } from '@/types/api'
import { activateGoal, archiveGoal, createGoal, deleteGoal, fetchActiveGoal, fetchGoals, updateGoal } from '@/api/profile'
import { isApiError, isSessionExpiredError, isVersionConflict, pageItems, pageMeta } from '@/utils/request'
import { toFailureView, type FailureView } from '@/utils/errorMessage'
import { instantToDisplay, isEndsBeforeStarts, todayDateValue } from '@/utils/date'
import { formatDecimal, isWithinDecimal6_2 } from '@/utils/units'

/** 后端 @Max(255)：每周次数上限，本地先拦，不静默截断。 */
const MAX_WEEKLY_SESSIONS = 255

const goals = ref<UserGoal[]>([])
const active = ref<UserGoal | null>(null)
const total = ref<number | null>(null)
const hasNext = ref(false)
const page = ref(1)
const size = ref(20)
const filter = ref<GoalStatus | ''>('')

const state = ref<PageState>('loading')
const failure = ref<FailureView | null>(null)
const topFailure = ref<FailureView | null>(null)
const successTip = ref('')
const busy = ref(false)

const showForm = ref(false)
const editing = ref<UserGoal | null>(null)
const submitFailure = ref<FailureView | null>(null)
const fieldErrors = ref<ApiFieldError[]>([])
const localErrors = ref<Record<string, string>>({})
const conflictText = ref('')

/* 以下都是纯展示状态：折叠哪一行、开哪个「更多」菜单、开哪段口径说明，不参与任何请求 */
const showActiveWhy = ref(false)
const showListWhy = ref(false)
const showFormWhy = ref(false)
const expandedId = ref('')
const moreOpen = ref(false)
const menuGoal = ref<UserGoal | null>(null)

const form = reactive({
  goalType: 'FAT_LOSS' as GoalType,
  targetWeightKg: null as number | null,
  weeklySessionsTarget: null as number | null,
  startsOn: '',
  endsOn: '',
  activate: true
})

const statusOptions: Array<{ value: GoalStatus | ''; label: string }> = [
  { value: '', label: '全部状态' },
  { value: 'DRAFT', label: GOAL_STATUS_LABELS.DRAFT },
  { value: 'ACTIVE', label: GOAL_STATUS_LABELS.ACTIVE },
  { value: 'ARCHIVED', label: GOAL_STATUS_LABELS.ARCHIVED }
]

const CONTROL_FIELDS = ['goalType', 'targetWeightKg', 'weeklySessionsTarget', 'startsOn', 'endsOn']

function err(field: string): string {
  return fieldErrorText(fieldErrors.value, field) || localErrors.value[field] || ''
}

function bannerErrors(all: ApiFieldError[]): ApiFieldError[] {
  const owned = new Set<ApiFieldError>()
  CONTROL_FIELDS.forEach((control) => {
    all.forEach((e) => {
      if (fieldErrorText([e], control) !== '') owned.add(e)
    })
  })
  return all.filter((e) => !owned.has(e))
}

/** 状态图形：三种状态一眼分得开，不靠颜色单独表达（图标形状本身就不同）。 */
const STATUS_ICONS: Record<GoalStatus, FaIconName> = { DRAFT: 'clock', ACTIVE: 'target', ARCHIVED: 'records' }
const STATUS_TONES: Record<GoalStatus, FaIconTone> = { DRAFT: 'sub', ACTIVE: 'success', ARCHIVED: 'sub' }

type RowAction = 'activate' | 'archive' | 'edit' | 'delete'

const ROW_ACTION_LABELS: Record<RowAction, string> = {
  activate: '激活',
  archive: '归档',
  edit: '编辑',
  delete: '删除草稿'
}

const ROW_ACTION_ICONS: Record<RowAction, FaIconName> = {
  activate: 'target',
  archive: 'records',
  edit: 'edit',
  delete: 'alert'
}

/**
 * 每行的动作集合与原来逐按钮渲染的条件完全等价（DRAFT 激活/编辑/删除、ACTIVE 归档/编辑、ARCHIVED 无），
 * 只是分了两层：第一个当主操作，其余进「更多」。危险项排最后，点它仍然先走原来的二次确认。
 */
function rowActionsOf(g: UserGoal): RowAction[] {
  if (g.status === 'DRAFT') return ['activate', 'edit', 'delete']
  if (g.status === 'ACTIVE') return ['archive', 'edit']
  return []
}

function primaryActionOf(g: UserGoal): RowAction | '' {
  return rowActionsOf(g)[0] ?? ''
}

function secondaryActionsOf(g: UserGoal): RowAction[] {
  return rowActionsOf(g).slice(1)
}

/* 主操作可能不存在（ARCHIVED 只读），所以模板里只判空、取值走这三个函数，
   避免出现 `ROW_ACTION_LABELS['']` 这种空索引（模板里也做不出窄化） */
function hasPrimaryAction(g: UserGoal): boolean {
  return primaryActionOf(g) !== ''
}

function primaryLabel(g: UserGoal): string {
  const action = primaryActionOf(g)
  return action === '' ? '' : ROW_ACTION_LABELS[action]
}

function onPrimaryTap(g: UserGoal): void {
  const action = primaryActionOf(g)
  if (action !== '') runRowAction(g, action)
}

const moreItems = computed<MoreItem[]>(() =>
  menuGoal.value === null
    ? []
    : secondaryActionsOf(menuGoal.value).map((action) => ({
        key: action,
        label: ROW_ACTION_LABELS[action],
        icon: ROW_ACTION_ICONS[action],
        danger: action === 'delete'
      }))
)

function runRowAction(g: UserGoal, action: RowAction): void {
  if (action === 'activate') askActivate(g)
  else if (action === 'archive') askArchive(g)
  else if (action === 'edit') startEdit(g)
  else askDelete(g)
}

function openMore(g: UserGoal): void {
  menuGoal.value = g
  moreOpen.value = true
}

function onMoreSelect(key: string): void {
  const g = menuGoal.value
  if (!g) return
  runRowAction(g, key as RowAction)
}

function toggleDetail(id: string): void {
  expandedId.value = expandedId.value === id ? '' : id
}

/** 一次读取「当前 ACTIVE + 分页列表」：所有写操作之后都走它，页面不本地拼接状态。 */
async function fetchBoth(resetPage: boolean): Promise<void> {
  const targetPage = resetPage ? 1 : page.value
  const [activeRes, listRes] = await Promise.all([
    fetchActiveGoal(),
    fetchGoals({ status: filter.value, page: targetPage, size: size.value, sort: 'createdAt,desc' })
  ])
  const rows = pageItems(listRes)
  const meta = pageMeta(listRes, targetPage, size.value)
  active.value = activeRes
  goals.value = resetPage ? rows : goals.value.concat(rows)
  total.value = meta.total
  hasNext.value = meta.hasNext
  page.value = targetPage
}

/** 真正的读取：调用方负责 busy（写操作成功后要在同一把锁内重读）。 */
async function loadRaw(resetPage: boolean): Promise<void> {
  if (resetPage) {
    state.value = goals.value.length === 0 && active.value === null ? 'loading' : 'success'
    failure.value = null
    topFailure.value = null
  }
  try {
    await fetchBoth(resetPage)
    // 只有「列表为空且没有 ACTIVE 目标」才算第 2 态：按状态筛空时「当前生效目标」卡片要留在屏幕上
    state.value = goals.value.length === 0 && active.value === null ? 'empty' : 'success'
  } catch (err2) {
    if (isSessionExpiredError(err2)) {
      // 第 5 态：封装层已清令牌并跳登录，页面如实渲染，不吞掉
      state.value = 'sessionExpired'
    } else {
      const view = toFailureView(err2)
      if (goals.value.length === 0) {
        failure.value = view
        state.value = 'error'
      } else {
        // 已有数据在屏幕上：保留列表，把失败如实显示在顶部横幅（不假装刷新成功）
        topFailure.value = view
        state.value = 'success'
      }
    }
  }
}

async function load(resetPage: boolean): Promise<void> {
  // 正在读取时按钮不能"点了没反应"：至少告诉用户这一次为什么要等
  if (busy.value) {
    uni.showToast({ title: '正在读取，请稍候', icon: 'none' })
    return
  }
  busy.value = true
  try {
    await loadRaw(resetPage)
  } finally {
    busy.value = false
  }
}

function reload(): void {
  successTip.value = ''
  void load(true)
}

function loadMore(): void {
  if (!hasNext.value || busy.value) return
  void load(false)
}

function setFilter(value: GoalStatus | ''): void {
  filter.value = value
  successTip.value = ''
  // 换筛选条件后上一行的展开状态没有意义（列表已经换了一批），但这是纯展示清理，不影响请求
  expandedId.value = ''
  void load(true)
}

function resetForm(): void {
  submitFailure.value = null
  fieldErrors.value = []
  localErrors.value = {}
  conflictText.value = ''
}

function startCreate(): void {
  editing.value = null
  form.goalType = 'FAT_LOSS'
  form.targetWeightKg = null
  form.weeklySessionsTarget = null
  form.startsOn = todayDateValue()
  form.endsOn = ''
  form.activate = true
  resetForm()
  showForm.value = true
  uni.pageScrollTo({ scrollTop: 0, duration: 200 })
}

function startEdit(g: UserGoal): void {
  editing.value = g
  form.goalType = g.goalType
  form.targetWeightKg = g.targetWeightKg
  form.weeklySessionsTarget = g.weeklySessionsTarget
  form.startsOn = g.startsOn
  form.endsOn = g.endsOn ?? ''
  form.activate = false
  resetForm()
  showForm.value = true
  uni.pageScrollTo({ scrollTop: 0, duration: 200 })
}

function closeForm(): void {
  showForm.value = false
  editing.value = null
  resetForm()
}

function setGoalType(value: string): void {
  form.goalType = value as GoalType
}

function toggleActivate(): void {
  form.activate = !form.activate
}

/** 只做契约/后端 DTO 写明的校验（必填、正数、小数位、量程、日期顺序），不发明规则。 */
function buildPayload(): GoalCreateRequest | null {
  const local: Record<string, string> = {}
  if (!GOAL_TYPE_OPTIONS.some((o) => o.value === form.goalType)) local.goalType = '目标类型取值非法'
  if (form.startsOn === '') local.startsOn = '开始日期必填'

  const weight = form.targetWeightKg
  if (weight !== null) {
    if (!(weight > 0)) local.targetWeightKg = '目标体重必须大于 0'
    else if (!isWithinDecimal6_2(weight)) local.targetWeightKg = '目标体重要在 DECIMAL(6,2) 量程内且最多 2 位小数'
  }
  const sessions = form.weeklySessionsTarget
  if (sessions !== null) {
    if (!Number.isInteger(sessions)) local.weeklySessionsTarget = '每周训练次数必须是整数'
    else if (sessions < 0 || sessions > MAX_WEEKLY_SESSIONS) {
      local.weeklySessionsTarget = `每周训练次数范围 0–${MAX_WEEKLY_SESSIONS}`
    }
  }
  if (form.endsOn !== '' && form.startsOn !== '' && isEndsBeforeStarts(form.startsOn, form.endsOn)) {
    local.endsOn = '结束日期不能早于开始日期（ENDS_BEFORE_STARTS）'
  }

  localErrors.value = local
  if (Object.keys(local).length > 0) return null

  return {
    goalType: form.goalType,
    targetWeightKg: weight,
    weeklySessionsTarget: sessions,
    startsOn: form.startsOn,
    endsOn: form.endsOn === '' ? null : form.endsOn,
    activate: editing.value === null ? form.activate : false
  }
}

function absorbError(err2: unknown): FailureView {
  const view = toFailureView(err2)
  const merged = readFieldErrors(isApiError(err2) ? err2.data : null, view.fields)
  fieldErrors.value = merged
  view.fields = bannerErrors(merged)
  return view
}

async function onSubmit(): Promise<void> {
  if (busy.value) return
  const payload = buildPayload()
  if (payload === null) {
    uni.showToast({ title: '先修正表单里标红的项', icon: 'none', duration: 2000 })
    return
  }
  const existing = editing.value
  const beforeActiveId = active.value?.id ?? null
  busy.value = true
  submitFailure.value = null
  fieldErrors.value = []
  conflictText.value = ''
  try {
    const saved =
      existing === null
        ? await createGoal({
            goalType: payload.goalType,
            targetWeightKg: payload.targetWeightKg,
            weeklySessionsTarget: payload.weeklySessionsTarget,
            startsOn: payload.startsOn,
            endsOn: payload.endsOn,
            activate: payload.activate
          })
        : await updateGoal(existing.id, {
            goalType: payload.goalType,
            targetWeightKg: payload.targetWeightKg,
            weeklySessionsTarget: payload.weeklySessionsTarget,
            startsOn: payload.startsOn,
            endsOn: payload.endsOn,
            lockVersion: existing.lockVersion
          })
    closeForm()
    await loadRaw(true)
    const replacedActive =
      existing === null && payload.activate && beforeActiveId !== null && beforeActiveId !== saved.id
    successTip.value =
      existing === null
        ? `已创建目标 ID ${saved.id}（服务端状态 ${goalStatusLabel(saved.status)}）` +
          (replacedActive
            ? `；原生效目标 ID ${beforeActiveId} 已由服务端在同一事务里置为 ARCHIVED（列表已重读，标签可见）。`
            : '。列表与「当前生效目标」已重新读取。')
        : `目标 ID ${saved.id} 已保存：lockVersion ${existing.lockVersion} → ${saved.lockVersion}。列表已重新读取。`
  } catch (err2) {
    const view = absorbError(err2)
    submitFailure.value = view
    if (view.sessionExpired) state.value = 'sessionExpired'
    else if (isVersionConflict(err2)) {
      conflictText.value =
        existing === null
          ? '并发激活冲突：服务端 UNIQUE(active_user_id) 只让一个胜出。请重新读取后重试。'
          : `页面里目标 ID ${existing.id} 的 lockVersion=${existing.lockVersion} 已过期，本次提交未写入。请重新读取后重做。`
    }
    uni.showToast({ title: view.title, icon: 'none', duration: 2000 })
  } finally {
    busy.value = false
  }
}

/**
 * 状态迁移与删除：成功后一律重新读取列表（`loadRaw(true)`），
 * 因为「激活会把旧 ACTIVE 归档」是服务端事务的结果，本地拼不出来。
 */
async function runTransition(label: string, call: () => Promise<unknown>, beforeActiveId: string | null): Promise<void> {
  if (busy.value) {
    uni.showToast({ title: '有请求正在进行，请稍候', icon: 'none', duration: 1500 })
    return
  }
  busy.value = true
  submitFailure.value = null
  fieldErrors.value = []
  conflictText.value = ''
  topFailure.value = null
  try {
    await call()
    // 关键：激活会连带归档旧 ACTIVE，这是服务端事务结果，只能重读不能本地拼
    await loadRaw(true)
    const nowActiveId = active.value?.id ?? null
    if (label === '激活') {
      successTip.value =
        beforeActiveId !== null && beforeActiveId !== nowActiveId
          ? `已激活目标 ID ${nowActiveId ?? '—'}；原生效目标 ID ${beforeActiveId} 已由服务端置为 ARCHIVED（列表已重读，标签可见）。`
          : `已激活目标 ID ${nowActiveId ?? '—'}（此前没有生效中的目标）。`
    } else if (label === '归档') {
      successTip.value = `目标 ID ${beforeActiveId ?? '—'} 已归档：现在没有生效中的目标，首页会显示「未设置目标」。`
    } else if (label === '删除') {
      successTip.value = total.value === null ? '草稿目标已删除，列表已按服务端结果重新读取。' : `草稿目标已删除，列表已按服务端结果重新读取（共剩 ${total.value} 条）。`
    } else {
      successTip.value = '操作完成，列表已重新读取。'
    }
  } catch (err2) {
    const view = toFailureView(err2)
    if (view.sessionExpired) {
      state.value = 'sessionExpired'
    } else {
      topFailure.value = view
      state.value = goals.value.length === 0 ? 'error' : 'success'
      if (view.versionConflict || view.invalidTransition) {
        // 版本过期 / 状态机不允许：服务端数据才是真相，重读一次让页面回到真实状态
        await loadRaw(true)
        if (goals.value.length > 0) state.value = 'success'
      }
    }
    uni.showToast({ title: view.title, icon: 'none', duration: 2000 })
  } finally {
    busy.value = false
  }
}

function askActivate(g: UserGoal): void {
  const beforeActiveId = active.value?.id ?? null
  uni.showModal({
    title: '激活目标',
    content:
      beforeActiveId === null
        ? `将把草稿目标 ID ${g.id}（${goalTypeLabel(g.goalType)}）置为生效。`
        : `当前生效目标是 ID ${beforeActiveId}。激活新目标时，服务端会在同一事务里先把它归档。`,
    confirmText: '激活',
    cancelText: '取消',
    success: (res) => {
      if (res.confirm) void runTransition('激活', () => activateGoal(g.id, { lockVersion: g.lockVersion }), beforeActiveId)
    }
  })
}

function askArchive(g: UserGoal): void {
  const beforeActiveId = active.value?.id ?? null
  uni.showModal({
    title: '归档当前目标',
    content: `归档后没有生效中的目标（首页显示「未设置目标」）。归档是留史，不删除数据。`,
    confirmText: '归档',
    cancelText: '取消',
    success: (res) => {
      if (res.confirm) void runTransition('归档', () => archiveGoal(g.id, { lockVersion: g.lockVersion }), beforeActiveId)
    }
  })
}

function askDelete(g: UserGoal): void {
  const beforeActiveId = active.value?.id ?? null
  uni.showModal({
    title: '删除草稿目标',
    content: `将物理删除草稿目标 ID ${g.id}（${goalTypeLabel(g.goalType)}）。服务端只允许删 DRAFT，删除后不可恢复。`,
    confirmText: '删除',
    cancelText: '取消',
    success: (res) => {
      if (res.confirm) void runTransition('删除', () => deleteGoal(g.id), beforeActiveId)
    }
  })
}

onLoad(() => {
  void load(true)
})
</script>

<style lang="scss" scoped>
.goal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $fa-space-tight;
  margin-bottom: $fa-space-tight;
}

.goal-head__title {
  flex: 1;
  min-width: 0;
}

/* 状态徽标：形状（圆）+ 图标 + 色调三重表达，色觉障碍下也读得出现不生效 */
.goal-badge {
  width: 72rpx;
  height: 72rpx;
  border-radius: 50%;
  background-color: $fa-highlight;
  border: 1px solid $fa-control-border;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
}

.goal-metrics {
  margin-bottom: $fa-space-tight;
}

.goal-filters {
  display: flex;
  flex-wrap: wrap;
}

.goal-chip {
  min-height: $fa-tap;
  align-items: center;
}

/* 「为什么」：口径与开发术语不在主阅读路径上，但一条都不删，点按才展开（提示词 §2.3） */
.goal-toggle {
  margin-top: $fa-space-tight;
  width: 180rpx;
}

.goal-why {
  margin-top: $fa-space-tight;
  padding-top: $fa-space-tight;
  border-top: 1px solid $fa-line;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

/* 一行一个目标：收起时只有状态图形 + 短标签 + 日期 + 一个主操作 */
.goal-item {
  padding: 20rpx $fa-space-card;
}

.goal-item__head {
  display: flex;
  align-items: center;
  gap: $fa-space-tight;
  min-height: 96rpx;
}

.goal-item__text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.goal-item__title {
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  color: $fa-text;
}

.goal-item__meta {
  font-size: $fa-font-ui;
  color: $fa-text-sub;
}

.goal-item__more {
  flex: 0 0 auto;
}

.goal-item__ops {
  display: flex;
  align-items: center;
  gap: $fa-space-tight;
  margin-top: $fa-space-tight;
}

/* 主操作占满剩余宽度，保证 44px 以上高度下仍然只有这一个 */
.goal-item__primary {
  flex: 1;
}

.goal-item__readonly {
  flex: 1;
}

.goal-item__detail {
  margin-top: $fa-space-tight;
  padding-top: $fa-space-tight;
  border-top: 1px solid $fa-line;
}

.goal-actions {
  margin-top: $fa-space-tight;
}

.goal-more {
  margin: 6px 0 12px;
}

.goal-block-title {
  display: block;
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  margin-bottom: 4px;
}

.goal-code {
  display: block;
  font-size: $fa-font-ui;
  color: $fa-text-mute;
  margin-top: 4px;
  word-break: break-all;
}

.goal-fielderr {
  border-left: 3px solid $fa-danger;
  padding: 6px 0 6px 10px;
  margin-top: 6px;
}

.goal-fielderr__name {
  display: block;
  font-size: $fa-font-ui;
  font-weight: 600;
  color: $fa-danger;
}

.goal-error {
  display: block;
  margin-top: 4px;
  font-size: $fa-font-ui;
  color: $fa-danger;
}

.goal-form {
  margin-top: 12px;
  /* 活动表单的边界要能单独认出来：淡黄对白只有 1.4:1 */
  border-color: $fa-control-border;
}
</style>
