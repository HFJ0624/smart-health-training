# FitAgent P1 基础功能：API 契约

版本：v1.0 规划稿；日期：2026-10-13。依赖：`p1-scope.md`（流程与验收）、`p1-database.md`（字段与不变式）、`p1-plan-import.md`（导入规范）。

---

## 1. 通用约定

| 项 | 约定 |
|---|---|
| Base URL | `{BASE_URL}/api`，`BASE_URL` 由小程序环境配置注入，不写死在代码里 |
| 传输 | `application/json; charset=utf-8`；本轮无 multipart |
| 鉴权 | `Authorization: Bearer <opaqueToken>`。除 `POST /api/auth/wechat-login`、`POST /api/auth/dev-login`、`/actuator/health`、`/actuator/info` 外全部必须认证 |
| BigInt | 所有 `id`/`userId`/`dataRevision` 等 64 位整型在 JSON 中**以字符串传输**（Jackson `ToStringSerializer`），避免 JS 精度丢失 |
| 时间 | 瞬时值用 ISO-8601 UTC（`2026-10-13T07:20:00.000Z`）；业务日期用 `YYYY-MM-DD`。两者分字段返回，不混用 |
| 时区 | 业务日期由**服务端**按 `user_account.timezone` 推导；客户端不传 `businessDate` |
| 响应包 | 所有响应同形：`{ code, message, data, traceId, serverTime }`；`code` 是稳定机器码，前端只 switch `code`，不解析 `message` 文案 |
| HTTP 状态 | 表达错误类别（400/401/403/404/409/413/422/429/500/502），`code` 表达具体原因 |
| 分页 | `page` 从 1 起（默认 1）、`size` 默认 20 **上限 100**；返回 `data.page/size/total/hasNext`。超出上限判 `VALIDATION_FAILED`，不静默截断 |
| 排序 | `sort=<白名单字段>,<asc|desc>`；每个接口声明白名单与默认排序；不在白名单即 `INVALID_SORT_FIELD`，**绝不拼接进 SQL** |
| 日期范围 | `from`/`to`（含）均为 `YYYY-MM-DD`，跨度 **≤ 366 天**，`to < from` 报错；不传则用各接口声明的默认窗口 |
| 乐观锁 | 更新/状态迁移必须携带 `lockVersion`（取自上次读取）；不符 → 409 `VERSION_CONFLICT` |
| 幂等 | `Idempotency-Key`（UUID v4）**只用于计划导入确认**（缺失则服务端生成并回传，Redis TTL 24h）。其余写接口靠各自的天然键保护：体重用 `client_record_uuid` 唯一索引，档案/目标/计划用 `lockVersion` + 数据库唯一约束，不额外铺一层幂等 |
| 限流 | 登录 10 次/分钟/IP；导入 preview+confirm 30 次/分钟/用户；全局 300 次/分钟/用户。超限 429 `RATE_LIMITED`，带 `Retry-After` |
| 请求体上限 | 普通 JSON ≤ 64 KiB；导入 ≤ 256 KiB。超限 413 `PAYLOAD_TOO_LARGE` |
| 未知字段 | 请求 DTO 全部 `FAIL_ON_UNKNOWN_PROPERTIES=true` → 422 `UNKNOWN_FIELD`。客户端**不能**用任意键影响服务端行为 |
| 越权 | 一律 404 `RESOURCE_NOT_FOUND`（不用 403），避免泄露资源是否存在 |

## 2. 错误码总表

| code | HTTP | 含义 | 前端处理 |
|---|---|---|---|
| `OK` | 200/201 | 成功 | — |
| `VALIDATION_FAILED` | 422 | Bean Validation 失败，`data.fields=[{field,code,message}]` | 表单逐字段提示 |
| `UNAUTHENTICATED` | 401 | 无/非法令牌 | 清本地令牌 → 登录页 |
| `SESSION_EXPIRED` | 401 | 令牌已过期 | 同上，并提示"登录已失效，请重新登录" |
| `ACCOUNT_DISABLED` | 403 | 账户非 ACTIVE | 提示后登出 |
| `RESOURCE_NOT_FOUND` | 404 | 不存在**或**不属于本人 | 空状态或"数据已变更，请刷新" |
| `VERSION_CONFLICT` | 409 | 乐观锁不符 | 提示"他人已修改/页面数据已过期"，引导重新加载 |
| `INVALID_STATE_TRANSITION` | 409 | 状态机不允许（如编辑 ACTIVE、归档 DRAFT） | 提示并刷新详情 |
| `ACTIVE_PLAN_EXISTS` | 409 | 并发激活被 `UNIQUE(active_user_id)` 拦下（正常流程不会出现：激活本身就在同一事务内无条件归档旧 ACTIVE，因此**没有** `replace` 这类开关字段） | 提示"计划状态已变化"并重新读取详情 |
| `MALFORMED_JSON` | 400 | 不是合法 JSON | 导入页显示解析错误位置 |
| `PLAN_IMPORT_INVALID` | 422 | 导入校验未通过，`data.errors[].path` 定位 | 逐行高亮 |
| `UNSUPPORTED_SCHEMA_VERSION` | 422 | 模板版本不受支持 | 提示下载新模板 |
| `FORBIDDEN_SERVER_FIELD` | 422 | 输入含服务端生成字段 | 明确指出该字段不可指定 |
| `PAYLOAD_TOO_LARGE` | 413 | 超体积上限 | 提示精简 |
| `RATE_LIMITED` | 429 | 触发限流 | 按 `Retry-After` 提示 |
| `WECHAT_LOGIN_FAILED` | 502 | 微信侧失败，`data.wechatErrcode` 原样给出数字码 | 按 §3.2 映射文案 |
| `WECHAT_CONFIG_MISSING` | 500 | 后端未配置 AppID/AppSecret | 非用户可修，提示联系维护者 |
| `INTERNAL_ERROR` | 500 | 未预期异常 | 通用失败态，展示 `traceId`；**不返回堆栈** |

导入类字段级码（`REQUIRED` `TOO_LONG` `INVALID_TYPE` `INVALID_DATE_FORMAT` `INVALID_ENUM_VALUE` `INVALID_WEEKDAY` `MUST_BE_POSITIVE` `NEGATIVE_LOAD` `REST_OUT_OF_RANGE` `REPS_MAX_LT_MIN` `ENDS_BEFORE_STARTS` `DUPLICATE_WEEKDAY` `DUPLICATE_POSITION` `EXERCISE_NOT_FOUND` `EXERCISE_DISABLED` `REST_DAY_HAS_ITEMS` `EMPTY_TRAINING_DAY` `TOO_MANY_DAYS` `TOO_MANY_ITEMS` `TOO_MANY_ITEMS_TOTAL` `STRENGTH_DURATION_FORBIDDEN` `CARDIO_SETS_FORBIDDEN` `CARDIO_REPS_FORBIDDEN` `CARDIO_LOAD_FORBIDDEN` `CARDIO_REST_FORBIDDEN` `MOBILITY_LOAD_FORBIDDEN` `MOBILITY_NO_TARGET` `UNKNOWN_FIELD`）的语义与触发条件见 `p1-plan-import.md` §2–§8。

---

## 3. auth 模块

### 3.1 `POST /api/auth/wechat-login`

```json
// 请求
{ "code": "0e3Kp8l00..." }        // wx.login 返回的 code，一次性、有效期由微信侧决定

// 201 首次注册
{ "code": "OK", "data": {
    "token": "b1f7…（44 位 base64url）", "tokenType": "Bearer",
    "expiresAt": "2026-10-20T07:20:00.000Z",
    "userId": "1", "nickname": null,
    "isNewAccount": true, "devMode": false, "profileRequired": true
}}
// 200 重复登录：isNewAccount=false
```

- 请求体**只有 `code`**。任何 `userId`/`openid`/`appId` 键 → `UNKNOWN_FIELD`。身份完全由后端从微信侧核实结果决定，这是"用户不能通过前端传 userId/openid 决定身份"的落点。
- 实现：`GET https://api.weixin.qq.com/sns/jscode2session?appid={APPID}&secret={SECRET}&js_code={code}&grant_type=authorization_code`（4 个参数均必填，GET 无请求体；AppSecret 只存在于后端环境变量，微信官方亦明确要求不得在小程序内调用该接口）。响应取 `openid`（必填）与 `unionid`（可选）。
- 微信错误码映射：`40029`→提示"登录凭证无效，请重试"；`45011`→"操作过于频繁，请稍后再试"；`40226`→"该账号登录被拦截"；`-1`→"微信服务繁忙，请稍后重试"。其余归 `WECHAT_LOGIN_FAILED` 并只回传数字码。
- 令牌：`SecureRandom` 32 字节 → base64url；Redis 键 `fa:sess:{sha256(token)}`，值 `userId|expiresAt`，TTL 7 天。数据库**不存令牌**，明文不落日志。续期策略：每次校验命中且剩余 TTL < 1 天时 `EXPIRE` 重置为 7 天（滑动续期）。
- 并发注册：先 `SELECT`，未命中则 `INSERT`（`REQUIRES_NEW` 短事务）；捕 `DuplicateKeyException` 后在新事务里重查复用。唯一索引 `uq_user_account_1` 是正确性来源，不是提示性检查。

### 3.2 `POST /api/auth/dev-login`（仅 dev）

```json
{ "devUserId": "dev-a" }   // 只接受 dev-a | dev-b
```
返回同 3.1，`devMode: true`。`@Profile("dev")` + `fitagent.auth.dev-login.enabled=true` 双开关；`prod` profile 下该路由不注册（验收 A19：探测到即启动失败）。两个用户映射到 `wechat_app_id='wx.dev.local'` + 固定 openid，用于隔离与双用户越权测试（A5）。

### 3.3 `POST /api/auth/logout` → `200 OK`，`data: null`

`DEL fa:sess:{sha256(token)}`。幂等：无令牌或已失效也返回 200。前端无论成败都清本地令牌并 `reLaunch` 登录页。

### 3.4 `GET /api/me`

```json
{ "code": "OK", "data": { "userId": "1", "nickname": null, "role": "USER",
  "status": "ACTIVE", "timezone": "Asia/Shanghai", "dataRevision": "7",
  "lastLoginAt": "2026-10-13T07:20:00.000Z", "createdAt": "…", "devMode": false } }
```
用于启动时探活（登录态判定）。

---

## 4. profile / goal 模块

### 4.1 `GET /api/me/profile` → 无档案时 `data: null`（200，非 404）

区分"没有档案"与"没权限"：前者是正常空状态，页面显示"建立档案"。

### 4.2 `POST /api/me/profile`（首次建立）／ `PUT /api/me/profile`（修改，需 `lockVersion`）

```json
// 请求
{ "heightCm": 175.00, "trainingLevel": "BEGINNER",
  "equipment": ["BARBELL","DUMBBELL","MAT"],
  "birthDate": "1994-05-08", "sexForEquation": "MALE",
  "trainingConstraints": ["每次训练不超过 60 分钟"],
  "lockVersion": 0 }
```

- 只有 `heightCm` 必填：>0、≤2 位小数、落在 `DECIMAL(6,2)` 范围内。**不加身高/体重医学区间阈值**（提示词明确禁止未经说明的阈值）。
- `equipment` 1–16 项，每项必须在器械代码词表内（`p1-plan-import.md` §9）。`trainingConstraints` 是**字符串数组**（落库为 JSON 数组），不是单条长文本。
- 请求体**没有 `nickname`**：昵称属于 `user_account`，只在 `GET /api/me` 出参可见。本轮没有写昵称的入口（微信已不再提供头像昵称接口），所以档案 DTO 里不存在这个字段，传了就按 `UNKNOWN_FIELD` 拒绝——比"收下但无处存"诚实。
- `birthDate` 可选；服务端校验格式与"不晚于今天"，不推断年龄并展示。`sexForEquation` 默认 `UNSPECIFIED`。
- `activityCategory`/`dietPreferences`/`professionalGuidanceRequired` 本轮不开放写入（无消费方，不录入无人使用的数据）。
- 并发首次创建：`UNIQUE(user_id)` 保证一人一档，第二次 `POST` → 409 `VERSION_CONFLICT`，前端改为读取后 `PUT`。
- 成功 → `dataRevision + 1`。

### 4.3 目标

| 接口 | 说明 |
|---|---|
| `GET /api/me/goals?status=&page=&size=&sort=createdAt,desc` | 分页历史；默认全部状态 |
| `GET /api/me/goals/active` | 当前 ACTIVE，无则 `data: null` |
| `POST /api/me/goals` | 新建。`activate`（默认 true）为 true 时事务内归档旧 ACTIVE |
| `PUT /api/me/goals/{id}` | 需 `lockVersion`。DRAFT/ACTIVE 可改；**ARCHIVED 不可改** → `INVALID_STATE_TRANSITION` |
| `DELETE /api/me/goals/{id}` | **只删 DRAFT**；ACTIVE/ARCHIVED 拒绝 |
| `POST /api/me/goals/{id}/activate` | 归档旧 ACTIVE 后激活；`UNIQUE(active_user_id)` 兜底 |
| `POST /api/me/goals/{id}/archive` | 仅 ACTIVE 可归档 |

```json
// POST /api/me/goals 请求
{ "goalType": "FAT_LOSS", "targetWeightKg": 70.00, "weeklySessionsTarget": 4,
  "startsOn": "2026-10-12", "endsOn": "2027-01-31", "activate": true }
// 新建目标不带 lockVersion：后端 CreateGoalRequest 没有该组件，带上即 422 UNKNOWN_FIELD
```

> 设计说明（与设计包一致的取舍）：`user_goal` 在设计包中**没有** `plan_group_uuid`/`revision_no` 那套版本机制，只有 `status` 历史。因此目标采用"就地改 + 归档留史"，而不是像计划那样产生新 revision。这是本轮与计划语义不一致的地方，需要你知晓（文末确认清单第 3 项）。`dailyIntakeTargetKcal` 不出现在请求与页面（列保留，响应恒为 `null`）。

---

## 5. body 模块

| 接口 | 说明 |
|---|---|
| `GET /api/body-records?from=&to=&page=&size=&sort=measuredAt,desc` | 默认窗口：最近 90 天（含今天）。白名单排序：`measuredAt` `businessDate` `weightKg` |
| `GET /api/body-records/latest` | `ORDER BY measured_at DESC, id DESC LIMIT 1`；无记录 `data: null` |
| `POST /api/body-records` | 新增 |
| `PUT /api/body-records/{id}` | 修改（需 `lockVersion`；`clientRecordUuid` 不可变） |
| `DELETE /api/body-records/{id}` | 删除 |

```json
// POST 请求
{ "measuredAt": "2026-10-13T07:20:00.000Z", "weightKg": 72.50,
  "waistCm": 88.0, "bodyFatPercent": null,
  "clientRecordUuid": "3f1a…（UUID v4）", "notes": "晨起空腹" }

// 201 新增
{ "code": "OK", "data": { "id": "12", "businessDate": "2026-10-13", "measuredAt": "…",
  "weightKg": 72.50, "waistCm": 88.00, "bodyFatPercent": null, "source": "MANUAL",
  "clientRecordUuid": "…", "notes": "晨起空腹", "lockVersion": 0, "createdAt": "…",
  "created": true, "idempotentReplay": false } }

// 同 clientRecordUuid 二次提交 → 200 + created:false + idempotentReplay:true，且行数不变
```

- `weightKg` 必填 >0，≤2 位小数；`waistCm` >0；`bodyFatPercent` 0–100。无体脂/体重医学判定。
- `measuredAt` 必须可解析为瞬时值；服务端拒绝晚于当前时间 +5 分钟的值（`VALIDATION_FAILED`/`FUTURE_MEASURED_AT`），这是防误填的工程护栏，不是业务规则，阈值 5 分钟需要你认可（确认清单第 4 项）。
- `businessDate` 由 `measuredAt` 在用户时区推导 → **补录一条业务日期更早的记录不会成为"最新体重"**（验收 A7）。
- 去重靠 `UNIQUE(user_id, client_record_uuid)`：捕获 `DuplicateKeyException` 后返回既有行，不报 500。
- 三种写操作都使 `dataRevision + 1`；删除最新记录后 `latest` 自动回退（验收 A8）。

---

## 6. exercise 模块（只读）

`GET /api/exercises?category=&equipment=&keyword=&page=&size=&sort=name,asc`

- 只返回 `status='ACTIVE'`；`keyword` 对 `name`/`exercise_code` 做前缀与包含匹配，参数转义后走 MyBatis 占位符（不拼接 SQL）。
- 响应项：`{ id, exerciseCode, name, category, muscleGroups[], equipment[], sourceUrl: null, instructions: null }`。
- 无写接口。字典维护（`role=ADMIN`）属后续阶段。

---

## 7. training 计划模块

聚合结构（读取与写入同形，减少两套模型）：

```json
{ "id": "5", "userId": "1", "planGroupUuid": "…", "revisionNo": 2,
  "basePlanId": "3", "title": "综合周计划示例（减脂方向）", "source": "IMPORT",
  "status": "DRAFT", "startsOn": "2026-10-12", "endsOn": null,
  "confirmedAt": null, "lockVersion": 0, "createdAt": "…", "updatedAt": "…",
  "goalSnapshot": { "schemaVersion": "fitagent.goal_snapshot/1", "capturedAt": "…",
                    "goalType": "FAT_LOSS", "absentFields": [] },
  "days": [
    { "id": "9", "isoWeekday": 1, "title": "下肢与上肢拉", "isRestDay": false, "notes": null,
      "items": [
        { "id": "31", "positionNo": 1, "exerciseId": "1", "exerciseCode": "BARBELL_BACK_SQUAT",
          "exerciseName": "杠铃深蹲", "category": "STRENGTH",
          "targetSets": 4, "repsMin": 8, "repsMax": 10,
          "targetExternalLoadKg": 60.00, "targetDurationSeconds": null,
          "restSeconds": 120, "notes": null } ] },
    { "id": "12", "isoWeekday": 3, "title": "休息日", "isRestDay": true, "notes": null, "items": [] }
  ] }
```

| 接口 | 语义 | 关键规则 |
|---|---|---|
| `GET /api/plans?status=&groupId=&page=&size=&sort=updatedAt,desc` | 版本列表 | 默认含 ARCHIVED/DISCARDED；`status` 多选逗号分隔，白名单校验 |
| `GET /api/plans/active` | 当前生效计划（含完整聚合） | 无则 `data: null` → 首页显示"未设置计划" |
| `GET /api/plans/today` | 今天的计划日 | `{ activePlanId, planGroupUuid, revisionNo, isoWeekday, businessDate, day }`；`day=null` 表示今天该计划不安排（超出 `ends_on` 或非排程日）；无 ACTIVE 时 `activePlanId=null` |
| `GET /api/plans/{id}` | 单版本详情 | `(id,user_id)` 复合校验 |
| `POST /api/plans` | 新建 DRAFT（聚合） | `source=MANUAL`，`planGroupUuid` 服务端生成，`revisionNo=1`，`goalSnapshot` 服务端生成 |
| `PUT /api/plans/{id}` | 就地编辑 DRAFT（**聚合替换**） | 仅 DRAFT；子行"删旧插新"在同事务；`lockVersion` 必填 |
| `POST /api/plans/{id}/revise` | 基于 ACTIVE/ARCHIVED 生成新 DRAFT 版本 | 同 `planGroupUuid`，`revisionNo=max+1`，`basePlanId=id`；**不修改来源版本**（验收 A11） |
| `POST /api/plans/{id}/copy` | 复制成新计划组 | 新 `planGroupUuid`，`revisionNo=1`，`source` 保留原值，标题追加"（副本）" |
| `POST /api/plans/{id}/activate` | 确认生效 | `lockVersion`；锁 `user_account` → 校验归属/版本 → 旧 ACTIVE 置 ARCHIVED → 本草稿置 ACTIVE + `confirmedAt` → `dataRevision+1` |
| `POST /api/plans/{id}/archive` | 归档当前计划 | 仅 ACTIVE；归档后首页显示未设置计划 |
| `POST /api/plans/{id}/discard` | **丢弃草稿** | 仅 DRAFT → 置 `DISCARDED`，**行保留**（不做物理删除，杜绝误删历史） |

写接口一律走聚合提交：一次请求带完整 `days[]`（含 items），服务端在同一事务内重建子行。P1 不提供 `POST /api/plans/{id}/days` 之类的细粒度接口——聚合替换更贴合"新增、编辑或导入'计划头、计划日、动作'必须作为一个聚合处理"的要求，也避免半份计划。**P2 接入实际训练打卡后这条必须改**（届时计划日已被 `workout_session` 引用，不能删旧插新），已记入 `p1-database.md` §6。

状态迁移矩阵：

| 当前 | 允许的操作 |
|---|---|
| `DRAFT` | `PUT` 编辑、`activate`、`discard`、`copy` |
| `ACTIVE` | `archive`、`revise`、`copy`（**不可** `PUT`/`discard`） |
| `ARCHIVED` | `revise`、`copy` |
| `DISCARDED` | `copy`（终态，只读） |

违规迁移 → 409 `INVALID_STATE_TRANSITION`。

---

## 8. 导入接口

`POST /api/plans/import/preview` 与 `POST /api/plans/import` 的请求/响应结构、校验顺序、错误定位、上限与幂等规则全部见 `p1-plan-import.md` §8–§10。要点：

- preview **无副作用**；confirm 重新完整校验，不信任 preview。
- confirm 成功后 `status=DRAFT`、`source=IMPORT`，绝不激活。
- 幂等：`Idempotency-Key` + Redis TTL 24h（已确认取舍）；重放返回首次 `planId` + `idempotentReplay=true`。

---

## 9. 前端封装约定（`miniapp/src/utils/request.ts`）

| 行为 | 实现 |
|---|---|
| baseURL | `import.meta.env` 分环境注入；开发指向本机后端，**不硬编码** |
| 令牌 | 从 `uni.getStorageSync('fa_token')` 读，统一加 `Authorization` |
| 超时 | 10 s；超时归"提交失败"态，不静默成功 |
| 401 | 拦截 → 清令牌 → `reLaunch('/pages/auth/login')` → 抛出 `SESSION_EXPIRED` 供页面提示一次 |
| 重试 | 仅 GET 允许 1 次自动重试；**写接口不自动重试**（由用户显式重试，配合幂等键） |
| 错误呈现 | `data.fields`/`data.errors` 存在 → 表单/预览逐字段展示；否则展示 `message` + `traceId` |
| 调用点 | 页面只调 `src/api/{auth,profile,body,training}`，不直接用 `uni.request`；`src/api` 里每个函数必须有调用点（验收 A22） |
| BigInt | 前端把 id 一律当字符串，不参与算术 |

---

## 10. 接口清单与鉴权一览

| 方法/路径 | 鉴权 | 幂等性 |
|---|---|---|
| `POST /api/auth/wechat-login` | 公开 | 否（依赖唯一索引去重） |
| `POST /api/auth/dev-login` | 公开，仅 dev | 否 |
| `POST /api/auth/logout` | 需要 | 是 |
| `GET /api/me` | 需要 | 只读 |
| `GET/POST/PUT /api/me/profile` | 需要 | POST 靠唯一索引 |
| `GET/POST/PUT/DELETE /api/me/goals…` | 需要 | PUT/DELETE 靠 `lockVersion` |
| `GET /api/me/goals/active` | 需要 | 只读 |
| `GET/POST/PUT/DELETE /api/body-records…` | 需要 | POST 靠 `client_record_uuid` |
| `GET /api/body-records/latest` | 需要 | 只读 |
| `GET /api/exercises` | 需要 | 只读 |
| `GET /api/plans` `/active` `/today` `/{id}` | 需要 | 只读 |
| `POST/PUT /api/plans…`、`/revise` `/copy` `/activate` `/archive` `/discard` | 需要 | 靠 `lockVersion` + DB 唯一索引 |
| `POST /api/plans/import/preview` | 需要 | 无副作用 |
| `POST /api/plans/import` | 需要 | `Idempotency-Key` |
| `GET /actuator/health` | 公开 | 只读存活探针，不含业务数据；dev 下 `show-details=always`，prod 为 `never` |
