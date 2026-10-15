<template>
  <view class="state-block">
    <!-- 状态 5：登录失效（请求封装层已清令牌并 reLaunch 到登录页，页面保留一次提示） -->
    <view v-if="state === 'sessionExpired'" class="fa-banner fa-banner--warn">
      <view class="sb-title-row">
        <FaIcon name="alert" tone="accent" size="40rpx" />
        <text class="sb-title">{{ sessionExpiredTitle }}</text>
      </view>
      <text class="fa-prose">{{ failureText || '请回到登录页重新登录。' }}</text>
    </view>

    <!-- 状态 1：加载中 -->
    <view v-else-if="state === 'loading'" class="sb-loading">
      <FaIcon name="clock" tone="sub" size="44rpx" />
      <text class="fa-muted">{{ loadingText }}</text>
    </view>

    <!-- 状态 3：请求失败（服务端 message 原文 + 错误码 + traceId + data.fields 逐字段） -->
    <view v-else-if="state === 'error'" class="sb-error">
      <view class="fa-banner fa-banner--error">
        <view class="sb-title-row">
          <FaIcon name="alert" tone="danger" size="40rpx" />
          <text class="sb-title">{{ errorTitle }}</text>
        </view>
        <text v-if="failureText" class="fa-prose">{{ failureText }}</text>
        <text v-if="errorCode" class="sb-code">错误码：{{ errorCode }}</text>
        <text v-if="failure && failure.traceId" class="sb-code">traceId：{{ failure.traceId }}</text>
        <text v-if="failure && failure.retryAfter" class="sb-code">{{ failure.retryAfter }} 秒后可重试</text>
      </view>
      <view v-if="failure && failure.fields.length > 0" class="sb-fields">
        <view v-for="(f, i) in failure.fields" :key="`pf-${i}`" class="sb-fields__row">
          <text class="sb-fields__field">{{ f.field }}</text>
          <text class="fa-prose">{{ f.message }}</text>
          <text class="sb-code">{{ f.code }}</text>
        </view>
      </view>
      <view v-if="retryable" class="fa-btn-row">
        <view class="fa-btn fa-btn--primary" @tap="emit('retry')">重新加载</view>
      </view>
    </view>

    <!-- 状态 2：无数据 -->
    <EmptyState v-else-if="state === 'empty'" :title="emptyTitle" :desc="emptyDesc">
      <template v-if="$slots.action" #action>
        <slot name="action" />
      </template>
    </EmptyState>

    <!-- 状态 4：操作成功（真实数据 + 可选成功提示 + 可选提交失败横幅） -->
    <template v-else>
      <view v-if="successTip" class="fa-banner fa-banner--info">
        <text class="fa-prose">{{ successTip }}</text>
      </view>

      <view v-if="submitFailure" class="fa-banner fa-banner--error">
        <view class="sb-title-row">
          <FaIcon name="alert" tone="danger" size="40rpx" />
          <text class="sb-title">{{ submitFailure.title }}</text>
        </view>
        <text v-if="submitFailure.detail" class="fa-prose">{{ submitFailure.detail }}</text>
        <text class="sb-code">错误码：{{ submitFailure.code }}</text>
        <text v-if="submitFailure.traceId" class="sb-code">traceId：{{ submitFailure.traceId }}</text>
        <view v-if="submitFailure.fields.length > 0" class="sb-fields">
          <view v-for="(f, i) in submitFailure.fields" :key="`sf-${i}`" class="sb-fields__row">
            <text class="sb-fields__field">{{ f.field }}</text>
            <text class="fa-prose">{{ f.message }}</text>
            <text class="sb-code">{{ f.code }}</text>
          </view>
        </view>
      </view>

      <slot />
    </template>
  </view>
</template>

<script setup lang="ts">
/**
 * 五态开关：每个页面都通过它显式实现
 * 「加载中 / 无数据 / 提交失败 / 操作成功 / 登录失效」（docs/p1-scope.md §4）。
 * - `state==='success'` 时默认插槽渲染真实接口数据；
 * - 页面级请求失败用 `failure`，按钮类提交失败用 `submitFailure`（可逐字段展示 `data.fields`）；
 * - 组件不猜测语义：文案一律来自 `utils/errorMessage.ts` 对契约错误码的映射 + 服务端 message 原文。
 */
import { computed } from 'vue'
import FaIcon from './FaIcon.vue'
import type { PageState } from '@/types/api'
import type { FailureView } from '@/utils/errorMessage'
import EmptyState from './EmptyState.vue'

const props = withDefaults(
  defineProps<{
    state: PageState
    failure?: FailureView | null
    submitFailure?: FailureView | null
    successTip?: string
    loadingText?: string
    emptyTitle?: string
    emptyDesc?: string
    sessionExpiredTitle?: string
    retryable?: boolean
  }>(),
  {
    failure: null,
    submitFailure: null,
    successTip: '',
    loadingText: '加载中…',
    emptyTitle: '暂无数据',
    emptyDesc: '',
    sessionExpiredTitle: '登录已失效，请重新登录',
    retryable: true
  }
)

const emit = defineEmits<{ (e: 'retry'): void }>()

const failureText = computed(() => props.failure?.detail ?? '')
const errorTitle = computed(() => props.failure?.title ?? '加载失败')
const errorCode = computed(() => props.failure?.code ?? '')
</script>

<style lang="scss" scoped>
.sb-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: $fa-space-tight;
  padding: 48rpx 12px;
  text-align: center;
}

/* 标题行：图形 + 文字并排，状态不只靠底色识别 */
.sb-title-row {
  display: flex;
  align-items: center;
  gap: 12rpx;
}

.sb-title {
  display: block;
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  margin-bottom: 4px;
}

.sb-code {
  display: block;
  font-size: $fa-font-ui;
  color: $fa-text-mute;
  margin-top: 4px;
  word-break: break-all;
}

.sb-fields {
  margin-top: 8px;
}

.sb-fields__row {
  border-left: 3px solid $fa-danger;
  padding: 6px 0 6px 10px;
  margin-bottom: 6px;
}

.sb-fields__field {
  display: block;
  font-size: $fa-font-ui;
  font-weight: 600;
  color: $fa-danger;
}
</style>
