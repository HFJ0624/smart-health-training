# FitAgent 小程序视觉与组件规范（淡黄色主题）

日期：2026-10-14。落地范围：`miniapp/src` 全部 14 个页面与 18 个组件、`pages.json` 导航与 TabBar。
自动检查：`cd miniapp && npm run check:theme`（源码级，见 §5 的边界说明）。

## 1. Token 权威位置

| 位置 | 作用 |
|---|---|
| `miniapp/src/uni.scss` | **唯一权威**。uni-app 自动把它注入每个 scss，页面直接用 `$fa-*`，不需要 `@import` |
| `miniapp/src/styles/theme.ts` | JS 侧副本：模板属性（如 `<switch :color>`）、**canvas 描边色**拿不到 SCSS 变量，只能运行时给字符串 |
| `miniapp/src/App.vue` `<style>` | 全局版式与控件类（`.fa-btn`、`.fa-card`、`.fa-input`、`.fa-banner--*`、`.fa-chip--*`、`.fa-tiles`、`.fa-toolbar`、`.fa-bottom-bar`）+ `@import './styles/icons.scss'` |
| `miniapp/src/styles/icons.scss` | **生成物**：`.fa-icon--<name>--<tone>` 的 base64 SVG 背景。改图标改脚本，别手改这个文件 |
| `miniapp/src/styles/icon-names.ts` | **生成物**：图标名联合类型 `FaIconName` 与色调 `FaIconTone`，写错名字 `vue-tsc` 直接报错 |
| `miniapp/scripts/gen-icons.mjs` | 图标几何 + 色调 → 上面两个文件（`npm run gen:icons`） |
| `miniapp/scripts/gen-tabbar.mjs` | 纯 Node（`node:zlib`）手写 PNG 编码器 → `src/static/tabbar/*.png`（`npm run gen:tabbar`） |
| `miniapp/src/pages.json` | 导航栏与 TabBar 配色/图标，取值必须来自 token |

`check:theme` 会逐对比较 `uni.scss` 与 `theme.ts` 的同名色值，漂移即失败——这是上一轮"绿色系残留"的真实风险点，所以做成检查而不是口头约定。

## 2. 主题 token 与用途

| Token（SCSS） | 值 | 用途 | 不能用来做什么 |
|---|---|---|---|
| `$fa-page-bg` | `#FFFBF1` | 页面奶油底、导航栏、TabBar 上方 | — |
| `$fa-surface` | `#FFFFFF` | 卡片、表单、弹窗、聊天消息表面 | — |
| `$fa-primary` | `#F5D87A` | 主按钮、选中项**填充** | ✗ 文字色、✗ 图标色、✗ 单独作为"选中"线索 |
| `$fa-primary-pressed` | `#EACB68` | 主按钮按压态 | 同上 |
| `$fa-highlight` | `#FFF2BF` | 轻量重点卡片、标签底、提示条底 | ✗ 承载 `muted` 文字（只有 4.34:1） |
| `$fa-text` | `#332E24` | 标题、正文、数字、淡黄之上文字 | — |
| `$fa-text-sub` | `#6B6253` | 辅助说明 | — |
| `$fa-text-mute` | `#7A705F` | 弱说明、占位 | ✗ 放在黄/淡黄填充上 |
| `$fa-accent` | `#715812` | 链接、强调文字、选中图标、开关"开"色 | — |
| `$fa-line` | `#E9E1CF` | 装饰分隔线、卡片轻边框 | ✗ 输入框边界、✗ 选中态、✗ 错误提示的唯一载体 |
| `$fa-control-border` | `#88774F` | 控件边界、聚焦/选中/错误描边 | — |
| `$fa-success` / `$fa-success-weak` | `#26704B` / `#EAF5EE` | 已确认、成功 | — |
| `$fa-warning` / `$fa-warning-weak` | `#715812` / `#FFF2BF` | 提醒、未完成 | — |
| `$fa-danger` / `$fa-danger-weak` | `#B53A32` / `#FCEDEB` | 错误、删除、不可逆 | — |

兼容别名 `$fa-bg`、`$fa-card`、`$fa-primary-weak` 仍可用（旧页面大量引用），新代码请写正式名。

## 3. 版式与控件尺度（沿用既有高密度规范）

| 项 | 值 | 说明 |
|---|---|---|
| 普通 UI 文本 | 15px | 按钮、标签、列表、输入 |
| 成段阅读文本 | 19px | 备注、AI 回答、错误详情 |
| 标题 / 关键数字 | 20px / 22px | — |
| 可点区域 | ≥40px，实际 44px | 按钮与行控件统一 |
| 页面左右内边距 | 36rpx（=18px） | UI 重排后按提示词 §6 放大，见下面的 rpx token 表 |
| 卡片内边距 / 圆角 | 28rpx / 24rpx | 同上 |

### 3.1 UI 重排后新增的 rpx 尺度 token（`uni.scss`）

留白与图形尺寸走 rpx（随屏宽线性放大），字号继续走 px（高密度）：

| Token | 值 | 用途 |
|---|---|---|
| `$fa-space-page` | 36rpx | 页面左右留白 |
| `$fa-space-section` | 32rpx | 主要区块间距、卡片下外边距 |
| `$fa-space-card` | 28rpx | 卡片内边距 |
| `$fa-space-tight` | 16rpx | 卡片内元素之间 |
| `$fa-space-grid` | 24rpx | 2 列网格间距 |
| `$fa-radius` / `$fa-radius-sm` | 24rpx / 16rpx | 卡片圆角 / 控件圆角 |
| `$fa-icon-lg` / `-md` / `-sm` | 64 / 56 / 40rpx | 入口图标 / 默认图标 / 行内小图标 |
| `$fa-num-xl` / `$fa-num-lg` | 64 / 52rpx | 关键数字 |
| `$fa-btn-h` | 88rpx（=44px） | 按钮与输入框交互高度 |
| `$fa-tile-h` | 176rpx | 图标入口卡最小高度 |

**与提示词的偏差与理由**：提示词 §6 建议辅助说明 28–30rpx（≈14–15px）与"标题 34–36rpx（≈17–18px）"，而本项目已确立"禁止 12–13px 正文、UI 文本 15px、阅读文本 19px"的高密度规范并全站落地。字号保留项目既有尺度（短标签 30rpx=15px 正好落在建议区间内），避免"一页一套"；**留白、圆角、图标、关键数字则按提示词放大**——拥挤靠分层与滚动解决，不靠缩字号。

## 4. 状态表达规则（不只是颜色）

- **选中态**：`.fa-chip--on`、`FaSegmented` 选中段、`FaDayStrip` 选中格、`WeekdayPicker` 选中格 = 淡黄填充 + `$fa-control-border` 描边 + 加粗深色文字，三重线索；不再出现"淡黄底 + 白字"。
- **控件边界**：`.fa-input` / `.fa-textarea` / `.fa-btn` 用 `$fa-control-border`（对白 4.38:1 ≥3:1）；`$fa-line` 只做装饰分隔。
- **错误**：字段级 = 红边 + `.fa-hint--error` 文字（`.sb-fields` 还带字段名与 code）；页面级 = `.fa-banner--error`，文案一定包含错误码与 traceId，颜色从不单独承载语义。
- **禁用/提交中**：`.fa-btn--disabled`（0.55 透明度）**必须**配函数内守卫；本轮已给 `import.vue` 的选文件、`edit.vue` 的另存草稿、`list.vue` 的重取补上真实判断与"正在读取，请稍候"反馈，因为 `<view>` 不像 `<button>` 有原生 disabled。
- **未知值**：一律显示"未设定 / 未提供 / 条数未知 / 暂无法获取"，禁止 `0`、空串或 `undefined`（`weekdayLabel(null)` 从"星期0"改为"未安排星期"）。

## 5. 检查过什么、没检查什么

`npm run check:theme` 实际断言（全部通过）：

1. `uni.scss` 20 个颜色 token 与 `theme.ts` 17 个 JS 色值逐对一致；
2. `pages.json` 的 5 个导航/TabBar 颜色都属于 token 集合；
3. `src` 下**零**字面十六进制色值（改前 15 处）；
4. 填充 token（`primary`/`primary-pressed`/`highlight`/`primary-weak`）出现在任何 `color:` 声明里即失败（改前 5 处）；
5. 19 组文字对比度 ≥4.5:1、4 组图形对比度 ≥3:1（最低项：`text-mute on page-bg` = 4.71，`accent on primary` = 4.82，`control-border on surface` = 4.38）；
6. 反向断言：`text-mute on highlight` 现在仍是 4.34:1，所以它一旦达标就说明有人放宽了约束，检查会主动报错；
7. **所有 `.vue` 的 `<style>` 里不得出现 `> *` 之类通配选择器**（§6 那条 DevTools 编译错误的防复发）；
8. 模板里静态写法的 `<FaIcon name="…">` / `<FaIconTile icon="…">` / `<EmptyState icon="…">` **必须存在于 `icon-names.ts` 的图标集合**（写错名字只会静默不显示，所以做成检查）；
9. `pages.json` 的 4 个 tab 都必须有 `iconPath` + `selectedIconPath`，文件真实存在且 **≤40KB**（微信硬限制）。

**这份检查不能证明的**（仍未验证，别当成通过）：真机与开发者工具里的实际渲染（透明度叠加、`rpx` 缩放、系统深色模式、字体回退）、键盘避让与底部安全区、按压态在微信原生组件上的观感、**canvas 图表是否真的画出线**、图标 base64 SVG 在目标端的渲染质量、图片资源与新主题的协调。**这些需要在微信开发者工具逐页目视复查**，本轮已在 DevTools 里跑起来并开始复查（先修掉了一条编译错误，见 §6）。

## 6. WXSS 选择器限制（DevTools 实测，不是 CSS 常识）

- **`> *` 通配选择器在小程序里会直接编译失败**：`[WXSS 文件编译错误] error at token "*"`。
  `pages/plan/edit.vue` 的两列栅格原来写 `.pe-grid--2 > * { flex: 1 }`，uni-app 加 scoped 后缀后变成
  `.pe-grid--2 > *.data-v-xxx`，微信编译器解析不了。现改为给子组件显式类名
  （`<NumberField class="pe-grid__cell">` + `.pe-grid--2 .pe-grid__cell`），编译产物里已无含 `*` 的规则。
- 影响面核对过：全仓 `src` 下只有这一处 `> *`；`dist/dev` 与 `dist/build` 的所有 `.wxss` 都不再有通配选择器。
- 新增样式时**别用**通配选择器与 `[attr=value]` 属性选择器；`:active` / `:focus` / `::after` 目前 DevTools 不报错，
  但只有主按钮的 `:active` 按压态是目视确认过的，其余未验证。

## 7. 图标系统（本轮新增）

- **出口只有一个**：`<FaIcon name="weight" tone="accent" size="56rpx" />`。色调 `tone` 类型上只允许
  `text | accent | sub | danger | success` 五个**能当前景**的 token——`primary`/`highlight` 是填充色，
  淡黄之上/之下都看不见，所以根本不给它们出现在图标上的机会。
- **实现方式**：`scripts/gen-icons.mjs` 里存 SVG 几何（24×24、`stroke-width 1.7`、圆头），按"图标 × 色调"
  烘成 base64 data-URI 写进 `src/styles/icons.scss`，由 `App.vue` 全局引入。
  为什么不用图标字体：这台机器没有生成 woff 的工具链，真机还要额外下载；为什么不用 Emoji：
  字形/粗细各端不一致，提示词 §7 也明确禁止当正式图标。
- **tabBar 必须另做**：微信 tabBar 只吃 PNG 位图（不给 SVG，也不帮忙栅格化），所以
  `scripts/gen-tabbar.mjs` 用 `node:zlib` 手写 PNG 编码器生成 8 张 81×81 RGBA（4 图标 × 普通/选中），
  4× 超采样后降采样做抗锯齿。改图标后必须 `npm run gen:tabbar` 重新生成。
- 图标语义对照表见 `docs/ui-render-plan.md` §2；`check:theme` 会核对模板里静态写的图标名真的存在。

## 8. 图表（本轮新增，且只落地了一个）

- `FaTrendChart.vue`：`canvas type="2d"`（微信基础库 2.9+）画**真实体重折线**。三条不让步的规则：
  1. 横轴按**测量时间戳**定位，不按记录序号——补录造成的稀疏区间必须看得出疏，不能被画成等间隔；
  2. 只有 1 个点时**只画点不连线**，绝不为了"像趋势"造第二个点；无数据画空状态而不是 0；
  3. 每个实例唯一 canvas id + 卸载后停止绘制 + dpr 缩放（`canvas.width = w * dpr` 后 `ctx.scale`），
     尺寸来自 `createSelectorQuery().fields({node,size})` 的真实容器，不写死。
- 描边色来自 `theme.ts`（canvas 拿不到 SCSS 变量），所以 `check:theme` 的 token 一致性顺带保住了图表配色。
- **进度环 / 训练日历点阵 / 周报柱状图本轮明确不做**：它们要的数据（`workout_session`、完成度、
  已确认训练记录）在 V1–V22 迁移里根本不存在（阶段 2 未实施，见 `docs/feature-audit-before.md` §4）。
  首页与统计页因此不放任何"完成度"图形——放上去的就是假数据。
  首页的"本周"点阵画的是**计划安排**并明确标注"这是计划，不代表实际练过"。

## 9. 导航与页面分层（本轮变更）

- TabBar 从 3 个变 4 个：**首页 / 训练 / 教练 / 我的**，都带图标。
  `pages/coach/chat` 从 `navigateTo` 提升为 tab 页——它不读任何入参，提升后 `switchTab` 不丢上下文；
  代价是所有跳它的地方必须用 `uni.switchTab`（`mine/index.vue` 的 `goto()` 已按 `TAB_PAGES` 分流）。
- 页面职责与"旧功能去哪了"的完整对照表在 `docs/ui-render-plan.md` §2，本轮 14 个页面逐页有去向，无删除项。
- 分层规则：一个区域**只有一个主操作**，其余进 `FaMoreMenu`；一条记录一行，调试字段（ID、lockVersion、
  幂等键、traceId）收进点按展开区，**不删字段**；表单字段标签、填写提示、错误文案保留必要长度。
