<template>
  <view class="fa-page">
    <StateBlock
      :state="state"
      :failure="failure"
      :submit-failure="submitFailure"
      :success-tip="successTip"
      loading-text="正在读取档案…"
      empty-title="尚未建立个人档案"
      empty-desc="只有身高是必填项，其余都可以留空。这是正常起点，不是错误。"
      @retry="load"
    >
      <template #action>
        <view class="fa-btn fa-btn--primary" @tap="startCreate">
          <FaIcon name="user" tone="text" size="40rpx" />
          <text>建立</text>
        </view>
      </template>

      <!-- 乐观锁冲突（409 VERSION_CONFLICT）：绝不静默覆盖，先引导重新读取 -->
      <view v-if="conflictText" class="fa-banner fa-banner--warn">
        <text class="pf-block-title">服务端版本已变化，你的这次修改没有写入</text>
        <text class="fa-prose">{{ conflictText }}</text>
        <view class="fa-btn-row pf-actions">
          <view class="fa-btn fa-btn--primary" @tap="reloadFromServer">重新读取档案</view>
        </view>
      </view>

      <!-- 抬头只留状态图形 + 短标签；接口路径、PUT/POST、lockVersion、请求键名这些开发术语
           一句都不删，收进点按展开的「为什么」——它们解释的是"为什么这么设计"，不是"怎么填" -->
      <view class="fa-card">
        <view class="pf-head">
          <view class="pf-badge">
            <FaIcon name="user" :tone="profile ? 'accent' : 'sub'" size="40rpx" />
          </view>
          <text class="fa-title pf-head__title">{{ profile ? '修改档案' : '新建档案' }}</text>
          <text class="fa-chip" :class="profile ? 'fa-chip--primary' : 'fa-chip--warning'">
            {{ profile ? '修改' : '新建' }}
          </text>
        </view>
        <text class="fa-prose">可空字段留空即表示不设置；保存时整份档案一次写回服务端。</text>

        <view class="fa-btn fa-btn--sm pf-toggle" @tap="showWhy = !showWhy">
          <FaIcon :name="showWhy ? 'chevron' : 'records'" tone="accent" size="36rpx" />
          <text>{{ showWhy ? '收起' : '为什么' }}</text>
        </view>
        <view v-if="showWhy" class="pf-why">
          <view class="pf-why__row">
            <text class="pf-why__term">读取</text>
            <text class="fa-prose">GET /api/me/profile 返回 data:null 表示你还没有档案，这是正常起点而不是错误（不是 404）。</text>
          </view>
          <view class="pf-why__row">
            <text class="pf-why__term">提交</text>
            <text class="fa-prose">提交只走一条路径：无档案用 POST 创建，已有档案用 PUT 并带上刚读取到的 lockVersion（当前 {{ profile ? profile.lockVersion : '无（首次创建）' }}）。后端 PUT 是全量替换，「省略即清空」。</text>
          </view>
          <view class="pf-why__row">
            <text class="pf-why__term">只提交 6 个字段</text>
            <text class="fa-prose">请求体是 Java record + 全局 FAIL_ON_UNKNOWN_PROPERTIES：多传一个键就判 422 UNKNOWN_FIELD。昵称归属 user_account（见 GET /api/me），档案请求里没有这个键；本轮也不开放「活动类别 / 饮食偏好 / 需专业指导」的录入（无消费方即不录入）。</text>
          </view>
          <view class="pf-why__row">
            <text class="pf-why__term">字段键名</text>
            <text class="fa-prose">heightCm、trainingLevel、equipment、birthDate、sexForEquation、trainingConstraints。</text>
          </view>
          <view class="pf-why__row">
            <text class="pf-why__term">训练经验</text>
            <text class="fa-prose">不选时服务端按列默认值落 BEGINNER（值域 BEGINNER / INTERMEDIATE / ADVANCED）。</text>
          </view>
          <view class="pf-why__row">
            <text class="pf-why__term">器械</text>
            <text class="fa-prose">器械代码与动作字典、后端 Vocabulary.EQUIPMENT 共用同一套大写字面量，所以只能从下面这些项里选。</text>
          </view>
          <view class="pf-why__row">
            <text class="pf-why__term">训练限制</text>
            <text class="fa-prose">落库是字符串数组（JSON 数组），不是单条长文本；每条各自成一项，空白条目提交前会被丢掉。</text>
          </view>
          <view class="pf-why__row">
            <text class="pf-why__term">性别参数</text>
            <text class="fa-prose">落库默认 UNSPECIFIED：这个值只用于「按性别计算的公式」，本页不据此算任何东西。</text>
          </view>
        </view>
      </view>

      <!-- 一屏只填一组：字段标签、必填标记、填写提示与逐字段错误全部原样保留，只按语义分两张卡 -->
      <view class="fa-section-title">基础信息</view>
      <view class="fa-card">
        <view class="fa-field">
          <NumberField
            v-model="form.heightCm"
            label="身高"
            unit="cm"
            required
            :max-decimals="2"
            placeholder="例如 175"
            hint="必填：大于 0、最多 2 位小数（DECIMAL(6,2)）。契约明确不做身高/体重医学区间阈值判定。"
          />
          <text v-if="err('heightCm')" class="pf-error">{{ err('heightCm') }}</text>
        </view>

        <DateField
          v-model="form.birthDate"
          label="出生日期（可选）"
          placeholder="未填写"
          :end="today"
          hint="只校验格式与「不晚于今天」（后端 @PastOrPresent）；页面不据此展示年龄。"
        />
        <text v-if="err('birthDate')" class="pf-error">{{ err('birthDate') }}</text>

        <view class="fa-field">
          <view class="fa-field__label"><text>性别参数（可选）</text></view>
          <ChipSelect
            :model-value="form.sexForEquation"
            :options="SEX_OPTIONS"
            @update:model-value="setSexForEquation"
          />
          <text class="fa-hint">不指定就不套用任何按性别计算的公式。</text>
          <text v-if="err('sexForEquation')" class="pf-error">{{ err('sexForEquation') }}</text>
        </view>
      </view>

      <view class="fa-section-title">训练条件</view>
      <view class="fa-card">
        <view class="fa-field">
          <view class="fa-field__label"><text>训练经验</text></view>
          <ChipSelect
            :model-value="form.trainingLevel"
            :options="TRAINING_LEVEL_OPTIONS"
            @update:model-value="setTrainingLevel"
          />
          <text class="fa-hint">自报训练经验；不选时按新手处理。</text>
          <text v-if="err('trainingLevel')" class="pf-error">{{ err('trainingLevel') }}</text>
        </view>

        <view class="fa-field">
          <view class="fa-field__label">
            <text>可用器械</text>
            <text class="pf-count">已选 {{ form.equipment.length }}/{{ MAX_EQUIPMENT }}，上限 16 项</text>
          </view>
          <view class="pf-chips">
            <view
              v-for="code in EQUIPMENT_CODES"
              :key="code"
              class="fa-chip pf-chip"
              :class="{ 'fa-chip--on': hasEquipment(code) }"
              @tap="toggleEquipment(code)"
            >
              <text>{{ EQUIPMENT_LABELS[code] }}</text>
            </view>
          </view>
          <text class="fa-hint">点一下选中，再点取消；器械最多 {{ MAX_EQUIPMENT }} 项。</text>
          <text v-if="err('equipment')" class="pf-error">{{ err('equipment') }}</text>
        </view>

        <view class="fa-field">
          <view class="fa-field__label">
            <text>训练限制与可用时间（可选）</text>
            <text class="pf-count">{{ filledConstraints.length }} 条，最多 16 条、单条 500 字</text>
          </view>
          <view v-for="(item, index) in form.constraints" :key="`c-${index}`" class="pf-constraint">
            <input
              class="fa-input pf-constraint__input"
              :value="item"
              maxlength="500"
              placeholder="例如：每次训练不超过 60 分钟"
              @input="onConstraintInput(index, $event)"
            />
            <view class="fa-btn pf-constraint__del" @tap="removeConstraint(index)">删除</view>
          </view>
          <view class="fa-btn fa-btn--ghost pf-add" @tap="addConstraint">添加一条</view>
          <text class="fa-hint">一条一句，例如「每周只能练 3 次」。空白条目提交前会被丢掉。</text>
          <text v-if="err('trainingConstraints')" class="pf-error">{{ err('trainingConstraints') }}</text>
        </view>
      </view>

      <!-- 保存是唯一主操作；「放弃本地修改并重读」是破坏性的次操作，进「更多」后仍走原来的确认弹窗 -->
      <view class="fa-toolbar pf-bottom">
        <view class="fa-btn fa-btn--primary pf-save" :class="{ 'fa-btn--disabled': submitting }" @tap="onSubmit">
          {{ submitting ? '提交中…' : profile ? '保存修改' : '创建档案' }}
        </view>
        <view v-if="profile" class="fa-btn fa-btn--sm" @tap="moreOpen = true">
          <FaIcon name="more" tone="sub" size="40rpx" />
          <text>更多</text>
        </view>
        <FaMoreMenu v-model:open="moreOpen" :items="moreItems" @select="onMoreSelect" />
      </view>
    </StateBlock>
  </view>
</template>

<script setup lang="ts">
/**
 * 档案页：建立或修改本人 `user_profile`（docs/p1-api.md §4.1–§4.2、docs/p1-scope.md §3.2）。
 *
 * 口径逐条对着后端实现核过（`backend/.../modules/profile/{UserProfileService,dto/UpsertProfileRequest}.java`）：
 *   - `GET /api/me/profile` 无档案 → 200 + `data:null`：渲染「建立」空状态入口，不是错误；
 *   - 只有 `heightCm` 必填（>0、≤2 位小数），**不引入任何医学区间阈值**（§2 范围外清单）；
 *   - 请求体是 Java record + 全局 `FAIL_ON_UNKNOWN_PROPERTIES`：多一个键即 422 `UNKNOWN_FIELD`。
 *     因此本页不发送 `nickname`（契约 §4.2 示例里有、后端 DTO 里没有），POST 也不发送
 *     `lockVersion`（创建带它会被判 `FORBIDDEN_SERVER_FIELD`）；
 *   - PUT 全量替换 + `lockVersion` 乐观锁：409 时提示重新读取，不覆盖他人改动；
 *   - 逐字段错误：后端把 `List<FieldError>` 直接放进 `data`（不是契约 §2 的 `data.fields`），
 *     由 `readFieldErrors()` 两种形状一起认。
 *
 * 五态：加载中 / 无档案（空）/ 读取或提交失败（服务端 message + 码 + traceId + 逐字段）/
 * 保存成功（回显服务端返回的 lockVersion）/ 登录失效（封装层已跳转，页面保留提示）。
 *
 * UI 重排的取舍：字段按「基础 / 训练条件」分两张卡，长口径与开发术语收进「为什么」折叠区，
 * 保存是唯一主操作。提示词 §2.3 要求表单提示保留必要长度，所以这里只换层级、不减一句。
 */
import { computed, reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import StateBlock from '@/components/StateBlock.vue'
import NumberField from '@/components/NumberField.vue'
import ChipSelect from '@/components/ChipSelect.vue'
import DateField from '@/components/DateField.vue'
import FaIcon from '@/components/FaIcon.vue'
import FaMoreMenu from '@/components/FaMoreMenu.vue'
import type { MoreItem } from '@/types/ui'
import { createProfile, fetchProfile, updateProfile } from '@/api/profile'
import {
  EQUIPMENT_CODES,
  EQUIPMENT_LABELS,
  SEX_OPTIONS,
  TRAINING_LEVEL_OPTIONS,
  type EquipmentCode,
  type ProfileCreateRequest,
  type SexForEquation,
  type TrainingLevel,
  type UserProfile
} from '@/types/profile'
import { fieldErrorText, readFieldErrors, type ApiFieldError, type PageState } from '@/types/api'
import { isApiError, isSessionExpiredError, isVersionConflict } from '@/utils/request'
import { toFailureView, type FailureView } from '@/utils/errorMessage'
import { isDateValue, todayDateValue } from '@/utils/date'
import { formatDecimal, isWithinDecimal6_2 } from '@/utils/units'

/** 与后端 `UpsertProfileRequest` 的 @Size 一致：器械 16 项、训练限制 16 条、单条 500 字。 */
const MAX_EQUIPMENT = 16
const MAX_CONSTRAINTS = 16
const MAX_CONSTRAINT_LENGTH = 500

/** 页内已有独立控件的字段：这些字段的服务器错误就地显示，其余错误进顶部横幅，一条都不丢也不重复。 */
const CONTROL_FIELDS = ['heightCm', 'trainingLevel', 'equipment', 'birthDate', 'sexForEquation', 'trainingConstraints']

const profile = ref<UserProfile | null>(null)
const state = ref<PageState>('loading')
const failure = ref<FailureView | null>(null)
const submitFailure = ref<FailureView | null>(null)
const fieldErrors = ref<ApiFieldError[]>([])
const localErrors = ref<Record<string, string>>({})
const successTip = ref('')
const conflictText = ref('')
const submitting = ref(false)

/* 纯展示状态：「为什么」区开合与「更多」菜单，不参与任何请求或校验 */
const showWhy = ref(false)
const moreOpen = ref(false)

const moreItems: MoreItem[] = [{ key: 'discard', label: '放弃本地修改并重读', icon: 'refresh' }]

function onMoreSelect(key: string): void {
  if (key === 'discard') confirmDiscard()
}

const form = reactive({
  heightCm: null as number | null,
  trainingLevel: 'BEGINNER' as TrainingLevel,
  equipment: [] as EquipmentCode[],
  birthDate: '',
  sexForEquation: 'UNSPECIFIED' as SexForEquation,
  constraints: [''] as string[]
})

const today = computed(() => todayDateValue())
const filledConstraints = computed(() => form.constraints.map((s) => s.trim()).filter((s) => s.length > 0))

/** 服务器逐字段错误优先，其次是提交前的本地自检。 */
function err(field: string): string {
  return fieldErrorText(fieldErrors.value, field) || localErrors.value[field] || ''
}

/** 归属到某个控件的错误不再进横幅（避免同一条错误显示两遍）。 */
function bannerErrors(all: ApiFieldError[]): ApiFieldError[] {
  const owned = new Set<ApiFieldError>()
  CONTROL_FIELDS.forEach((control) => {
    all.forEach((e) => {
      if (fieldErrorText([e], control) !== '') owned.add(e)
    })
  })
  return all.filter((e) => !owned.has(e))
}

function fillFrom(p: UserProfile): void {
  form.heightCm = p.heightCm
  form.trainingLevel = p.trainingLevel
  form.equipment = (p.equipment ?? []).filter((code) => EQUIPMENT_CODES.includes(code))
  form.birthDate = p.birthDate ?? ''
  form.sexForEquation = p.sexForEquation ?? 'UNSPECIFIED'
  const list = (p.trainingConstraints ?? []).filter((s) => typeof s === 'string' && s.length > 0)
  form.constraints = list.length > 0 ? list.slice(0, MAX_CONSTRAINTS) : ['']
}

async function load(): Promise<void> {
  state.value = 'loading'
  failure.value = null
  conflictText.value = ''
  try {
    const p = await fetchProfile()
    profile.value = p
    if (p === null) {
      // 契约 §4.1：data:null 是正常空状态，页面显示「建立档案」
      state.value = 'empty'
      return
    }
    fillFrom(p)
    state.value = 'success'
  } catch (err2) {
    if (isSessionExpiredError(err2)) {
      state.value = 'sessionExpired'
      return
    }
    failure.value = toFailureView(err2)
    state.value = 'error'
  }
}

function resetForm(): void {
  form.heightCm = null
  form.trainingLevel = 'BEGINNER'
  form.equipment = []
  form.birthDate = ''
  form.sexForEquation = 'UNSPECIFIED'
  form.constraints = ['']
  submitFailure.value = null
  fieldErrors.value = []
  localErrors.value = {}
  conflictText.value = ''
}

function startCreate(): void {
  profile.value = null
  resetForm()
  successTip.value = ''
  state.value = 'success'
}

/** 放弃本地修改是破坏性的：先确认，再重读，避免一键丢掉已经填好的档案内容。 */
function confirmDiscard(): void {
  uni.showModal({
    title: '放弃本地修改',
    content: '当前表单里未保存的填写内容会被服务端已保存的档案覆盖，且无法找回。确认放弃？',
    confirmText: '放弃并重读',
    cancelText: '继续编辑',
    success: (res) => {
      if (res.confirm) reloadFromServer()
    }
  })
}

function reloadFromServer(): void {
  successTip.value = ''
  resetForm()
  void load()
}

function hasEquipment(code: EquipmentCode): boolean {
  return form.equipment.includes(code)
}

function toggleEquipment(code: EquipmentCode): void {
  const index = form.equipment.indexOf(code)
  if (index >= 0) {
    form.equipment.splice(index, 1)
    return
  }
  if (form.equipment.length >= MAX_EQUIPMENT) {
    // 器械代码词表恰好 16 项，正常走不到这里；留着是为了词表以后变长时仍然如实报错
    localErrors.value = { ...localErrors.value, equipment: `器械最多 ${MAX_EQUIPMENT} 项` }
    return
  }
  form.equipment.push(code)
}

function setTrainingLevel(value: string): void {
  form.trainingLevel = value as TrainingLevel
}

function setSexForEquation(value: string): void {
  form.sexForEquation = value as SexForEquation
}

/**
 * uni 运行时给 input 的载荷是 `{detail:{value}}`，而 @vue/runtime-dom 把 `<input>` 标成 DOM
 * `InputEvent`。按运行时事实取值，取不到再退回 `target.value`（H5）。与 NumberField.vue 的口径一致。
 */
function inputValueFrom(evt: unknown): string {
  const detail = (evt as { detail?: { value?: unknown } })?.detail
  if (detail && typeof detail.value === 'string') return detail.value
  const target = (evt as { target?: { value?: unknown } })?.target
  return typeof target?.value === 'string' ? target.value : ''
}

function onConstraintInput(index: number, evt: Event): void {
  form.constraints[index] = inputValueFrom(evt)
}

function addConstraint(): void {
  if (form.constraints.length >= MAX_CONSTRAINTS) {
    localErrors.value = {
      ...localErrors.value,
      trainingConstraints: `最多 ${MAX_CONSTRAINTS} 条（后端 @Size(max=16) 同值）`
    }
    return
  }
  form.constraints.push('')
}

function removeConstraint(index: number): void {
  form.constraints.splice(index, 1)
  if (form.constraints.length === 0) form.constraints.push('')
}

/**
 * 提交前自检只做契约写明的规则（必填、正数、小数位/量程、长度、枚举、日期格式），
 * 不发明任何业务或医学阈值。返回 null 表示本地不通过，此时不发请求。
 */
function buildPayload(): ProfileCreateRequest | null {
  const local: Record<string, string> = {}
  const height = form.heightCm
  if (height === null || !Number.isFinite(height)) {
    local.heightCm = '身高必填'
  } else if (!(height > 0)) {
    local.heightCm = '身高必须大于 0'
  } else if (!isWithinDecimal6_2(height)) {
    local.heightCm = '身高要落在 DECIMAL(6,2) 量程内且最多 2 位小数'
  }

  if (!TRAINING_LEVEL_OPTIONS.some((o) => o.value === form.trainingLevel)) {
    local.trainingLevel = '训练经验取值非法'
  }
  if (!SEX_OPTIONS.some((o) => o.value === form.sexForEquation)) {
    local.sexForEquation = '性别参数取值非法'
  }
  if (form.equipment.length > MAX_EQUIPMENT) {
    local.equipment = `器械最多 ${MAX_EQUIPMENT} 项`
  }
  form.equipment.forEach((code, i) => {
    if (!EQUIPMENT_CODES.includes(code)) local[`equipment[${i}]`] = `器械代码未定义：${code}`
  })

  if (form.birthDate !== '') {
    if (!isDateValue(form.birthDate)) local.birthDate = '出生日期格式必须是 YYYY-MM-DD'
    else if (form.birthDate > today.value) local.birthDate = '出生日期不能晚于今天'
  }

  const constraints = filledConstraints.value
  if (constraints.length > MAX_CONSTRAINTS) {
    local.trainingConstraints = `训练限制最多 ${MAX_CONSTRAINTS} 条`
  }
  constraints.forEach((s, i) => {
    if (s.length > MAX_CONSTRAINT_LENGTH) {
      local.trainingConstraints = `第 ${i + 1} 条超过 ${MAX_CONSTRAINT_LENGTH} 字`
    }
  })

  localErrors.value = local
  if (Object.keys(local).length > 0) return null

  return {
    heightCm: height as number,
    trainingLevel: form.trainingLevel,
    equipment: form.equipment.slice(),
    birthDate: form.birthDate === '' ? null : form.birthDate,
    sexForEquation: form.sexForEquation,
    trainingConstraints: constraints.length > 0 ? constraints : null
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
  if (submitting.value) return
  const payload = buildPayload()
  if (payload === null) {
    uni.showToast({ title: '先修正表单里标红的项', icon: 'none', duration: 2000 })
    return
  }

  const existing = profile.value
  submitting.value = true
  submitFailure.value = null
  fieldErrors.value = []
  successTip.value = ''
  conflictText.value = ''
  try {
    const saved =
      existing === null
        ? await createProfile(payload)
        : await updateProfile({ ...payload, lockVersion: existing.lockVersion })
    profile.value = saved
    fillFrom(saved)
    submitFailure.value = null
    fieldErrors.value = []
    localErrors.value = {}
    conflictText.value = ''
    successTip.value =
      existing === null
        ? `档案已创建：服务端返回 id=${saved.id}、lockVersion=${saved.lockVersion}，身高 ${formatDecimal(saved.heightCm, 'cm')}。`
        : `档案已保存：lockVersion ${existing.lockVersion} → ${saved.lockVersion}（服务端乐观锁已确认这次写入）。`
    state.value = 'success'
  } catch (err2) {
    const view = absorbError(err2)
    submitFailure.value = view
    if (view.sessionExpired) {
      state.value = 'sessionExpired'
    } else if (isVersionConflict(err2)) {
      conflictText.value =
        existing === null
          ? '并发首次创建：档案已由另一次请求建好（服务端 UNIQUE(user_id) 判 409）。请重新读取，再在最新数据上修改。'
          : `页面里的 lockVersion=${existing.lockVersion} 已过期。重新读取后请重做本次修改，本次提交没有写入服务端。`
    }
    uni.showToast({ title: view.title, icon: 'none', duration: 2000 })
  } finally {
    submitting.value = false
  }
}

onLoad(() => {
  void load()
})
</script>

<style lang="scss" scoped>
.pf-head {
  display: flex;
  align-items: center;
  gap: $fa-space-tight;
  margin-bottom: $fa-space-tight;
}

.pf-head__title {
  flex: 1;
  min-width: 0;
}

/* 抬头徽标承载"这是档案"，标签只留两字（修改/新建） */
.pf-badge {
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

.pf-block-title {
  display: block;
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  margin-bottom: 4px;
}

.pf-actions {
  margin-top: 8px;
}

.pf-toggle {
  margin-top: $fa-space-tight;
  width: 180rpx;
}

/* 「为什么」：开发术语与口径不占主阅读路径，但一条都不删（提示词 §2.3） */
.pf-why {
  margin-top: $fa-space-tight;
  padding-top: $fa-space-tight;
  border-top: 1px solid $fa-line;
}

.pf-why__row {
  padding: $fa-space-tight 0;
  border-bottom: 1px solid $fa-line;
}

.pf-why__row:last-child {
  border-bottom: none;
}

.pf-why__term {
  display: block;
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  color: $fa-text-sub;
  margin-bottom: 4rpx;
}

.pf-count {
  display: block;
  font-size: $fa-font-ui;
  color: $fa-text-mute;
  margin-top: 2px;
}

.pf-chips {
  display: flex;
  flex-wrap: wrap;
}

.pf-chip {
  min-height: $fa-tap;
  align-items: center;
}

.pf-constraint {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.pf-constraint__input {
  flex: 1;
  min-height: $fa-tap;
}

.pf-constraint__del {
  flex: 0 0 auto;
  min-width: 60px;
  min-height: $fa-tap;
  font-size: $fa-font-ui;
  color: $fa-danger;
  border-color: $fa-danger;
}

.pf-add {
  min-height: $fa-tap;
  margin-top: 2px;
}

.pf-error {
  display: block;
  margin-top: 4px;
  font-size: $fa-font-ui;
  color: $fa-danger;
}

.pf-bottom {
  margin-top: $fa-space-section;
}

/* 保存是这一屏唯一的主操作，宽度吃掉剩余空间 */
.pf-save {
  flex: 1;
}
</style>
