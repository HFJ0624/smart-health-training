# 阶段 2 验收：训练执行记录、基础统计与 Agent 查询

生成时间：2026-10-16 16:30（本地）。分支 `main`，基线提交 `348961c`（本轮改动**尚未提交**，因此本文件描述的是当前工作区，不是某个已合并版本）。

范围与取舍的权威文档是 [docs/stage2-design.md](stage2-design.md)；本文件只记录**实际执行过什么、结果如何、哪些没验证**。

---

## 1. 执行环境

| 项 | 实测值 | 怎么确认的 |
|---|---|---|
| JDK | Java 17.0.10（`D:\software\Java\jdk17`） | `mvn -v` 与 `java -version` |
| Spring Boot | 4.1.1（仓库锁定版本，本轮未改依赖） | [backend/app/pom.xml](../backend/app/pom.xml) |
| MyBatis-Plus | 3.5.17 + `mybatis-plus-spring-boot4-starter` | 同上 |
| Maven 平台编码 | GBK（因此所有 `mvn` 命令前带 `MAVEN_OPTS=-Duser.language=en -Duser.country=US`） | 本轮实际执行 |
| 本机 MySQL | **5.7，不满足本项目要求**（迁移用 CHECK / STORED 生成列 / `utf8mb4_0900_ai_ci` 都是 8.x 特性） | [AGENTS.md](../AGENTS.md)「语言与运行时」；本轮未拿它验证任何东西 |
| Docker | 无 | `docker` 命令不存在 |
| 真库 | 用户自己的服务器上另有 MySQL 8.4 / Redis；**本轮按「先按无真库交付」执行**，DDL 只交付脚本，不由我在其服务器上执行 | 用户 2026-10-16 的决定 |

结论：所有**依赖真实 MySQL 8.x 行为**的验收项（外键、CHECK、STORED 生成列、条件唯一闸门、并发）**在本轮没有证据**，只在 §4 里给出可复现步骤。这是交付边界，不是通过。

---

## 2. 本轮实际跑过的验证

### 2.1 后端全量测试

```
cd backend
MAVEN_OPTS="-Duser.language=en -Duser.country=US" mvn -B -pl app test
```

| 时间 | 结果 |
|---|---|
| 2026-10-16 16:06 | `Tests run: 421, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`（Total time 30.075 s） |
| 2026-10-16 16:31 | 追加 §2.4 那条循环用例后全量重跑：`Tests run: 422, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`（Total time 31.764 s） |

命令与数字原样来自 `mvn` 输出，不是估算。`Skipped: 0` 的含义要说清：默认 profile 用 `excludedGroups=requires-docker` **根本不收集**那些用例（[backend/app/pom.xml:125-146](../backend/app/pom.xml#L125)），所以"0 skipped"不等于"集成测试也过了"，而是"集成测试没参与这次运行"。

### 2.2 阶段 2 新增/改动的测试类（16:06 那次运行的分项）

| 测试类 | 用例 | 钉住什么 |
|---|---|---|
| `modules/workout/WorkoutServiceTest` | 21 | 幂等开始/恢复、单草稿闸门、归属、聚合保存的子项删除顺序、结束幂等、丢弃、删除、乐观锁、参考校验（拒 DRAFT 计划、星期匹配、区间） |
| `modules/metrics/TrainingMetricsServiceTest` | 21 | 封顶口径与全部原因码；**必过示例**：计划 A/B 各 3 组、实际确认 A=5 B=0 → `50.00%` 且额外 2 组（`:65-86`）；同条目跨场次先汇总再封顶；历时与动作时长绝不相加（`:261-262`） |
| `modules/metrics/controller/MetricsControllerTest` | 5 | 日期文本解析、`INVALID_DATE_FORMAT` 字段错误、身份只取认证上下文 |
| `modules/metrics/MetricsBoundaryGuardTest` | 4 | 整个 metrics 包只有一个被批准的只读小件碰别人的 Mapper；出参不含实体类型 |
| `modules/metrics/support/MetricsDataRevisionReaderTest` | 3 | 修订号读不到时按未知处理，不猜 |
| `modules/coach/tools/TrainingQueryToolsTest` | 7 | 两个新工具的契约、周工具**结构上不存在百分比字段**（反射断言）、白名单 5 个、**新工具经过 `AgentRunner` 循环**（§2.4） |
| `FakeModelSwitchBindingTest` | 3 | 环境变量 `FITAGENT_AGENT_FAKE_MODEL_ENABLED` 的真实绑定与 prod 保护 |
| `AgentConfigBindingTest` | 原有 | 宿主环境污染修复：`@BeforeAll` 移除 `systemEnvironment`/`systemProperties`，默认值断言不再被本机 `infra/.env` 影响 |

### 2.3 前端三道门禁（**16:40–16:44 重跑**，全部实际执行）

> ⚠ 为什么是重跑：本文件初稿记的是 16:24–16:29 那一次，但之后后台代理又改写了 `pages/workout/history.vue`、`pages/workout/stats.vue`（16:27–16:28）与 `pages/plan/list.vue`、`record.vue`（16:20）。旧数字描述的不是当前文件，一律以本节为准。`record.vue` 里我加的按 ID 打开、只读态与丢弃/删除三处经复核仍在（`:412`、`:491`、`:976`、`:990`）。

```
cd miniapp
npx vue-tsc --noEmit        → EXIT=0（0 错误）
npm run check:theme         → ✓ 主题 token、导航配色、硬编码色值、对比度、图标名、> * 扫描、tabBar 体积全部通过
npm run build:mp-weixin     → DONE Build complete.（且构建告警已清零，见 §2.3.1）
```

构建产物核对（用 Python 读 `dist/build/mp-weixin/app.json`）：17 个页面，其中 `pages/workout/record`、`pages/workout/history`、`pages/workout/stats` 三张新页面均已产出 `.js/.json/.wxml/.wxss`；`tabBar` 仍是首页/训练/教练/我的四项。

#### 2.3.1 顺手修掉的一个真实构建缺陷（不在原任务清单里）

`pages/plan/import.vue` 的构建日志里有一条 **`条件编译失败: #ifdef/#ifndef 缺少配对的 #endif`**。两处**注释里复述了条件编译指令的原文**（`:115` 与 `:389`），uni-app 的预处理器按纯文本扫描，把注释里的孤立指令当真指令配对，于是整个文件的分支裁剪按不完整处理。真实指令 `:393/#ifdef → :459/#endif`、`:461/#ifndef → :467/#endif` 本身是配对的。

这不是"日志难看"：分支判定不完整时，被裁掉的可能是微信选文件那一整段（`wx.chooseMessageFile` + `readFile`），而 `vue-tsc` 与 `check:theme` 都照绿——只有构建日志说话。修法是把两处注释改成不含指令原文的描述，并把这条约束写在注释里。

修复后复核：告警计数 **0**；`dist/build/mp-weixin/pages/plan/import.js` 里 `chooseMessageFile` 仍在（分支没被裁走）；`vue-tsc` 与 `check:theme` 重跑仍全绿。

### 2.4 循环调用证据（本轮补上的那条）

`TrainingQueryToolsTest.progressToolRunsInsideTheLoop`：脚本化 `FakeModelClient` 第一轮返回 `tool_calls`（`get_training_progress`），第二轮返回文本；断言

- 两次模型调用**都经过** `ScriptedGateway`（即费用网关那一层，一次都没绕过）；
- 第二轮上下文里 `assistant(tool_calls)` 与 `tool` 消息恰好各 1 条，且 `tool_call_id` 同为 `call-p`（原样回传，不是重编）；
- 回传给模型的文本里 `50.00`、`workout-completion-v1`、`evidenceId` 与**本 run 那条证据的真实 ID** 原样出现——数字从 metrics 服务到模型上下文没有中间改写点；
- `verify(metricsService).day(USER_ID, 2026-10-16)` 成立：统计真的被读到。

「模型引用证据 → 生成事实卡 → 卡片取工具值而非模型口述值」这一段不在这条用例里重复实现，由既有的 `agent/AnswerAssemblerTest` 钉住（其中 `modelSuppliedNumberNeverOverridesToolData` 就是这条规则）。

---

## 3. 交付了什么（可核对的清单）

### 3.1 数据层

新增迁移 [V23__create_workout_session.sql](../backend/app/src/main/resources/db/migration/V23__create_workout_session.sql)、`V24__create_workout_session_entry.sql`、`V25__create_workout_session_set.sql`。三表都带 `user_id` 冗余列 + `(id,user_id)` 复合外键做父子归属；`workout_session` 用 `active_user_id` STORED 生成列 + `UNIQUE` 实现"同一用户至多一条进行中草稿"的闸门，**实体类里没有 `activeUserId` 属性**（由 `GeneratedColumnGuardTest` 双向守住）。

给 MySQL 8.4 的单文件 DDL 与 9 条真库探针在 [docs/sql/stage2-workout-mysql-8.4.sql](sql/stage2-workout-mysql-8.4.sql)。**我没有在任何数据库上执行过它**——它是需要你自己跑的交付物。

### 3.2 后端模块

- `modules/workout/`：`WorkoutService`（开始/恢复/列表/详情/保存/结束/丢弃/删除）、`WorkoutController`、3 实体 + 3 Mapper + DTO/VO。
- `modules/metrics/`：`TrainingMetricsService`（当天对比 + 周统计）、`MetricsRules`（口径唯一常量表）、`MetricsController`、出参 `MetricsVos`。**只读**，不建统计表，不调模型。
- `modules/coach/tools/`：`GetTrainingProgressTool`、`GetWeeklyTrainingSummaryTool`；`CoachToolNames.ALL` 从 3 个变 5 个，白名单由它单点定义（不再有任何地方硬编码"三个工具"）。
- `modules/training/ExerciseService`：新增 `mapByIds` 批量读，供 workout 侧解析动作名，不跨模块拿别人的实体。

依赖方向守住：workout → training；metrics → training/workout（经 Service，唯一豁免是批准的只读修订号小件）；coach 工具 → metrics Service。业务模块都不依赖 `AgentRunner`。

### 3.3 小程序

新增 [types/workout.ts](../miniapp/src/types/workout.ts)、[api/workout.ts](../miniapp/src/api/workout.ts)、[types/metrics.ts](../miniapp/src/types/metrics.ts)、[api/metrics.ts](../miniapp/src/api/metrics.ts)、[pages/workout/record.vue](../miniapp/src/pages/workout/record.vue)、[pages/workout/history.vue](../miniapp/src/pages/workout/history.vue)、[pages/workout/stats.vue](../miniapp/src/pages/workout/stats.vue)、[components/FaProgressRing.vue](../miniapp/src/components/FaProgressRing.vue)、[components/FaTrainingCalendar.vue](../miniapp/src/components/FaTrainingCalendar.vue)。

改动的入口：训练 tab（[pages/plan/list.vue](../miniapp/src/pages/plan/list.vue)）加了「计划 / 记录 / 统计」二级结构；教练页快捷提问加两条（今日完成、本周统计）并更正了"本轮无工具可答"那段旧说明；首页四宫格的「导入」已在上一片换成「训练」。

页面上**没有任何写死的训练数据或比例**：完成度环只在服务端给出 `SET_COMPLETION_PCT` 且值非 null 时才画；周层面只有场次/天数/组数/时长四类绝对量，组件里不存在百分比这条路径。

---

## 4. 未验证项（本轮交付边界，逐条给复现方式）

| # | 未验证内容 | 为什么 | 在你这边怎么验证 |
|---|---|---|---|
| U1 | V23–V25 在真实 MySQL 8.4 上可执行；外键、CHECK、生成列、条件唯一闸门真的生效 | 本机只有 MySQL 5.7 且没有 Docker；我没有碰你的服务器 | 先跑 [docs/sql/stage2-workout-mysql-8.4.sql](sql/stage2-workout-mysql-8.4.sql) 的 pre-check，再执行 DDL，最后跑文件里 P1–P9 九条探针；或用 `mvn -B -pl app test -P with-docker` 让 `@Tag("requires-docker")` 用例参与运行 |
| U2 | 空库从 V1 一路迁移到 V25 可重复初始化 | 同 U1（Flyway 需要 8.x） | 在一个**新建的空库**上把 `spring.flyway.enabled=true` 起一次后端，确认 `flyway_schema_history` 有 25 行且无失败 |
| U3 | 并发开始只留一条草稿（两个请求同时打进来） | 需要真库的唯一索引 + 真实并发 | U1 之后用两个终端各发一次 `POST /api/workout/sessions`（不同 `clientRecordUuid`），预期一个 201、一个 200 且返回同一 `sessionId` |
| U4 | 微信真机登录端到端 | 缺 AppSecret（只应存在于你本地 `infra/.env`），且需要你的手机与网络可达 | 你把 `FITAGENT_WECHAT_SECRET` 填进 `infra/.env` 并重启后端后，在真机点「微信登录」；**在此之前 dev-a/dev-b 的通过不能当成微信联调** |
| U5 | 真实 DeepSeek 联调、真实计价与预算结算 | 你未主动提供测试 Key 与预算，我不发收费请求 | 在「我的 · 模型设置」存一把 Key，问一句「今天练得怎么样」，核对该 run 的 `model_call` 台账与预估/实际费用 |
| U6 | 页面在微信开发者工具/真机上的实际渲染与点击流（开始→保存→恢复→结束→历史→统计→教练来源） | 本轮我没有驱动过 DevTools 会话，只有类型检查与构建产物 | 按 [docs/run-and-test.md §3.1](run-and-test.md) 的阶段 2 点击项 21–28 逐条走一遍；`npm run dev:mp-weixin` 后导入 `dist/dev/mp-weixin` |
| U7 | 图表（进度环、训练日历、趋势图）在设备上的绘制效果 | canvas `type="2d"` 只有真机/DevTools 能验 | U6 时重点看：单日/零单日进度环是否**不出现**、日历「未记」格与已确认格是否可分 |
| U8 | 现有手工库的接管边界 | 我没有读你服务器上的 `flyway_schema_history`，也不能替你 baseline | 见 [docs/run-and-test.md §5](run-and-test.md)：先结构比对再决定，不要打开 `baseline-on-migrate` |

另外两处**已知的实现取舍**，不是缺陷但要知道：`TrainingMetricsService` 每场次调一次 `detail()`（换来 metrics 完全不碰 workout 的 Mapper，读放大被"一天最多几场"挡住）；峰谷电价仍按 PEAK 保守预占（无机器可读节假日日历）。

---

## 5. 这一轮用户实际能做完的事（代码层已闭环，运行时受 U1/U6 限制）

1. 从今天计划点「开始」→ 建立实际训练草稿；没有计划或休息日也能自主记录。
2. 逐动作、逐组填写：勾一组才算一组已确认，次数/负重/时长都可留空（留空 = 未知，不等于 0）。
3. 保存退出、再进来仍是同一场（单草稿闸门 + 幂等键，页面带 `sessionId` 时只走 GET）。
4. 点「结束」并确认，才成为 COMPLETED 并进入统计；重复点不重复计数。
5. 看本次详情、历史（分页 + 状态筛选 + 原地详情）、当天计划对比、本周绝对量统计。
6. 丢弃草稿或删除整场，两者都二次确认并说明对统计与闸门的影响。
7. 问 AI 教练「今天练得怎么样」「这周练了几次」，回答里的数字带 session 来源与口径版本。

**没有做的**：AI 生成/修改计划、图片与饮食识别、能耗/医学阈值、RAG、视频、多 Agent，以及热量与「训练效果评分」——本轮 metrics 全部是确定性业务计算，不产出任何效果或健康结论。
