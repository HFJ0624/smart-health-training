/**
 * auth 模块类型（docs/p1-api.md §3）。
 * 所有 id 均为字符串：后端用 Jackson `ToStringSerializer` 传输 64 位整型（§1「BigInt」）。
 */

/** `POST /api/auth/wechat-login` 请求体：只有 `code`。带 userId/openid 等键一律 `UNKNOWN_FIELD`（§3.1、验收 A18）。 */
export interface WechatLoginRequest {
  code: string
}

/** `POST /api/auth/dev-login` 请求体：只接受 dev-a | dev-b（§3.2）。 */
export interface DevLoginRequest {
  devUserId: DevUserId
}

export type DevUserId = 'dev-a' | 'dev-b'

/** 3.1 与 3.2 的返回同形（3.2 的 `devMode` 恒为 true）。 */
export interface LoginResult {
  /** SecureRandom 32 字节 → base64url，不落数据库 */
  token: string
  tokenType: 'Bearer'
  /** ISO-8601 UTC 瞬时值 */
  expiresAt: string
  userId: string
  nickname: string | null
  /** 首次登录自动注册为 true；重复登录复用同一账号为 false（§1.1(a)） */
  isNewAccount: boolean
  /** 后端 dev-login 是否可用；false 时登录页隐藏「开发登录模式」入口 */
  devMode: boolean
  profileRequired: boolean
}

/** `GET /api/me`（§3.4）：启动时探活 + 登录态判定。 */
export interface MeResult {
  userId: string
  nickname: string | null
  role: 'USER' | 'ADMIN'
  /** 非 ACTIVE 时后端返回 403 `ACCOUNT_DISABLED`（§2） */
  status: string
  timezone: string
  /** 字符串型 64 位整型 */
  dataRevision: string
  lastLoginAt: string | null
  createdAt: string
  devMode: boolean
}
