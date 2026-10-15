/**
 * 会话 store：**只放登录态**（令牌、userId、devMode、有效期）。
 * 不把数据库镜像进 store——体重历史、计划详情、档案都由各页面按需调接口获取
 * （docs/p1-architecture.md §2 表格 `stores` 行）。
 */

import { defineStore } from 'pinia'
import type { LoginResult } from '@/types/auth'
import { clearToken, getToken, setToken } from '@/utils/storage'

interface SessionState {
  token: string
  userId: string
  /** 后端 dev-login 是否可用（docs/p1-api.md §3.2）。false 时登录页不显示「开发登录模式」入口 */
  devMode: boolean
  /** ISO-8601 UTC，来自登录响应 */
  expiresAt: string
  nickname: string | null
}

export const useSessionStore = defineStore('session', {
  state: (): SessionState => ({
    token: '',
    userId: '',
    devMode: false,
    expiresAt: '',
    nickname: null
  }),

  getters: {
    /** 内存或本地存储里有令牌 */
    hasToken: (state): boolean => state.token.length > 0,
    /** 令牌存在且未过期（过期判定只是少发一次请求，最终仍以服务端 401 为准） */
    isSignedIn: (state): boolean => {
      if (!state.token) return false
      if (!state.expiresAt) return true
      const ts = Date.parse(state.expiresAt)
      return Number.isNaN(ts) ? true : ts > Date.now()
    }
  },

  actions: {
    /** App onLaunch 调用：把本地令牌读回内存（docs/p1-scope.md §3.1）。 */
    restore(): void {
      this.token = getToken()
    },

    /** 登录成功后写入令牌与身份（令牌同时落本地存储，供请求封装读取）。 */
    applyLogin(res: LoginResult): void {
      this.token = res.token
      this.userId = res.userId
      this.devMode = res.devMode
      this.expiresAt = res.expiresAt
      this.nickname = res.nickname
      setToken(res.token)
    },

    /** 退出登录 / 会话失效：清内存 + 清存储。 */
    clear(): void {
      this.token = ''
      this.userId = ''
      this.devMode = false
      this.expiresAt = ''
      this.nickname = null
      clearToken()
    }
  }
})
