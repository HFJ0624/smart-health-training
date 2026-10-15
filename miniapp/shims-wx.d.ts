/**
 * 微信小游戏/小程序 `wx` 全局对象的最小化环境声明。
 *
 * 为什么需要这个文件：`@dcloudio/types@3.4.31` 只声明了 `uni.*`（把微信接口收进 `UniNamespace`），
 * **没有**声明全局 `wx`。而本项目按 `docs/p1-plan-import.md` §1 的要求必须调用微信原生接口
 * `wx.chooseMessageFile` 与 `wx.getFileSystemManager().readFile`，因此这里按官方文档给出用到的
 * 那部分结构，不做全量声明。
 *
 * 所有 `wx.*` 调用点都必须写在 `// #ifdef MP-WEIXIN ... // #endif` 条件编译块内（非微信平台没有 `wx`）。
 */

interface WxChooseMessageFileOption {
  /** 选择的文件数目，本项目固定 1 */
  count: number
  /** 本项目固定 'file'（也可 all/video/image） */
  type: 'all' | 'video' | 'image' | 'file'
  /** 所允许选择文件的后缀名数组，例如 ['json'] */
  extension?: string[]
  success?: (res: { tempFiles: WxChosenFile[] }) => void
  fail?: (err: { errMsg: string; errCode?: number }) => void
  complete?: (res: { errMsg: string; tempFiles?: WxChosenFile[] }) => void
}

interface WxChosenFile {
  /** 本地临时文件路径，可直接喂给 readFile */
  path: string
  /** 原始文件名 */
  name: string
  /** 文件大小（字节） */
  size: number
  type: string
  time: number
}

interface WxReadFileOption {
  filePath: string
  /** 本项目固定 'utf8'；不传 encoding 时返回 ArrayBuffer */
  encoding?: 'utf8' | 'binary' | string
  /** 官方事实：readFile 没有 Promise 形态，只能回调（docs/p1-plan-import.md §1），封装层用 new Promise 包 */
  success?: (res: { data: string | ArrayBuffer; errMsg?: string }) => void
  fail?: (err: { errMsg: string; errno?: number }) => void
  complete?: (res: unknown) => void
}

interface WxFileSystemManager {
  readFile(option: WxReadFileOption): void
  readFileSync(filePath: string, encoding?: string): string | ArrayBuffer
}

interface WxApi {
  chooseMessageFile(option: WxChooseMessageFileOption): void
  getFileSystemManager(): WxFileSystemManager
}

declare const wx: WxApi
