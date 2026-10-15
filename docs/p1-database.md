# FitAgent P1 基础功能：数据库设计与迁移规划

版本：v1.0 规划稿；日期：2026-10-13。依据：`FitAgent_项目设计/数据库设计与字段字典.md` 与 `FitAgent_项目设计/schema.sql`（v1.0，2026-10-12）。

本文档只做规划，**不执行建表**。阶段 B 才落地为 Flyway 迁移文件。

---

## 1. 本轮范围结论

本轮只创建 **8 张表**，全部沿用设计包的表名与核心字段：

| 顺序 | 表 | 本轮用途 |
|---|---|---|
| 1 | `user_account` | 微信身份、账户状态、业务时区、`data_revision` |
| 2 | `user_profile` | 身高、训练经验、器械、可选背景参数 |
| 3 | `user_goal` | 用户手工基础目标（减脂/增肌/维持/一般健康） |
| 4 | `body_measurement` | 手工体重记录，含可选腰围与体脂 |
| 5 | `exercise_reference` | 动作字典（系统维护，本轮只读 + 种子数据） |
| 6 | `training_plan` | 计划版本头，DRAFT/ACTIVE/ARCHIVED/DISCARDED |
| 7 | `training_plan_day` | 每周计划日模板（ISO 星期 1–7，支持休息日） |
| 8 | `training_plan_item` | 计划日内动作条目（组数/次数/负重/时长/休息） |

设计包标注为 P1 但**本轮不创建**的表：`daily_checkin`、`food_reference`、`met_activity`、`calculation_rule`、`media_asset`、`workout_session`、`workout_entry`、`workout_set`、`meal_record`、`meal_item`。

> 重要澄清：设计文档里的 "P1/P2/P3" 是**完整项目的模块分期**标签，不等于本次提示词的"本轮基础功能"范围。本轮范围由提示词第一节 6 条功能限定，只覆盖上面 8 张表。

P2/P3 的 28 张表（模型、凭据、预算、Agent 轨迹、任务、知识库等）一律不在本轮 DDL 中出现。

---

## 2. 本机环境事实与由此产生的约束

实测结果（2026-10-13，`java -version` / `mvn -v` / `netstat` / `tasklist` / `which`）：

| 组件 | 本机实际情况 | 影响 |
|---|---|---|
| JDK | **17.0.10**（`D:\software\Java\jdk17`） | 满足硬约束，按 Java 17 规划 |
| Maven | 3.8.8，`platform encoding: GBK` | 满足 Boot ≥3.6.3 要求；**必须在 pom 里强制 `project.build.sourceEncoding=UTF-8`**，否则含中文 COMMENT 的迁移脚本与源码在 Windows 下会乱码 |
| Docker | **不存在**（`docker` 命令未找到） | 无法 `docker compose up` 拉起 MySQL 8.4 |
| MySQL | 已运行 **5.7.19**（服务进程 `mysqld.exe`，端口 3306），另有 Navicat 16 | **不能用它验证本轮迁移**，原因见下 |
| Redis | 已运行 **3.2.100 Windows 版**（127.0.0.1:6379），另有 RedisInsight | 会话令牌只需 SET/GET/TTL/DEL，3.2 具备；但属废弃非官方移植，需实测 |
| Node/npm | v24.18.0 / 11.16.0，registry 已是 `registry.npmmirror.com` | 可安装 uni-app CLI 工程 |
| 微信开发者工具 | 已安装（`D:\software\微信web开发者工具\cli.bat`） | 可做微信小程序编译与真机预览验证 |
| 网络 | Maven Central 200，npm 镜像 200 | 依赖可下载 |

### 2.1 本机 MySQL 5.7.19 不可用于本轮迁移（硬结论）

`schema.sql` 与设计目标为 MySQL 8.4，本轮迁移同样按 8.4 语法编写。5.7 上有三处**功能性不符**：

1. `utf8mb4_0900_ai_ci` 是 8.0 引入的排序规则，5.7 不存在 → `CREATE DATABASE ... COLLATE utf8mb4_0900_ai_ci` **直接报错**，脚本第一步就跑不通。
2. 5.7 **解析但不执行 CHECK 约束**。本轮 `weight_kg > 0`、`reps_max >= reps_min`、`iso_weekday BETWEEN 1 AND 7`、`target_sets > 0` 等防线会在 5.7 上静默失效，导致"测试通过"是假象。提示词明确要求"不能仅用 H2 证明 MySQL 生成列和约束可用"，同理不能拿 5.7 冒充 8.4。
3. 5.7 的 `DATETIME(3) ON UPDATE CURRENT_TIMESTAMP(3)`、`JSON`、STORED 生成列虽然支持，但优化器/索引行为与 8.4 有差异，不足以支撑验收结论。

生成列（`active_user_id`）本身 5.7.6+ 即可用，但**不构成可以使用 5.7 的理由**。

### 2.2 因此本阶段的处置（按你的指示：不代你安装环境）

- 本轮**不安装** MySQL 8.4、不安装 Docker，也不改动本机现有 5.7 实例与其中任何数据。
- 阶段 B 仍交付 `infra/docker-compose.yml`（MySQL 8.4 + Redis 7）与 `.env.example`，作为规范路径。
- 迁移的**语法与约束验证**标记为"未在本机执行"，交付时给出可精确复现的命令（见 §9）。
- 阶段 B 的自动化测试分层，确保在没有 MySQL 8.4 时仍能覆盖业务规则：
  - Service 单元测试：Mockito，覆盖状态机、归属校验、校验器、最新体重回退逻辑；
  - Mapper/迁移集成测试：用 Testcontainers（`mysql:8.4`）跑真实 8.4 —— 需 Docker；无 Docker 时该组测试 `@Tag("requires-docker")` 默认跳过，并在交付说明中如实标注跳过项；
  - 并发激活只剩一个 ACTIVE、DuplicateKey 并发注册这两条**必须**在真实 MySQL 8.4 上证明，不允许用 H2 结论代替。
- 如果你更希望走"不装 Docker"的路线：在你已有的 Ubuntu WSL 里 `apt install mysql-server-8.4`，或在 Windows 上解压 MySQL 8.4 ZIP 到独立端口（如 3307）+ 独立 datadir，均可跑通同一套 Flyway 迁移；`jdbc.url` 通过环境变量注入即可，不需要改代码。这两种方式由你决定并执行。

Redis 3.2.100：Boot 4.1.1 管理的是 **Lettuce 7.5.2**（不是 Boot 3.5 下的 Lettuce 6），它与这个废弃的 Windows 移植版之间的兼容性**未核实**。阶段 B 在 B-0 就真实连一次做 `SET/GET/TTL/DEL` 冒烟；若不通，改用 WSL/Docker 的 Redis 7.x，代码不需要改动（只换 `spring.data.redis.*`）。

---

## 3. 逐表设计（本轮 8 张）

以下字段与设计包 `schema.sql` 一致，**差异集中在 §4**。ID 全部 `BIGINT UNSIGNED AUTO_INCREMENT`，接口层以字符串输出。

### 3.1 `user_account`

| 字段 | 类型 | 可空 | 默认/派生 | 本轮说明 |
|---|---|---|---|---|
| `id` | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 主键 |
| `wechat_app_id` | VARCHAR(64) | 否 | — | 后端配置注入，**绝不接受前端传入** |
| `wechat_open_id` | VARCHAR(128) | 否 | — | `code2Session` 返回值，后端唯一来源 |
| `wechat_union_id` | VARCHAR(128) | 是 | — | 本轮可为空（未绑开放平台时微信不返回） |
| `nickname` | VARCHAR(80) | 是 | — | 本轮仅允许用户在档案页自填；不调用微信头像昵称接口 |
| `role` | ENUM('USER','ADMIN') | 否 | 'USER' | 本轮只用 USER；ADMIN 为后续动作字典维护预留 |
| `status` | ENUM('ACTIVE','DISABLED','DELETION_PENDING') | 否 | 'ACTIVE' | 登录时非 ACTIVE 直接拒绝 |
| `timezone` | VARCHAR(64) | 否 | 'Asia/Shanghai' | 业务日期计算依据；服务端用 `ZoneId.getAvailableZoneIds()` 校验 |
| `data_revision` | BIGINT UNSIGNED | 否 | 0 | 档案/目标/体重/计划变更后在同一事务内 +1 |
| `last_login_at` | DATETIME(3) | 是 | — | UTC，每次登录成功刷新 |
| `created_at` / `updated_at` | DATETIME(3) | 否 | CURRENT_TIMESTAMP(3) (+ON UPDATE) | UTC |

索引：`PRIMARY KEY (id)`；`UNIQUE KEY uq_user_account_1 (wechat_app_id, wechat_open_id)`。
唯一索引是**并发首登只注册一个账号**的最终防线，不靠"先查后插"保证。

### 3.2 `user_profile`（1:1 用户）

关键字段：`birth_date` DATE NULL、`sex_for_equation` ENUM('MALE','FEMALE','UNSPECIFIED') 默认 UNSPECIFIED、`height_cm` DECIMAL(6,2) NULL、`training_level` ENUM('BEGINNER','INTERMEDIATE','ADVANCED') 默认 BEGINNER、`activity_category` ENUM NULL、`activity_confirmed_at` DATETIME(3) NULL、`diet_preferences`/`equipment`/`training_constraints` JSON NULL、`professional_guidance_required` BOOLEAN 默认 FALSE、`lock_version` INT UNSIGNED 默认 0。

索引：`PK(id)`；`UNIQUE (id,user_id)`；`KEY (user_id)`；`UNIQUE (user_id)`（保证一人一档）。
CHECK：`height_cm IS NULL OR height_cm > 0`。FK：`(user_id) → user_account(id)` RESTRICT。

本轮页面使用：身高、训练经验、器械、（可选）出生日期与性别、可用时间/训练限制备注。
`user_profile` **没有体重的列**，与设计一致，这正是"不另建易不一致的当前体重字段"的落点：体重唯一真相是 `body_measurement`。

### 3.3 `user_goal`

字段：`goal_type` ENUM('FAT_LOSS','MUSCLE_GAIN','MAINTAIN','GENERAL_FITNESS')、`target_weight_kg` DECIMAL(6,2) NULL、`daily_intake_target_kcal` DECIMAL(9,2) NULL、`weekly_sessions_target` TINYINT UNSIGNED NULL、`starts_on` DATE NOT NULL、`ends_on` DATE NULL、`status` ENUM('DRAFT','ACTIVE','ARCHIVED') 默认 DRAFT、`active_user_id` 生成列 STORED、`lock_version`。

索引：`PK(id)`；`UNIQUE (id,user_id)`；`KEY(user_id)`；`UNIQUE (active_user_id)`。
CHECK：目标体重/热量为正或 NULL；`ends_on IS NULL OR ends_on >= starts_on`。

`daily_intake_target_kcal` **列保留但页面与本轮接口不暴露**（提示词要求"饮食热量目标无需出现在页面"，同时不破坏后续兼容）。接口对它的策略：请求体不接受该字段（未知/禁用字段报错），响应里返回 `null`。

### 3.4 `body_measurement`

字段：`measured_at` DATETIME(3) NOT NULL（UTC 实际测量时间）、`business_date` DATE NOT NULL、`weight_kg` DECIMAL(6,2) NOT NULL、`waist_cm` DECIMAL(6,2) NULL、`body_fat_percent` DECIMAL(5,2) NULL、`source` ENUM('MANUAL','IMPORT') 默认 MANUAL、`client_record_uuid` CHAR(36) NOT NULL、`notes` VARCHAR(500) NULL、`lock_version`。

索引：`PK(id)`；`UNIQUE (id,user_id)`；`KEY(user_id)`；`UNIQUE (user_id, client_record_uuid)`；`KEY (user_id, business_date, measured_at)`。
CHECK：`weight_kg > 0`；`waist_cm > 0 或 NULL`；`body_fat_percent` 在 [0,100] 或 NULL。

`(user_id, client_record_uuid)` 唯一索引 = 重复提交幂等的数据库防线。`source` 本轮恒为 `MANUAL`（JSON 导入的是计划，不是体重历史）。

**最新体重的查询口径（唯一实现方式）**：

```sql
SELECT * FROM body_measurement
WHERE user_id = ?
ORDER BY measured_at DESC, id DESC
LIMIT 1;
```

按 `measured_at` 而非 `business_date` 排序，所以补录一条 `business_date` 更早、但 `measured_at` 是当下的记录，**不会**成为"最新体重"（它只影响历史曲线的横轴位置）。删除当前最新一条，`ORDER BY measured_at DESC LIMIT 1` 天然回退到上一条；无记录返回空数据。不使用 `MAX(business_date)`、不使用视图缓存、不在 `user_profile` 冗余字段。

### 3.5 `exercise_reference`（系统字典，本轮只读）

字段：`exercise_code` VARCHAR(64) UNIQUE、`name` VARCHAR(120)、`category` ENUM('STRENGTH','CARDIO','MOBILITY')、`muscle_groups` JSON NULL、`equipment` JSON NULL、`instructions` TEXT NULL、`source_url` VARCHAR(1000) NULL、`status` ENUM('ACTIVE','DISABLED') 默认 ACTIVE。

本轮**只读**：页面从 `status='ACTIVE'` 的动作里选，用户不能新增/修改字典。种子数据见 §7。

### 3.6 `training_plan`

字段：`plan_group_uuid` CHAR(36) NOT NULL（同组版本共享）、`revision_no` INT UNSIGNED NOT NULL、`base_plan_id` BIGINT UNSIGNED NULL、`title` VARCHAR(160) NOT NULL、`source` ENUM('MANUAL','IMPORT','AI')、`status` ENUM('DRAFT','ACTIVE','ARCHIVED','DISCARDED') 默认 DRAFT、`goal_snapshot` JSON NOT NULL、`starts_on` DATE NOT NULL、`ends_on` DATE NULL、`confirmed_at` DATETIME(3) NULL、`active_user_id` 生成列 STORED、`lock_version`。

索引：`PK(id)`；`UNIQUE (id,user_id)`；`KEY(user_id)`；`UNIQUE (user_id, plan_group_uuid, revision_no)`；`UNIQUE (active_user_id)`。
CHECK：`revision_no > 0`；`ends_on IS NULL OR ends_on >= starts_on`。
FK：`(base_plan_id,user_id) → training_plan(id,user_id)`；`(user_id) → user_account(id)`。

`UNIQUE (active_user_id)` 的语义要澄清：列值就是 `user_id`，不同用户写入不同值互不冲突，所以它精确表达"**每用户最多一个 ACTIVE**"，而不是全局一个。NULL 不参与唯一性判定，因此 DRAFT/ARCHIVED/DISCARDED 不受限。

`active_user_id` 是 STORED 生成列：**INSERT/UPDATE 不得携带该列**，否则 MySQL 报错。ORM 侧必须屏蔽（§6）。

本轮 `source` 只产生 `MANUAL` 和 `IMPORT`；`'AI'` 枚举值保留（避免后续改枚举），但 P1 不存在任何写入 `'AI'` 的代码路径。

### 3.7 `training_plan_day`

字段：`plan_id`（明确指向某个计划**版本**）、`iso_weekday` TINYINT UNSIGNED（1 周一…7 周日）、`title` VARCHAR(120) NULL、`is_rest_day` BOOLEAN 默认 FALSE、`notes` VARCHAR(800) NULL。

索引：`PK(id)`；`UNIQUE (id,user_id)`；`KEY(user_id)`；`UNIQUE (plan_id, iso_weekday)`。
CHECK：`iso_weekday BETWEEN 1 AND 7`。FK：`(plan_id,user_id) → training_plan(id,user_id)`；`(user_id) → user_account(id)`。

`UNIQUE (plan_id, iso_weekday)` = "每个星期最多一个计划日"的数据库级保证；重复星期由它拒绝并映射为定位到 `days[i].iso_weekday` 的错误。

### 3.8 `training_plan_item`

字段：`plan_day_id`、`exercise_id`、`position_no` SMALLINT UNSIGNED、`target_sets`、`reps_min`、`reps_max`、`target_external_load_kg` DECIMAL(8,2)、`target_duration_seconds` INT UNSIGNED、`rest_seconds` SMALLINT UNSIGNED、`notes` VARCHAR(800)。

索引：`PK(id)`；`UNIQUE (id,user_id)`；`KEY(user_id)`；`UNIQUE (plan_day_id, position_no)`。
CHECK：`target_sets > 0 或 NULL`；`reps_min > 0 或 NULL`；`reps_max IS NULL OR reps_max >= reps_min`；`target_external_load_kg >= 0 或 NULL`；`target_duration_seconds > 0 或 NULL`。
FK：`(plan_day_id,user_id) → training_plan_day(id,user_id)`；`(exercise_id) → exercise_reference(id)`；`(user_id) → user_account(id)`。

`exercise_id` 外键保证导入/编辑不可能塞入字典外的动作；但外键只保证"存在"，不保证"有效"，`status='ACTIVE'` 由 Service 校验（详见 p1-api.md 错误码 `EXERCISE_UNAVAILABLE`）。

### 3.9 单位与精度约定（沿用设计包 §1）

身高 cm、体重 kg、外部负重 kg、食物 g、时长 s、视频 ms、热量 kcal。金额/测量一律 DECIMAL + Java `BigDecimal`，禁止 `double`/`float`。`NULL` = 未知或用户未填，**不写成 0**，不做默认填补。所有 DATETIME 存 UTC；业务日期 `DATE` 单独存列，按 `user_account.timezone` 计算。

---

## 4. 与设计包完整 schema.sql 的差异清单

本轮迁移与设计包脚本的差异，全部是**可追溯的、有意的**差异。

### 4.1 表数量：36 → 8

只建 §1 的 8 张。差异是范围缩小，不是设计变更。

### 4.2 `training_plan` 暂不创建 2 个列与 2 个外键（重点）

设计包 `schema.sql:631-632,650-651`：

```sql
`source_job_id` BIGINT UNSIGNED NULL COMMENT '导入任务',
`source_run_id` BIGINT UNSIGNED NULL COMMENT 'AI 生成任务',
CONSTRAINT `fk_training_plan_1` FOREIGN KEY (`source_job_id`, `user_id`) REFERENCES `processing_job` (`id`, `user_id`),
CONSTRAINT `fk_training_plan_2` FOREIGN KEY (`source_run_id`, `user_id`) REFERENCES `agent_run` (`id`, `user_id`),
```

本轮**不创建这 2 列、不创建这 2 个外键**。原因：`processing_job` 属 P3 文件任务表，`agent_run` 属 P2 Agent 表，本轮不建它们；若保留外键就必须连带建 2 张表，与"只建基础 8 张"冲突。提示词第 4.5 条明确建议此收缩，并要求"后续用新增迁移补上"。

**必须保留、不得因缩小范围而删掉的东西**（逐条对照）：

| 不变式 | 保留位置 | 校验层 |
|---|---|---|
| 每用户最多一个 ACTIVE | `training_plan.active_user_id` 生成列 + `UNIQUE`；`user_goal` 同理 | 数据库 + 事务 |
| 用户归属 | 每表 `user_id` 列 + `(id,user_id)` 复合唯一键 + 子表复合外键 | 数据库 + Service |
| 版本不可覆盖 | `plan_group_uuid` + `revision_no` + `base_plan_id` + `UNIQUE(user_id,plan_group_uuid,revision_no)` | 数据库 + Service |
| 乐观锁 | `lock_version`（可变的头表行） | ORM + Service |
| 数据修订号 | `user_account.data_revision` 事务内递增 | Service |
| 表名与核心字段 | 完全沿用设计包 | — |

后续加回的方式（未来迁移，本轮不写文件，只记录）：

```sql
-- 未来 V-next（必须在 processing_job / agent_run 已存在之后执行；同一脚本内顺序敏感）
ALTER TABLE `training_plan`
  ADD COLUMN `source_job_id` BIGINT UNSIGNED NULL COMMENT '导入任务' AFTER `source`,
  ADD COLUMN `source_run_id` BIGINT UNSIGNED NULL COMMENT 'AI 生成任务' AFTER `source_job_id`;

ALTER TABLE `training_plan`
  ADD CONSTRAINT `fk_training_plan_src_job`
    FOREIGN KEY (`source_job_id`, `user_id`) REFERENCES `processing_job` (`id`, `user_id`),
  ADD CONSTRAINT `fk_training_plan_src_run`
    FOREIGN KEY (`source_run_id`, `user_id`) REFERENCES `agent_run` (`id`, `user_id`);

ALTER TABLE `training_plan`
  ADD CONSTRAINT `ck_training_plan_src_consistent` CHECK (
    (source = 'IMPORT' AND source_job_id IS NOT NULL AND source_run_id IS NULL)
    OR (source = 'AI' AND source_run_id IS NOT NULL AND source_job_id IS NULL)
    OR (source = 'MANUAL' AND source_job_id IS NULL AND source_run_id IS NULL)
  );
```

MySQL 加外键时对存量行做校验：本轮所有行 `source IN ('MANUAL','IMPORT')` 且两列为 NULL，加 FK 不会失败。上面的 `ck_training_plan_src_consistent` 是我建议的**额外收紧**，加它之前必须先确认历史数据满足，否则该 CHECK 单独放到再下一个迁移里。

`training_plan_item.exercise_id → exercise_reference(id)` 本轮保留（字典同轮建立）。

### 4.3 `daily_checkin` / `calculation_rule` / `met_activity` 等不建

设计包把它们标为 P1，但本轮 6 条功能不消费它们：无饮食、无训练打卡、无公式计算（提示词"不添加未经说明的医学阈值"且本轮无模型调用/计算报告）。所以不建，也不建"空壳"。

### 4.4 `goal_snapshot JSON NOT NULL` 的 P1 取值

该列 NOT NULL 且本轮无 AI 生成，因此手工/导入计划也必须写入。规范形状（服务端生成，客户端不可注入）：

```json
{
  "schema_version": "fitagent.goal_snapshot/1",
  "captured_at": "2026-10-13T07:20:00Z",
  "goal_id": "12",
  "goal_type": "FAT_LOSS",
  "target_weight_kg": 70.00,
  "weekly_sessions_target": 4,
  "height_cm": 175.00,
  "training_level": "BEGINNER",
  "equipment": ["DUMBBELL", "BODYWEIGHT"],
  "absent_fields": ["goal_id", "goal_type", "target_weight_kg", "weekly_sessions_target"]
}
```

- 用户尚无 ACTIVE 目标时，相关键进 `absent_fields` 并置 `null`，**不补造**（设计约定"NULL 表示未知，不当 0"）。
- 写入前校验：必须是合法 JSON、体积 ≤ 8 KiB、`schema_version` 在白名单内。这条来自设计包 §1"JSON 必须校验版本和体积"。

### 4.5 唯一有意"修改"设计包定义的地方：外部标识列的排序规则

见 §5。这一处会在阶段 B 落到 DDL，需要你确认。

---

## 5. 大小写与规范化口径（提示词点名要求）

表级默认排序规则按设计包用 `utf8mb4_0900_ai_ci`（**ai** = accent-insensitive, **ci** = case-insensitive）。对身份标识和 UUID 而言，CI 是**语义错误**方向，必须逐列定口径。规则如下：

| 列 | 目标口径 | DDL 做法 | 服务端规范化 | 理由 |
|---|---|---|---|---|
| `user_account.wechat_app_id` | 大小写敏感 | `COLLATE utf8mb4_bin` | 仅 trim，不折叠大小写 | AppID 是微信发放的不透明串，含字母大小写 |
| `user_account.wechat_open_id` | 大小写敏感 | `COLLATE utf8mb4_bin` | 仅 trim | openid 由微信生成、大小写有意义；CI 会把两个不同用户折叠成同一个账号，属于**身份混淆级**风险 |
| `user_account.wechat_union_id` | 大小写敏感 | `COLLATE utf8mb4_bin` | 仅 trim；空串按 NULL | 同上 |
| `body_measurement.client_record_uuid` | 大小写不敏感（等价） | 保持表默认 `0900_ai_ci` | **强制规范化**：`UUID.fromString(s).toString()` → 小写；格式非法 → 字段级错误 `INVALID_UUID_FORMAT` | 去重要求"重复提交不重复新增"。规范化保证索引值单一形态；CI 排序规则作为兜底，即使某条路径漏规范化也不会重复插入 |
| `training_plan.plan_group_uuid` | 大小写不敏感（等价） | 保持表默认 `0900_ai_ci` | 同上，服务端生成，小写 | 同上；该值**永不接受客户端传入**（见 p1-api.md） |
| `exercise_reference.exercise_code` | 大小写不敏感匹配 | 保持表默认 `0900_ai_ci` | 导入匹配前 `toUpperCase(Locale.ROOT)` | 字典码统一大写（§7），CI 让用户写 `barbell_squat` 也能命中；但**不做模糊/近似匹配**，命中不了就报错，不静默跳过 |

补充规则：

1. **枚举值**（`status`、`source`、`category`、`goal_type`、`iso_weekday` 字符串别名等）：MySQL ENUM 本身大小写不敏感，但**契约层必须严格**：JSON 入参只接受设计定义的大写字面量，`"draft"` 一律判 `INVALID_ENUM_VALUE`。否则前端与后端对"大小写是否等价"的口径会分叉。
2. **日期**：只接受 `YYYY-MM-DD`（ISO-8601 local date），拒绝时间戳、拒绝斜杠分隔、拒绝带时区的值。
3. `wechat_*` 三列改用 `utf8mb4_bin` 后，`uq_user_account_1 (wechat_app_id, wechat_open_id)` 的两个成员各自按自身列排序规则比较；索引前缀长度 (64+128)*4 = 768 字节 < InnoDB DYNAMIC 上限 3072，安全。
4. 列排序规则与连接排序规则不一致时，"列 vs 绑定参数"的比较按列的隐式优先级取胜，索引仍可用；这是本处唯一需要留意的 MySQL 特性，阶段 B 用真实 8.4 计划查询做 `EXPLAIN` 确认。

这是本轮对设计包**唯一的定义级修改**，需要你确认（见文末确认清单第 6 项）。

---

## 6. 必须由代码维护的跨表约束（CHECK / 外键做不到）

| 场景 | 事务与校验要求 |
|---|---|
| 首次登录并发注册 | `INSERT` 撞 `uq_user_account_1` → 捕获 `DuplicateKeyException`，在**新事务**里重新查询并复用。外层登录方法不开事务，注册写在 `REQUIRES_NEW` 的短事务里，避免失败事务被标记 rollback-only 后无法复用连接 |
| 计划/目标激活 | 事务内先 `SELECT ... FOR UPDATE` 锁 `user_account` 行（**固定加锁顺序：先 user_account，再目标行**，防止死锁）；校验草稿属于本人、`lock_version` 匹配；把旧 ACTIVE 改 ARCHIVED，再把新版本置 ACTIVE + `confirmed_at`。唯一索引作为最终防线，冲突时整个事务回滚 → 并发两次激活只留一个 ACTIVE |
| 休息日不含动作 | `is_rest_day = TRUE` 且 `training_plan_item` 存在 → Service 拒绝（CHECK 跨不了表）。非休息日至少 1 个动作，否则 `EMPTY_TRAINING_DAY` |
| 聚合完整性 | 计划头 + 计划日 + 动作条目在一次事务内写；任一失败全量回滚，不留半份计划 |
| DRAFT 就地编辑 | 允许；替换子行采用"删旧插新 + 重排 `position_no`"，在同一事务内完成。P1 无 `workout_session` 引用计划日，所以删子行不产生悬空引用；**P2 接入实际打卡后这条必须改**（届时计划日不得物理删除），已在文档标注 |
| ACTIVE/ARCHIVED 不可覆盖 | 任何内容修改 → 新建同 `plan_group_uuid` 的 DRAFT 版本，`revision_no = 该组 max+1`，`base_plan_id` 指向被修改的那个版本 |
| `exercise_id` 有效性 | 外键只保证存在；`status='ACTIVE'` 由 Service 校验 |
| `data_revision` | 档案/目标/体重/计划任一变更后，同事务 `UPDATE user_account SET data_revision = data_revision + 1 WHERE id = ?` |
| 归属校验 | 每个读写接口以认证上下文 `userId` 为条件；子资源同时校验父资源 `(id,user_id)` 链路；越权返回统一的 `RESOURCE_NOT_FOUND`（**不返回 403**，避免泄露资源是否存在） |

### ORM 侧硬约束

- MyBatis-Plus 实体**不映射** `active_user_id`；或映射为 `@TableField(insertStrategy = NEVER, updateStrategy = NEVER, select = false)`。首选**根本不声明该字段**，从源头杜绝写入 STORED 生成列。
- `created_at` / `updated_at` 由数据库默认值与 `ON UPDATE` 维护，ORM 不写这两列（`insertStrategy/updateStrategy = NEVER`）。
- `lock_version` 用 MyBatis-Plus `@Version` + `OptimisticLockerInnerInterceptor`；更新影响 0 行 → 转成 `VERSION_CONFLICT` 错误，不重试覆盖。
- 数据库 Entity 不作为接口响应；一律 VO。

---

## 7. 动作字典种子数据

设计包 `schema.sql` **没有** `exercise_reference` 的 INSERT（我核对了脚本第 932–957 行，种子只有 `model_provider` / `model_catalog` / `model_price` / `calculation_rule`）。而提示词要求迁移包含"动作字典初始化"，且导入模板要"真实可导入"，所以本轮必须自备种子。

原则：

1. `exercise_code` 统一大写下划线，作为导入匹配键；`name` 用中文，`category` 取 STRENGTH/CARDIO/MOBILITY。
2. `muscle_groups` / `equipment` 是 JSON 数组，供页面分组筛选；器械取值与设计包 `user_profile.equipment` 用同一套代码（如 `BARBELL`/`DUMBBELL`/`KETTLEBELL`/`CABLE`/`MACHINE`/`BODYWEIGHT`/`BAND`/`ROWER`/`AIR_BIKE`）。
3. `instructions` 只写基础操作要点，`source_url` **一律留 NULL**：我不会有来源地伪造"科学依据"链接。是否补录权威来源由你后续决定（设计包 §10.1 精神：来源必须可追溯，宁缺毋伪）。
4. 规模：约 24 条（12 力量 / 6 心肺 / 4 柔韧 + 2 补充），够建计划和模板，不堆量。全部 `status='ACTIVE'`。
5. 迁移文件形式：`V9__seed_exercise_reference.sql`，**版本化而非 REPEATABLE**，不显式指定 `id`（让自增分配），避免占位 ID 与后续场景冲突；重复执行由 Flyway 历史表保证一次性。

**已定稿（2026-10-13 你的答复）**：本轮内置 **25 条** ACTIVE 动作，完整清单（代码/名称/类型/肌群/器械）见 `docs/p1-plan-import.md` §9，模板文件所用的 12 个代码全部在该清单内。`source_url` 与 `instructions` **一律留 NULL** —— 不引入未经来源核对的动作说明，也不编造权威链接。是否补录来源与说明由你后续决定。

---

## 8. Flyway 迁移规划

命名与顺序（`backend/app/src/main/resources/db/migration`）：

| 文件 | 内容 | 依赖 |
|---|---|---|
| `V1__create_user_account.sql` | 建 `user_account`（含 §5 的 3 个 `utf8mb4_bin` 列） | — |
| `V2__create_user_profile.sql` | `user_profile` + FK | V1 |
| `V3__create_user_goal.sql` | `user_goal` + 生成列 + UNIQUE + FK | V1 |
| `V4__create_body_measurement.sql` | `body_measurement` + 2 个 UNIQUE + 组合索引 + FK | V1 |
| `V5__create_exercise_reference.sql` | 字典表 | — |
| `V6__create_training_plan.sql` | `training_plan`（**无** source_job_id/source_run_id）+ 生成列 + 3 个 UNIQUE + FK | V1 |
| `V7__create_training_plan_day.sql` | `training_plan_day` + `UNIQUE(plan_id,iso_weekday)` + 复合 FK | V6 |
| `V8__create_training_plan_item.sql` | `training_plan_item` + `UNIQUE(plan_day_id,position_no)` + 5 个 CHECK + 复合 FK | V7, V5 |
| `V9__seed_exercise_reference.sql` | 约 24 条 ACTIVE 动作 | V5 |

每张表的 DDL 都以 `CONSTRAINT ck_xxx / fk_xxx / uq_xxx / idx_xxx` 显式命名，与设计包同风格，便于未来 `ALTER TABLE ... DROP CONSTRAINT` 与排错。

### 8.1 配置要点

- `spring.flyway.enabled=true`，`baseline-on-migrate=false`（新库不需要 baseline；已有库见 §8.2），`clean-disabled=true`（**永久**，防止误 `flyway clean` 删库），`validate-on-migrate=true`，`out-of-order=false`，`encoding=UTF-8`（Windows GBK 环境必须显式）。
- `locations=classpath:db/migration`。
- **Flyway 不建库**。`CREATE DATABASE` 由部署脚本或人工执行（见 §9），迁移账号只授予目标 schema 的 DDL+DML，不给 `SUPER`/`CREATE ROUTINE`。
- 与设计包脚本共存策略：`schema.sql` 是**全量 36 表的初始化脚本**，属于设计交付物；本轮 Flyway 走 8 表路线。两者**不混用**，一个环境只选一条路线。若你以后要一次性上全量，正确做法是把 `schema.sql` 转成 `V1__baseline_full_schema.sql` 并对未建的表做拆分，而不是手工导一遍再让 Flyway 追。

### 8.2 已有数据库的兼容处理（提示词：不能重建或删除）

阶段 B 启动前先探测目标 schema：

- 空库 → 按 V1..V9 正常迁移。
- 已存在上述 8 张表且结构匹配 → `flyway baseline`（版本设为 0 之后首跑 V1 会撞已存在），更实际的做法：先比对结构，若确认与设计包一致则把 Flyway 历史标记为已应用相应版本，或改为"接管模式"：新增 `V1__existing_db_takeover.sql`（内容为空 + 注释），后续变更从 V2 起。这条路线需要你提供库的实际情况我才好定，因此列入待确认。
- 已存在但结构不符 → **停止**，输出差异报告，不自动 `ALTER`、不 `DROP`。

我不会对现有 MySQL 5.7 实例做任何写操作。

---

## 9. 新 MySQL 8.4 上单独执行本阶段迁移（可复现步骤）

```bash
# 1) 建库与专用账号（不要复用 root，不要提交口令）
mysql -h 127.0.0.1 -P 3306 -u root -p <<'SQL'
CREATE DATABASE IF NOT EXISTS `fitagent`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'fitagent'@'%' IDENTIFIED BY '<从环境注入>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES,
      SHOW VIEW, TRIGGER ON `fitagent`.* TO 'fitagent'@'%';
FLUSH PRIVILEGES;
SQL

# 2) 跑迁移（阶段 B 交付后）
cd backend && mvn -pl app flyway:migrate

# 3) 结构核验（逐条确认本轮设计成立）
mysql -h 127.0.0.1 -u fitagent -p fitagent -e "
SHOW CREATE TABLE training_plan\G
SHOW CREATE TABLE training_plan_item\G
SELECT TABLE_NAME, CONSTRAINT_NAME, CHECK_CLAUSE
  FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA='fitagent';"

# 4) 关键不变式手工验证
#    a. 生成列不可写
mysql ... -e "UPDATE training_plan SET active_user_id=NULL WHERE id=1;"   # 期望报错
#    b. 每用户仅一个 ACTIVE：手工置第二个 ACTIVE 应被 UNIQUE 拒绝
#    c. CHECK 生效：INSERT weight_kg=0 应报错（5.7 上会静默通过 —— 这正是必须用 8.4 的原因）
```

阶段 B 交付物会包含上面每一步的**实际执行结果**，跑不了的会标成未验证并说明原因。

---

## 10. Redis 使用范围（本轮）

| 用途 | Key 形态 | TTL | 说明 |
|---|---|---|---|
| 登录会话 | `fa:sess:{sha256(token)}` → `userId\|expiresAt` | 7 天滑动 | 只存令牌**摘要**，明文令牌不落库不落日志；退出即 `DEL` → 服务端立即失效 |
| 导入幂等 | `fa:imp:{userId}:{Idempotency-Key}` → `planId` | 24 小时 | 见下方限制 |
| 登录/导入限流 | `fa:rl:{ip}:{route}` | 窗口 | 具体阈值见 p1-api.md |

Redis 只承载会话与幂等，**不做业务数据缓存**，不作为费用或权威状态来源（设计包 §4 精神）。

**导入幂等的保证边界（已定稿）**：采用 **Redis 24 小时窗口**方案（你的答复）。窗口外同键重复提交会生成新计划，这是已知取舍。持久化幂等账本（`plan_import_request` 表 + 复合唯一索引）留到 P2，与 Agent 任务幂等共用同一套机制。**本轮严格 8 张表。**
