<template>
  <view class="fa-page ms">
    <view class="fa-banner fa-banner--info">
      <text class="fa-prose">你自己向供应商充值并配置 API Key（BYOK），模型费用从你的供应商账户扣除，与本应用的预算是两本账。</text>
      <text v-if="showByokHelp" class="fa-prose">
        密钥只经 HTTPS 发到后端加密保存，前端不会收到它，日志里也不会有；这里只显示脱敏尾部。
      </text>
      <view class="fa-btn fa-btn--sm ms-toggle" @tap="showByokHelp = !showByokHelp">
        <FaIcon :name="showByokHelp ? 'chevron' : 'alert'" tone="accent" size="36rpx" />
        <text>{{ showByokHelp ? '收起' : '密钥去哪了' }}</text>
      </view>
    </view>

    <!-- ==================== 凭据 ==================== -->
    <view class="fa-section-title">
      <FaIcon name="key" tone="accent" size="40rpx" />
      <text>密钥</text>
    </view>
    <StateBlock
      :state="credentialState"
      :failure="credentialFailure"
      :submit-failure="credentialSubmitFailure"
      :success-tip="credentialTip"
      loading-text="正在读取凭据状态…"
      empty-title="还没有配置 API Key"
      empty-desc="没有凭据时 AI 教练不会发起任何模型调用。"
      @retry="loadCredential"
    >
      <view class="fa-card">
        <view class="fa-row ms-status-row">
          <text class="fa-chip" :class="statusChipClass">{{ credential ? credentialStatusLabel(credential.status) : '未配置' }}</text>
          <text class="fa-muted">{{ credential ? `版本 v${credential.credentialVersion} · 尾部 ${credential.keySuffix || '—'}` : '保存一把 Key 才能问 AI 教练' }}</text>
        </view>
        <view class="fa-btn fa-btn--sm ms-toggle" @tap="showCredMeta = !showCredMeta">
          <FaIcon :name="showCredMeta ? 'chevron' : 'records'" tone="accent" size="36rpx" />
          <text>{{ showCredMeta ? '收起' : '更多字段' }}</text>
        </view>
        <view v-if="showCredMeta">
          <view class="fa-row">
            <text class="fa-row__label">供应商</text>
            <text class="fa-row__value">{{ credential ? credential.providerCode : 'DEEPSEEK' }}</text>
          </view>
          <view class="fa-row">
            <text class="fa-row__label">最近校验</text>
            <text class="fa-row__value">{{ instantToDisplay(credential ? credential.validatedAt : null) }}</text>
          </view>
          <view v-if="credential && credential.revokedAt" class="fa-row">
            <text class="fa-row__label">撤销时间</text>
            <text class="fa-row__value">{{ instantToDisplay(credential.revokedAt) }}</text>
          </view>
          <text class="fa-hint">后端只返回 id、状态、脱敏尾部与版本；密文、nonce、指纹与明文都不出现在任何响应或日志里。</text>
        </view>
      </view>

      <view class="ms-form">
        <view class="fa-field">
          <view class="fa-field__label">
            <text>API Key</text>
            <text class="fa-field__label--req">必填</text>
          </view>
          <!-- password 型输入：不让值在任何回显里出现；提交成功后立刻从内存清掉 -->
          <input
            v-model="apiKeyInput"
            class="fa-input"
            password
            placeholder="粘贴 sk- 开头的 Key"
            :adjust-position="false"
          />
        </view>
        <view class="fa-field">
          <view class="fa-field__label"><text>备注名（可选）</text></view>
          <input v-model="labelInput" class="fa-input" maxlength="80" placeholder="例如：我的 DeepSeek" />
        </view>
        <view class="fa-btn-row">
          <button
            class="fa-btn fa-btn--primary"
            :class="{ 'fa-btn--disabled': saving || !apiKeyInput.trim() }"
            :disabled="saving || !apiKeyInput.trim()"
            @tap="saveCredential"
          >
            {{ saving ? '保存中…' : (credential ? '替换 Key' : '保存 Key') }}
          </button>
          <button
            v-if="credential && credential.status !== 'REVOKED'"
            class="fa-btn fa-btn--danger"
            :class="{ 'fa-btn--disabled': revoking }"
            :disabled="revoking"
            @tap="confirmRevoke"
          >
            {{ revoking ? '撤销中…' : '撤销 Key' }}
          </button>
        </view>
        <text class="fa-hint">
          同一把 Key 重复保存不会增加凭据版本；换不同 Key 会在同一行递增版本，历史账本仍保留旧版本号。
          撤销后不会再发起新的模型调用，但**已经发出去的请求无法追回、费用按实际结果计**。
        </text>
      </view>
    </StateBlock>

    <!-- ==================== 余额 ==================== -->
    <view class="fa-section-title">
      <FaIcon name="wallet" tone="accent" size="40rpx" />
      <text>余额</text>
    </view>
    <StateBlock
      :state="balanceState"
      :failure="balanceFailure"
      :submit-failure="balanceSubmitFailure"
      loading-text="正在查询余额…"
      empty-title="暂无法获取"
      empty-desc="还没有成功的余额快照。查询失败时不会显示 0。"
      @retry="loadBalance"
    >
      <view v-if="balance" class="fa-card">
        <view v-if="balance.stale" class="fa-banner fa-banner--warn">
          <text class="fa-prose">这不是实时结果，是上次成功查询到的快照（{{ instantToDisplay(balance.fetchedAt) }}）。</text>
        </view>
        <view class="fa-metrics">
          <FaMetric class="fa-metric" label="总额" :value="moneyText(balance.totalBalance, balance.currency)" />
          <FaMetric class="fa-metric" label="赠送" :value="moneyText(balance.grantedBalance, balance.currency)" />
          <FaMetric class="fa-metric" label="充值" :value="moneyText(balance.toppedUpBalance, balance.currency)" />
        </view>
        <view class="fa-btn fa-btn--sm ms-toggle" @tap="showBalanceMeta = !showBalanceMeta">
          <FaIcon :name="showBalanceMeta ? 'chevron' : 'records'" tone="accent" size="36rpx" />
          <text>{{ showBalanceMeta ? '收起' : '币种、时间与判定' }}</text>
        </view>
        <view v-if="showBalanceMeta">
          <view class="fa-row">
            <text class="fa-row__label">是否充足</text>
            <text class="fa-row__value">{{ balanceAvailabilityText(balance.isAvailable) }}</text>
          </view>
          <view class="fa-row">
            <text class="fa-row__label">币种</text>
            <text class="fa-row__value">{{ currencyDisplayName(balance.currency) }}</text>
          </view>
          <view class="fa-row">
            <text class="fa-row__label">查询时间</text>
            <text class="fa-row__value">{{ instantToDisplay(balance.fetchedAt) }}</text>
          </view>
          <text class="fa-hint">
            这是供应商**账户级**余额快照，可能被本应用之外的调用影响，也不是单把 Key 独享的额度；
            与本应用预算币种不同时**分开显示**，不做汇率换算。
          </text>
        </view>
      </view>
      <view class="fa-btn-row">
        <button class="fa-btn fa-btn--ghost" :disabled="balanceState === 'loading'" @tap="loadBalance">重新查询</button>
      </view>
      <!-- 空态也要能重试：StateBlock 的 empty 分支只渲染 #action 插槽，不渲染默认插槽 -->
      <template #action>
        <view class="fa-btn-row">
          <button class="fa-btn fa-btn--ghost" :disabled="balanceState === 'loading'" @tap="loadBalance">重新查询</button>
        </view>
      </template>
    </StateBlock>

    <!-- ==================== 预算 ==================== -->
    <view class="fa-section-title">
      <FaIcon name="shield" tone="accent" size="40rpx" />
      <text>预算</text>
    </view>
    <StateBlock
      :state="budgetState"
      :failure="budgetFailure"
      :submit-failure="budgetSubmitFailure"
      :success-tip="budgetTip"
      loading-text="正在读取预算…"
      empty-title="还没有设置预算"
      empty-desc="留空表示不设该限；不设置时 AI 教练仍可调用，直至触发轮数或时间上限。"
      @retry="loadBudget"
    >
      <view class="fa-card">
        <view class="fa-metrics">
          <FaMetric class="fa-metric" label="单次上限" :value="budget ? budgetLimitText(budget.maxTaskCost, budgetCurrencyUnit) : budgetLimitText(null)" />
          <FaMetric class="fa-metric" label="每日上限" :value="budget ? budgetLimitText(budget.maxDailyCost, budgetCurrencyUnit) : budgetLimitText(null)" />
        </view>
        <view class="fa-btn fa-btn--sm ms-toggle" @tap="showBudgetMeta = !showBudgetMeta">
          <FaIcon :name="showBudgetMeta ? 'chevron' : 'records'" tone="accent" size="36rpx" />
          <text>{{ showBudgetMeta ? '收起' : '其它限额' }}</text>
        </view>
        <view v-if="showBudgetMeta">
          <view class="fa-row">
            <text class="fa-row__label">币种</text>
            <text class="fa-row__value">{{ budget ? currencyDisplayName(budget.currency) : 'CNY（人民币）' }}</text>
          </view>
          <view class="fa-row">
            <text class="fa-row__label">模型调用次数</text>
            <text class="fa-row__value">{{ budget ? budgetLimitText(budget.maxModelCalls, ' 次') : budgetLimitText(null) }}</text>
          </view>
          <view class="fa-row">
            <text class="fa-row__label">工具执行次数</text>
            <text class="fa-row__value">{{ budget ? budgetLimitText(budget.maxToolCalls, ' 次') : budgetLimitText(null) }}</text>
          </view>
          <view class="fa-row">
            <text class="fa-row__label">单次输出上限</text>
            <text class="fa-row__value">{{ budget ? budgetLimitText(budget.maxOutputTokens, ' tokens') : budgetLimitText(null) }}</text>
          </view>
          <view class="fa-row">
            <text class="fa-row__label">启用预算</text>
            <text class="fa-row__value">{{ budget ? budgetEnabledText(budget.enabled) : '是' }}</text>
          </view>
        </view>
      </view>

      <view class="ms-form">
        <view v-for="field in budgetFields" :key="field.key" class="fa-field">
          <view class="fa-field__label">
            <text>{{ field.title }}</text>
            <text class="fa-muted ms-hint-inline">留空＝不设限</text>
          </view>
          <input
            class="fa-input"
            type="digit"
            :value="field.model"
            :placeholder="field.placeholder"
            @input="(evt) => onBudgetInput(field, evt)"
          />
          <text v-if="field.localError" class="fa-hint ms-error">{{ field.localError }}</text>
        </view>

        <view class="fa-row ms-switchrow">
          <text class="fa-row__label">启用预算</text>
          <switch :checked="enabledFlag" :color="switchOnColor" @change="onEnabledChange" />
        </view>

        <view class="fa-btn-row">
          <button class="fa-btn fa-btn--primary" :class="{ 'fa-btn--disabled': budgetSaving }" :disabled="budgetSaving" @tap="saveBudget">
            {{ budgetSaving ? '保存中…' : '保存预算' }}
          </button>
        </view>
        <text class="fa-hint">
          预算的承诺是**保守预占 + 逐轮阻止后续请求**：预估按"输入全部按未命中缓存价、输出按满额"计，因此实际花费可能低于预估；
          但已经发出去的请求若超出预估，只会记录超支并停止后续调用，不能撤销供应商已产生的扣费。
        </text>
      </view>
    </StateBlock>
  </view>
</template>

<script setup lang="ts">
/**
 * 模型设置页（BYOK）：凭据保存/替换/撤销、余额快照、本应用预算。
 * 契约见 docs/agent-v1-design.md §6 与 §11；余额与计费的口径见 docs/agent-v1-protocol.md §6–§8。
 *
 * 三条不可让的规矩：
 * 1. 明文 Key 只存在于提交的那一瞬间：成功后立即清空输入与内存，不写存储、不进日志、不在任何提示里回显（尾部只能用服务端返回的 keySuffix）；
 * 2. 「拿不到」一律显示 UNKNOWN_BALANCE_TEXT，不显示 0 —— 失败回落时显示上次快照 + stale 标记；
 * 3. 预算的 null 是「不设限」，不是 0；币种不换算、不相减。
 */
import { computed, reactive, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  fetchBudget,
  fetchModelCredential,
  fetchProviderBalance,
  revokeModelCredential,
  saveModelCredential,
  updateBudget
} from '@/api/model'
import StateBlock from '@/components/StateBlock.vue'
import FaIcon from '@/components/FaIcon.vue'
import FaMetric from '@/components/FaMetric.vue'
import type { BudgetPolicy, ModelCredentialSummary, ProviderBalance } from '@/types/model'
import {
  budgetEnabledText,
  budgetLimitText,
  balanceAvailabilityText,
  currencyUnitLabel,
  credentialStatusLabel,
  currencyDisplayName,
  moneyText
} from '@/types/model'
import type { PageState } from '@/types/api'
import { isApiError } from '@/utils/request'
import { toFailureView, briefError, type FailureView } from '@/utils/errorMessage'
import { instantToDisplay } from '@/utils/date'
import { inputValue, detailValue } from '@/utils/events'
import { sanitizeDecimalInput } from '@/utils/units'
import { switchOnColor } from '@/styles/theme'

const credential = ref<ModelCredentialSummary | null>(null)
const balance = ref<ProviderBalance | null>(null)
const budget = ref<BudgetPolicy | null>(null)

/** 口径/审计字段默认折叠：第一眼只给"配没配、还剩多少、限到多少"，其余点按展开，一条不删。 */
const showByokHelp = ref(false)
const showCredMeta = ref(false)
const showBalanceMeta = ref(false)
const showBudgetMeta = ref(false)

const credentialState = ref<PageState>('loading')
const balanceState = ref<PageState>('loading')
const budgetState = ref<PageState>('loading')

const credentialFailure = ref<FailureView | null>(null)
const balanceFailure = ref<FailureView | null>(null)
const budgetFailure = ref<FailureView | null>(null)
const credentialSubmitFailure = ref<FailureView | null>(null)
const balanceSubmitFailure = ref<FailureView | null>(null)
const budgetSubmitFailure = ref<FailureView | null>(null)

const credentialTip = ref('')
const budgetTip = ref('')

const apiKeyInput = ref('')
const labelInput = ref('')
const saving = ref(false)
const revoking = ref(false)
const budgetSaving = ref(false)

const statusChipClass = computed(() => {
  const status = credential.value?.status
  if (status === 'ACTIVE') return 'fa-chip--primary'
  if (status === 'INVALID') return 'fa-chip--warning'
  if (status === 'REVOKED') return 'fa-chip--danger'
  return 'fa-chip'
})

const budgetCurrencyUnit = computed(() => currencyUnitLabel(budget.value?.currency))

onShow(() => {
  loadCredential()
  loadBalance()
  loadBudget()
})

/** 401/403 一类会话与账户问题交给请求封装集中处理；这里只区分「加载失败」与「空」。 */
function failInto(err: unknown, target: { set: (v: FailureView) => void }, state: (v: PageState) => void): void {
  target.set(toFailureView(err))
  state(isSessionError(err) ? 'sessionExpired' : 'error')
}

function isSessionError(err: unknown): boolean {
  return isApiError(err) && (err.code === 'UNAUTHENTICATED' || err.code === 'SESSION_EXPIRED')
}

async function loadCredential(): Promise<void> {
  credentialState.value = 'loading'
  credentialFailure.value = null
  try {
    credential.value = await fetchModelCredential()
    credentialState.value = credential.value ? 'success' : 'empty'
  } catch (err) {
    credential.value = null
    failInto(err, { set: (v) => (credentialFailure.value = v) }, (v) => (credentialState.value = v))
  }
}

async function loadBalance(): Promise<void> {
  balanceState.value = 'loading'
  balanceFailure.value = null
  balanceSubmitFailure.value = null
  try {
    balance.value = await fetchProviderBalance()
    // 后端查不到时给 null：这是「暂无法获取」，不是 0，也不是成功
    balanceState.value = balance.value ? 'success' : 'empty'
  } catch (err) {
    balance.value = null
    failInto(err, { set: (v) => (balanceFailure.value = v) }, (v) => (balanceState.value = v))
  }
}

async function loadBudget(): Promise<void> {
  budgetState.value = 'loading'
  budgetFailure.value = null
  try {
    budget.value = await fetchBudget()
    syncBudgetForm()
    budgetState.value = budget.value ? 'success' : 'empty'
  } catch (err) {
    budget.value = null
    failInto(err, { set: (v) => (budgetFailure.value = v) }, (v) => (budgetState.value = v))
  }
}

// ---------------------------------------------------------------------------
// 凭据
// ---------------------------------------------------------------------------

async function saveCredential(): Promise<void> {
  const raw = apiKeyInput.value.trim()
  if (!raw) return
  saving.value = true
  credentialSubmitFailure.value = null
  credentialTip.value = ''
  try {
    const summary = await saveModelCredential({ apiKey: raw, label: labelInput.value.trim() || null })
    credential.value = summary
    // 明文只活在这一次请求里：提交成功立刻抹掉，不做任何回显
    apiKeyInput.value = ''
    credentialState.value = 'success'
    credentialTip.value = `已保存，Key 尾部 ${summary.keySuffix || '—'}`
    uni.showToast({ title: '凭据已保存', icon: 'none' })
  } catch (err) {
    credentialSubmitFailure.value = toFailureView(err)
    // 提交失败也不保留明文：让用户重新粘贴，避免密钥残留在页面状态里
    apiKeyInput.value = ''
    credentialState.value = credential.value ? 'success' : 'empty'
    uni.showToast({ title: briefError(err), icon: 'none' })
  } finally {
    saving.value = false
  }
}

function confirmRevoke(): void {
  const tail = credential.value?.keySuffix ?? ''
  uni.showModal({
    title: '撤销 API Key',
    content: `撤销后清除已保存的密钥并拒绝新的模型调用。已发出的请求不可撤销、费用按实际结果计。确认撤销${tail ? `尾部为 ${tail} 的 Key` : '该 Key'}？`,
    success: (res) => {
      if (res.confirm) void doRevoke()
    }
  })
}

async function doRevoke(): Promise<void> {
  revoking.value = true
  credentialSubmitFailure.value = null
  try {
    credential.value = await revokeModelCredential()
    credentialTip.value = '已撤销，新的模型调用会被拒绝'
    credentialState.value = credential.value ? 'success' : 'empty'
    await loadBalance()
  } catch (err) {
    credentialSubmitFailure.value = toFailureView(err)
    credentialState.value = credential.value ? 'success' : 'empty'
    uni.showToast({ title: briefError(err), icon: 'none' })
  } finally {
    revoking.value = false
  }
}

// ---------------------------------------------------------------------------
// 预算表单
// ---------------------------------------------------------------------------

interface BudgetField {
  key: 'maxTaskCost' | 'maxDailyCost' | 'maxModelCalls' | 'maxToolCalls' | 'maxOutputTokens'
  title: string
  placeholder: string
  model: string
  integerOnly: boolean
  localError: string
}

const budgetFields = reactive<BudgetField[]>([
  { key: 'maxTaskCost', title: '单次任务上限（元）', placeholder: '例如 0.50', model: '', integerOnly: false, localError: '' },
  { key: 'maxDailyCost', title: '每日上限（元）', placeholder: '例如 5.00', model: '', integerOnly: false, localError: '' },
  { key: 'maxModelCalls', title: '单次问题最大模型调用次数', placeholder: '例如 4', model: '', integerOnly: true, localError: '' },
  { key: 'maxToolCalls', title: '单次问题最大工具执行次数', placeholder: '例如 8', model: '', integerOnly: true, localError: '' },
  { key: 'maxOutputTokens', title: '单次请求输出上限（tokens）', placeholder: '例如 1024', model: '', integerOnly: true, localError: '' }
])

const enabledFlag = ref(true)

function syncBudgetForm(): void {
  for (const field of budgetFields) {
    const value = budget.value ? budget.value[field.key] : null
    field.model = value === null || value === undefined ? '' : String(value)
    field.localError = ''
  }
  enabledFlag.value = budget.value ? budget.value.enabled : true
}

function onBudgetInput(field: BudgetField, evt: Event): void {
  const allowed = field.integerOnly ? { maxDecimals: 0 } : { maxDecimals: 2 }
  field.model = sanitizeDecimalInput(inputValue(evt), allowed)
  field.localError = ''
}

function onEnabledChange(evt: Event): void {
  enabledFlag.value = detailValue(evt) === true
}

/** 只校验「正数、整数性、长度」，不引入任何医学或业务阈值；null 表示不设限。 */
function parseBudgetField(field: BudgetField): number | null | undefined {
  const raw = field.model.trim()
  if (raw === '') return null
  const num = Number(raw)
  if (!Number.isFinite(num) || num <= 0) {
    field.localError = '必须是大于 0 的数字，或留空表示不设限'
    return undefined
  }
  if (field.integerOnly && !Number.isInteger(num)) {
    field.localError = '这一项必须是整数'
    return undefined
  }
  if (!field.integerOnly && !/^\d{1,7}(\.\d{1,2})?$/.test(raw)) {
    field.localError = '金额最多 2 位小数'
    return undefined
  }
  field.localError = ''
  return num
}

async function saveBudget(): Promise<void> {
  budgetSubmitFailure.value = null
  budgetTip.value = ''
  const parsed: Record<string, number | null> = {}
  for (const field of budgetFields) {
    const value = parseBudgetField(field)
    if (value === undefined) {
      budgetState.value = 'success'
      return
    }
    parsed[field.key] = value
  }
  const currency = budget.value?.currency ?? 'CNY'
  budgetSaving.value = true
  try {
    budget.value = await updateBudget({
      currency,
      maxTaskCost: parsed.maxTaskCost ?? null,
      maxDailyCost: parsed.maxDailyCost ?? null,
      maxModelCalls: parsed.maxModelCalls ?? null,
      maxToolCalls: parsed.maxToolCalls ?? null,
      maxOutputTokens: parsed.maxOutputTokens ?? null,
      enabled: enabledFlag.value,
      // 还没有策略行时按 0 提交；服务端不认可会给结构化错误，这里如实展示而不本地兜底
      lockVersion: budget.value?.lockVersion ?? 0
    })
    syncBudgetForm()
    budgetState.value = 'success'
    budgetTip.value = '预算已保存'
    uni.showToast({ title: '预算已保存', icon: 'none' })
  } catch (err) {
    budgetSubmitFailure.value = toFailureView(err)
    budgetState.value = 'success'
    if (isApiError(err) && err.code === 'VERSION_CONFLICT') {
      // 预算被别处改过：重新读取，不覆盖对方的修改
      uni.showModal({
        title: '预算已被修改',
        content: '这份预算在别处被更新过了，需要重新读取后再改。',
        showCancel: false,
        success: () => void loadBudget()
      })
    } else {
      uni.showToast({ title: briefError(err), icon: 'none' })
    }
  } finally {
    budgetSaving.value = false
  }
}
</script>

<style lang="scss" scoped>
/* 区块内的"展开更多字段"开关：统一小尺寸、统一宽度，避免每节各长一个样 */
.ms-toggle {
  width: 260rpx;
  margin-top: $fa-space-tight;
}

.ms-status-row {
  justify-content: flex-start;
  border-bottom: none;
}

.ms-form {
  margin-top: $fa-space-tight;
}

.ms-hint-inline {
  font-size: $fa-font-ui;
  margin-left: 12rpx;
}

.ms-error {
  color: $fa-danger;
}

.ms-switchrow {
  align-items: center;
  min-height: $fa-tap;
}

.fa-mono {
  font-family: $fa-font-num;
  letter-spacing: 0.4px;
}
</style>
