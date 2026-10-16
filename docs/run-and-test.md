# FitAgent 本地起服务与小程序测试流程

日期：2026-10-14。这份是"照着敲就能看见界面"的操作清单，不重复架构说明（`docs/module-guide.md`）。

## 0. 我这边做不到、只能你做的两步

我没法替你启动：你的 MySQL 和 Redis 在你的服务器上，仓库里没有也不会存有它们的地址与口令（`.env` 不提交，密钥不入库）。所以我做了这些，剩下两件事填一下就行：

1. `infra/.env` 我已经从 `.env.example` 生成好了，**两把随机演示密钥已填**（`FITAGENT_MASTER_KEY`、`FITAGENT_KEY_FINGERPRINT_KEY`，只用于本机演示，删掉重来即可），`FITAGENT_FLYWAY_ENABLED=false`（因为你已手工建表）。
2. **你只需要补 5 行**：`FITAGENT_MYSQL_URL`（把 `127.0.0.1:13306` 改成你服务器的真实地址，库名 `smart_health_training`）、`FITAGENT_MYSQL_USERNAME`、`FITAGENT_MYSQL_PASSWORD`、`FITAGENT_REDIS_HOST`、`FITAGENT_REDIS_PASSWORD`（Redis 无密码就留空）。
3. 确认服务器上那 21 张表建好了：`SHOW TABLES;` 应该有 21 行；`SELECT COUNT(*) FROM exercise_reference;` 应该是 25。
4. 想跑 AI 教练，还要执行一次 `docs/sql/agent-v1-model-seed-flash-mysql-8.4.sql`（模型与报价种子，数字来自 2026-10-13 的官方页核查，**启用的前提是你自己复核过**）。不执行也能启动应用，只是 AI 相关接口会明确回 `MODEL_NOT_CONFIGURED`——这是设计，不是崩。

## 1. 起后端

```bash
cd D:/workspace/smart-health-training/backend
# .env 里含 & 的值必须带引号（脚本已加）；不放心就直接在下面显式 export 覆盖
set -a; . ../infra/.env; set +a
export SPRING_PROFILES_ACTIVE=dev
export FITAGENT_MYSQL_URL='jdbc:mysql://<你的服务器IP>:3306/smart_health_training?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true&characterEncoding=utf8&serverTimezone=UTC'
mvn -B -pl app -am spring-boot:run
```

看到 `Started FitAgentApplication`（默认端口 8080）就算起来了。快速自检：

```bash
curl -s http://127.0.0.1:8080/actuator/health      # {"status":"UP"}
curl -s -X POST http://127.0.0.1:8080/api/auth/dev-login \
     -H 'Content-Type: application/json' -d '{"devUserId":"dev-a"}'   # 返回 code:OK + 令牌
```

> dev 档案里的 `dev-login` 只在 `dev` profile 打开；`prod` 下这条路由根本不会注册（`ProductionHardeningValidator` 会在启动期拒绝）。所以**别用 dev-login 的通过当成真实微信联调**。

## 2. 起小程序（微信开发者工具）

```bash
cd D:/workspace/smart-health-training/miniapp
npm install            # 装过就跳过
npm run dev:mp-weixin  # 产物在 dist/dev/mp-weixin，会热更新
```

开发者工具里（2026-10-14 实测修正）：

1. **小程序根目录必须是编译产物**，不是 `miniapp` 源码目录。uni-app 的 `app.json` 只存在于 `dist/dev/mp-weixin/`，
   直接导入 `miniapp` 会报 `app.json: 在项目根目录未找到 app.json`。
   现在 `miniapp/project.config.json` 已声明 `miniprogramRoot: "dist/dev/mp-weixin/"`，
   所以导入 `miniapp` 或 `miniapp\dist\dev\mp-weixin` 都可以；改完配置需要在 DevTools 里**重新打开项目**。
2. 域名校验**不用手勾**：`src/manifest.json` 与编译产物的 `project.config.json` 里 `urlCheck` 已是 `false`。
3. **TabBar 现在是 4 个**（首页 / 训练 / 教练 / 我的），图标是 `src/static/tabbar/*.png`，
   由 `npm run gen:tabbar` 生成（微信只吃 PNG，不给 SVG）。`pages/coach/chat` 已升为 tab 页：
   跳它必须用 `uni.switchTab`，用 `navigateTo` 会静默失败。
   ⚠ 改 `src/pages.json` 有坑：路由重复会让 dev 监听直接退出（`Error: pages.json->xxx duplication`），
   产物停在旧版本上——改完要看一眼监听日志，必要时重启 `npm run dev:mp-weixin`。
4. **小程序 AppID 不入库**（GitHub 的密钥扫描会把 `wx` + 16 位十六进制串当凭证报提醒，虽然 AppID 本身不是密钥）。
   真实值只留在两个本地文件里：`miniapp/project.config.json`（DevTools 打开 `miniapp` 目录时读它，已 gitignore）
   与 `infra/.env` 的 `FITAGENT_WECHAT_APPID`。仓库里只有模板
   `miniapp/project.config.example.json`（`appid` 留空）与 `src/manifest.json` 里空的 `mp-weixin.appid`。
   换机器时：复制 example 成 `project.config.json` → 填 AppID → DevTools **重新打开项目**。
   ⚠ 因为 `manifest.json` 的 appid 是空的，**必须打开 `miniapp` 目录而不是 `dist/dev/mp-weixin`**，
   否则 DevTools 读到的是编译产物自动生成的 `touristappid`，预览/真机调试拿不到你的小程序身份。
   ⚠ **真实微信登录还需要 AppSecret**：由你自己填进 `infra/.env` 的 `FITAGENT_WECHAT_SECRET=` 后面
   （公众平台 → 开发管理 → 开发设置；别发聊天里，会话会被留存），填完**重启后端**才生效。
   没有它点「微信登录」会被 `jscode2session` 拒（40013/40029 一类），此时**用登录页的 `dev-a` / `dev-b` 测**——
   这条路径不碰微信服务器（也正因如此，dev 登录的通过不能当成真实微信联调）。
   ⚠ DevTools 要求**打开项目的人是该小程序的项目成员**，所以这必须是你自己名下的那个小程序。
5. 后端不在这台机器上时，改 `miniapp/.env.development` 的 `VITE_API_BASE_URL` 为 `http://<后端IP>:8080`，然后重启 `npm run dev:mp-weixin`。
6. **真机预览时 `127.0.0.1` 打不通**（手机上的 localhost 是手机自己），要么换成局域网 IP 且同一网段，要么只看模拟器。
7. DevTools 若再报 `[WXSS 文件编译错误]`，把**文件名 + `error at token` 后面那个符号**发我。
   已踩过一条：`> *` 通配选择器微信编译器不认（`docs/ui-style-guide.md` §6），这类错误 `vue-tsc` 和 `build:mp-weixin` 都不会报。

## 3. 建议的点测顺序（每步都写了该看到什么）

登录页会显示两个开发身份 `dev-a` / `dev-b`——**这是故意给的，用来验"两个用户互相看不到数据"**。

| # | 操作 | 该看到什么 | 不通过说明 |
|---|---|---|---|
| 1 | 用 `dev-a` 登录 | 直接进首页；退出再登录，不产生第二个账户 | 重复注册 = `user_account` 唯一键没生效 |
| 2 | 我的 → 个人档案：填身高、训练经验、器械，保存 | 提示成功后**下拉刷新仍是刚填的值**；未填项显示"未填写" | 回显空 = 写入或读取带错了 user_id |
| 3 | 我的 → 目标管理：新建目标 → 确认生效 | 同时只能有一个 ACTIVE；对 ACTIVE 点编辑会被状态机拒 | 出现两个 ACTIVE = 数据库唯一闸门失效 |
| 4 | 我的 → 体重记录：加 2–3 条不同日期的记录 | 首页/记录页顶部"最新体重"取的是**最后一次测量**，不是最大/最小值 | 说明按 `measured_at` 判定没生效 |
| 5 | 训练计划 → 导入：选 `docs/templates/training-plan-template.v1.json` | 预览出现动作与组数；确认后落 **DRAFT**，状态是草稿不是生效 | 若变 ACTIVE 就是违约（导入只能出草稿） |
| 6 | 故意导入错内容（改一个动作代码为 `XXX`） | 错误清单能定位到 `days[?].items[?].exercise_code`，点一条会滚到对应位置；确认后**不会**留下半份计划 | 出现半份数据 = 聚合事务没回滚 |
| 7 | 计划详情：编辑草稿 → 生效 → 复制/另存新草稿 | 生效后再编辑要"另存为新草稿"，旧版本记录不变；被抢先时提示版本冲突并**先问你**要不要重读 | 自动重读会丢你刚填的内容（本轮已修） |
| 8 | 首页 | 今天有没有安排、当前计划名与版本、最新体重都是**真实数据**；没有指标就不显示该卡片 | 出现"完成度 50%"之类就是假数据（阶段 2 未实施） |
| 9 | 退出登录 | 提示已登出；重进要重新登录 | 服务端令牌没撤销 = 会话删除没生效 |
| 10 | 退出 → 用 `dev-b` 登录 | `dev-b` 的档案/体重/计划**全是空的**，任何 URL 手改 id 都拿不到 dev-a 的数据（404，不是 403） | 越权可见 = P0 |
| 11 | 我的 → 模型设置 | 未配 Key 时显示"尚未配置"；保存后只显示**尾部几位**+ 版本 1；撤销后状态 REVOKED；余额失败显示"暂无法获取"而不是 0 | 页面里出现完整 Key、或失败显示 0，都是缺陷 |
| 12 | 同上，设置单次/每日预算（如 0.5 / 5 元，币种 CNY） | 保存回显；不改预算直接问 AI，超限时会被拒 | 没有策略行时 BudgetGate 会拒绝发起调用（预期） |
| 13 | AI 教练：先点提问前的**预估** | 显示预估金额、上限 4 次模型 / 8 次工具、可用工具 3 个、`模拟模型` 标记（若开了 Fake） | 这里若报 `MODEL_NOT_CONFIGURED` → 第 0.4 步的种子没执行 |
| 14 | 问「我最近体重怎么变？」 | 排队 → 进行中 → 回答；下方事实卡片带来源表/记录 ID/日期；有"操作摘要"折叠区与本次费用 | 无 Key/无预算时的拒绝文案要能看懂，不许假成功 |
| 15 | 问「今天练什么？」 | 走 `get_training_plan`，引用当天**生效版本**的计划；没安排就说没安排 | 编造动作 = 事实来源失效 |
| 16 | 问「本周练了几次？」 | 明确回答"本轮没有已确认训练记录可查，无法判断"，**不能**拿计划次数冒充实际 | 只要它报出一个"次数"，就是伪造 |
| 17 | AI 回答后去把体重改成别的值，再回看历史回答 | 旧回答标 `数据已变化`（isStale），事实仍显示**当时**的来源日期，不会偷偷用新数字 | 数字变了 = 快照被重写 |
| 18 | 提问中点「停止」，然后杀掉小程序再进来 | 停止后状态是已取消；重新进入读**同一个 run**，不会自动再问一次（不重复扣费） | 自动重发 = 幂等/恢复逻辑失效 |
| 19 | 后端起来后手工删掉那把 Key（撤销）再提问 | 直接失败并提示去配置，**台账里没有新增调用**（`model_call` 行数不增） | 有新增行 = 无凭据仍发了请求（P0） |
| 20 | 看淡黄主题 | 首页/计划/导入/教练/设置五处观感一致：奶油底、白卡、淡黄主按钮配**深色字**；输入框边界看得清 | 出现白字淡黄按钮或看不见输入框边界 = 主题回归 |

### 3.1 阶段 2 新增流程（训练执行与统计）

| # | 操作 | 该看到什么 | 不通过说明 |
|---|---|---|---|
| 21 | 首页今日主卡点「开始」 | 进入训练记录页，顶部明说这是"进行中草稿"；再点一次「开始」（或杀掉再进）应回到**同一条**草稿 | 出现第二条草稿 = 单草稿闸门没生效（查 `UNIQUE(active_user_id)`） |
| 22 | 逐组勾选 + 填次数，中途「保存」退出再进 | 勾与数字原样回来，`lockVersion` 递增一次 | 回到旧值 = 聚合重建顺序或版本传递错 |
| 23 | 不勾任何组直接「结束」并确认 | 允许结束（用户可以说"今天没做成"），但统计里已确认组数为 0 且**不报错** | 报 0 分母/500 = 缺失被当成错误 |
| 24 | 结束后再点一次「结束」 | 幂等成功，状态仍是已确认，`model` 层不重复写 `ended_at`、修订号不再递增 | 修订号变了 = 重复计数风险 |
| 25 | 计划 A/B 各 3 组，实际确认 A=5、B=0，看当天对比 | 已记录组数完成度 **50%**、额外组 **2**，文案是"已记录完成情况" | 出现 83%/100% 或"没练"的断言 = 封顶/缺口规则错 |
| 26 | 训练日历与统计页 | 只有已确认场次出现为"已练"；草稿与丢弃不出现；无记录日显示缺失而非 0 | 空白日被解读成"没练"是允许的，但页面不能把它算成统计数字 |
| 27 | 问 AI「我今天练了多少」 | 走 `get_training_progress`，事实卡能追溯到 session ID 与计划版本；无记录时如实说没有 | 报出一个没有来源 session 的数字 = 伪造 |
| 28 | 用 dev-b 打开 dev-a 的 session 详情 | 一律 404（不是 403），列表里也看不到 | 能看到 = 越权（P0） |

## 4. 没有 DeepSeek Key 也能看整条 AI 链路（Fake 模式）

```bash
export FITAGENT_AGENT_FAKE_MODEL_ENABLED=true   # dev profile 下才生效
```

假客户端按脚本依次返回"要调工具 → 工具结果 → 最终回答"，能把 14/15 两步的**时序、配对、事实卡片、费用占位、操作摘要**跑通，页面上会明确标 **模拟模型**。它**不能**证明协议兼容、模型会不会真调工具、或费用算得准——那三件事只有真实供应商 + 你的账单能证明（`docs/agent-v1-acceptance.md` §3 已按"未联调"记录）。

在 `prod`/`production` 下把这个开关打开会让应用**启动失败**（`FakeModelStartupGuard`）。这条我想验证也验证不了——需要一个真能跑的 8.0.16+ 环境，你那边方便的话按下面第 5 节跑一次。

## 5. 数据库现状与"能不能让 Flyway 接管"（阶段 2 之后必读）

**当前真实状态**：库里的 21 张表（P1 的 V1–V9 + Agent 的 V10–V22）是**人工执行 SQL 建的**，
`FITAGENT_FLYWAY_ENABLED=false`，因此 **`flyway_schema_history` 表不存在**。
"有迁移文件"、"表已经建好"、"被 Flyway 记录并校验过"是三件不同的事，现在只满足前两件。

阶段 2 新增的三张表（`workout_session` / `workout_session_entry` / `workout_session_set`）走同一条路：
执行 `docs/sql/stage2-workout-mysql-8.4.sql`（脚本开头有前置检查，**不幂等**，重复跑会报表已存在）。

**不要直接打开 `baseline-on-migrate`。** baseline 的语义是"把指定版本及之前的迁移标记为已应用并跳过"，
它**不会**去比对那些脚本与库里现有表是否一致——一旦 baseline 版本号猜错，Flyway 会以为一切正常，
而实际差了一张表或一列 CHECK。要接管，按下面这条可核对的路径走：

1. 新建一个隔离空库 `fitagent_flyway_check`，用 Flyway 从 **V1 跑到 V25**（`FITAGENT_FLYWAY_ENABLED=true`）。
2. 两边各导一次结构：`mysqldump --no-data --skip-comments` 老手工库 vs 新库，逐表 diff。
   差异为 0 才谈接管；有差异就先按差异补 `ALTER`（迁移只向前，不改建表脚本）。
3. 确认要接管时，在**新库**上让 Flyway 建历史表，或把老库的 `flyway_schema_history` 用
   `flyway repair` + 手工插入 22 行记录的方式补齐——这一步必须由你亲自执行并留档，我不代做。
4. 接管完成后，后续版本才谈得上"自动迁移"。

**版本要求**：MySQL **8.0.16 及以上**。8.0.16 之前 CHECK 只解析不生效，
而 `utf8mb4_0900_ai_ci`、STORED 生成列 + 条件唯一闸门（`active_user_id`）也都依赖 8.x。
本机在跑的 5.7 不满足，禁止拿它验证迁移。

## 6. 顺手能替我补的三个实证（都需要你的环境）

1. `SPRING_PROFILES_ACTIVE=prod FITAGENT_AGENT_FAKE_MODEL_ENABLED=true java -jar app/target/fitagent-app-1.0.0-SNAPSHOT.jar` → **应该启动失败**，把报错前几行发我。
2. 真库并发：同一会话连发两个问题 → 第二个应该拿 `RUN_ALREADY_ACTIVE`；两个用户同时提问花同一份日预算 → 第二个应该被预算挡。
3. `SELECT VERSION();` 与 `SHOW COLLATION LIKE 'utf8mb4_0900%';` → 确认那台实例是 8.0.16+，否则 57 条 CHECK 其实没生效（`docs/feature-audit-before.md` 与上轮讨论的悬案）。
