# 阶段 2 设计：训练执行记录、基础统计与 Agent 查询

日期：2026-10-16。基线：`main@348961c` + 本轮改动。范围依据：`docs/current-status-handoff.md` §2.8 与用户本轮授权。
**本轮不做**：AI 生成/修改计划、饮食/图片/视频、RAG、能耗公式、训练效果评分、新 Agent 框架、Java/Spring Boot 版本切换。

## 1. 用户闭环

从今天安排点「开始」→ 建实际训练草稿 → 逐动作逐组记录（次数/可选负重/时长/距离）→ 可保存退出、再进续填同一草稿 →
点「结束」并确认才成为正式已确认记录 → 看本次详情 / 历史 / 当天计划对比 / 本周基础统计 → Agent 工具查同一份结果。
**没有参考计划、休息日也能自主记录**；计划安排永远不会自动变成已完成数据。

## 2. 模块与依赖方向

```
modules/workout   实际执行：session/entry/set 的增删改查、幂等、草稿闸门、版本
modules/metrics   只读查询与确定性计算：当天对比、本周统计（不建统计表、不调模型）
modules/training  计划版本（现有）
modules/coach/tools  两个新工具，只调 workout/metrics 的 Service
```
- `workout → training`（校验参考版本）；`metrics → training + workout`；`coach/tools → metrics/workout Service`。
- **任何业务模块都不依赖 `agent/`**；不跨模块用 Mapper。

## 3. 三张新表（V23–V25）

### V23 `workout_session`

| 列 | 类型 | 语义 |
|---|---|---|
| `id` | BIGINT UNSIGNED AUTO PK | 对外一律字符串 |
| `user_id` | BIGINT NOT NULL | 来自 `AuthenticatedUsers.require()` |
| `client_record_uuid` | CHAR(36) NOT NULL | 幂等键，服务端规范化小写、列保持 CI（沿用 `AGENTS.md`） |
| `business_date` | DATE NOT NULL | 服务端按 session 选定时区推导；补录可指定过去日期 |
| `timezone` | VARCHAR(64) NOT NULL | 开始时快照的账户时区，之后不随账户改 |
| `plan_id` / `plan_day_id` | BIGINT NULL | 参考的计划版本与计划日；**成对出现** |
| `plan_revision_no` | INT NULL | 开始时的版本快照，用于"按原版本查询" |
| `started_at` / `ended_at` | DATETIME(6) NULL | UTC 瞬时；`ended_at` 只在确认结束时写 |
| `status` | VARCHAR(16) NOT NULL | `DRAFT` / `COMPLETED` / `DISCARDED` |
| `notes` | VARCHAR(500) NULL | 用户备注 |
| `lock_version` | BIGINT NOT NULL DEFAULT 0 | 聚合级乐观锁 |
| `created_at` / `updated_at` | DATETIME(6) NOT NULL | UTC |
| `active_user_id` | BIGINT GENERATED ALWAYS AS (`CASE WHEN status='DRAFT' THEN user_id ELSE NULL END`) **STORED** | **单草稿闸门**，实体里不声明 |

约束：`UNIQUE(user_id, client_record_uuid)`、`UNIQUE(active_user_id)`（**每用户至多一个进行中草稿**，历史完成记录不受限）、
`CHECK(status IN ('DRAFT','COMPLETED','DISCARDED'))`、`CHECK(plan_id IS NULL = plan_day_id IS NULL)`、
`CHECK(ended_at IS NULL OR status <> 'DRAFT')`、`FK(user_id) → user_account`、
`FK(plan_id,user_id) → training_plan(id,user_id)`、`FK(plan_day_id,user_id) → training_plan_day(id,user_id)`（**RESTRICT**，默认）。

### V24 `workout_session_entry`

`id`、`user_id`、`session_id`、`exercise_id`、`plan_item_id NULL`、`position_no INT NOT NULL`、
`target_duration_seconds NULL`（**动作实际时长**，与场次历时分开）、`distance_meters NULL`、`notes NULL`、时间戳。
`UNIQUE(session_id, position_no)`、`UNIQUE(session_id, plan_item_id)`（同一参考条目不得被拆成两行）、
`CHECK(position_no>=1)`、`CHECK(target_duration_seconds IS NULL OR >0)`、`CHECK(distance_meters IS NULL OR >0)`、
`FK(session_id,user_id) → workout_session(id,user_id)`、`FK(exercise_id) → exercise_reference(id)`、
`FK(plan_item_id,user_id) → training_plan_item(id,user_id)`。

### V25 `workout_session_set`

`id`、`user_id`、`entry_id`、`set_no INT NOT NULL`、`reps INT NULL`、`external_load_kg DECIMAL(6,2) NULL`、
`duration_seconds INT NULL`、`is_completed TINYSET(1) NOT NULL DEFAULT 0`、`notes`、时间戳。
`UNIQUE(entry_id, set_no)`、`CHECK(set_no>=1)`、`CHECK(reps IS NULL OR reps>=0)`、
`CHECK(external_load_kg IS NULL OR >=0)`、`CHECK(duration_seconds IS NULL OR >0)`、`FK(entry_id,user_id)`。
**`NULL` 是未知、`0` 是真实已知的零**；自重不自动填负重；`is_completed` 默认 0，**永不替用户确认**。

## 4. 关键不变式

1. **参考版本不能是 DRAFT**：`plan_id` 指向的计划在开始时必须是 ACTIVE/ARCHIVED/DISCARDED。
   因为 `TrainingPlanService.update()` 只在 DRAFT 态做"删条目→删计划日→重建"（`TrainingPlanService.java:147-148`），
   禁掉 DRAFT 引用后，**被引用的子项在数据库层面永远不会被删除**，无需改动现有计划编辑流程。
   归档/复制/另存都不删行 → 已记录执行永远能按 `plan_revision_no` 查回原版本。
2. **幂等**：`(user_id, client_record_uuid)` 唯一；同键同内容返回首次结果 + `idempotentReplay=true`；同键不同内容 → 既有幂等冲突码。
3. **单草稿**：并发两次「开始」→ 唯一闸门命中，返回可恢复的已有草稿（不是 500，也不是第二条）。
4. **草稿不计正式**：只有 `COMPLETED` 参与统计；`DISCARDED` 与删除都不参与。
5. **重复结束幂等**：对已 `COMPLETED` 的 session 再点结束 → 返回当前状态，不重复写 `ended_at`、不重复计数、不重复递增修订号。
6. **data_revision**：新增/确认/编辑/丢弃/删除按既有规则递增；旧 AI 事实快照靠修订号标过期，**不静默改写**。
7. 跨午夜结束不把业务日期自动挪到第二天。

## 5. 接口（沿用 `/api` 风格与统一错误规范）

| 方法 | 路径 | 语义 |
|---|---|---|
| POST | `/api/workout/sessions` | 开始或恢复草稿（`clientRecordUuid` 幂等；已有草稿则返回它） |
| GET | `/api/workout/sessions` | 本人分页（可按状态/日期区间） |
| GET | `/api/workout/sessions/{id}` | 聚合详情（含 entry/set 与参考计划） |
| PUT | `/api/workout/sessions/{id}` | 聚合保存动作与逐组，携带 `lockVersion`；子项整体替换（只属于本 session） |
| POST | `/api/workout/sessions/{id}/complete` | 结束确认（幂等） |
| POST | `/api/workout/sessions/{id}/discard` | 丢弃草稿 |
| DELETE | `/api/workout/sessions/{id}` | 删除本人记录（按既有删除规则） |
| GET | `/api/metrics/training/days/{businessDate}` | 当天计划 vs 已确认执行对比 |
| GET | `/api/metrics/training/weeks/{weekStartDate}` | 本周场次/天数/时长与缺失标记 |

Controller 不算指标；每个路径都单独校验归属（不给"按 id 查"只加 user_id 条件的侥幸）。

## 6. 统计口径（确定性计算，无模型、无效果分）

- **组数完成度**：以对应计划条目为单位
  `sum( min(该条目已确认完成组数, 该条目计划组数) ) / sum(该组目标计划组数) × 100%`；
  同一条目跨场次先汇总再封顶；同一组不重复计数；额外组与自主动作单列，**不抵消**其他条目缺口。
  必过示例：计划 A/B 各 3 组，实际确认 A=5、B=0 → 已记录组数完成度 **50%**、额外 **2** 组；
  页面文案是"已记录完成情况"，不因 B 没记录就断言现实中没做。
- **时长**：逐对应条目封顶后汇总，与组数**分开**；未知时长带 `missing_fields`，不当作已知 0。
- **次数是否在目标范围**：单列，不混进"组是否确认"。
- **场次历时**（`ended_at - started_at`）与动作实际时长分开展示，不相加成"总活动时长"。
- 空/分母 0/无已确认记录 → 返回 null + 原因码，**不给 0% 或 100%**。
- 不同计划版本、不同业务日期不混同一分母；一天引用多版本按版本分组；周统计**不平均每日百分比**，只给场次/天数/时长。
- 每个指标都带：指标码、值或 null、单位、规则版本（`RULE_VERSION`）、参考计划版本、session/entry 来源、缺失项、`data_revision`。

## 7. Agent 工具（复用现有循环，不重写执行器）

| 工具 | 参数 | 返回 |
|---|---|---|
| `get_training_progress` | `business_date`（严格 `YYYY-MM-DD`） | 本人当日计划执行对比与记录摘要 |
| `get_weekly_training_summary` | `week_start_date` | 本人一周基础统计与来源 |

单日工具不冒充周统计；周工具不接受"随便一个日期"当周界。沿用 `CoachToolNames`/`CoachToolConfiguration`/
`CoachToolSchemas`/`ToolResult` 与证据契约；参数不收 `user_id`；数字与引用由工具产出，**模型不得改写**。
能力清单与提示词改为"注册了几个工具"由注册表推导，不再硬编码"总共三个"。

## 8. 前端（沿用淡黄 + 图标短标签 + Fa* 组件，不再换版）

- 首页四宫格：**导入 → 训练**（导入仍在训练页工具条里，入口不丢）。
- 今日主卡按真实状态给「开始 / 继续」；休息日与无计划各有入口；**没有真实目标与已确认数据就不显示进度**。
- 训练模块二级分层：`训练` tab → 计划 / 记录 / 统计 三段，不把整周计划 + 全部历史 + 录入表挤一页。
- 记录页：一次聚焦当前动作，逐组勾选与次数/负重输入，计划参考与实际值视觉区分，底部「保存」次操作 +「结束」主操作（避开安全区）。
- 历史/详情可查可编辑；重进页面读同一 session，不新建。
- 进度环只在有目标且有已确认数据时出现；训练日历点阵表示**已确认训练**，与"安排"条阵是两套图形，不混用。
- 周统计：少量关键数字 + 简洁柱状（场次/天数分列）；未知与无记录保持诚实文案。

## 9. 验收边界（本轮实际能做到的）

- 单元/契约层（Mockito）：状态机、幂等、归属、封顶算法（含 3+3/5+0=50% 示例）、版本快照、工具参数校验与配对、停止条件复用。
- **真库项本轮未验证**（用户选择"先按无真库交付"，虚机 MySQL/Redis 不可达，本机 5.7 按 `AGENTS.md` 禁用于迁移验证）：
  CHECK 强制、STORED 生成列 + 条件唯一闸门、外键 RESTRICT 行为、并发双开始。
  交付：`docs/sql/stage2-workout-mysql-8.4.sql`（等价 DDL + 逐条断言的验证 SQL）与 `docs/stage2-acceptance.md` 里的复现步骤。
- 隔离空库全链路（V1→V25）同样需要真 MySQL 8，本轮只保证脚本可执行性经人工核对，不宣称通过。
