<template>
  <view v-if="visible" class="picker-mask" @tap="close">
    <view class="picker-panel" @tap.stop="noop">
      <view class="picker-head">
        <text class="fa-title">选择动作</text>
        <view class="picker-close" @tap="close">关闭</view>
      </view>

      <!-- 过滤条件：category / equipment / keyword，全部走 GET /api/exercises 的真实参数（§6） -->
      <view class="picker-filters">
        <view class="picker-chips">
          <view
            v-for="opt in categoryOptions"
            :key="opt.value"
            class="fa-chip"
            :class="{ 'fa-chip--on': query.category === opt.value }"
            @tap="setCategory(opt.value)"
          >
            <text>{{ opt.label }}</text>
          </view>
        </view>

        <view class="picker-filter-row">
          <text class="fa-row__label">器械</text>
          <picker class="picker-filter-row__picker" mode="selector" :range="equipmentLabels" :value="equipmentIndex" @change="onEquipmentChange">
            <view class="fa-input picker-input">{{ equipmentText }}</view>
          </picker>
        </view>

        <view class="picker-filter-row">
          <text class="fa-row__label">关键词</text>
          <input class="fa-input picker-filter-row__input" :value="query.keyword" placeholder="动作名或代码，前缀/包含匹配" @input="onKeywordInput" @confirm="reload" />
          <view class="fa-btn picker-search" @tap="reload">查询</view>
        </view>
      </view>

      <StateBlock
        :state="listState"
        :failure="failure"
        :retryable="true"
        empty-title="没有符合条件的动作"
        empty-desc="换一个类别或器械再试；字典由后端维护，本页只读"
        @retry="reload"
      >
        <scroll-view class="picker-list" scroll-y>
          <view v-for="item in items" :key="item.id" class="picker-item" @tap="choose(item)">
            <view class="picker-item__head">
              <text class="fa-strong">{{ item.name }}</text>
              <text class="fa-chip">{{ categoryLabel(item.category) }}</text>
            </view>
            <text class="fa-muted">{{ item.exerciseCode }}</text>
            <text class="fa-muted">器械：{{ item.equipment.length > 0 ? item.equipment.join('、') : '—' }}</text>
          </view>
          <view v-if="hasNext" class="fa-btn fa-btn--block picker-more" @tap="loadMore">
            {{ loadingMore ? '加载中…' : total === null ? `加载更多（已载入 ${items.length} 条）` : `加载更多（已载入 ${items.length} / ${total}）` }}
          </view>
        </scroll-view>
      </StateBlock>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 动作选择器：真实调 `GET /api/exercises`（docs/p1-api.md §6，只返回 ACTIVE 字典项）。
 * 计划编辑页用它拿 `exerciseCode`——写入聚合时以代码标识动作，与导入契约 §4 的客户端可见键一致。
 */
import { computed, reactive, ref, watch } from 'vue'
import { detailValue, inputValue } from '@/utils/events'
import StateBlock from './StateBlock.vue'
import { fetchExercises } from '@/api/exercise'
import { CATEGORY_OPTIONS, categoryLabel, type ExerciseCategory, type ExerciseItem } from '@/types/exercise'
import { EQUIPMENT_CODES, EQUIPMENT_LABELS } from '@/types/profile'
import type { PageState } from '@/types/api'
import { pageItems, pageMeta } from '@/utils/request'
import { toFailureView, type FailureView } from '@/utils/errorMessage'

const props = withDefaults(defineProps<{ visible: boolean }>(), { visible: false })
const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'select', item: ExerciseItem): void
}>()

type FilterValue = ExerciseCategory | ''

const categoryOptions: Array<{ value: FilterValue; label: string }> = [
  { value: '', label: '全部类别' },
  ...CATEGORY_OPTIONS
]

const equipmentOptions: Array<{ value: string; label: string }> = [
  { value: '', label: '全部器械' },
  ...EQUIPMENT_CODES.map((code) => ({ value: code, label: EQUIPMENT_LABELS[code] }))
]

const equipmentLabels = equipmentOptions.map((o) => `${o.label}`)

const items = ref<ExerciseItem[]>([])
const total = ref<number | null>(null)
const hasNext = ref(false)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const loadingMore = ref(false)
const failure = ref<FailureView | null>(null)
const loadedOnce = ref(false)

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

const listState = computed<PageState>(() => {
  if (failure.value && items.value.length === 0) return 'error'
  if (!loadedOnce.value && loading.value) return 'loading'
  if (loadedOnce.value && items.value.length === 0) return 'empty'
  return 'success'
})

function noop(): void {
  /* 阻止点击面板内部时关闭遮罩 */
}

function close(): void {
  emit('update:visible', false)
}

async function load(reset: boolean): Promise<void> {
  if (loading.value) return
  loading.value = reset ? true : loadingMore.value
  if (reset) {
    page.value = 1
    failure.value = null
    loading.value = true
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
    const rows = pageItems(res)
    items.value = reset ? rows : items.value.concat(rows)
    const meta = pageMeta(res, page.value, size.value)
    total.value = meta.total
    hasNext.value = meta.hasNext
    loadedOnce.value = true
    failure.value = null
  } catch (err) {
    failure.value = toFailureView(err)
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

function setCategory(value: FilterValue): void {
  query.category = value
  reload()
}

function onEquipmentChange(evt: Event): void {
  const picked = detailValue(evt)
  const idx = Number(Array.isArray(picked) ? picked[0] : picked)
  query.equipment = equipmentOptions[idx] ? equipmentOptions[idx].value : ''
  reload()
}

function onKeywordInput(evt: Event): void {
  query.keyword = inputValue(evt)
}

function choose(item: ExerciseItem): void {
  emit('select', item)
  close()
}

watch(
  () => props.visible,
  (visible) => {
    if (visible && items.value.length === 0) reload()
  },
  { immediate: true }
)
</script>

<style lang="scss" scoped>
.picker-mask {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.45);
  z-index: 90;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
}

.picker-panel {
  background-color: $fa-page-bg;
  border-radius: $fa-radius $fa-radius 0 0;
  padding: $fa-space-card $fa-space-page;
  padding-bottom: calc(#{$fa-space-card} + env(safe-area-inset-bottom));
  max-height: 82vh;
  display: flex;
  flex-direction: column;
}

.picker-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.picker-close {
  min-height: $fa-btn-h;
  padding: 0 20rpx;
  display: flex;
  align-items: center;
  font-size: $fa-font-ui;
  color: $fa-accent; /* 强调文字用深金，淡黄做前景不达标 */
}

.picker-filters {
  background-color: $fa-surface;
  border: 1px solid $fa-line;
  border-radius: $fa-radius-sm;
  padding: $fa-space-tight;
  margin-bottom: $fa-space-tight;
}

.picker-chips {
  display: flex;
  flex-wrap: wrap;
}

.picker-filter-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}

.picker-filter-row__picker {
  flex: 1;
}

.picker-filter-row__input {
  flex: 1;
}

.picker-input {
  min-height: $fa-tap;
}

.picker-search {
  flex: 0 0 auto;
  min-width: 72px;
}

.picker-list {
  flex: 1;
  max-height: 52vh;
}

.picker-item {
  background-color: $fa-surface;
  border: 1px solid $fa-line;
  border-radius: $fa-radius-sm;
  padding: 20rpx;
  margin-bottom: $fa-space-tight;
}

.picker-item__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 2px;
}

.picker-more {
  margin: 6px 0 16px;
}
</style>
