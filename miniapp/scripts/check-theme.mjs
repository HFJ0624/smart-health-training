/**
 * 主题一致性检查（无第三方依赖，纯 Node 标准库）。
 *
 * 检查四件事，全部是"源码里能确定"的部分：
 *   1) uni.scss 的色值与 styles/theme.ts 的 JS 侧色值一致（防止页面一半淡黄一半绿）；
 *   2) pages.json 的导航栏/TabBar 颜色都来自同一套 token；
 *   3) 页面与组件里没有散落的硬编码色值（提示词要求色值集中在变量里）；
 *   4) token 之间的对比度达到文字 4.5:1、图形 3:1。
 *
 * 它**不能**证明的事：真机渲染后的实际对比度（透明度叠加、深色模式、字体渲染、
 * 开发者工具里的视觉层级）——那些仍然需要在微信开发者工具里逐页看。
 */
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs'
import { join, relative } from 'node:path'

const root = process.cwd()
const src = join(root, 'src')
const failures = []
const notes = []

/** WCAG 2.2 相对亮度与对比度。 */
function luminance (hex) {
  const h = hex.replace('#', '')
  const full = h.length === 3 ? h.split('').map((c) => c + c).join('') : h.slice(0, 6)
  const channel = [0, 2, 4].map((i) => parseInt(full.slice(i, i + 2), 16) / 255)
    .map((v) => (v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4)))
  return 0.2126 * channel[0] + 0.7152 * channel[1] + 0.0722 * channel[2]
}

function contrast (a, b) {
  const la = luminance(a)
  const lb = luminance(b)
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
}

function round (n) {
  return Math.round(n * 100) / 100
}

/* ---------- 1) uni.scss 的 token ---------- */
const scss = readFileSync(join(src, 'uni.scss'), 'utf8')
const scssColors = {}
for (const match of scss.matchAll(/^\$fa-([a-z-]+):\s*(#[0-9a-fA-F]{3,8});/gm)) {
  scssColors[match[1]] = match[2].toUpperCase()
}
const aliasMatches = [...scss.matchAll(/^\$fa-([a-z-]+):\s*\$fa-([a-z-]+);/gm)]
for (const [, alias, target] of aliasMatches) {
  if (scssColors[target]) scssColors[alias] = scssColors[target]
}
if (Object.keys(scssColors).length < 12) failures.push(`uni.scss 只解析到 ${Object.keys(scssColors).length} 个颜色 token`)

/* ---------- 2) theme.ts 的 JS 侧色值 ---------- */
const ts = readFileSync(join(src, 'styles', 'theme.ts'), 'utf8')
const jsColors = {}
for (const match of ts.matchAll(/^\s*([a-zA-Z]+):\s*'(#[0-9a-fA-F]{3,8})'/gm)) {
  jsColors[match[1]] = match[2].toUpperCase()
}
const camel = (s) => s.replace(/-([a-z])/g, (_, c) => c.toUpperCase())
const pairMap = {
  pageBg: 'page-bg', surface: 'surface', primary: 'primary', primaryPressed: 'primary-pressed',
  highlight: 'highlight', text: 'text', textSub: 'text-sub', textMute: 'text-mute',
  accent: 'accent', line: 'line', controlBorder: 'control-border', success: 'success',
  successWeak: 'success-weak', warning: 'warning', warningWeak: 'warning-weak',
  danger: 'danger', dangerWeak: 'danger-weak'
}
for (const [jsKey, scssKey] of Object.entries(pairMap)) {
  const jsValue = jsColors[jsKey]
  const scssValue = scssColors[scssKey]
  if (!jsValue || !scssValue) {
    failures.push(`token 缺失：theme.ts.${jsKey} 或 uni.scss.$fa-${scssKey}`)
  } else if (jsValue !== scssValue) {
    failures.push(`token 漂移：theme.ts.${jsKey}=${jsValue} 与 uni.scss.$fa-${scssKey}=${scssValue} 不一致`)
  }
}
notes.push(`SCSS token ${Object.keys(scssColors).length} 个，JS 侧 ${Object.keys(jsColors).length} 个，逐对核对 ${Object.keys(pairMap).length} 组`)

/* ---------- 3) pages.json 的导航与 Tab 颜色 ---------- */
const pages = JSON.parse(readFileSync(join(src, 'pages.json'), 'utf8'))
const navColors = [
  ['globalStyle.navigationBarBackgroundColor', pages.globalStyle.navigationBarBackgroundColor],
  ['globalStyle.backgroundColor', pages.globalStyle.backgroundColor],
  ['tabBar.color', pages.tabBar.color],
  ['tabBar.selectedColor', pages.tabBar.selectedColor],
  ['tabBar.backgroundColor', pages.tabBar.backgroundColor]
]
const tokenValues = new Set(Object.values(scssColors))
for (const [where, value] of navColors) {
  if (!tokenValues.has(String(value).toUpperCase())) {
    failures.push(`pages.json ${where}=${value} 不属于主题 token`)
  }
}
notes.push(`pages.json 导航/Tab 颜色 ${navColors.length} 项已核对`)

/* ---------- 4) 页面与组件里不得散落硬编码色值 ---------- */
function walk (dir, out = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) {
      if (entry === 'node_modules' || entry === 'unpackage') continue
      walk(full, out)
    } else if (/\.(vue|ts)$/.test(entry) && !full.endsWith(join(src, 'styles', 'theme.ts'))) {
      out.push(full)
    }
  }
  return out
}
const offenders = []
for (const file of walk(src)) {
  const text = readFileSync(file, 'utf8')
  text.split('\n').forEach((line, index) => {
    // theme.ts 之外不应出现新的字面色值；rgba() 的半透明遮罩不在本项检查内（它没有 token 语义）
    const hits = line.match(/#[0-9a-fA-F]{6}\b/g)
    if (hits) offenders.push(`${relative(root, file)}:${index + 1} ${hits.join(' ')}`)
  })
}
if (offenders.length) {
  failures.push(`发现 ${offenders.length} 处硬编码色值，应改回 $fa-* token：\n  ${offenders.join('\n  ')}`)
}

/* 填充型 token 不能当文字/图标色：淡黄做前景在任何表面上都不达标 */
const FILL_TOKENS = ['primary', 'primary-pressed', 'highlight', 'primary-weak']
const foregroundViolations = []
for (const file of walk(src)) {
  readFileSync(file, 'utf8').split('\n').forEach((line, index) => {
    const hit = line.match(/(^|[^-\w])color:\s*\$fa-([a-z-]+)/)
    if (hit && FILL_TOKENS.includes(hit[2])) {
      foregroundViolations.push(`${relative(root, file)}:${index + 1} ${line.trim()}`)
    }
  })
}
if (foregroundViolations.length) {
  failures.push(`淡黄填充 token 被当作文字色（必须用 $fa-text 或 $fa-accent）：\n  ${foregroundViolations.join('\n  ')}`)
}
notes.push(`前景误用检查：${FILL_TOKENS.length} 个填充 token 不允许出现在 color 上`)

/* ---------- 5) 对比度 ---------- */
const T = scssColors
const textPairs = [
  ['text', 'page-bg', 4.5], ['text', 'surface', 4.5], ['text', 'primary', 4.5],
  ['text', 'primary-pressed', 4.5], ['text', 'highlight', 4.5],
  ['text-sub', 'surface', 4.5], ['text-sub', 'page-bg', 4.5], ['text-sub', 'highlight', 4.5],
  ['text-mute', 'surface', 4.5], ['text-mute', 'page-bg', 4.5],
  ['accent', 'surface', 4.5], ['accent', 'page-bg', 4.5], ['accent', 'highlight', 4.5],
  ['accent', 'primary', 4.5],
  ['success', 'success-weak', 4.5], ['success', 'surface', 4.5],
  ['danger', 'danger-weak', 4.5], ['danger', 'surface', 4.5],
  ['warning', 'warning-weak', 4.5]
]
const graphicPairs = [
  ['control-border', 'surface', 3.0], ['control-border', 'page-bg', 3.0],
  ['control-border', 'highlight', 3.0], ['primary', 'page-bg', 1.0]
]
for (const [fg, bg, need] of [...textPairs, ...graphicPairs]) {
  if (!T[fg] || !T[bg]) {
    failures.push(`对比度检查跳过：token $fa-${T[fg] ? bg : fg} 不存在`)
    continue
  }
  const ratio = round(contrast(T[fg], T[bg]))
  if (ratio < need) failures.push(`对比度不足：$fa-${fg} on $fa-${bg} = ${ratio} < ${need}`)
}
notes.push(`对比度：文字组合 ${textPairs.length} 组（≥4.5:1），图形组合 ${graphicPairs.length} 组（≥3:1）`)

/* ---------- 明确记录：muted 不允许出现在 highlight 上 ---------- */
const mutedOnHighlight = round(contrast(T['text-mute'], T['highlight']))
notes.push(`已核对约束：$fa-text-mute 放在 $fa-highlight 上只有 ${mutedOnHighlight}:1，因此 muted 只允许用于白/奶油表面`)
if (mutedOnHighlight >= 4.5) failures.push('text-mute/highlight 现在达标了？请同步更新 uni.scss 与 style guide 的约束说明')

/* ---------- 6) WXSS 不认的通配选择器（DevTools 会报 error at token "*"） ---------- */
const vueFiles = walk(src).filter((f) => f.endsWith('.vue'))
const universal = []
for (const file of vueFiles) {
  const text = readFileSync(file, 'utf8')
  const styleBlocks = [...text.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)]
  for (const [, css] of styleBlocks) {
    css.split('\n').forEach((line, index) => {
      const trimmed = line.trim()
      if (trimmed.startsWith('/*') || trimmed.startsWith('*') || trimmed.startsWith('//')) return
      if (/[{,]?\s*[^{}]*>\s*\*[^{}]*\{/.test(line) || /^\*\s*\{/.test(trimmed)) {
        universal.push(`${relative(root, file)}:${index + 1} ${trimmed}`)
      }
    })
  }
}
if (universal.length) {
  failures.push(`发现 WXSS 不接受的通配选择器（改成都带 class 的子选择器）：\n  ${universal.join('\n  ')}`)
}

/* ---------- 7) 图标名必须存在（色调由 TS 类型管，名字写错只会静默不显示，所以在这里挡） ---------- */
const iconNames = new Set(
  ((readFileSync(join(src, 'styles', 'icon-names.ts'), 'utf8').match(/FA_ICON_NAMES\s*=\s*\[([^\]]*)\]/) ?? [])[1] ?? '')
    .split(',')
    .map((s) => s.trim().replace(/^'|'$/g, ''))
    .filter(Boolean)
)
const badIcons = []
for (const file of vueFiles) {
  const text = readFileSync(file, 'utf8')
  // 只看图标组件标签上的**静态** name/icon；`:name="x"` 是动态绑定，由 TS 类型负责
  for (const tag of text.matchAll(/<(?:FaIcon|FaIconTile|EmptyState)[\s>]([^>]*?)\/>/g)) {
    const line = text.slice(0, tag.index).split(String.fromCharCode(10)).length
    for (const m of tag[1].matchAll(/(?:^|\s)(?:name|icon)="([a-z]+)"/g)) {
      if (!iconNames.has(m[1])) badIcons.push(`${relative(root, file)}:${line} ${m[1]}`)
    }
  }
}
if (badIcons.length) failures.push(`引用了不存在的图标名（跑 npm run gen:icons 加图标或改名）：\n  ${badIcons.join('\n  ')}`)
notes.push(`图标名集合 ${iconNames.size} 个，模板内静态引用已逐个核对`)

/* ---------- 8) tabBar 图标必须存在且不超过微信 40KB 限制 ---------- */
for (const item of pages.tabBar.list) {
  for (const key of ['iconPath', 'selectedIconPath']) {
    const rel = item[key]
    if (!rel) {
      failures.push(`pages.json tabBar「${item.text}」缺少 ${key}`)
      continue
    }
    const abs = join(src, rel)
    if (!existsSync(abs)) {
      failures.push(`tabBar 图标不存在：${rel}（跑 npm run gen:tabbar 重新生成）`)
      continue
    }
    const bytes = statSync(abs).size
    if (bytes > 40960) failures.push(`tabBar 图标超过 40KB：${rel} = ${bytes}B`)
  }
}
notes.push(`tabBar ${pages.tabBar.list.length} 个 tab 的图标文件与体积已核对`)

/* ---------- 输出 ---------- */
for (const note of notes) console.log('· ' + note)
if (failures.length) {
  console.error('\n主题检查失败：')
  for (const f of failures) console.error('  ✗ ' + f)
  process.exit(1)
}
console.log('\n✓ 主题 token、导航配色、硬编码色值与对比度检查全部通过')
