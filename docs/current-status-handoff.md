# FitAgent 当前状态与阶段验收交接报告

| 项 | 值 |
|---|---|
| 生成时间 | 2026-10-16 09:14 +08:00 |
| 分支 / commit | `main` @ `348961c`（工作区干净，`git status --porcelain` 为 0 行）→ **本报告覆盖的就是已提交并推送到 GitHub 的版本** |
| 后端 | Java 17.0.10 · Spring Boot 4.1.1 · MyBatis-Plus 3.5.17（`mybatis-plus-spring-boot4-starter` + `mybatis-plus-jsqlparser`）· Flyway 迁移 V1–V22 |
| 前端 | uni-app 3.0.0-5020420260813003 · Vue 3.4 · Pinia 2.3 · TypeScript，目标端 mp-weixin |
| 数据服务 | MySQL 8.0.30 + Redis 在 `192.168.10.100`（**本报告生成时不可达**，见 §2.4/§2.7） |
| 复用材料 | `docs/feature-audit-before.md`、`docs/feature-audit-after.md`、`docs/agent-v1-acceptance.md`、`docs/agent-v1-baseline.md`、`docs/ui-render-plan.md`、`docs/ui-render-acceptance.md`、`docs/ui-style-guide.md`、`docs/module-guide.md`、`docs/run-and-test.md` |

> 本轮只整理现状、复现关键流程、写交接文档；**未新增业务、未重做 UI、未做重构**。发现的缺陷只登记在 §2.7。

## 0. 版本更正（2026-10-16 16:35，阶段 2 已落地）

**本文件 §2 的主体写于 09:14、覆盖 `main@348961c`（阶段 2 之前）。那之后的当前工作区已经实施了阶段 2**，
所以 §2.1「3 个缺口」第 1 条、§2.2 表末段、§2.3 训练执行行、§2.4 第 5–8 步、§2.7 第 5 条、§2.8 的建议、§2.9 的用例数
**都已被 [docs/stage2-acceptance.md](stage2-acceptance.md) 取代**。保留原文是为了区分"旧证据"与"本轮结果"，不是现状。

当前工作区（未提交）的实际状态：

| 项 | 09:14 报告 | 16:35 现状 | 证据 |
|---|---|---|---|
| 训练执行记录 | 整段缺失 | `workout_session/entry/set` 三表 + `modules/workout` 全套（开始/恢复/列表/详情/保存/结束/丢弃/删除） | 迁移 V23–V25、`WorkoutServiceTest` 21 项 |
| 统计 | 无 | `modules/metrics`：当天计划对比 + 周绝对量，确定性计算、不建统计表、不调模型 | `TrainingMetricsServiceTest` 21 项，含必过示例 50% + 额外 2 组 |
| Agent 工具 | 3 个 | **5 个**：新增 `get_training_progress`、`get_weekly_training_summary`，白名单由 `CoachToolNames.ALL` 单点定义 | `TrainingQueryToolsTest` 7 项，其中一条把新工具真的跑过 `AgentRunner` |
| 小程序 | 无训练入口 | 新页 `pages/workout/record`、`history`、`stats` + 训练 tab 的「计划/记录/统计」二级结构 + 两个图表组件 | `vue-tsc` 0 错误、`check:theme` 通过、`build:mp-weixin` 通过且**告警清零**（16:40–16:44 重跑；顺手修掉 `plan/import.vue` 注释里写条件编译指令原文导致的分支裁剪缺陷） |
| Fake 开关 | 曾误判为死 | 实测可用并已钉住（本文件 §2.7 第 1 条已在上午更正） | `FakeModelSwitchBindingTest` 3 项 |
| 后端全量 | 358 | **422，0 失败 0 错误**（`BUILD SUCCESS`，31.764 s） | `mvn -B -pl app test` @ 16:31 |

**仍然为真的部分**（不随阶段 2 改变）：真实 DeepSeek 联调**零证据**；真实微信登录端到端**未走通**（缺你本地填写的 AppSecret）；
所有依赖真实 MySQL 8.x 的约束行为（外键、CHECK、STORED 生成列、单草稿闸门、并发）**未验证**——本轮按你 2026-10-16 的决定
「先按无真库交付」执行，DDL 与 9 条探针作为交付物放在 [docs/sql/stage2-workout-mysql-8.4.sql](sql/stage2-workout-mysql-8.4.sql)，我没有在任何数据库上执行过它。

下一阶段的唯一主任务建议相应改为：**AI 生成/修改训练计划草稿（只出 DRAFT，用户确认后经现有流程生效）**——
训练记录与统计地基已在代码层闭环，而 §0 表里的真库项与费用真实闭环仍需在具备环境时补验。


## 2.1 当前结论

**用户现在能实际完成的流程**

1. 登录 → 自动建号 → 首页（开发身份 `dev-a`/`dev-b` 走 `POST /api/auth/dev-login`；真实微信登录代码与凭据齐备，凭据对经 `jscode2session` 探测确认有效，但**端到端未走通**，见 §2.4）。
2. 档案建立/编辑（含乐观锁与冲突处理）、目标新建/激活/归档（每用户至多一个 ACTIVE）、体重新增/编辑/删除与"最新一次"回显。
3. 训练计划：新建草稿、整周编辑、动作选择器、确认生效、归档、复制、另存新草稿；JSON 模板导入 preview → confirm → **只出 DRAFT**。
4. BYOK：保存/替换/撤销 API Key（只显示脱敏尾部与版本）、查询供应商余额快照（失败不显示 0）、设置单次/每日预算与轮数上限。
5. AI 教练：建会话、发起预估（无 Key 也能拿到金额/上限/工具清单）、提交 run、查看步骤与事实卡、取消、重进恢复同一 run。

**只是"代码存在"或"未验证"**

- 真实 DeepSeek 调用：**零证据**（没有可用 Key，也没有发生过一次真实计费请求）。协议兼容、模型是否真会调工具、费用是否算准，全部未验证。
- prod 档案启动实证、真库并发与唯一索引验证：受环境限制未做（`docs/agent-v1-acceptance.md` §3 已逐条标注）。
- `FakeModelClient` 是**测试用脚本队列**（取完即抛 `scriptExhausted`），不是"演示模式"。开关本身可用
  （`FITAGENT_AGENT_FAKE_MODEL_ENABLED=true` 实测能装配，见 §2.7 第 1 条的更正），
  但打开后也只能跑出脚本里预置的那一串消息，**不能**当成"看到了真实模型的回答"。

**当前最重要的 3 个缺口**

> ⚠ 本小节写于阶段 2 之前：第 1 条已由阶段 2 补上（见 §0 与 [docs/stage2-acceptance.md](stage2-acceptance.md)）；第 2、3 条仍然为真。

1. **阶段 2（训练执行记录与统计）整段未实施**：没有 `workout_session/entry/set` 与 metrics 表、没有打卡页、没有统计接口 → 提示词 §2.4 要求的"开始训练 → 保存实际记录 → 结束 → 统计 → 问 AI 当天情况"这条链在**第 5 步就必然断**，AI 也没有第 4 个工具可注册。
2. **费用与凭据的真实闭环没有任何证据**：预算预占/结算/未知费用只在单元级验证；`UNCERTAIN` 预占、峰谷计价、余额账户级语义都还没跟真供应商对过一次账。
3. **本轮环境阻断**：`192.168.10.100` 的 3306/6379 TCP 全部超时（ICMP 有应答但 TTL=61），因此**今天无法复现任何依赖真库的流程**；同一份配置在 2026-10-14 20:45 曾成功连上（`/actuator/health` 里 `db=UP`、dev-login 出过真令牌）。

**是否具备进入下一阶段的前提**：具备**开工阶段 2** 的前提（基础闭环有单元证据 + 一次真实 API 走通记录，且缺的正是训练记录本身）；**不具备**直接做"AI 生成/修改计划"的前提——预算与真实费用尚无证据，且没有训练历史可供生成参考。依据见 §2.8。

## 2.2 实际架构与运行方式

| 目录 | 职责 |
|---|---|
| `backend/app/src/main/java/com/hfj/fitagent/` | 单应用；`common`/`security`/`config` + `modules/{auth,profile,training,coach,modelaccess,billing}` + `agent/`（执行循环与模型契约）+ `worker/`（run 领取与心跳） |
| `backend/app/src/main/resources/db/migration/` | Flyway V1–V22（本机 `FITAGENT_FLYWAY_ENABLED=false`，表是手工建的） |
| `miniapp/src/` | 14 个页面 + 18 个组件；`styles/icons.scss` 与 `static/tabbar/*.png` 是**生成物** |
| `miniapp/scripts/` | `gen-icons.mjs`（SVG→base64 图标）、`gen-tabbar.mjs`（纯 Node 手写 PNG 编码器）、`check-theme.mjs`（9 组断言） |
| `infra/` | `.env`（**不入库**）、`.env.example`、`docker-compose.yml`、`mysql-init/` |
| `docs/` | 契约与验收文档；`docs/sql/` 是可直接执行的建表与种子脚本 |

```bash
# 后端（不要加 -am：spring-boot:run 会在聚合父工程上找不到 main 类）
cd backend && set -a; . ../infra/.env; set +a; export SPRING_PROFILES_ACTIVE=dev
mvn -B -pl app spring-boot:run
# 前端
cd miniapp && npm run dev:mp-weixin        # 产物 dist/dev/mp-weixin，DevTools 要打开 miniapp 根目录
npm run build:mp-weixin && npm run check:theme && npx vue-tsc --noEmit
```

**数据库实际表（21 张，全部已迁移）**：V1 `user_account`、V2 `user_profile`、V3 `user_goal`、V4 `body_measurement`、V5 `exercise_reference`（25 条种子在 V9）、V6 `training_plan`、V7 `training_plan_day`、V8 `training_plan_item`；V10 `model_provider`、V11 `model_catalog`、V12 `model_price`、V13 `model_credential`、V14 `provider_balance_snapshot`、V15 `budget_policy`、V16 `budget_daily_usage`、V17 `budget_reservation`、V18 `chat_conversation`、V19 `agent_run`、V20 `model_call`、V21 `agent_step`、V22 `chat_message`。

**仅存在于设计、尚未建立的表**：`workout_session`、`workout_session_entry`、`workout_session_set` 与一切 metrics/统计对象（阶段 2）。**未使用的依赖服务**：向量库、对象存储、消息队列（设计里也没有）。

## 2.3 功能状态矩阵

状态取值：`已实际验证` / `代码存在未验证` / `已实现有缺陷` / `未实现` / `仅Mock` / `环境受限` / `后续阶段`。

| 模块/功能 | 实际能力 | 页面/入口 | API 与关键代码 | 状态 | 验证方式/结果 | 缺口 |
|---|---|---|---|---|---|---|
| 微信登录 + 首次自动注册 | `code → jscode2session → openid → 建号/复用 → 发令牌` | `pages/auth/login`「微信登录」 | `modules/auth/AuthRoutes`、`WechatLoginService`、`WechatCode2SessionClient` | 环境受限 | 单元级：`WechatLoginServiceTest` 14 项全绿（含 40013/40029/45001 映射、session_key 不外泄）；凭据对有效性由 `jscode2session` 返回 `40029 invalid code` 证实（2026-10-15） | 端到端未走通：需手机扫码 + 电脑 8080 可达（§2.7 P2-3） |
| 开发登录（**不能当真实微信联调**） | 固定 `dev-a`/`dev-b` 两个身份，仅 `dev` profile + 显式开关 | 登录页「开发登录」两枚图标入口 | `modules/auth/DevLoginController`（`@Profile("dev")` + `@ConditionalOnProperty`） | 已实际验证 | 2026-10-14 20:45 真库返回 `code:OK` + Bearer 令牌 | — |
| 会话失效 / 退出 | 401 集中清令牌 + `reLaunch` 登录页；退出幂等，服务端失败也清本机 | 我的页「退出」；登录页一次性提示 | `utils/request.ts`、`stores/session.ts`、`AuthRoutes.LOGOUT` | 代码存在未验证 | 单元：`AccountSessionServiceTest` 6 项；前端 `takeSessionExpiredNotice()` 消费点已接 | 真库上"令牌撤销后旧令牌确实 401"未跑 |
| 档案（身高必填/经验/器械/限制） | 建/改 + 乐观锁 409 + 逐字段错误回显 | 我的 → 档案 | `modules/profile/controller/ProfileController` `/api/me/profile` | 已实际验证 | 2026-10-14 真库 PUT/POST 走通，含 409 冲突与回显 | — |
| 目标 | 新建/编辑/激活/归档/删除草稿；至多一个 ACTIVE | 我的 → 目标 | `GoalController` `/api/me/goals` | 已实际验证 | 真库唯一闸门 + 状态机拒绝均观测到 | — |
| 体重增改删 + 最新测量 + 历史 | 幂等键重试、补录不改"最新"、分页窗口 | 我的 → 体重（tab 首页也有入口） | `BodyRecordController` `/api/body-records*`、`pages/mine/body/records.vue` | 已实际验证 | 真库 `replay=true` 命中、删空回退到 `data:null` 均观测到 | — |
| 计划创建/草稿编辑/版本/生效/归档/复制/另存 | 聚合根一次事务写入；版本 `revisionNo=max+1`、`basePlanId` 指向来源 | tab 训练 → 列表/详情/编辑 | `TrainingPlanController` `/api/plans*` | 已实际验证 | 真库 DRAFT→ACTIVE→归档、抢先版本冲突 409 均观测到 | — |
| JSON 模板导入 | 选文件/粘贴两条入口 → preview（无副作用）→ confirm（幂等键）→ 只出 DRAFT | tab 训练 → 导入；首页「导入」入口 | `PlanImportController`、`api/training.ts#previewPlanImport/confirmPlanImport` | 已实际验证 | 真库非法动作码定位到 `days[0].items[0].exercise_code`、同键重放返回同一 planId | — |
| **实际训练 / 逐组记录 / 草稿续填 / 结束确认 / 训练历史** | 无 | 无入口（首页四宫格刻意不放"训练"） | 无表、无 Service、无 API | **未实现（后续阶段）** | 迁移 V1–V22 内无 `workout_*`；`AGENTS.md` 阶段边界写明 | 整段是阶段 2 的范围 |
| 当天计划对比 / 周统计 / 真实体重趋势 | 只有"今天有没有安排"与"体重真实趋势"；**没有**完成度/场次/时长统计 | 首页主卡 + 本周点阵；体重页折线 | `GET /api/plans/today`、`GET /api/body-records`、`components/FaTrendChart.vue` | 部分已实现 | 体重折线数据源真实（`measuredAt`/`weightKg`/`businessDate`）；单点不连线、无数据不画 0 | 统计类全部依赖阶段 2 |
| 自研 Agent：模型适配 | DeepSeek Chat Completions 客户端 + 协议硬约束（`thinking=disabled`、`tool_choice∈{auto,none}`、finish_reason 白名单、不截断执行 arguments） | 不直接暴露 | `agent/model/deepseek/*`、`docs/agent-v1-protocol.md` | 代码存在未验证 | 仅 HTTP stub 协议测试（`DeepSeekLogSafetyTest` 等） | 未与真实供应商对过一次 |
| 工具注册与执行 | 只注册 3 个：`get_profile`、`get_training_plan`、`get_weight_trend` | 教练页事实卡来源 | `modules/coach/tools/CoachToolNames`、`CoachToolConfiguration`、`*Tool.java` | 代码存在未验证 | 单元级参数校验/归属校验通过 | 没有 `get_training_progress`（无数据可答，刻意不注册） |
| 执行循环与停止条件 | 有上限循环：`max-model-calls: 4`、`max-tool-calls: 8`（`application.yml:106-107`）+ `deadline`；`LOOP_EXHAUSTED` 与 `PROTOCOL_INCOMPLETE` 可区分 | 教练页运行详情 | `agent/core/AgentRunner.java:152-159`、`RunLimits`、`StopReason` | 代码存在未验证 | 循环/配对/截断处理有测试 | 真实模型是否会遵守收尾约束未知 |
| 会话/run 持久化、取消、幂等、重入恢复 | 10 个端点；`Idempotency-Key` 走 UUID 规范化；重启后恢复同一 run 且**不自动重发** | 教练页 + 历史会话 | `CoachController`（`/api/coach`：conversations/…/estimate/…/runs、runs/{id}、steps、cancel）、`RunWorker`、`RunExecutionService` | 代码存在未验证 | 单元 358 项含这些路径；真库并发/租约未验 | 需真库跑一次双请求 `RUN_ALREADY_ACTIVE` |
| 用户自带 Key：加密/脱敏/替换/撤销 | AES-256-GCM（一次性 nonce + AAD 绑定 userId/provider/version）+ 独立 HMAC 指纹；响应只回脱敏尾部 | 我的 → 设置 → 密钥 | `ModelSettingsController` `/api/model-credentials/deepseek`、`ModelCredentialService` | 代码存在未验证 | 单元覆盖密文/明文/日志不外泄；真库唯一约束与版本递增未验 | — |
| 余额与更新时间 | `GET /user/balance` 账户级快照；失败显示上次快照 + `stale` 或"暂无法获取"，**不显示 0** | 设置 → 余额 | `DeepSeekBalanceGateway`、`BalanceSnapshotService`、`provider_balance_snapshot` | 代码存在未验证 | 无 Key → 未联调 | 真实余额字段口径未对账 |
| 费用估算 / 每轮预占 / 结算 / 未知费用 / 单次与每日限额 | 短事务预占 → HTTP 在锁外 → 幂等结算；`UNCERTAIN` 不核销不重发；一律按 PEAK 保守档 | 教练页预估与费用卡；设置 → 预算 | `modules/coach/gateway/BudgetedModelCallGateway`、`agent/core/BudgetReference`、`budget_reservation` | 代码存在未验证 | 单元 10 项（含 Mockito 陷阱修正后的断言） | 真实计费一致性零证据 |
| UI 重排 / 图标 / 图表 / 导航 | 14 页全部重排；27 图标 × 5 色调；4 tab 带 PNG 图标；体重折线 | 全站 | `components/Fa*.vue`、`scripts/gen-icons.mjs`、`scripts/gen-tabbar.mjs` | 部分已实际验证 | 真机截图（登录页）+ 构建/类型/9 组断言通过 | 其余页面仅代码与构建检查（§2.5） |
| 关键代码简明注释 | 后端与前端关键类/组合式函数均有 1–3 行"为什么"注释 | — | 例：`AgentRunner`、`BudgetedModelCallGateway`、`FaTrendChart`、`check-theme.mjs` 头部 | 已实际验证 | 本轮 grep 复核无过时注释残留 | — |
| **AI 生成/修改训练计划** | 无 | 无 | 无 | **未实现（后续能力）** | 计划写入只允许用户确认（`AGENTS.md` 阶段边界） | 见 §2.8 |
| 图片/资料/视频识别 | 无，且不属当前授权阶段 | 无 | 无 | 后续阶段 | — | 饮食识别、教学视频明确不做 |

## 2.4 核心流程复现

提示词要求的链路：**登录 → 保存档案/体重 → 创建或导入计划 → 确认生效 → 开始训练 → 保存实际记录 → 结束 → 查询统计 → 向 AI 查询当天情况**。

| # | 步骤 | 入口 | 本轮实际结果 | 验证层级 |
|---|---|---|---|---|
| 0 | 后端启动并连上数据服务 | `mvn -B -pl app spring-boot:run` | **失败**：应用能起（曾监听 8080），但 `RedisConnectionException: Unable to connect to 192.168.10.100:6379` 与 `Communications link failure / Connect timed out`（MySQL 3306）。TCP 探测：3306/6379/22 全部 TIMEOUT，ICMP 有应答 TTL=61 | 本机命令行 |
| 1 | 登录（dev 身份） | 登录页「用户A」 | **本轮未执行**（依赖步骤 0）。最近一次成功：2026-10-14 20:45 真库返回 `code:OK` + Bearer 令牌 | 上次 API 级 |
| 2–4 | 档案 / 体重 / 计划导入与生效 | 我的、体重、训练 tab | **本轮未执行**。最近一次成功：2026-10-14 同一批真库走通（含 409 冲突、幂等 `replay=true`、非法动作码定位、DRAFT 结果） | 上次 API 级 |
| 5 | 开始训练 | **无此功能** | 链路在此**结构性中断**：没有 `workout_session` 表、没有页面、没有 API | — |
| 6–8 | 逐组保存 / 结束确认 / 统计 | **无此功能** | 同上；因此"今天完成多少""本周练了几次"当前无工具可答 | — |
| 9 | 向 AI 查询当天情况 | 教练 tab | **本轮未执行**；且即使库可连，也只能走到"预估 + 提交 run 被 `CREDENTIAL_MISSING` 拒"（无 DeepSeek Key）。2026-10-14 观测到该拒绝且 `model_call` 台账**没有新增行** | 上次 API 级 |

**不补造**：步骤 1–4、9 的"成功"来自 2026-10-14 那一次真实运行（`docs/feature-audit-after.md` §4.5 有逐条记录），本轮**没有重跑**，因此不能当作今天的验收结论。步骤 5–8 从未实现，不存在"曾经成功"。

**本轮实际能复现的（不依赖外部数据服务）**

| 命令 | 结果 |
|---|---|
| `mvn -B -pl app test`（干净环境） | **358 项，0 失败 0 错误，BUILD SUCCESS** |
| `npx vue-tsc --noEmit` | 0 错误 |
| `npm run build:mp-weixin` | DONE Build complete |
| `npm run check:theme` | 9 组断言全部通过 |
| 真机截图（用户提供，2026-10-14 20:54） | 登录页在 iPhone 上正常渲染：机器人图形、图标 + 短标签按钮、错误横幅带图形与错误码 `TIMEOUT` |

**关键不变式的复现检查点（等库恢复后按此跑）**：数据刷新回显（档案/体重写后重读）、跨用户归属（dev-b 拿 dev-a 的 id 应 404 不是 403）、草稿不计正式完成（当前无"完成"概念）、计划版本（生效后再改必走 revise）、重复提交（同 `Idempotency-Key`/`clientRecordUuid` 重放）、UI 重排后原功能仍可访问（`docs/run-and-test.md` §3 那张 20 行点测表就是为这件事写的）。

**Agent 协议验证边界**：默认走脚本化假模型与 HTTP stub——它证明的是**我们自己那侧的配对、上限、停止原因、台账写入**，**不**证明供应商兼容性、模型是否真会调工具、费用是否算准。真实供应商验证需要用户主动提供 Key 与预算；本轮**未发起任何计费请求**。

## 2.5 UI 改造后的状态

| 页面 | 淡黄 + 留白 + 图标短标签 | 首页主卡/入口 | 图表组件与数据源 | 是否实际渲染过 |
|---|---|---|---|---|
| 首页 `pages/index/home`（tab） | 是（代码检查） | 一张今日主卡 + 2×2 图标入口（体重/计划/教练/导入）；刻意不放"训练"与任何完成度 | `FaTrendChart`（`GET /api/body-records` 窗口内真实点）+ `FaDayStrip`（`GET /api/plans/{id}` 的安排，标注"不代表实际练过"） | **否**，仅构建 |
| 训练 `pages/plan/list`（tab） | 是 | 图标工具条「新建/导入」+「更多」收纳动作字典 | 无图表 | 否 |
| 计划详情/编辑 | 是 | 详情主操作 1 个 + 更多；编辑七日条选、动作折叠卡、底部操作条 | 无图表 | 否 |
| 体重 `pages/mine/body/records` | 是 | 关键数字 + 7/30/90/1年 分段 + 行内「详情」折叠 | `FaTrendChart` 同上 | **否**（canvas 是否真画出线未验证） |
| 教练 `pages/coach/chat`（tab） | 是 | 对话为主体；运行状态与「停止」常驻不折叠，事实/步骤/费用/停止原因进「详情」折叠 | 无图表 | 否 |
| 我的 / 设置 | 是 | 头像区 + 图标分组入口；账户字段、Key/余额/预算的审计项折叠但字段不删 | 无图表 | 否 |
| 登录 `pages/auth/login` | 是 | 主操作「微信登录」+ 开发登录图标入口 | 无 | **是**：DevTools 模拟器与 iPhone 真机各一张截图（用户提供，临时附件路径，未归档进仓库） |

已检查到的边界：单点不连线 / 无数据画空态、缺失指标显示"未设定"而非 0、加载与失败态带图形+文字、长动作名 `word-break`、底部安全区用 `env(safe-area-inset-bottom)`（**未在刘海屏真机验证**）。**可视检查环境**：只有用户手上的微信开发者工具与一台 iPhone；本轮我这边**没有任何渲染运行时**，除登录页外的所有"视觉效果"都只能算代码与构建检查。

## 2.6 Agent 与费用的真实实现

**一条测试问题的链路（stub 层，非真实供应商）**

```
POST /api/coach/conversations/{id}/runs (Idempotency-Key)
  → CoachController:90 → CoachApplicationService.submit（写 chat_message + agent_run，QUEUED）
  → RunDispatcher（事务提交后派发）→ RunWorker（有界线程池 + 租约心跳 lease-seconds:60 / heartbeat:20）
  → RunExecutionService → AgentRunner.run
      循环：ContextManager 组装 → BudgetedModelCallGateway（预占 → 写 model_call → HTTP → 结算）
            → ModelClient（stub）→ finish_reason 判定 → 工具配对 → CoachTool* 调业务 Service
            → AgentStepWriter 写 agent_step → 直到 stop / 上限 / deadline
  → 结果写 chat_message + result_payload；StopReason 映射 COMPLETED/LOOP_EXHAUSTED/…
```

| 关键点 | 位置 | 实际值 |
|---|---|---|
| 工具注册表 | `modules/coach/tools/CoachToolNames.java:12-14` | 仅 3 个（`get_profile`/`get_training_plan`/`get_weight_trend`） |
| 参数与归属校验 | `CoachToolSchemas.java`、各 `Get*Tool.java` | 严格解析 arguments；越权 id 走业务 Service 的归属过滤，不信任模型入参 |
| 循环上限 | `application.yml:106-107` + `agent/core/RunLimits.java` | 模型 4 次 / 工具 8 次；`deadline` 推导 HTTP 超时 |
| 停止条件 | `agent/core/AgentRunner.java:152-159`、`StopReason` | deadline、轮数上限、协议不完整、取消 |
| 费用关口 | `modules/coach/gateway/BudgetedModelCallGateway.java`、`agent/core/BudgetReference.java` | 短事务预占 → 锁外调用 → 幂等结算；`UNCERTAIN` 不核销、不重发 |
| 凭据 | `ModelCredentialService`、`AgentCredentialResolverAdapter` | 明文只在提交瞬间；撤销后 `CREDENTIAL_MISSING` 直接拒发 |

**三种证据等级的区分**：① **真实模型联调 = 零**；② **stub/HTTP 假响应验证 = 有**（协议字段、配对、上限、台账、取消与恢复）；③ **纯演示 = 不存在**——页面没有任何写死的 AI 回答。举例中不含真实 Key、Authorization、openid 或用户数据。

**尚无证据的能力**：真实工具调用是否被模型正确触发、真实 token 计费与 `model_price` 档位是否吻合、峰谷计价与节假日、余额账户级并发影响、真库下 `active_conversation_id`/`UNIQUE` 闸门在并发时的行为。

## 2.7 已知问题与优先级

| # | 问题 | 复现方式 | 影响 | 相关代码 | 建议修复 | 优先级 |
|---|---|---|---|---|---|---|
| 1 | ~~Fake 模型开关是死的~~ **已撤回：该结论错误** | 复现方式：`set -a; . ../infra/.env; set +a; export FITAGENT_AGENT_FAKE_MODEL_ENABLED=true; mvn -B -pl app spring-boot:run` | 启动日志实测出现 `Fake 模型客户端已装配 provider=DEEPSEEK label=FAKE_MODEL` 与 `FakeModelStartupGuard` 的告警 → **环境变量确实能打开**，`docs/run-and-test.md` §4 的写法本来就是对的 | `application.yml:112-114`、`FakeModelClient:44`、`FakeModelStartupGuard` | 原报告断言"键名映射不上 + yml 写死 false 覆盖不了"，两条都不成立。已补 `FakeModelSwitchBindingTest` 把三种事实钉住：默认关闭、文档里的键名能打开、保留连字符的写法也能打开 | 已更正，无缺陷 |
| 2 | **`AgentConfigBindingTest` 会被进程环境染污**：它按 `docs/run-and-test.md` §1 的推荐先 `source infra/.env` 再 `mvn test` 时**假失败** | `set -a; . ../infra/.env; set +a; mvn -B -pl app test` → `Tests run: 358, Failures: 1`，报在 `AgentConfigBindingTest.java:90`（`keys.masterKeyConfigured()` 期望 false 实为 true） | 让人误判"仓库有回归"；本轮实测已踩到（干净环境下同一测试 4/4 通过、全量 358/0） | `backend/app/src/test/java/com/hfj/fitagent/AgentConfigBindingTest.java:44-49`（`StandardEnvironment` 带 systemEnvironment） | 测试内用不含系统环境的 `StandardEnvironment`（关掉 `systemProperties`/`systemEnvironment` property source），或在文档里明确"跑测试前不要 source `.env`" | **P2** |
| 3 | **手机扫码预览连不上后端**：WLAN 网络类别是 Public 且公用防火墙启用，8080 未放行；`172.26.161.94` 也可能是被隔离的客户端网段 | 手机开发版点登录 → 页面显示 `请求超时 TIMEOUT`（真机截图）；同一地址在电脑上 `curl` 返回 200 | 真机联调做不了，只能靠模拟器 | `miniapp/.env.development`、`docs/run-and-test.md` §2 | 管理员放行 8080，或改手机热点让电脑接入；文档已写但需要一条"如何自测可达性"的判定步骤 | **P2**（环境） |
| 4 | **数据服务当前不可达**，导致本轮无法复现任何真库流程 | `node` TCP 探测 `192.168.10.100:3306/6379` 超时；启动日志 `RedisConnectionException` + MySQL `Communications link failure` | §2.4 的第 1–4、9 步全部只能引用 10-14 的旧证据 | `infra/.env` 的 `FITAGENT_MYSQL_URL`/`FITAGENT_REDIS_HOST` | 让虚机/服务起来（或确认代理 TUN 没劫持该网段）后重跑 `docs/run-and-test.md` §3 点测表 | **环境受限，非代码缺陷** |
| 5 | 阶段 2 未实施：无训练执行、无统计、无"当天对比"，AI 缺第 4 个工具 | 全仓无 `workout_*` 表/页面/接口；`GET /api/plans/today` 只回答"有没有安排" | 提示词 §2.4 链路结构性断裂 | `AGENTS.md` 阶段边界、`docs/feature-audit-before.md` §4 | 见 §2.8（这是**下一阶段范围**，不当 bug 记） | **后续阶段** |
| 6 | UI 的可视验收只覆盖登录页；其余 13 页与 canvas 图表未经渲染验证 | 见 §2.5 最后一列 | 图标/折线/安全区/窄屏换行等存在"构建通过但显示不对"的风险 | `docs/ui-render-acceptance.md` §5 | 在 DevTools 逐页点一遍并回填该文档 | **P2**（缺关键证据） |

**没有 P0**：凭据未入库（AppID 也已按要求移出并改写历史）；无越权路径证据；无重复收费路径（`UNCERTAIN` 不自动重发、无凭据不发请求都有测试与一次真库观测支撑）。

## 2.8 下一阶段建议（只推荐一个主任务）

> ⚠ 本节建议（开工阶段 2）**已执行完毕**。当前唯一主任务建议见 §0 末段：AI 生成/修改训练计划草稿。

**主任务：补齐阶段 2「训练执行记录」的最小闭环——开始 / 逐组保存 / 结束确认 / 草稿续填。**

- **依据**：基础闭环（登录、档案、体重、计划、导入）已有真库走通记录与单元证据；Agent 侧的会话/run/预算代码齐备但缺真实供应商证据，短期内无法靠"再加 AI 功能"取得证据；而**统计、当天对比、完成度、AI 第 4 个工具全部卡在同一个前置——没有训练记录**。补它同时解锁 UI 上那些刻意留空的数据位。
- **依赖**：新增增量迁移（建议 V23–V25：`workout_session` / `workout_session_entry` / `workout_session_set`），沿用现有 `active_user_id` 式派生列与 `UNIQUE` 闸门口径；沿用 `AuthenticatedUsers.require()` 与 `incrementDataRevision`；**不得**让计划日成为可删对象（P1 的"删旧插新"在 `workout_session` 引用计划日后必须改成增量更新——`AGENTS.md` 已标了这条）。
- **明确边界**：只做"记录用户实际做了什么"，**不做**任何能耗公式、训练效果分、医学阈值；完成度百分比必须由已有真实字段（完成的组数/计划组数、实际时长/目标时长）直接算出，缺数据就显示缺失而不是 0。
- **验收条件**：① 迁移可在 MySQL 8.0.16+ 真库执行且 CHECK/唯一索引生效；② 同一 session 草稿重进可续填、不误增记录（幂等键）；③ 未点"结束"的记录不计入任何统计；④ 结束后逐组数据可编辑且保留历史版本口径；⑤ 新增第 4 个工具 `get_training_progress` 后，AI 回答"今天/本周练了什么"时事实卡能追溯到 session ID；⑥ 首页/统计页的进度图形在**有数据**时才出现，无数据仍是缺失态。
- **本轮不推荐**："AI 生成/修改训练计划"。理由：预算与真实费用还没有一次证据（§2.6），且没有训练历史可供参考；若将来做，必须复用现有 `AgentRunner` + `BudgetedModelCallGateway` + 计划版本链（`revise` 出 DRAFT，用户确认后走现有 `activate`），**只保存草稿、不得自动生效、不得绕过预算**。

## 2.9 验证结果

> ⚠ 下表数字是 09:14 那一次的证据，保留不改。阶段 2 之后的最新数字：**后端 422 项 0 失败**、前端三道门禁全绿（见 [docs/stage2-acceptance.md](stage2-acceptance.md) §2）。

| 命令 / 操作 | 环境 | 结果 |
|---|---|---|
| `mvn -B -pl app test` | 本机 Windows · JDK 17.0.10 · Maven 3.8.8 · 干净 shell（无 `FITAGENT_*`） | **358 项全通过，BUILD SUCCESS**（2026-10-16 09:0x） |
| `mvn -B -pl app test`（先 `source infra/.env`） | 同上，但带进程环境变量 | **358 项 1 失败**（`AgentConfigBindingTest:90`）→ 见 §2.7 P2-2，属测试隔离缺陷，非功能回归 |
| `mvn -B -pl app test -Dtest='WechatLoginServiceTest,AccountSessionServiceTest'` | 本机（2026-10-15，AppID 占位串改动后） | 14 + 6 全通过 |
| `npx vue-tsc --noEmit` | Node v24.18.0 | 0 错误 |
| `npm run build:mp-weixin` | 同上 | DONE Build complete |
| `npm run check:theme` | 同上 | 9 组断言全部通过 |
| `git grep -niE "wx[0-9a-f]{16}" HEAD` | 提交 `348961c` | 0 命中（AppID 已移出仓库） |
| 后端启动 + `/actuator/health` | 本机，`dev` profile | 2026-10-14 20:45 `UP`（db=MySQL、redis=8.2.3）；**2026-10-16 09:0x 启动即 Redis/MySQL 连接失败**（§2.7 环境受限） |
| 真机 | iPhone + 微信开发版 | 登录页渲染正常；请求 `TIMEOUT`（网络不可达，非前端缺陷） |
| 真实 DeepSeek 调用 | — | **未执行**（无 Key，未发起任何计费请求） |
| prod profile 启动 + `FakeModelStartupGuard` 拒绝 | — | **未执行**（需要能跑 8.0.16+ 真库的环境） |
| 真库并发 / 唯一索引 / 令牌撤销后 401 | — | **未执行**（本轮数据服务不可达；10-14 那次也只跑了单请求路径） |

**引用旧结论的地方已写明日期**：§2.3 与 §2.4 里所有"已实际验证"的真库结论来自 2026-10-14 那一次运行（记录在 `docs/feature-audit-after.md` §4.5），本轮未重跑。
