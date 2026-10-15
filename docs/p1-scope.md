# FitAgent P1 基础功能：范围、流程、页面与验收

版本：v1.0 规划稿；日期：2026-10-13。依据：`Codex_P1基础功能_先规划后开发提示词.md` + `FitAgent_项目设计/`。

---

## 1. 本轮做什么

| # | 功能 | 完成定义 |
|---|---|---|
| 1 | 微信登录 | `wx.login` code → 后端 `jscode2session` → 首次自动注册、重复登录复用同一账号；服务端随机令牌 + Redis 会话；退出使令牌在后端失效；401 统一处理 |
| 2 | 个人档案 | 建立与修改本人 `user_profile`：身高必填，训练经验/器械/出生日期/可用时间与限制可选；`lock_version` 乐观锁 |
| 3 | 基础目标 | 本人 `user_goal` 手工录入与修改：目标类型、目标体重、每周训练次数、起止日期；每用户最多一个 ACTIVE |
| 4 | 体重记录 | 新增/编辑/删除本人 `body_measurement`；历史列表 + 最近一次测量；重复提交幂等；补录不改最新；删除可回退 |
| 5 | 训练计划 | 手工建立每周计划（按星期编排动作/组数/次数/可选外部负重/时长/休息），支持休息日；列表、详情、草稿编辑、复制、确认生效、归档、丢弃草稿、当前计划、今天的计划 |
| 6 | 计划导入 | JSON 模板导入 + 粘贴 JSON：校验 → 结构化预览 → 确认 → 存为 DRAFT；文件原件留本机，后端只存结构化数据 |

### 1.1 三个必须显式确认的边界（已按你的答复定稿）

**(a) 微信登录 = 首次登录自动注册账号。** 不存在独立的"注册"页面或表单。账号在第一次成功 `code2Session` 时创建，靠 `uq_user_account_1 (wechat_app_id, wechat_open_id)` 唯一索引 + 事务处理并发。首次登录**不强制**填档案。

**(b) 本轮"上传训练计划"= JSON 导入，不是文件服务器。** 且有一个平台级限制需要你知情：微信小程序**没有** `wx.chooseFile`，`uni.chooseFile` 在小程序的官方兼容表标注为不可用；唯一可用的选取接口是 `wx.chooseMessageFile`，语义是"从客户端会话选择文件"。所以按你的选择实现**双入口**：

- 入口 1：`wx.chooseMessageFile({type:'file', extension:['json', ...]})` → `wx.getFileSystemManager().readFile({filePath, encoding:'utf8'})` → `JSON.parse` → POST 文本。UI 明确引导"先把模板文件发送到微信聊天或文件传输助手，再从这里选取"，**不谎称**可以读任意本机路径。
- 入口 2：粘贴 JSON 文本框。

两个入口进入**同一套后端校验与预览流程**，能力等价。后端不接收 multipart 文件、不建文件服务器、不长期保存原件、不做图片/PDF 智能解析。

**(c) 导入幂等采用 Redis 24 小时窗口。** `Idempotency-Key` 请求头 + Redis 保存 `planId`，TTL 24h，叠加"预览/确认两段式 + 前端按钮防重"。窗口外同键重复提交会生成新计划——这是已知取舍，持久化幂等账本留到 P2 与 Agent 任务幂等共用一套机制。本轮严格 8 张表。

---

## 2. 本轮明确不做（也不做假功能）

不开发、不预留空类、不摆占位按钮：模型调用与供应商适配、自研 Agent 执行核心（`agent-core`）、API Key 管理与加密、余额/价格/预算/用量账本、饮食识别与饮食记录、训练实际打卡（`workout_session/entry/set`）、教学视频解析、图片/PDF 智能解析、Qdrant 向量检索、Python 服务、OSS、分块上传与临时媒体清理、日报周报、每日记录完整性 `daily_checkin`、WebSocket。

页面上**不出现**"敬请期待"之类的灰按钮或假入口。后续接入点只写在 `docs/p1-architecture.md` 的扩展章节。

同理，本轮无计算公式调用：不显示 BMI/REE/TDEE/消耗热量，不引入 `calculation_rule` 数据，也不添加未经说明的医学阈值。档案校验只做"单位、正数、长度、枚举合法性、日期格式"。

---

## 3. 功能流程

### 3.1 登录与会话

```
小程序 onLaunch → 本地读 token（uni.getStorageSync）
  ├─ 有 token → GET /api/me 探活
  │    ├─ 200 → 进首页
  │    └─ 401 → 清本地 token → 登录页
  └─ 无 token → 登录页
登录页 [微信登录] → uni.login 取 code
  → POST /api/auth/wechat-login { code }
  → 后端 GET https://api.weixin.qq.com/sns/jscode2session?appid&secret&js_code&grant_type=authorization_code
     （AppSecret 只在后端；小程序端不能也不应直连 api.weixin.qq.com）
  → 成功：取 openid/unionid → upsert user_account（唯一索引兜底并发）
     → SecureRandom 生成 256bit 不透明令牌 → Redis SET fa:sess:{sha256(token)} = userId|expiresAt, TTL 7d
     → 返回 { token, userId, isNewAccount }
  → 失败：映射成结构化错误码（见 p1-api.md），不返回微信原始响应
退出登录 → POST /api/auth/logout → Redis DEL → 无论后端成败都清本地 token → reLaunch 登录页
```

令牌校验用**自定义 OncePerRequestFilter**（不是 Spring Session）：取 `Authorization: Bearer <token>` → SHA-256 → Redis 查 → 命中则装配 `Authentication` 进 `SecurityContext`，并把 `userId` 放进不可变认证主体。业务代码只从认证上下文取 `userId`，**永不**从请求体/查询参数取身份。

开发登录（dev-login）：`@Profile("dev")` + 显式开关 `fitagent.auth.dev-login.enabled=true`，只接受 `devUserId ∈ {dev-a, dev-b}` 两个固定测试用户，映射到独立的合成 `wechat_app_id = 'wx.dev.local'` 与固定 openid。响应体带 `"devMode": true`，小程序登录页在开发构建里显著标注"开发登录模式"。**生产环境**：`application-prod.yml` 不定义该属性，`SecurityConfig` 在非 dev profile 下根本不注册该路由，`prod` 启动时若探测到该端点存在即启动失败。dev-login 测试结果与真实微信联调结果在交付说明里**分开记录**。

### 3.2 档案与目标

```
我的 → 档案卡片（无档案显示空状态"建立档案"）
  → 档案页：身高 cm（必填，>0，≤2 位小数）、训练经验、器械多选、
            出生日期（可选）、性别参数（可选，默认 UNSPECIFIED）、可用时间/限制备注（可选）
  → 无档案 POST 创建；有档案 PUT 携带 lockVersion 更新；冲突返回 409 并提示刷新
目标区：目标类型 / 目标体重 kg / 每周训练次数 / 起止日期
  → POST 新建（可同时激活：激活则事务内归档旧 ACTIVE）
  → PUT 改 DRAFT/ACTIVE（lock_version 保护）；DELETE 只允许删 DRAFT
```

`diet_preferences` 本轮页面不填（无饮食功能），后端也不开放该字段写入，避免录入无人消费的数据。

### 3.3 体重

```
我的 → 体重记录（列表 + 顶部"最近一次"）
  → 新增：体重 kg 必填；腰围/体脂/备注可选；测量时间默认"现在"，可选更早（补录）
     client_record_uuid 由前端生成 UUID v4，同一逻辑记录重试复用同值
  → 编辑：改数值不改 client_record_uuid；测量时间可修正
  → 删除：确认后删除，列表与"最近一次"自动回退
最新体重 = ORDER BY measured_at DESC LIMIT 1（补录旧业务日期的记录不会成为最新）
```

业务日期由服务端按 `user_account.timezone` 从 `measured_at` 推导，前端不传 `business_date`（避免客户端伪造日期归属）。

### 3.4 训练计划

状态机（`training_plan.status`）：

```
                     ┌──── 编辑（就地）────┐
                     ▼                     │
DRAFT ──确认生效──► ACTIVE ──归档──► ARCHIVED
  │                                    │
  └──丢弃──► DISCARDED                 └─ 修改内容 ─► 新建同组 DRAFT(revision_no=max+1)
                                                        base_plan_id 指向被改版本
任意状态 ──复制──► 新 plan_group_uuid 的 DRAFT(revision_no=1)
```

- 只有 DRAFT 可就地编辑；改 ACTIVE/ARCHIVED 一律产生同组新草稿，**不覆盖**已确认版本。
- 确认生效：事务内锁 `user_account` 行 → 校验草稿归属与 `lockVersion` → 旧 ACTIVE 置 ARCHIVED → 新草稿置 ACTIVE + `confirmed_at` → `data_revision+1`。`UNIQUE(active_user_id)` 为最终防线，并发只胜出一人。
- 归档当前计划后，首页显示"未设置计划"。
- 首版只提供**丢弃草稿**与**归档**，不做历史版本删除。ARCHIVED/DISCARDED 记录永久保留。
- 计划日：`iso_weekday` 1–7，每星期最多一日（DB 唯一索引）；休息日不得带动作；非休息日至少 1 个动作、按 `position_no` 顺序、可调整顺序。
- 动作条目按类型分别校验：STRENGTH/MOBILITY 用组数/次数/外部负重/休息；CARDIO 用时长/休息。`reps_max >= reps_min`。负重允许 0（自重动作），NULL 表示未设定。
- 「今天的计划」= 当前 ACTIVE 计划里 `iso_weekday = 今天` 的计划日；若 ACTIVE 计划有 `ends_on` 且已过，则视为无计划并提示。

### 3.5 导入

```
选择 JSON（会话选文件 / 粘贴）→ 前端本地 JSON.parse 失败即给"不是合法 JSON"提示
 → POST /api/plans/import/preview（无副作用，不落库）
     后端：字节上限 → 结构校验 → 动作字典匹配 → 逐字段校验
     ├─ 不通过 → 422 返回定位到 path 的错误数组，页面按行/字段高亮
     └─ 通过 → 返回规范化预览（星期/动作名/组次/负重/时长）+ warningCount
 → 用户点"确认保存"
 → POST /api/plans/import { normalizedPlan, Idempotency-Key }
     服务端重新完整校验（不信任 preview 结果）→ 事务内聚合写 plan+day+item → DRAFT
     幂等命中 → 返回首次结果 + idempotentReplay=true
 → 跳转草稿详情页，用户可继续编辑或确认生效
```

未知动作代码、重复星期、非法字段**一律报错**，不静默跳过；`id`/`userId`/`owner`/`status`/`revisionNo`/`createdAt` 等服务端字段出现在输入里即判 `FORBIDDEN_SERVER_FIELD`。校验规则与模板见 `docs/p1-plan-import.md`。

---

## 4. 页面清单

TabBar 三个入口（首页 / 训练计划 / 我的），首版不加其余 Tab。

| 页面 | 路径 | 内容 | 主要接口 |
|---|---|---|---|
| 登录 | `pages/auth/login` | 微信登录按钮；开发模式标注 | `POST /api/auth/wechat-login`、`GET /api/me` |
| 首页 | `pages/index/home` | 档案摘要、最新体重、今天的计划（休息日/无计划/非休息日三形态） | `GET /api/me/profile`、`GET /api/body-records/latest`、`GET /api/plans/today` |
| 档案 | `pages/mine/profile` | 建立/修改档案 | `GET/POST/PUT /api/me/profile` |
| 目标 | `pages/mine/goal` | 当前目标、历史目标、新建与修改 | `GET/POST/PUT/DELETE /api/me/goals...` |
| 我的 | `pages/mine/index` | 账户信息、档案与目标入口、体重入口、退出登录 | `GET /api/me`、`POST /api/auth/logout` |
| 体重记录 | `pages/mine/body/records` | 最近一次 + 分页历史 + 新增/编辑/删除 | `GET/POST/PUT/DELETE /api/body-records...` |
| 计划列表 | `pages/plan/list` | 分组展示 ACTIVE/DRAFT/历史版本与归档 | `GET /api/plans` |
| 计划详情 | `pages/plan/detail` | 按星期展示计划日与动作；版本信息；操作按钮按状态出现 | `GET /api/plans/{id}` |
| 计划编辑 | `pages/plan/edit` | 草稿就地编辑；计划日与动作增删排序；ACTIVE/ARCHIVED 修改走"另存为新草稿" | `POST/PUT /api/plans...` |
| 计划导入 | `pages/plan/import` | 会话选文件 / 粘贴 JSON、预览、错误列表、确认保存 | `POST /api/plans/import/preview`、`POST /api/plans/import` |
| 动作字典 | `pages/plan/exercises` | 只读浏览有效动作（辅助选择） | `GET /api/exercises` |

**每个页面必须实现 5 种状态**：加载中、无数据、提交失败（含服务端错误码文案）、操作成功、登录失效（跳回登录页并保留一次提示）。请求统一走 `src/api/*` + `src/utils/request.ts`，页面里不散落 `uni.request`；401 由封装层集中处理（清 token → 跳登录）。

---

## 5. 开发顺序（阶段 B）

| 步骤 | 内容 | 出口条件 |
|---|---|---|
| **B-0** | **Spring Boot 4.1.1 + MyBatis-Plus 可行性门禁**：最小工程（parent + app + 一张表 + 一个含 JSON 列的 CRUD + Flyway 空跑 + Redis 冒烟）。用 `mybatis-plus-spring-boot4-starter:3.5.17`，JSON 字段显式 `Jackson3TypeHandler`，不开 `type-handlers-package` 扫描 | **通过** → 锁定版本继续；**失败** → 停下按降级阶梯（覆盖 mybatis-spring 4.1.0 → Boot 4.0.8 → Boot 3.5.16 → 换持久层）与你确认后再继续。不带着未验证组合往下堆功能 |
| B-1 | 工程骨架：`backend/pom.xml`(父) + `backend/app`、`common` 统一响应/分页/异常、`config`、UTF-8 强制、`application*.yml`、`.env.example`、`infra/docker-compose.yml`、根 README | `mvn -q verify` 空跑通过；中文注释无乱码 |
| B-2 | Flyway `V1..V9` + 实体/Mapper + 生成列与乐观锁的 ORM 屏蔽 | 迁移在真实 MySQL 8.4 跑通（无环境则标注未验证） |
| B-3 | auth：`jscode2Session` 客户端、令牌签发/校验过滤器、退出、并发注册、dev-login | 单测 + 会话冒烟；未登录全量拒绝 |
| B-4 | profile + goal | 归属校验、乐观锁、单 ACTIVE 通过 |
| B-5 | body measurement | 幂等、最新体重、补录、回退通过 |
| B-6 | exercise 字典读 + training 计划聚合 CRUD 与状态机 | 聚合回滚、版本不覆盖、并发生效通过 |
| B-7 | JSON 导入校验器 + preview + 幂等 confirm + 模板文件 | 非法输入定位报错、整体回滚、模板真实可导入 |
| B-8 | 小程序逐模块联调（登录→首页→档案→体重→计划→导入），`build:mp-weixin` 用微信开发者工具验证 | 微信目标编译通过 + 关键路径手工联调记录；H5 通过不算数 |
| B-9 | 交付文档与测试结果、未验证清单、P2 接入点 | 见 §6 |

前端脚手架在 B-1 并行创建：`npx degit dcloudio/uni-preset-vue#vite-ts miniapp`，版本按 `p1-architecture.md` 锁定表执行（注意 `@dcloudio/uni-app` 必须取 Vue 3 线快照版本而非 npm `latest`，Pinia 锁 `^2.3.1`）。

---

## 6. 验收条件

| 编号 | 验收项 | 验收方式 | 依赖环境 |
|---|---|---|---|
| A1 | 首次登录自动注册；重复登录复用同一账号、不产生第二行 | 连续两次 dev-login + 两次真实 code 登录，查 `user_account` 行数与 `id` 不变 | MySQL 8.4、微信开发者工具 |
| A2 | 并发首登只创建一个账号 | 同一 openid 并发 20 路 `wechat-login`，断言 1 行、20 个响应同一 `userId` | MySQL 8.4 |
| A3 | 未登录访问业务接口被拒 | 无/错/过期 token 三种情形 `GET /api/plans` → 401 `UNAUTHENTICATED` | — |
| A4 | 退出后令牌在后端失效 | 退出前 200 → 退出后同一 token 再请求 401；Redis key 已消失 | Redis |
| A5 | 两个用户数据隔离 | A 读写 B 的 profile/goal/body-records/plan 全部 `RESOURCE_NOT_FOUND`；含子资源（B 的 plan_day/item 经 A 的接口） | MySQL 8.4 |
| A6 | 体重重复提交不重复新增 | 同 `client_record_uuid` 二次 POST → 返回既有记录 + `created=false`，行数 1 | MySQL 8.4 |
| A7 | 补录旧记录不改变最新体重 | 先记 70kg（今天），再补录业务日期为上周的 75kg → `latest` 仍是 70kg | MySQL 8.4 |
| A8 | 删除最新记录可正确回退 | 删最新后 `latest` 变上一条；删光后返回空数据而非报错 | MySQL 8.4 |
| A9 | 计划聚合完整保存 | 提交 3 个计划日 + 若干动作，断言头/日/条目同事务齐全 | MySQL 8.4 |
| A10 | 聚合任一失败完整回滚 | 构造第 2 日动作字典外键失败 → 断言 `training_plan` 行数为 0（无半份计划） | MySQL 8.4 |
| A11 | ACTIVE/ARCHIVED 内容修改不被覆盖 | 修改 ACTIVE → 生成同组新 DRAFT `revision_no=max+1`，原 ACTIVE 内容与 `id` 不变 | MySQL 8.4 |
| A12 | 并发生效只剩一个 ACTIVE | 同一用户并发 10 路 activate 两个草稿 → `status='ACTIVE'` 计数为 1，失败方得 409/版本冲突 | MySQL 8.4 |
| A13 | `active_user_id` 由数据库派生 | 断言 ORM 生成的 INSERT/UPDATE SQL 不含该列；手工 UPDATE 报错 | MySQL 8.4 |
| A14 | CHECK 约束真实生效 | `weight_kg=0`、`reps_max<reps_min`、`iso_weekday=8`、`target_sets=0` 均被数据库拒绝 | **仅 MySQL 8.4**（5.7 会静默通过） |
| A15 | 导入错误定位到字段 | 非法 JSON / 未知动作码 / 重复星期 / 非法枚举 / 含服务端字段 / 超限体积，六种输入各返回带 `path` 的错误 | — |
| A16 | 模板真实可导入 | 用交付的模板原样导入成功 → 草稿内容与模板一致（动作名、星期、组次、负重、时长逐字段核对） | MySQL 8.4 |
| A17 | 导入幂等 | 同 `Idempotency-Key` 二次确认 → 同一 `planId` + `idempotentReplay=true`，计划行数不增 | Redis |
| A18 | 微信登录不泄露身份控制权 | 请求体带 `userId`/`openid` 不影响结果；后端忽略并（严格 schema）报错 | — |
| A19 | prod 不暴露 dev-login | 以 `prod` profile 启动，`POST /api/auth/dev-login` → 404；启动日志无该 bean | — |
| A20 | 页面 5 状态齐全 | 逐页人工核对加载/空/失败/成功/失效，截图记录 | 微信开发者工具 |
| A21 | 微信小程序可编译运行 | `npm run build:mp-weixin` + 开发者工具导入 `dist/build/mp-weixin`，登录→建档案→记体重→建计划→导入→生效全链路走通 | 微信开发者工具 + 后端 |
| A22 | 无假功能 | 代码评审：无占位按钮、无写死成功弹窗、`src/api` 每个函数都有调用点 | — |

演示数据必须真实走接口写入（不手工 INSERT），且与生产配置隔离。本机缺 MySQL 8.4 / Docker / 微信 AppSecret 时，A1–A2、A5–A14、A16–A17 归入"未验证项"，交付说明给出 §p1-database 第 9 节的复现命令，不虚构"全部通过"。

---

## 7. 已知风险与缓解

| 风险 | 处理 |
|---|---|
| Boot 4.1.1 + MyBatis-Plus | Boot 4 专用 starter 已确认存在，但 MP 自动装配层针对 Boot 2.7.18 编译、社区验证只到 Boot 4.0.x，无人验证过 4.1.1 → B-0 硬门禁 + 四级降级阶梯，失败先告知你再动 |
| MP 在 Boot 4 的 Jackson 坑 | 三条硬性注意写进评审清单：必须用 `spring-boot4-starter`（用错 boot3 会 `factoryBeanObjectType` 崩溃）、JSON 字段必须 `Jackson3TypeHandler`、禁用 `type-handlers-package` 扫描 |
| 本机无 MySQL 8.4 / 无 Docker | 你自己装；期间迁移类验收标注未验证，Testcontainers 组默认跳过 |
| 本机 MySQL 5.7 在用 | 阶段 B 不连接、不写、不删；迁移账号与库名单独配置且指向 8.4 |
| 本机 Redis 3.2.100 非官方移植 | 只用 SET/GET/TTL/DEL；先冒烟，不通则换 Redis 7（改配置不改代码） |
| 微信真机不能访问 `http://localhost` | 开发者工具关 `urlCheck`；真机用电脑局域网 IP + 调试模式；联调结论区分"工具内"与"真机" |
| Node 24 对 uni-app/Vite 5.2.8 无官方声明 | 用预设锁定的 Vite 版本不擅自升级；构建失败先降 Node LTS 验证再定性 |
| `wx.chooseMessageFile` 在开发者工具与真机行为可能不同 | 交付说明分别记录两端实测结果，工具内通过不等同于真机可用 |
| Maven 平台编码 GBK | `project.build.sourceEncoding=UTF-8` + Flyway `encoding=UTF-8` + 文件写入显式 UTF-8 |
