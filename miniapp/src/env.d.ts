/// <reference types="vite/client" />

declare module '*.vue' {
  import { DefineComponent } from 'vue'
  // eslint-disable-next-line @typescript-eslint/no-explicit-any, @typescript-eslint/ban-types
  const component: DefineComponent<{}, {}, any>
  export default component
}

/**
 * 环境变量的类型声明（与 vite/client 的 `ImportMetaEnv` 声明合并）。
 * 值来自 `.env.development` / `.env.production`，**不在代码里硬编码后端地址**（docs/p1-api.md §9）。
 * `DEV`/`PROD`/`MODE` 已由 vite/client 提供，这里只补充项目自定义的那一个键。
 */
interface ImportMetaEnv {
  /** 后端 Base URL，开发态为 `http://127.0.0.1:8080`；请求封装拼成 `{VITE_API_BASE_URL}/api`。 */
  readonly VITE_API_BASE_URL: string
}
