/**
 * 模型设置（BYOK）接口封装：DeepSeek 凭据、供应商余额快照、本应用预算。
 * 一个函数对应一个后端接口，不做 UI 提示、不存状态（docs/p1-architecture.md §2）。
 *
 * 路径依据 `docs/agent-v1-design.md` §11 的接口清单：
 *   GET/PUT/DELETE `/api/model-credentials/deepseek`、
 *   GET `/api/model-credentials/deepseek/balance`、
 *   GET/PUT `/api/budgets/deepseek`。
 * 全部由服务端按认证上下文限定到当前用户，因此这里**不传任何 userId / 余额 / 费用 / 模型能力**
 * （§11 约束：客户端传来的这些值都不是可信值）。
 *
 * 路径里的 `deepseek` 是**固定字面量**（本轮只有一个供应商）：`providerCode` 由服务端匹配
 * `model_provider` 白名单得到，客户端不提交、也不改写任何外呼地址（V10 迁移注释：
 * 「由输入决定外呼地址」等于把服务端变成 SSRF 跳板）。
 *
 * 幂等：本模块**不**自带 `Idempotency-Key` 头（docs/p1-api.md §1 把这个头限定给计划导入确认）。
 * 凭据写入的天然保护是服务端的指纹 + credential_version（同 Key 重放不产生新版本），
 * 撤销的天然保护是 status=REVOKED；预算写入靠 lockVersion。
 * 若后端最终要求该头，`RequestOptions.header` 已支持，在这里补一行即可（见下方 TODO 注释）。
 */

import { del, get, put } from '@/utils/request'
import type {
  BudgetPolicy,
  BudgetUpdateRequest,
  ModelCredentialSaveRequest,
  ModelCredentialSummary,
  ProviderBalance
} from '@/types/model'

const PROVIDER_SEGMENT = 'deepseek'
const CREDENTIAL_PATH = `/model-credentials/${PROVIDER_SEGMENT}`
const BALANCE_PATH = `${CREDENTIAL_PATH}/balance`
const BUDGET_PATH = `/budgets/${PROVIDER_SEGMENT}`

/**
 * `GET /api/model-credentials/deepseek`：凭据摘要。
 * **从未保存过 Key 时 `data: null`（200，不是 404）**——与 `GET /api/me/profile` 同一口径，
 * 属于正常空状态。响应在任何情况下都不含 Key、密文、nonce 或指纹（design §6.1）。
 */
export function fetchModelCredential(): Promise<ModelCredentialSummary | null> {
  return get<ModelCredentialSummary | null>(CREDENTIAL_PATH)
}

/**
 * `PUT /api/model-credentials/deepseek`：保存或替换凭据，返回脱敏摘要。
 * 「同一把 Key 重复提交不产生新 credential_version」是**服务端**用 HMAC 指纹比对得出的结论
 * （design §6.1：指纹与加密密钥分开管理），页面只回显返回的 credentialVersion，不本地比较密钥内容。
 * 请求体只有 `{ apiKey, label }` 两个键：多一个键即 422 `UNKNOWN_FIELD`。
 * 后端定稿结论（docs/agent-v1-design.md §11）：模型设置接口**不要求** `Idempotency-Key`，
 * 凭据靠指纹 + credential_version、预算靠 lockVersion 收敛，因此这里不加该头。
 */
export function saveModelCredential(payload: ModelCredentialSaveRequest): Promise<ModelCredentialSummary> {
  return put<ModelCredentialSummary>(CREDENTIAL_PATH, payload)
}

/**
 * `DELETE /api/model-credentials/deepseek`：撤销凭据（status=REVOKED，密文与 nonce 被清除）。
 * 撤销只阻止**新**调用发起；已经发出的请求无法追回、供应商侧已扣的费用不退（design §6.1）。
 * 返回体形状未在契约里定稿：按「摘要或 null」接收，页面一律重读服务端为准。
 */
export function revokeModelCredential(): Promise<ModelCredentialSummary | null> {
  return del<ModelCredentialSummary | null>(CREDENTIAL_PATH)
}

/**
 * `GET /api/model-credentials/deepseek/balance`：供应商账户余额快照。
 * 需要有效凭据；查询失败时后端**不清零**，而是回落到上次成功快照并置 `stale`，
 * 首次就失败则 `data: null`（protocol §8）。金额字段是字符串，页面不参与算术。
 */
export function fetchProviderBalance(): Promise<ProviderBalance | null> {
  return get<ProviderBalance | null>(BALANCE_PATH)
}

/**
 * `GET /api/budgets/deepseek`：本应用预算策略。
 * 未设置过预算时 `data: null`（同 profile 口径），这是空状态不是错误。
 */
export function fetchBudget(): Promise<BudgetPolicy | null> {
  return get<BudgetPolicy | null>(BUDGET_PATH)
}

/**
 * `PUT /api/budgets/deepseek`：保存预算（§11 只有 GET/PUT，PUT 兼作首次创建）。
 * 限额留空 = 不设限（提交 null，不是 0）；币种只有一份，页面不做任何汇率换算。
 * 带 `lockVersion` 乐观锁，不符 409 `VERSION_CONFLICT`，此时本次提交没有写入服务端。
 */
export function updateBudget(payload: BudgetUpdateRequest): Promise<BudgetPolicy> {
  return put<BudgetPolicy>(BUDGET_PATH, payload)
}
