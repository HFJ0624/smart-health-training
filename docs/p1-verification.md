# FitAgent P1 验证记录

本文档记录**实际执行过的验证**与**未执行的验证**。空着的条目就是还没做，不会写成"已通过"。
每项对应 `docs/p1-scope.md` §6 的验收编号 A1–A22。

## 1.1 前后端契约对账：发现并已修的真实缺陷

这些不是"顺手优化"，而是会让功能在联调当场失败或静默错失的问题。逐条都先复核了代码再改。

| # | 缺陷 | 后果 | 修法 |
|---|---|---|---|
| 1 | `ApiResponse.ok()` 的 `message` 是 `null`，而前端 `isEnvelopeShape` 要求 `typeof message === 'string'` | **阻塞全站**：每一个成功响应都被判成 `BAD_RESPONSE`，任何页面都拿不到数据 | 前端放宽：`message` 允许 `string \| null \| undefined`；后端不动（成功本就没有文案） |
| 2 | 字段错误后端把 `List<FieldError>` 直接当 `data`，而契约与前端都取 `data.fields` | 表单永远显示不出服务端逐字段错误，"提交失败"态形同虚设 | 在异常出口单点归一化：`VALIDATION_FAILED → data.fields`、`PLAN_IMPORT_INVALID → data.errors`；业务代码继续传 List，不各拼一次 Map |
| 3 | 未知字段被 Jackson 抛出后经 Spring 包成 `HttpMessageNotReadableException`，一律报 400 `MALFORMED_JSON` | 与契约 422 `UNKNOWN_FIELD` 语义不符，前端无法区分"格式错"和"多写字段"，验收 A18 无据 | 下钻 cause 链识别 `tools.jackson.databind.exc.UnrecognizedPropertyException` → 422 `UNKNOWN_FIELD` 并带属性名 |
| 4 | 前端激活计划时可能发送 `{lockVersion, replace:true}` | 后端 `ActivateRequest` 只有 `lockVersion` + 全局拒绝未知字段 → **激活当场 422**；且后端本就是无条件归档旧 ACTIVE，`replace` 是臆造的开关 | 删掉 `replace` 与二次重试分支；改为调用前确认弹窗说明"旧计划会被归档"，契约同步更正 |
| 5 | 前端 `PlanAggregateInput` 带顶层 `notes`，后端 `PlanUpsertRequest` 没有 | 保存草稿 422 `UNKNOWN_FIELD` | 计划级备注整条链路移除（模板、编辑器、详情页、导入规范）；`training_plan` 本来就没有该列 |
| 6 | 导入接受顶层 `notes` 却无处可存 | "预览里有、保存后没了"= 静默丢弃 | 校验器直接判 `PLAN_NOTES_UNSUPPORTED` 拦下；模板去掉该字段；计划日/动作的 `notes` 有真实列，不受影响 |
| 7 | `utils/date.ts`：`localDateTimeToInstant` 先 `replace(/-/g,'/')` 再 `new Date()` | 对本文件自己生成的 `2026-10-13T17:05:00` **恒返回 null**（实测 V8 也失败），体重提交被卡死 | 改为显式拆分量 + 本地构造器组装，跨引擎一致，并拒绝 2 月 31 日这类被 Date 静默进位的溢出；顺带删掉页面里的临时绕行实现 |
| 8 | `UserGoalEntity.dailyIntakeTargetKcal` 同时 `select=false` + `updateStrategy=ALWAYS` | 每次 `updateById` 都把该列写回 NULL。P1 无害，P2 一旦开始写饮食目标，每次改目标都会静默清空 | insert/update 双 `NEVER`，本轮 ORM 完全不碰该列 |

另有 2 处工程护栏补齐：契约 §1 承诺的**限流**（登录 10/分/IP、导入 30/分、全局 300/分，Redis 固定窗口，计数组件故障时放行而非打死全站，且不解析 `X-Forwarded-For`——伪造头即可绕过）与**请求体上限**（普通 64 KiB、导入 256 KiB）此前只写在文档里，现在由 `RateLimitFilter` 真正执行。

## 1. 已在开发机执行


| 项 | 命令/方式 | 结果 | 证据 |
|---|---|---|---|
| 本机工具链勘察 | `java -version`、`mvn -v`、`node -v`、`which docker`、`netstat -ano`、`tasklist` | 完成 | JDK 17.0.10；Maven 3.8.8（GBK）；Node 24.18.0；**Docker 不存在**；MySQL **5.7.19** 在用(3306)；Redis **3.2.100** 在用(6379)；微信开发者工具已装 |
| Boot 4.1.1 + MyBatis-Plus 依赖门禁 | `mvn -B dependency:tree` | 通过 | `mybatis-plus-spring-boot4-starter:3.5.17`、`mybatis-spring:4.1.0`、`flyway 12.4.0`+`flyway-mysql`、`mysql-connector-j 9.7.0`、`lettuce 7.5.2`、`security 7.1.1`、`hibernate-validator 9.1.3`、`jakarta.validation-api 3.1.1` |
| Jackson3TypeHandler 可用性 | `javap` 直接读 jar | 确认存在，构造器 `(Class<?>)` / `(Class<?>,Field)`，**无无参构造** | 因此禁止 `type-handlers-package` 扫描 |
| classpath 上 Jackson 2 databind 是否存在 | `dependency:tree` | 不存在，只有 `tools.jackson:3.1.5` | JSON 列必须 `Jackson3TypeHandler` |
| 分页拦截器归属 | 解包 `mybatis-plus-extension-3.5.17.jar` | extension 内只剩 `OptimisticLocker`/`DynamicTableName`/`ReplacePlaceholder` | 需另引 `mybatis-plus-jsqlparser`，否则分页静默失效 |
| 后端编译 | `mvn -B -pl app -am compile` | BUILD SUCCESS | 基座 + auth/profile/training 三模块 + 8 实体 + 8 Mapper + 9 个 Flyway 脚本 |
| 导入模板自洽性 | 脚本比对模板与字典清单 | 通过 | JSON 合法；12 个动作代码全部命中 25 条种子；星期无重复；休息日无动作；非休息日非空；`position_no` 无重复 |
| 建表脚本安全扫描 | 正则扫描注释以外的语句 | 通过 | `docs/sql/p1-init-mysql-8.4.sql` 含 8 个 `CREATE TABLE`、1 条种子 `INSERT`、15 个 CHECK、10 个外键、2 个生成列、4 处 `utf8mb4_bin`；**无 DROP / DELETE / TRUNCATE** |

## 2. 因环境缺失而未执行

以下项目**依赖 MySQL 8.4（和/或 Redis、微信凭据、微信开发者工具）**，本机没有可用 8.4 实例，按约定不由我安装。

| 验收项 | 需要 | 复现方式 |
|---|---|---|
| A1 首次注册/重复登录 | MySQL 8.4 + 微信凭据 或 dev-login | `SPRING_PROFILES_ACTIVE=dev` 启动后连续两次 `POST /api/auth/dev-login` |
| A2 并发首登只一个账号 | MySQL 8.4 | 同一 openid 并发 20 路登录，`SELECT COUNT(*) FROM user_account` |
| A4 退出后令牌失效 | Redis | 登录 → 退出 → 同令牌重请求应 401；`redis-cli EXISTS fa:sess:<sha256>` |
| A5 两用户数据隔离 | MySQL 8.4 | 用 dev-a / dev-b 交叉读写 |
| A6–A8 体重幂等/补录/回退 | MySQL 8.4 | 见 `docs/p1-scope.md` §6 |
| A9–A13 计划聚合/版本/并发激活/生成列 | MySQL 8.4 | 并发 10 路 `POST /api/plans/{id}/activate` |
| A14 CHECK 约束真实生效 | **只能 MySQL 8.4**（5.7 静默通过） | `INSERT ... weight_kg=0` 等 4 条 |
| A16–A17 导入成功与幂等 | MySQL 8.4 + Redis | 用交付模板走 preview→confirm |
| A19 prod 不暴露 dev-login | 应用可启动 | `SPRING_PROFILES_ACTIVE=prod` 启动后请求该路径应 404，且启动期校验不报错 |
| A21 微信编译与联调 | 微信开发者工具 + 后端可访问地址 | `npm run build:mp-weixin` → 工具导入 `dist/build/mp-weixin` → 走通登录/档案/体重/计划/导入 |
| 迁移在真实库执行 | MySQL 8.4 | 见 `docs/p1-database.md` §9 |

## 3. 单元测试（已执行）

| 命令 | 结果 |
|---|---|
| `mvn -B -pl app -am test`（JDK 17.0.10 / Maven 3.8.8） | **Tests run: 177, Failures: 0, Errors: 0, Skipped: 0** — BUILD SUCCESS |
| `npx vue-tsc --noEmit` | **0 error** |
| `npm run build:mp-weixin` | **DONE Build complete.**，产物 `dist/build/mp-weixin`，11 个页面 + `app.json` tabBar（首页/训练计划/我的）齐全 |

测试分布（无 Spring 上下文、无数据库、无 Docker）：

| 测试类 | 用例数 | 覆盖的验收点（应用侧） |
|---|---:|---|
| `AccountProvisioningServiceTest` | 7 | A1/A2 的并发注册与 DuplicateKey 复用 |
| `WechatLoginServiceTest` | 14 | 微信错误码映射、配置缺失、`session_key` 不出现在响应、一次登录只发一次令牌、URL 编码 |
| `DevLoginServiceTest` | 5 | 两个测试用户映射为不同账户、越界 devUserId 拒绝 |
| `AccountSessionServiceTest` | 6 | 退出幂等、令牌取自 Authorization 原文 |
| `ProfileRequestValidationTest` | 7 | 身高必填/正数/精度、器械词表、枚举必须大写字面量 |
| `UserProfileServiceTest` | 13 | 档案只写允许字段、乐观锁、并发首次创建转 409 |
| `UserGoalServiceTest` | 20 | 先归档后激活的顺序、ARCHIVED 不可改/不可删、DRAFT 才可删、排序白名单 |
| `BodyMeasurementServiceTest` | 16 | A6 幂等回退、A7 业务日期由服务端推导、`latest` 只走 `findLatest`、未来时间拒绝、UUID 不可变 |
| `ProfileContextReaderImplTest` | 6 | 跨模块只读端口、查询恒带 user_id |
| `GeneratedColumnGuardTest` | 2 | 反射 + 源码扫描双重确认：实体不声明 `active_user_id`，SQL 不写该列 |
| `PlanAggregateValidatorTest`（@Nested） | — | §5 类型×字段矩阵、星期重复、休息日带动作、空训练日、顺序重排警告、长度与数量上限 |
| `PlanImportServiceTest`（@Nested） | — | A15/A16：交付模板零错零警、非法 JSON、schema_version、禁用服务端字段、体积闸门边界、幂等 |
| `TrainingPlanServiceStateMachineTest` | 11 | 激活的加锁与归档顺序、版本不符不写、冲突不加修订号、`UNIQUE(active_user_id)` 冒出来时转 ACTIVE_PLAN_EXISTS、**丢弃不物理删行**、**revise 不覆盖来源版本** |

### 测试自身的 5 个缺陷（已修，记录以免被当成"实现没问题"的错觉）

首轮 `mvn test` 是 **166 跑 / 6 失败**。逐个定位后，6 个都出在测试写法而非实现：

1. `duplicateWeekdayFromJson` 把第二个计划日的 `iso_weekday` 改成 2，于是构造出的是"一周里合法两天"，永远撞不出重复 → 改为两个都用 weekday=1。
2. `payloadExactlyAtLimitIsAccepted` 用 `String.replace` 给 `"notes": null` 补零，但该片段在文档里出现多次，padding 被复制 → 变成 785KB。且**任何合法文档都不可能凑到 256KiB**（每个字段的上限远小于它），这个用例本身不成立 → 换成直接锁死"闸门用 `>` 且在解析之前"的边界语义（等长→MALFORMED_JSON，超 1 字节→PAYLOAD_TOO_LARGE）。
3. `UserProfileServiceTest` 断言"回读到 175"，但 mock 的 `selectOne` 返回的正是服务当场改写的那个实例，回读必然是提交值 180 → 改断言并说明单测证明不了 DB 回读。
4. `UserGoalServiceTest` 用 `containsExactly` 断言白名单**顺序**，而白名单是成员关系 → 改 `containsOnly`。
5. `ProfileContextReaderImplTest` 在读 `paramNameValuePairs` 前先没生成 SQL 片段（MyBatis-Plus 在生成片段时才回填占位参数），拿到的是空集合 → 先断言 `getSqlSegment()` 含 `user_id` 再断言参数。

另外补了 `TrainingPlanServiceStateMachineTest`：原实现方在被中断前没来得及写状态机测试，而 A9–A12 的应用侧一半正落在这里。

## 4. 集成与真机：仍未执行

需要你的 MySQL 8.4 / Redis / 微信凭据 / 微信开发者工具，见 §2 表格。给我 `FITAGENT_MYSQL_URL`、库名与账号（口令走环境变量，不用贴明文）我就把 A1–A2、A5–A14、A16–A17 真跑一遍并回填本节。
