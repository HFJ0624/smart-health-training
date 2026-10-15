# FitAgent Agent 第一版：演示步骤与可观察结果

日期：2026-10-14。**前提先说清楚**：本文件的每一步都**尚未在本机执行过**——它需要可连的 MySQL 8.4、Redis，以及你主动提供的 DeepSeek Key 与预算配置。下面写的是"照着做应当看到什么"，其中标 ⛔ 的一栏是只有真环境才能证明的部分（口径见 `agent-v1-acceptance.md` §3）。

## 0. 准备

```bash
# 1) 两个必须不同的 32 字节密钥（base64）
python -c "import base64,os;print(base64.b64encode(os.urandom(32)).decode())"   # 跑两次

# 2) 环境变量（.env 不提交）
export FITAGENT_MASTER_KEY='<上面第一串>'
export FITAGENT_KEY_FINGERPRINT_KEY='<上面第二串>'
export FITAGENT_MYSQL_URL='jdbc:mysql://<你的 8.4 主机>:3306/fitagent?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true&characterEncoding=utf8&serverTimezone=UTC'
export FITAGENT_MYSQL_USERNAME=fitagent FITAGENT_MYSQL_PASSWORD=...
export FITAGENT_REDIS_HOST=... FITAGENT_REDIS_PORT=...
export SPRING_PROFILES_ACTIVE=dev

# 3) 起后端（Flyway 会把 V10..V22 建齐）
cd backend && mvn -B -pl app -am spring-boot:run
```

如果没有那 13 张表而是手工建表，执行 `docs/sql/agent-v1-coach-and-foundation-mysql-8.4.sql` 并设 `FITAGENT_FLYWAY_ENABLED=false`。⚠️ **没有模型与价格种子（V23）时，一切调用都会被 `MODEL_NOT_CONFIGURED` 拒绝**——这是设计而非故障：请先按 `docs/agent-v1-protocol.md` 的核查结果把 `model_provider` / `model_catalog` / `model_price`（`rate_tier='PEAK'`、`enabled=1`）填好。

```bash
# 小程序（微信开发者工具导入 dist/dev/mp-weixin，关闭域名校验）
cd miniapp && npm install && npm run dev:mp-weixin
```

## 1. 配置凭据与预算（我的 → 模型设置）

| 动作 | 预期界面 | 预期数据库/日志 |
|---|---|---|
| 首次保存 Key | 只显示脱敏尾部（最多 8 字符）、状态 `ACTIVE`、`credentialVersion=1` | `model_credential` 一行：`ciphertext`/`nonce`/`key_fingerprint`/`encryption_key_version`；应用日志与前端**都不出现**完整 Key |
| 重复提交同一把 Key | 版本号不变 | HMAC 指纹相同 → 不产生新 `credential_version` |
| 提交另一把 Key | 版本号 +1 | 历史 `model_call`/`agent_run` 仍记着旧版本号（run 绑定凭据版本，执行中不悄悄换 Key） |
| 撤销 | 状态 `REVOKED` | 密文与 nonce 被清除；此后提问直接 `CREDENTIAL_INVALID`，**不发起任何调用** |
| 查看余额 | 币种、金额、上次成功查询时间 | `provider_balance_snapshot`；查询失败时显示上次快照 + `stale=true` 或"暂无法获取"，**不显示 0** |
| 设置单次/每日预算 | 限额回显；留空显示"不设限" | `budget_policy`。⛔ 没有生效策略时任何调用都被 BudgetGate 拒绝 |

## 2. 一次真实提问走完整个循环

输入："我最近体重怎么变？"

1. 输入区先显示预估：`estimatedCostLow/High`、`maxModelCalls=4`、`maxToolCalls=8`、可用工具 3 个、当前剩余额度，以及四条假设文案（含"按 PEAK 档计"）。这一步**不发收费请求**。
2. 发送 → `POST .../runs` 返回 `202 {runId, status:"QUEUED", fakeModel:false}`；页面开始按 `runId` 轮询（一次只有一个定时器）。
3. 服务端：会话行锁下分配 `message_no` 写入 user 消息 → 落 `agent_run(QUEUED)` → 事务提交后才派发给 Worker。
4. Worker 领取（`UPDATE ... WHERE status='QUEUED'` 影响 1 行）→ 组装 `AgentContext`（用户、会话、run、本人时区、业务日期、数据修订号、凭据版本、模型能力、预算引用、截止时间）→ 进入循环。
5. 第 1 次模型调用：`thinking=disabled`、`tool_choice=auto`、带 3 个工具声明 → 返回 `tool_calls:[{id:"call_x", function:{name:"get_weight_trend", arguments:"{\"start_date\":\"2026-09-14\",\"end_date\":\"2026-10-13\"}"}}]`。
6. Java 侧执行：白名单 → 注册表 → 严格 JSON 解析（未知字段即拒）→ Bean Validation → 归属校验（只查 `ctx.userId()` 的数据）→ 结果限长 → 生成 `evidenceId` → 追加**恰好一条** `role:"tool"` 消息。
7. 第 2 次模型调用（带完整消息历史）→ `finish_reason=stop` + 自由文本 + 引用 ID。
8. `AnswerAssembler` 校验引用必须存在于本 run 证据集合，按引用把**服务端取到的**数值/单位/日期组装成事实卡片；模型自己写的数字一律不覆盖。
9. 终态一次 CAS 落定：`agent_run.status/result_payload/settled_cost/uncertain_cost/finished_at`；assistant 消息与事实卡片落 `chat_message`。

页面上应当看到：回答正文 + 事实卡片（"最近体重 72.4kg · 2026-10-12 · 来源 body_measurement#1001"）+ 缺失项（若有）+ 本次费用（已知/不确定分列）+ 操作轨迹（"调用模型 2 次、查询体重 1 次"这类真实摘要，**不含模型隐藏推理**）。

10. 离开页面再回来：读同一个 `runId` 的已持久化结果，**不重发问题**、不重新计费。

## 3. 反例一：预算耗尽

把当日预算设成极小值（例如 0.000001 元）后提问：

- 第 1 次调用前的预占就被拒：`BUDGET_EXCEEDED_DAILY` → run 以 `stopReason=BUDGET_STOPPED` 结束；
- **没有**额外的"免费总结"请求：`model_call` 台账里一条记录都不新增；
- 页面显示"预算闸门在发起调用前拒绝"+ 本轮已核实的事实（如果之前几轮跑成功过，那些事实仍保留）；
- `agent_run.status='BUDGET_STOPPED'`，`settled_cost` 是已结算之和。

⛔ 需要真库验证的并发部分：两个 run 同时想花同一份日额度时，第二个必须被固定锁序（run → daily_usage）+ 比较并交换拦下，重复回调只记一次账。

## 4. 反例二：工具失败与供应商异常

| 情形 | 预期 |
|---|---|
| 参数非法（`{"days":0}` 或塞了 `userId`） | 工具返回结构化错误（`VALIDATION_FAILED` / `FORBIDDEN_IDENTITY_FIELD`）并**仍配对**回传；模型最多得到 1 次修正机会，修正照样计入次数与费用 |
| 未注册/不允许的工具名 | `TOOL_NOT_ALLOWED` 或 `UNKNOWN_TOOL`，仍补齐 tool 消息，不执行 |
| `finish_reason=length` 或被截断的 arguments | 不执行、不宣称成功，`PROTOCOL_INCOMPLETE`，保留此前已核实事实 |
| 429 / 402 / 500 / 503 | `PROVIDER_ERROR` + 具体错误码（`RATE_LIMITED`/`INSUFFICIENT_BALANCE`/…），**不自动重发** |
| 读取超时或断连（请求已发出） | `model_call=UNKNOWN`、预占转 `UNCERTAIN`；页面显示"存在用量不明"，额度继续被占，**不归零也不重发** |
| 用户点"停止" | `cancel_requested_at` 落库 + 通知在途线程；`RUNNING` 的 run 由 Worker 收敛成 `CANCELLED`；已发出的调用不免费。QUEUED 的直接转终态 |
| 进程被杀后重启 | 补偿扫描：有 `PREPARED/SENT/UNKNOWN` 调用 → `FAILED + UNKNOWN_CHARGE`（不重发）；一次都没发出 → 退回 `QUEUED` 重新领取 |
| 只有 Fake 开关在 prod 打开 | 应用启动失败（`FakeModelStartupGuard`）——这条⛔必须真跑一次 `SPRING_PROFILES_ACTIVE=prod` 才算证明 |

## 5. 本地无 Key 的闭环演示（只能证明消息循环）

```bash
FITAGENT_AGENT_FAKE_MODEL_ENABLED=true SPRING_PROFILES_ACTIVE=dev mvn -B -pl app -am spring-boot:run
```

Fake 客户端按脚本依次返回"工具调用 → 结果 → 最终回答"，能演示第 2 节的时序、配对、事实卡片与轨迹落库。**页面会显式标出"模拟模型"**（`fakeModel=true` 出现在 estimate/runs/run 三处）。它的回答内容、用量与费用都**不能**当作 DeepSeek 兼容性或计费正确性的证据。
