# AGENTS.md — FitAgent 项目约定

给后续任何参与本仓库的人（含 AI 会话）。与 `docs/p1-*.md` 冲突时，以 `docs/` 为准并修正本文件。

## 语言与运行时

- **Java 17**（本机 `D:\software\Java\jdk17`，17.0.10）。设计文档里出现过 Java 21，已明确作废：**一律按 17**。
- Spring Boot **4.1.1** / Spring Framework 7.0.9 / Spring Security 7.1.1。
- MyBatis-Plus **3.5.17**，且必须用 `mybatis-plus-spring-boot4-starter`。
- 数据库 **MySQL 8.4**（`utf8mb4_0900_ai_ci` + 强制生效的 CHECK + STORED 生成列都依赖 8.x；本机在用的 5.7 不满足，禁止拿它验证迁移）。

## 构建与编码（Windows 特有，踩过）

- 本机 Maven 平台编码是 **GBK**。任何新增模块都必须显式 `project.build.sourceEncoding=UTF-8`、`maven-compiler-plugin` 的 `<encoding>UTF-8</encoding>`、`spring.flyway.encoding=UTF-8`，否则中文注释与迁移脚本静默损坏。
- 想让 javac 报错变成英文便于检索：`MAVEN_OPTS="-Duser.language=en -Duser.country=US"`。
- `mvn -B` 输出可能含 GBK 字节，用 `python -c "...decode('gbk')"` 读，别指望 grep。

## 多构造器的 Spring bean 必须标 `@Autowired`

真实启动（2026-10-14）踩到两类**测试全绿也发现不了**的错误，因为只有起上下文才暴露：

1. 组件有 2 个构造器却没标 `@Autowired` → Spring 回退找无参构造器：找不到就是启动期 `No default constructor found`；**找到了更糟——带配置的那个构造器被静默跳过**（`AppClock()` 用系统时钟、`FakeModelClient()` 用 `disabled()` 而不是 `fitagent.agent.fake-model.*`）。生产入口是无参构造器时也要显式标注。守卫：`BeanConstructorAmbiguityTest`（组件扫描，不需要数据库）。
2. 注入 `RequestMappingHandlerMapping` 要 `@Qualifier("requestMappingHandlerMapping")`：开了 actuator 后容器里有**两个**该类型 bean（`controllerEndpointHandlerMapping` 也是），不指名就 `expected single matching bean but found 2`。

同类原则：启动期的补偿扫描/自检必须**尽力而为**（`RunWorker.afterSingletonsInstantiated` 走 `scanQuietly()`），库还没就绪时不能把整个应用带崩。

## 配置键必须被绑一次

`@ConfigurationProperties` 对**写错的键名是静默忽略**的：应用照样启动，只是永远在用代码默认值。所以新增/修改 `fitagent.*` 配置键时，同步在 `backend/app/src/test/java/com/hfj/fitagent/AgentConfigBindingTest.java` 里把真实的 `application.yml` 再绑一次并断言取值。

## 三条 MyBatis-Plus / Jackson 硬规则

1. **不能混用 starter**：Boot 4 上出现 `Invalid value type for attribute 'factoryBeanObjectType': java.lang.String` 几乎都是误用了 `mybatis-plus-spring-boot3-starter`。
2. **JSON 列只能用 `Jackson3TypeHandler`** 并在实体类上开 `@TableName(autoResultMap = true)`；Boot 4 的 classpath 上没有 Jackson 2 databind，用 `JacksonTypeHandler` 会 `NoClassDefFoundError`。
3. **禁止配置 `mybatis-plus.type-handlers-package`**：`Jackson3TypeHandler` 无无参构造，包扫描会 `NoSuchMethodException`。逐个字段声明。
4. 附带：MP 3.5.9+ 把 `PaginationInnerInterceptor` / `BlockAttackInnerInterceptor` 拆到了 `mybatis-plus-jsqlparser`，不引这个包分页会静默失效。

## 注释里不要写条件编译指令的原文

`pages/plan/import.vue` 有两处**注释**复述了 `// #ifdef MP-WEIXIN`，uni-app 预处理器按纯文本扫描，把注释里的孤立指令当成真指令去配对，构建日志直接报
`条件编译失败: #ifdef/#ifndef 缺少配对的 #endif`，整个文件的分支裁剪按"配对不完整"处理——被裁掉的可能是微信选文件那整段（`wx.chooseMessageFile` + `readFile`）。
`vue-tsc` 与 `check:theme` 对此**全绿**，只有构建日志说话（2026-10-16 修，实测告警清零）。
所以：条件编译指令只写在真正要裁剪的代码里，注释里要提就写"微信端条件编译分支"，不要写出指令原文。

## 架构边界

- 一个 Spring Boot 应用，按 `modules/{auth,profile,training}` 分包。模块内 `controller → service → mapper`，`entity` 永不出现在响应里。
- **不跨模块调 Mapper**。跨模块只走接口，目前唯一端口是 `modules/profile/api/ProfileContextReader`。`auth` 不依赖任何人，也不被业务模块反向依赖身份判定。
- 唯一豁免：业务写操作在同一事务里调用 `UserAccountMapper.incrementDataRevision(userId)`（数据修订号）。必须放在可单独审查的小组件里。Agent 侧同手法新增两处，各自只有一个文件：`modules/coach/support/CoachDataRevisionReader`（只读 `data_revision` / `timezone`）与 `modules/coach/gateway/BudgetedModelCallGateway`（`ModelCallGateway` 的实现落在 coach 而不是 billing，因为它要写 `model_call` 台账）。
- `common` / `security` 不依赖业务模块。

## 业务不变式（改代码前必读）

- `active_user_id` 是数据库派生的 STORED 生成列：**实体里不要声明它**，任何 INSERT/UPDATE 都不能带它。"每用户最多一个 ACTIVE" 靠 `UNIQUE(active_user_id)` + 激活事务（先归档后激活）。
- 身份只来自 `AuthenticatedUsers.require()`；Service 签名里不出现 `userId` 请求参数。
- 度量一律 `BigDecimal`，不用 double/float；可空列 `updateStrategy = ALWAYS`（否则用户"清空"某字段清不掉）。
- 微信身份列（`wechat_app_id`/`wechat_open_id`/`wechat_union_id`）是 `utf8mb4_bin`，**只做 trim，绝不折叠大小写**。UUID 反过来：服务端规范化小写 + 列保持 CI。
- 时间存 UTC，业务日期由服务端按用户时区推导，客户端不传。BigInt 在 JSON 里是字符串。
- 校验只做单位/正数/长度/枚举/日期格式。**不引入任何未经说明的医学阈值**。
- 计划是聚合根：头+日+条目一次事务写入，任一失败全量回滚。删除子行的顺序是先 item 后 day（外键 RESTRICT）。
  ✅ 阶段 2 的解法（2026-10-16 已核实代码，不是计划）：**不改成增量更新，而是禁止引用可被删的版本**。
  就地编辑的删旧插新只作用于 DRAFT（`TrainingPlanService:136` 的 `requireStatus(..., DRAFT)` 挡住生效/归档版本，
  生效版本的修改走 revise/copy 出**新行新 id**），而 `WorkoutService.resolveReference` 明确拒绝 DRAFT 计划作为参考，
  所以 `workout_session`/`workout_session_entry` 永远指不到会被物理删除的行上。
  ⚠ 任何人若要放开「就地编辑 ACTIVE」，必须先同时放开这两处守卫，届时才真的需要增量更新 —— 改之前先读这条。

## DeepSeek 接入硬规则（协议已核查，别靠记忆）

核查全文见 `docs/agent-v1-protocol.md`。踩过的坑：

1. **模型名只有 `deepseek-flash` 与 `deepseek-v4-pro`**。`deepseek-chat`/`deepseek-reasoner` 已于 2026-07-24 停用；旧设计包 §4 里的 `deepseek-v4-pro` 是文本模型、`deepseek-flash` 支持图片 —— 这些是对的，别被"更流行的名字"带偏。
2. **Base URL `https://api.deepseek.com`，文档路径不带 `/v1`**（`POST /chat/completions`、`GET /user/balance`）。无凭据时 `/v1/...` 和不带前缀都返回同一个 401，**不能**据此证明前缀可用；按文档走并把地址做成配置。
3. **思考模式默认是 enabled**，且**带 `tools` 时必须完整回传 `reasoning_content`，否则 400**。本项目不实现回传 → 每个请求必须显式 `thinking.type=disabled`，配置层禁止改成 enabled。也因此 `tool_choice` 只能用 `auto`/`none`，`required` 与指定工具名会 400。
4. **非流式路径不得发送 `stream_options`**（`stream` 非 true 时它是 400）；`parallel_tool_calls` **不是** Chat Completions 的参数，不要发。
5. `finish_reason` 只有 `stop` 与 `tool_calls` 算可用。`length`/`content_filter`/`insufficient_system_resource`/`aborted` 一律按不完整处理，且**不得执行被截断的 arguments**。
6. `usage` 字段名是 `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens` / `completion_tokens` / `completion_tokens_details.reasoning_tokens`。`reasoning_tokens` 是**细分项不是加项**（用 create-response 示例的 22+29=51 算式证实）。缺字段填 null 不填 0。
7. **峰谷分时计价是官方真实机制**（峰=北京时段 9–12/14–18 工作日非法定节假日，谷=峰的 50%）。所以 `model_price` 有 `rate_tier`；但节假日日历无机器可读来源 → **不入库不猜**，预占一律按 PEAK 保守档，档位不确定就标"保守核算"。
8. 余额接口 `GET /user/balance` 返回 `is_available` + `balance_infos[]`，**三个金额都是字符串** → BigDecimal 绑定。`is_expired` 在当前文档中**不存在**（旧设计包写错了），别实现。余额是账户级快照，不是单把 Key 的额度，失败时显示上次快照+过期标记或"暂无法获取"，**不显示 0**。
9. 429 是**并发数**超限（flash 2500 / v4-pro 500，账户级），402 是余额不足，两者都不自动重发。非流式响应服务端会持续返回空行保活、最坏 10 分钟才关连接 → **HTTP 超时必须受 run 剩余时间约束**，否则 Worker 被拖死。

## 阶段边界

**P1 基础功能（已完成）**：微信登录自动注册、档案、基础目标、体重记录、每周训练计划、JSON 模板导入。8 张表、V1–V9 迁移。

**Agent 第一版（代码与装配完成，2026-10-14）**：单 Agent + 原生工具调用 + 有上限的执行循环；BYOK 凭据加密、余额快照、版本化价格、预算预占与幂等结算；会话/run 持久化、取消与重启恢复；工具只注册 `get_profile`、`get_training_plan`、`get_weight_trend` 三个有真实业务支撑的。本机证据：`mvn test` 352 用例 0 失败、`vue-tsc` 0 错误、`build:mp-weixin` 通过。仍未做且不能声称已做：真实 DeepSeek 联调、真库并发/约束验证、prod 启动实证（`docs/agent-v1-acceptance.md` §3 逐条标注）。范围与取舍见 `docs/agent-v1-design.md`；能力缺口见 `docs/agent-v1-baseline.md`；自研清单见 `docs/agent-v1-selfbuilt.md`。

**阶段 2（训练执行记录与基础统计，代码与装配完成，2026-10-16）**：`workout_session` / `workout_session_entry` / `workout_session_set` 三表（V23–V25）、开始→逐组记录→草稿续填→确认结束→当天对比与周基础统计，以及 `get_training_progress`、`get_weekly_training_summary` 两个 Agent 工具（`CoachToolNames.ALL` 现为 5 个，白名单由它单点定义，**不要再在任何地方写死工具数量**）。本机证据：`mvn -B -pl app test` 422 用例 0 失败、`vue-tsc` 0 错误、`check:theme` 通过、`build:mp-weixin` 通过。仍未验证：真库约束与并发、真机 UI、真实 DeepSeek（`docs/stage2-acceptance.md` §4 逐条给复现方式）。⚠ 迁移序号说明：`docs/agent-v1-*` 里预留为 V23 的**模型与价格种子迁移现在轮到 V26**。设计见 `docs/stage2-design.md`，验收见 `docs/stage2-acceptance.md`。三条硬口径：**草稿不计正式统计**、**`is_completed` 只由用户给出**、**统计是确定性计算，不引入任何训练效果分或医学结论**。

**仍然不做**：饮食识别、能耗公式与医学阈值、RAG/向量库、文件上传、Python 服务、教学视频、MCP、多 Agent、自动修改或激活训练计划、**AI 生成/修改训练计划**（要等预算与真实费用有证据之后，且只能出草稿）、为纯统计预建日报/周报持久表。
⚠ 历史文档（含 `docs/agent-v1-*.md` 与本轮之前的报告）里"训练执行记录与完成度统计不做"的说法已被本次授权取代，读到时应以本节为准。

不要在页面上放占位按钮或假成功提示，也不要为未来功能建空类。缺能力就从注册表/入口里拿掉，不要在文档与 UI 里声称已支持。

## 安全

- 配置与密钥分离：`.env` 不提交，AppSecret/API Key 不入库、不进 Git、不打日志。
- 不部署到外部环境；不动本机在用的 MySQL 5.7 / Redis 3.2 里的数据。
- 迁移一律向前：不修改已发布的 Flyway 脚本，不改历史，不加 `DROP`/`TRUNCATE`。
