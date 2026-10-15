# FitAgent 目录与关键入口导读（大白话版）

日期：2026-10-14。目标：新接手的人不用全文搜，就能知道"某个行为归谁管、去哪改、改完会打到谁"。
架构决策与理由在 `docs/agent-v1-design.md`、`docs/p1-architecture.md`，本文件只做路标。

## 1. 顶层结构

```
backend/                      Spring Boot 4.1.1 + Java 17 单应用，按模块分包
  app/src/main/java/com/hfj/fitagent/
    common/                   横切：响应信封、错误码、幂等、时钟、追踪 id
    security/                 会话令牌过滤器与当前用户读取（身份唯一来源）
    modules/auth|profile|training/   P1 三个业务模块
    modules/modelaccess/      BYOK：供应商白名单、模型目录、报价、凭据加密、余额
    modules/billing/          钱：预算闸门、预占、结算、成本计算
    modules/coach/            AI 教练：会话/run、业务工具、费用关口、后台 Worker
    agent/                    自研执行核心（core/model/tool/context/trace/answer/fake）
  app/src/main/resources/db/migration/  Flyway V1..V22，只向前
  app/src/test/java/          357 个用例（不连库、不起 Docker、不发网络）
miniapp/                      uni-app + Vue 3 + TS
  src/pages/…                14 个页面（pages.json 是唯一路由清单）
  src/components/…           11 个组件
  src/api/ + src/types/      一个后端接口配一个函数、一套类型
  src/utils/                 request 封装、错误文案、事件取值、日期/单位、存储
  src/uni.scss + src/styles/theme.ts + src/App.vue   主题三件套
docs/                        规划、协议核查、验收、审计、演示、本导读
infra/                       .env.example 与 docker-compose（本机未执行）
```

## 2. 后端：一个请求会路过哪些地方

| 关注点 | 入口 | 说明 |
|---|---|---|
| 我是谁 | `security/AuthenticatedUsers.require()` | Service 方法签名里的 `userId` 只能由控制器从它取；**客户端传的 userId 一律不可信** |
| 统一响应 | `common/web/ApiResponse` | `code/message/data/traceId/serverTime`；前端只按 `code` 分支 |
| 错误码 | `common/error/ErrorCode` + `GlobalExceptionHandler` | 越权与不存在同形（404）；字段错误在 `data.fields`；BigInt 出参是字符串 |
| 幂等 | `common/IdempotencyService`（Redis 24h）与数据库唯一索引 | 计划导入确认用 Redis；AI run 用 `UNIQUE(user_id, idempotency_key)`，因为 run 需要可查询的持久状态 |
| 数据修订号 | `modules/profile/service/AccountRowGuard`、`modules/training/PlanRevisionTicker`、`modules/coach/support/CoachDataRevisionReader` | 写业务数据同事务 `+1`；AI 结果据此判断 `isStale`。三个文件是**仅有的**跨模块读 `user_account` 的地方 |
| 计划聚合 | `modules/training/TrainingPlanService`（写路径 create/update/revise/copy 内部一次事务写头+日+条目），校验在 `PlanAggregateValidator` | 任一失败全量回滚；删除子行顺序是先 item 后 day（外键 RESTRICT） |
| 派生列 | `V1..V22` 里的 `active_user_id` / `active_conversation_id` | STORED 生成列，实体不声明、任何写入不带它；"每用户一个 ACTIVE""每会话一个活动 run" 靠 UNIQUE + 数据库 |

## 3. AI 教练：一次提问的完整链路

```
CoachController（/api/coach/**，取 userId 与时区）
  └─ CoachApplicationService        会话、消息顺序、预估、幂等提交、run 状态、取消
       ├─ CoachMessageWriter        会话行锁下分配 message_no（并发不交错）
       ├─ CoachCostEstimates        与执行侧同口径的保守预估（PEAK 档、不假设缓存命中）
       └─ RunDispatcher → RunWorker 事务提交后派发；有界线程池 + 租约心跳 + 补偿/恢复扫描
            └─ RunExecutionService  组装 AgentContext（全部服务端可信值）+ 结果落库
                 └─ agent/core/AgentRunner        ★ 自研循环：次数/截止/取消/重复 → 调用 → 配对 → 停止
                      ├─ ModelCallGateway 接口 → BudgetedModelCallGateway（modules/coach/gateway）
                      │     BudgetGate.reserve → model_call 台账 → DeepSeekClient → UsageSettlement
                      ├─ ToolExecutor → ToolRegistry → modules/coach/tools/Get*Tool → P1 业务 Service
                      ├─ ContextManager → ConversationContext（工具组配对合法性、按轮次裁剪）
                      ├─ TraceRecorder → AgentStepWriter（agent_step，脱敏、可丢）
                      └─ AnswerAssembler → CitationValidator → AgentAnswer（事实卡片由服务端取值）
```

要改行为时最该看的四个文件：`AgentRunner.java`（循环与停止原因）、`BudgetedModelCallGateway.java`（钱）、`ToolExecutor.java`（工具边界）、`RunWorker.java`（取消与恢复）。

## 4. 协议事实的单一出处

`docs/agent-v1-protocol.md` 是唯一取值依据（模型名、base URL 不带 `/v1`、思考模式必须显式 disabled、`finish_reason` 白名单、usage 字段名、峰谷计价、余额接口字段）。AGENTS.md 里那 9 条"DeepSeek 接入硬规则"是它的摘要。代码里的对应实现点：`DeepSeekProperties`（拒绝开启思考）、`DeepSeekRequestFactory`（不发 `stream_options`/`parallel_tool_calls`）、`DeepSeekResponseParser`（不完整即丢）、`FinishReason.isAccepted`。

## 5. 前端：一页对应一条链

| 页面 | 后端 | 该页最需要注意的是 |
|---|---|---|
| `pages/auth/login` | `/api/auth/*` | 探活失败必须留页并说明；`takeSessionExpiredNotice()` 在此消费一次 |
| `pages/index/home`（tab） | `/api/body-records`、`/api/plans/today`、`/api/plans/{id}` | 只显示真实已有指标；四块内容各自独立请求与五态 |
| `pages/plan/list`（tab） | `GET /api/plans` | 排序/筛选改动必须触发重取，且旧请求要按代次作废 |
| `pages/plan/detail` | `/api/plans/{id}/activate\|archive\|discard\|revise\|copy`（都带 `lockVersion`） | 动作失败要在计划日区域之外也可见（零计划日的草稿容易吞掉提示） |
| `pages/plan/edit` | `POST/PUT /api/training-plans` | 版本冲突后**先问再重载**，不能丢用户输入 |
| `pages/plan/import` | `POST /api/plans/import/preview`（无副作用）、`POST /api/plans/import`（确认） | 只有确认那一步带 `Idempotency-Key`；返回状态必须仍是 DRAFT |
| `pages/mine/*` | 档案/目标/体重/模型设置 | 空值显示"未设定"，不显示 0 |
| `pages/coach/chat`（tab） | `/api/coach/**` | 只有一个轮询定时器；重进恢复同一 run；停止原因与费用按契约显示（默认折叠但字段不删） |

⚠ `pages/coach/chat` 与 `pages/plan/list`、`pages/index/home`、`pages/mine/index` 现在是 **tabBar 页**，
跳转必须用 `uni.switchTab`（`navigateTo` 打不开 tab 页），见 `pages/mine/index.vue` 的 `TAB_PAGES` 分流。

## 6. 主题、图标、图表与检查

- `uni.scss` 是色值与尺度权威，`styles/theme.ts` 是给模板属性/canvas 用的 JS 副本，`App.vue` 放全局控件类。
- 图标：几何在 `scripts/gen-icons.mjs`，产物 `styles/icons.scss` + `styles/icon-names.ts`（`npm run gen:icons`）；
  tabBar 的 81×81 PNG 由 `scripts/gen-tabbar.mjs` 用纯 Node 编码器生成（`npm run gen:tabbar`）。
- 图表：只有 `FaTrendChart.vue`（`canvas type="2d"`，真实体重折线）。进度环/训练日历/周报柱状图**没有做**，
  因为阶段 2 的数据不存在（§7）。
- `npm run check:theme` 现在核对 9 项：两处色值一致、`pages.json` 用 token、源码零硬编码色值、
  填充色没被当文字色、对比度达标、`> *` 通配选择器（微信编译器不认）、模板里静态图标名真实存在、
  tabBar 图标存在且 ≤40KB。改颜色/图标/图表跑它，改图标几何还要跑两个 gen 脚本。

## 7. 什么不在这里做

阶段 2（训练执行与统计）没有实现，所以**没有** `workout_*` 表、没有打卡页、没有统计服务，AI 也没有 `get_training_progress` 之类工具——这是明确边界而不是遗漏（`docs/feature-audit-before.md` §4 写了开工要做的事）。饮食识别、RAG、视频、Python、MCP、多 Agent、自动改计划同样不在本轮。
