<template>
  <view class="fa-page">
    <!-- 头像区：昵称 + 一行身份摘要，其余账户字段收进下面的「账户详情」 -->
    <view class="mine-hero">
      <view class="mine-hero__avatar">
        <FaIcon name="user" tone="accent" size="56rpx" />
      </view>
      <view class="mine-hero__text">
        <text class="mine-hero__name">{{ displayName }}</text>
        <text class="mine-hero__meta">{{ heroMeta }}</text>
      </view>
      <text v-if="me && me.devMode" class="fa-chip fa-chip--warning mine-hero__chip">开发</text>
    </view>

    <StateBlock
      :state="state"
      :failure="failure"
      :session-expired-title="sessionExpiredTitle"
      loading-text="正在读取账户信息…"
      empty-title="未登录"
      empty-desc="登录后这里会显示账户资料。"
      @retry="load"
    >
      <template #action>
        <view class="fa-btn fa-btn--primary" @tap="goLogin">去登录</view>
      </template>

      <view class="fa-card">
        <view class="fa-btn fa-btn--sm mine-toggle" @tap="showAccount = !showAccount">
          <FaIcon :name="showAccount ? 'chevron' : 'records'" tone="accent" size="36rpx" />
          <text>{{ showAccount ? '收起' : '账户详情' }}</text>
        </view>

        <view v-if="showAccount">
          <view class="fa-row">
            <text class="fa-row__label">用户 ID</text>
            <text class="fa-row__value">{{ me ? me.userId : '—' }}</text>
          </view>
          <view class="fa-row">
            <text class="fa-row__label">角色 / 状态</text>
            <text class="fa-row__value">{{ me ? `${me.role} · ${statusText(me.status)}` : '—' }}</text>
          </view>
          <view class="fa-row">
            <text class="fa-row__label">业务时区</text>
            <text class="fa-row__value">{{ me ? me.timezone : '—' }}</text>
          </view>
          <view class="fa-row">
            <text class="fa-row__label">数据修订号</text>
            <text class="fa-row__value">{{ me ? me.dataRevision : '—' }}</text>
          </view>
          <view class="fa-row">
            <text class="fa-row__label">最近登录</text>
            <text class="fa-row__value">{{ me ? instantToDisplay(me.lastLoginAt) : '—' }}</text>
          </view>
          <view class="fa-row">
            <text class="fa-row__label">注册时间</text>
            <text class="fa-row__value">{{ me ? instantToDisplay(me.createdAt) : '—' }}</text>
          </view>
          <text class="fa-hint">数据修订号由服务端在档案/目标/体重/计划变更后递增，本页只回显，不本地推算。</text>
        </view>
      </view>
    </StateBlock>

    <!-- 入口区不依赖 /api/me：账户信息读取失败时也要能进档案、体重、设置 -->
    <view class="fa-section-title">我的数据</view>
    <view class="fa-tiles">
      <FaIconTile v-for="tile in dataTiles" :key="tile.url" :icon="tile.icon" :label="tile.label" @tap="goto(tile.url)" />
    </view>

    <view class="fa-section-title">模型与记录</view>
    <view class="fa-tiles">
      <FaIconTile v-for="tile in aiTiles" :key="tile.url" :icon="tile.icon" :label="tile.label" @tap="goto(tile.url)" />
    </view>
    <text class="fa-hint">AI 教练用你自己配置的模型 Key（BYOK），费用从你的供应商账户扣除。</text>

    <!-- 退出登录：登录态存在就一直可用（即使读取账户失败也不能把用户困在页面里） -->
    <view v-if="showLogoutArea" class="fa-card mine-logout">
      <view v-if="notice" class="fa-banner fa-banner--warn">
        <text class="fa-prose">{{ notice }}</text>
      </view>
      <text class="fa-prose">退出会让服务端会话失效并清除本机令牌；服务端调用失败也不影响本机清理。</text>
      <view class="fa-btn-row mine-logout-row">
        <view class="fa-btn fa-btn--danger" :class="{ 'fa-btn--disabled': loggingOut }" @tap="askLogout">
          {{ loggingOut ? '正在退出…' : '退出' }}
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 「我的」页（docs/p1-scope.md §4 页面清单：账户信息 + 档案/目标/体重入口 + 退出登录）。
 *
 * 接口只有两个：`GET /api/me`（§3.4 探活与账户资料）与 `POST /api/auth/logout`（§3.3 幂等退出）。
 * 本页不缓存子页数据（架构 §2：不把数据库镜像进 store），每次 onShow 重新读取。
 *
 * 五态：加载中 / 无登录状态（空）/ 读取失败（含服务端 message、code、traceId）/
 * 成功（真实账户资料）/ 登录失效（令牌由封装层清除并跳登录，这里保留一次提示）。
 * UI 重排后：账户字段默认折叠，图标入口移出 StateBlock——账户读不到时用户仍然要走。
 */
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import StateBlock from '@/components/StateBlock.vue'
import FaIcon from '@/components/FaIcon.vue'
import FaIconTile from '@/components/FaIconTile.vue'
import { fetchMe, logout } from '@/api/auth'
import { useSessionStore } from '@/stores/session'
import type { FaIconName } from '@/styles/icon-names'
import type { MeResult } from '@/types/auth'
import type { PageState } from '@/types/api'
import { isSessionExpiredError } from '@/utils/request'
import { briefError, toFailureView, type FailureView } from '@/utils/errorMessage'
import { instantToDisplay } from '@/utils/date'

const LOGIN_PAGE = '/pages/auth/login'

const session = useSessionStore()

const me = ref<MeResult | null>(null)
const state = ref<PageState>('loading')
const failure = ref<FailureView | null>(null)
const notice = ref('')
const loggingOut = ref(false)
const sessionExpiredTitle = ref('登录已失效，请重新登录')
const showAccount = ref(false)

/** 图标入口：一个图标一个落点；字段长说明归各页自己的表单 hint。 */
interface MineTile {
  url: string
  label: string
  icon: FaIconName
}

const dataTiles: MineTile[] = [
  { url: '/pages/mine/profile', label: '档案', icon: 'user' },
  { url: '/pages/mine/goal', label: '目标', icon: 'target' },
  { url: '/pages/mine/body/records', label: '体重', icon: 'weight' }
]

const aiTiles: MineTile[] = [
  { url: '/pages/mine/model-settings', label: '设置', icon: 'settings' },
  { url: '/pages/coach/history', label: '记录', icon: 'records' }
]

/** 教练已升为 tabBar 页：tab 页必须走 switchTab，navigateTo 打不开。 */
const TAB_PAGES = ['/pages/index/home', '/pages/plan/list', '/pages/coach/chat', '/pages/mine/index']

const STATUS_TEXT: Record<string, string> = { ACTIVE: '正常', DISABLED: '已停用' }

function statusText(value: string): string {
  return STATUS_TEXT[value] ?? value
}

const displayName = computed(() => {
  const nickname = me.value?.nickname
  return nickname && nickname.length > 0 ? nickname : '未设置昵称'
})

const heroMeta = computed(() => {
  if (!me.value) return state.value === 'loading' ? '正在读取…' : '登录后显示'
  return `${me.value.timezone} · ${statusText(me.value.status)}`
})

/** 有令牌就显示退出区块；加载中不显示，避免点到一个还没读完的页面。 */
const showLogoutArea = computed(() => session.hasToken && state.value !== 'loading')

async function load(): Promise<void> {
  if (!session.hasToken) {
    // 本机没有令牌：这是空状态而不是错误，不去打一个注定 401 的请求
    me.value = null
    state.value = 'empty'
    return
  }
  state.value = 'loading'
  failure.value = null
  try {
    me.value = await fetchMe()
    state.value = 'success'
  } catch (err) {
    if (isSessionExpiredError(err)) {
      // 封装层已清令牌并 reLaunch；页面仍要渲染第 5 态，不吞掉
      me.value = null
      session.clear()
      sessionExpiredTitle.value = '登录已失效，请重新登录'
      failure.value = toFailureView(err)
      state.value = 'sessionExpired'
      return
    }
    failure.value = toFailureView(err)
    if (failure.value.code === 'ACCOUNT_DISABLED') {
      notice.value = '账户已被停用（ACCOUNT_DISABLED）：服务端会话不可继续，请退出登录后联系维护者。'
    }
    me.value = null
    state.value = 'error'
  }
}

function goto(url: string): void {
  if (TAB_PAGES.includes(url)) {
    uni.switchTab({ url })
    return
  }
  uni.navigateTo({
    url,
    fail: (err) => {
      uni.showModal({
        title: '打开失败',
        content: `无法打开 ${url}：${err?.errMsg ?? '未知原因'}`,
        showCancel: false
      })
    }
  })
}

function goLogin(): void {
  uni.reLaunch({ url: LOGIN_PAGE })
}

function askLogout(): void {
  if (loggingOut.value) return
  uni.showModal({
    title: '退出登录',
    content: '将请求服务端注销当前会话，并清除本机登录状态。',
    confirmText: '退出',
    cancelText: '取消',
    success: (res) => {
      if (res.confirm) void doLogout()
    }
  })
}

/**
 * 契约 §3.3：`POST /api/auth/logout` 幂等，**前端无论成败都清本地令牌并 reLaunch 登录页**。
 * 服务端失败只是把原因写进提示，不改变本机清理——不能把用户困在"退出不了"的状态里。
 */
async function doLogout(): Promise<void> {
  if (loggingOut.value) return
  loggingOut.value = true
  notice.value = ''
  let serverFailureText = ''
  try {
    await logout()
  } catch (err) {
    if (isSessionExpiredError(err)) {
      sessionExpiredTitle.value = '登录已失效，请重新登录'
    } else {
      serverFailureText = briefError(err)
    }
  } finally {
    session.clear()
    me.value = null
    loggingOut.value = false
  }
  if (serverFailureText.length > 0) {
    notice.value = `服务端退出请求失败：${serverFailureText}。本机登录状态已清除，可直接重新登录。`
    uni.showModal({
      title: '本机已退出登录',
      content: '服务端未确认注销（会话可能仍在那个令牌上有效，但它已不在本机）。现在回到登录页。',
      showCancel: false,
      confirmText: '去登录',
      complete: () => {
        uni.reLaunch({ url: LOGIN_PAGE })
      }
    })
    return
  }
  uni.reLaunch({ url: LOGIN_PAGE })
}

onShow(() => {
  if (loggingOut.value) return
  void load()
})
</script>

<style lang="scss" scoped>
.mine-hero {
  display: flex;
  align-items: center;
  gap: $fa-space-tight;
  margin-bottom: $fa-space-section;
}

.mine-hero__avatar {
  width: 96rpx;
  height: 96rpx;
  border-radius: 50%;
  background-color: $fa-highlight;
  border: 1px solid $fa-control-border;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
}

.mine-hero__text {
  flex: 1;
  min-width: 0;
}

.mine-hero__name {
  display: block;
  font-size: $fa-font-title;
  font-weight: 600;
  color: $fa-text;
}

.mine-hero__meta {
  display: block;
  font-size: $fa-font-ui;
  color: $fa-text-sub;
}

.mine-hero__chip {
  margin: 0;
  flex: 0 0 auto;
}

.mine-toggle {
  width: 200rpx;
}

.mine-logout {
  margin-top: $fa-space-section;
}

.mine-logout-row {
  margin-top: $fa-space-tight;
}
</style>
