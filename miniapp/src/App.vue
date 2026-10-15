<script setup lang="ts">
import { onLaunch, onShow, onHide } from '@dcloudio/uni-app'
import { useSessionStore } from './stores/session'

/**
 * 启动流程（docs/p1-scope.md §3.1）：
 *   onLaunch 只做「把本地令牌读回内存」这一件事；
 *   是否有效（GET /api/me 探活）由入口页 `pages/auth/login` 负责，
 *   因为探活失败要落到登录页并提示一次，放在 App 里拿不到页面实例。
 */
onLaunch(() => {
  useSessionStore().restore()
})

onShow(() => {
  /* 前台时不主动请求：令牌失效由请求封装层按 401 集中处理 */
})

onHide(() => {
  /* 无需清理 */
})
</script>

<style lang="scss">
@import './styles/icons.scss';

/* ==========================================================================
   全局排版与控件样式。
   - 字号：UI 文本 15px、阅读文本 19px（高密度尺度，跨轮次确认过，见 docs/ui-style-guide.md §3）；
   - 留白/圆角/图标/关键数字：$fa-space-* / $fa-radius* / $fa-icon-* / $fa-num-* 的 rpx 基线，
     拥挤一律靠"拆层级 + 滚动"解决，禁止缩字号、压间距、缩小点击区；
   - 配色：淡黄只做填充，文字与图标用 $fa-text/$fa-accent，控件边界用 $fa-control-border。
   图标类 .fa-icon--<name>--<tone> 由 scripts/gen-icons.mjs 生成，不要在页面里写死色值。
   ========================================================================== */

page {
  background-color: $fa-page-bg;
  color: $fa-text;
  font-size: $fa-font-ui;
  line-height: 1.55;
  font-family: -apple-system, 'PingFang SC', 'Helvetica Neue', sans-serif;
}

view,
text,
button,
input,
textarea {
  box-sizing: border-box;
  font-size: $fa-font-ui;
}

/* ---------- 图标底座（色调类来自生成的 icons.scss） ---------- */
.fa-icon {
  display: inline-block;
  flex: 0 0 auto;
  background-repeat: no-repeat;
  background-position: center;
  background-size: 100% 100%;
}

/* ---------- 版式 ---------- */
.fa-page {
  padding: $fa-space-page $fa-space-page 64rpx;
}

.fa-title {
  font-size: $fa-font-title;
  font-weight: 600;
  line-height: 1.4;
}

/* 区块短标题：图形优先后，标题只留一两个字，说明性长句挪进详情 */
.fa-section-title {
  display: flex;
  align-items: center;
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  color: $fa-text;
  margin: $fa-space-section 0 $fa-space-tight;
}

.fa-section-title--first {
  margin-top: 0;
}

/* 成段阅读文本（备注、说明、错误详情）：19px */
.fa-prose {
  font-size: $fa-font-prose;
  line-height: 1.65;
  color: $fa-text-sub;
}

.fa-muted {
  font-size: $fa-font-ui;
  color: $fa-text-mute;
}

.fa-strong {
  font-weight: 600;
  color: $fa-text;
}

.fa-num {
  font-size: $fa-font-num;
  font-weight: 600;
  color: $fa-text;
}

/* 关键数字：首页/详情页的主指标 */
.fa-num--xl {
  font-size: $fa-num-xl;
  font-weight: 600;
  line-height: 1.1;
  color: $fa-text;
}

.fa-unit {
  font-size: $fa-font-ui-lg;
  color: $fa-text-sub;
  margin-left: 6rpx;
}

.fa-link {
  color: $fa-accent;
  font-weight: 600;
}

/* ---------- 卡片 ---------- */
.fa-card {
  background-color: $fa-surface;
  border-radius: $fa-radius;
  padding: $fa-space-card;
  margin-bottom: $fa-space-section;
  border: 1px solid $fa-line;
}

/* 重点卡片：淡黄底属于"表面"，文字仍用深色，不用 muted */
.fa-card--highlight {
  background-color: $fa-highlight;
  border-color: $fa-line;
}

.fa-card--tap {
  min-height: $fa-tap;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.fa-card--flat {
  border: none;
  padding: 0;
  margin-bottom: 0;
  background-color: transparent;
}

/* ---------- 行 ---------- */
.fa-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
  min-height: $fa-tap;
  padding: 12rpx 0;
  border-bottom: 1px solid $fa-line;
}

.fa-row:last-child {
  border-bottom: none;
}

.fa-row__label {
  font-size: $fa-font-ui;
  color: $fa-text-sub;
  flex: 0 0 auto;
  margin-right: 10px;
}

.fa-row__value {
  font-size: $fa-font-ui;
  color: $fa-text;
  text-align: right;
  flex: 1;
  word-break: break-all;
}

.fa-kv {
  display: flex;
  justify-content: space-between;
  padding: 3px 0;
  font-size: $fa-font-ui;
}

/* ---------- 按钮：交互高度 88rpx（=44px），图标 + 两字标签 ---------- */
.fa-btn {
  min-height: $fa-btn-h;
  padding: 0 32rpx;
  border-radius: $fa-radius-sm;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10rpx;
  font-size: $fa-font-ui-lg;
  /* 控件边界要能单独认出来，所以用 control-border 而不是装饰性的 line */
  border: 1px solid $fa-control-border;
  background-color: $fa-surface;
  color: $fa-text;
  margin: 0;
  line-height: 1.3;
}

/* 淡黄填充必须配深色文字：白字在这块底上只有 1.6:1 */
.fa-btn--primary {
  background-color: $fa-primary;
  border-color: $fa-primary;
  color: $fa-text;
  font-weight: 600;
}

.fa-btn--primary:active {
  background-color: $fa-primary-pressed;
  border-color: $fa-primary-pressed;
}

.fa-btn--danger {
  background-color: $fa-surface;
  border-color: $fa-danger;
  color: $fa-danger;
}

.fa-btn--ghost {
  background-color: $fa-highlight;
  border-color: $fa-control-border;
  color: $fa-accent;
}

.fa-btn--disabled {
  opacity: 0.55;
}

.fa-btn--block {
  width: 100%;
}

.fa-btn-row {
  display: flex;
  flex-wrap: wrap;
  gap: $fa-space-tight;
}

.fa-btn-row .fa-btn {
  flex: 1 1 44%;
}

/* 次级小操作（工具条里的"更多"等），仍保持 ≥40px 可点高度 */
.fa-btn--sm {
  min-height: $fa-tap;
  padding: 0 20rpx;
  font-size: $fa-font-ui;
}

/* ---------- 状态标签 ---------- */
.fa-chip {
  display: inline-flex;
  align-items: center;
  gap: 8rpx;
  min-height: 56rpx;
  padding: 0 20rpx;
  border-radius: $fa-radius-sm;
  font-size: $fa-font-ui;
  background-color: $fa-page-bg;
  color: $fa-text-sub;
  border: 1px solid transparent;
  margin: 0 12rpx 12rpx 0;
}

.fa-chip--primary {
  background-color: $fa-highlight;
  color: $fa-accent;
}

.fa-chip--danger {
  background-color: $fa-danger-weak;
  color: $fa-danger;
}

.fa-chip--success {
  background-color: $fa-success-weak;
  color: $fa-success;
}

.fa-chip--warning {
  background-color: $fa-warning-weak;
  color: $fa-warning;
}

/* 选中态不能只靠底色：加描边 + 加粗，色觉或屏幕取反下也分得出来 */
.fa-chip--on {
  background-color: $fa-primary;
  border-color: $fa-control-border;
  color: $fa-text;
  font-weight: 600;
}

/* ---------- 表单 ---------- */
.fa-field {
  padding: 20rpx 0;
  border-bottom: 1px solid $fa-line;
}

.fa-field:last-child {
  border-bottom: none;
}

.fa-field__label {
  font-size: $fa-font-ui;
  color: $fa-text-sub;
  margin-bottom: 12rpx;
}

.fa-field__label--req::after {
  content: ' *';
  color: $fa-danger;
}

.fa-input {
  min-height: $fa-btn-h;
  padding: 16rpx 20rpx;
  border: 1px solid $fa-control-border;
  border-radius: $fa-radius-sm;
  background-color: $fa-surface;
  font-size: $fa-font-ui;
  color: $fa-text;
  width: 100%;
}

.fa-input:focus {
  border-color: $fa-accent;
}

.fa-textarea {
  min-height: 176rpx;
  padding: 20rpx;
  border: 1px solid $fa-control-border;
  border-radius: $fa-radius-sm;
  background-color: $fa-surface;
  font-size: $fa-font-prose;
  line-height: 1.6;
  color: $fa-text;
  width: 100%;
}

/* 出错字段：颜色之外必须有文字（.fa-hint--error 写具体原因），边框只是加强 */
.fa-input--error,
.fa-textarea--error {
  border-color: $fa-danger;
}

.fa-hint {
  display: block;
  font-size: $fa-font-ui;
  color: $fa-text-mute;
  margin-top: 8rpx;
  line-height: 1.5;
}

.fa-hint--error {
  color: $fa-danger;
}

/* ---------- 提示条：状态一律由文字承载，边框与底色只做区分不做唯一标识 ---------- */
.fa-banner {
  border-radius: $fa-radius-sm;
  padding: 20rpx 24rpx;
  margin-bottom: $fa-space-tight;
  font-size: $fa-font-prose;
  line-height: 1.6;
  border: 1px solid $fa-line;
}

.fa-banner--error {
  background-color: $fa-danger-weak;
  color: $fa-danger;
}

.fa-banner--warn {
  background-color: $fa-warning-weak;
  color: $fa-warning;
}

.fa-banner--info {
  background-color: $fa-highlight;
  color: $fa-accent;
}

.fa-banner--success {
  background-color: $fa-success-weak;
  color: $fa-success;
}

/* ==========================================================================
   布局骨架：图形优先之后，页面由"图标入口 / 指标卡 / 工具条 / 底部操作条"四种容器拼出来，
   各页不再复制同一套 flex 布局。
   ========================================================================== */

/* 2 列宽松图标入口网格 */
.fa-tiles {
  display: flex;
  flex-wrap: wrap;
  gap: $fa-space-grid;
  margin-bottom: $fa-space-section;
}

.fa-tiles > .fa-tile {
  flex: 1 1 40%;
}

/* 指标行：一张卡里并排放 1–2 个关键数字，超过两个就该拆到二级页。
   子元素必须带 .fa-metric —— WXSS 不认「子选择器 + 通配星号」，编译期直接报 error at token。 */
.fa-metrics {
  display: flex;
  align-items: stretch;
  gap: $fa-space-grid;
}

.fa-metrics > .fa-metric {
  flex: 1;
  min-width: 0;
}

/* 页面/区块顶部的图标工具条：主操作 + 更多，不放整排次级按钮 */
.fa-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $fa-space-tight;
  margin-bottom: $fa-space-tight;
}

.fa-toolbar__group {
  display: flex;
  align-items: center;
  gap: 16rpx;
}

/* 底部固定操作条：主操作唯一，安全区由 env() 处理，不靠页面自己加空白 */
.fa-bottom-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: $fa-space-tight;
  padding: 20rpx $fa-space-page;
  padding-bottom: calc(20rpx + env(safe-area-inset-bottom));
  background-color: $fa-surface;
  border-top: 1px solid $fa-line;
}

.fa-bottom-bar > .fa-btn {
  flex: 1;
}

/* 有固定底栏时，正文要留出等高高度的尾巴，否则最后一行永远被压住 */
.fa-bottom-spacer {
  height: 208rpx;
}
</style>
