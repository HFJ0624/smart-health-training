import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

// FitAgent 小程序构建配置。
// vite 版本由 @dcloudio/vite-plugin-uni 的精确 peer 依赖锁定为 5.2.8，禁止擅自升级。
// 说明：`@/xxx` -> `src/xxx` 的别名由 uni 插件内置提供（tsconfig.json 中的 paths 与之保持一致），
// 因此这里不重复声明，避免 Windows 盘符路径在手写 alias 时被误处理。
export default defineConfig({
  plugins: [uni()]
})
