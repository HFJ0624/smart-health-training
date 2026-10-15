/**
 * uni-app 模板里 `<input>` / `<textarea>` / `<switch>` / `<picker>` 的事件对象
 * 与 DOM 的不一样：值在 `event.detail.value` 里。
 *
 * vue-tsc 却按 `@vue/runtime-dom` 把这些标签判定为标准 DOM 元素事件（`InputEvent`），
 * 于是把处理函数写成 `({ detail: { value } }) => void` 会报 TS2322/TS2345。
 * 让参数接受 `Event | UniInputEvent` 是最省事且类型安全的做法：
 * 形参更宽（InputEvent 是 Event 的子类型）就能赋值成功，运行时再取 uni 的 `detail.value`。
 */

export interface UniInputEvent {
  detail: { value: string }
}

export function inputValue(event: Event | UniInputEvent): string {
  const detail = (event as UniInputEvent).detail
  return typeof detail?.value === 'string' ? detail.value : ''
}

/** picker / switch 等把结果放在 `detail.value`，但类型可能是 number、boolean 或数组。 */
export function detailValue(event: Event | { detail?: { value?: unknown } }): unknown {
  return (event as { detail?: { value?: unknown } }).detail?.value
}
