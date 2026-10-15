# FitAgent 自研 Agent 第一版：现有能力基线核查

日期：2026-10-13。核查方式：逐文件读仓库 + 执行过的构建/测试记录，**不是**依据 `FitAgent_项目设计/` 的规划推断。
四类结论：**已验证** / **代码存在未运行** / **缺失** / **环境受限**。

## 1. 仓库真实状态

```
git 分支：master（仓库已 git init，尚无 commit；8 个顶层条目待提交）
后端：Spring Boot 4.1.1 + Spring Framework 7.0.9 + Security 7.1.1
      Java 17.0.10（JAVA_HOME=D:\software\Java\jdk17）
      MyBatis-Plus 3.5.17（mybatis-plus-spring-boot4-starter + mybatis-plus-jsqlparser）
      Flyway 12.4.0 + flyway-mysql，mysql-connector-j 9.7.0，Lettuce 7.5.2
      Jackson 3.1.5（tools.jackson；编译期 classpath 上没有 Jackson 2 databind）
数据：MySQL 8.4 与 Redis 在用户服务器上，本机不可连
迁移：V1..V9，共 8 张表
```

`mvn -B -pl app -am test` → **177 用例，0 失败**。`npx vue-tsc --noEmit` → 0 错误。`npm run build:mp-weixin` → 构建完成，11 个页面。

## 2. 已验证（真实存在且有自动化测试）

| 能力 | 位置 | 证据 |
|---|---|---|
| 微信登录 + 首次自动注册 + 并发去重 | `modules/auth` | `AccountProvisioningServiceTest` 7、`WechatLoginServiceTest` 14；错误码映射与 `session_key` 不外泄有断言 |
| 服务端不透明令牌会话、退出即时失效 | `security/SessionTokenService`（Redis，键存 SHA-256 摘要） | `AccountSessionServiceTest` 6；真实 Redis 行为属"环境受限" |
| 开发登录（仅 dev + 两个测试用户 + prod 启动期拒绝） | `modules/auth/DevLogin*` | `DevLoginServiceTest` 5 |
| 当前用户上下文与跨用户归属校验 | `security/AuthenticatedUsers`、各 `(id,user_id)` 复合查询 | `ProfileContextReaderImplTest` 6；`越权 → RESOURCE_NOT_FOUND` 有断言 |
| 档案（身高/训练经验/器械/可选生日与性别） | `modules/profile` | `UserProfileServiceTest` 13、`ProfileRequestValidationTest` 7 |
| 基础目标 + 每用户最多一个 ACTIVE | `modules/profile/UserGoalService` | `UserGoalServiceTest` 20（含"先归档后激活"的 InOrder 断言） |
| 体重记录、最新体重、补录不改最新、幂等回退 | `BodyMeasurementService` + `findLatest(measured_at DESC)` | `BodyMeasurementServiceTest` 16 |
| 计划版本 / 草稿 / 生效 / 归档 / 丢弃 / 复制 / 今天 | `modules/training/TrainingPlanService` | `TrainingPlanServiceStateMachineTest` 11 |
| 训练计划 JSON 模板导入（校验→预览→确认） | `PlanImportService`、`PlanAggregateValidator` | `PlanImportServiceTest` + `PlanAggregateValidatorTest`，含"交付模板零错零警" |
| 动作字典（25 条种子，只读） | `exercise_reference` + `V9` | 迁移与 `ExerciseService` |
| `data_revision` 递增机制 | `modules/auth/mapper/UserAccountMapper.incrementDataRevision` | 各服务在写事务内调用，测试断言"失败不加修订号" |

**这直接给出 Agent 第一版可依赖的可信输入**：`userId`、业务时区（`CurrentUser.zone()`）、业务日期（`AppClock`）、`data_revision`、以及下面三个真实查询能力。

## 3. 缺失（本轮必须新建或据此裁剪范围）

### 3.1 模型接入与费用（B10/B11 要求的依赖，全部不存在）

`model_provider`、`model_catalog`、`model_credential`、`model_price`、`provider_balance_snapshot`、`budget_policy`、`budget_reservation`、`budget_daily_usage`、`model_call` 九张表与对应 Java 模块**全部为零**。没有 AES-GCM 凭据加解密、没有余额适配器、没有价格版本、没有预占/结算、没有 BudgetGate。

> 结论：不补这些就无法产生"真实调用 + 真实记账"，而 B10 明令禁止"默认放行的 BudgetGate"。这部分是本轮必做的地基，不是可选项。

### 3.2 会话与 Agent 执行（B4/B5/B11）

`chat_conversation`、`chat_message`、`agent_run`、`agent_step` 四张表与 `agent/*`、`modules/coach/*` 代码**全部为零**。没有 Runner、ModelClient、ToolRegistry、ContextManager、TraceRecorder、AnswerAssembler，没有后台执行器与取消/恢复。

### 3.3 训练执行记录与确定性统计（决定工具能不能注册）

`workout_session`、`workout_entry`、`workout_set`、`daily_checkin`、`met_activity`、`calculation_rule` **全部不存在**（P1 刻意不做，见 `docs/p1-scope.md` §2 与 `docs/p1-database.md` §4.3）。因此：

| B7 建议工具 | 本轮能否注册 | 原因 |
|---|---|---|
| `get_profile` | ✅ 能 | 档案 + ACTIVE 目标已有服务 |
| `get_training_plan` | ✅ 能 | `TrainingPlanService.findToday` 已有，含"无 ACTIVE/今天不安排/休息日"三种真实形态 |
| `get_weight_trend` | ✅ 能 | `body_measurement` 有真实测量序列 |
| `get_workout_records` | ❌ **不注册** | 没有训练执行记录业务 |
| `get_training_progress` | ❌ **不注册** | 没有执行记录，也没有 metrics 服务；完成率无从计算 |
| `get_weekly_training_summary` | ❌ **不注册** | 同上 |

B2 的指令是"先把真实已有能力接入，把不可用工具从注册表中移除…不要为此重做整轮训练记录业务"，本表就是执行结果。

**必须点破的连带后果**：A3 那个"计划 6 组、实际 5 组 → 组数完成度 50%、额外 2 组"的示范场景，以及 B14 第 8 项对该数值的验收，**本轮无法成立**，因为分母（计划）有、分子（执行记录）没有。我不会用假数据凑出这个百分比，也不会让模型自己编。要闭环它，需要先做训练执行记录（`workout_*` 三张表 + 逐组录入 + 确定性统计服务），那是一个独立的业务迭代。

### 3.4 前端

`miniapp/src` 现有 11 个页面里没有 AI 教练、模型设置、预算页；`src/api` 无 coach 域；TabBar 是"首页/训练计划/我的"三格（B3/B13 要新增入口）。

## 4. 环境受限（代码可写，结论不可宣称）

| 项 | 状态 | 影响 |
|---|---|---|
| MySQL 8.4 | 在用户服务器，本机无连接参数 | 迁移真实执行、预算行锁并发（B14 第 11 项）、`UNIQUE` 约束类验收只能标注未验证，并给出可复现命令 |
| Redis | 同上 | 会话/幂等真实行为未验证 |
| DeepSeek API Key | 未提供 | 真实供应商联调与"费用真实记账"只能标注未执行；按 B14 第 17 项，默认不发收费请求 |
| 微信开发者工具 | 已安装但未做 GUI 验证 | 小程序页面以 `build:mp-weixin` 通过 + 类型检查通过为限，运行时人工核对仍待做 |
| 本机 Docker | 不存在 | Testcontainers 组默认跳过 |

## 5. 与规划文档的冲突处理

- 设计包 `项目大纲与架构.md` 写 Java 21 → **作废**，按用户指示 Java 17（`AGENTS.md` 已固化）。
- 设计包 `schema.sql` 的 36 张表与 `deepseek-flash` / `deepseek-v4-pro`、峰谷价、`holiday_calendar_verified` 等**不得照抄**：B6 明确要求实施时重查当前官方文档，不能把旧设计里的模型名与价格写死。模型 ID、能力、报价以 `docs/agent-v1-protocol.md` 的核查结果为准。
- 设计包 `training_plan.source_job_id/source_run_id` 两个列与外键本轮仍不建（它们指向 `processing_job`/`agent_run` 的 `(id,user_id)` 复合键；本轮建 `agent_run` 后，`source_run_id` 才具备加回条件，届时单独出迁移）。

## 6. 本轮实现顺序（依赖驱动）

```
1 协议核查（agent-v1-protocol.md）        ← 没有它，适配器与价格只能靠猜
2 迁移：provider/catalog/credential/price/balance/policy/reservation/daily_usage/model_call
3 modelaccess：AES-GCM 凭据 + 余额 + 能力   ← 真实 Key 的存储与撤销
4 billing：价格核算 + 预占 + 幂等结算 + BudgetGate（非默认放行）
5 agent/*：ModelClient 契约、DeepSeekAdapter、ToolRegistry/Executor、
           ContextManager、Runner、TraceRecorder、AnswerAssembler、FakeModelClient
6 modules/coach：会话/run/estimate/steps/cancel + 3 个真实工具
7 miniapp：模型设置页 + 教练聊天页（HTTP 轮询）
8 测试与交付文档（acceptance / demo / README）
```
