/* 本文件由 scripts/gen-icons.mjs 生成，请勿手改。 */
export const FA_ICON_NAMES = ['alert', 'calendar', 'chart', 'chevron', 'clock', 'copy', 'dict', 'dumbbell', 'edit', 'home', 'inbox', 'key', 'logout', 'moon', 'more', 'plus', 'records', 'refresh', 'robot', 'settings', 'shield', 'target', 'trend', 'upload', 'user', 'wallet', 'weight'] as const

export type FaIconName = (typeof FA_ICON_NAMES)[number]

/** 图标可用的前景色调。`primary`/`highlight` 是填充色，不能出现在这里。 */
export type FaIconTone = 'text' | 'accent' | 'sub' | 'danger' | 'success'
