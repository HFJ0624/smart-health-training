<template>
  <view class="fa-page">
    <!-- 还没拿到场次：整页交给五态（加载中 / 加载失败 / 登录失效） -->
    <StateBlock
      v-if="!session"
      :state="pageState"
      :failure="loadFailure"
      loading-text="正在准备训练记录…"
      empty-title="训练记录没打开"
      empty-desc="从首页今日卡、训练计划页或训练历史进入本页。"
      @retry="retry"
    />

    <template v-else>
      <!-- ========== 场次头：状态 + 服务端推导的业务日期；这是"这一场"的身份，不是表单标题 ========== -->
      <view class="fa-card rd-head">
        <view class="rd-head__top">
          <text class="fa-title rd-head__title">{{ headTitle }}</text>
          <text class="fa-chip rd-chip" :class="statusChipClass">{{ statusLabel }}</text>
        </view>

        <view class="rd-kv">
          <text class="fa-muted">业务日期</text>
          <text>{{ session.businessDate }}（服务端按 {{ session.timezone }} 推导）</text>
        </view>
        <view class="rd-kv">
          <text class="fa-muted">开始时间</text>
          <text>{{ instantToDisplay(session.startedAt) }}</text>
        </view>
        <view v-if="session.endedAt" class="rd-kv">
          <text class="fa-muted">结束时间</text>
          <text>{{ instantToDisplay(session.endedAt) }}</text>
        </view>
        <view v-if="session.durationSeconds !== null" class="rd-kv">
          <text class="fa-muted">场次历时</text>
          <text>{{ secondsToHuman(session.durationSeconds) }}（与动作时长分开，不相加）</text>
        </view>

        <view class="fa-btn fa-btn--sm rd-toggle" @tap="showMeta = !showMeta">
          <FaIcon :name="showMeta ? 'chevron' : 'records'" tone="accent" size="36rpx" />
          <text>{{ showMeta ? '收起' : '记录详情' }}</text>
        </view>
        <view v-if="showMeta" class="rd-meta">
          <view class="rd-kv"><text class="fa-muted">场次 ID</text><text class="rd-mono">{{ session.id }}</text></view>
          <view class="rd-kv"><text class="fa-muted">幂等键（服务端）</text><text class="rd-mono">{{ session.clientRecordUuid }}</text></view>
          <!-- 恢复别人建的草稿时，本页这次提交用的键与库里不同：两个都给出来，不假装是同一个 -->
          <view v-if="startUuid !== session.clientRecordUuid" class="rd-kv">
            <text class="fa-muted">本次提交键</text><text class="rd-mono">{{ startUuid }}</text>
          </view>
          <view class="rd-kv"><text class="fa-muted">乐观锁版本</text><text>{{ lockVersion }}</text></view>
          <view class="rd-kv">
            <text class="fa-muted">参考计划</text>
            <text>{{ planRefText }}</text>
          </view>
        </view>
      </view>

      <!-- 恢复说明：闸门命中已有草稿时必须说清是哪一场，不能假装"已开始新训练" -->
      <view v-if="resumeNote" class="fa-banner fa-banner--info">
        <text class="fa-prose">{{ resumeNote }}</text>
      </view>

      <!-- 提交失败横幅：错误码与 traceId 原样给出，重试复用同一幂等键（页面不自动重发写请求） -->
      <view v-if="submitFailure" class="fa-banner fa-banner--error">
        <text class="rd-block-title">{{ submitFailure.title }}</text>
        <text v-if="submitFailure.detail" class="fa-prose">{{ submitFailure.detail }}</text>
        <text class="rd-code">错误码：{{ submitFailure.code }}</text>
        <text v-if="submitFailure.traceId" class="rd-code">traceId：{{ submitFailure.traceId }}</text>
        <view v-for="(e, i) in submitFailure.fields" :key="`sf-${i}`" class="rd-fielderr">
          <text class="rd-fielderr__name">{{ e.field }}</text>
          <text class="fa-prose">{{ e.message }}</text>
          <text class="rd-code">{{ e.code }}</text>
        </view>
        <text v-if="submitFailure.sessionExpired" class="fa-prose">登录已失效，本次填写仍留在本页；重新登录后继续即可。</text>
        <text v-else class="fa-prose">本次提交没有写进服务端，填写内容原样保留，可直接重试。</text>
      </view>

      <view v-if="successTip" class="fa-banner fa-banner--success">
        <text class="fa-prose">{{ successTip }}</text>
      </view>

      <!-- ========== 区域一：计划参考（只读，永不等于"已练"） ========== -->
      <view class="fa-card">
        <view class="rd-sec__head">
          <FaIcon name="calendar" tone="accent" size="40rpx" />
          <text class="fa-section-title rd-sec__t">计划参考</text>
        </view>
        <text class="fa-hint">这一区是计划的安排，只作对照；下面的勾选与实际值只来自你的点击与输入。</text>
        <text v-if="planRefNote" class="fa-muted rd-plan-note">{{ planRefNote }}</text>
        <view v-for="(it, i) in planItems" :key="it.id" class="rd-ref__row">
          <text class="rd-ref__pos">#{{ i + 1 }}</text>
          <view class="rd-ref__text">
            <text class="rd-ref__name">{{ it.exerciseName || it.exerciseCode }}</text>
            <text class="fa-muted rd-ref__target">{{ planItemLine(it) }}</text>
          </view>
        </view>
      </view>

      <!-- ========== 区域二：我的实际记录（可写；一次聚焦一个动作） ========== -->
      <StateBlock
        :state="entriesState"
        empty-title="还没有动作"
        empty-desc="本场没有任何动作条目。自主练也要记：加一个动作，勾一组算一组。"
      >
        <template #action>
          <view class="fa-btn fa-btn--primary" @tap="openPicker">添加动作</view>
        </template>

        <view class="fa-card">
          <view class="rd-sec__head">
            <FaIcon name="dumbbell" tone="accent" size="40rpx" />
            <text class="fa-section-title rd-sec__t">我的实际记录</text>
            <text class="rd-sec__count">{{ completedSetsText }}</text>
          </view>
          <text class="fa-hint">计划安排不会自动变成已完成数据；没勾的组一律算未确认。</text>

          <view v-for="en in entries" :key="en.key" class="rd-entry">
            <view class="rd-entry__head" hover-class="rd-entry__head--hover" @tap="focusEntry(en.positionNo)">
              <text class="rd-entry__pos">#{{ en.positionNo }}</text>
              <view class="rd-entry__pick">
                <text class="fa-strong rd-entry__name">{{ en.exerciseName || en.exerciseCode || '（未选择动作）' }}</text>
                <text class="fa-muted rd-entry__target">{{ entryFoldLine(en) }}</text>
              </view>
              <FaIcon
                class="rd-entry__chev"
                :class="{ 'rd-entry__chev--open': isActive(en.positionNo) }"
                name="chevron"
                tone="sub"
                size="40rpx"
              />
            </view>

            <view v-if="isActive(en.positionNo)" class="rd-entry__body">
              <text v-if="referenceLine(en)" class="rd-ref__hint">参考：{{ referenceLine(en) }}</text>
              <text class="fa-hint">勾上某一组才展开填写；没勾的组保持未确认，不会被算成完成。</text>

              <!-- 逐组：勾选 + 次数 + 可选负重 + 可选时长 -->
              <view v-for="(st, si) in en.sets" :key="st.key" class="rd-set">
                <view class="rd-set__head" hover-class="rd-set__head--hover" @tap="toggleSet(en, st)">
                  <view class="rd-set__box" :class="{ 'rd-set__box--on': st.isCompleted }">
                    <FaIcon v-if="st.isCompleted" name="check" tone="success" size="34rpx" />
                  </view>
                  <text class="rd-set__no">第 {{ si + 1 }} 组</text>
                  <text class="rd-set__state">{{ st.isCompleted ? '已确认完成' : '未确认' }}</text>
                </view>

                <view v-if="st.isCompleted" class="rd-set__fields">
                  <!-- 两列而不是三列：三列会把 15px 的输入挤成一行看不清，拥挤只能靠分行解决 -->
                  <view class="rd-grid rd-grid--2">
                    <NumberField
                      class="rd-grid__cell"
                      v-model="st.reps"
                      label="次数"
                      unit="次"
                      integer-only
                      :max-decimals="0"
                      placeholder="可留空"
                    />
                    <NumberField
                      class="rd-grid__cell"
                      v-model="st.externalLoadKg"
                      label="负重"
                      unit="kg"
                      :max-decimals="2"
                      placeholder="可留空"
                      hint="0 = 自重；留空表示没记，不自动填 0"
                    />
                  </view>
                  <view class="rd-grid rd-grid--2">
                    <NumberField
                      class="rd-grid__cell"
                      v-model="st.durationSeconds"
                      label="该组时长"
                      unit="秒"
                      integer-only
                      :max-decimals="0"
                      placeholder="可留空"
                      hint="只填秒：1 分钟 = 60 秒"
                    />
                    <view class="rd-grid__cell fa-field rd-set__notes">
                      <view class="rd-label"><text>本组备注（≤ 200 字）</text></view>
                      <input class="fa-input" :value="st.notes || ''" maxlength="200" placeholder="可选" @input="onSetNotes(st, $event)" />
                    </view>
                  </view>
                </view>
              </view>

              <view class="rd-entry__ops">
                <view class="fa-btn fa-btn--sm" @tap="addSet(en)">
                  <FaIcon name="plus" tone="accent" size="36rpx" />
                  <text>加一组</text>
                </view>
                <view class="fa-btn fa-btn--sm rd-btn--del" @tap="removeEntry(en)">
                  <text>删除该动作</text>
                </view>
              </view>

              <!-- 动作级实际时长/距离：与逐组时长、场次历时都是不同口径 -->
              <view class="rd-grid rd-grid--2">
                <NumberField
                  class="rd-grid__cell"
                  v-model="en.durationSeconds"
                  label="动作总时长"
                  unit="秒"
                  integer-only
                  :max-decimals="0"
                  placeholder="可留空"
                  hint="与上面逐组时长是两个口径，页面不做相加"
                />
                <NumberField
                  class="rd-grid__cell"
                  v-model="en.distanceMeters"
                  label="距离"
                  unit="米"
                  :max-decimals="2"
                  placeholder="可留空"
                />
              </view>

              <view class="fa-field">
                <view class="rd-label"><text>动作备注（≤ 500 字）</text></view>
                <input class="fa-input" :value="en.notes || ''" maxlength="500" placeholder="可选" @input="onEntryNotes(en, $event)" />
              </view>
            </view>
          </view>

          <view class="fa-btn fa-btn--ghost rd-additem" @tap="openPicker">
            <FaIcon name="plus" tone="accent" size="40rpx" />
            <text>自主添加动作</text>
          </view>

          <view class="fa-field rd-notes">
            <view class="rd-label"><text>本场备注（≤ 500 字）</text></view>
            <input class="fa-input" :value="sessionNotes" maxlength="500" placeholder="例如：状态不错" @input="onSessionNotes" />
          </view>
        </view>
      </StateBlock>

      <!-- 本地校验清单：镜像服务端 CHECK/上限，未过不发请求（服务端仍是权威） -->
      <view v-if="issues.length > 0" class="rd-issues">
        <text class="fa-section-title rd-issues__t">先改掉这 {{ issues.length }} 处</text>
        <view v-for="(iss, idx) in issues" :key="`iss-${idx}`" class="rd-issue">
          <text class="rd-issue__where">{{ iss.where }}</text>
          <text class="rd-issue__msg">{{ iss.message }}</text>
          <text class="rd-issue__code">{{ iss.code }}</text>
        </view>
      </view>

      <view v-if="!planRefNote && planItems.length === 0" class="fa-hint rd-tip">
        本场没有可参考的计划条目：这是没有安排（或自主记录）的正常情况，不是错误。
      </view>

      <!-- ========== 底部固定操作条：保存=次操作，结束=唯一主操作 ========== -->
      <!-- 已丢弃场次只读：它的行还在库里（闸门已释放），但任何写入都会把丢弃过的记录改活 -->
      <view v-if="readOnly" class="fa-banner fa-banner--warn">
        <text class="fa-prose">
          这一场已丢弃，页面只读：不参与统计，也不能再保存或结束。要练请重新开始一场。
        </text>
      </view>

      <view class="fa-bottom-bar">
        <view v-if="!readOnly" class="fa-btn" :class="{ 'fa-btn--disabled': busy }" @tap="onSave">
          {{ busy ? '处理中…' : isDraft ? '保存' : '保存修改' }}
        </view>
        <view v-if="isDraft" class="fa-btn fa-btn--primary" :class="{ 'fa-btn--disabled': busy }" @tap="askComplete">
          {{ busy ? '处理中…' : '结束' }}
        </view>
        <view v-else class="fa-btn fa-btn--primary" @tap="backHome">
          {{ readOnly ? '返回' : '完成并返回' }}
        </view>
      </view>
      <view class="fa-bottom-spacer" />

      <!-- 危险操作收在正文之后：丢弃与删除都要二次确认，且文案必须说清对统计与闸门的影响 -->
      <view v-if="!busy" class="rd-danger">
        <view v-if="isDraft" class="fa-btn fa-btn--sm rd-btn--del" @tap="askDiscard">丢弃这场草稿</view>
        <view class="fa-btn fa-btn--sm rd-btn--del" @tap="askDelete">删除整场记录</view>
        <text class="fa-hint">
          丢弃=保留数据但不再计入统计，同时释放「只保留一条进行中」的闸门；删除=连动作与逐组一起删掉，不可恢复。
        </text>
      </view>
    </template>

    <ExercisePicker v-model:visible="pickerVisible" @select="onExerciseSelected" />
  </view>
</template>

<script setup lang="ts">
/**
 * 训练记录页（阶段 2 第一片，docs/stage2-design.md §1、§8）。
 *
 * 四条实现要点：
 *   1. **进入即 POST /workout/sessions**：新建与恢复是同一个调用。`clientRecordUuid` 在 onLoad 生成一次，
 *      之后任何失败重试都复用同值（幂等键的生命周期归页面，与 body/records.vue 一致）；
 *      服务端另有单草稿闸门 `UNIQUE(active_user_id)`，重复进入不会造出第二场。
 *   2. **计划参考与实际记录分区**：上面那张卡只读，下面这张卡的值只来自用户点击与输入。
 *      逐组铺开的输入行是"填的位置"，`isCompleted` 一律初始为 false —— 绝不替用户确认（§3 V25）。
 *   3. **乐观锁**：写请求都带 `lockVersion`，409/`VERSION_CONFLICT` 先弹窗问用户，
 *      确认才重读（重读会丢弃本次未保存填写，所以不给"自动重载"）。
 *   4. **写后回读**：保存/结束的响应就是 `SessionDetailVo`，页面用服务端结果重渲染，
 *      不本地拼数组、不本地 +1 版本号。
 */
import { computed, reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import StateBlock from '@/components/StateBlock.vue'
import FaIcon from '@/components/FaIcon.vue'
import NumberField from '@/components/NumberField.vue'
import ExercisePicker from '@/components/ExercisePicker.vue'
import { fetchPlan } from '@/api/training'
import {
  completeWorkoutSession,
  deleteWorkoutSession,
  discardWorkoutSession,
  fetchWorkoutSession,
  saveWorkoutSession,
  startWorkoutSession
} from '@/api/workout'
import { categoryLabel, type ExerciseCategory, type ExerciseItem } from '@/types/exercise'
import { weekdayLabel, type PlanItem } from '@/types/training'
import {
  WORKOUT_LIMITS,
  WORKOUT_STATUS_LABELS,
  type WorkoutEntryInput,
  type WorkoutSaveInput,
  type WorkoutSessionDetail,
  type WorkoutStartInput
} from '@/types/workout'
import type { PageState } from '@/types/api'
import { inputValue } from '@/utils/events'
import { instantToDisplay } from '@/utils/date'
import { toFailureView, type FailureView } from '@/utils/errorMessage'
import { isSessionExpiredError, isVersionConflict } from '@/utils/request'
import { formatDecimal, isDecimalWithin, isWithinDecimal6_2, secondsToHuman } from '@/utils/units'
import { uuidV4 } from '@/utils/uuid'

interface DraftSet {
  key: string
  setNo: number
  reps: number | null
  externalLoadKg: number | null
  durationSeconds: number | null
  isCompleted: boolean
  notes: string | null
}

interface DraftEntry {
  key: string
  positionNo: number
  exerciseId: string
  exerciseCode: string | null
  exerciseName: string | null
  category: ExerciseCategory | null
  planItemId: string | null
  planTargetSets: number | null
  planRepsMin: number | null
  planRepsMax: number | null
  planTargetExternalLoadKg: number | null
  planTargetDurationSeconds: number | null
  durationSeconds: number | null
  distanceMeters: number | null
  notes: string | null
  sets: DraftSet[]
}

let seq = 0
function mkKey(): string {
  seq += 1
  return `k${seq}`
}

/** 首页/计划页带过来的参考；恢复已有草稿时不依赖它（闸门返回哪一场就以那一场为准）。 */
const planId = ref('')
const planDayId = ref('')

const startUuid = ref('')
const session = ref<WorkoutSessionDetail | null>(null)
const lockVersion = ref(0)
const entries = reactive<DraftEntry[]>([])
const sessionNotes = ref('')

const planItems = ref<PlanItem[]>([])
const planRefNote = ref('')
const resumeNote = ref('')

const entering = ref(true)
const busy = ref(false)
const loadFailure = ref<FailureView | null>(null)
const submitFailure = ref<FailureView | null>(null)
const successTip = ref('')
const showMeta = ref(false)
const activePosition = ref(1)
const pickerVisible = ref(false)
const issues = ref<Array<{ where: string; message: string; code: string }>>([])

/* ------------------------------------------------------------------ 状态判定 */

const pageState = computed<PageState>(() => {
  if (loadFailure.value?.sessionExpired) return 'sessionExpired'
  if (entering.value) return 'loading'
  if (loadFailure.value) return 'error'
  return 'empty'
})

/** 提交失败时保留记录卡：StateBlock 的 empty 分支不渲染默认插槽，别让一条 422 把用户填的列表换成空态图。 */
const entriesState = computed<PageState>(() =>
  submitFailure.value || entries.length > 0 ? 'success' : 'empty'
)

const isDraft = computed(() => session.value?.status === 'DRAFT')

/** 已丢弃 = 只读。`DISCARDED` 的行仍在库里（闸门已释放），把它改活等于伪造一条曾经被放弃的历史。 */
const readOnly = computed(() => session.value?.status === 'DISCARDED')

const statusLabel = computed(() => {
  const s = session.value?.status
  return s ? WORKOUT_STATUS_LABELS[s] : '未知状态'
})

function statusChipClass(): string {
  const s = session.value?.status
  if (s === 'DRAFT') return 'fa-chip--warning'
  if (s === 'COMPLETED') return 'fa-chip--success'
  if (s === 'DISCARDED') return 'fa-chip--danger'
  return ''
}

const headTitle = computed(() => {
  const s = session.value
  if (!s) return '训练记录'
  const week = weekdayLabel(s.planDayIsoWeekday)
  return `${s.businessDate}${week === '未安排星期' ? '' : ` · ${week}`}`
})

const planRefText = computed(() => {
  const s = session.value
  if (!s) return '—'
  if (!s.planId) return '无（自主记录）'
  return `${s.planTitle || `计划 ${s.planId}`} · 第 ${s.planRevisionNo ?? '?'} 版`
})

const completedSetsText = computed(() => {
  let done = 0
  let recorded = 0
  entries.forEach((en) => {
    en.sets.forEach((st) => {
      if (st.isCompleted) done += 1
      if (st.reps !== null || st.externalLoadKg !== null || st.durationSeconds !== null) recorded += 1
    })
  })
  // 「填了数值但没勾」与「勾了完成」是两回事，分开报，不合并成一个百分比
  return `已确认 ${done} 组 · 填了数值 ${recorded} 组`
})

/* ------------------------------------------------------------------ 进入：开始 / 恢复 / 按 ID 打开 */

/** 进入方式：`start`=带参考开始或命中闸门恢复；`resume`=从历史点进来，压根不调开始接口。 */
const entryMode = ref<'start' | 'resume'>('start')

async function enter(): Promise<void> {
  if (busy.value) return
  // 幂等键只在第一次进入时生成；失败重试走同一个值，服务端才能命中唯一索引
  if (!startUuid.value) startUuid.value = uuidV4()
  entering.value = true
  busy.value = true
  loadFailure.value = null
  submitFailure.value = null
  try {
    const payload: WorkoutStartInput = { clientRecordUuid: startUuid.value }
    if (planId.value && planDayId.value) {
      payload.planId = planId.value
      payload.planDayId = planDayId.value
    }
    const result = await startWorkoutSession(payload)
    applySession(result.session, result.resumedExisting === true)
    await loadPlanReference()
    seedFromPlanIfEmpty()
  } catch (err) {
    loadFailure.value = toFailureView(err)
  } finally {
    busy.value = false
    entering.value = false
  }
}

/**
 * 按场次 ID 打开已有记录（历史/统计页的入口）。
 *
 * 这里**只走 GET**：开始接口带的是「新的幂等键 + 参考计划」，用它打开旧记录会重新推导业务日期，
 * 跨天查看昨天的记录会被判成幂等冲突，甚至多造一场。GET 的归属校验在服务端每个路径上单独做。
 */
async function enterById(id: string): Promise<void> {
  if (busy.value) return
  entryMode.value = 'resume'
  entering.value = true
  busy.value = true
  loadFailure.value = null
  submitFailure.value = null
  try {
    const detail = await fetchWorkoutSession(id)
    session.value = detail
    lockVersion.value = detail.lockVersion
    sessionNotes.value = detail.notes ?? ''
    if (detail.entries.length > 0) hydrate(detail.entries)
    else entries.splice(0, entries.length)
    activePosition.value = entries[0]?.positionNo ?? 1
    await loadPlanReference()
    resumeNote.value = `从训练历史打开（场次 ${detail.id}，${WORKOUT_STATUS_LABELS[detail.status]}）：` +
      `本页直接读取这一场的服务端聚合，不会新建场次。`
  } catch (err) {
    loadFailure.value = toFailureView(err)
  } finally {
    busy.value = false
    entering.value = false
  }
}

function applySession(detail: WorkoutSessionDetail, resumed: boolean): void {
  session.value = detail
  lockVersion.value = detail.lockVersion
  sessionNotes.value = detail.notes ?? ''
  resumeNote.value = resumed
    ? detail.planDayId && planDayId.value && detail.planDayId !== planDayId.value
      ? `已恢复进行中的记录（场次 ${detail.id}）。它参考的计划日与这次入口不同，页面按服务端这一场为准继续。`
      : `已恢复进行中的记录（场次 ${detail.id}）：同一用户只会有一条进行中草稿，重复进入不会新建。`
    : ''
  if (detail.entries.length > 0) hydrate(detail.entries)
  else entries.splice(0, entries.length)
}

/** 用服务端聚合回读重建本地模型：写后一律重读，不本地打补丁。 */
function hydrate(serverEntries: WorkoutSessionDetail['entries']): void {
  entries.splice(0, entries.length)
  serverEntries
    .slice()
    .sort((a, b) => a.positionNo - b.positionNo)
    .forEach((en) => {
      const sets = en.sets.slice().sort((a, b) => a.setNo - b.setNo)
      entries.push({
        key: mkKey(),
        positionNo: en.positionNo,
        exerciseId: en.exerciseId,
        exerciseCode: en.exerciseCode,
        exerciseName: en.exerciseName,
        category: en.category,
        planItemId: en.planItemId,
        planTargetSets: en.planTargetSets,
        planRepsMin: en.planRepsMin,
        planRepsMax: en.planRepsMax,
        planTargetExternalLoadKg: en.planTargetExternalLoadKg,
        planTargetDurationSeconds: en.planTargetDurationSeconds,
        durationSeconds: en.durationSeconds,
        distanceMeters: en.distanceMeters,
        notes: en.notes,
        // sets 为空也要给一行输入位，否则用户没有可勾的地方
        sets: sets.length > 0
          ? sets.map((st, i) => ({
              key: mkKey(),
              setNo: i + 1,
              reps: st.reps,
              externalLoadKg: st.externalLoadKg,
              durationSeconds: st.durationSeconds,
              isCompleted: st.isCompleted === true,
              notes: st.notes
            }))
          : [emptySet(1)]
      })
    })
  if (!entries.some((e) => e.positionNo === activePosition.value)) activePosition.value = entries[0]?.positionNo ?? 1
}

/* ------------------------------------------------------------------ 计划参考 */

async function loadPlanReference(): Promise<void> {
  const s = session.value
  planItems.value = []
  planRefNote.value = ''
  if (!s || !s.planId || !s.planDayId) {
    planRefNote.value = '本场没有参考计划：可以直接自主记录，统计里不会把它算成计划完成。'
    return
  }
  try {
    const plan = await fetchPlan(s.planId)
    const day = plan.days.find((d) => d.id === s.planDayId)
    if (!day) {
      planRefNote.value = '服务端返回的参考计划日已不在该版本里，以下按实际记录填写（不影响历史执行）。'
      return
    }
    planItems.value = day.items.slice().sort((a, b) => a.positionNo - b.positionNo)
    planRefNote.value = day.isRestDay
      ? `${day.title || '本日'} 在计划里是休息日；今天练了就照常记录。`
      : `${day.title || '训练日'} · 计划 ${planItems.value.length} 个动作（第 ${s.planRevisionNo ?? '?'} 版）`
  } catch (err) {
    const view = toFailureView(err)
    // 参考读不到不影响实际记录这一件事：降级成一行说明，不把整页变成错误态
    planRefNote.value = `计划参考读取失败（${view.code}），实际记录仍可填写与保存。`
  }
}

/** 新草稿才铺位置：已有条目说明用户（或上一次保存）已经录过，绝不能覆盖。 */
function seedFromPlanIfEmpty(): void {
  if (entries.length > 0 || session.value?.entries.length) return
  planItems.value.forEach((it, i) => {
    const targetSets = it.targetSets === null ? 1 : Math.min(Math.max(it.targetSets, 1), WORKOUT_LIMITS.maxSetsPerEntry)
    const sets: DraftSet[] = []
    for (let n = 1; n <= targetSets; n += 1) sets.push(emptySet(n))
    entries.push({
      key: mkKey(),
      positionNo: i + 1,
      exerciseId: it.exerciseId,
      exerciseCode: it.exerciseCode,
      exerciseName: it.exerciseName,
      category: it.category,
      planItemId: it.id,
      planTargetSets: it.targetSets,
      planRepsMin: it.repsMin,
      planRepsMax: it.repsMax,
      planTargetExternalLoadKg: it.targetExternalLoadKg,
      planTargetDurationSeconds: it.targetDurationSeconds,
      durationSeconds: null,
      distanceMeters: null,
      notes: null,
      sets
    })
  })
  activePosition.value = entries[0]?.positionNo ?? 1
}

function emptySet(setNo: number): DraftSet {
  // isCompleted 恒为 false：勾不勾只能由用户点（§3 V25「永不替用户确认」）
  return { key: mkKey(), setNo, reps: null, externalLoadKg: null, durationSeconds: null, isCompleted: false, notes: null }
}

/* ------------------------------------------------------------------ 展示辅助 */

function planItemLine(it: PlanItem): string {
  const parts: string[] = [categoryLabel(it.category)]
  if (it.targetSets !== null) parts.push(`${it.targetSets} 组`)
  if (it.repsMin !== null || it.repsMax !== null) {
    parts.push(it.repsMin !== null && it.repsMax !== null ? `${it.repsMin}–${it.repsMax} 次` : `${it.repsMin ?? it.repsMax} 次`)
  }
  if (it.targetExternalLoadKg !== null) parts.push(formatDecimal(it.targetExternalLoadKg, 'kg'))
  if (it.targetDurationSeconds !== null) parts.push(secondsToHuman(it.targetDurationSeconds))
  return parts.join(' · ')
}

function referenceLine(en: DraftEntry): string {
  const parts: string[] = []
  if (en.planTargetSets !== null) parts.push(`${en.planTargetSets} 组`)
  if (en.planRepsMin !== null || en.planRepsMax !== null) {
    parts.push(
      en.planRepsMin !== null && en.planRepsMax !== null
        ? `${en.planRepsMin}–${en.planRepsMax} 次`
        : `${en.planRepsMin ?? en.planRepsMax} 次`
    )
  }
  if (en.planTargetExternalLoadKg !== null) parts.push(formatDecimal(en.planTargetExternalLoadKg, 'kg'))
  if (en.planTargetDurationSeconds !== null) parts.push(secondsToHuman(en.planTargetDurationSeconds))
  return parts.join(' · ')
}

/** 收起态也要能看出这个动作记到哪一步了，否则"只展开一个"就变成藏业务状态。 */
function entryFoldLine(en: DraftEntry): string {
  const done = en.sets.filter((st) => st.isCompleted).length
  const parts = [`${done}/${en.sets.length} 组已确认`]
  if (en.category) parts.push(categoryLabel(en.category))
  if (en.planItemId === null) parts.push('自主添加')
  if (en.durationSeconds !== null) parts.push(`时长 ${secondsToHuman(en.durationSeconds)}`)
  if (en.distanceMeters !== null) parts.push(`${formatDecimal(en.distanceMeters, 'm')}`)
  return parts.join(' · ')
}

function isActive(position: number): boolean {
  return activePosition.value === position
}

function focusEntry(position: number): void {
  activePosition.value = isActive(position) ? 0 : position
}

/* ------------------------------------------------------------------ 编辑动作 */

function toggleSet(en: DraftEntry, st: DraftSet): void {
  if (readOnly.value) return
  // 勾选状态只由这一下点击产生：勾上才展开数值输入位，未勾的组永远是"未确认"
  st.isCompleted = !st.isCompleted
  activePosition.value = en.positionNo
}

function addSet(en: DraftEntry): void {
  if (readOnly.value) return
  if (en.sets.length >= WORKOUT_LIMITS.maxSetsPerEntry) {
    uni.showToast({ title: `单个动作最多 ${WORKOUT_LIMITS.maxSetsPerEntry} 组`, icon: 'none' })
    return
  }
  en.sets.push(emptySet(en.sets.length + 1))
}

function renumber(): void {
  entries.forEach((en, i) => {
    en.positionNo = i + 1
    en.sets.forEach((st, j) => (st.setNo = j + 1))
  })
  if (!entries.some((e) => e.positionNo === activePosition.value)) activePosition.value = entries[0]?.positionNo ?? 0
}

function openPicker(): void {
  if (readOnly.value) return
  pickerVisible.value = true
}

function onExerciseSelected(ex: ExerciseItem): void {
  if (entries.length >= WORKOUT_LIMITS.maxEntries) {
    uni.showToast({ title: `一场最多 ${WORKOUT_LIMITS.maxEntries} 个动作`, icon: 'none' })
    return
  }
  // 自主动作不带 planItemId：统计里它只算额外条目，不进计划完成度的分母（§6）
  entries.push({
    key: mkKey(),
    positionNo: entries.length + 1,
    exerciseId: ex.id,
    exerciseCode: ex.exerciseCode,
    exerciseName: ex.name,
    category: ex.category,
    planItemId: null,
    planTargetSets: null,
    planRepsMin: null,
    planRepsMax: null,
    planTargetExternalLoadKg: null,
    planTargetDurationSeconds: null,
    durationSeconds: null,
    distanceMeters: null,
    notes: null,
    sets: [emptySet(1)]
  })
  renumber()
  activePosition.value = entries[entries.length - 1].positionNo
}

function removeEntry(en: DraftEntry): void {
  const idx = entries.findIndex((e) => e.key === en.key)
  if (idx < 0) return
  const done = en.sets.filter((st) => st.isCompleted).length
  uni.showModal({
    title: '删除该动作',
    content: `将从本场移除「${en.exerciseName || en.exerciseCode || '该动作'}」共 ${en.sets.length} 组（其中 ${done} 组已确认）。保存后服务端按本次入参整棵重建。`,
    confirmText: '删除',
    cancelText: '返回',
    success: (res) => {
      if (!res.confirm) return
      entries.splice(idx, 1)
      renumber()
    }
  })
}

function onEntryNotes(en: DraftEntry, evt: Event): void {
  en.notes = inputValue(evt)
}

function onSetNotes(st: DraftSet, evt: Event): void {
  st.notes = inputValue(evt)
}

function onSessionNotes(evt: Event): void {
  sessionNotes.value = inputValue(evt)
}

/* ------------------------------------------------------------------ 本地校验 */

interface LocalIssue {
  where: string
  message: string
  code: string
}

function validateLocal(): LocalIssue[] {
  const out: LocalIssue[] = []
  if (entries.length > WORKOUT_LIMITS.maxEntries) {
    out.push({ where: '本场', message: `动作条目最多 ${WORKOUT_LIMITS.maxEntries} 个`, code: 'TOO_MANY_ENTRIES' })
  }
  if (sessionNotes.value.length > WORKOUT_LIMITS.maxNotesLength) {
    out.push({ where: '本场备注', message: `不超过 ${WORKOUT_LIMITS.maxNotesLength} 字`, code: 'TOO_LONG' })
  }
  const positions = new Set<number>()
  entries.forEach((en) => {
    const name = en.exerciseName || en.exerciseCode || `第 ${en.positionNo} 项`
    if (!en.exerciseId) out.push({ where: name, message: '动作缺少字典 ID，无法提交', code: 'REQUIRED' })
    if (positions.has(en.positionNo)) out.push({ where: name, message: '顺序号重复', code: 'INVALID_POSITION' })
    positions.add(en.positionNo)
    if (en.sets.length > WORKOUT_LIMITS.maxSetsPerEntry) {
      out.push({ where: name, message: `组数最多 ${WORKOUT_LIMITS.maxSetsPerEntry}`, code: 'TOO_MANY_SETS' })
    }
    if (en.durationSeconds !== null && !(en.durationSeconds >= 1)) {
      out.push({ where: name, message: '动作时长填了就需 ≥ 1 秒（留空表示未记录）', code: 'MUST_BE_POSITIVE' })
    }
    if (en.distanceMeters !== null) {
      if (!(en.distanceMeters > 0)) out.push({ where: name, message: '距离填了就需 > 0', code: 'MUST_BE_POSITIVE' })
      else if (!isDecimalWithin(en.distanceMeters, 2)) out.push({ where: name, message: '距离最多 2 位小数', code: 'TOO_MANY_DECIMALS' })
    }
    en.sets.forEach((st, si) => {
      const where = `${name} · 第 ${si + 1} 组`
      if (st.reps !== null && (!Number.isInteger(st.reps) || st.reps < 0 || st.reps > 65535)) {
        out.push({ where, message: '次数需为 0–65535 的整数（留空=未记录）', code: 'REPS_OUT_OF_RANGE' })
      }
      if (st.externalLoadKg !== null) {
        if (st.externalLoadKg < 0) out.push({ where, message: '负重不能为负', code: 'NEGATIVE_VALUE' })
        else if (!isWithinDecimal6_2(st.externalLoadKg)) out.push({ where, message: '负重需落在 DECIMAL(6,2) 内且最多 2 位小数', code: 'TOO_MANY_DECIMALS' })
      }
      if (st.durationSeconds !== null && !(st.durationSeconds >= 1)) {
        out.push({ where, message: '该组时长填了就需 ≥ 1 秒', code: 'MUST_BE_POSITIVE' })
      }
      if ((st.notes || '').length > 200) out.push({ where, message: '本组备注不超过 200 字', code: 'TOO_LONG' })
    })
  })
  return out
}

function buildPayload(): WorkoutSaveInput {
  return {
    lockVersion: lockVersion.value,
    notes: sessionNotes.value.trim() === '' ? null : sessionNotes.value.trim(),
    entries: entries.map<WorkoutEntryInput>((en) => ({
      positionNo: en.positionNo,
      exerciseId: en.exerciseId,
      planItemId: en.planItemId,
      durationSeconds: en.durationSeconds,
      distanceMeters: en.distanceMeters,
      notes: blankToNull(en.notes),
      sets: en.sets.map((st) => ({
        setNo: st.setNo,
        reps: st.reps,
        externalLoadKg: st.externalLoadKg,
        durationSeconds: st.durationSeconds,
        // 未勾的组也照样提交其数值，但 isCompleted 明确为 false：不替用户确认
        isCompleted: st.isCompleted,
        notes: blankToNull(st.notes)
      }))
    }))
  }
}

function blankToNull(value: string | null | undefined): string | null {
  const t = (value ?? '').trim()
  return t.length > 0 ? t : null
}

/* ------------------------------------------------------------------ 保存 / 结束 */

async function onSave(): Promise<void> {
  const s = session.value
  if (!s || busy.value) return
  const local = validateLocal()
  issues.value = local
  if (local.length > 0) {
    uni.showToast({ title: `先改掉 ${local.length} 处再保存`, icon: 'none' })
    return
  }
  busy.value = true
  submitFailure.value = null
  try {
    const saved = await saveWorkoutSession(s.id, buildPayload())
    applySaved(saved, `已保存草稿：${saved.entries.length} 个动作、已确认 ${countCompleted(saved)} 组，lockVersion ${lockVersion.value} → ${saved.lockVersion}。`)
  } catch (err) {
    handleSubmitError(err, '保存')
  } finally {
    busy.value = false
  }
}

function askComplete(): void {
  const s = session.value
  if (!s || busy.value) return
  const local = validateLocal()
  issues.value = local
  if (local.length > 0) {
    uni.showToast({ title: `先改掉 ${local.length} 处再结束`, icon: 'none' })
    return
  }
  const done = countCompleted(s)
  uni.showModal({
    title: '结束并确认',
    content: `确认后本场记录成为正式已确认记录并参与统计（当前已确认 ${done} 组）。未勾的组保持未确认。确认结束？`,
    confirmText: '结束',
    cancelText: '再等等',
    success: (res) => {
      if (res.confirm) void doComplete()
    }
  })
}

/** 结束前先整棵保存再 complete：complete 只改状态，不会替我们写子树。 */
async function doComplete(): Promise<void> {
  const s = session.value
  if (!s) return
  busy.value = true
  submitFailure.value = null
  try {
    const saved = await saveWorkoutSession(s.id, buildPayload())
    const ended = await completeWorkoutSession(saved.id, { lockVersion: saved.lockVersion })
    applySaved(ended, `已结束并确认为正式记录（COMPLETED）：业务日期 ${ended.businessDate}，场次历时 ${ended.durationSeconds === null ? '未记录' : secondsToHuman(ended.durationSeconds)}。重复点「结束」不会重复计数。`)
  } catch (err) {
    handleSubmitError(err, '结束')
  } finally {
    busy.value = false
  }
}

function countCompleted(detail: WorkoutSessionDetail): number {
  return detail.entries.reduce((sum, en) => sum + en.completedSets, 0)
}

function applySaved(detail: WorkoutSessionDetail, tip: string): void {
  session.value = detail
  lockVersion.value = detail.lockVersion
  sessionNotes.value = detail.notes ?? ''
  hydrate(detail.entries)
  issues.value = []
  successTip.value = tip
  uni.showToast({ title: '已同步服务端结果', icon: 'none' })
}

function handleSubmitError(err: unknown, actionName: string): void {
  const view = toFailureView(err)
  submitFailure.value = view
  successTip.value = ''
  // 409：先问再重载，绝不静默把用户的填写换成服务端旧内容
  if (isVersionConflict(err) || view.invalidTransition) askReloadAfterConflict(actionName)
  else if (view.sessionExpired) uni.showToast({ title: '登录已失效，请重新登录', icon: 'none' })
  else uni.showToast({ title: view.title, icon: 'none' })
}

function askReloadAfterConflict(actionName: string): void {
  uni.showModal({
    title: '版本已变化',
    content: `服务端这场记录已被更新（或状态已变），${actionName}没有写入。是否放弃当前填写并重新读取？取消则保留你填的内容，但本次提交仍未生效。`,
    confirmText: '放弃并重读',
    cancelText: '保留我的填写',
    success: (res) => {
      if (res.confirm) void reloadFromServer()
    }
  })
}

async function reloadFromServer(): Promise<void> {
  const s = session.value
  if (!s) return
  busy.value = true
  submitFailure.value = null
  try {
    // 回读走 GET：再 POST 一次开始会把"参考与业务日期"重新算一遍，跨天继续时会被服务端判成幂等冲突
    const fresh = await fetchWorkoutSession(s.id)
    session.value = fresh
    lockVersion.value = fresh.lockVersion
    sessionNotes.value = fresh.notes ?? ''
    hydrate(fresh.entries)
    successTip.value = `已按服务端最新内容重新读取（场次 ${fresh.id}，lockVersion ${fresh.lockVersion}），本次未保存的填写已丢弃。`
  } catch (err) {
    const view = toFailureView(err)
    if (isSessionExpiredError(err)) loadFailure.value = view
    else submitFailure.value = view
  } finally {
    busy.value = false
  }
}

function backHome(): void {
  uni.navigateBack({
    fail: () => {
      uni.switchTab({ url: '/pages/index/home' })
    }
  })
}

/* ------------------------------------------------------------------ 丢弃 / 删除 */

function askDiscard(): void {
  const s = session.value
  if (!s || busy.value) return
  uni.showModal({
    title: '丢弃这场草稿',
    content: '丢弃后本场不再参与任何统计，记录本身保留（可回看），并释放「同一用户只保留一条进行中」的闸门，你可以重新开始一场。确定丢弃？',
    confirmText: '丢弃',
    cancelText: '返回',
    success: (res) => {
      if (res.confirm) void runDanger(() => discardWorkoutSession(s.id, { lockVersion: lockVersion.value }), '已丢弃')
    }
  })
}

function askDelete(): void {
  const s = session.value
  if (!s || busy.value) return
  const entries = s.entries.length
  const sets = s.entries.reduce((sum, en) => sum + en.completedSets, 0)
  uni.showModal({
    title: '删除整场记录',
    content: `将删除本场次及其 ${entries} 个动作、${sets} 个已确认组，同时删除后当天与本周统计会同步变化。此操作不可恢复。确定删除？`,
    confirmText: '删除',
    cancelText: '返回',
    success: (res) => {
      if (res.confirm) void runDanger(() => deleteWorkoutSession(s.id), '已删除')
    }
  })
}

/**
 * 危险操作的同一条路径：带上当前 `lockVersion`（乐观锁），成功后**离开本页**而不是继续渲染旧聚合——
 * 丢弃后本页只读、删除后本页无数据，留着表单只会让用户以为还能再保存。
 */
async function runDanger(call: () => Promise<unknown>, okWord: string): Promise<void> {
  busy.value = true
  submitFailure.value = null
  try {
    await call()
    uni.showToast({ title: `${okWord}，正在返回`, icon: 'none' })
    setTimeout(() => backHome(), 600)
  } catch (err) {
    handleSubmitError(err, okWord)
  } finally {
    busy.value = false
  }
}

onLoad((query) => {
  const q = (query ?? {}) as Record<string, string | undefined>
  planId.value = (q.planId || '').trim()
  planDayId.value = (q.planDayId || '').trim()
  const sessionId = (q.sessionId || '').trim()
  if (sessionId) void enterById(sessionId)
  else void enter()
})

/** 重试要分清进入方式：按 ID 打开的走 GET，绝不能把「重读」变成「再开始一场」。 */
function retry(): void {
  const id = (session.value?.id || '').trim()
  if (entryMode.value === 'resume' && id) void enterById(id)
  else void enter()
}
</script>

<style lang="scss" scoped>
/* ---------- 场次头 ---------- */
.rd-head__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $fa-space-tight;
  margin-bottom: $fa-space-tight;
}

.rd-head__title {
  flex: 1;
}

.rd-chip {
  margin: 0;
  flex: 0 0 auto;
}

.rd-kv {
  display: flex;
  justify-content: space-between;
  gap: 20rpx;
  padding: 6rpx 0;
  font-size: $fa-font-ui;
}

.rd-toggle {
  margin-top: $fa-space-tight;
  width: 220rpx;
}

.rd-meta {
  margin-top: $fa-space-tight;
  padding-top: $fa-space-tight;
  border-top: 1px solid $fa-line;
}

.rd-mono {
  word-break: break-all;
  text-align: right;
  flex: 1;
}

/* ---------- 区块标题（图标 + 短标题） ---------- */
.rd-sec__head {
  display: flex;
  align-items: center;
  gap: 12rpx;
}

.rd-sec__t {
  margin: 0;
}

.rd-sec__count {
  margin-left: auto;
  font-size: $fa-font-ui;
  color: $fa-text-sub;
}

.rd-plan-note {
  display: block;
  margin-top: 8rpx;
  line-height: 1.5;
}

/* ---------- 计划参考行（只读） ---------- */
.rd-ref__row {
  display: flex;
  align-items: flex-start;
  gap: 20rpx;
  padding: 16rpx 0;
  border-top: 1px solid $fa-line;
}

.rd-ref__pos {
  flex: 0 0 auto;
  font-size: $fa-font-ui;
  font-weight: 600;
  color: $fa-text-mute;
}

.rd-ref__text {
  flex: 1;
  min-width: 0;
}

.rd-ref__name {
  display: block;
  font-size: $fa-font-ui-lg;
  line-height: 1.4;
}

.rd-ref__target {
  display: block;
  margin-top: 4rpx;
  line-height: 1.5;
}

.rd-ref__hint {
  display: block;
  margin: $fa-space-tight 0 4rpx;
  padding: 12rpx 16rpx;
  border-radius: $fa-radius-sm;
  background-color: $fa-highlight;
  color: $fa-accent; /* 淡黄底上的文字必须用深金，淡黄本身不能当字色 */
  font-size: $fa-font-ui;
  line-height: 1.5;
}

/* ---------- 实际记录：一次聚焦一个动作 ---------- */
.rd-entry {
  border: 1px solid $fa-control-border;
  border-radius: $fa-radius-sm;
  padding: 0 $fa-space-tight;
  margin-top: $fa-space-tight;
  background-color: $fa-page-bg;
}

.rd-entry__head {
  display: flex;
  align-items: center;
  gap: 20rpx;
  min-height: 108rpx;
  padding: $fa-space-tight 0;
}

.rd-entry__head--hover {
  background-color: $fa-highlight;
}

.rd-entry__pos {
  flex: 0 0 auto;
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  color: $fa-text-mute;
}

.rd-entry__pick {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.rd-entry__name {
  font-size: $fa-font-ui-lg;
  line-height: 1.4;
}

.rd-entry__target {
  font-size: $fa-font-ui;
  line-height: 1.5;
}

.rd-entry__chev {
  flex: 0 0 auto;
}

.rd-entry__chev--open {
  transform: rotate(90deg);
}

.rd-entry__body {
  padding-bottom: $fa-space-card;
  border-top: 1px solid $fa-line;
}

/* ---------- 逐组：勾选 + 数值 ---------- */
.rd-set {
  padding: $fa-space-tight 0;
  border-bottom: 1px solid $fa-line;
}

.rd-set__head {
  display: flex;
  align-items: center;
  gap: 20rpx;
  min-height: $fa-tap;
}

.rd-set__head--hover {
  background-color: $fa-highlight;
}

/* 勾选框必须有边框（$fa-line 只有 1.3:1 撑不起控件边界），勾上再加描边 + 图形 + 文字三重提示 */
.rd-set__box {
  flex: 0 0 auto;
  width: 48rpx;
  height: 48rpx;
  border: 1px solid $fa-control-border;
  border-radius: 10rpx;
  background-color: $fa-surface;
  display: flex;
  align-items: center;
  justify-content: center;
}

.rd-set__box--on {
  border-color: $fa-success;
  background-color: $fa-success-weak;
}

.rd-set__no {
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  color: $fa-text;
}

.rd-set__state {
  margin-left: auto;
  font-size: $fa-font-ui;
  color: $fa-text-sub;
}

.rd-set__fields {
  margin-top: $fa-space-tight;
}

.rd-set__notes {
  /* 与同行的 NumberField 对齐：.fa-field 的上下内边距会把备注标签往下推 */
  border-bottom: none;
  padding: 0;
}

.rd-entry__ops {
  display: flex;
  flex-wrap: wrap;
  gap: $fa-space-tight;
  margin-top: $fa-space-tight;
}

.rd-btn--del {
  color: $fa-danger;
  border-color: $fa-danger;
}

/* 危险区排在正文与底部操作条之后：主操作永远是「保存/结束」，删与丢弃不抢视觉重心 */
.rd-danger {
  display: flex;
  flex-wrap: wrap;
  gap: $fa-space-tight;
  align-items: center;
  padding-top: $fa-space-tight;
  border-top: 1px solid $fa-line;
}

.rd-danger .fa-hint {
  flex: 1 1 100%;
  line-height: 1.5;
}

.rd-additem {
  margin-top: $fa-space-tight;
}

.rd-notes {
  margin-top: $fa-space-tight;
}

/* ---------- 两列网格：WXSS 不认「子选择器 + 通配星号」，一律用显式 cell 类 ---------- */
.rd-grid--2 {
  display: flex;
  align-items: flex-start;
  gap: $fa-space-grid;
  margin: $fa-space-tight 0;
}

.rd-grid--2 .rd-grid__cell {
  flex: 1;
  min-width: 0;
}

/* ---------- 错误与清单 ---------- */
.rd-block-title {
  display: block;
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  margin-bottom: 4rpx;
}

.rd-code {
  display: block;
  font-size: $fa-font-ui;
  color: $fa-text-mute;
  margin-top: 4rpx;
  word-break: break-all;
}

.rd-fielderr {
  border-left: 3px solid $fa-danger;
  padding: 6rpx 0 6rpx 16rpx;
  margin-top: 8rpx;
}

.rd-fielderr__name {
  display: block;
  font-size: $fa-font-ui;
  font-weight: 600;
  color: $fa-danger;
}

.rd-issues {
  background-color: $fa-danger-weak;
  border: 1px solid $fa-danger;
  border-radius: $fa-radius;
  padding: $fa-space-card;
  margin-bottom: $fa-space-section;
}

.rd-issues__t {
  color: $fa-danger;
  margin: 0 0 $fa-space-tight;
}

.rd-issue {
  padding: $fa-space-tight 0;
  border-bottom: 1px solid $fa-danger;
}

.rd-issue:last-child {
  border-bottom: none;
}

.rd-issue__where {
  display: block;
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  color: $fa-danger;
}

.rd-issue__msg {
  display: block;
  font-size: $fa-font-prose;
  line-height: 1.6;
  color: $fa-text;
  margin-top: 8rpx;
}

.rd-issue__code {
  display: block;
  font-size: $fa-font-ui;
  color: $fa-text-sub;
  margin-top: 8rpx;
  word-break: break-all;
}

.rd-label {
  font-size: $fa-font-ui;
  color: $fa-text-sub;
  margin-bottom: 6rpx;
}

.rd-tip {
  margin-bottom: $fa-space-section;
}
</style>
