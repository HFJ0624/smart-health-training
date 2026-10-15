# FitAgent 功能核查矩阵（修复前快照）

日期：2026-10-14。核查方式：逐页读 `miniapp/src` + 逐类读 `backend/app/src` + 跑 `mvn test`、`vue-tsc`、`build:mp-weixin`、`npm run check:theme`，并派三个只读子代理分别精读"我的/登录""计划/导入""教练/模型设置"三组页面。

**本文件记录的是"修之前是什么样"**，修复结果与逐项验证见 `feature-audit-after.md`。状态图例：
`V` 已实现且实际验证 · `C` 已有代码但未实际验证 · `B` 已实现但行为有误 · `M` 应实现但缺失 · `F` 仅占位/假成功 · `E` 环境受限无法验证 · `N` 阶段尚未实施

## 1. 阶段基线与授权边界（先说清哪些不该按"漏项"算）

| 阶段 | 是否授权交付 | 依据 | 结论 |
|---|---|---|---|
| 1 基础功能（登录/档案/身高体重/计划/JSON 导入） | 是 | `docs/p1-scope.md`、提示词"阶段 B"、AGENTS.md 阶段边界 | 本轮逐项核查，见 §2 |
| 2 训练执行与统计（打卡、逐组记录、计划对比、周统计） | **从未授权开发** | `AGENTS.md`「仍然不做」明文列出"训练执行记录与完成度统计"；迁移清单里 21 张表**没有任何** `workout_session/entry/set` 或 metrics 表（`backend/app/src/main/resources/db/migration/V1..V22`）；`pages.json` 14 个路由里没有训练记录页 | 判为 **N 阶段尚未实施**，按提示词要求单列（§4），不在本轮铺开 |
| 3 自研 Agent 第一版 | 是 | `Codex_自研Agent第一版…提示词.md` B 部分 + 用户"按 B 部分执行" | 本轮逐项核查，见 §3 |

提示词 §1 明确"不得仅凭没找到代码就把应交付功能归为未来规划"，也明确"某整阶段若从未开始，单独列为阶段尚未实施"。阶段 2 属于后者：它不是被漏掉，而是被明确排除过（用户在 Agent 首轮就选过"按 B2 只做 3 个工具"）。**但阶段 2 的缺失会连带影响阶段 3 的验收口径**，这一点在 §3 里逐项标注，不当成 Agent 的缺陷，也不替它遮掩。

## 2. 阶段 1：基础功能

| 功能 | 预期行为 | 页面/路由 | API 与关键代码 | 数据与约束 | 状态 | 验证证据 | 缺口与优先级 |
|---|---|---|---|---|---|---|---|
| 微信登录自动注册 | 用 `jscode2session` 换 openid，首登建账户，重复登录复用 | `pages/auth/login` | `api/auth.ts` → `modules/auth/WechatLoginService` | `UNIQUE(user_id)`+`wechat_*` 为 `utf8mb4_bin` | V | `WechatLoginServiceTest`、`AccountProvisioningServiceTest`（用例名含"重复登录复用账户"） | 真微信联调 **E**：本机无 AppSecret |
| 开发 Mock 登录 | 与真实登录明确区分，prod 不可用 | 同页 `devBuild` 分支 | `DevLoginService`、`AuthRoutes`、`ProductionHardeningValidator` | `dev-login.enabled=false` 默认 | V | `DevLoginServiceTest`；构建期校验路由不注册 | 真机不可用属预期 |
| 会话令牌与过期 | 401 集中处理、清令牌、回登录页并**说明原因** | `utils/request.ts` → login 页 | `AccountSessionService`（Redis `fa:sess:{sha256}`） | 滑动续期 | **B** | `utils/storage.ts:25-33` `markSessionExpired` 有写入；**全项目零调用** `takeSessionExpiredNotice`；`request.ts:189-202` 先 `reLaunch` | P1：用户被静默送回登录页，看不到"为什么"。各页 `state='sessionExpired'` 分支（`mine/index.vue:170`、`profile.vue:254`、`goal.vue:358`、`records.vue:426`、`StateBlock.vue:4-7`）基本渲染不到 |
| 档案保存/回显 | 刷新后与服务端一致 | `pages/mine/profile` | `api/profile.ts` → `UserProfileService` | `UNIQUE(user_id)` | V | `UserProfileServiceTest`（"读取条件始终带调用者的 user_id"） | P2：`POST` 无幂等键，靠 409 兜；"放弃本地修改并重读"无二次确认（`profile.vue:135`） |
| 目标管理 | 至多一个 ACTIVE，历史可查 | `pages/mine/goal` | `UserGoalService.create/activate/archive` | `UNIQUE(active_user_id)` 生成列 | V | `UserGoalServiceTest`、`GeneratedColumnGuardTest` | P2：新建无幂等（`goal.vue:505`）、删除不带 lockVersion（`goal.vue:635`）；busy 时"重新读取"静默无反馈（`goal.vue:374`） |
| 体重新增/编辑/删除/历史 | 最新体重按 `measured_at` 判定，不把未知显示为 0 | `pages/mine/body/records` | `BodyMeasurementService`；幂等键 `clientRecordUuid` | `CHECK` 单位/正数 | V | `BodyMeasurementServiceTest`（"越权访问他人记录 → 404 同形"） | P2：取消表单丢内容无确认（`records.vue:273`）；busy 重载静默（`records.vue:441`） |
| 分页元信息 | 服务端没给 total 时不能显示 0 | 各列表页 | `utils/request.ts:336-343` `pageMeta` | — | **B** | `total: page?.total ?? 0` → 页面渲染"共 0 条""已到最后一页（共 0 条）"（`goal.vue:125,127`、`records.vue:136,138`、`exercises.vue:39`） | P1：把"未知"显示成确定的 0，属于假数据。`hasNext ?? false` 保守可接受 |
| 计划列表/筛选/排序 | 改筛选或排序立即重取，不串页 | `pages/plan/list` | `TrainingPlanService.list` | 排序白名单 | **B** | `onSortChange` 只改 `sortIndex` 不调 `reload()`（`list.vue:201-205`）；`load()` 首行 `if (loading.value) return` 丢连续触发（`list.vue:219`） | P1：排序控件"标签变了、列表没变"；慢回的旧请求会 concat 进新结果 |
| 计划详情与状态动作 | 生效/归档/丢弃真实调后端，失败可见 | `pages/plan/detail` | `api/training.ts:48-81`；`lockVersion` 随动作提交 | 迁移矩阵 + 生成列 | **B** | 动作调用与版本号是真的（`detail.vue:239,249,265`）；但失败提示绑在计划日 `StateBlock` 的 `submit-failure` 上，零计划日走 empty 分支不渲染默认插槽（`StateBlock.vue:36-40`）→ 409 完全看不见；已有 plan 时刷新失败也无提示 | P1：出错时看起来像"点了没反应"，用户会重复点 |
| 计划编辑/复制/另存新草稿 | 不覆盖已确认版本；连点不产生两份草稿 | `pages/plan/edit` | `revisePlan`、`createPlan`（`POST /plans`） | `revisionNo=max+1`、`basePlanId` | **B** | 版本语义正确；但 `reviseToDraft/doRevise` 无忙碌标志（`edit.vue:743-764`），`createPlan` 无幂等键（`api/training.ts:43-45`），`VERSION_CONFLICT` 后自动 `loadPlan()` 覆盖用户未提交编辑（`edit.vue:663-666`） | **P0（数据破坏类）**：冲突后静默丢弃用户输入；P1：连点生成多份草稿 |
| 休息日开关 | 切换生效，取消后不留在错误视觉 | `edit.vue:67` + `onRestChange` | `PlanAggregateValidator` | 休息日不得有动作 | V/B | 事件解析正确（`utils/events.ts:21-23`，与 `model-settings.vue:448` 同法）；但取消弹窗后 `:checked` 不重绘 | P2：视觉与实际不一致 |
| JSON 导入全链路 | 选文件/粘贴 → preview → confirm → DRAFT；错误定位字段 | `pages/plan/import` | `previewPlan`、`confirmPlanImport` + `Idempotency-Key` | 字节/条数上限 | V/B | 每步真走后端、重试复用同键、失败不残留（`import.vue:198-224,250-272`）；但"从会话选择文件"只 CSS 禁用、`onChooseFile` 无守卫（`import.vue:275+`），confirming 时可"清空重填"，且未校验返回 `status==='DRAFT'` | P1：叠回调/中途清空；契约承诺的"只出 DRAFT"没有前端兜底 |
| 动作字典浏览 | 器械/分类筛选真实生效 | `pages/plan/exercises` | `GET /api/exercises` | 只读字典 | **B** | 器械 picker 用 `inputValue` 读 `detail.value`（number）→ 恒得 `''`→ 索引 0 → 永远"全部器械"（`exercises.vue:132-136`，对照 `ExercisePicker.vue:188-193` 的写法） | P1：死控件，筛选形同不存在 |
| 跨用户隔离 | A 读不到也改不了 B 的任何数据 | 全部接口 | `common/security` + 每条 SQL 带 `user_id` | 复合 `(id,user_id)` 外键 | V | `UserProfileServiceTest:226,243`、`BodyMeasurementServiceTest:310`、`UserGoalServiceTest:298`、`TrainingPlanServiceStateMachineTest:152,262`（越权与不存在同形 404） | 真库外键行为 **E** |
| 长内容不溢出 | 窄屏长动作名/备注不撑破卡片 | 各页 | `word-break` 分布不均 | — | **B** | 全项目零 `line-clamp`；`PlanDayCard`/`ImportPreviewList`/`ExercisePicker` 的长文本行无换行约束；`isoWeekday=null` 显示"星期0"（`types/training.ts:39-41`） | P2 |

## 3. 阶段 3：自研 Agent、BYOK 与预算

| 功能 | 预期行为 | 关键代码 | 状态 | 验证证据 | 缺口 |
|---|---|---|---|---|---|
| 工具注册与参数边界 | 只注册 3 个有真实业务支撑的工具，schema 无 `user_id` | `modules/coach/tools/*`、`CoachToolSchemas.java:21`、`ToolExecutor.doExecute` | V | `identityFieldInArgumentsIsRefused`；`grep implements AgentTool` = 3 个类 | — |
| 多轮工具循环与配对 | 一轮多调用各自配对；未知工具不执行仍配对 | `agent/core/AgentRunner.java:215-245` | V | `AgentRunnerLoopTest`（23 例）含 `everyDeclaredToolCallGetsExactlyOneToolMessage`、`reuseAndRepetition` | — |
| 停止条件 | 次数/工具/单轮/字节/截止/重复都能停 | `RunLimits`、`StopReason` | V/B | 同一测试类；但"收尾调用仍返回 tool_calls"原实现把整轮丢掉导致 `LOOP_EXHAUSTED` 不可达 | 本轮已按契约改为补齐配对后以 `LOOP_EXHAUSTED` 结束（design §4） |
| `finish_reason` 白名单 | 截断/内容过滤/资源不足不执行 arguments | `DeepSeekResponseParser`、`FinishReason.isAccepted` | V | `DeepSeekWireFormatTest` 17 例、`incompleteToolCallsAreDropped` | 真实供应商返回 **E** |
| 结果限长与完整性 | 先保指标/来源/缺失标记再裁明细，裁剪不冒充全量 | `ToolExecutor.buildPayload`/`CappedPayload` | V | `AgentRunnerLoopTest` 截断用例断言 `dataComplete=false` | — |
| 事实与引用 | 个人数字由服务端填，非法引用丢弃并计入缺失 | `AnswerAssembler`、`CitationValidator`、`EvidenceRegistry` | V | `AnswerAssemblerTest` | 自由文本语义正确性不宣称覆盖（design §9 边界） |
| BYOK 加密/脱敏/撤销 | 每次新 nonce、AAD 绑定、永不回显明文 | `modules/modelaccess/crypto/CredentialCipher` + `AgentCredentialResolverAdapter` | V/C | 逐行审计 + `CredentialCipher` 测试；401 后 `markInvalid` | 真库读写 **E** |
| 余额快照 | 失败不清零，显示上次快照+stale | `BalanceSnapshotService`、`model-settings.vue:110-142` | V/C | 单测 + 页面读取口径 | 真实 `/user/balance` **E** |
| 预算预占/结算 | 短事务预占 → HTTP 在锁外 → 幂等结算 → 未知保留 | `BudgetGate`、`UsageSettlement`、`BudgetedModelCallGateway` | V/C | `BudgetedModelCallGatewayTest` 10 例（含"拒绝时一个字节都不发出""已发出用量不明保留预占""未预期异常按已发出处理"） | 并发两 run 抢同日额度 **E** |
| 会话/run 持久化与恢复 | 完整结果落 `result_payload`；重启按恢复规则 | `RunExecutionService`、`RunWorker.recover` | V/C | `RunExecutionServiceTest` 6 例、`RunWorkerTest` 5 例（含"有已发送调用即不重发"） | 租约/接管真库并发 **E** |
| 取消幂等 | QUEUED 直转终态，RUNNING 打标记；终态不可取消 | `CoachApplicationService.cancel`、`AgentRunner` 每轮检查 | V | `cancelIsIdempotentOnlyForCancelledRuns`、`cancelWhileRunningOnlyMarksAndSignals` | — |
| 无凭据不花钱 | 提交与执行双重拒绝 | `requireUsable` + `AgentRunner.java:124` | V | `submitWithoutCredentialCreatesNothing` | — |
| 前端 run 闭环 | 预估→提交→单定时器轮询→结果/来源/费用→取消→重进恢复 | `pages/coach/chat.vue` | V/B | 轮询单定时器与退避（`chat.vue:800-890`）、恢复不重发（`chat.vue:722-768`）都成立；但 run 顶层 `errorCode` 从不显示（`types/coach.ts` 未声明该字段）、`fakeModel` 只在 estimate 类型上声明、`STOP_REASON_LABELS` 与契约 11 个取值不对齐（含 5 个契约里不存在的键）→ 成功与多种停止态原样吐机器码；停止态只有一行文字、无下一步入口；余额空态没有重试按钮（按钮在默认插槽，empty 分支不渲染） | P1/P2 |
| AI 能力边界诚实性 | 无记录就说"暂无已确认记录"，不编造 | 模板 `resources/prompts/coach-system-v1.md`、`CoachPromptTemplatesTest` | V | 用例"模板包含六条硬规则…未知即未知" | "今天完成多少/本周几次"**无工具可答**（阶段 2 未实施），提示词 B14 第 8 项与 A3 的 50% 演示因此 **M/N** |
| 真实供应商联调 | — | `DeepSeekClient` + `JdkHttpTransport` | **E** | 未发起任何收费请求（无用户凭据） | 只有 stub 与 Fake 证据，不能宣称协议兼容已验证 |

## 4. 阶段 2 未实施：依据与后续工作

**判据**：`docs/../AGENTS.md` 阶段边界把它列入"仍然不做"；迁移里没有那三张表；前端没有入口页；用户在本轮之前明确选择"按 B2 只做 3 个工具"。所以它不是漏项，而是未开工。

**要做时的工作**（提示词 §4 的清单即验收口径）：`workout_session` / `workout_session_entry` / `workout_session_set` 三张表 + 草稿/确认状态、逐组实际值、补录与编辑删除、计划对比与周统计（**逐动作封顶**后汇总，5+0 对 3+3 得 50% 且额外 2 组单列）、未确认草稿不进统计、场次与天数区分、规则版本与缺失标记。接入打卡后 P1 的"删旧插新"必须改成增量更新（AGENTS.md 已标 ⚠，因为届时 `workout_session` 会引用计划日）。统计服务落地后才能注册 `get_workout_records` / `get_training_progress` / `get_weekly_training_summary`。

**顺带不能做**：热量公式、训练效果评分、恢复评分、医学阈值——提示词 §4 与 AGENTS.md 同口径。

## 5. 优先级汇总（本轮处理顺序）

- **P0**：计划编辑遇版本冲突静默覆盖用户未提交内容（数据丢失）。
- **P1**：会话失效无解释；排序控件不触发重取；详情页动作失败不可见；连点生成多份草稿；器械筛选死控件；分页 total 显示假 0；导入可叠回调/中途清空；教练页 `errorCode` 不显示、`stopReason` 映射与契约不对齐、余额空态无重试。
- **P2**：淡黄主题未落地（原为绿色系）、色值散落 15 处、填充色被当文字色 5 处、选中态只靠颜色、长文本无换行、"星期0"、目标缺失时整块空白、破坏性表单操作无确认、busy 时按钮静默、注释与实际不一致处（如读超时 90s 的旧注释）。
- **E**：真实供应商、真库并发/约束、微信开发者工具里的可视检查、prod 启动实证。
