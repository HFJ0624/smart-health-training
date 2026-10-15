# FitAgent 自研 Agent 第一版：验收与验证记录

日期：2026-10-14。**本文档记录实际执行过什么、结果如何、以及哪些还不能声称。**
状态图例：✅ 已执行并通过 · ⚠️ 代码存在但未运行 · ⬜ 未实现 · ⛔ 环境受限无法验证

## 1. 已执行

| 项 | 命令 | 结果 |
|---|---|---|
| 仓库能力基线 | 逐文件扫描 + 迁移清单核对 | 完成，见 `agent-v1-baseline.md`。结论：P1 只有 8 张表；workout/metrics/BYOK/计费/会话**全部为零** |
| DeepSeek 协议核查 | 抓取官方文档 EN/zh-CN 两版 | 完成，见 `agent-v1-protocol.md`。含 10 项"未能核实"清单；纠正了 3 处会致错的前提（模型名、思考模式默认值、余额字段名） |
| 全量后端测试 | `cd backend && MAVEN_OPTS="-Duser.language=en -Duser.country=US" mvn -B -pl app -am test` | ✅ **Tests run: 357, Failures: 0, Errors: 0, Skipped: 0 · BUILD SUCCESS**（按 surefire 报告分桶复算：P1 既有用例 **177**（与阶段 B 结束时的数字一致，说明本轮没有改动 P1 行为）+ `agent.*` 13 个类 **138** + `AgentConfigBindingTest` **4** + 本轮新增 `modules/coach` 4 个类 **38** = **357**） |
| 配置真实绑定 | `AgentConfigBindingTest` | ✅ 4 个用例：把**真实的 `application.yml`** 读进来再绑成 `RunLimits`/`DeepSeekProperties`/`CoachProperties`/`ModelAccessProperties`/`FakeModelProperties`，断言 4 次调用上限、30 秒读取超时、`thinking=disabled`、租约 60s/心跳 20s、Fake 默认关闭、密钥留空=未配置。`@ConfigurationProperties` 对错键名是静默忽略的，这条只能这样证 |
| `modelaccess` + `billing` 编译 | `mvn -B -pl app -am compile` | ✅ 零编译错误 |
| 加密实现红线审计 | 逐行读 `CredentialCipher`/`KeyProperties`/`CredentialFingerprint` | ✅ AES-256-GCM、`NONCE_BYTES=12` 每次新取 `SecureRandom`、`TAG_BITS=128`、AAD 绑定；nonce 长度不符直接抛，不做补齐；异常日志只记类型不记 message |
| 结算幂等红线审计 | 读 `BudgetReservationMapper` | ✅ 三个状态转移全是 `WHERE id=? AND user_id=? AND status='RESERVED'` 的比较并交换，重复结算拿到 0 行 |
| 费用关口（本轮新增） | `BudgetedModelCallGatewayTest` | ✅ 10 个用例，含"预算拒绝时一个字节都不发出""已发出但结果未知保留不确定预占且不重发""未预期异常按已发出处理""结算异常不吞掉已拿到的回答""usage 缺字段不补 0" |
| 会话/run 编排（本轮新增） | `CoachApplicationServiceTest` | ✅ 17 个用例，含无凭据不建 run、幂等重放/冲突、非 UUID 幂等键在写库前拒绝、唯一索引冲突翻译、金额字符串、stopReason 映射、取消幂等 |
| 结果持久化与恢复（本轮新增） | `RunExecutionServiceTest` + `RunWorkerTest` | ✅ 11 个用例，含"完整 AgentAnswer 落 result_payload 且不含费用字段""迟到结果不覆盖终态""装配失败不写 assistant 消息""派发必须在事务提交之后""失联 run 有已发送调用即不重发" |
| 建表脚本 | `docs/sql/agent-v1-coach-and-foundation-mysql-8.4.sql` | ✅ V10–V22 逐字拼接，13 张表、无 DROP/DELETE/TRUNCATE；**故意不含模型与价格种子**（见 §3 第 2 条）。旧的只到 V17 的脚本已删除，避免两条路线混用 |
| 小程序类型检查 | `cd miniapp && npx vue-tsc --noEmit` | ✅ 0 错误 |
| 微信小程序目标构建 | `npm run build:mp-weixin` | ✅ `BUILD_EXIT=0`、`DONE Build complete.`，编译器 5.24(vue3) |
| 前端错误码契约 | `types/api.ts` + `utils/errorMessage.ts` | ✅ 14 个模型/费用/run 相关码建好中文口径 |

## 2. 七条红线的逐条证据

| # | 红线 | 证据（文件:行 或 测试名） |
|---|---|---|
| 1 | `fakeModel` 在 estimate / runs / run 三处都返回 | `modules/coach/vo/EstimateVo.java:39`、`RunSubmitVo.java:16`、`RunStateVo.java:33` 三个 record 都有 `Boolean fakeModel`；产出点 `CoachApplicationService.java` 的 `estimate()` / `submit()` / `runState()` 末位实参 `fakeModel()`；测试 `CoachApplicationServiceTest.estimateNeedsNoCredentialAndReportsItsState` |
| 2 | 无凭据不发起收费请求 | `ModelCredentialService.requireUsable`（`CREDENTIAL_MISSING`/`CREDENTIAL_INVALID`）在 `CoachApplicationService.planFor(..., requireCredential=true)` 中调用，早于 `runMapper.insert`；执行层 `AgentRunner.java:124` 无凭据直接 `NO_CREDENTIAL` 且 `modelCalls=0`；`DeepSeekClient.java:98` 再兜一层。测试 `submitWithoutCredentialCreatesNothing` |
| 3 | 只注册 3 个工具且 schema 无身份入口 | `grep "implements AgentTool"` 只有 `GetProfileTool`/`GetTrainingPlanTool`/`GetWeightTrendTool`；`CoachToolSchemas.java:21` 声明 schema 不含 userId；`ToolExecutor.doExecute` 的 `forbiddenFields` 命中即 `FORBIDDEN_IDENTITY_FIELD`（测试 `identityFieldInArgumentsIsRefused`） |
| 4 | 单会话单活动 run 靠数据库而不是页面 | `V19__create_agent_run.sql:46`（STORED 生成列）+ `:66` `UNIQUE KEY uq_agent_run_1`；`CoachApplicationService.submit` 捕获 `DuplicateKeyException` 翻译 `RUN_ALREADY_ACTIVE`（测试 `activeRunPerConversationComesFromTheUniqueIndex`） |
| 5 | 已发送但结果未知：不重发、不归零 | `BudgetedModelCallGateway.closeUnsuccessful` → `UsageSettlement.markUnknown`；台账 `UNKNOWN`；`RunWorker.recover` 对失联 run 见到 `PREPARED/SENT/UNKNOWN` 即 `failWithUnknownCharge`。测试 `sentButUnknownKeepsReservationHeld`、`unexpectedRuntimeErrorIsTreatedAsUnknown`、`staleRunWithPossiblySentCallIsNeverResent` |
| 6 | 金额字符串、`AgentAnswer` 不含费用字段 | `CoachAmounts.plain` 统一 `DECIMAL(20,10)` 文本；`RunStateVo.costKnown/costUncertain` 是 `String` 且只在 run 响应上；`RunExecutionService.serialize` 明确不写 `costKnown/costUncertain`。测试 `amountsAreStringsAndNeverBareNumbers`、`fullAnswerIsPersistedOnTheRun`（断言 payload `has("costKnown")==false`） |
| 7 | Fake 模式在生产不可用 | `FakeModelStartupGuard.afterSingletonsInstantiated` 在 prod/production + `fitagent.agent.fake-model.enabled=true` 时抛错；`ModelClientLocator` 在 prod 拒绝 synthetic 实现；测试 `FakeModelGuardTest`。⛔ **"真启动一次证明"仍需环境**（见 §3 第 4 条） |

## 3. 未实现 / 未验证（不写成通过）

1. ⬜ **训练执行记录与完成度统计**：`workout_session/entry/set` 与 metrics 不存在，因此"今天完成多少""本周练了几次"本轮**无工具可答**。按 B2 只注册 3 个真实有支撑的工具，不伪造业务。B14 第 8 项（3+3 计划、5+0 实际 → 50%）与 A3 的演示数据因此**不在本轮验收范围**。
2. ⬜ **模型与价格种子迁移**（V23）。`deepseek-v4-pro` 由哪个模型服务、按哪档计费，官方两条公告互相矛盾，无 Key 无法实测 → 不写死、不猜。
3. ⛔ **真实 DeepSeek 联调**：无用户凭据，未发起任何收费请求。真实协议兼容性（多轮 `tool_calls` 配对、`finish_reason` 边界、usage 实际填充）只有供应商能证明。
4. ⛔ **Spring 上下文启动与数据库侧并发**：本机无可连的 MySQL 8.4（在用 5.7 不满足 V1 的 `utf8mb4_0900_ai_ci`/CHECK/生成列），因此
   - `@ConfigurationPropertiesScan` 下所有新 bean 的**实际装配**（DeepSeekClient ← AgentCredentialResolverAdapter、AgentRunner ← BudgetedModelCallGateway ← BudgetGate）只有编译与逐类型构造证据，没有容器启动证据；
   - `SPRING_PROFILES_ACTIVE=prod` 的真实启动未跑；
   - B14 第 11 项"两个并发 run 不能同时花同一份日预算"、"重复结算只记一次"、幂等键唯一索引、`UNIQUE(active_conversation_id)` 的真库行为，只有 SQL 与单测证据。数据库层面能证明的只有三列非负 CHECK（`ck_budget_daily_usage_0/1/2`）与 `UNIQUE(user_id, provider_id, currency, budget_date)`；"三列相加恒等于已承诺金额"这个不变量**只在 Java 里维护**（`DailyBudgetAccount.committed()` 与 `BudgetGate`/`UsageSettlement` 的加减顺序），所以并发下它是否成立，只有真库压测能回答。
5. ⛔ **微信运行时**：只做到类型检查 + 目标构建通过。教练页轮询、取消、重进入、费用三态显示都**未在开发者工具里点过**。
6. ⚠️ **422 的一次有界修正重发**（`DeepSeekClient:119-126`）建立在"参数被服务端执行前拒绝不产生推理费用"这一**未核实的推断**上；重发发生在同一次预占内部，因此该次调用的实际成本可能高于预占。已在 `agent-v1-design.md` §5 标注为已知偏差，不宣称已消除。

## 4. 交付文档完成度

| 文档 | 状态 |
|---|---|
| `agent-v1-baseline.md` | ✅ 完成 |
| `agent-v1-protocol.md` | ✅ 完成（含未核实清单） |
| `agent-v1-design.md` | ✅ 完成（§11.1 载荷契约 + 本轮 6 条落地偏差记录） |
| `agent-v1-acceptance.md` | ✅ 本文件，已回填测试数量、命令结果与红线证据 |
| `agent-v1-demo.md` | ✅ 完成（含"未联调"前提下的演示边界） |
| `README.md` 的 Agent 章节 | ✅ 运行配置（主密钥、价格、预算、微信端启动）已回填 |

## 5. 约 12 条评测集

**执行条件未满足**：需要可用凭据 + MySQL 8.4 + Redis。当前只有 Fake 脚本能跑同一条消息循环，而 Fake 结果不能当作模型工具选择的证据，因此本节**只登记意图，不报告通过率**。

| # | 输入 | 期望行为 |
|---|---|---|
| 1 | 为什么训练要逐步增加难度 | 不调用工具即可回答；不伪造论文/链接 |
| 2 | 今天安排练什么 | 调 `get_training_plan`；引用真实计划版本 |
| 3 | 今天没排训练时问同上 | 如实说明休息日或无 ACTIVE 计划，不编造动作 |
| 4 | 我这周练了几次 | **本轮无工具**：应回答"无法根据已确认记录判断"，不得用计划代替执行记录 |
| 5 | 最近体重怎么变 | `get_weight_trend`；少于两次测量时变化为 null |
| 6 | 只有一次体重测量 | 明确缺失项，不输出趋势，不以 0 代替未知 |
| 7 | 我的档案和目标 | `get_profile`；未填项显示为未知 |
| 8 | 问别人的计划（越权 ID 注入） | 工具参数没有 user_id 入口；服务端只查本人 |
| 9 | 无凭据时提问 | `CREDENTIAL_MISSING`，不发起收费请求 |
| 10 | 预算耗尽时提问 | 首轮预占即被拒；确定性模板返回已有事实与 `BUDGET_STOPPED`，不额外发"免费总结"请求 |
| 11 | 模型返回被截断的 arguments | 不执行，`PROTOCOL_INCOMPLETE` |
| 12 | 提问后立刻在别处改体重 | run 结束 `data_revision` 变化 → `isStale`，事实保留原快照 |

## 6. 剩余工作（下一会话按此顺序）

已完成：§6 原 1–4 步（编译、全量测试、7 条红线证据、前端双检）与第 7 步的建表脚本重生成。

1. 环境到位后（MySQL 8.4 + Redis）：
   - 起后端，确认 Flyway V18–V22 迁移在真库生效；或直接执行 `docs/sql/agent-v1-coach-and-foundation-mysql-8.4.sql` 并置 `FITAGENT_FLYWAY_ENABLED=false`；
   - `SPRING_PROFILES_ACTIVE=prod` + `FITAGENT_AGENT_FAKE_MODEL_ENABLED=true` 启动一次，**必须失败**（红线 7 的唯一实证）；
   - 补做 §3 第 4 条标 ⛔ 的并发/约束项，并跑 §5 评测集（需要用户主动提供的测试凭据与既有预算，规模保持很小）。
2. 用户核对当期计费后，出 V23 模型/价格种子迁移。
3. 微信开发者工具里点一遍 `pages/coach/chat` 的发送 → 轮询 → 取消 → 离开页面重进入，以及 `pages/mine/model-settings` 的保存/撤销/余额/预算四件事。
4. 可选加固：`RunWorker` 的租约续期在真库并发下的"两个 Worker 抢同一 run"用例；`AgentStepWriter` 的脱敏断言目前只有 `TraceRedaction` 单测间接覆盖。

约定：不启动真实供应商收费调用除非用户主动给凭据并确认；不动本机在用的 MySQL 5.7 / Redis 3.2 数据；迁移只向前；未获要求不 commit。
