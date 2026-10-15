<template>
  <view class="fa-page login">
    <!-- 品牌区只有一个图形 + 名字：登录页不宣传产品能力，此刻用户要做的只有一件事 -->
    <view class="login__brand">
      <view class="login__mark">
        <FaIcon name="robot" tone="accent" size="64rpx" />
      </view>
      <text class="login__title">FitAgent</text>
    </view>

    <StateBlock
      :state="state"
      :failure="failure"
      :submit-failure="submitFailure"
      :loading-text="loadingText"
      empty-title="准备就绪"
      @retry="probeSession"
    >
      <view class="login__panel">
        <view v-if="sessionFound" class="fa-banner fa-banner--info">
          <text class="fa-prose">检测到有效登录状态，正在进入…</text>
        </view>

        <!-- 状态 5：登录失效（启动探活失败时保留一次说明，不静默跳转） -->
        <view v-if="expiredNotice" class="fa-banner fa-banner--warn">
          <text class="fa-prose">{{ expiredNotice }}</text>
        </view>

        <!-- 主操作唯一。用 view 而非 button：:class 的禁用态只改透明度，
             重复点击靠 loginWithWechat 里的 submitting 守卫挡住（与改前行为一致，uni.login 流程未动） -->
        <view
          class="fa-btn fa-btn--primary fa-btn--block"
          :class="{ 'fa-btn--disabled': submitting }"
          @tap="loginWithWechat"
        >
          <FaIcon name="user" tone="text" size="44rpx" />
          <text>{{ submitting ? '登录中…' : '微信登录' }}</text>
        </view>

        <text class="fa-hint">首次登录自动建号，重复登录复用同一账号；本轮没有独立注册入口。</text>

        <!-- 开发登录：仅开发构建显示。后端在 prod 不注册该路由（docs/p1-api.md §3.2、验收 A19） -->
        <view v-if="devBuild" class="login__dev">
          <view class="login__dev-head">
            <FaIcon name="key" tone="accent" size="40rpx" />
            <text class="login__dev-title">开发登录</text>
            <text class="fa-hint">不经微信核验身份</text>
          </view>
          <view class="fa-btn-row">
            <view
              class="fa-btn fa-btn--ghost"
              :class="{ 'fa-btn--disabled': submitting }"
              @tap="loginWithDev('dev-a')"
            >
              <FaIcon name="user" tone="accent" size="40rpx" />
              <text>用户A</text>
            </view>
            <view
              class="fa-btn fa-btn--ghost"
              :class="{ 'fa-btn--disabled': submitting }"
              @tap="loginWithDev('dev-b')"
            >
              <FaIcon name="user" tone="accent" size="40rpx" />
              <text>用户B</text>
            </view>
          </view>
          <!-- 两个测试用户的用途是长说明，收进折叠区；入口本身一屏可点 -->
          <view class="fa-btn fa-btn--sm login__dev-toggle" @tap="showDevNote = !showDevNote">
            <FaIcon :name="showDevNote ? 'chevron' : 'alert'" tone="accent" size="36rpx" />
            <text>{{ showDevNote ? '收起' : '说明' }}</text>
          </view>
          <text v-if="showDevNote" class="fa-hint">
            两个用户的数据完全隔离，用于验证越权访问。开发登录的联调结论不计入真实微信登录验收。
          </text>
        </view>
      </view>
    </StateBlock>
  </view>
</template>

<script setup lang="ts">
/**
 * 登录页（docs/p1-scope.md §3.1）。
 *
 * 身份来自微信 code，由后端向 code2Session 核验；页面**不传** userId/openid，
 * 即使传了后端也会因未知字段被拒（docs/p1-api.md §3.1、验收 A18）。
 *
 * 令牌写入统一走 stores/session，本地存储键由 utils/request.ts 与 utils/storage.ts 持有。
 *
 * UI 重排：主操作只有「微信登录」一个；开发登录入口原样保留（仍由 devBuild 控制、走的还是
 * `POST /api/auth/dev-login`），只把长标签换成图标 + 两字标签，用途说明折叠。
 * 探活失败的说明横幅与 StateBlock 的错误横幅（错误码 / traceId）一条都没删。
 */
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { devLogin, fetchMe, wechatLogin } from '@/api/auth'
import FaIcon from '@/components/FaIcon.vue'
import StateBlock from '@/components/StateBlock.vue'
import { useSessionStore } from '@/stores/session'
import type { DevUserId, LoginResult } from '@/types/auth'
import type { PageState } from '@/types/api'
import { briefError, toFailureView, type FailureView } from '@/utils/errorMessage'
import { ApiError } from '@/utils/request'
import { takeSessionExpiredNotice } from '@/utils/storage'

const session = useSessionStore()

const state = ref<PageState>('loading')
const loadingText = ref('检查登录状态…')
const failure = ref<FailureView | null>(null)
const submitFailure = ref<FailureView | null>(null)
const expiredNotice = ref('')
const sessionFound = ref(false)
const submitting = ref(false)
/** 纯展示开关：开发登录的用途说明默认折叠，入口本身保持一屏可点（不影响 devBuild 判定与登录流程） */
const showDevNote = ref(false)

/** 开发构建才显示开发登录入口；正式构建即使命令也拿不到该路由 */
const devBuild = import.meta.env.DEV === true

onShow(() => {
  probeSession()
})

/** 本地已有令牌时先探活，避免让已登录用户重复点登录。 */
async function probeSession(): Promise<void> {
  session.restore()
  failure.value = null
  sessionFound.value = false
  // 请求封装层遇到 401 会清令牌并 reLaunch 到本页，把原因存在本地：
  // 不在这里取出来，用户就是"莫名其妙被送回登录页"，什么解释都看不到。
  const storedNotice = takeSessionExpiredNotice()
  if (storedNotice) expiredNotice.value = storedNotice
  if (!session.hasToken) {
    state.value = 'success'
    return
  }
  state.value = 'loading'
  loadingText.value = '检查登录状态…'
  try {
    // silentSessionRedirect：本页就是登录页，不能再被封装层 reLaunch 回本页
    const me = await fetchMe({ silentSessionRedirect: true })
    session.userId = me.userId
    session.nickname = me.nickname
    sessionFound.value = true
    state.value = 'success'
    uni.reLaunch({ url: '/pages/index/home' })
  } catch (err) {
    const view = toFailureView(err)
    if (err instanceof ApiError && (err.code === 'UNAUTHENTICATED' || err.code === 'SESSION_EXPIRED')) {
      // 状态 5：令牌已失效。清干净并留在本页，明确告知原因
      session.clear()
      if (!expiredNotice.value) {
        expiredNotice.value = err.code === 'SESSION_EXPIRED' ? '登录已过期，请重新登录。' : '登录状态已清除，请重新登录。'
      }
      state.value = 'success'
      return
    }
    failure.value = view
    state.value = 'error'
  }
}

async function loginWithWechat(): Promise<void> {
  if (submitting.value) return
  submitFailure.value = null
  submitting.value = true
  try {
    const code = await wxLogin()
    if (!code) {
      submitFailure.value = {
        title: '未获取到微信登录凭证',
        detail: '请在微信中重试；若使用开发者工具，请确认已登录微信账号。',
        code: 'WECHAT_CODE_MISSING',
        traceId: '',
        fields: [],
        importErrors: [],
        importWarnings: [],
        sessionExpired: false,
        versionConflict: false,
        invalidTransition: false,
        activePlanExists: false,
        retryAfter: null
      }
      state.value = 'success'
      return
    }
    await finishLogin(await wechatLogin({ code }), false)
  } catch (err) {
    submitFailure.value = toFailureView(err)
    state.value = 'success'
    uni.showToast({ title: briefError(err), icon: 'none' })
  } finally {
    submitting.value = false
  }
}

async function loginWithDev(devUserId: DevUserId): Promise<void> {
  if (submitting.value) return
  submitFailure.value = null
  submitting.value = true
  try {
    await finishLogin(await devLogin({ devUserId }), true)
  } catch (err) {
    submitFailure.value = toFailureView(err)
    state.value = 'success'
    uni.showToast({ title: briefError(err), icon: 'none' })
  } finally {
    submitting.value = false
  }
}

function wxLogin(): Promise<string | undefined> {
  return new Promise((resolve) => {
    uni.login({
      provider: 'weixin',
      success: (res) => resolve(res.code),
      // 失败必须成为可见的提交失败态，不能假装登录成功
      fail: () => resolve(undefined)
    })
  })
}

async function finishLogin(result: LoginResult, isDev: boolean): Promise<void> {
  session.applyLogin(result)
  expiredNotice.value = ''
  uni.showToast({
    title: result.isNewAccount ? '已为你创建账号' : '欢迎回来',
    icon: 'none'
  })
  if (isDev) {
    uni.showToast({ title: '当前为开发登录会话', icon: 'none' })
  }
  uni.reLaunch({ url: '/pages/index/home' })
}
</script>

<style lang="scss" scoped>
.login {
  padding-top: 96rpx;
}

/* 品牌区：图形承载"这是谁"，文字只留产品名 */
.login__brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: $fa-space-tight;
  margin-bottom: 72rpx;
}

.login__mark {
  width: 168rpx;
  height: 168rpx;
  border-radius: 50%;
  background-color: $fa-highlight;
  border: 1px solid $fa-control-border;
  display: flex;
  align-items: center;
  justify-content: center;
}

.login__title {
  display: block;
  font-size: $fa-font-title;
  font-weight: 600;
  color: $fa-text;
}

/* 登录动作区：不再叠左右内边距（页面的 $fa-space-page 已经管了留白），
   只在大屏上把主操作收成一列，避免按钮被拉成一条横杠（高度仍是 $fa-btn-h 的 44px） */
.login__panel {
  max-width: 640rpx;
  margin: 0 auto;
}

.login__dev {
  margin-top: $fa-space-section;
  padding: $fa-space-card;
  border: 1px solid $fa-line;
  border-radius: $fa-radius;
  background-color: $fa-page-bg;
}

.login__dev-head {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-bottom: $fa-space-tight;
}

.login__dev-title {
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  color: $fa-text;
}

.login__dev-toggle {
  margin-top: $fa-space-tight;
  width: 200rpx;
}
</style>
