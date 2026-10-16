# FitAgent 自研 Agent 第一版：设计

日期：2026-10-13。依据：`FitAgent_自研Agent第一版_设计思路与Codex提示词_Java17_Boot4.md` 的 B 部分 + `docs/agent-v1-baseline.md` 的实际核查结果。
技术栈沿用现仓库：Java 17、Spring Boot 4.1.1、MyBatis-Plus 3.5.17（boot4 starter）、Flyway 12.4.0、Jackson 3.1.5、Redis（Lettuce 7.5.2）。不引入 LangChain4j / Spring AI / LangGraph。

> 模型 ID、能力开关、usage 字段名、报价、余额接口形状一律以 `docs/agent-v1-protocol.md` 的官方核查为准。本文档**不写死**任何来自旧设计包的模型名或价格（B6 明确要求）。

---

## 1. 本轮能力边界（先说不能做什么）

| B7 建议工具 | 本轮 | 原因 |
|---|---|---|
| `get_profile` | ✅ 注册 | 档案 + ACTIVE 目标服务已存在 |
| `get_training_plan` | ✅ 注册 | `TrainingPlanService.findToday` 已存在 |
| `get_weight_trend` | ✅ 注册 | `body_measurement` 有真实序列 |
| `get_workout_records` | ❌ 不注册 | `workout_session/entry/set` 不存在 |
| `get_training_progress` | ❌ 不注册 | 无执行记录、无 metrics 服务 |
| `get_weekly_training_summary` | ❌ 不注册 | 同上 |

后果（必须承认，不能用假数据补）：A3 的"计划 6 组 / 实际 5 组 → 完成度 50%、额外 2 组"示范与 B14 第 8 项本轮无法成立；模型也不得自行估算完成率。要闭环它需要先做训练执行记录 + 确定性统计，那是独立迭代。

同样不做：饮食识别、能耗公式、RAG/向量库、文件上传、Python、视频、MCP、多 Agent、自动修改或激活计划。

## 2. 目录与依赖方向

```
backend/app/src/main/java/com/hfj/fitagent/
├─ agent/
│  ├─ core/       AgentContext, AgentRunner, RunLimits, StopReason, RunCancellation, ModelCallGateway(接口)
│  ├─ model/      ModelMessage, ModelRequest, ModelResponse, ModelUsage, ToolCall, FinishReason, ModelClient(接口), ModelCapabilities
│  ├─ model/deepseek/  DeepSeekAdapter(实现 ModelClient), DeepSeekRequestFactory, DeepSeekResponseParser, DeepSeekBalanceClient, DeepSeekProperties
│  ├─ tool/       AgentTool<A,R>, ToolDefinition, ToolRegistry, ToolExecutor, ToolResult, ToolErrorCode
│  ├─ context/    ContextManager, CoachPromptTemplates（版本化模板，见 resources/prompts）
│  ├─ trace/      TraceRecorder, EvidenceRegistry, AgentEvent
│  ├─ answer/     AnswerAssembler, FactCard, AgentAnswer, CitationValidator
│  └─ fake/       FakeModelClient —— 仅 dev + 显式开关，prod 拒绝启动
├─ modules/
│  ├─ modelaccess/  provider/catalog/credential/price/balance：加密、脱敏、撤销、余额快照
│  ├─ billing/      BudgetGate, ReservationLedger, UsageSettlement, PriceCatalog, CostCalculator, DailyBudgetAccount
│  └─ coach/        controller/service/dto/vo + tools/（业务工具适配，只调已有 Service）
└─ resources/prompts/coach-system-v1.md
```

依赖方向（单向，禁环）：

```
coach ──► agent.core ──► agent.model.ModelClient(接口) / agent.tool.ToolRegistry(接口)
  │            ▲                    ▲
  │            │                    │
  │      modelaccess/deepseek 实现 ─┘        billing 实现 ModelCallGateway 的预占/结算
  └──► coach/tools ──► 既有 profile / training 的 Service（只读）
```

硬规则：

1. `agent/*` **不引用任何业务 Mapper**，也不引用 `modules/{profile,training,workout}` 的实体；工具适配层在 `modules/coach/tools`，只注入 Service 与只读端口。
2. `agent/*` 不引用 `modules/modelaccess` / `modules/billing` 的具体类，只依赖 `agent.core.ModelCallGateway`、`agent.model.ModelClient`、`billing` 暴露的接口；反向由 Spring 装配。
3. 沿用现有边界约定：`common`/`security` 不依赖业务模块；跨模块只走接口，目前新增端口 `modules/profile/api/ProfileContextReader`（已有）与 `modules/coach/api/*`（如需）。
4. `modules/coach` 允许依赖 `agent.*` 与 `billing.*`；`modelaccess` 与 `billing` 互不依赖，由 `agent.core` 在调用点串起来。

落地时相对本节的 3 处偏差（2026-10-14 实现后回填，都是依赖方向逼出来的，不是改需求）：

- **`ModelCallGateway` 的实现落在 `modules/coach/gateway/BudgetedModelCallGateway`，不在 `billing`。** 它必须写 `model_call` 台账，而那张表的 Mapper 归 coach；放 billing 就变成 `billing → coach` 的编译期依赖，与上面的单向图冲突。预占/结算仍然只调 `billing` 的 `BudgetGate`/`UsageSettlement`。
- **`user_account` 的只读豁免扩到 coach 一个文件**：`modules/coach/support/CoachDataRevisionReader` 读 `data_revision` 与 `timezone`（陈旧判定与业务日期）。这与 P1 的 `modules/training/PlanRevisionTicker` 同一手法——收在一个可单独审查的组件里，而不是让 coach 到处读别人的表。`UserAccountMapper` 因此多了一个只读方法 `findDataRevision`。
- **Worker 配置前缀是 `fitagent.coach.worker.*`**（本节 §10 原文写 `fitagent.agent.worker.*`）：`agent/*` 不允许碰业务表，线程池属于 coach。字段含义不变，见 `CoachProperties.Worker`。

## 3. 核心契约（B5）

```java
AgentContext(long runId, String runUuid, long userId, long conversationId, ZoneId zone,
             LocalDate businessDate, long dataRevision, CredentialHandle credential,
             ModelSelection model, BudgetReference budget, Instant deadline, Set<String> allowedTools)
```
全部服务端可信构造；`AgentContext` 不持有明文凭据，只持 `CredentialHandle(credentialId, credentialVersion, providerId)`，解密只发生在发出 HTTP 的那一步（B10）。

`ModelMessage(role, content, toolCalls, toolCallId, name)`；供应商特有字段（如 `reasoning_content`）只活在 `deepseek` 包内部，不进入 `agent.model` 契约。

`ModelRequest(model, messages, tools, toolChoice, thinking, maxTokens, temperature, timeout)`；`ModelResponse(content, toolCalls, finishReason, usage, providerRequestId, truncated)`。

```java
interface AgentTool<A, R> {
    ToolDefinition definition();      // name 全局唯一；重名启动即失败
    Class<A> argumentType();
    ToolResult<R> execute(A args, AgentContext ctx);
}
ToolResult(code, data, evidenceRefs, missingFields, dataComplete, dataRevision, truncated)
AgentAnswer(answerText, factCards, evidenceRefs, missingFields, dataRevision,
            isStale, runStatus, stopReason, usageSummary, costKnown, costUncertain)
RunLimits(maxModelCalls=4, maxToolCalls=8, maxToolsPerRound=4,
          maxResultBytes=8192, maxOutputTokens=1024, deadlineSeconds=120)
```

`RunLimits` 的初值是**工程闸门**，配置化（`fitagent.agent.limits.*`），不是产品标准，也不是健康阈值。

## 4. 执行循环

```
1 服务端装配 AgentContext（userId 来自 AuthenticatedUsers，businessDate 来自 AppClock + 用户时区）
2 ContextManager 构造消息：系统模板 + 服务端日期/时区说明 + 当前问题 + 有限历史 + 本轮工作消息
3 while (limits.allowAnotherModelCall() && !deadlinePassed && !cancelled)
4     gateway.call(...)          ← 每次都过 BudgetGate，不在数据库锁里等 HTTP
5     if finishReason 表示不完整（length/aborted/content_filter/insufficient_system_resource）
6         → 不执行任何被截断的 arguments，按 StopReason 结束，保留已验证事实
7     if 有 tool_calls
8         先把 assistant(含 tool_calls) 加入工作上下文
9         逐个：白名单查找 → 严格解析 arguments（拒绝未知字段）→ 参数校验 → 归属校验 → 执行 → 限长
10        为每个声明过的 tool_call_id 追加一条 tool 消息（失败也要追加结构化错误），全部配对后才进入下一轮
11    else → AnswerAssembler 校验引用并组装 → 结束
12 若这是最后一次允许的调用且仍需收尾：带 tool_choice=none 要求收束
13 预算/时间不足：不额外发"免费总结"请求，用确定性模板把已验证事实 + 停止原因返回
```

细节决策：

- **工具结果本轮内复用**：同一 `(toolName, 规范化参数, dataRevision)` 的已验证结果可复用，但仍要为新的 `tool_call_id` 生成对应 tool 消息；连续命中同一键视为有界循环，触发 `StopReason.REPETITIVE_LOOP` 终止，不让模型空转烧钱。
- **参数修正有界**：非法参数最多允许 1 次修正请求，且计入原 run 的调用次数与费用（B6.6）。
- **首版顺序执行工具**，不并发，不引入并行调度器。
- 循环由**远程响应**推动，不做"关键词命中就固定跑一组查询"的伪工具选择（B14 第 1 项要验证真实两轮以上消息循环）。
- **收尾调用仍返回 `tool_calls` 时照普通轮配对执行**（2026-10-14 定）：最后一次允许的调用带 `tool_choice=none` 是"要求"，供应商不遵守时如果把这一轮丢掉，就只剩 `PROTOCOL_INCOMPLETE` 而拿不到这一次已经读到的事实；正确做法是补齐配对、在循环顶部的次数闸门处以 `LOOP_EXHAUSTED` 结束，再用确定性模板返回已验证事实。被截断的 `arguments` 仍然一律不执行（§5 第 10 条不变）。

## 5. DeepSeek 适配（B6）

`stream=false` 为唯一实现路径。Base URL `https://api.deepseek.com`，文档路径**不带 `/v1`**（`POST /chat/completions`、`GET /user/balance`）；地址做成配置，不猜前缀。必须做到：

1. `tools[]` 声明（`type:"function"`，`function.name/description/parameters`）、`tool_calls[].function.arguments` 是 JSON 字符串 → 解析 + Bean Validation + 拒绝未知字段后才执行。`strict` 属 Beta 且需 `/beta` 前缀，**本轮不使用**，服务端参数与权限校验照常保留。
2. 带 `tool_calls` 的 assistant 消息**原样**留在后续请求消息里。
3. 每个 `tool_call_id` 恰好一条 `role:"tool"` 消息；孤儿 tool 消息和缺失配对都视为协议错误。官方明确 Chat Completions 不支持中途插入工具结果，我们只在响应末尾追加。
4. 同批重复 `tool_call_id`、未知工具名、异常 ID → 明确错误响应（仍完成协议配对），不执行"半个协议组"。
5.  思考模式**必须每次请求显式 `{"thinking":{"type":"disabled"}}`**：官方默认是 **enabled**，而带 `tools` 的请求若不回传 `reasoning_content` 会**直接 400**（`agent-v1-protocol.md` §5）。本轮不实现 `reasoning_content` 回传，因此配置校验**直接拒绝开启**该模式（B6：不能丢字段还宣称可用）。连带后果：`tool_choice` 只能用 `auto` 与收尾的 `none`，`required`/指定工具名在思考模式下会 400。
6. 已发出请求后读超时/断连 → 该次调用记 `UNKNOWN`，**不自动重发**（B6.8 + B10）。`insufficient_system_resource`、`aborted` 这类"结果未知"的终止同样按已发送处理。
7. `Authorization` 头与请求体在日志里脱敏；禁用 HTTP body debug 日志；测试断言里不出现真实 Key。
8. 模型 ID / 上下文与输出上限 / 是否支持 tools / 报价，全部来自 `model_catalog` + `model_price` 表与配置，不写死在代码里。
9. **不发送与能力不兼容的参数**：非流式路径下不得带 `stream_options`（官方明确 `stream` 非 true 时设置它会 400）；`parallel_tool_calls` 不是 Chat Completions 的文档参数，不发送；思考模式关闭时才允许的非思考参数按需给。
10. `finish_reason` 白名单判定：只有 `stop` 与 `tool_calls` 算可用；`length`、`content_filter`、`insufficient_system_resource`、`aborted` 一律按不完整处理。
11. HTTP 读取超时受 run 剩余时间约束。官方会为非流式响应**持续返回空行**保活，最坏 10 分钟才关连接，因此不设超时会拖死 Worker。默认读取超时 **30 秒**（`DeepSeekProperties.DEFAULT_READ_TIMEOUT`）：run 总截止默认 120 秒，若取 90 秒，一次挂起就吃掉四分之三的预算，后续工具轮根本没有机会执行。
12. **422 的一次有界修正重发是本轮的已知偏差**：`DeepSeekClient` 把 422（参数在执行前被拒）当作"没产生推理费用"，允许在同一次预占内重发一次收紧参数后的请求。这个"执行前拒绝不计费"的推断**在官方文档里没有依据**（`agent-v1-protocol.md` §9 只核实了 400/402/429/500/503 的语义）。后果是这一次调用的实际成本可能高于保守预占；不改的原因是重发次数固定为 1、且仍受 run 次数与预算闸门约束。真实供应商联调时必须核对这一笔是否出现在账单里。

## 6. 凭据、价格与预算（B10）

### 6.1 凭据

- AES-256-GCM；**每次加密使用新的 12 字节随机 nonce**；认证附加数据绑定 `userId|providerId|credentialVersion`，防止密文跨行搬运。
- 主密钥 `FITAGENT_MASTER_KEY`（base64 的 32 字节，环境变量注入，不入库/不入 Git/不打日志）。
- 指纹用**另一个独立密钥** `FITAGENT_KEY_FINGERPRINT_KEY` + HMAC-SHA256，与加密密钥分开管理（不做"从一个密钥推两个用途"的自创派生）。指纹仅用于"这把 Key 是否同一把"，不保存明文。
- 表只存 `ciphertext`、`nonce`、`key_suffix`（最多 8 字符尾部）、`key_fingerprint`、`encryption_key_version`、`credential_version`、`status`。
- 任何读取接口只返回 id/状态/脱敏尾部/校验时间；**永不**返回密文、nonce、指纹、明文。
- 替换：同一行 `credential_version + 1`，历史账本保留旧版本号；撤销：清除密文与 nonce，`status=REVOKED`，此后不允许启动新调用（已在途调用无法追回，界面如实说明）。
- run 创建时**绑定凭据版本**，执行中不悄悄换 Key。

### 6.2 价格与核算

- `model_price` 版本化：`(model_id, price_version, currency, rate_tier, input_hit_per_million, input_miss_per_million, output_per_million, source_url, source_checked_on, enabled)`。
  `rate_tier ENUM('PEAK','OFF_PEAK')` —— **协议核查证实峰谷分时是官方真实机制**（`agent-v1-protocol.md` §7），故保留档位；但**节假日日历不入库、不猜测**，因此：预占一律按 **PEAK** 保守档，结算时档位可确定则按实际档，跨段或节假日存疑就标"保守核算"，不冒充供应商账单。
  当前模型只有 `deepseek-flash` 与 `deepseek-v4-pro`（`deepseek-chat`/`deepseek-reasoner` 已于 2026-07-24 停用）。`deepseek-v4-pro` 因官方两处公告自相矛盾（news260910 vs 2026-09-10 changelog），种子行 **`enabled=false`**，由用户核对当期计费后再启用。
- 核算：`(缓存命中输入 × 命中价 + 未命中输入 × 未命中价 + 输出 × 输出价) / 1,000,000`，`BigDecimal` + `DECIMAL(20,10)`。
- `reasoning_tokens` 是 completion 的**细分项**，不额外加钱；缺字段填 null 并标记未知，**不填 0**。
- 一个 run 的已知费用 = 所有已结算 `model_call` 之和（含参数修正与收尾调用）。

### 6.3 BudgetGate（不是默认放行）

```
短事务（固定锁序：agent_run → budget_daily_usage，避免反向等待）
  1 锁 run 行与当日账户行
  2 可用额度 = 限额 − (settled + reserved + uncertain_reserved)
  3 预占额 = 保守估算：输入按"全部未命中价"+ 输出按 maxOutputTokens 全价，不假定缓存命中
  4 超过单次或每日限额 → 抛 BUDGET_EXCEEDED，不发起 HTTP
  5 插入 budget_reservation(status=RESERVED) 与 model_call(status=PREPARED)
提交，释放行锁
—— 然后才发远程 HTTP ——
结算：usage 已知 → 按 model_call id 幂等结算（settled += 实际，reserved −= 估算，重复回调不重复扣账）
      确定未送达（连接前失败）→ 释放预占
      已送达但 usage 不明 → reservation 转 UNCERTAIN_HELD，保留估算额，不重发
```

- 日账本按**每次调用开始时的业务预算日**（`Asia/Shanghai`）归属；跨午夜的长任务，单次任务预算仍覆盖全部调用。
- 供应商余额币种 ≠ 预算币种时**分开显示**，不猜汇率、不相减。
- 承诺的是"保守预占 + 逐轮阻止后续请求"，不是供应商硬额度；实际超支记录并停止后续调用，界面不得声称能撤销供应商扣费。
- **`BudgetReference.complete()` 只要求 `policyId + currency`**（2026-10-14 定）：当天的 `budget_daily_usage` 行要到第一次预占才被创建，提交问题时它本就不存在；把 `dailyUsageId` 也纳入完整性判定等于宣布"每个新 run 都不许花钱"。缺币种或缺策略仍然一律拒绝发起调用，`unsetDetail()` 的文案就是这条拒绝的理由。
- **结算单价固定取 `BudgetContext.peakPrices()`**：档位不确定时按保守档算才不会算少（§6.2 的谷段优惠留给"能核实"之后）；因此"实际结算 ≤ 保守预占"在档位层面成立。
- 上面第 5 步的 `model_call(status=PREPARED)` 在实现里由**费用关口**写而不是 `BudgetGate`：网关落在 `modules/coach/gateway`（§2 的偏差说明），它先写 `SENT + sent_at` 再交棒传输层。差别只影响"崩溃点在哪"，不影响恢复结论——`countPossiblySent` 把 `PREPARED/SENT/UNKNOWN` 三种都算"可能已计费"。

## 7. 表与增量迁移（B11）

只新增本轮真正用到的表；**不复制 36 张全量 schema**，不修改 V1–V9。

| 迁移 | 表 | 要点 |
|---|---|---|
| V10 | `model_provider` | 白名单 base_url，`adapter_code`；`code2Session` 式外呼地址不可由输入决定 |
| V11 | `model_catalog` | `model_code`、`supports_tools/vision/json`、`max_output_tokens`、`capability_snapshot JSON`、`source_checked_on` |
| V12 | `model_price` | 版本化报价 + `rate_tier ENUM('PEAK','OFF_PEAK')`（协议核查证实峰谷档真实存在），币种显式、来源与核查日期；`deepseek-v4-pro` 种子行 `enabled=false` |
| V13 | `model_credential` | `(user_id, provider_id)` 唯一；`ck`：ACTIVE 必须有 ciphertext+nonce |
| V14 | `provider_balance_snapshot` | `is_available BOOLEAN NULL`（官方字段，**不是** `is_expired`——该字段在当前文档中不存在）、值+币种+total/granted/topped_up（官方返回**字符串**，落库 DECIMAL）、`fetched_at`；失败不清零 |
| V15 | `budget_policy` | `(user_id, provider_id, currency)` 唯一；单次/每日上限、轮数与输出上限、`budget_timezone` 固定 Asia/Shanghai |
| V16 | `budget_daily_usage` | `(user_id, provider_id, currency, budget_date)` 唯一；settled / reserved / uncertain_reserved 三列分账 |
| V17 | `chat_conversation` | 归属 + status + `last_message_at` + `lock_version` |
| V18 | `agent_run` | `run_uuid`、`idempotency_key`、`request_hash`、凭据版本、model_id、status、`active_conversation_id` 生成列、budget_snapshot、context_revision、result_payload、stop_reason、estimated/settled/uncertain 三费、error_code、时间戳、lock_version |
| V19 | `budget_reservation` | `run_id + attempt_no` 唯一；`daily_usage_id`；估算/结算额/质量/状态 |
| V20 | `model_call` | `run_id + attempt_no` 唯一；`reservation_id` 唯一（保证一次结算）；三个 token 分项 + usage 原文 + 单价快照 + 币种 |
| V21 | `agent_step` | `(run_id, step_no)` 唯一；`arguments_redacted`、`result_summary`、耗时、错误码 |
| V22 | `chat_message` | `(conversation_id, message_no)` 唯一；role、正文、tool_calls/citations 结构化列、`run_id` |
| V23（**已让位给阶段 2 三表，实际序号 V26**） | 种子：provider + catalog + price | 取值全部来自 `agent-v1-protocol.md` 的核查，附来源 URL 与核查日期；**无核查通过则本迁移不落地，改为空占位并在 acceptance 标注** |

关键设计点：

- **单会话只允许一个活动 run** 用与 `active_user_id` 同一套手法：
  `active_conversation_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN status IN ('QUEUED','RUNNING') THEN conversation_id ELSE NULL END) STORED` + `UNIQUE`。
  这是数据库约束，不是"页面禁用发送按钮"（B12）。
- **幂等**：`UNIQUE(user_id, idempotency_key)`。同键同 `request_hash` → 返回同一 run；同键不同 hash → `IDEMPOTENCY_KEY_CONFLICT`，绝不返回另一个问题的结果。`request_hash = SHA-256(conversationId | 规范化问题文本 | 语言/日期参数)`，**不含明文凭据**。
- 所有表带 `user_id`，子资源用 `(id, user_id)` 复合唯一 + 复合外键，沿用 P1 已验证的做法。
- `result_payload JSON NOT NULL`（终态时）存**完整** `AgentAnswer`：事实卡片、来源、缺失项、data_revision、stop_reason。页面重进读同一份持久化结果，不依赖内存，也不用截短的 `result_summary` 冒充。费用状态可更新，**事实快照不被静默重写**。
- 迁移只向前：不改历史脚本，不加 `DROP`/`TRUNCATE`。

## 8. 会话与消息顺序

- `message_no` 在会话行锁下原子分配（`SELECT ... FOR UPDATE` 会话行后 `max+1`），并发提问不会交错消息。
- 历史回放只取"user + 最终可见 assistant"，**不**把历史原始 tool 消息塞进新请求；当前 run 的工具组在当前工作上下文内完整保留（B9）。
- 超长上下文按**轮次边界**丢弃并提示，绝不裁掉未闭合的工具组。
- 历史模型回答与用户备注是非权威文本：不作为最新身体/训练事实，需要个人数据必须重新过真实工具；文本内容不能改变系统权限、预算或工具能力（越权指令一律不执行）。

## 9. 事实、引用与陈旧判定（B8）

- 工具为每条来源生成 `evidenceId`，绑定 `sourceTable / sourceId / planRevision / businessDate / ruleVersion / dataRevision`。
- 模型只能引用本次 run 证据集合里已存在的 ID；不存在的引用直接丢弃并计入 `missingFields`，不报错伪装。
- **个人数值不由模型填写**：`AnswerAssembler` 按引用取工具提供的值/单位/日期组装 `factCards`；模型给出的百分比一律不覆盖 metrics/服务结果。
- `data_revision` 在 run 开始与结束各取一次，不同则 `isStale=true`，事实保留原快照与来源日期，不自动付费重跑。
- 缺数据明确列出，不以 0 代替未知；"没有正式记录"不等于"实际没训练"。
- 边界声明：引用校验只证明**引用存在且归属合法**，不证明自由文本全部结论正确。评测集检查模型是否擅自引入个人数值或不存在的事实，但不宣称实现了完整防幻觉。

## 10. 后台执行、取消与恢复（B12）

- 单机有界 `ThreadPoolExecutor`（核心 1、最大 2、队列 32，`fitagent.agent.worker.*` 可调），不用无界线程、不用消息队列。
- 提交在数据库事务**提交之后**派发（`TransactionSynchronization.afterCommit`），避免 Worker 抢在未可见事务上跑。
- Worker 用条件更新原子领取：`UPDATE agent_run SET status='RUNNING', worker_id=?, lease_expires_at=? WHERE id=? AND status='QUEUED'`，影响 0 行即放弃 → 同一 run 不会被两个 Worker 并行执行。
- 心跳续租；启动扫描器补领遗漏的 QUEUED，并对失去 Worker 的 RUNNING 应用恢复规则：
  - 存在 `status=SENT/PREPARED` 的 `model_call` → run 转 `FAILED` + `stop_reason=UNKNOWN_CHARGE`，预占转 UNCERTAIN，**不自动重发**；
  - 无任何已发送调用 → 允许回到 QUEUED 重新领取。
- 取消：写 `cancel_requested_at`；每轮模型调用前、每个工具执行前检查；对进行中的 HTTP 尽力取消（Future cancel）。已发送的请求不因取消而免费，界面显示已发生费用。
- 客户端停止轮询 ≠ 取消后台任务；只有 `POST /api/coach/runs/{id}/cancel` 才是取消。
- `TraceRecorder` 写失败**绝不**触发重发模型请求（轨迹是可丢的诊断材料，不是计费依据）。
- 状态集：`QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED / BUDGET_STOPPED / LIMIT_STOPPED / DEADLINE_STOPPED / WAITING_CREDENTIAL / COST_UNKNOWN`。终态不可被普通重试改回 RUNNING。

## 11. 接口（B13）

| 方法/路径 | 语义 |
|---|---|
| `POST /api/coach/conversations` | 建会话 |
| `GET /api/coach/conversations?page=&size=` | 本人会话分页 |
| `GET /api/coach/conversations/{id}` | 会话详情 |
| `POST /api/coach/conversations/{id}/archive` | 归档 |
| `GET /api/coach/conversations/{id}/messages?after=&limit=` | 历史消息 |
| `POST /api/coach/conversations/{id}/estimate` | **不调用收费模型**；返回假设、最大调用次数、限额、可用工具与凭据状态 |
| `POST /api/coach/conversations/{id}/runs` | 幂等提交，返回 202 + runId。`Idempotency-Key` 必须是标准 UUID：列是 `CHAR(36)` 且约定小写规范化（V19），服务端在进数据库之前就 `Ids.canonicalUuid` 校验并规范化，格式错误返回 400 字段错误而不是 1406 内部错误 |
| `GET /api/coach/runs/{id}` | 状态、进度摘要、最终结果、已知/未知费用 |
| `GET /api/coach/runs/{id}/steps` | 脱敏操作摘要 |
| `POST /api/coach/runs/{id}/cancel` | 幂等取消 |
| `PUT/DELETE /api/model-credentials/deepseek` | 保存/替换/撤销；读取永不返回完整 Key |
| `GET /api/model-credentials/deepseek/balance` | 值、币种、查询时间、stale 标志 |
| `GET/PUT /api/budgets/deepseek` | 单次/每日限额，同币种 |

约束：`estimate` **不要求可用凭据**——输入区要在用户还没配 Key 时也能显示预估、限额和 `credentialStatus=MISSING`；真正的拒绝发生在 `POST .../runs` 与每一次预占（B14 第 9 项）。`stop_reason` 在库里存枚举名（`SUCCEEDED`），出参按 §11.1 第 8 条映射为 `COMPLETED`，未识别值原样透传；映射只有一个文件：`modules/coach/support/CoachStopReasons`。`user_id` 一律来自认证上下文；客户端传来的费用/用户 ID/模型能力/余额**都不是可信值**；`estimate` 的假设与每轮真实预占算法一致，有 estimate 也不跳过执行时的 BudgetGate。

## 11.1 载荷契约（前端已按此实现，后端必须匹配，不得另起一名）

```jsonc
// POST /api/coach/conversations            请求体 {}（标题由服务端从首个问题截取）
// POST /api/coach/conversations/{id}/estimate  与  .../runs  请求体：
{ "question": "今天安排练什么？" }

// estimate 响应 data：不调用收费模型，只做确定性计算
{ "assumptions": ["输入按未命中缓存价、输出按 maxOutputTokens 满额预占", "档位按 PEAK 计"],
  "maxModelCalls": 4, "maxToolCalls": 8,
  "estimatedCostLow": "0.0000120000", "estimatedCostHigh": "0.0003400000",
  "currency": "CNY", "budgetCurrency": "CNY",
  "availableTools": ["get_profile","get_training_plan","get_weight_trend"],
  "credentialStatus": "ACTIVE",           // ACTIVE | MISSING | INVALID | REVOKED
  "budgetRemainingTask": "0.4996600000", "budgetRemainingDaily": "4.2000000000",
  "fakeModel": false }

// POST .../runs 响应：202
{ "runId": "88", "conversationId": "12", "status": "QUEUED",
  "estimatedCost": "0.0003400000", "currency": "CNY", "idempotencyKey": "…", "fakeModel": false }

// GET /api/coach/runs/{id} 响应
{ "runId":"88", "conversationId":"12", "status":"SUCCEEDED", "stopReason":"COMPLETED",
  "progress": { "modelCalls":2, "toolCalls":1, "stepCount":4 },
  "answer": { /* AgentAnswer：见 §3；不含费用字段 */ },
  "costKnown": "0.0002130000", "costUncertain": "0.0000000000", "currency": "CNY",
  "isStale": false, "fakeModel": false, "errorCode": null,
  "createdAt":"…", "finishedAt":"…" }
```

规范化决定（前端的 8 处猜测在此变成契约，改动必须先改本节）：

1. **费用字段只在 run 响应上**；`AgentAnswer` 里不放 `costKnown/costUncertain`。两处都能放就一定漂移，只留一处。
2. **`fakeModel: boolean` 必须存在于 estimate / runs / run 三处**。§12 要求页面显示"模拟模型"标记；没有该字段时前端只能显示"无法确认是真实还是模拟" —— 不接受推断出来的可信度。
3. 所有金额以**字符串**传输，前端不做算术只格式化，避免 JS 浮点污染 `DECIMAL(20,10)`。
4. `messages` 与 `steps` 响应统一 `{items:[…]}`（与 `PageResult` 同形），不返回裸数组。
5. `CoachMessage`：`{ id, messageNo, role:'user'|'assistant', content, runId, createdAt, factCards[], missingFields[], isStale, dataRevision }`，`role` 小写。
6. `RunStep`：`{ stepNo, eventType, toolName, summary, status, errorCode, durationMs }`；**永不**返回原始参数、`reasoning_content`、请求体或密钥。
7. 错误码 `IDEMPOTENCY_KEY_CONFLICT`、`RUN_ALREADY_ACTIVE`、`RUN_NOT_CANCELLABLE`、`BUDGET_EXCEEDED_TASK/DAILY`、`CREDENTIAL_MISSING/INVALID`、`MODEL_NOT_CONFIGURED`、`PROVIDER_UNAVAILABLE`、`BALANCE_UNAVAILABLE`、`WAITING_CREDENTIAL`、`INSUFFICIENT_BALANCE`、`UNKNOWN_CHARGE`、`UNKNOWN_FIELD` 已在 `miniapp/src/types/api.ts` 与 `utils/errorMessage.ts` 建好中文口径，后端必须用同名 code。
8. `stopReason` 取值：`COMPLETED / BUDGET_STOPPED / LIMIT_STOPPED / DEADLINE_STOPPED / CANCELLED / REPETITIVE_LOOP / PROTOCOL_INCOMPLETE / PROVIDER_ERROR / NO_CREDENTIAL / UNKNOWN_CHARGE / LOOP_EXHAUSTED`；未识别值前端**原样显示机器码**，不隐藏。`LOOP_EXHAUSTED`（模型调用次数耗尽且没有可用回答，见 §4）与 `UNKNOWN_CHARGE`（恢复规则判定"可能已计费"，见 §10）是 2026-10-14 实现时补进清单的两个原因：把前者塞进 `LIMIT_STOPPED` 会让"次数闸门"与"长度/字节闸门"在界面上分不清，把后者塞进 `PROVIDER_ERROR` 会掩盖"这一问可能已经扣了钱却没结果"。库里存枚举名（成功是 `SUCCEEDED`），出参按本节映射为 `COMPLETED`，唯一实现是 `modules/coach/support/CoachStopReasons`。
9. `GET /api/coach/conversations/{id}` 与 `POST /api/coach/conversations/{id}/archive` 后端要实现（§11 已列）；本轮小程序历史页只做只读查看与"回到当前会话"，**不放归档按钮**，UI 需要时再加（不留占位）。
10. 供应商余额币种与本应用预算币种是两本账：教练页只显示预算侧金额，供应商余额只在模型设置页显示，两边都不做汇率折算。

## 12. 小程序

- 新增 `api/coach.ts`、`api/model.ts`，`types/coach.ts`、`types/model.ts`。
- 页面：`pages/coach/chat`（会话 + 快捷问题 + 任务状态 + 事实卡片与来源 + 缺失项 + 费用 + 停止按钮 + 轮询）、`pages/coach/history`（可选，若空间不足并入 chat）、`pages/mine/model-settings`（Key 保存/替换/撤销、脱敏尾部、余额快照与查询时间、预算设置）。入口挂在"我的"，TabBar 保持三格不动。
- 轮询：一次只维护**一个**定时器；进入页面若该会话有活动 run 就恢复轮询同一个 runId，**不自动重发问题**；终态或页面销毁即停。
- 用微信可用的普通 HTTP 轮询；不用只在 H5 能工作的 `EventSource` 冒充实现。
- 沿用现有五态与密度规范（15px UI / 19px 正文 / ≥40px 触控）；费用显示区分"发送前预估""本次已核算""存在用量不明"，低于显示精度写"低于 0.0001 元"，不写 0.00。
- Fake 模式在页面上必须可见标注，与真实供应商结果分开。

## 13. 测试策略（B14）

- 协议与循环用 **FakeModelClient**（脚本化响应序列）+ HTTP stub；Fake 只在 dev/test profile 且显式开关下装配，prod 探测到即启动失败（沿用 dev-login 的双锁手法）。Fake 不作为生产回退。
- 单元：工具参数严格校验、引用校验、成本核算（BigDecimal 精度与 reasoning 不重复计费）、预占/结算幂等、状态机与恢复规则、凭据加解密往返与 AAD 绑定、撤销后拒绝新调用。
- 需要数据库的并发/唯一约束用例（同幂等键并发、两 run 抢同一份日预算、单会话单活动 run）用 Testcontainers `@Tag("requires-docker")`，本机默认跳过并如实标注未验证。
- 真实 DeepSeek：默认**不发**收费请求；提供一条受既有预算保护的手动 smoke 命令，未执行则在 acceptance 写"未做真实供应商联调"。

## 14. 关键取舍一览（便于逐条评审）

| 取舍 | 决定 | 理由 |
|---|---|---|
| 流式 | 不做 | 先验证完整协议闭环；轮询足够 |
| 思考模式 | 关闭且拒绝开启 | 未实现 `reasoning_content` 回传，开着就是丢字段骗人 |
| 工具并发 | 顺序执行 | 首版无并发需求，少一套调度状态机 |
| 队列 | 数据库 + 有界线程池 | 单机开发，消息队列是过度设计 |
| 单活动 run 约束 | 生成列 + UNIQUE | 与项目既有 `active_user_id` 同构，可证明 |
| 预算锁 | MySQL 短事务行锁，HTTP 在锁外 | 不能在行锁里等远程调用 |
| 未知费用 | 保留预占、不归零、不重发 | 已发送的调用可能已计费 |
| 训练执行类工具 | 不注册 | 业务不存在，宁缺毋伪 |
| 价格与模型 ID | 表 + 配置，核查后才种子 | B6 禁止照抄旧设计 |
