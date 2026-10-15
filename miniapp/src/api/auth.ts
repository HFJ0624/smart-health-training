/**
 * auth 接口封装（docs/p1-api.md §3）。一个函数对应一个后端接口，签名用 `types/*`，
 * 不做 UI 提示、不存状态（docs/p1-architecture.md §2）。
 */

import { get, post } from '@/utils/request'
import type { DevLoginRequest, LoginResult, MeResult, WechatLoginRequest } from '@/types/auth'

/** `POST /api/auth/wechat-login`：请求体只有 `code`（§3.1）。 */
export function wechatLogin(payload: WechatLoginRequest): Promise<LoginResult> {
  return post<LoginResult>('/auth/wechat-login', payload, { withAuth: false })
}

/** `POST /api/auth/dev-login`：仅 dev profile 注册该路由，只接受 dev-a | dev-b（§3.2）。 */
export function devLogin(payload: DevLoginRequest): Promise<LoginResult> {
  return post<LoginResult>('/auth/dev-login', payload, { withAuth: false })
}

/** `POST /api/auth/logout`：幂等，无令牌或已失效也返回 200（§3.3）。 */
export function logout(): Promise<null> {
  return post<null>('/auth/logout')
}

/** `fetchMe` 的可选行为。 */
export interface FetchMeOptions {
  /**
   * 会话失效时**只清令牌 + 留下「登录已失效」提示，不再 `reLaunch`**。
   * 登录页自己的启动探活必须传 true：`request.ts` 的 401 处理会把用户 `reLaunch` 到登录页，
   * 而此刻页面已经在登录页，不关掉跳转就会出现「登录页 → 登录页」的连环重启。
   * 令牌清理与「保留一次提示」照常执行（不绕过集中的会话失效处理，只是不重复跳转）。
   */
  silentSessionRedirect?: boolean
}

/**
 * `GET /api/me`：启动时探活（§3.4）。
 * 401 时封装层一定抛 `ApiError`（UNAUTHENTICATED / SESSION_EXPIRED），调用方必须接住并渲染
 * 「登录失效」态——把异常吞掉就等于伪造成功（docs/p1-scope.md §4 五态）。
 */
export function fetchMe(options: FetchMeOptions = {}): Promise<MeResult> {
  return get<MeResult>('/me', undefined, { silentSessionRedirect: options.silentSessionRedirect === true })
}
