<template>
  <view class="fa-page pex">
    <!-- 类别是「同一视图的口径切换」→ 分段控件（图标 + 两字标签）。
         完整类别名（含机器码，如「力量（STRENGTH）」）仍原样出现在每条动作上，信息不缩。 -->
    <view class="pex-segrow">
      <FaSegmented :model-value="query.category" :options="categorySegments" @update:model-value="pickCategory" />
    </view>

    <!-- 过滤条件保持可见：器械 picker + 关键词，全部走 GET /api/exercises 的真实参数（docs/p1-api.md §6） -->
    <view class="fa-card">
      <view class="fa-field">
        <view class="fa-field__label"><text>器械</text></view>
        <picker class="pex-picker" mode="selector" :range="equipmentLabels" :value="equipmentIndex" @change="onEquipmentChange">
          <view class="fa-input pex-pick">
            <text class="pex-pick__text">{{ equipmentText }}</text>
            <FaIcon name="chevron" tone="sub" size="36rpx" />
          </view>
        </picker>
      </view>

      <view class="fa-field">
        <view class="fa-field__label"><text>关键词</text></view>
        <view class="pex-search">
          <input class="fa-input pex-kw" :value="query.keyword" placeholder="动作名或代码，前缀/包含匹配" @input="onKeywordInput" @confirm="reload" />
          <view class="fa-btn pex-search__btn" @tap="reload">
            <FaIcon name="dict" tone="accent" size="40rpx" />
            <text>查询</text>
          </view>
        </view>
        <text class="fa-hint">字典由后端维护，本页只读；结果按名称升序分页返回。</text>
      </view>
    </view>

    <StateBlock
      :state="pageState"
      :failure="failure"
      loading-text="正在加载动作字典…"
      empty-title="没有符合条件的动作"
      empty-desc="换一个类别或器械，或清空关键词再试。字典由后端维护，本页只读。"
      @retry="reload"
    >
      <view class="pex-count fa-muted">共 {{ total === null ? '未知' : total }} 条，已载入 {{ items.length }} 条</view>

      <view v-for="item in items" :key="item.id" class="fa-card">
        <view class="pex-item__head">
          <text class="fa-strong pex-item__name">{{ item.name }}</text>
          <text class="fa-chip pex-item__cat">{{ categoryLabel(item.category) }}</text>
        </view>
        <text class="fa-muted pex-item__code">{{ item.exerciseCode }}</text>
        <view class="pex-item__meta">
          <text class="fa-muted pex-line">器械：{{ item.equipment.length > 0 ? item.equipment.map(equipmentLabel).join('、') : '—' }}</text>
          <text class="fa-muted pex-line">肌群：{{ item.muscleGroups.length > 0 ? item.muscleGroups.join('、') : '—' }}</text>
        </view>
        <text v-if="item.instructions" class="fa-prose pex-line pex-inst">{{ item.instructions }}</text>
        <text v-else class="fa-muted pex-line">说明：本轮字典未提供（不伪造）</text>
      </view>

      <view v-if="hasNext" class="fa-btn fa-btn--block pex-more" @tap="loadMore">
        {{ loadingMore ? '加载中…' : total === null ? `加载更多（已载入 ${items.length} 条）` : `加载更多（已载入 ${items.length} / ${total}）` }}
      </view>
      <view v-else-if="items.length > 0" class="pex-end fa-muted">已加载全部 {{ total === null ? '' : total }} 条{{ total === null ? '（条数未知）' : '' }}</view>
    </StateBlock>
  </view>
</template>

<script setup lang="ts">
/**
 * 动作字典浏览页（docs/p1-api.md §6，只读）：类别 / 器械过滤 + 关键词 + 分页。
 * 一切数据经 `api/exercise.ts` 的真实接口；`sourceUrl`/`instructions` 恒为 null 时不伪造。
 * 五态齐全（docs/p1-scope.md §4）。
 *
 * UI 重排：类别切换改用 FaSegmented（图标 + 两字标签），查询与「加载更多」原样保留；
 * 动作名称、代码、完整类别名、器械/肌群与说明都留在列表里——业务内容不折叠、不缩写。
 */
import { computed, reactive, ref } from 'vue'
import { inputValue, detailValue } from '@/utils/events'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import FaIcon from '@/components/FaIcon.vue'
import FaSegmented from '@/components/FaSegmented.vue'
import StateBlock from '@/components/StateBlock.vue'
import { fetchExercises } from '@/api/exercise'
import type { FaIconName } from '@/styles/icon-names'
import { CATEGORY_OPTIONS, categoryLabel, type ExerciseCategory, type ExerciseItem } from '@/types/exercise'
import { EQUIPMENT_CODES, equipmentLabel } from '@/types/profile'
import { pageItems, pageMeta, isSessionExpiredError } from '@/utils/request'
import { toFailureView, briefError, type FailureView } from '@/utils/errorMessage'
import type { PageState } from '@/types/api'

type FilterValue = ExerciseCategory | ''

/** 分段控件的图标只是识别辅助，取值仍是后端的 category 枚举，一个都没少。 */
const CATEGORY_ICONS: Record<ExerciseCategory, FaIconName> = {
  STRENGTH: 'dumbbell',
  CARDIO: 'chart',
  MOBILITY: 'moon'
}

const CATEGORY_SHORT_LABELS: Record<ExerciseCategory, string> = {
  STRENGTH: '力量',
  CARDIO: '有氧',
  MOBILITY: '柔韧'
}

/** 仍以 `CATEGORY_OPTIONS` 为唯一来源：后端将来加类别会自动出现在分段里（没有短标签时退回完整名）。 */
const categorySegments: Array<{ value: string; label: string; icon: FaIconName }> = [
  { value: '', label: '全部', icon: 'dict' },
  ...CATEGORY_OPTIONS.map((o) => ({
    value: o.value as string,
    label: CATEGORY_SHORT_LABELS[o.value] ?? o.label,
    icon: CATEGORY_ICONS[o.value] ?? ('more' as FaIconName)
  }))
]

const equipmentOptions: Array<{ value: string; label: string }> = [
  { value: '', label: '全部器械' },
  ...EQUIPMENT_CODES.map((code) => ({ value: code, label: equipmentLabel(code) }))
]
const equipmentLabels = equipmentOptions.map((o) => o.label)

const items = ref<ExerciseItem[]>([])
const total = ref<number | null>(null)
const hasNext = ref(false)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const loadingMore = ref(false)
const loadedOnce = ref(false)
const failure = ref<FailureView | null>(null)

const query = reactive<{ category: FilterValue; equipment: string; keyword: string }>({
  category: '',
  equipment: '',
  keyword: ''
})

const equipmentIndex = computed(() => {
  const i = equipmentOptions.findIndex((o) => o.value === query.equipment)
  return i < 0 ? 0 : i
})

const equipmentText = computed(() => {
  const hit = equipmentOptions.find((o) => o.value === query.equipment)
  return hit ? hit.label : '全部器械'
})

const pageState = computed<PageState>(() => {
  if (failure.value && failure.value.sessionExpired) return 'sessionExpired'
  if (loading.value && items.value.length === 0) return 'loading'
  if (failure.value && items.value.length === 0) return 'error'
  if (loadedOnce.value && items.value.length === 0) return 'empty'
  return 'success'
})

function setCategory(value: FilterValue): void {
  query.category = value
  reload()
}

/** FaSegmented 只吐 string，这里收窄回 FilterValue 后走的仍是原来的 setCategory + reload 路径。 */
function pickCategory(value: string): void {
  setCategory(value as FilterValue)
}

// picker 的结果在 detail.value 且是 number/数组，不是 string：用 inputValue 会恒得 0（=全部器械）
function onEquipmentChange(evt: Event): void {
  const picked = detailValue(evt)
  const idx = Number(Array.isArray(picked) ? picked[0] : picked)
  query.equipment = equipmentOptions[idx] ? equipmentOptions[idx].value : ''
  reload()
}

function onKeywordInput(evt: Event): void {
  query.keyword = inputValue(evt)
}

async function load(reset: boolean): Promise<void> {
  if (loading.value) return
  if (reset) {
    page.value = 1
    loading.value = true
    failure.value = null
  } else {
    loadingMore.value = true
  }
  try {
    const res = await fetchExercises({
      category: query.category || undefined,
      equipment: query.equipment || undefined,
      keyword: query.keyword || undefined,
      page: page.value,
      size: size.value,
      sort: 'name,asc'
    })
    const data = pageItems(res)
    items.value = reset ? data : items.value.concat(data)
    const meta = pageMeta(res, page.value, size.value)
    total.value = meta.total
    hasNext.value = meta.hasNext
    loadedOnce.value = true
    failure.value = null
  } catch (err) {
    failure.value = toFailureView(err)
    if (isSessionExpiredError(err)) loadedOnce.value = true
    if (items.value.length > 0) uni.showToast({ title: briefError(err), icon: 'none' })
  } finally {
    loading.value = false
    loadingMore.value = false
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

onShow(() => {
  if (!loadedOnce.value) reload()
})

onPullDownRefresh(() => {
  void load(true).then(() => uni.stopPullDownRefresh())
})
</script>

<style lang="scss" scoped>
/* FaSegmented 只有在 flex 父容器里才按内容宽度排（组件自带 align-self: flex-start），
   放进普通 block 会被拉成整行、右侧留一大片空白 */
.pex-segrow {
  display: flex;
  margin-bottom: $fa-space-section;
}

.pex-picker {
  display: block;
}

.pex-pick {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12rpx;
}

.pex-pick__text {
  flex: 1;
  min-width: 0;
}

.pex-search {
  display: flex;
  align-items: center;
  gap: $fa-space-tight;
}

.pex-kw {
  flex: 1;
  min-width: 0;
}

/* 查询按钮保持 88rpx 交互高度，不为了塞进一行而缩小点击区 */
.pex-search__btn {
  flex: 0 0 auto;
  min-width: 176rpx;
}

.pex-count {
  display: block;
  margin-bottom: $fa-space-tight;
}

/* 一条动作一张卡（不套卡）：名称 + 完整类别名同排，代码/器械/肌群/说明逐行原样可读 */
.pex-item__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $fa-space-tight;
}

.pex-item__name {
  font-size: $fa-font-ui-lg;
  flex: 1;
}

.pex-item__cat {
  margin: 0;
}

.pex-item__code {
  display: block;
  margin-top: 6rpx;
  word-break: break-all;
}

.pex-item__meta {
  margin-top: $fa-space-tight;
}

.pex-line {
  display: block;
  line-height: 1.5;
}

.pex-inst {
  margin-top: $fa-space-tight;
  color: $fa-text-sub;
}

.pex-more,
.pex-end {
  margin: $fa-space-tight 0 $fa-space-section;
  text-align: center;
}

.pex-end {
  padding: $fa-space-tight 0;
}
</style>
