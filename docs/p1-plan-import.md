# FitAgent P1 训练计划 JSON 导入：模板与字段规范

版本：v1.0 规划稿；日期：2026-10-13。模板文件：`docs/templates/training-plan-template.v1.json`。

> 阶段 A 产物。本文件与模板**不写入业务数据库**；模板里的动作代码依赖 `V9__seed_exercise_reference.sql` 的种子（见 §9），阶段 B 一并落地。

---

## 1. 入口与载体

| 入口 | 实现 | 平台事实 |
|---|---|---|
| 从微信会话选文件 | `wx.chooseMessageFile({count:1, type:'file', extension:['json']})` → `wx.getFileSystemManager().readFile({filePath, encoding:'utf8'})` → `JSON.parse` → 提交文本 | 微信**没有** `wx.chooseFile`；`uni.chooseFile` 官方兼容表在小程序标注不可用。`chooseMessageFile` 只能选**聊天会话里的文件**，所以页面引导"先把模板发送到微信聊天或文件传输助手"。`readFile` 不返回 Promise，需回调包装或 `readFileSync` |
| 粘贴 JSON | 多行文本框，同样走后端校验 | 跨端一致，作为保证可用的兜底路径 |

两条路径提交**完全相同的请求体**（UTF-8 JSON 文本），共用同一套校验、预览与确认逻辑，能力等价。

后端**不接收 multipart、不保存原件、不做图片/PDF 解析**。`FileSystemManager.readFile` 的返回值只在内存中短暂存在，提交后丢弃。

---

## 2. 顶层字段

| 字段 | 类型 | 必填 | 规则 | 违规错误码 |
|---|---|---|---|---|
| `schema_version` | string | 是 | 必须精确等于 `fitagent.training_plan/1` | `UNSUPPORTED_SCHEMA_VERSION` |
| `title` | string | 是 | 1–160 字符，非空白 | `REQUIRED` / `TOO_LONG` |
| `starts_on` | string | 是 | `YYYY-MM-DD`（ISO local date）；拒绝时间戳、斜杠格式、带时区值 | `REQUIRED` / `INVALID_DATE_FORMAT` |
| `ends_on` | string \| null | 否 | 同上；若给出必须 `>= starts_on` | `INVALID_DATE_FORMAT` / `ENDS_BEFORE_STARTS` |
| `notes` | — | 否 | **P1 的 `training_plan` 没有备注列，因此该字段一律拒收**：写了即报 `PLAN_NOTES_UNSUPPORTED`，不做"先接受、再悄悄丢弃"。计划日与动作条目的 `notes` 有真实列，不受影响；手工创建路径（`PlanUpsertRequest`）本来就没有这个字段，两条入口形状一致 |
| `days` | array | 是 | 1–7 个元素；`iso_weekday` 唯一 | `REQUIRED` / `TOO_MANY_DAYS` / `DUPLICATE_WEEKDAY` |

**未定义字段一律拒绝**（Jackson `FAIL_ON_UNKNOWN_PROPERTIES=true`）→ `UNKNOWN_FIELD`。这既挡住了随手写错的键名，也挡住了下面 §7 的服务端字段注入。

---

## 3. `days[i]` 字段

| 字段 | 类型 | 必填 | 规则 | 错误码 |
|---|---|---|---|---|
| `iso_weekday` | int | 是 | 1=周一 … 7=周日；同计划内唯一 | `REQUIRED` / `INVALID_WEEKDAY` / `DUPLICATE_WEEKDAY` |
| `title` | string \| null | 否 | ≤ 120 字符 | `TOO_LONG` |
| `is_rest_day` | boolean | 是 | 必须是 JSON 布尔，不接受 `0/1`、`"true"` | `REQUIRED` / `INVALID_TYPE` |
| `notes` | string \| null | 否 | ≤ 800 字符 | `TOO_LONG` |
| `items` | array | 是 | `is_rest_day=true` 时必须为 `[]`；false 时 1–30 个 | `REST_DAY_HAS_ITEMS` / `EMPTY_TRAINING_DAY` / `TOO_MANY_ITEMS` |

## 4. `days[i].items[j]` 字段

| 字段 | 类型 | 必填 | 规则 | 错误码 |
|---|---|---|---|---|
| `exercise_code` | string | 是 | 大小写规范化后（`toUpperCase(ROOT)`）必须命中 `status='ACTIVE'` 的字典 | `REQUIRED` / `EXERCISE_NOT_FOUND` / `EXERCISE_DISABLED` |
| `position_no` | int | 否 | 若给出必须 ≥1 且在当日唯一；缺省时按数组顺序赋值 | `INVALID_WEEKDAY` 无关；`DUPLICATE_POSITION` |
| `target_sets` | int \| null | 见 §5 | ≥1 | `INVALID_TYPE` / `MUST_BE_POSITIVE` |
| `reps_min` | int \| null | 见 §5 | ≥1 | `MUST_BE_POSITIVE` |
| `reps_max` | int \| null | 见 §5 | `>= reps_min`（两者都给时） | `REPS_MAX_LT_MIN` |
| `target_external_load_kg` | number \| null | 见 §5 | ≥0；≤2 位小数；0 合法（自重） | `NEGATIVE_LOAD` / `TOO_MANY_DECIMALS` |
| `target_duration_seconds` | int \| null | 见 §5 | ≥1 | `MUST_BE_POSITIVE` |
| `rest_seconds` | int \| null | 否 | ≥0；≤3600 | `MUST_BE_POSITIVE` / `REST_OUT_OF_RANGE` |
| `notes` | string \| null | 否 | ≤ 800 字符 | `TOO_LONG` |

数值型不接受字符串数字（`"8"` 判 `INVALID_TYPE`）、不接受布尔、不接受 NaN/Infinity。

---

## 5. 动作类型 × 字段矩阵（按字典 `category` 分别校验）

`category` 由后端从 `exercise_reference` 查得，**不由输入文件声明**。这正是要"力量动作与时长动作分别验证各自字段"的落点。

| category | `target_sets` | `reps_min/max` | `target_external_load_kg` | `target_duration_seconds` | `rest_seconds` |
|---|---|---|---|---|---|
| `STRENGTH` | **必填** ≥1 | 可选；给则 `max>=min` | 可选 ≥0 | **必须 null** | 可选 |
| `CARDIO` | **必须 null** | **必须 null** | **必须 null** | **必填** ≥1 | **必须 null**（无组概念，不存在组间休息） |
| `MOBILITY` | 可选 | 可选；给则 `max>=min` | **必须 null** | 可选 | 可选 |
| `MOBILITY` 额外规则 | `target_sets`/`reps`/`target_duration_seconds` **至少给一项**，否则该条目无训练量 | | | | `MOBILITY_NO_TARGET` |

违规错误码：`STRENGTH_DURATION_FORBIDDEN`、`CARDIO_SETS_FORBIDDEN`、`CARDIO_REPS_FORBIDDEN`、`CARDIO_LOAD_FORBIDDEN`、`CARDIO_REST_FORBIDDEN`、`MOBILITY_LOAD_FORBIDDEN`、`MOBILITY_NO_TARGET`。

---

## 6. 单位与换算（写死，不接受别的单位）

| 语义 | 唯一合法单位 | 键名 | 明确不接受 |
|---|---|---|---|
| 时长 | 秒 | `target_duration_seconds`、`rest_seconds` | `minutes`/`hours`/`min`/`"10min"`/带单位字符串 |
| 外部负重 | kg | `target_external_load_kg` | `lb`/`公斤`/`kg` 字符串 |
| 日期 | ISO local date | `starts_on`/`ends_on` | `2026/10/12`、时间戳、带 `Z` 的时间 |
| 星期 | ISO 1–7（1=周一） | `iso_weekday` | `Mon`/`周一`/0–6/1–7 但周日起算 |

页面提供换算提示表：1 min = 60 s，1 h = 3600 s。**不做隐式换算**，防止把 30 当成 30 分钟存成 30 秒。

---

## 7. 服务端字段黑名单（不得被输入控制）

以下键出现在任何层级都判 `FORBIDDEN_SERVER_FIELD`（区别于 `UNKNOWN_FIELD`，错误文案会说明"该字段由服务端生成"）：

`id`, `userId`, `user_id`, `owner`, `ownerUserId`, `planId`, `plan_id`, `planGroupUuid`, `plan_group_uuid`, `revisionNo`, `revision_no`, `basePlanId`, `base_plan_id`, `source`, `status`, `activeUserId`, `active_user_id`, `lockVersion`, `lock_version`, `goalSnapshot`, `goal_snapshot`, `confirmedAt`, `confirmed_at`, `createdAt`, `created_at`, `updatedAt`, `updated_at`, `exerciseId`, `exercise_id`, `planDayId`, `plan_day_id`, `businessDate`, `dataRevision`

- `plan_group_uuid` 与 `revision_no` **一律由服务端生成**（UUID v4 小写；`revision_no` 取组内 max+1）。不接受客户端传入的理由：客户端持有的 group 若与既有计划撞车，会被静默写成"新版本"，破坏"复制计划创建新计划组"的语义。
- `source` 由后端置为 `IMPORT`；`status` 恒为 `DRAFT`。导入**不可能**直接把计划变成 ACTIVE。
- `goal_snapshot` 由后端按当前档案与 ACTIVE 目标生成，客户端无法注入。

---

## 8. 上限、错误格式与校验顺序

| 限制项 | 值 | 错误码 |
|---|---|---|
| 请求体 UTF-8 字节数 | ≤ 256 KiB | `PAYLOAD_TOO_LARGE` |
| 计划日数量 | 1–7 | `TOO_MANY_DAYS` |
| 单日动作数 | 1–30 | `TOO_MANY_ITEMS` |
| 全计划动作总数 | ≤ 120 | `TOO_MANY_ITEMS_TOTAL` |
| 单个字符串 | 见各字段 | `TOO_LONG` |
| 单日 `notes` | ≤ 800；条目 `notes` ≤ 800 | `TOO_LONG` |

**校验顺序**（短路策略：结构层失败即返回，不做字典查询；避免半量校验产生误导性错误）：

1. 字节数 → 2. JSON 可解析 → 3. `schema_version` → 4. 未知/禁用字段 → 5. 类型与必填 → 6. 字符串长度与数值范围 → 7. `days` 结构（星期唯一、休息日与 items 一致性）→ 8. `position_no` 唯一 → 9. 字典匹配（`exercise_code`）→ 10. 类型×字段矩阵 → 11. 跨字段（`reps_max>=reps_min`、`ends_on>=starts_on`）

**错误定位格式**：`path` 用 JSON 路径，数组用下标，与页面渲染一一对应。

```json
{
  "code": "PLAN_IMPORT_INVALID",
  "message": "训练计划校验未通过，共 2 处错误",
  "errors": [
    { "path": "days[0].items[1].reps_max", "code": "REPS_MAX_LT_MIN", "message": "次数上界不能小于下界", "value": 5 },
    { "path": "days[2].items[0].exercise_code", "code": "EXERCISE_NOT_FOUND", "message": "动作代码不在有效动作字典中：BENCHMARK_PRESS", "value": "BENCHMARK_PRESS" }
  ],
  "warnings": [
    { "path": "days[1].items", "code": "POSITION_RENUMBERED", "message": "position_no 不连续，已按数组顺序重排为 1..n" }
  ]
}
```

`warnings` 不阻断确认；`errors` 非空即不能确认。字典匹配失败**绝不自动新增动作**（设计包 §6.4 同一原则：不能把未知输入塞进系统字典）。

---

## 9. 动作字典种子清单（`V9__seed_exercise_reference.sql`，25 条）

`source_url` 与 `instructions` 全部留 NULL：本轮不引入未经来源核对的动作说明文本，宁可留空也不伪造权威链接。`status` 全为 `ACTIVE`，`role` 层面本轮无可写入口。

| exercise_code | name | category | muscle_groups | equipment |
|---|---|---|---|---|
| `BARBELL_BACK_SQUAT` | 杠铃深蹲 | STRENGTH | 股四头肌, 臀大肌, 腘绳肌 | BARBELL |
| `BARBELL_DEADLIFT` | 杠铃硬拉 | STRENGTH | 腘绳肌, 臀大肌, 竖脊肌 | BARBELL |
| `BARBELL_BENCH_PRESS` | 杠铃卧推 | STRENGTH | 胸大肌, 三角肌前束, 肱三头肌 | BARBELL, BENCH |
| `BARBELL_OVERHEAD_PRESS` | 杠铃站姿肩上推 | STRENGTH | 三角肌, 肱三头肌, 斜方肌 | BARBELL |
| `BARBELL_ROW` | 杠铃划船 | STRENGTH | 背阔肌, 斜方肌, 肱二头肌 | BARBELL |
| `DUMBBELL_BENCH_PRESS` | 哑铃卧推 | STRENGTH | 胸大肌, 三角肌前束, 肱三头肌 | DUMBBELL, BENCH |
| `DUMBBELL_ROW` | 哑铃划船 | STRENGTH | 背阔肌, 斜方肌, 肱二头肌 | DUMBBELL, BENCH |
| `DUMBBELL_REVERSE_LUNGE` | 哑铃后撤步弓步 | STRENGTH | 股四头肌, 臀大肌, 腘绳肌 | DUMBBELL |
| `LAT_PULLDOWN` | 高位下拉 | STRENGTH | 背阔肌, 肱二头肌 | CABLE_MACHINE |
| `SEATED_CABLE_ROW` | 坐姿绳索划船 | STRENGTH | 背阔肌, 斜方肌, 肱二头肌 | CABLE_MACHINE |
| `LEG_PRESS` | 腿举 | STRENGTH | 股四头肌, 臀大肌 | MACHINE |
| `PUSH_UP` | 俯卧撑 | STRENGTH | 胸大肌, 三角肌前束, 肱三头肌 | BODYWEIGHT |
| `INVERTED_ROW` | 反向划船 | STRENGTH | 背阔肌, 斜方肌, 肱二头肌 | BODYWEIGHT |
| `RUNNING` | 跑步 | CARDIO | 下肢肌群 | OUTDOOR, TREADMILL |
| `TREADMILL_WALK_INCLINE` | 坡度快走 | CARDIO | 下肢肌群 | TREADMILL |
| `CYCLING` | 骑行 | CARDIO | 下肢肌群 | BIKE |
| `ROWING` | 划船机 | CARDIO | 下肢肌群, 背阔肌, 心肺 | ROWER |
| `ELLIPTICAL` | 椭圆机 | CARDIO | 下肢肌群, 心肺 | ELLIPTICAL |
| `STAIR_CLIMBER` | 楼梯机 | CARDIO | 下肢肌群, 心肺 | STAIR_CLIMBER |
| `JUMP_ROPE` | 跳绳 | CARDIO | 小腿, 肩部, 心肺 | JUMP_ROPE |
| `CAT_COW` | 猫牛式 | MOBILITY | 脊柱, 背部 | MAT |
| `HIP_FLEXOR_STRETCH` | 髋屈肌拉伸 | MOBILITY | 髂腰肌, 股直肌 | MAT |
| `HAMSTRING_STRETCH` | 腘绳肌拉伸 | MOBILITY | 腘绳肌 | MAT |
| `SHOULDER_CROSS_STRETCH` | 肩部横位拉伸 | MOBILITY | 三角肌, 上背 | MAT |
| `THORACIC_ROTATION` | 胸椎旋转 | MOBILITY | 胸椎, 核心 | MAT |

器械代码词表（与 `user_profile.equipment` 共用同一套，前后端各存一份常量表并保持同步）：`BARBELL` `DUMBBELL` `KETTLEBELL` `BENCH` `CABLE_MACHINE` `MACHINE` `TREADMILL` `BIKE` `ROWER` `ELLIPTICAL` `STAIR_CLIMBER` `JUMP_ROPE` `MAT` `BODYWEIGHT` `OUTDOOR` `BAND`。

---

## 10. 预览与确认契约

| 步骤 | 接口 | 副作用 | 关键返回 |
|---|---|---|---|
| 校验+预览 | `POST /api/plans/import/preview` | **无**（不落库、不占用幂等键） | 规范化后的计划结构（含字典解析出的 `exerciseId`/`name`/`category`、服务端预排的 `position_no`）、`errors[]`、`warnings[]`、字节与条目统计 |
| 确认保存 | `POST /api/plans/import` | 事务内聚合写入 `training_plan` + `training_plan_day` + `training_plan_item`，状态 `DRAFT`、`source=IMPORT` | `planId`、`revisionNo`、`status`、`idempotentReplay` |

- 确认接口**重新执行完整校验**，不信任 preview 结果（preview 与 confirm 之间字典可能被维护、幂等键可能已被用）。
- `Idempotency-Key` 缺失 → 服务端为本次请求生成一个并在响应里回传；同键重放返回首次结果 + `idempotentReplay=true`。Redis TTL 24h（已确认取舍）。
- 确认成功后不自动激活，首页仍显示原 ACTIVE 计划；用户需进草稿详情页显式点"确认生效"。

## 11. 阶段 B 必须跑的导入用例（对应验收 A15–A17）

| # | 输入 | 期望 |
|---|---|---|
| 1 | 交付模板原样 | preview 0 错误 → confirm 成功 → 草稿逐字段与模板一致 |
| 2 | 截断/语法错 JSON | `MALFORMED_JSON`，不进入字段校验 |
| 3 | `schema_version` 改成 v2 | `UNSUPPORTED_SCHEMA_VERSION` |
| 4 | 未知动作码 + 拼错大小写混用 | `EXERCISE_NOT_FOUND`，`path` 精确到 items[j] |
| 5 | 两个 `iso_weekday=2` | `DUPLICATE_WEEKDAY`（预览层）；若绕过预览，DB `UNIQUE(plan_id,iso_weekday)` 仍拒绝 |
| 6 | `"status":"ACTIVE"` 混入输入 | `FORBIDDEN_SERVER_FIELD`，保存结果仍为 DRAFT |
| 7 | CARDIO 动作给 `target_sets` | `CARDIO_SETS_FORBIDDEN` |
| 8 | 休息日带 items | `REST_DAY_HAS_ITEMS` |
| 9 | 30 个动作 + 第 15 个外键失败 | 整体回滚，`training_plan` 行数 0 |
| 10 | 同 `Idempotency-Key` 二次确认 | 同一 `planId`，计划行数不增 |
| 11 | 超 256 KiB | `PAYLOAD_TOO_LARGE` |
| 12 | 时长写成 `"10min"` | `INVALID_TYPE` + 单位说明 |
