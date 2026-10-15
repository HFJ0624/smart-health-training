# FitAgent 自研 Agent 第一版：DeepSeek 协议核查

核查日期：**2026-10-13**。来源：`api-docs.deepseek.com` 官方文档（EN 与 zh-CN 内容一致）。
本文档是实现 `modules/modelaccess` 与 `agent/model/deepseek` 的**唯一取值依据**。凡本文标注"未能核实"的，代码里不得写成事实，只能做成配置或明确拒绝。

## 0. 两条会直接改变实现的前提

1. **旧设计包里的模型名是对的，被作废的反而是一度流行的名字。** `deepseek-chat` 与 `deepseek-reasoner` 已于 **2026-07-24 停用**（[updates](https://api-docs.deepseek.com/updates)）。当前 `model` 枚举只有 `deepseek-flash` 与 `deepseek-v4-pro`。
2. **峰谷分时计价是官方文档里真实存在的机制**，不是旧设计的臆造。因此 `model_price` 必须带档位；预占一律按 **PEAK** 保守档。这条更正了 `docs/agent-v1-design.md` §6.2 原先"不含 rate_tier"的判断。

## 1. 模型与能力（[pricing](https://api-docs.deepseek.com/quick_start/pricing)、[list-models](https://api-docs.deepseek.com/api/list-models)、[vision](https://api-docs.deepseek.com/guides/vision)）

| model | 实际模型版本 | Tool Calls | JSON Output | Vision |
|---|---|---|---|---|
| `deepseek-flash` | DeepSeek-V4.1-Flash | ✅ | ✅ | ✅（每图上限 1024 tokens） |
| `deepseek-v4-pro` | DeepSeek-V4-Pro-0813 | ✅ | ✅ | ❌ 官方标注 Not supported |

上下文与输出：`context_window = 1048576`（1M），`max_output_tokens = 393216`（384K）。默认输出上限：非思考 8K、思考 64K、`reasoning_effort=max` 时 128K。
`deepseek-v4-flash`、`deepseek-v4-flash-vision-exp` 属"仍接受但由 V4.1-Flash 服务并按 Flash 计价"的别名。

> ⚠️ **未解决的官方自相矛盾**：news260910 称自 2026-09-14 04:00 UTC 起所有 `deepseek-v4-pro` 请求改由 V4.1-Flash 服务并按 Flash 费率计费"直至 V4.1-Pro 上线"；而 2026-09-10 的 changelog 称 V4 Pro 计费不变。两处说法冲突，**没有 Key 无法实测**。
> 处置：`model_price` 里 `deepseek-v4-pro` 的 PEAK/OFF_PEAK 报价按官方 pricing 页快照写入，但 `source_checked_on=2026-10-13` 且 `enabled` **默认 false**；启用前必须由用户自行核对当期计费。预占与核算都从表里取价，代码内不写死任何数字。

## 2. Base URL 与路径（[home](https://api-docs.deepseek.com/)、[create-chat-completion](https://api-docs.deepseek.com/api/create-chat-completion)）

```
Base URL: https://api.deepseek.com        （OpenAI 兼容格式）
文档中的路径不带 /v1：
  POST /chat/completions
  GET  /models
  GET  /user/balance
另有 https://api.deepseek.com/anthropic 与 https://api.deepseek.com/beta（Beta 特性专用）
```

**未能核实**：`/v1/*` 是否真的可用。无凭据探测时 `/chat/completions`、`/v1/chat/completions`、`/models`、`/v1/models`、`/user/balance` **全部返回同一个 `401 Authentication Fails`**，说明鉴权先于路由，401 证明不了前缀是否存在。官方文档从未出现 `/v1`。**实现按文档路径走，并把完整 URL 做成配置项**（`fitagent.modelaccess.providers.deepseek.base-url` + 固定拼接），不做两种猜测。

## 3. 工具调用协议（[tool_calls](https://api-docs.deepseek.com/guides/tool_calls)）

请求侧 `tools[]`（OpenAI 兼容）：

```json
{ "type": "function",
  "function": { "name": "get_training_plan",
                "description": "…",
                "parameters": { …JSON Schema… } } }
```

- `type` 只有 `function` 一个取值。
- `name` 必填：`a-zA-Z0-9_-`，≤128 字符，**同一次请求内必须唯一** → 与 `ToolRegistry` 的"重名即启动失败"一致。
- `parameters` 省略等价于空列表。
- `strict` 默认 false，且属 **Beta**：需把 base_url 换成 `/beta`，服务端会校验 Schema，要求每个 object 的全部属性 `required` 且 `additionalProperties:false`，**不支持** `minLength/maxLength/minItems/maxItems`。
  → **本轮不使用 strict**（B6 也要求不依赖它）。服务端参数校验与权限校验照常保留。

`tool_choice`：字符串 `none | auto | required`，或对象 `{"type":"function","function":{"name":"…"}}`。默认：无 tools 时 `none`，有 tools 时 `auto`。
**`required` 与指定工具名在思考模式下不支持，会返回 400。** → 这正是本轮必须显式关闭思考模式的又一个理由（§5）。

响应侧：

```json
"choices[0].message": { "role": "assistant", "content": "…", "reasoning_content": "…",
  "tool_calls": [ { "id": "…", "type": "function",
                    "function": { "name": "…", "arguments": "{\"business_date\":\"2026-10-13\"}" } } ] }
```

- `arguments` 是 **JSON 字符串**，官方明确写"模型不保证生成合法 JSON，请在你的代码里校验" → 必须先解析再 Bean Validation，解析失败走有界修正或报错。
- 回传要求：把含 `tool_calls` 的 assistant 消息**原样**追加到上下文，再为每个 `tool_call_id` 追加 `{"role":"tool","tool_call_id":"…","content":"…"}`。`tool_call_id` 必填。
- 官方明确："Chat Completion API **不支持在会话中途插入 tool 调用**"（伪造工具结果需改用 Anthropic/Responses API）。→ 我们的 Runner 只在响应末尾追加真实结果，符合该约束。
- `parallel_tool_calls` **不是 Chat Completions 的文档参数**（只作为 Responses API 的输出字段出现）。→ **请求里不得发送该字段**。

## 4. finish_reason 与完整性（create-chat-completion）

官方枚举：`stop`、`length`、`content_filter`、`tool_calls`、`insufficient_system_resource`、`aborted`。

| 值 | 语义 | 本轮判定 |
|---|---|---|
| `stop` | 自然停止 | ✅ 可用回答 |
| `tool_calls` | 模型请求调用工具 | ✅ 进入工具执行 |
| `length` | 触达 `max_tokens` | ❌ 内容可能被截断；**不得执行 arguments**，不宣称成功 |
| `content_filter` | 内容被过滤 | ❌ 按不完整处理 |
| `insufficient_system_resource` | 推理侧资源不足被中断 | ❌ 不完整，不宣称成功 |
| `aborted` | 生成被中断 | ❌ 不完整，不宣称成功 |

只有 `stop` 与 `tool_calls` 算"可用"。其余一律映射到明确的停止/失败原因（`docs/agent-v1-design.md` §4 第 5–6 步）。
**未能核实**：`insufficient_system_resource` / `aborted` 是否已计费、能否安全重试 —— 文档只给语义。→ 一律按"已发送、结果未知"处理：保留不确定预占、不自动重发（B6.8）。

## 5. 思考模式（[thinking_mode](https://api-docs.deepseek.com/guides/thinking_mode)）—— 本轮的硬约束

- 请求参数 `{"thinking": {"type": "enabled" | "disabled"}}`，**默认是 enabled**。
- `reasoning_effort: none|low|high|max`（默认 high；`none` 等效关闭；`minimal→low`、`medium/xhigh→high`、`ultra→max`）。
- **带 `tools` 的请求必须把 `reasoning_content` 完整回传**，包括模型没有调用工具的那些轮次；不回传 → **API 返回 400**。原文："If your code does not correctly pass back `reasoning_content`, the API will return a 400 error."（不带 tools 时不需要回传，会被忽略。）
- 思考模式与工具调用**兼容**（自 DeepSeek-V3.2 起）。
- 思考模式下 `temperature` / `presence_penalty` / `frequency_penalty` 被忽略（静默），`top_p` 有效区间 0.95–1.0。

**本轮决定**（B6 + 本文档共同要求）：

1. 每次请求**显式**发送 `thinking.type = disabled`，绝不依赖供应商默认值 —— 默认 enabled 时不回传 `reasoning_content` 会让带工具的多轮请求当场 400。
2. 不实现 `reasoning_content` 回传，因此**配置层直接拒绝开启思考**：`fitagent.modelaccess.deepseek.thinking=enabled` 启动即失败并说明原因（B6："若不实现，配置校验直接拒绝开启"）。
3. 因此 `tool_choice: required` / 指定工具名也不可用（§3 的 400）→ Runner 只用 `auto` 与收尾用的 `none`，两者在关闭思考后都合法。
4. 若将来启用思考模式：必须实现 `reasoning_content` 全量回传并验证兼容性，且该字段只用于内部协议，**不得**出现在前端轨迹或普通应用日志。

## 6. usage 字段与计费口径（create-chat-completion、[kv_cache](https://api-docs.deepseek.com/guides/kv_cache)）

```
prompt_tokens                = prompt_cache_hit_tokens + prompt_cache_miss_tokens
completion_tokens
total_tokens
prompt_tokens_details.cached_tokens
completion_tokens_details.reasoning_tokens
```

- 缓存命中/未命中字段名：`prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`。
- `reasoning_tokens` 是 `completion_tokens` 的**细分项，不额外加钱**。证据是 create-response 示例的算术：`input_tokens 22 + output_tokens 29 = total_tokens 51`，而 `output_tokens_details.reasoning_tokens = 27` 已包含在 29 内。
- **未能核实**：pricing 页面**没有**任何关于 `reasoning_tokens` 计费口径的文字说明（细分结论来自上述算术与字段命名）；也没有"混合命中/未命中如何相加"的示例算式，页面只给费率。→ 核算按 `(hit×命中价 + miss×未命中价 + output×输出价)/1,000,000` 实现，并把它作为**可评审的项目假设**写进 `agent-v1-acceptance.md`，不宣称是供应商账单口径；官方结算记录才是最终依据（旧设计包 §7.3 同此立场）。
- 缺字段一律 null，**不填 0**（B10）。

## 7. 报价快照（[zh-cn/pricing](https://api-docs.deepseek.com/zh-cn/quick_start/pricing)，单位：元 / 1M tokens，核查于 2026-10-13）

| model | 档 | 输入(命中缓存) | 输入(未命中) | 输出 |
|---|---|---|---|---|
| `deepseek-flash` | OFF_PEAK | 0.02 | 1 | 4 |
| `deepseek-flash` | PEAK | 0.04 | 2 | 8 |
| `deepseek-v4-pro` | OFF_PEAK | 0.15 | 4.5 | 13.5 |
| `deepseek-v4-pro` | PEAK | 0.30 | 9.0 | 27.0 |

峰段：北京时间周一至周五 09:00–12:00、14:00–18:00，**不含中国法定节假日**；谷段为峰段的 50%。
扣费顺序：从赠送余额或充值余额扣，**赠送优先**。
页面**没有**"数据核查日期"标注，因此 `source_checked_on` 记本次核查日 2026-10-13。

**未能核实 / 需要人做的事**：
- 中国法定节假日日历本身没有官方机器可读来源被本文核实 → **节假日表不入库、不猜测**。
- 因此**预占一律按 PEAK 档**，与当前时刻是否真在峰段无关；结算时若档位可确定则按实际档位，档位不确定（跨段、节假日存疑）就标注"保守核算"，不称为供应商实际扣费。
- `deepseek-v4-pro` 的适用性受 §1 的官方矛盾影响，默认 `enabled=false`。

## 8. 余额接口（[get-user-balance](https://api-docs.deepseek.com/api/get-user-balance)）

```
GET https://api.deepseek.com/user/balance
Authorization: Bearer <key>      ← 文档站通用写法；余额页本身未单独列头
```

响应：

```json
{ "is_available": true,
  "balance_infos": [ { "currency": "CNY", "total_balance": "110.00",
                       "granted_balance": "4.00", "topped_up_balance": "106.00" } ] }
```

- `is_available`：余额是否足以继续调用（布尔）。
- 三个金额字段都是**字符串** → 绑定用 `BigDecimal`，不用 double。
- `currency` 取值 `CNY | USD`。
- 更正：`is_expired` **不存在于当前任何文档页**；`/user/limits` 与"余额接口被废弃/合并"的告示也**没有找到**。旧设计包里的 `is_expired` 不予实现。
- 语义提醒（旧设计包 §7.2 同）：这是**供应商账户级**余额，可能被本应用之外的调用影响，也不是单把 Key 独享的额度。查询失败时展示上次成功快照 + 过期标记，首次就失败则显示"暂无法获取"，**绝不显示 0**。

## 9. 错误码与限流（[error_codes](https://api-docs.deepseek.com/quick_start/error_codes)、[rate_limit](https://api-docs.deepseek.com/quick_start/rate_limit)）

| HTTP | 官方含义 | 本项目的映射与处置 |
|---|---|---|
| 400 | Invalid Format | 请求构造错误 → `PROVIDER_UNAVAILABLE` + 记 `INVALID_REQUEST`；不重发 |
| 401 | Authentication Fails | 凭据无效 → 凭据转 `INVALID`，返回 `CREDENTIAL_INVALID`，run 转 `WAITING_CREDENTIAL` |
| 402 | Insufficient Balance | 账户余额不足 → 记 `INSUFFICIENT_BALANCE`，停止后续调用并提示用户向供应商充值（BYOK 下这不是应用预算） |
| 422 | Invalid Parameters | 参数非法 → `PROVIDER_UNAVAILABLE` + 有界修正 1 次 |
| 429 | Rate Limit Reached | 并发超限 → `RATE_LIMITED`，不重发；本轮 run 直接以限额停止 |
| 500 | Server Error | `PROVIDER_UNAVAILABLE`；已发送即按未知结果保留预占 |
| 503 | Server Overloaded | 同上 |

限流按**并发数**计（账户级）：`deepseek-flash` 2500、`deepseek-v4-pro` 500，超限返回 429。可用 `user_id`（`[a-zA-Z0-9_-]{1,512}`）做供应商侧隔离 —— 但**不得**传真实身份，应传不可逆的 run/user 派生标识。
非流式响应期间服务端会**持续返回空行**保持连接；若 10 分钟内未开始推理则关闭连接。→ Java `HttpClient` 的读取超时必须**受 run 剩余时间约束**（默认总截止 120s），不能不设或设成分钟级放任。

**未能核实**：4xx 的响应体 JSON 结构（错误码页没有给出 schema）→ 解析时对 body 做宽容处理，只把 HTTP 状态与自身错误码当作可信信息，不依赖对方 body 字段名。

## 10. 流式（本轮不实现）

`stream_options.include_usage` 受支持，但**必须与 `stream=true` 同用**；`stream` 非 true 时设置它会返回 400。SSE 以 `data: [DONE]` 结束，usage 挂在最后一个内容块上。
→ 本轮非流式，**不发送 `stream_options`**（否则 400）。这是设计 §5 第 6 条"不发送与当前能力不兼容的参数"的具体一例。

## 11. 本文档没有覆盖、因而代码里必须留白的事项

1. `/v1` 前缀可用性 —— 按文档不带前缀，并做成配置。
2. `deepseek-chat` / `deepseek-reasoner` 今天是硬失败还是仍兼容 —— 只有停用公告，无实测手段。
3. 显式的最大输入 token 数字 —— 只有 1M 上下文与 `max_tokens` 上限。
4. `reasoning_tokens` 与混合命中/未命中的账单算法原文 —— 以算术与字段命名推导，作为项目假设声明。
5. 中国节假日日历与 `insufficient_system_resource`/`aborted` 的计费与重试安全性。
6. `deepseek-v4-pro` 当前实际由哪个模型服务、按哪档计费（官方两处说法冲突）。
