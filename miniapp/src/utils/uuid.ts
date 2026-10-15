/**
 * UUID v4 生成（docs/p1-api.md §1「幂等」+ §5 clientRecordUuid + §8 导入 Idempotency-Key）。
 *
 * 用途不是安全场景，而是**幂等标识**：同一逻辑记录重试必须复用同一个值，
 * 所以生成时机与保存位置由调用方（页面/表单状态）负责，这里只保证格式正确。
 *
 * 随机源：
 *   1. 优先 `crypto.getRandomValues`（H5 / Node 环境可用）；
 *   2. 微信小程序运行时没有全局 `crypto`，退化为 Math.random + 单调计数器 + 时间戳混合，
 *      仍然写入正确的 version(4)/variant 位，格式与碰撞概率满足幂等用途。
 *   退化路径是已知事实，不谎称密码学强度。
 */

const HEX = '0123456789abcdef'

let counter = 0
let lastTime = 0

function weakRandomBytes(length: number): number[] {
  const out: number[] = []
  const now = Date.now()
  if (now !== lastTime) {
    lastTime = now
    counter = 0
  }
  counter = (counter + 1) % 0xffff
  for (let i = 0; i < length; i += 1) {
    // Math.random 提供主要熵源，计数器与时间戳尾数避免同一毫秒内的序列重复
    const mixed = Math.random() * 0xffffffff + ((counter * 2654435761) ^ (now + i * 97))
    out.push(Math.abs(Math.floor(mixed)) % 256)
  }
  return out
}

function randomBytes(length: number): number[] {
  const g = globalThis as { crypto?: { getRandomValues?: (a: Uint8Array) => Uint8Array } }
  if (g.crypto && typeof g.crypto.getRandomValues === 'function') {
    try {
      const buf = new Uint8Array(length)
      g.crypto.getRandomValues(buf)
      return Array.prototype.slice.call(buf) as number[]
    } catch {
      /* 落到弱随机源 */
    }
  }
  return weakRandomBytes(length)
}

/** 标准 8-4-4-4-12 小写 UUID v4。 */
export function uuidV4(): string {
  const b = randomBytes(16)
  while (b.length < 16) b.push(Math.floor(Math.random() * 256))
  b[6] = (b[6] & 0x0f) | 0x40 // version 4
  b[8] = (b[8] & 0x3f) | 0x80 // variant 10xx
  let out = ''
  for (let i = 0; i < 16; i += 1) {
    const byte = b[i] & 0xff
    out += HEX[byte >> 4] + HEX[byte & 0x0f]
    if (i === 3 || i === 5 || i === 7 || i === 9 || i === 11) out += '-'
  }
  return out
}

const UUID_V4_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

/** 校验（后端会对非法格式返回 `INVALID_UUID_FORMAT`，见 docs/p1-database.md §5）。 */
export function isUuidV4(value: string): boolean {
  return UUID_V4_RE.test(value)
}
