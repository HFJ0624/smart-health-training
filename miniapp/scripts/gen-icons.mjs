/**
 * 图标资产生成器：把下面的 SVG 几何按「图标 × 色调」编译成 base64 data-URI 的 WXSS 类。
 *
 * 为什么不用图标字体 / Emoji / 现成图标库：
 *   - Emoji 不是图标系统（各端字形不同、粗细不可控，提示词 §7 明确禁止当正式图标）；
 *   - 图标字体要 @font-face 引外部文件，这台机器没有生成 woff 的工具链，且真机上还要额外下载；
 *   - `background-image: url("data:image/svg+xml;base64,...")` 是微信端验证可用的写法，且零依赖。
 * 颜色一律从 `src/uni.scss` 的 $fa-* token 读出后再烘进 SVG——淡黄主题里"淡黄只能当填充"，
 * 图标是前景，所以绝不能直接吃 $fa-primary，这里只开放 5 个可作前景的色调。
 *
 * 产物两个（都要提交，构建时不再依赖本脚本）：
 *   src/styles/icons.scss      —— .fa-icon--<name>--<tone>
 *   src/styles/icon-names.ts   —— 图标名联合类型，模板里写错名字 vue-tsc 直接报错
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const root = process.cwd()
const srcDir = join(root, 'src')

/* ---------- 1) 前景色色调（权威定义在 uni.scss，这里只按 token 名取） ---------- */
const scss = readFileSync(join(srcDir, 'uni.scss'), 'utf8')
function token (name) {
  const m = scss.match(new RegExp(String.raw`^\$fa-${name}:\s*(#[0-9a-fA-F]{3,8});`, 'm'))
  if (!m) throw new Error(`uni.scss 里找不到 $fa-${name}`)
  return m[1].toUpperCase()
}
const TONES = {
  text: token('text'),
  accent: token('accent'),
  sub: token('text-sub'),
  danger: token('danger'),
  success: token('success')
}

/* ---------- 2) 图标几何：24×24 线性图标，@@ 是颜色占位 ---------- */
const STROKE = 'stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"'
const ICONS = {
  home: '<path d="M4 10.6 12 4.2l8 6.4V19.8H4z"/><path d="M9.6 19.8v-5.6h4.8v5.6"/>',
  weight: '<rect x="3.6" y="4.6" width="16.8" height="14.8" rx="4"/><circle cx="12" cy="12.4" r="3.5"/><path d="M12 8.9v1.1"/><path d="M8.6 4.6h6.8"/>',
  calendar: '<rect x="3.6" y="5.2" width="16.8" height="15" rx="2.6"/><path d="M3.6 9.6h16.8M8.2 3.6v3.2M15.8 3.6v3.2"/><path d="M7.6 13.4h2.4M14 13.4h2.4M7.6 16.8h2.4M14 16.8h2.4"/>',
  dumbbell: '<path d="M6.6 7.6v8.8M3.8 9.4v5.2M17.4 7.6v8.8M20.2 9.4v5.2M6.6 12h10.8"/>',
  robot: '<rect x="4.2" y="7.6" width="15.6" height="12.2" rx="3.6"/><path d="M12 4.2v3.4"/><circle cx="12" cy="3.4" r="1.2" fill="@@ " stroke="none"/><path d="M9 12.6v1.8M15 12.6v1.8"/><path d="M2.6 12v3.4M21.4 12v3.4"/>',
  trend: '<path d="M3.6 16.8l5-5.2 3.4 3.4 7.4-8"/><path d="M15.4 6.6h4.6v4.6"/>',
  records: '<path d="M14.6 3.8H6.4v16.4h11.2V7.8z"/><path d="M14.6 3.8v4h3"/><path d="M9.2 12h5.6M9.2 15.6h5.6"/>',
  upload: '<path d="M12 15.8V4.4"/><path d="M7.6 8.8 12 4.4l4.4 4.4"/><path d="M4.4 19.6h15.2"/>',
  plus: '<path d="M12 5.4v13.2M5.4 12h13.2"/>',
  settings: '<path d="M4 8h9.2M17.6 8h2.4M4 16h2.4M11 16h9"/><circle cx="15.4" cy="8" r="2.2"/><circle cx="8" cy="16" r="2.2"/>',
  user: '<circle cx="12" cy="8" r="3.9"/><path d="M5 20.2c0-4.3 3.1-6.9 7-6.9s7 2.6 7 6.9"/>',
  wallet: '<rect x="3.6" y="6.2" width="16.8" height="12.6" rx="3"/><path d="M3.6 10.6h16.8"/><circle cx="16.4" cy="14.8" r="1.3"/>',
  shield: '<path d="M12 3.6 19 6.2v5.4c0 4.3-2.9 7.2-7 8.6-4.1-1.4-7-4.3-7-8.6V6.2z"/><path d="M9.2 12.2h5.6"/>',
  moon: '<path d="M19.4 14.6A8 8 0 1 1 9.8 4.9a6.5 6.5 0 0 0 9.6 9.7z"/>',
  key: '<circle cx="8" cy="8.4" r="3.9"/><path d="M10.8 11.2l8.4 8.4"/><path d="M15.6 16l1.8-1.8M17.8 18.2l1.4-1.4"/>',
  more: '<circle cx="5.6" cy="12" r="1.5" fill="@@ " stroke="none"/><circle cx="12" cy="12" r="1.5" fill="@@ " stroke="none"/><circle cx="18.4" cy="12" r="1.5" fill="@@ " stroke="none"/>',
  chevron: '<path d="M9.6 5.4 16.2 12l-6.6 6.6"/>',
  target: '<circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="3.4"/>',
  dict: '<path d="M4 5.6h5.6a2.4 2.4 0 0 1 2.4 2.4v10.4a2 2 0 0 0-2-2H4z"/><path d="M20 5.6h-5.6a2.4 2.4 0 0 0-2.4 2.4v10.4a2 2 0 0 1 2-2H20z"/>',
  alert: '<path d="M12 4.2 20.6 19.4H3.4z"/><path d="M12 9.4v4.4"/><circle cx="12" cy="16.6" r="1.1" fill="@@ " stroke="none"/>',
  inbox: '<path d="M3.6 12.6h4.6l1.5 2.6h4.6l1.5-2.6h4.6"/><path d="M6.2 4.4h11.6l2.6 8.2v7H3.6v-7z"/>',
  clock: '<circle cx="12" cy="12" r="8"/><path d="M12 7.4V12l3.4 2.1"/>',
  logout: '<path d="M14.6 4.4h5v15.2h-5"/><path d="M3.8 12h10.6"/><path d="M10.4 8.2 14.2 12l-3.8 3.8"/>',
  edit: '<path d="M4.4 19.6h4l10.2-10.2-4-4L4.4 15.6z"/><path d="M14.6 5.4l4 4"/>',
  copy: '<rect x="8.6" y="8.6" width="11.8" height="11.8" rx="2.4"/><path d="M15.4 8.6V5.8a2 2 0 0 0-2-2H5.8a2 2 0 0 0-2 2v7.6a2 2 0 0 0 2 2h2.8"/>',
  refresh: '<path d="M19.6 12a7.6 7.6 0 1 1-2.3-5.4"/><path d="M20 4v3.6h-3.6"/>',
  /* 逐组「已确认完成」的勾选：阶段 2 训练记录页的实际值只能由用户点出来，图形也必须只在点上时出现 */
  check: '<path d="M4.8 12.4 9.6 17.2 19.2 6.9"/>',
  chart: '<path d="M4 19.6h16"/><path d="M7 19.6v-6M12 19.6V8.4M17 19.6v-8.6"/>'
}

/* ---------- 3) 产出 ---------- */
function svg (inner, color) {
  const body = inner.replace(/@@\s*/g, color)
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="${color}" ${STROKE}>${body}</svg>`
}

const names = Object.keys(ICONS).sort()
const lines = [
  '/* 本文件由 scripts/gen-icons.mjs 生成，请勿手改：改图标或色调请改脚本后重新 npm run gen:icons。 */'
]
for (const name of names) {
  for (const [tone, color] of Object.entries(TONES)) {
    const b64 = Buffer.from(svg(ICONS[name], color), 'utf8').toString('base64')
    lines.push(`.fa-icon--${name}--${tone} { background-image: url("data:image/svg+xml;base64,${b64}"); }`)
  }
}
writeFileSync(join(srcDir, 'styles', 'icons.scss'), lines.join('\n') + '\n', 'utf8')

writeFileSync(
  join(srcDir, 'styles', 'icon-names.ts'),
  [
    '/* 本文件由 scripts/gen-icons.mjs 生成，请勿手改。 */',
    'export const FA_ICON_NAMES = [' + names.map((n) => `'${n}'`).join(', ') + '] as const',
    '',
    'export type FaIconName = (typeof FA_ICON_NAMES)[number]',
    '',
    "/** 图标可用的前景色调。`primary`/`highlight` 是填充色，不能出现在这里。 */",
    "export type FaIconTone = 'text' | 'accent' | 'sub' | 'danger' | 'success'",
    ''
  ].join('\n'),
  'utf8'
)

console.log(`✓ 生成 ${names.length} 个图标 × ${Object.keys(TONES).length} 个色调 = ${names.length * Object.keys(TONES).length} 条 .fa-icon 规则`)
console.log('  src/styles/icons.scss, src/styles/icon-names.ts')
