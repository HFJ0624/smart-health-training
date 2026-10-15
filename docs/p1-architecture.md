# FitAgent P1 基础功能：架构规划

版本：v1.0 规划稿；日期：2026-10-13。依据：`Codex_P1基础功能_先规划后开发提示词.md` + `FitAgent_项目设计/`（v1.0，2026-10-12）。

配套：`p1-scope.md`（范围与验收）、`p1-api.md`（契约）、`p1-database.md`（表与迁移）、`p1-plan-import.md`（导入规范）。

---

## 1. 结论摘要

| 项 | 决定 |
|---|---|
| Java | **17**（JDK 17.0.10 实测存在；与设计包文档里的 Java 21 冲突，按你的指示**以 Java 17 为准**） |
| 后端 | Spring Boot **4.1.1** + Spring Security + MyBatis-Plus + Bean Validation（Jakarta）+ Flyway，单应用、按业务模块分包 |
| ⚠️ 前置门禁 | Boot 4 专用 MP starter **已确认存在**，但 `mybatis-plus-spring-boot-autoconfigure` 针对 Boot 2.7.18 编译、社区验证只到 Boot 4.0.x，**MP 3.5.17 + Boot 4.1.1 无人验证过** → 阶段 B 第 0 步做小范围可行性验证（§3.3），失败按阶梯降级并先告知你 |
| 数据 | MySQL **8.4**（目标），Redis 承载会话令牌 + 导入幂等 + 限流 |
| 迁移 | Flyway，本阶段 `V1..V9`，8 张表 |
| 小程序 | uni-app + Vue 3 + TypeScript + Pinia（版本按 §3.2 锁定，有已知坑） |
| 基础设施 | `infra/docker-compose.yml`（MySQL 8.4 + Redis 7）交付但**本机不执行**：Docker 未安装，MySQL 仅有在用 5.7 实例，按你的指示不代为安装 |
| 本轮不启动 | `agent-core`、`media-worker`、Python 服务、Qdrant、OSS。边界只写入文档（§7），**不创建空模块、不建空类** |

---

## 2. 目录结构与职责

```
smart-health-training/
├─ README.md                         启动命令、环境要求、目录说明、未验证项
├─ AGENTS.md                         （B-1 建议新建）项目约定，供后续会话沿用
├─ .gitignore                        必须含 .env / dist / target / node_modules
├─ FitAgent_项目设计/                 你上传的设计包，只读参考，不改动
├─ docs/
│  ├─ p1-architecture.md  p1-scope.md  p1-api.md  p1-database.md  p1-plan-import.md
│  ├─ p1-verification.md             阶段 B 的实际测试结果与未验证清单
│  └─ templates/training-plan-template.v1.json
├─ infra/
│  ├─ docker-compose.yml             mysql:8.4 + redis:7，卷与端口
│  ├─ mysql-init/00-create-database.sql
│  └─ .env.example                   变量名样例，无真实密钥
├─ backend/
│  ├─ pom.xml                        父 POM：仅 <modules>app</modules>；统一编码/插件/依赖管理
│  └─ app/
│     ├─ pom.xml
│     └─ src/
│        ├─ main/java/com/hfj/fitagent/
│        │  ├─ FitAgentApplication.java
│        │  ├─ config/               应用与基础设施配置
│        │  ├─ common/               统一响应、分页、异常、错误码、幂等键、JSON 上限
│        │  ├─ security/            令牌校验过滤器、当前用户上下文、接口鉴权、会话存储
│        │  └─ modules/
│        │     ├─ auth/             微信登录、账号创建、会话签发与退出、dev-login
│        │     ├─ profile/          身体档案、基础目标、体重记录
│        │     └─ training/         动作字典、计划版本、计划日、动作条目、JSON 导入
│        ├─ main/resources/
│        │  ├─ application.yml  application-dev.yml  application-prod.yml
│        │  ├─ db/migration/V1..V9__*.sql
│        │  └─ mapper/*.xml          仅复杂 SQL（聚合读写、最新体重）；简单 CRUD 用 MyBatis-Plus
│        └─ test/java/…              单元 + 集成（集成按 requires-docker 分组）
└─ miniapp/
   ├─ package.json  vite.config.ts  tsconfig.json  project.config.json  .env.development
   └─ src/
      ├─ main.ts  App.vue  pages.json  manifest.json  uni.scss
      ├─ pages/{auth,index,mine,plan}/…          登录、首页、档案、目标、体重、计划列表/详情/编辑/导入、动作浏览
      ├─ api/{auth,profile,body,exercise,training}.ts   真实后端请求，按域划分
      ├─ stores/session.ts                      登录态（令牌、userId、devMode）；不复制数据库
      ├─ components/{StateBlock,EmptyState,NumberField,WeekdayPicker,ExercisePicker,PlanDayCard,ImportPreviewList}
      ├─ types/{api,auth,profile,body,exercise,training,plan-import}.ts
      ├─ utils/{request,date,units,uuid,storage,errorMessage}.ts
      └─ static/
```

| 目录 | 放什么 | 不放什么 |
|---|---|---|
| `pages` | 页面级编排：调用 `api`、渲染 5 种状态、路由参数 | 不直接写 `uni.request`；不写业务规则 |
| `api` | 每个后端接口一个函数，签名用 `types` | 不做 UI 提示、不存状态 |
| `stores` | 仅登录态与确实跨页共享的状态（当前会话、当前 ACTIVE 计划摘要） | **不**镜像整套数据库；体重历史、计划详情走接口按需取 |
| `components` | 可复用表单控件、状态块、动作选择器、导入预览列表 | 不放只有一处使用且耦合页面数据的块 |
| `types` | 请求/响应/业务结构 TS 类型，字段与 `p1-api.md` 一一对应 | 不重复定义同一结构 |
| `utils` | 请求封装、日期与单位、UUID、存储键、错误文案映射 | 不放业务判断 |
| `backend/app/config` | 安全链路注册、MyBatis-Plus 拦截器、Jackson 全局规则、Redis、属性绑定 | 不放业务规则 |
| `common` | `ApiResponse`、分页载体、`BizException`+`ErrorCode`、全局异常处理、幂等键工具、体积上限 | **不堆业务逻辑** |
| `security` | 令牌签发/校验/失效、`CurrentUser` 主体、鉴权规则、开发登录守卫 | 不查业务表 |
| `modules/*/…` | 见 §3 | — |
| `infra` | Compose、建库脚本、`.env.example` | 不放真实口令 |
| `docs` | 架构、接口、表设计、导入规范、验收结果、后续扩展 | 不放代码 |

---

## 3. 技术版本

### 3.1 后端锁定组合

| 组件 | 坐标 | 版本 | Java 17 | 证据状态 |
|---|---|---|---|---|
| 语言/运行时 | — | **17**（本机 17.0.10） | — | ✅ 实测 |
| 构建 | Apache Maven | 3.8.8（Boot 要求 ≥3.6.3） | — | ✅ 实测 |
| Spring Boot | `spring-boot-starter-parent` | **4.1.1** | ✅ parent POM 内 `<java.version>17</java.version>`；官方"至少 Java 17，兼容至 Java 26" | ✅ POM 已核 |
| Spring Framework | Boot BOM 管理 | **7.0.9** | ✅ | ✅ |
| Spring Security | Boot BOM 管理 | **7.1.1** | ✅ | ⚠️ 注意迁移要点：Jackson 3 模块改名、鉴权默认值变化、Access API 拆分到 `spring-security-access` |
| MyBatis-Plus | **`com.baomidou:mybatis-plus-spring-boot4-starter`** | **3.5.17** | ✅ | ✅ **官方有 Boot 4 专用 starter**（3.5.13–3.5.17 均在 Central）；其 POM import `spring-boot-dependencies:4.0.1` |
| MyBatis-Plus 分页/防护拦截器 | `com.baomidou:mybatis-plus-jsqlparser` | 与 MP 同版 **3.5.17** | ✅ | ❗**实测新增发现**：3.5.9 起 `PaginationInnerInterceptor` 与 `BlockAttackInnerInterceptor` 已从 `mybatis-plus-extension` 拆出；不引这个包，**分页静默不生效**（不报错、直接返回全量）。已核实 3.5.17 的 extension jar 里只剩 `OptimisticLocker`/`DynamicTableName`/`ReplacePlaceholder` 三个拦截器 |
| MyBatis-Spring | 由 MP starter 传递 | `4.0.0`（MP 所钉）；必要时显式覆盖为 **`4.1.0`**（对应 Framework 7.0.8/Boot 4.1.0，POM 亦声明 Java 17） | ✅ | ⚠️ MP 钉的是 4.0.0 而 Boot 4.1.1 带 Framework 7.0.9，同属 7.0.x，低风险 |
| MyBatis | starter 传递 | `3.5.19` | ✅ | ✅ |
| Flyway | `flyway-core` + **`flyway-mysql`** | **12.4.0**（Boot 管理） | ✅ | ✅ MySQL 支持自 Flyway 10 起移出 core，**`flyway-mysql` 必需**；`flyway-mysql` 在 BOM 中有管理 |
| MySQL 驱动 | `com.mysql:mysql-connector-j` | **9.7.0**（Boot 管理） | ✅ | ⚠️ 旧坐标 `mysql:mysql-connector-java` 已废弃；9.7.0 ↔ MySQL 8.4 官方兼容页未取得原文，按"支持 MySQL 8.0 及以上"口径处理，B-2 实跑确认 |
| Redis 客户端 | `lettuce-core`（`spring-boot-starter-data-redis`） | **7.5.2.RELEASE**（`spring-data-bom:2026.0.1`） | ✅ | ❗**Boot 4 下是 Lettuce 7 而非 6**；对 Redis 3.2.100 的兼容**未核实** → 冒烟测试决定去留 |
| Bean Validation | `hibernate-validator` + `jakarta.validation-api` | **9.1.3.Final** + **3.1.1（Validation 3.1，不是 3.0）** | ✅ | ✅ 仍是 `jakarta.*` 命名空间 |
| JSON | Jackson 2 与 3 **并存** | BOM 同时管理 `com.fasterxml.jackson:jackson-bom:2.21.5` 与 `tools.jackson:jackson-bom:3.1.5`；模块 `spring-boot-jackson` / `spring-boot-jackson2` | ✅ | ✅ Jackson 2 在 Boot 4 下仍可干净引入，不必强行迁移 |
| 编码 | `project.build.sourceEncoding=UTF-8`、`file.encoding=UTF-8`、Flyway `encoding=UTF-8` | — | — | **必须**：本机 Maven 平台编码 GBK |

**四条会导致启动失败或静默错误的硬性注意（必须写进代码评审清单）**

1. **绝不能用 `mybatis-plus-spring-boot3-starter`**：它 import Boot `3.5.9` 并钉 `mybatis-spring:3.0.5`（只支持 Spring 6），在 Boot 4 上触发 `Invalid value type for attribute 'factoryBeanObjectType': java.lang.String`（MP issue #7009，PR #7010 修复）。Boot 4 就用 `mybatis-plus-spring-boot4-starter`。
2. **JSON 字段必须显式用 `Jackson3TypeHandler`**：`mybatis-plus-extension:3.5.17` 同时声明 `jackson-databind 2.20.1` 与 `tools.jackson 3.0.3`，两者都是 `<optional>true</optional>`。用面向 Jackson 2 的 `JacksonTypeHandler` 会在 Boot 4 运行时 `NoClassDefFoundError: com/fasterxml/jackson/core/JsonProcessingException`（MP issue #7022）。本轮受影响的列：`user_profile.equipment`/`diet_preferences`/`training_constraints`、`exercise_reference.muscle_groups`/`equipment`、`training_plan.goal_snapshot`。
3. **不要开 `type-handlers-package` 扫描**：`Jackson3TypeHandler` 没有无参构造，包扫描会 `NoSuchMethodException`（同 issue #7022 结论）。逐字段 `@TableField(typeHandler = Jackson3TypeHandler.class)` 显式注册。
4. **不引 `mybatis-plus-jsqlparser` 就没有分页**：`PaginationInnerInterceptor` / `BlockAttackInnerInterceptor` 已不在 extension 里。这个坑不报错——`Page` 参数被忽略、直接返回全量结果，属于**静默错误**，比崩溃更危险。

### 3.2 小程序锁定组合（含实测坑位）

| 组件 | 版本 | 说明 |
|---|---|---|
| 脚手架 | `npx degit dcloudio/uni-preset-vue#vite-ts miniapp` | 官方 CLI 快速上手页给出的 Vue3+TS 预设 |
| `@dcloudio/uni-app` 等 | **预设里的精确快照版**（`3.0.0-…` 形式） | ❗npm 的 `latest` 标签指向 **Vue 2 线**（`2.0.2-…`），Vue 3 在 `vue3` 标签下；必须按预设锁精确版，不能用 `latest`/`^3` 语义范围 |
| `@dcloudio/vite-plugin-uni` | 与 `uni-app` **同一快照号** | ❗其 npm `latest` 是 2021 年 alpha，绝不可用 |
| `vite` | 预设 peer 精确锁定（5.2.8） | 不擅自升级：uni-app 对 vite 版本是精确 peer 依赖 |
| `vue` | `^3.4.21`（预设值） | — |
| `typescript` / `vue-tsc` | 预设值（`^4.9.4` / `^1.x`） | 与 Pinia 4 冲突，故不升级 |
| **Pinia** | **`^2.3.1`** | ❗Pinia 4.0.3 的 peer 要求 `vue ^3.5.11` + `typescript >=5.6.0`，与预设冲突；锁 2.x |
| `@dcloudio/types` | 与 uni-app 快照 peer 一致的精确版 | 提供 `uni.*`/`wx.*` 声明 |
| Node | v24.18.0（本机） | ⚠️ 厂商未官方声明 Node 24；构建异常时先用 Node LTS 复现再定性 |
| 构建目标 | `npm run dev:mp-weixin` / `build:mp-weixin` → `dist/{dev,build}/mp-weixin` | ❗**H5 构建通过 ≠ 微信可运行**，验收以微信开发者工具为准（A21） |
| 微信凭据 | AppID 必需；AppSecret **只在后端** | 开发者工具可用测试号；`project.config.json` 的 `appid` 必填 |

### 3.3 B-0 可行性门禁（阶段 B 第一步，先于任何功能代码）

**已核实到 what 程度**：Boot 4 专用 starter 存在、Jackson 2/3 双通道存在、`mybatis-spring` 4.x 面向 Spring 7 且声明 Java 17、BOM 版本号全部拿到。**仍未核实的关键一点**：`mybatis-plus-spring-boot-autoconfigure`（boot3 与 boot4 starter 共用）的 POM 是针对 `spring-boot-autoconfigure:2.7.18` 编译的，它在 Boot 4.1.1 上的**运行时行为没有任何公开证据** —— 社区反馈（#6929/#6955/#6966/#6970/#6972/#7002/#7050/#7064）全部止步于 Boot 4.0.x，**没有人验证过 MP 3.5.17 + Boot 4.1.1**；未关闭的 #7168（"springboot4 兼容问题"）经判定是提问者用错了 artifact，不算缺陷证据。所以这一步仍然要做，只是**成功概率已从"未知"提升为"大概率可行"**。

范围严格限定为"证明组合可跑"，不写业务：父 POM + app 模块 + `V1` 一张表 + 一个带 JSON 列的 CRUD + Flyway 空跑 + Redis 连通 + 一个 `@SpringBootTest` 上下文加载。

检查点：① `mybatis-plus-spring-boot4-starter:3.5.17` 在 Boot 4.1.1 下能否装配 `SqlSessionFactory` 并完成 insert/select；② `user_profile.equipment` 用 `Jackson3TypeHandler` 显式注册后 JSON 列读写是否正常（含 `typeHandler` 与 `autoResultMap` 配合）；③ `OptimisticLockerInnerInterceptor`（`lock_version`）与分页拦截器是否可用；④ `flyway-mysql:12.4.0` 在 MySQL 8.4 上跑通含 CHECK + STORED 生成列的 DDL；⑤ Security 7 下自定义 `OncePerRequestFilter`（Framework 7.0.9 中该类**存在且未废弃**，也不存在所谓 `PerRequestFilter`）能否正常装配令牌校验链；⑥ Lettuce 7.5.2 对 Redis 3.2.100 的冒烟结果；⑦ `mysql-connector-j 9.7.0` 的 UTC 时区参数组合是否生效。

**失败阶梯**（按代价从低到高，动任何一步前先告知你）：

| 级 | 动作 | 触发条件 |
|---|---|---|
| a | 显式覆盖 `org.mybatis:mybatis-spring:4.1.0` + `mybatis:3.5.19` | 出现 `NoSuchMethodError`/装配类找不到 |
| b | Boot 降到 **4.0.8**（MP 的 boot4 starter 官方 import 的是 4.0.1 BOM，4.0.x 有社区验证） | a 无效，且失败发生在 MP 自动装配层 |
| c | Boot 降到 **3.5.16** + `mybatis-plus-spring-boot3-starter`（已核实该组合互相引用；代价：3.5 的 OSS 支持已于 2026-06-30 结束） | b 仍不可用，但业务代码尚未铺开 |
| d | 放弃 MyBatis-Plus，改用 `mybatis-spring-boot-starter` 或 Spring Data JDBC | 前三级都失败 —— **这会改变提示词指定的技术栈，必须你批准** |

**B-0 实测结果（2026-10-13，阶段 B 执行后回填）**

| 检查点 | 结果 |
|---|---|
| Boot 4.1.1 + `mybatis-plus-spring-boot4-starter:3.5.17` 依赖解析 | ✅ `mvn dependency:tree` 通过；`mybatis-spring` 已按阶梯 a 覆盖为 **4.1.0**，`mybatis` 3.5.19 |
| `mvn compile`（含实体、Mapper、安全链、Jackson 配置） | ✅ BUILD SUCCESS |
| `Jackson3TypeHandler` 是否存在 | ✅ 存在于 `mybatis-plus-extension:3.5.17`；`javap` 确认构造器只有 `(Class<?>)` 与 `(Class<?>,Field)`，**无无参构造**（因此包扫描必然失败，已在注意 3 固化） |
| classpath 上是否有 Jackson 2 databind | ❌ 没有，只有 `tools.jackson:3.1.5` → 注意 2 的风险被实测证实，实体 JSON 列一律 `Jackson3TypeHandler` |
| 分页/防全表拦截器是否在 extension 里 | ❌ 不在 → 已补 `mybatis-plus-jsqlparser:3.5.17`（注意 4） |
| Boot 4.1.1 BOM 版本 | ✅ Flyway 12.4.0 + flyway-mysql、connector-j 9.7.0、Lettuce 7.5.2、Security 7.1.1、Hibernate Validator 9.1.3、jakarta.validation-api **3.1.1** |
| `OncePerRequestFilter` 在 Framework 7.0.9 | ✅ 存在且未废弃；也不存在所谓 `PerRequestFilter`，令牌过滤器按原方案实现 |
| Spring 上下文启动 + 真实 SQL | ⏳ **未验证**：需要 MySQL 8.4。你的库在服务器上，待连接参数给到后执行；届时补 `docs/p1-verification.md` |

---

## 4. 后端模块边界与调用方向

```
                        ┌─────────── common ───────────┐
                        │  ApiResponse / ErrorCode /    │
                        │  BizException / Page / 幂等    │
                        └──────────────▲────────────────┘
   miniapp ──HTTP──► security (Bearer) ┤
                        │ CurrentUser │
        ┌───────────────┼─────────────┴───────────────┐
        │               │                             │
   modules/auth    modules/profile             modules/training
        │               │  ▲ GoalSnapshotReader ◄─────│  (training 单向依赖 profile 的端口)
        │               │  │  WeightLatestReader ◄────┘
        └───────────────┴──┴──► 各自 entity/mapper（不跨模块访问 Mapper）
```

规则：

1. **每个模块内部**分层：`controller`（仅接收/返回与调 Service）→ `service`（业务规则 + 事务）→ `mapper`（持久化）；`dto` 入参、`vo` 出参、`entity` 只映射表。
2. **数据库 Entity 不作为接口响应**。VO 独立定义，避免字段泄漏（如 `wechat_open_id` 永不出现在任何 VO）。
3. **不跨模块调用 Mapper**。跨模块协作只经 Service 或显式端口（`GoalSnapshotReader`、`WeightLatestReader` 这类只读接口由 profile 实现、training 消费）。
4. **依赖方向单向、无环**：`auth` 只负责身份，**不反向依赖** profile/training（首次登录不强制建档案）；`profile` 不依赖 `training`；`training` 只依赖 `profile` 的只读端口。
5. `common` 与 `security` 不依赖任何业务模块。
6. 时间与时区：`security`/`common` 提供 `AppClock`（可注入 `ZoneId`），业务代码不裸用 `LocalDateTime.now()` 决定业务日期——业务日期一律取认证主体的时区。

### 4.1 各模块内容

| 模块 | 类（示意，阶段 B 实际命名） | 表 |
|---|---|---|
| `auth` | `AuthController`、`WechatLoginService`、`WechatCode2SessionClient`、`AccountProvisioningService`、`DevLoginController`（`@Profile("dev")`）、`AccountEntity/Mapper`、`WechatLoginDto`、`LoginResultVo` | `user_account` |
| `profile` | `ProfileController`、`GoalController`、`BodyRecordController`、`UserProfileService`、`UserGoalService`、`BodyMeasurementService`、`GoalSnapshotReaderImpl`、各自 dto/vo/entity/mapper | `user_profile`、`user_goal`、`body_measurement` |
| `training` | `ExerciseController`、`TrainingPlanController`、`PlanImportController`、`ExerciseReferenceService`、`TrainingPlanService`、`PlanActivationService`、`PlanImportService`、`PlanJsonValidator`、`PlanAggregateRepository`、各自 dto/vo/entity/mapper | `exercise_reference`、`training_plan`、`training_plan_day`、`training_plan_item` |
| `security` | `BearerTokenAuthenticationFilter`、`SessionTokenService`(Redis)、`CurrentUser`、`SecurityConfig`、`AuthExceptionHandler` | — |
| `config` | `MybatisPlusConfig`、`JacksonConfig`、`RedisConfig`、`WechatProperties`、`FitAgentProperties`、`DevLoginGuard`（prod 探测即启动失败） | — |

---

## 5. 认证与授权架构

```
小程序 wx.login → code ─────────────────────────────────────────┐
                                                               ▼
POST /api/auth/wechat-login { code }              GET api.weixin.qq.com/sns/jscode2session
   │                                                   (AppID + AppSecret 均在后端)
   ▼                                                    │
WechatLoginService ── openid/unionid ◄──────────────────┘
   ├─ AccountProvisioningService.getOrCreate(appId, openid)   ← REQUIRES_NEW，撞唯一索引则重查
   ├─ SessionTokenService.issue(userId) → SecureRandom 256bit，Redis: fa:sess:{sha256} = userId|expiresAt, TTL 7d
   └─ 返回 { token, expiresAt, userId, isNewAccount, devMode }

后续请求：BearerTokenAuthenticationFilter
   → sha256(token) → Redis GET → 命中且未过期 → 装配 Authentication + CurrentUser(userId)
   → 剩余 TTL < 1d 滑动续期 → 未命中 → 401 SESSION_EXPIRED（不回落成"匿名放行"）
退出：DEL key → 该令牌在服务端立即失效（验收 A4）
```

要点：

- **不使用 JWT 自造签名**，也不自造加密：不透明随机令牌 + SHA-256 摘要存储，走 JDK `SecureRandom`/`MessageDigest` 与 Spring Security 现成机制。
- 过滤器基类：`OncePerRequestFilter` 在 Framework 7.0.9 中**存在且未废弃**，直接继承它实现 `BearerTokenAuthenticationFilter`。备选方案是接入 `spring-boot-starter-oauth2-resource-server` 的 `BearerTokenAuthenticationFilter` + 自定义 `OpaqueTokenIntrospector`，但那是为 OAuth2 语义设计的，本轮不需要，多一层抽象反而更难验证。Security 7 迁移需额外注意 Jackson 3 模块改名（`SecurityJackson2Modules` → `SecurityJacksonModules`）、鉴权默认值变化、Access API 拆分到 `spring-security-access`。
- **不使用 Spring Session**：那是 Cookie/`HttpSession` 模型，与 Bearer 令牌和小程序（无浏览器 Cookie 语义）不匹配。会话真相就是 `fa:sess:*`。
- 鉴权边界：`SecurityConfig` 显式列出公开路径，其余 `anyRequest().authenticated()`。**禁止**用"全局放行 + 注解逐个补"的方式（提示词明确不允许以全局放行替代鉴权）。
- 身份来源单一：`CurrentUser.userId()` 只从 `SecurityContext` 取；Service 层签名不接受 `userId` 参数，从根上杜绝"前端传 userId 决定身份"。
- `AppSecret` 只来自环境变量/本机密钥配置，不入库、不入 Git、不写进日志；`jscode2session` 的 URL 与响应不进客户端（只回传数字错误码）。

---

## 6. 横切能力

| 能力 | 做法 |
|---|---|
| 统一响应 | `ApiResponse<T>{code,message,data,traceId,serverTime}`；`@RestControllerAdvice` 收口 |
| 异常 | `BizException(ErrorCode, httpStatus, payload)`；不拿异常做常规流程控制；`GlobalExceptionHandler` 分类映射（校验/状态/乐观锁/导入/微信/兜底），兜底只回 `traceId` |
| 校验 | Jakarta Bean Validation 注解在 DTO + 分组；复杂跨字段规则（`reps_max>=reps_min`、类型×字段矩阵）在专用 Validator；错误结构 `{field,code,message}` |
| 分页/排序 | `PageQuery{page,size≤100,sort}` + 每接口排序白名单，非法字段直接拒绝；不给 SQL 拼接入口 |
| 时间 | DB 存 UTC `DATETIME(3)`；`mysql-connector-j` 用 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true`；Jackson 序列化 `Instant` 为带 `Z` 的 ISO-8601 |
| 大整数 | `Long`/`BigInteger` 全局 `ToStringSerializer` |
| 修订号 | 任一业务写操作在**同事务**内 `UPDATE user_account SET data_revision = data_revision + 1` |
| 幂等 | `IdempotencyService`（Redis，TTL 24h），仅用于导入确认 |
| 日志 | 结构化，含 `traceId`/`userId`；**屏蔽** `Authorization` 头、令牌、AppSecret；不打印请求体原文（导入 JSON 只记字节数与错误数） |
| 体积防护 | 导入 256 KiB、普通 64 KiB；`days≤7`、`items/day≤30`、`总 items≤120`、`notes` 长度上限 |
| 安全编码 | 路径/命令/SQL 一律参数化；无动态拼接；不接收客户端文件路径；无 shell 字符串拼接 |

---

## 7. 为什么这套架构适合本阶段，以及后续怎么接自研 Agent

**适合本阶段的理由**

1. **单应用按包分层**符合"本轮先一个 Spring Boot 应用"的要求；模块边界靠包 + 依赖方向约束，不靠 Maven 多模块，避免过早拆分带来的构建复杂度。父 POM 只含 `app` 一个模块，未来抽 `agent-core` 时是"把包提升为模块"，不是重写调用关系。
2. **8 张表 + 版本化迁移**覆盖了本轮全部真实需求，同时保留设计包的三样关键不变式（ACTIVE 唯一、用户隔离、版本+乐观锁），所以 P2/P3 不需要回头改数据结构语义。
3. **不透明令牌 + Redis 会话**是本轮唯一需要的服务端状态，实现面小、可验证（退出即失效），且不需要引入 OAuth2/SSO 这类与小程序场景不匹配的重量级组件。
4. **聚合式计划写入**（一次请求=一个聚合事务）与"不能留下半份计划"的验收项 A10 直接对应；细粒度 REST 子资源接口在 P1 反而是风险来源。
5. **导入的 preview/confirm 两段式 + 完整重校验**满足"校验、预览、确认后保存为草稿"，并且 preview 无副作用，天然安全可重复。

**后续接入自研 Agent 的边界（本轮不实现、不建空类）**

| 未来组件 | 落点 | 与本轮的衔接方式 |
|---|---|---|
| `backend/agent-core` | 独立 Maven 模块：`ModelClient`/`ModelRouter`/`ToolRegistry`/`ToolExecutor`/`AgentContext`/`AgentRunner`/`BudgetGate`/`TraceRecorder`/`CancellationToken`（设计包 §6.1） | 只通过**接口 + 工具契约**访问业务模块，不反向依赖 controller；本轮的 Service 就是它将来的工具实现层 |
| `create_plan_draft` / `revise_plan_draft` 工具 | 直接复用本轮 `TrainingPlanService` 与 `PlanJsonValidator` 的同一套校验 | 本轮导入用的 JSON 结构即 Agent 计划输出的目标结构；"无法匹配动作字典时提示用户、不自动塞入字典"这条规则在 P2 同样成立 |
| `agent_run` / `processing_job` 溯源列 | 用新增迁移加回 `training_plan.source_run_id` / `source_job_id` 及其复合外键（SQL 见 `p1-database.md` §4.2） | 本轮已刻意保留 `source` 枚举含 `AI`，加回时**不需要改枚举、不需要改已有行** |
| `model-credentials` / `billing` 模块 | 新包 `modules/credential`、`modules/billing`；AES-GCM 加密与 HMAC 指纹按设计包 §7.1，用 JDK 现成实现 + 独立主密钥 | 与本轮 `security` 包同层，互不依赖 |
| `media-worker`(Python) / Qdrant / OSS | 独立服务，Java 侧只加 `modules/media`、`modules/jobs`、`modules/knowledge` | 本轮无涉及；`infra` Compose 预留可扩展位置但不写死 |
| 服务端身份传递 | `AgentContext` 需要不可变 `userId` + `dataRevision` | 本轮的 `CurrentUser` 与 `data_revision` 递增机制正是它的输入，无需改造鉴权模型 |

`media-worker` 边界：Java 创建受控内部任务并把临时任务目录/受限任务标识交给 Python；内部服务只绑本机/容器网络、需内部令牌；Python 不读模型 Key、不处理登录（设计包 §5）。本轮不创建该目录。

---

## 8. 配置与运行

| Profile | 内容 |
|---|---|
| `application.yml` | 公共：Jackson、MyBatis-Plus、Flyway、日志、`fitagent.*` 默认值、`server.port=8080` |
| `application-dev.yml` | `jdbc.url` 指向本机 MySQL 8.4（端口/库名来自环境变量）；Redis 指向 127.0.0.1:6379；`fitagent.auth.dev-login.enabled=true`；`logging.level…=DEBUG` |
| `application-prod.yml` | **不定义** dev-login；`DevLoginGuard` 探测到 dev-login bean 即启动失败；DDL 权限最小化、`flyway.clean-disabled=true` |

环境变量（`.env.example` 只给变量名）：`FITAGENT_MYSQL_URL/USERNAME/PASSWORD`、`FITAGENT_REDIS_HOST/PORT/PASSWORD`、`FITAGENT_WECHAT_APPID/SECRET`、`FITAGENT_AUTH_DEVLOGIN`。

> 密钥分离：不写入真实 AppSecret/API Key，不提交 `.env`（`.gitignore` 强制），不部署到外部环境。演示数据一律真实走接口写入，且写入 dev 库，不与生产配置混用。

本机启动路径（阶段 B 交付 README 会写全）：MySQL 8.4 + Redis 由你启动（Docker Compose 或本机服务）→ `mvn -pl app spring-boot:run` → `cd miniapp && npm i && npm run dev:mp-weixin` → 微信开发者工具导入 `dist/dev/mp-weixin`。

真机注意：微信服务器域名不允许 IP/`localhost`，`http://localhost` 在真机上必然失败；开发期用开发者工具「不校验合法域名」（`project.config.json` 的 `setting.urlCheck=false`），真机需电脑局域网 IP + 调试模式，正式发布才需要合法 HTTPS 域名。

---

## 9. 新增功能应该放哪里

| 你要加的东西 | 放哪里 |
|---|---|
| 新接口 | 对应 `modules/<域>/controller` + `service` + `dto`/`vo`；同步 `docs/p1-api.md`；前端 `src/api/<域>.ts` |
| 新表/字段 | **只加新的 Flyway 迁移文件**（`V10__…`），不修改已发布的迁移脚本 |
| 新业务域（如睡眠、恢复） | 新包 `modules/<新域>`；若需被别的模块读，暴露只读端口，不共享 Mapper |
| 动作字典新增条目 | `V<n>__seed_exercise_xxx.sql`；来源与说明由你审核后再填 `source_url`/`instructions` |
| AI 生成计划 | `backend/agent-core` 新模块 + `modules/training` 的 `create_plan_draft` 工具适配，**不改**已有状态机语义 |
| 新页面 | `miniapp/src/pages/<域>/` + `pages.json` + TabBar/路由；若需要跨页状态才加 `stores` |
| 文件/媒体解析 | 新增 `modules/media` + `modules/jobs`，并引入 `processing_job` 表与溯源列迁移 |
