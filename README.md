# FitAgent（智能健康训练智能体小程序）

当前状态：**P1 基础功能 + Agent 第一版 + 一轮 UI 重排 + 阶段 2（训练执行记录、基础统计、两个 Agent 查询工具）的代码与装配全部落地**；能在本机执行的验证都已执行通过，依赖你的 MySQL 8.4 / Redis / 微信凭据 / DeepSeek Key 的项目在 `docs/stage2-acceptance.md` §4、`docs/agent-v1-acceptance.md` 与 `docs/ui-render-acceptance.md` 里逐条标为未验证。

本机已执行并通过：`mvn test` **422 用例 0 失败 0 错误**（2026-10-16 16:31，`BUILD SUCCESS`）· `vue-tsc --noEmit` **0 错误** · `npm run build:mp-weixin` **构建完成（17 个页面）** · `npm run check:theme` **9 组断言通过**（色值/对比度/零硬编码/无 WXSS 不认的通配选择器/图标名/tabBar 图标）。

## 你要建的表：8 张（P1）+ 13 张（Agent 第一版）+ 3 张（阶段 2）

| 顺序 | 表 | 用途 |
|---|---|---|
| 1 | `user_account` | 微信身份、账户状态、业务时区、`data_revision` |
| 2 | `user_profile` | 身高、训练经验、器械等背景（**无体重列**） |
| 3 | `user_goal` | 手工基础目标，每用户最多一个 ACTIVE |
| 4 | `body_measurement` | 体重记录，最新体重按 `measured_at` 判定 |
| 5 | `exercise_reference` | 动作字典（本轮只读，含 25 条种子） |
| 6 | `training_plan` | 计划版本头，DRAFT/ACTIVE/ARCHIVED/DISCARDED |
| 7 | `training_plan_day` | 每周计划日，`UNIQUE(plan_id, iso_weekday)` |
| 8 | `training_plan_item` | 计划日内动作条目 |

Agent 第一版增量 13 张（V10–V22）：`model_provider`、`model_catalog`、`model_price`、`model_credential`、`provider_balance_snapshot`、`budget_policy`、`budget_daily_usage`、`budget_reservation`、`chat_conversation`、`agent_run`、`model_call`、`agent_step`、`chat_message`。

阶段 2 增量 3 张（V23–V25）：`workout_session`（实际训练场次，含 `active_user_id` STORED 生成列 + `UNIQUE` 实现的「同一用户至多一条进行中草稿」闸门）、`workout_session_entry`（动作条目）、`workout_session_set`（逐组）。**没有 metrics 表**：当天对比与周统计都是查询时的确定性计算，不预建报表。

SQL 全文：

- **一次建齐 21 张表：`docs/sql/fitagent-full-schema-mysql-8.4.sql`**（V1..V22 逐字拼接，含建库语句与 25 条动作字典种子）
- 分批版本：`docs/sql/p1-init-mysql-8.4.sql`（V1..V9，8 张表）、`docs/sql/agent-v1-coach-and-foundation-mysql-8.4.sql`（V10..V22，13 张表）
- **阶段 2 三张表：`docs/sql/stage2-workout-mysql-8.4.sql`**（V23..V25 逐字拼接 + 前置检查 + 9 条真库探针 P1–P9）。这份脚本**我没有在任何数据库上执行过**，是交付给你在 MySQL 8.4 上跑的；它同样不含 DROP/DELETE/TRUNCATE。
- 手工执行方式：`mysql --default-character-set=utf8mb4 -h <你的 8.4 主机> -u <账号> -p < docs/sql/fitagent-full-schema-mysql-8.4.sql`
- 目标库名是 **`smart_health_training`**（你的实际库），三份脚本与应用默认 JDBC URL、`infra/.env.example`、`docker-compose` 已同步改成它。
  脚本不是可重复执行的：`CREATE TABLE` 没有 `IF NOT EXISTS`（与 Flyway 迁移一致），之前跑到一半失败的残留表会报 1050；先按脚本头部的 `information_schema` 查询看缺哪几张，只补那几段，**不要 DROP**。
- 两份都**不含任何 DROP/DELETE/TRUNCATE**，都要求 MySQL 8.4。
- **模型与价格种子（原预留 V23，现改为 V26）故意不出**：`deepseek-v4-pro` 的适用性与当期计费在官方两条公告里互相矛盾，需要你核对后再单独出迁移（`docs/agent-v1-protocol.md` §1）。没有种子行时，`ProviderCatalogService` 会以 `MODEL_NOT_CONFIGURED` 拒绝发起调用，而不是猜一个模型名。
  ⚠ V23–V25 已被阶段 2 的 `workout_session` / `workout_session_entry` / `workout_session_set` 占用，**这份种子迁移的下一个是 V26**；`docs/agent-v1-acceptance.md`、`docs/agent-v1-demo.md` 里残留的「V23」都按 V26 理解。

SQL 全文：**`docs/sql/p1-init-mysql-8.4.sql`**（由 `backend/app/src/main/resources/db/migration/V1..V9` 逐字拼接生成，含建库语句与字典种子，**不含任何 DROP/DELETE**）。

两条路线**只选一条**：

- A（推荐）：让应用启动时由 Flyway 自动迁移。迁移账号需要 `CREATE/ALTER/INDEX/REFERENCES/TRIGGER` 等 DDL 权限，`FITAGENT_FLYWAY_ENABLED=true`。
- B：你先手工执行上面那份 SQL，然后把 `FITAGENT_FLYWAY_ENABLED=false`。否则 Flyway 会因对象已存在而失败。

硬性前提：**MySQL 8.4**（至少 8.0.16）。8.0.16 之前 CHECK 约束不生效，而 5.7 连 `utf8mb4_0900_ai_ci` 都不存在，脚本第一步就会报错。

## 启动命令

> 完整的起服务 + 小程序点测流程（含 20 步该看到什么）见 [`docs/run-and-test.md`](docs/run-and-test.md)；模型/报价种子见 `docs/sql/agent-v1-model-seed-flash-mysql-8.4.sql`。

```bash
# 后端（需要能连到你的 MySQL 8.4 与 Redis）
cd backend
set -a; . ../infra/.env; set +a   # Windows 用 Git Bash；.env 里的值含 & 必须加双引号，否则会被 shell 当后台命令
SPRING_PROFILES_ACTIVE=dev mvn -B -pl app -am spring-boot:run

# 小程序（微信开发者工具导入 dist/dev/mp-weixin）
cd miniapp
npm install
npm run dev:mp-weixin
```

`infra/.env.example` 是变量清单；`.env` 不提交。`infra/docker-compose.yml` 把 MySQL 映射到 **13306**、Redis 到 **16379**，是为了避开你这台机器上已在跑的 5.7(3306) 与 Redis 3.2(6379)。

## Agent 第一版的运行配置

全部配置项都写进了 `backend/app/src/main/resources/application.yml`（带注释），密钥与地址走环境变量：

| 环境变量 | 用途 | 不设置会怎样 |
|---|---|---|
| `FITAGENT_MASTER_KEY` | 凭据 AES-256-GCM 主密钥（base64 的 32 字节） | 保存 Key 时报配置错误；**不会**退化成默认密钥 |
| `FITAGENT_KEY_FINGERPRINT_KEY` | 判"是不是同一把 Key"的 HMAC 密钥，**必须与主密钥不同** | 同上；两把密钥相同直接启动失败 |
| `FITAGENT_KEY_VERSION` | 加密密钥版本号，落 `model_credential.encryption_key_version` | 默认 `v1` |
| `FITAGENT_DEEPSEEK_BASE_URL` | 供应商地址；表里的 `model_provider.base_url` 是最终权威值 | 默认 `https://api.deepseek.com`（文档路径不带 `/v1`） |
| `FITAGENT_AGENT_FAKE_MODEL_ENABLED` | 本地演示消息循环用的假模型开关 | 默认 `false`；在 `prod`/`production` 下为 `true` 会让应用**启动失败** |
| `FITAGENT_WECHAT_APPID` / `_SECRET` | 微信登录 | 登录接口报配置错误，dev 登录仅在 `dev` profile 可用 |

闸门数值（`fitagent.agent.limits.*`、`fitagent.coach.worker.*`、`fitagent.agent.model.deepseek.*`）都有代码默认值，改配置即可，**它们是工程上限，不是产品或健康规则**。

用户侧三步（BYOK，费用记在你自己的供应商账户上）：

1. 我的 → 模型设置：保存 API Key（只见脱敏尾部）、查看余额快照与查询时间；
2. 同一页设置单次任务与当日预算（`budget_policy`）。**没有生效策略与 PEAK 档报价时 BudgetGate 直接拒绝发起调用**，不存在"没设预算就随便花"；
3. AI 教练页提问：输入区显示预估与限额，页面按 run 轮询状态、事实卡片、来源、缺失项与本次费用。

## 主题在哪里改

小程序是淡黄色（奶油底 + 白卡片 + 淡黄强调）三处集中管理：

| 文件 | 作用 |
|---|---|
| `miniapp/src/uni.scss` | **色值、字号与留白尺度的唯一权威**（uni-app 自动注入，页面直接写 `$fa-*`） |
| `miniapp/src/styles/theme.ts` | 给模板属性与 canvas 用的 JS 侧副本（如 `<switch :color>`、折线描边色） |
| `miniapp/src/App.vue` `<style>` | 全局控件类：`.fa-btn`、`.fa-card`、`.fa-input`、`.fa-banner--*`、`.fa-chip--*`、`.fa-tiles`、`.fa-toolbar`、`.fa-bottom-bar` |
| `miniapp/src/styles/icons.scss` | **生成物**：`.fa-icon--<name>--<tone>`（base64 SVG），改几何跑 `npm run gen:icons` |
| `miniapp/src/static/tabbar/` | **生成物**：tabBar 的 81×81 PNG，跑 `npm run gen:tabbar` |

改完跑 `cd miniapp && npm run check:theme`：它核对两处色值一致、`pages.json` 只用 token、源码零硬编码色值、淡黄不被当文字色、对比度达标（文字 4.5:1 / 图形 3:1）、**没有微信编译器不认的 `> *` 通配选择器**、模板里静态图标名真实存在、tabBar 图标存在且 ≤40KB。规范与实测值见 [`docs/ui-style-guide.md`](docs/ui-style-guide.md)，UI 重排的页面去向对照见 [`docs/ui-render-plan.md`](docs/ui-render-plan.md)。

## 功能核查与导读

| 文件 | 内容 |
|---|---|
| [`docs/feature-audit-before.md`](docs/feature-audit-before.md) | 逐页/逐链核查矩阵：已实现且验证、有代码未验证、行为有误、缺失、占位、环境受限、阶段未实施七类状态与证据 |
| [`docs/feature-audit-after.md`](docs/feature-audit-after.md) | 修复项、代码位置、明确没修的东西与理由、仍未验证清单 |
| [`docs/module-guide.md`](docs/module-guide.md) | 目录职责、一个请求路过的地方、AI 教练完整链路、一页对应一条链 |
| [`docs/ui-style-guide.md`](docs/ui-style-guide.md) | 主题 token、尺度规范、图标系统与图表口径、状态表达规则、检查覆盖与不覆盖什么 |
| [`docs/ui-render-plan.md`](docs/ui-render-plan.md) | UI 重排对照表：原有功能分别去了哪个新入口/新区域、新增组件、明确不做的图形与理由 |
| [`docs/current-status-handoff.md`](docs/current-status-handoff.md) | **当前状态与阶段验收交接报告**：功能状态矩阵、核心流程复现到哪一步、Agent 与费用的真实证据等级、问题清单与下一阶段唯一建议（§0 是阶段 2 落地后的版本更正） |
| [`docs/stage2-design.md`](docs/stage2-design.md) | 阶段 2 设计：三表与四条不变式、接口、状态机、计划版本引用口径、统计口径、两个 Agent 工具、前端结构 |
| [`docs/stage2-acceptance.md`](docs/stage2-acceptance.md) | 阶段 2 验收：执行环境、实际跑过的命令与数字、循环调用证据、交付清单、**8 条未验证项及各自的复现方式** |
| [`docs/ui-render-acceptance.md`](docs/ui-render-acceptance.md) |
逐页改动、自动化检查结果、我真正"看见"过的渲染证据、仍未验证清单（微信可视验收靠这一节） |

## 阶段 A 规划文档

| 文件 | 内容 |
|---|---|
| [`docs/p1-scope.md`](docs/p1-scope.md) | 本轮做什么、不做什么、功能流程、页面清单、开发顺序、验收条件 A1–A22 |
| [`docs/p1-architecture.md`](docs/p1-architecture.md) | 目录职责、模块边界与调用方向、**已核实的版本锁定表**、认证架构、后续接入自研 Agent 的边界 |
| [`docs/p1-api.md`](docs/p1-api.md) | 接口契约、鉴权、错误码、分页排序上限、状态迁移矩阵 |
| [`docs/p1-database.md`](docs/p1-database.md) | 8 张基础表字段/索引/CHECK/外键、与 `schema.sql` 的差异、Flyway 顺序、大小写口径、后续加回 AI 溯源列的 SQL |
| [`docs/p1-plan-import.md`](docs/p1-plan-import.md) | 导入模板字段规范、类型×字段校验矩阵、错误定位格式、动作字典种子清单（25 条） |
| [`docs/templates/training-plan-template.v1.json`](docs/templates/training-plan-template.v1.json) | 真实可导入的训练计划模板（已用脚本核对：JSON 合法、代码全部命中字典、星期无重复） |
| `FitAgent_项目设计/` | 你提供的 v1.0 设计包原文，只读参考，未改动 |

## Agent 第一版文档

| 文件 | 内容 |
|---|---|
| [`docs/agent-v1-baseline.md`](docs/agent-v1-baseline.md) | **仓库真实能力核查**：已验证 / 代码存在未运行 / 缺失 / 环境受限四类；为什么本轮只注册 3 个工具 |
| [`docs/agent-v1-protocol.md`](docs/agent-v1-protocol.md) | DeepSeek 官方协议与报价核查（2026-10-13），含"未能核实"清单；实现与种子的唯一取值依据 |
| [`docs/agent-v1-design.md`](docs/agent-v1-design.md) | 架构、目录与依赖方向、核心契约、执行循环、凭据/价格/预算、表与增量迁移、状态与恢复、接口 |
| [`docs/agent-v1-acceptance.md`](docs/agent-v1-acceptance.md) | 实际测试命令与结果、**7 条红线的逐条证据（文件:行 / 测试名）**、未验证项、评测集为何还没跑 |
| [`docs/agent-v1-demo.md`](docs/agent-v1-demo.md) | 从设置 Key 到一次提问、工具调用、来源与费用的演示步骤，含预算停止与工具失败两个反例 |
| [`docs/agent-v1-selfbuilt.md`](docs/agent-v1-selfbuilt.md) | 自研了哪些核心类、复用了哪些业务服务、一个问题如何跑完整条循环、后续计划草稿/RAG 怎么扩 |

## 技术栈（已按 Java 17 锁定）

- 后端：Java 17 + Spring Boot 4.1.1 + Spring Security 7.1.1 + MyBatis-Plus 3.5.17（`mybatis-plus-spring-boot4-starter`）+ Flyway 12.4.0 + MySQL 8.4 + Redis
- 小程序：uni-app + Vue 3 + TypeScript + Pinia 2.x
- 基础设施：`infra/docker-compose.yml`（阶段 B 交付，本机未执行）

## 本机环境事实（已实测，2026-10-13）

可用：JDK 17.0.10、Maven 3.8.8（平台编码 GBK，故所有构建强制 UTF-8）、Node 24.18.0 + npm 11.16.0（registry 已是 npmmirror）、微信开发者工具、Redis 3.2.100（在用，非官方移植版）。

缺失或由你自行安装：**Docker 未安装**；本机在用的是 **MySQL 5.7.19**，它无法满足 `utf8mb4_0900_ai_ci` 与 CHECK 约束要求，**不能用于验证本阶段迁移**（详见 `docs/p1-database.md` §2）。因此迁移类与并发类验收项在阶段 B 交付时会如实标注为"未验证"，并附可复现命令。

## 仍然没有做的

**AI 生成/修改训练计划**：计划只能由用户手工新建或 JSON 模板导入，且导入只出 DRAFT、需用户确认才生效。这是下一阶段的唯一主任务建议（见 `docs/current-status-handoff.md` §0）。

其余同样未做：饮食图片识别、能耗公式与医学阈值、RAG/向量库、文件上传、Python 服务、教学视频、MCP、多 Agent、自动修改或激活训练计划。

> 阶段 2 之前这里写的是「训练执行记录与完成度统计不存在、`workout_session/entry/set` 与 metrics 都不存在、Agent 只有三个工具」。**那段已过期**：三表、`modules/metrics` 与 `get_training_progress`/`get_weekly_training_summary` 两个工具都在 2026-10-16 落地，「今天练得怎么样」「这周练了几次」现在有据可答。范围与验收分别见 `docs/stage2-design.md`、`docs/stage2-acceptance.md`。
