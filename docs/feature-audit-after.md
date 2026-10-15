# FitAgent 功能核查结果（修复后）

日期：2026-10-14。对应 `docs/feature-audit-before.md` 的逐项结论；本文件只写"改了什么、在哪改的、怎么验证的、仍未验证什么"。

## 1. 本轮验证执行记录

| 命令 | 结果 |
|---|---|
| `cd backend && MAVEN_OPTS="-Duser.language=en -Duser.country=US" mvn -B -pl app -am test` | ✅ **Tests run: 357, Failures: 0, Errors: 0, Skipped: 0**，`MVN=0` |
| `cd miniapp && npx vue-tsc --noEmit` | ✅ 0 错误 |
| `cd miniapp && npm run build:mp-weixin` | ✅ `DONE Build complete.`，`BUILD=0` |
| `cd miniapp && npm run check:theme` | ✅ token 一致性、`pages.json` 配色、零硬编码色值、填充色不作前景、19 组文字 + 4 组图形对比度全部通过 |

## 2. P0 修复

| 问题 | 代码位置 | 修法 | 验证 |
|---|---|---|---|
| 计划编辑遇版本冲突时**静默覆盖用户未提交内容** | `miniapp/src/pages/plan/edit.vue`（`submit` catch 分支 + 新增 `askReloadAfterConflict`） | 不再自动 `loadPlan()`；改为弹窗让用户选「放弃并重读 / 保留我的编辑」，默认不丢用户输入 | 类型检查 + 微信构建通过；无自动化用例（纯 UI 决策路径，见 §6） |
| 连点「另存为新草稿」生成多份草稿 | `edit.vue`（新增 `revising` 标志 + 按钮禁用与文案） | 忙碌期间拒绝再次调用并显示"生成中…" | 同上 |

## 3. P1 修复

| 问题 | 代码位置 | 修法 |
|---|---|---|
| 会话失效被静默送回登录页、没有任何解释 | `pages/auth/login.vue`（消费 `storage.ts:takeSessionExpiredNotice()`） | 探活前先取出封装层存的失效原因并显示；本地探活失败的原因只在没有存储提示时才覆盖 |
| 排序控件改了标签但列表不重取 | `pages/plan/list.vue:onSortChange` | 值真变化才 `reload()`；同值不重复请求 |
| 慢回的旧分页请求把过期数据 concat 回来 | `pages/plan/list.vue`（请求代次 `epoch`） | 只有最新一次请求能写状态与收尾加载态；`reset` 不再被"正在加载"丢弃 |
| 计划动作失败（409 等）在零计划日草稿上完全看不见 | `pages/plan/detail.vue` | 新增 `actionFailure`，在计划日区域**之外**渲染错误横幅（含错误码与 traceId） |
| 动作期间按钮无反馈，连点被静默吞 | `pages/plan/detail.vue` 动作按钮 | `acting` 期间加禁用类与"…"文案（`runAction` 原本已有守卫） |
| 器械筛选是死控件（`inputValue` 读 number 恒得 0） | `pages/plan/exercises.vue:onEquipmentChange` | 改用 `detailValue`，与 `ExercisePicker.vue` 的正确写法一致 |
| 分页缺 `total` 时显示"共 0 条" | `utils/request.ts:pageMeta` + 6 处调用页面 | 缺失返回 `null`，模板显示"条数未知"；`hasNext` 仍保守取 false（宁可不给"加载更多"） |
| 导入可在确认中清空、选文件回调可叠加 | `pages/plan/import.vue` | `resetAll` 在 `confirming` 时拒绝并提示；`onChooseFile` 增加忙碌守卫（`:class` 的禁用只改透明度，`<view>` 仍可点） |
| 导入返回非 DRAFT 时前端照旧跳转 | `pages/plan/import.vue:confirm` | 校验 `res.status === 'DRAFT'`，不符则停止跳转并给契约不符错误 |
| run 顶层 `errorCode` 从不显示 | `types/coach.ts:RunState` + `pages/coach/chat.vue` | 声明字段并在停止原因横幅内显示 |
| `stopReason` 中文映射与契约 11 个取值不对齐 | `types/coach.ts:STOP_REASON_LABELS` | 按 design §11.1 第 8 条重写（含 `COMPLETED`/`LOOP_EXHAUSTED`/`NO_CREDENTIAL`/`PROVIDER_ERROR`/`PROTOCOL_INCOMPLETE`），删除 5 个契约里不存在的臆造键；未知取值仍原样显示机器码 |
| 停止态只有一行文字、无下一步 | `types/coach.ts:stopReasonAction/IsBlocking` + `chat.vue` | 阻塞态用错误色带；`NO_CREDENTIAL`/`BUDGET_STOPPED` 等给出"去模型设置"真实入口，其余只显示说明（不留假按钮） |
| `fakeModel` 只在 estimate 类型上声明 | `types/coach.ts:RunSubmitResult/RunState` | 三处载荷都声明（§11.1 第 2 条） |
| 余额查询失败的空态没有重试入口 | `pages/mine/model-settings.vue` | 按钮同时给 `#action` 插槽（`StateBlock` 的 empty 分支只渲染 `#action`） |
| 「重新读取」在忙碌时点了没反应 | `pages/mine/goal.vue`、`pages/mine/body/records.vue` | 明确 toast「正在读取，请稍候」，不再静默 return |
| 「放弃本地修改并重读」一键丢用户输入 | `pages/mine/profile.vue:confirmDiscard` | 二次确认后才重载 |

## 4. P2 与主题

- 淡黄色主题落地：`uni.scss` 20 个 token（奶油底 `#FFFBF1`、白卡片、淡黄 `#F5D87A` 只做填充、深金 `#715812` 承担文字级强调）、`App.vue` 全局控件类、`pages.json` 导航/TabBar、`styles/theme.ts` 供模板属性使用。
- 可访问性修正：淡黄按钮白字 → 深色字（白字仅 1.6:1）；5 处填充色被当文字色 → `$fa-accent`；选中态（`WeekdayPicker`、`.fa-chip--on`）从"只有颜色"改为填充+描边+加粗；输入框/按钮边界从装饰色 `$fa-line`（1.3:1）改为 `$fa-control-border`（4.38:1）。
- 源码里 15 处硬编码色值清零，并由 `npm run check:theme` 常驻检查（新加颜色会直接失败）。
- 文本溢出与未知值：`PlanDayCard` 加 `word-break`；`weekdayLabel(null)` 从"星期0"改为"未安排星期"；条目没有任何目标值时显示"未设定组数/次数/时长"而不是整块空白。
- 过时注释纠正：`api/model.ts`（`Idempotency-Key` 的 TODO 已定稿为"不需要"）、`api/coach.ts`（messages/steps 壳形状已定稿）、`types/coach.ts`（费用字段口径已定稿只在 run 顶层）、`DeepSeekProperties`（读超时 90s 的旧说明）。

## 4.5 真实启动（连上你的 MySQL 8.0.30 + Redis 8.2.3）发现并修掉的缺陷

前面所有绿测都没能发现这几条——因为**单元测试不起 Spring 上下文，也不经过微信编译器**。真启动 + 开发者工具一共暴露 5 个：

| # | 症状 | 根因 | 修法 |
|---|---|---|---|
| 1 | 启动失败 `No default constructor found: WechatCode2SessionClient` | 组件有 2 个构造器却没标 `@Autowired`，Spring 回退找无参构造器 | 给 5 个类的生产构造器显式标注（`WechatCode2SessionClient`、`DeepSeekBalanceGateway`、`FakeModelClient`、`CoachPromptTemplates`、`AppClock`），其中后两个是"有无参构造器所以能起来、但会**静默忽略配置值**"的隐性同类 |
| 2 | 启动失败 `expected single matching bean but found 2: requestMappingHandlerMapping, controllerEndpointHandlerMapping` | 开 actuator 后 `RequestMappingHandlerMapping` 有两个候选，`ProductionHardeningValidator` 按类型注入 | `StartupGuardConfig` 加 `@Qualifier("requestMappingHandlerMapping")` |
| 3 | 数据库还没就绪时应用起不来（`Failed to obtain JDBC Connection` 出现在 `RunWorker` 的启动扫描里） | 启动期补偿扫描直接 `scan()`，异常冒泡到上下文刷新 | 改成 `scanQuietly()`：启动这一次也尽力而为，失败等下一轮 |
| 4 | README 教的 `set -a; . ../infra/.env` 让应用**连到了本机 5.7**，报 `Unknown database 'smart_health_training'` | dotenv 里 JDBC URL 含 `&`，未加引号时被 shell 当后台命令，变量根本没导出（本机 5.7 上没有该库，连接在建库阶段就失败，**没有读写任何 5.7 数据**） | `.env`/`.env.example` 中含 `&`/空格的值统一加双引号；README 与 `docs/run-and-test.md` 同步说明 |
| 5 | 微信开发者工具报 `[WXSS 文件编译错误] ./pages/plan/edit.wxss error at token "*"` | `pages/plan/edit.vue` 用了 `> *` 通配选择器，uni-app 给它加 scoped 后缀后微信编译器不认（`vue-tsc` 与 `build:mp-weixin` 都不报错，只有 DevTools 报） | 两列栅格改成显式子组件类 `.pe-grid--2 .pe-grid__cell`；核对全仓仅此一处，见 `docs/ui-style-guide.md` §6 |

防复发：新增 `BeanConstructorAmbiguityTest`（组件扫描断言"多构造器必须声明注入点"，不需要数据库就能挡住第 1 类）。第 2/3 类仍只有真启动能发现，第 5 类只有微信开发者工具能发现（编译器与 uni-app 不一致），所以 `docs/run-and-test.md` §5 把那三个实证列成了要你配合的清单。

启动成功后的实测：`Started FitAgentApplication in 11.2s`，`/actuator/health` 里 `db=UP`、`redis=UP(8.2.3)`；dev 登录发出真实 Bearer 令牌并自动注册账户；档案创建/更新/乐观锁 409/回显、目标"至多一个 ACTIVE"、体重幂等重放 `replay=true`、JSON 导入 preview→confirm→DRAFT 与同键重放、非法动作代码定位到 `days[0].items[0].exercise_code`、AI 预估在**没有凭据**时仍返回金额与 3 个工具、提交 run 被 `CREDENTIAL_MISSING`(409) 拒且**台账无新增**——全部按契约工作。

另记一条边界：`FakeModelClient` 是**测试脚本队列**，不是演示驱动器；队列为空时它抛 `scriptExhausted` 而不是编一个回答。所以"没有 DeepSeek Key 也能看 AI 效果"这句在文档里要打个折：当前实现只保证不假成功，不提供可显示的假答案。要看得见的回答，要么给真实 Key，要么单独做一个 dev-only 演示脚本（需要定生命周期与标注口径）。

## 5. 明确没有修、以及理由

| 项 | 处置 |
|---|---|
| `POST /api/plans`（手工新建计划）没有幂等键 | **未改后端**。加幂等要新增 Redis 键空间或给计划表加自然键，属于 API/表结构变更，应先与用户定口径；本轮先消除前端连点（§2 的 `revising`/已有 `submitting` 守卫），并在审计里登记为已知缺口 |
| 目标新建无幂等、目标删除不带 `lockVersion` | 同上：`user_goal` 的"至多一个 ACTIVE"由数据库唯一索引兜底，重复新建最坏是多一条 DRAFT 历史，不产生数据破坏 |
| `chat.vue` 原样打印 `progress`/`usageSummary` 未知键 | 保留。载荷由本仓库后端生成且已在源头脱敏；改成硬编码白名单会让后端每加一个统计项都要改前端 |
| `evidenceRefs` 不在页面单独渲染 | 保留。每条事实卡片已恒定显示来源表/记录 ID/日期/规则版本，再列一份 ID 清单是噪音 |
| 历史会话页只能看"最近一个 run"，不接收 `runId` 路由参数 | 保留。design §11.1 第 9 条把该页定义为只读查看 + 回到当前会话 |
| 休息日开关取消后视觉不重绘 | 未改（微信 `<switch>` 需要在 cancel 分支强制重绑值，属独立小交互，登记为待办） |
| 全局加 `line-clamp`/安全区体系 | 未做。本轮只在出问题的位置补 `word-break`，避免一次铺开全站布局改动 |

## 6. 仍未验证（不写成通过）

1. ⛔ **真机与开发者工具的可视检查**：对比度、token 一致性是**源码级**计算结果；透明度叠加、`rpx` 缩放、深色模式、键盘与安全区、按压观感都**没有**在微信开发者工具里逐页看过。
2. ⛔ **阶段 1/3 的端到端运行**：需要用户的 MySQL 8.4 + Redis（+ 微信凭据）。登录/档案/体重/计划的"保存→刷新回显"、AI 的"提问→轮询→结果"都只有单元与契约级证据。
3. ⛔ **真实 DeepSeek 联调**：无用户凭据，本轮未发起任何收费请求。
4. ⛔ **并发与数据库约束**：两个 run 抢同一份日额度、`UNIQUE(active_conversation_id)`、租约被接管后的双 Worker 竞争，都只有 SQL 与单测证据。
5. ⚠️ 本轮新增的前端交互修复（冲突确认、禁用守卫、失效提示）**没有配套的自动化测试**：小程序侧目前没有组件测试脚手架。若需要，应先引入 `vitest` + `@vue/test-utils`，那是一条独立的工程决定。
6. **阶段 2（训练执行与统计）仍未实施**，按用户此前的阶段边界保持不变；它开工时 `get_training_progress` 等三个工具与"逐动作封顶 50%"的验收项才可能成立（`docs/feature-audit-before.md` §4）。
