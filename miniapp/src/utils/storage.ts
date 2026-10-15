/**
 * 本地存储键与令牌读写（docs/p1-api.md §9：令牌从 `uni.getStorageSync('fa_token')` 读）。
 * 放在 utils 里，避免请求封装层依赖 store 造成循环引用。
 */

export const TOKEN_STORAGE_KEY = 'fa_token'
/** 会话失效提示：401 之后要在登录页「保留一次提示」，reLaunch 后仍能读到（docs/p1-scope.md §4 五态之一）。 */
export const SESSION_EXPIRED_NOTICE_KEY = 'fa_session_expired_notice'

export function getToken(): string {
  const raw = uni.getStorageSync(TOKEN_STORAGE_KEY)
  return typeof raw === 'string' ? raw : ''
}

export function setToken(token: string): void {
  uni.setStorageSync(TOKEN_STORAGE_KEY, token)
}

export function clearToken(): void {
  uni.removeStorageSync(TOKEN_STORAGE_KEY)
}

/** 记录「会话已失效」，供登录页展示一次后清除。 */
export function markSessionExpired(message: string): void {
  uni.setStorageSync(SESSION_EXPIRED_NOTICE_KEY, message)
}

/** 取出并清除会话失效提示（只消费一次）。 */
export function takeSessionExpiredNotice(): string {
  const raw = uni.getStorageSync(SESSION_EXPIRED_NOTICE_KEY)
  if (typeof raw === 'string' && raw.length > 0) {
    uni.removeStorageSync(SESSION_EXPIRED_NOTICE_KEY)
    return raw
  }
  return ''
}
