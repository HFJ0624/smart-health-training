# FitAgent Agent 第一版：自研了什么、复用了什么

日期：2026-10-14。给"这套东西为什么值得自己写一遍"这个问题一个能逐条指着代码回答的说明。范围与取舍见 `agent-v1-design.md`，实际验证结果见 `agent-v1-acceptance.md`。

## 1. 自研的六件事（都是执行机制，不是业务概念）

| 能力 | 代码位置 | 为什么必须自己控制 |
|---|---|---|
| 统一的模型请求/响应契约与能力边界 | `agent/model/`：`ModelRequest`、`ModelResponse`、`ModelMessage`、`ModelUsage`、`FinishReason`、`ToolChoice`、`ThinkingMode`、`ModelCapabilities`、`ModelProviderException`、`ProviderErrorKind` | 供应商特有的字段（`reasoning_content`、`prompt_cache_hit_tokens`、`finish_reason` 的四个不完整值）只在适配层内部处理，业务代码看不到；换供应商不重写循环 |
| 工具定义、注册、参数校验、权限与执行 | `agent/tool/`：`AgentTool<A,R>`、`ToolDefinition`、`ToolRegistry`、`ToolExecutor`、`ToolResult`、`ToolResultCache`、`ToolErrorCode` | 工具名唯一性在启动期炸掉；参数严格解析（未知字段即拒）；身份字段出现在参数里直接 `FORBIDDEN_IDENTITY_FIELD`；结果超长按"先保指标与来源、再裁明细"的顺序收缩 |
| 有停止条件的执行循环 | `agent/core/AgentRunner.java` + `RunLimits` + `StopReason` + `RunCancellation` | 轮数、工具数、单轮工具数、结果字节、截止时间、重复请求检测、重复工具读取检测、有界参数修正——每一个都要能回答"在哪一层拦、为什么拦" |
| 多轮上下文与完整工具消息配对 | `agent/context/`：`ContextManager`、`ConversationContext`、`CoachPromptTemplates`（版本化模板在 `resources/prompts/`） | DeepSeek 要求带 `tool_calls` 的 assistant 消息原样保留、每个 `tool_call_id` 恰好一条 tool 消息；历史按**轮次边界**裁剪，绝不裁掉未闭合的工具组 |
| 每次远程请求的预算预占与用量结算 | `modules/coach/gateway/BudgetedModelCallGateway`（实现 `agent/core/ModelCallGateway`）+ `modules/billing/`：`BudgetGate`、`UsageSettlement`、`CostCalculator`、`DailyBudgetAccount` | 短事务预占 → 释放行锁 → 才发 HTTP → 按调用 ID 幂等结算；已发出但用量不明时保留 `UNCERTAIN` 预占，不归零、不重发。这条时序用框架的"拦截器"表达反而说不清 |
| 任务状态、取消、幂等与执行轨迹 | `modules/coach/service/`（`CoachApplicationService`、`RunExecutionService`、`RunCancellationRegistry`）+ `modules/coach/worker/RunWorker` + `agent/trace/`（`TraceRecorder`、`AgentEventSink`、`TraceRedaction`、`EvidenceRegistry`） | 幂等键与"每会话一个活动 run"由数据库唯一索引保证；租约 + 恢复规则决定"失联的 run 能不能重跑"；轨迹是**可丢的诊断材料**，写失败绝不触发已付费请求重发 |

没有自研的：HTTP 客户端（`java.net.http`）、JSON（Jackson 3）、ORM（MyBatis-Plus）、加密原语（JDK `Cipher`/`Mac`）、校验（Bean Validation）。这些用现成的，是因为"自己实现一个 AES 或一个连接池"对本项目没有解释力。

## 2. 复用了哪些既有业务服务

三个工具全部只读现成 Service，不碰任何 Mapper，也不新写一条业务 SQL：

| 工具 | 复用 | 服务端绑定 |
|---|---|---|
| `get_profile` | `modules/profile/service/UserProfileService.find` + `UserGoalService.findActive` | `ctx.userId()`；输出排除 openid、凭据等标识 |
| `get_training_plan` | `modules/training/service/TrainingPlanService.findToday(userId, zone)` | 本人时区推导的业务日期；返回**实际生效的那个版本**与动作目标 |
| `get_weight_trend` | `modules/profile/service/BodyMeasurementService.list` | 起止日期区间校验、最多 31 天、缺测如实报告；少于两次测量时变化为 null |

跨模块只走接口这条 P1 规矩仍然成立：唯一新增的"越界"是 `modules/coach/support/CoachDataRevisionReader` 一个文件里读 `user_account.data_revision / timezone`（与 P1 的 `PlanRevisionTicker` 同一手法，单独可审）。

⚠️ **本轮没有第四个工具**：训练执行记录与完成度统计（`workout_session/*`、metrics）在仓库里不存在，所以"今天完成了多少""本周练了几次"无工具可答。设计包 A3 那段 50% 完成度的演示属于未来迭代——宁可少注册工具，也不用假返回值凑演示。

## 3. 一个问题如何跑完整条循环（以"我最近体重怎么变？"为例）

```
HTTP POST /api/coach/conversations/{id}/runs  (Idempotency-Key)
  └─ CoachApplicationService.submit            事务开始
       ├─ 会话归属校验 + 问题长度校验（不截断后照常计费）
       ├─ ModelCredentialService.requireUsable  ← 没有可用 Key 就在这里拒，一次调用都不发起
       ├─ ProviderCatalogService.selectModel    ← 模型 ID/能力/输出上限来自 model_catalog，不写死
       ├─ agent_run(QUEUED) 绑定 credential_version / model_id / currency / data_revision
       │    └─ 撞 UNIQUE(active_conversation_id) → DuplicateKeyException → RUN_ALREADY_ACTIVE
       └─ chat_message(user, message_no = MAX+1 在会话行锁下)     事务提交
            └─ RunWorker.scheduleAfterCommit → 有界线程池 → claim(QUEUED→RUNNING + 租约)
                 └─ RunExecutionService → AgentContext(服务端可信值)
                      └─ AgentRunner.run
                           loop:
                             ① 次数/截止/取消/重复请求检查
                             ② BudgetedModelCallGateway.call
                                  BudgetGate.reserve(短事务：锁 run→日账户，保守预占，落 budget_reservation)
                                  model_call(SENT + sent_at)         ← 台账先落，崩溃也能判定"可能已计费"
                                  DeepSeekClient.complete → JdkHttpTransport（thinking=disabled, tool_choice=auto）
                                  UsageSettlement.settle(按 usage 幂等) / markUnknown / release
                             ③ finish_reason 不在白名单 → PROTOCOL_INCOMPLETE，被截断的 arguments 不执行
                             ④ tool_calls：assistant 消息原样入上下文 → 逐个 白名单→注册表→严格解析→
                                校验→归属→执行→限长 → 每个 tool_call_id 恰好一条 tool 消息
                             ⑤ get_weight_trend 的结果生成 evidenceId 绑定
                                (sourceTable, sourceId, businessDate, ruleVersion, dataRevision)
                             ⑥ 第 2 轮模型给出自由文本 + 引用 ID
                           AnswerAssembler：引用必须存在于本 run 证据集合；数值一律取服务端的值组装 factCards
            └─ finishCas(终态 + result_payload 完整快照 + settled/uncertain 费用) → chat_message(assistant)
```

页面侧只有普通 HTTP 轮询：`GET /api/coach/runs/{id}` 读的就是 `result_payload` 这一份持久化快照，费用字段可以后续更新，事实快照不被静默重写。

## 4. 三条"看起来能省，其实不能省"的取舍

- **不用 LangChain4j / Spring AI 做编排**。不是因为框架不好，而是因为这套代码要能回答"权限在哪一层拦、这一问的钱是谁允许花的、为什么停了"。委托给框架后这三个问题都要穿透几层抽象去找答案。
- **不做工作流 DSL、反射调用工具、插件热加载**。第一版需要的是一张 `Map<String, AgentTool>` 加白名单，发明一门语言只会让审计面变大。
- **事实由 Java 生成，模型负责解释**。引用校验只能证明"引用的东西真实存在且属于本人"，不能证明自由文本每个结论都对。这一点在 `agent-v1-design.md` §9 明写，评测集检查模型是否擅自引入个人数值，但不宣称实现了完整防幻觉。

## 5. 接下来怎么扩（复用已验证的机制，不预先建抽象）

| 扩展 | 直接复用 | 需要新增 |
|---|---|---|
| AI 计划草稿（生成/修改 `DRAFT`） | `AgentRunner` 循环、`BudgetedModelCallGateway`、`ToolExecutor` 的配对与限长、`agent_run` 状态机 | `propose_plan_draft` 工具（写操作要在 `ToolExecutor` 里多一层"本 run 允许写"的能力判定）、计划结构校验、用户确认后才落 `DRAFT` |
| 训练执行记录 + 完成度统计 | 上面整套 | `workout_session/entry/set` 三张表与 metrics Service —— **然后**才有 `get_training_progress` / `get_weekly_training_summary` / `get_workout_records` |
| RAG（私有知识库） | `AgentTool` 契约、`EvidenceRegistry` 的 evidenceId 机制 | 检索工具 + 片段来源 + 访问控制；引用粒度从"记录 ID"变成"文档片段 ID"，`AnswerAssembler` 的校验逻辑不变 |
| 饮食图片识别 | `ModelCallGateway`（同一套预算与账本） | 固定识别工作流（不是自由对话）、食物字典匹配、用户确认；`model_catalog.supports_vision` 已经是真列，能力判定不用改 |
| 教学视频要点 | run/预算/状态/来源四件套 | ASR、抽帧、分段解析，evidenceId 绑到时间点 |

顺序上有一条硬规矩：**不要因为将来要做 RAG 就先接向量库，也不要因为叫 Agent 就先做多 Agent**。本轮的循环连真实供应商联调都还没做完（缺用户凭据），在此之上加编排层只会让"哪一步真的成立"变得更难说清。
