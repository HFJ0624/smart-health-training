// 为什么纯 stdlib 手写 PNG 编码器：这台机器没有 PIL / canvas / sharp 可用，
// 而微信 tabBar 只吃 PNG 位图（不支持 SVG，也不会帮忙栅格化），
// 为一个 81px 图标引第三方图像库不值当，所以用 node:zlib + 自实现 CRC32 直接打包。
import { deflateSync } from 'node:zlib'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

/* ---------- 画布参数 ---------- */
const SIZE = 81 // 输出边长
const SS = 4 // 超采样倍率：在 324×324 上绘制，再 4×4 盒式降采样
const BUF = SIZE * SS
const AA = 0.5 / SS // 亚像素软边，近似单个子像素格的面积覆盖
const STROKE = 5 // 线性图标笔画宽度
const PAD = 10 // 四周留白目标

const NORMAL = '#6B6253'
const ACTIVE = '#715812'

/* ---------- 覆盖率缓冲 ---------- */
let cov = new Float32Array(BUF * BUF)

function reset () {
  cov = new Float32Array(BUF * BUF)
}

/** 累加覆盖率取 max（不是相加）：同一图形重叠处不该越叠越黑，否则接头会有深色疤。 */
function put (px, py, c) {
  const i = py * BUF + px
  if (c > cov[i]) cov[i] = c
}

/** 到骨架距离 d 的子像素覆盖：软边宽 = 一个子像素（2*AA），d <= hw-AA 全黑、d >= hw+AA 全透明。
 *  注意量纲：AA 是最终像素单位下的半个子像素，除以 2*AA 才归一成 0..1 的斜坡。 */
function ramp (d, hw) {
  const v = (hw + AA - d) / (2 * AA)
  return v <= 0 ? 0 : v >= 1 ? 1 : v
}

/** 扫描线包围盒：把几何范围换算成子像素整数区间并裁到缓冲内。 */
function span (min, max, out) {
  out[0] = Math.max(0, Math.floor(min * SS))
  out[1] = Math.min(BUF - 1, Math.ceil(max * SS))
}

const bx = [0, 0]
const by = [0, 0]

/* ---------- 图元 ---------- */

/** 粗线段，两端圆头（长度 0 时退化为圆盘）。 */
function line (x0, y0, x1, y1, w = STROKE) {
  const hw = w / 2
  const pad = hw + AA
  span(Math.min(x0, x1) - pad, Math.max(x0, x1) + pad, bx)
  span(Math.min(y0, y1) - pad, Math.max(y0, y1) + pad, by)
  const dx = x1 - x0
  const dy = y1 - y0
  const len2 = dx * dx + dy * dy
  for (let py = by[0]; py <= by[1]; py++) {
    const fy = (py + 0.5) / SS
    for (let px = bx[0]; px <= bx[1]; px++) {
      const fx = (px + 0.5) / SS
      let t = len2 === 0 ? 0 : ((fx - x0) * dx + (fy - y0) * dy) / len2
      t = t < 0 ? 0 : t > 1 ? 1 : t
      const d = Math.hypot(fx - (x0 + t * dx), fy - (y0 + t * dy))
      const c = ramp(d, hw)
      if (c > 0) put(px, py, c)
    }
  }
}

/** 空心圆 / 环（也用来当圆点：r=0 即实心圆盘）。 */
function circle (cx, cy, r, w = STROKE) {
  const hw = w / 2
  const ext = r + hw + AA
  span(cx - ext, cx + ext, bx)
  span(cy - ext, cy + ext, by)
  for (let py = by[0]; py <= by[1]; py++) {
    const fy = (py + 0.5) / SS
    for (let px = bx[0]; px <= bx[1]; px++) {
      const fx = (px + 0.5) / SS
      const d = Math.abs(Math.hypot(fx - cx, fy - cy) - r)
      const c = ramp(d, hw)
      if (c > 0) put(px, py, c)
    }
  }
}

function dot (cx, cy, d) {
  circle(cx, cy, 0, d)
}

/** 空心圆角矩形：外框 (x,y,w,h)、圆角 r，笔画 strokeW 居中于向内缩半个笔画的骨架上。 */
function roundRect (x, y, w, h, r, strokeW = STROKE) {
  const hw = strokeW / 2
  const halfX = w / 2 - hw
  const halfY = h / 2 - hw
  // 骨架圆角：外框圆角缩半个笔画；若矩形比笔画还窄则归零，SDF 仍能给出实心带
  const cr = Math.max(0, Math.min(r - hw, Math.min(Math.max(halfX, 0), Math.max(halfY, 0))))
  const cx = x + w / 2
  const cy = y + h / 2
  const ext = Math.max(w, h) / 2 + hw + AA
  span(cx - ext, cx + ext, bx)
  span(cy - ext, cy + ext, by)
  for (let py = by[0]; py <= by[1]; py++) {
    const fy = (py + 0.5) / SS
    for (let px = bx[0]; px <= bx[1]; px++) {
      const fx = (px + 0.5) / SS
      const qx = Math.abs(fx - cx) - halfX + cr
      const qy = Math.abs(fy - cy) - halfY + cr
      const sd = Math.min(Math.max(qx, qy), 0) + Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) - cr
      const c = ramp(Math.abs(sd), hw)
      if (c > 0) put(px, py, c)
    }
  }
}

/** 椭圆弧（角度制，0° 指向 +x，顺时针向下）：密采样成折线后逐段画粗线。 */
function arc (cx, cy, rx, ry, a0, a1, w = STROKE) {
  const r = Math.max(rx, ry)
  const sweep = Math.abs(a1 - a0) * (Math.PI / 180)
  // 每段弦长 <= ~0.35px：折线偏离真弧的矢高 < 0.01px，肉眼与降采样都看不出
  const steps = Math.max(2, Math.ceil((r * sweep) / 0.35))
  let px = cx + rx * Math.cos(a0 * (Math.PI / 180))
  let py = cy + ry * Math.sin(a0 * (Math.PI / 180))
  for (let i = 1; i <= steps; i++) {
    const a = (a0 + ((a1 - a0) * i) / steps) * (Math.PI / 180)
    const nx = cx + rx * Math.cos(a)
    const ny = cy + ry * Math.sin(a)
    line(px, py, nx, ny, w)
    px = nx
    py = ny
  }
}

/* ---------- 四个图标（坐标都是 81×81 单位） ---------- */

function drawHome () {
  // 房子：屋顶两斜边在顶点相接（圆头当接头），两道竖墙 + 地板
  line(40.5, 15, 12.5, 39.5)
  line(40.5, 15, 68.5, 39.5)
  line(19.5, 36, 19.5, 66)
  line(61.5, 36, 61.5, 66)
  line(19.5, 66, 61.5, 66)
  // 门：⊓ 形，底边交给地板线，不额外加一条横线免得笔画过重
  line(36, 66, 36, 53)
  line(36, 53, 45, 53)
  line(45, 53, 45, 66)
}

function drawTrain () {
  // 哑铃：中间一根横杆 + 两端两块配重片（竖向圆角矩形）
  line(28.5, 40.5, 52.5, 40.5)
  roundRect(10.5, 17.5, 19, 46, 8.5)
  roundRect(51.5, 17.5, 19, 46, 8.5)
}

function drawCoach () {
  // 机器人：圆角方脑袋 + 两只眼睛 + 头顶一根天线
  roundRect(13.5, 23, 54, 45, 14)
  line(40.5, 26, 40.5, 17.5)
  dot(40.5, 14.5, 7)
  dot(30, 44, 8)
  dot(51, 44, 8)
}

function drawMine () {
  // 人：一个圆脑袋 + 下面一道肩形弧线（弧端略向下翻，读起来像肩而不是半圆）
  circle(40.5, 23.5, 12)
  arc(40.5, 66, 28, 21.5, 176, 364)
}

const ICONS = {
  home: drawHome,
  train: drawTrain,
  coach: drawCoach,
  mine: drawMine,
}

/* ---------- 降采样 ---------- */

/** 4×4 盒式降采样：取 16 个子像素覆盖率的平均值作为最终 alpha。 */
function downsample () {
  const out = new Uint8Array(SIZE * SIZE)
  for (let y = 0; y < SIZE; y++) {
    for (let x = 0; x < SIZE; x++) {
      let sum = 0
      for (let sy = 0; sy < SS; sy++) {
        const row = (y * SS + sy) * BUF + x * SS
        for (let sx = 0; sx < SS; sx++) sum += cov[row + sx]
      }
      const a = Math.round((sum / (SS * SS)) * 255)
      out[y * SIZE + x] = a < 0 ? 0 : a > 255 ? 255 : a
    }
  }
  return out
}

function paint (draw) {
  reset()
  draw()
  return downsample()
}

/* ---------- PNG 编码（color type 6 / bit depth 8，无时间戳所以字节可复现） ---------- */

const CRC_TABLE = (() => {
  const t = new Int32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c
  }
  return t
})()

function crc32 (buf) {
  let c = 0xffffffff
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

function chunk (type, data) {
  const head = Buffer.alloc(4)
  head.writeUInt32BE(data.length, 0)
  const body = Buffer.concat([Buffer.from(type, 'latin1'), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(body), 0)
  return Buffer.concat([head, body, crc])
}

function encodePng (draw, hex) {
  const r = parseInt(hex.slice(1, 3), 16)
  const g = parseInt(hex.slice(3, 5), 16)
  const b = parseInt(hex.slice(5, 7), 16)
  const alpha = paint(draw)
  // 每行前置 filter byte 0（None），RGBA 全图同色，只有 alpha 变化
  const raw = Buffer.alloc(SIZE * (1 + SIZE * 4))
  let o = 0
  for (let y = 0; y < SIZE; y++) {
    raw[o++] = 0
    for (let x = 0; x < SIZE; x++) {
      raw[o++] = r
      raw[o++] = g
      raw[o++] = b
      raw[o++] = alpha[y * SIZE + x]
    }
  }
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(SIZE, 0)
  ihdr.writeUInt32BE(SIZE, 4)
  ihdr[8] = 8 // bit depth
  ihdr[9] = 6 // RGBA
  ihdr[10] = 0 // deflate
  ihdr[11] = 0 // filter
  ihdr[12] = 0 // 非隔行
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

/* ---------- 输出 ---------- */

const outDir = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'static', 'tabbar')
mkdirSync(outDir, { recursive: true })

const results = []
for (const [name, draw] of Object.entries(ICONS)) {
  for (const [suffix, hex] of [['', NORMAL], ['-active', ACTIVE]]) {
    const png = encodePng(draw, hex)
    const file = `${name}${suffix}.png`
    writeFileSync(join(outDir, file), png)
    results.push({ file, bytes: png.length })
  }
}

for (const r of results) console.log(`${r.file.padEnd(18)} ${String(r.bytes).padStart(6)} B`)
console.log(`画布 ${SIZE}×${SIZE} · 超采样 ${SS}× · 笔画 ${STROKE}px · 留白 ~${PAD}px`)
