<template>
  <view class="fa-page">
    <StateBlock
      :state="state"
      :failure="failure"
      :success-tip="successTip"
      loading-text="正在读取体重记录…"
      :empty-title="windowHintTitle"
      :empty-desc="emptyDesc"
      @retry="reload"
    >
      <template #action>
        <view class="fa-btn fa-btn--primary" @tap="startCreate">新增第一条记录</view>
      </template>

      <view v-if="topFailure" class="fa-banner fa-banner--error">
        <text class="bd-block-title">{{ topFailure.title }}</text>
        <text v-if="topFailure.detail" class="fa-prose">{{ topFailure.detail }}</text>
        <text class="bd-code">错误码：{{ topFailure.code }}</text>
        <text v-if="topFailure.traceId" class="bd-code">traceId：{{ topFailure.traceId }}</text>
        <view v-for="(e, i) in topFailure.fields" :key="`tf-${i}`" class="bd-fielderr">
          <text class="bd-fielderr__name">{{ e.field }}</text>
          <text class="fa-prose">{{ e.message }}</text>
          <text class="bd-code">{{ e.code }}</text>
        </view>
        <view class="fa-btn-row bd-actions">
          <view class="fa-btn fa-btn--primary" @tap="reload">重新读取</view>
        </view>
      </view>

      <!-- 关键数字 + 真实趋势：窗口切换是"同一视图的口径切换"，所以用分段控件而不是说明文字 -->
      <view class="fa-card">
        <view class="bd-head">
          <text class="fa-title">体重</text>
          <FaSegmented :model-value="String(windowDays)" :options="windowOptions" :disabled="busy" @update:model-value="setWindow(Number($event))" />
        </view>

        <view class="fa-metrics bd-metrics">
          <FaMetric class="fa-metric" label="最新一次" :value="latest ? latest.weightKg : null" unit="kg" :caption="latestCaption" />
          <FaMetric class="fa-metric" label="腰围" :value="latest ? latest.waistCm : null" unit="cm" unknown-text="未填" />
          <FaMetric class="fa-metric" label="体脂" :value="latest ? latest.bodyFatPercent : null" unit="%" unknown-text="未填" />
        </view>

        <FaTrendChart :points="trendPoints" :note="trendNote" height="300rpx" />

        <view v-if="latest" class="bd-latest-ops">
          <text class="fa-hint">「最新一次」只按测量时间判定，补录更早的记录不会替换它。</text>
        </view>
      </view>

      <view class="fa-toolbar">
        <text class="fa-section-title fa-section-title--first">记录</text>
        <view class="fa-toolbar__group">
          <view class="fa-btn fa-btn--sm" :class="{ 'fa-btn--disabled': busy }" @tap="reload">
            <FaIcon name="refresh" tone="accent" size="40rpx" />
            <text>重读</text>
          </view>
          <view class="fa-btn fa-btn--primary fa-btn--sm" @tap="startCreate">
            <FaIcon name="plus" tone="text" size="40rpx" />
            <text>记录</text>
          </view>
        </view>
      </view>

      <text v-if="records.length === 0" class="fa-muted">
        {{ rangeFrom }} 至 {{ rangeTo }} 没有记录；上面「最新一次」取的是全部记录里测量时间最近的一条。
      </text>

      <!-- 一条记录一行：数字 + 日期 + 来源；ID/版本/幂等键等调试字段收进展开区，编辑与删除也在那里 -->
      <view v-for="r in visibleRecords" :key="r.id" class="fa-card bd-item">
        <view class="bd-item__head" hover-class="bd-item__head--hover" @tap="toggleDetail(r.id)">
          <view class="bd-item__main">
            <text class="bd-item__num">{{ formatDecimal(r.weightKg) }}</text>
            <text class="fa-unit">kg</text>
          </view>
          <text class="fa-muted">{{ r.businessDate }} · {{ sourceText(r.source) }}</text>
          <view class="bd-item__more">
            <text class="bd-item__more-text">{{ expanded === r.id ? '收起' : '详情' }}</text>
            <FaIcon name="chevron" tone="sub" size="36rpx" />
          </view>
        </view>

        <view v-if="expanded === r.id" class="bd-item__detail">
          <view class="fa-row">
            <text class="fa-row__label">测量时间</text>
            <text class="fa-row__value">{{ instantToDisplay(r.measuredAt) }}</text>
          </view>
          <view class="fa-row">
            <text class="fa-row__label">腰围 / 体脂</text>
            <text class="fa-row__value">
              {{ formatDecimal(r.waistCm, 'cm') }} · {{ r.bodyFatPercent === null ? '未填' : r.bodyFatPercent + ' %' }}
            </text>
          </view>
          <view v-if="r.notes" class="fa-row">
            <text class="fa-row__label">备注</text>
            <text class="fa-row__value">{{ r.notes }}</text>
          </view>
          <view class="fa-row">
            <text class="fa-row__label">记录 ID / 版本</text>
            <text class="fa-row__value">{{ r.id }} / {{ r.lockVersion }}</text>
          </view>
          <view class="fa-row">
            <text class="fa-row__label">幂等键</text>
            <text class="fa-row__value bd-uuid">{{ r.clientRecordUuid }}</text>
          </view>
          <view class="fa-btn-row bd-actions">
            <view class="fa-btn" @tap="startEdit(r)">编辑</view>
            <view class="fa-btn fa-btn--danger" @tap="askDelete(r)">删除</view>
          </view>
        </view>
      </view>

      <view v-if="records.length > COLLAPSED_COUNT && !showAllRecords" class="fa-btn bd-more" @tap="showAllRecords = true">
        全部记录（{{ records.length }}）
      </view>

      <view
        v-if="hasNext"
        class="fa-btn fa-btn--block bd-more"
        :class="{ 'fa-btn--disabled': busy }"
        @tap="loadMore"
      >
        {{ busy ? '加载中…' : total === null ? `加载更多（已载入 ${records.length} 条）` : `加载更多（已载入 ${records.length} / ${total}）` }}
      </view>
      <text v-else-if="records.length > 0" class="fa-hint">已到最后一页{{ total === null ? '（条数未知）' : `（共 ${total} 条）` }}。</text>
    </StateBlock>

    <!-- 新增/编辑表单：UUID 的生命周期只在这里，重试复用、新建才换 -->
    <view v-if="showForm" class="fa-card bd-form">
      <view class="bd-head">
        <text class="fa-title">{{ form.mode === 'edit' ? '编辑记录' : '新增记录' }}</text>
        <text class="fa-chip" :class="form.mode === 'edit' ? 'fa-chip--warning' : 'fa-chip--primary'">
          {{ form.mode === 'edit' ? '编辑' : '新增' }}
        </text>
      </view>

      <view v-if="conflictText" class="fa-banner fa-banner--warn">
        <text class="bd-block-title">版本已变化，这次提交没有写入服务端</text>
        <text class="fa-prose">{{ conflictText }}</text>
        <view class="fa-btn-row bd-actions">
          <view class="fa-btn fa-btn--primary" @tap="reloadAndClose">重新读取并关闭表单</view>
        </view>
      </view>

      <view v-if="submitFailure" class="fa-banner fa-banner--error">
        <text class="bd-block-title">{{ submitFailure.title }}</text>
        <text v-if="submitFailure.detail" class="fa-prose">{{ submitFailure.detail }}</text>
        <text class="bd-code">错误码：{{ submitFailure.code }}</text>
        <text v-if="submitFailure.traceId" class="bd-code">traceId：{{ submitFailure.traceId }}</text>
        <view v-for="(e, i) in submitFailure.fields" :key="`sf-${i}`" class="bd-fielderr">
          <text class="bd-fielderr__name">{{ e.field }}</text>
          <text class="fa-prose">{{ e.message }}</text>
          <text class="bd-code">{{ e.code }}</text>
        </view>
        <text class="fa-hint">表单内容与下面这个 UUID 都保留着：原地重试仍复用同一条幂等键。</text>
      </view>

      <view class="fa-banner fa-banner--info">
        <text class="fa-prose">测量时间可以改成更早的时间用来补录；业务日期由服务端按你的时区推导，本页不提交它。</text>
        <text v-if="showIdempotentHelp" class="fa-prose">
          「最新一次」只按测量时间倒序取第一条，所以补录不会改变最新体重。删光记录后本页回到空数据。
        </text>
        <view class="fa-btn fa-btn--sm bd-help-toggle" @tap="showIdempotentHelp = !showIdempotentHelp">
          {{ showIdempotentHelp ? '收起' : '为什么' }}
        </view>
      </view>

      <view class="fa-field">
        <view class="bd-label"><text>测量时间（measuredAt，本地时区录入）</text></view>
        <view class="bd-datetime">
          <DateField
            v-model="form.measuredDate"
            label="日期"
            required
            placeholder="YYYY-MM-DD"
            :clearable="false"
            :hint="''"
          />
          <view class="bd-time">
            <view class="bd-label"><text>时间</text></view>
            <picker mode="time" :value="form.measuredTime" @change="onTimeChange">
              <view class="fa-input bd-time__value">{{ form.measuredTime || '选择时间' }}</view>
            </picker>
          </view>
        </view>
        <view class="fa-btn-row bd-actions">
          <view class="fa-btn" @tap="useNow">改为现在</view>
        </view>
        <text class="fa-hint">默认「现在」；改成更早即补录。晚于当前时间 5 分钟以上会被服务端拒绝（FUTURE_MEASURED_AT）。</text>
        <text v-if="err('measuredAt')" class="bd-error">{{ err('measuredAt') }}</text>
      </view>

      <view class="fa-field">
        <NumberField
          v-model="form.weightKg"
          label="体重"
          unit="kg"
          required
          :max-decimals="2"
          placeholder="例如 72.5"
          hint="必填：大于 0、最多 2 位小数（DECIMAL(6,2)）。按契约不做任何体重医学区间警告。"
        />
        <text v-if="err('weightKg')" class="bd-error">{{ err('weightKg') }}</text>
      </view>

      <view class="fa-field">
        <NumberField
          v-model="form.waistCm"
          label="腰围"
          unit="cm"
          :max-decimals="2"
          placeholder="可留空"
          hint="可选：大于 0、最多 2 位小数。"
        />
        <text v-if="err('waistCm')" class="bd-error">{{ err('waistCm') }}</text>
      </view>

      <view class="fa-field">
        <NumberField
          v-model="form.bodyFatPercent"
          label="体脂率"
          unit="%"
          :max-decimals="2"
          placeholder="可留空"
          hint="可选：契约允许 0–100、最多 2 位小数；本页不做体脂判定。"
        />
        <text v-if="err('bodyFatPercent')" class="bd-error">{{ err('bodyFatPercent') }}</text>
      </view>

      <view class="fa-field">
        <view class="bd-label">
          <text>备注（notes）</text>
          <text class="bd-count">{{ form.notes.length }}/500 字</text>
        </view>
        <textarea
          class="fa-textarea"
          :value="form.notes"
          maxlength="500"
          placeholder="例如：晨起空腹"
          @input="onNotesInput"
        />
        <text v-if="err('notes')" class="bd-error">{{ err('notes') }}</text>
      </view>

      <view class="fa-field">
        <view class="bd-label"><text>幂等键</text></view>
        <text class="bd-uuid">{{ form.uuid }}</text>
        <text v-if="showIdempotentHelp" class="fa-hint">
          {{
            form.mode === 'edit'
              ? '编辑时该值不可变：后端会拿它与库中值核对，不同即判 IMMUTABLE_FIELD。'
              : '提交失败后原地重试复用同一个值，服务端才能命中幂等唯一索引；只有重新点「记录」才会换新值。'
          }}
        </text>
        <text v-if="err('clientRecordUuid')" class="bd-error">{{ err('clientRecordUuid') }}</text>
      </view>

      <view class="fa-btn-row">
        <view class="fa-btn fa-btn--primary" :class="{ 'fa-btn--disabled': busy }" @tap="onSubmit">
          {{ busy ? '提交中…' : submitFailedOnce ? '重试提交（复用同一 UUID）' : form.mode === 'edit' ? '保存修改' : '提交记录' }}
        </view>
        <view class="fa-btn" @tap="closeForm">取消</view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 体重记录页（docs/p1-api.md §5、docs/p1-scope.md §3.3、验收 A6/A7/A8）。
 *
 * 四条硬规则的实现位置（口径已核对 `BodyMeasurementService.java` 与 `dto/{Create,Update}BodyRecordRequest.java`）：
 *   1. **A6 幂等**：`clientRecordUuid` 用 `utils/uuid.ts` 的真 UUID v4，只在「新增一条」时生成；
 *      提交失败后表单内容与该值一起保留，原地重试发同一个键 → 服务端命中唯一索引回读既有行
 *      （响应 `created=false, idempotentReplay=true`），编辑时原样回传（不同即 `IMMUTABLE_FIELD`）。
 *   2. **不提交 businessDate**：请求体里没有该键，业务日期由服务端按 `user_account.timezone` 推导。
 *   3. **A7 补录**：measuredAt 默认现在但可改早；页面如实写明「最新体重只按 measuredAt 倒序取」，
 *      写操作之后重新读取 `latest`，绝不本地把补录记录插成最新。
 *   4. **A8 回退**：删除成功后重新读取 `latest` + 列表，用服务端结果渲染（不 splice 数组）。
 *
 * 校验只做契约允许的：单位、正数、小数位/量程、0–100 体脂、日期可解析、未来的 5 分钟护栏。
 * **没有任何医学区间警告**（§2 明确禁止未经说明的阈值）。
 */
import { computed, reactive, ref } from 'vue'
import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
import StateBlock from '@/components/StateBlock.vue'
import NumberField from '@/components/NumberField.vue'
import DateField from '@/components/DateField.vue'
import FaIcon from '@/components/FaIcon.vue'
import FaMetric from '@/components/FaMetric.vue'
import FaSegmented from '@/components/FaSegmented.vue'
import FaTrendChart from '@/components/FaTrendChart.vue'
import { createBodyRecord, deleteBodyRecord, fetchBodyRecords, fetchLatestBodyRecord, updateBodyRecord } from '@/api/body'
import type { BodyRecord, BodyRecordCreateRequest } from '@/types/body'
import type { TrendPoint } from '@/types/ui'
import { fieldErrorText, readFieldErrors, type ApiFieldError, type PageState } from '@/types/api'
import { isApiError, isSessionExpiredError, isVersionConflict, pageItems, pageMeta } from '@/utils/request'
import { toFailureView, type FailureView } from '@/utils/errorMessage'
import { uuidV4, isUuidV4 } from '@/utils/uuid'
import {
  DEFAULT_BODY_RECORD_WINDOW_DAYS,
  addDays,
  formatDateTimeValue,
  instantToDisplay,
  instantToLocalDateTime,
  isFutureBeyondTolerance,
  localDateTimeToInstant,
  nowTimeValue,
  splitLocalDateTime,
  todayDateValue
} from '@/utils/date'
import { formatDecimal, isDecimalWithin, isWithinDecimal6_2 } from '@/utils/units'

/**
 * 窗口分段：7/30/90 天与 1 年。上限 366 天是契约护栏（MAX_DATE_RANGE_DAYS），所以最长只能到 365；
 * 默认仍是后端约定的 90 天窗口。
 */
const windowOptions = [
  { value: '7', label: '7天' },
  { value: '30', label: '30天' },
  { value: String(DEFAULT_BODY_RECORD_WINDOW_DAYS), label: '90天' },
  { value: '365', label: '1年' }
]

const MAX_NOTES_LENGTH = 500

const records = ref<BodyRecord[]>([])
const latest = ref<BodyRecord | null>(null)
const total = ref<number | null>(null)
const hasNext = ref(false)
const page = ref(1)
const size = ref(20)
const windowDays = ref<number>(DEFAULT_BODY_RECORD_WINDOW_DAYS)

/** 列表默认只露 5 条：整页表格压在趋势图旁边就是本轮要消灭的拥挤形态。 */
const COLLAPSED_COUNT = 5
const expanded = ref('')
const showAllRecords = ref(false)
/** 幂等/补录这类口径解释默认收起：少文字不等于删掉解释，点「为什么」才展开（提示词 §2.3）。 */
const showIdempotentHelp = ref(false)

const visibleRecords = computed(() =>
  showAllRecords.value ? records.value : records.value.slice(0, COLLAPSED_COUNT)
)

/** 折线只画窗口内真实存在的测量点：不补零、不插值、不在单点时连线。 */
const trendPoints = computed<TrendPoint[]>(() =>
  [...records.value]
    .sort((a, b) => Date.parse(a.measuredAt) - Date.parse(b.measuredAt))
    .map((r) => ({ t: Date.parse(r.measuredAt), label: r.businessDate.slice(5), value: r.weightKg }))
)

const trendNote = computed(() => {
  const n = trendPoints.value.length
  if (n === 0) return ''
  if (n === 1) return `窗口内只有 1 次测量，只画点不连线`
  return `${rangeFrom.value} 至 ${rangeTo.value} · ${n} 次测量`
})

const latestCaption = computed(() =>
  latest.value ? `测量于 ${instantToDisplay(latest.value.measuredAt)}` : '还没有测量'
)

function sourceText(value: string): string {
  return value === 'IMPORT' ? '导入' : '手动'
}

function toggleDetail(id: string): void {
  expanded.value = expanded.value === id ? '' : id
}

const state = ref<PageState>('loading')
const failure = ref<FailureView | null>(null)
const topFailure = ref<FailureView | null>(null)
const successTip = ref('')
const busy = ref(false)

const showForm = ref(false)
const submitFailure = ref<FailureView | null>(null)
const fieldErrors = ref<ApiFieldError[]>([])
const localErrors = ref<Record<string, string>>({})
const conflictText = ref('')
const submitFailedOnce = ref(false)

const form = reactive({
  mode: 'create' as 'create' | 'edit',
  id: '',
  uuid: '',
  lockVersion: 0,
  measuredDate: '',
  measuredTime: '',
  weightKg: null as number | null,
  waistCm: null as number | null,
  bodyFatPercent: null as number | null,
  notes: ''
})

const rangeFrom = computed(() => addDays(todayDateValue(), -(windowDays.value - 1)))
const rangeTo = computed(() => todayDateValue())
/**
 * 第 2 态只在「彻底没有记录」时出现：列表窗口筛空但 latest 有值时仍渲染成功态（保住「最近一次」卡片）。
 * 而 `latest` 是全表 measuredAt 最新一条，所以 latest===null 等价于一条记录都没有。
 */
const windowHintTitle = computed(() => '还没有记录')
const emptyDesc = computed(() => '点「记录」写第一条：测量时间默认是现在，也可以改成更早的时间补录。')

/** 控件已就地显示的字段：其余逐字段错误进横幅，一条不丢也不重复显示。 */
const CONTROL_FIELDS = ['measuredAt', 'weightKg', 'waistCm', 'bodyFatPercent', 'notes', 'clientRecordUuid', 'lockVersion']

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

/** 一次读取「最近一次 + 分页历史」：所有写操作之后都走它，页面不本地拼接数组。 */
async function fetchBoth(resetPage: boolean): Promise<void> {
  const targetPage = resetPage ? 1 : page.value
  const [latestRes, listRes] = await Promise.all([
    fetchLatestBodyRecord(),
    fetchBodyRecords({
      from: rangeFrom.value,
      to: rangeTo.value,
      page: targetPage,
      size: size.value,
      sort: 'measuredAt,desc'
    })
  ])
  const rows = pageItems(listRes)
  const meta = pageMeta(listRes, targetPage, size.value)
  latest.value = latestRes
  records.value = resetPage ? rows : records.value.concat(rows)
  total.value = meta.total
  hasNext.value = meta.hasNext
  page.value = targetPage
}

async function loadRaw(resetPage: boolean): Promise<void> {
  if (resetPage) {
    state.value = records.value.length === 0 ? 'loading' : 'success'
    failure.value = null
    topFailure.value = null
  }
  try {
    await fetchBoth(resetPage)
    // 只有「列表为空且连最新记录都没有」才算第 2 态：
    // 窗口过滤把历史筛空时，「最近一次」卡片仍然要留在屏幕上（A7/A8 的观测点）
    state.value = records.value.length === 0 && latest.value === null ? 'empty' : 'success'
  } catch (err2) {
    if (isSessionExpiredError(err2)) {
      state.value = 'sessionExpired'
      return
    }
    const view = toFailureView(err2)
    if (records.value.length === 0) {
      failure.value = view
      state.value = 'error'
    } else {
      topFailure.value = view
      state.value = 'success'
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

function setWindow(days: number): void {
  windowDays.value = days
  expanded.value = ''
  showAllRecords.value = false
  successTip.value = ''
  void load(true)
}

function resetForm(): void {
  submitFailure.value = null
  fieldErrors.value = []
  localErrors.value = {}
  conflictText.value = ''
  submitFailedOnce.value = false
}

/** 新增：这是**唯一**生成新 clientRecordUuid 的地方（A6 的落点）。 */
function startCreate(): void {
  form.mode = 'create'
  form.id = ''
  form.uuid = uuidV4()
  form.lockVersion = 0
  form.measuredDate = todayDateValue()
  form.measuredTime = nowTimeValue()
  form.weightKg = null
  form.waistCm = null
  form.bodyFatPercent = null
  form.notes = ''
  resetForm()
  showForm.value = true
  uni.pageScrollTo({ scrollTop: 0, duration: 200 })
}

function startEdit(r: BodyRecord): void {
  const local = instantToLocalDateTime(r.measuredAt)
  const parts = splitLocalDateTime(local)
  form.mode = 'edit'
  form.id = r.id
  form.uuid = r.clientRecordUuid
  form.lockVersion = r.lockVersion
  form.measuredDate = parts.date || todayDateValue()
  form.measuredTime = parts.time || '00:00'
  form.weightKg = r.weightKg
  form.waistCm = r.waistCm
  form.bodyFatPercent = r.bodyFatPercent
  form.notes = r.notes ?? ''
  resetForm()
  showForm.value = true
  uni.pageScrollTo({ scrollTop: 0, duration: 200 })
}

function closeForm(): void {
  showForm.value = false
  resetForm()
}

async function reloadAndClose(): Promise<void> {
  if (busy.value) return
  busy.value = true
  try {
    closeForm()
    await loadRaw(true)
  } finally {
    busy.value = false
  }
}

function useNow(): void {
  form.measuredDate = todayDateValue()
  form.measuredTime = nowTimeValue()
}

function onTimeChange(evt: { detail: { value: string | number } }): void {
  const raw = Array.isArray(evt.detail.value) ? evt.detail.value[0] : evt.detail.value
  form.measuredTime = String(raw ?? '')
}

/**
 * uni 运行时给 textarea/input 的载荷是 `{detail:{value}}`，而 @vue/runtime-dom 把 `<textarea>`
 * 标成 DOM `InputEvent`。按运行时事实取值，取不到再退回 `target.value`（H5）。
 */
function inputValueFrom(evt: unknown): string {
  const detail = (evt as { detail?: { value?: unknown } })?.detail
  if (detail && typeof detail.value === 'string') return detail.value
  const target = (evt as { target?: { value?: unknown } })?.target
  return typeof target?.value === 'string' ? target.value : ''
}

function onNotesInput(evt: Event): void {
  form.notes = inputValueFrom(evt)
}

/** 只做契约允许的校验；返回 null 表示本地不通过，此时不发请求，表单与 UUID 原样保留。 */
function buildPayload(): BodyRecordCreateRequest | null {
  const local: Record<string, string> = {}

  if (form.measuredDate === '') {
    local.measuredAt = '测量日期必填'
  }
  if (form.measuredTime === '') {
    local.measuredAt = local.measuredAt || '测量时间必填'
  }
  let instant: string | null = null
  if (!local.measuredAt) {
    instant = localDateTimeToInstant(formatDateTimeValue(form.measuredDate, form.measuredTime))
    if (instant === null) local.measuredAt = '测量时间无法解析为瞬时值（日期或时间不合法）'
    else if (isFutureBeyondTolerance(instant)) {
      local.measuredAt = '测量时间不能晚于当前时间 5 分钟以上（服务端护栏 FUTURE_MEASURED_AT）'
    }
  }

  const weight = form.weightKg
  if (weight === null || !Number.isFinite(weight)) local.weightKg = '体重必填'
  else if (!(weight > 0)) local.weightKg = '体重必须大于 0'
  else if (!isWithinDecimal6_2(weight)) local.weightKg = '体重要落在 DECIMAL(6,2) 量程内且最多 2 位小数'

  const waist = form.waistCm
  if (waist !== null) {
    if (!(waist > 0)) local.waistCm = '腰围必须大于 0'
    else if (!isWithinDecimal6_2(waist)) local.waistCm = '腰围要落在 DECIMAL(6,2) 量程内且最多 2 位小数'
  }

  const fat = form.bodyFatPercent
  if (fat !== null) {
    if (fat < 0 || fat > 100) local.bodyFatPercent = '体脂率范围 0–100（契约明示区间，非医学判定）'
    else if (!isDecimalWithin(fat, 2)) local.bodyFatPercent = '体脂率最多 2 位小数'
  }

  const notes = form.notes.trim()
  if (notes.length > MAX_NOTES_LENGTH) local.notes = `备注最长 ${MAX_NOTES_LENGTH} 字`

  if (!local.clientRecordUuid && form.mode === 'create' && !isUuidV4(form.uuid)) {
    local.clientRecordUuid = `UUID 格式非法：${form.uuid}`
  }

  localErrors.value = local
  if (Object.keys(local).length > 0) return null

  return {
    measuredAt: instant as string,
    weightKg: weight as number,
    waistCm: waist,
    bodyFatPercent: fat,
    clientRecordUuid: form.uuid,
    notes: notes === '' ? null : notes
    // 注意：不写 businessDate —— 由服务端按账户时区从 measuredAt 推导（§1「时区」、A7）
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

  const isCreate = form.mode === 'create'
  const carriedUuid = payload.clientRecordUuid
  const editLockVersion = form.lockVersion
  busy.value = true
  submitFailure.value = null
  fieldErrors.value = []
  conflictText.value = ''
  try {
    const saved = isCreate
      ? await createBodyRecord(payload)
      : await updateBodyRecord(form.id, { ...payload, lockVersion: editLockVersion })

    const replay = saved.created === false || saved.idempotentReplay === true
    const head = isCreate
      ? replay
        ? `幂等命中：同 clientRecordUuid（${carriedUuid}）服务端回读既有记录 ID ${saved.id}，created=${String(saved.created)}、idempotentReplay=${String(saved.idempotentReplay)}，历史行数没有增加（验收 A6）。`
        : `已新增记录 ID ${saved.id}：业务日期由服务端推导为 ${saved.businessDate}（本页未提交该字段）。`
      : `已修改记录 ID ${saved.id}：业务日期重算为 ${saved.businessDate}，lockVersion ${editLockVersion} → ${saved.lockVersion}。`
    closeForm()
    await loadRaw(true)
    // 「最近一次」用重读后的服务端结果说明，补录到底改没改最新由服务端说了算（验收 A7）
    const latestText =
      latest.value === null
        ? '「最近一次」目前为空'
        : `重读后的「最近一次」= ${formatDecimal(latest.value.weightKg, 'kg')}（测量 ${instantToDisplay(latest.value.measuredAt)}）`
    successTip.value = `${head}${latestText}。`
  } catch (err2) {
    const view = absorbError(err2)
    submitFailure.value = view
    submitFailedOnce.value = true
    if (view.sessionExpired) {
      state.value = 'sessionExpired'
    } else if (isVersionConflict(err2) && !isCreate) {
      conflictText.value = `记录 ID ${form.id} 的 lockVersion=${form.lockVersion} 已过期，本次提交未写入。请重新读取后在最新值上重做。`
    }
    uni.showToast({ title: view.title, icon: 'none', duration: 2000 })
  } finally {
    busy.value = false
  }
}

function askDelete(r: BodyRecord): void {
  uni.showModal({
    title: '删除记录',
    content: `将删除 ${instantToDisplay(r.measuredAt)} 的 ${formatDecimal(r.weightKg, 'kg')} 记录（ID ${r.id}）。删除不可恢复；若这是最新一条，「最近一次」会回退到上一条。`,
    confirmText: '删除',
    cancelText: '取消',
    success: (res) => {
      if (res.confirm) void runDelete(r)
    }
  })
}

/** 删除后**重新读取** latest 与列表，用服务端结果渲染回退（验收 A8），不本地 splice。 */
async function runDelete(r: BodyRecord): Promise<void> {
  if (busy.value) {
    uni.showToast({ title: '有请求正在进行，请稍候', icon: 'none', duration: 1500 })
    return
  }
  busy.value = true
  topFailure.value = null
  submitFailure.value = null
  try {
    await deleteBodyRecord(r.id)
    await loadRaw(true)
    successTip.value =
      latest.value === null
        ? `已删除记录 ID ${r.id}；重新读取后「最近一次」返回 data:null —— 没有记录时是空数据而不是报错（验收 A8）。`
        : `已删除记录 ID ${r.id}；「最近一次」已按服务端结果回退为 ${formatDecimal(latest.value.weightKg, 'kg')}（测量 ${instantToDisplay(latest.value.measuredAt)}）。`
  } catch (err2) {
    const view = toFailureView(err2)
    if (view.sessionExpired) state.value = 'sessionExpired'
    else {
      topFailure.value = view
      state.value = records.value.length === 0 ? 'error' : 'success'
    }
    uni.showToast({ title: view.title, icon: 'none', duration: 2000 })
  } finally {
    busy.value = false
  }
}

onLoad(() => {
  void load(true)
})

onPullDownRefresh(() => {
  // 下拉刷新是用户显式发起的写后重读：忙时直接收尾，不排队、不假装已刷新
  if (busy.value) {
    uni.stopPullDownRefresh()
    return
  }
  busy.value = true
  successTip.value = ''
  loadRaw(true).finally(() => {
    busy.value = false
    uni.stopPullDownRefresh()
  })
})
</script>

<style lang="scss" scoped>
.bd-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $fa-space-tight;
  margin-bottom: $fa-space-tight;
}

.bd-head__title {
  display: flex;
  flex-direction: column;
  flex: 1;
}

.bd-metrics {
  margin-bottom: $fa-space-tight;
}

/* 一条记录一行：收起时只有数字 + 日期 + 「详情」 */
.bd-item {
  padding: 20rpx $fa-space-card;
}

.bd-item__head {
  display: flex;
  align-items: center;
  gap: $fa-space-tight;
  min-height: 96rpx;
}

.bd-item__head--hover {
  background-color: $fa-page-bg;
}

.bd-item__main {
  display: flex;
  align-items: baseline;
  flex: 0 0 auto;
}

.bd-item__num {
  font-size: $fa-num-lg;
  font-weight: 600;
  color: $fa-text;
}

.bd-item__more {
  display: flex;
  align-items: center;
  gap: 4rpx;
  margin-left: auto;
  flex: 0 0 auto;
}

.bd-item__more-text {
  font-size: $fa-font-ui;
  color: $fa-accent;
}

.bd-item__detail {
  margin-top: $fa-space-tight;
  padding-top: $fa-space-tight;
  border-top: 1px solid $fa-line;
}

.bd-actions {
  margin-top: $fa-space-tight;
}

.bd-help-toggle {
  margin-top: 12rpx;
  width: 180rpx;
}

.bd-more {
  margin: 12rpx 0 $fa-space-section;
}

.bd-block-title {
  display: block;
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  margin-bottom: 4px;
}

.bd-code {
  display: block;
  font-size: $fa-font-ui;
  color: $fa-text-mute;
  margin-top: 4px;
  word-break: break-all;
}

.bd-fielderr {
  border-left: 3px solid $fa-danger;
  padding: 6px 0 6px 10px;
  margin-top: 6px;
}

.bd-fielderr__name {
  display: block;
  font-size: $fa-font-ui;
  font-weight: 600;
  color: $fa-danger;
}

.bd-label {
  font-size: $fa-font-ui;
  color: $fa-text-sub;
  margin-bottom: 6px;
}

.bd-count {
  display: block;
  font-size: $fa-font-ui;
  color: $fa-text-mute;
  margin-top: 2px;
}

.bd-datetime {
  display: flex;
  gap: 8px;
}

.bd-time {
  flex: 0 0 38%;
}

.bd-time__value {
  min-height: $fa-tap;
  display: flex;
  align-items: center;
}

.bd-uuid {
  display: block;
  font-size: $fa-font-ui;
  word-break: break-all;
  color: $fa-text-sub;
}

.bd-error {
  display: block;
  margin-top: 4px;
  font-size: $fa-font-ui;
  color: $fa-danger;
}

.bd-form {
  margin-top: 12px;
  /* 活动表单的边界要能单独认出来：淡黄对白只有 1.4:1 */
  border-color: $fa-control-border;
}
</style>
