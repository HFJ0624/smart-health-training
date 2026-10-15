/**
 * JS 侧要用的主题色值。
 *
 * 为什么需要第二个文件：SCSS 变量只能在样式里用，而 `<switch color="…">`、图表描边这类
 * **模板属性**必须是运行时字符串。色值的权威定义在 `src/uni.scss`，这里只是把它抄给 JS，
 * 改色时两处一起改（`npm run check:theme` 会核对两边一致，防止页面一半绿一半黄）。
 *
 * 用法约定同 uni.scss 顶部注释：primary/primaryPressed/highlight 只能当填充，
 * 淡黄之上的文字与图标一律用 text 或 accent。
 */
export const theme = {
  pageBg: '#FFFBF1',
  surface: '#FFFFFF',
  primary: '#F5D87A',
  primaryPressed: '#EACB68',
  highlight: '#FFF2BF',
  text: '#332E24',
  textSub: '#6B6253',
  textMute: '#7A705F',
  accent: '#715812',
  line: '#E9E1CF',
  controlBorder: '#88774F',
  success: '#26704B',
  successWeak: '#EAF5EE',
  warning: '#715812',
  warningWeak: '#FFF2BF',
  danger: '#B53A32',
  dangerWeak: '#FCEDEB'
} as const

/**
 * 原生控件的"打开"色：开关选中态是填充，但微信会把滑块画在轨道上，
 * 淡黄轨道配白滑块只有 1.4:1，所以这里给 accent（对白 6.76:1）。
 */
export const switchOnColor = theme.accent
