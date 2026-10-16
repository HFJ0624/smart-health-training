<template>
  <view class="fa-page pi">
    <!-- 图标步骤条替代原来的整段说明文字（docs/ui-render-plan.md §2「导入」行）：
         三步各一枚图标 + 两字标签，进行到哪一步仍用**文字**写在下面，状态不只靠颜色 -->
    <view class="pi-steps">
      <view v-for="s in stepItems" :key="s.key" class="pi-step">
        <FaIcon :name="s.icon" :tone="s.tone" size="48rpx" />
        <text class="pi-step__label">{{ s.label }}</text>
        <text class="pi-step__note">{{ s.note }}</text>
      </view>
    </view>

    <!-- 两条入口并列，一个图标一个落点：内容都写进同一个 jsonText，走同一套后端校验与预览 -->
    <view class="fa-tiles">
      <FaIconTile
        icon="upload"
        label="选文件"
        :hint="fileBusy ? '读取中…' : '.json 文件'"
        :disabled="fileBusy || previewing"
        @tap="onChooseFile"
      />
      <FaIconTile icon="edit" label="粘贴" :hint="pasteOpen ? '文本框已展开' : 'JSON 文本'" @tap="pasteOpen = true" />
    </view>

    <!-- 编码 / 上限 / 草稿口径：关键项默认可见，只有「怎么把文件弄进微信」这段长流程折起来 -->
    <view class="fa-card">
      <view class="fa-banner fa-banner--info">
        <text class="fa-prose">
          上限 {{ IMPORT_LIMITS.maxBytes / 1024 }} KiB（按 UTF-8 字节数计）；两条入口提交完全相同的内容，走同一套后端校验与预览；导入只会生成「草稿」，不会自动生效。
        </text>
      </view>
      <text class="fa-hint">模板路径：<text class="pi-path">{{ PLAN_TEMPLATE_PATH }}</text></text>
      <view class="fa-btn fa-btn--sm pi-toggle" @tap="showGuide = !showGuide">
        <FaIcon :name="showGuide ? 'chevron' : 'records'" tone="accent" size="36rpx" />
        <text>{{ showGuide ? '收起' : '文件从哪来' }}</text>
      </view>
      <text v-if="showGuide" class="fa-prose pi-guide__body">
        微信小程序不能浏览手机里的任意本地文件。用「选文件」前，请先把上面的模板发送到一个微信聊天或「文件传输助手」，
        再在这里选择它；也可以直接点「粘贴」把 JSON 贴进文本框。
      </text>
    </view>

    <!-- 入口 A 的结果：文件名与读取失败原因保持原文（readFile 的降级说明靠这些字，不靠颜色） -->
    <view v-if="fileName || fileNote" class="fa-card">
      <text v-if="fileName" class="fa-muted pi-filemeta">
        已选：{{ fileName }}（{{ formatBytes(byteSize) }}，{{ IMPORT_LIMITS.maxBytes / 1024 }} KiB 上限内）
      </text>
      <text v-if="fileNote" class="pi-warn fa-prose">{{ fileNote }}</text>
    </view>

    <!-- 两条入口共用的编辑面：点「粘贴」或已经有内容才展开，写的始终是同一个 jsonText（业务逻辑未动） -->
    <view v-if="showTextarea" class="fa-card">
      <view class="fa-field__label"><text>JSON 内容</text></view>
      <textarea
        class="fa-textarea pi-textarea"
        :value="jsonText"
        :placeholder="pastePlaceholder"
        @input="onPasteInput"
      />
      <text class="fa-muted pi-textmeta">当前 {{ formatBytes(byteSize) }} · 上限 {{ IMPORT_LIMITS.maxBytes / 1024 }} KiB · 需 UTF-8 文本</text>
    </view>

    <view class="pi-actions fa-btn-row">
      <view class="fa-btn fa-btn--primary" :class="{ 'fa-btn--disabled': !canPreview || previewing || confirming }" @tap="runPreview">
        <FaIcon name="shield" tone="text" size="40rpx" />
        <text>{{ previewing ? '校验中…' : '校验' }}</text>
      </view>
      <view class="fa-btn" @tap="resetAll">
        <FaIcon name="refresh" tone="sub" size="40rpx" />
        <text>清空</text>
      </view>
    </view>

    <text v-if="localJsonError" class="fa-banner fa-banner--error pi-localerr">{{ localJsonError }}</text>

    <!-- 预览 + 错误定位 -->
    <StateBlock
      :state="previewState"
      :failure="hardFailure"
      :submit-failure="confirmFailure"
      :loading-text="previewing ? '正在后端校验…' : '正在确认保存…'"
      empty-title="还没有可预览的内容"
      empty-desc="选文件或粘贴 JSON 后点「校验」。没有错误才能「存草稿」。"
      @retry="runPreview"
    >
      <!-- 逐字段错误清单与规范化预览由组件原样渲染，一个 path 都不省 -->
      <ImportPreviewList :plan="previewPlan" :errors="previewErrors" :warnings="previewWarnings" />

      <view v-if="previewStats" class="fa-card">
        <view class="fa-metrics">
          <FaMetric class="fa-metric" label="计划日" :value="previewStats.dayCount" unit="天" :caption="`动作 ${previewStats.itemCount} 个 · ${formatBytes(previewStats.byteSize)}`" />
          <FaMetric class="fa-metric" label="错误" :value="previewStats.errorCount" unit="处" :caption="`警告 ${previewStats.warningCount} 条（不阻断保存）`" />
        </view>
      </view>

      <!-- 确认保存：errors 非空即禁用；幂等键在同一次确认的重试间保持稳定 -->
      <view class="pi-confirm">
        <view class="fa-btn fa-btn--primary fa-btn--block" :class="{ 'fa-btn--disabled': !canConfirm || confirming }" @tap="confirm">
          <FaIcon name="records" tone="text" size="40rpx" />
          <text>{{ confirming ? '保存中…' : '存草稿' }}</text>
        </view>
        <text class="fa-muted pi-confirm__tip">
          {{ previewErrors.length > 0 ? `还有 ${previewErrors.length} 处错误，修正后才能保存。` : '确认后会重新完整校验，成功后进入草稿详情；期间不会自动生效。' }}
        </text>
        <text v-if="idempotencyKey" class="fa-muted pi-key">Idempotency-Key：{{ idempotencyKey }}</text>
      </view>
    </StateBlock>
  </view>
</template>

<script setup lang="ts">
/**
 * 计划导入页（docs/p1-plan-import.md §1、§10）。两条入口——
 *  (a) 从微信会话选文件：`wx.chooseMessageFile({count:1,type:'file',extension:['json']})` → `getFileSystemManager().readFile`
 *      —— `readFile` 无 Promise 形式，用回调包；整段只在微信端的条件编译分支里执行。
 *      ⚠ 这条注释里**不要复述条件编译指令的原文**：uni-app 的预处理器是按纯文本扫的，
 *      注释里出现一个孤立指令就会让整个文件被判成「配对不完整」，构建时静默按错误分支裁剪代码。
 *      其它平台走明确降级说明那条路径。
 *  (b) 粘贴 JSON 文本。
 * 两条都喂给 `POST /api/plans/import/preview`（无副作用），用 `ImportPreviewList` 渲染规范化预览 + 逐字段错误；
 * `errors` 非空即禁用「确认保存」。确认调 `POST /api/plans/import`，带 `Idempotency-Key`（uuidV4），
 * **同一次确认的重试复用同一个键**（新预览才换新键）；成功后跳草稿详情。
 * preview 无副作用、confirm 重新完整校验；本页面把 preview 与 confirm 的失败都当作可展示的「提交失败」态。
 *
 * UI 重排：说明性长句换成图标步骤条 + 并列的两个入口（选文件 / 粘贴），
 * 而逐字段错误清单（ImportPreviewList）、256 KiB 上限、UTF-8 编码口径与「只出 DRAFT」的说明一项都没省。
 */
import { computed, ref } from 'vue'
import { inputValue } from '@/utils/events'
import FaIcon from '@/components/FaIcon.vue'
import FaIconTile from '@/components/FaIconTile.vue'
import FaMetric from '@/components/FaMetric.vue'
import StateBlock from '@/components/StateBlock.vue'
import ImportPreviewList from '@/components/ImportPreviewList.vue'
import { confirmPlanImport, previewPlanImport } from '@/api/training'
import { isApiError } from '@/utils/request'
import { toFailureView, type FailureView } from '@/utils/errorMessage'
import { uuidV4 } from '@/utils/uuid'
import { formatBytes, utf8ByteLength } from '@/utils/units'
import type { FaIconName, FaIconTone } from '@/styles/icon-names'
import {
  IMPORT_LIMITS,
  PLAN_SCHEMA_VERSION,
  PLAN_TEMPLATE_PATH,
  type ImportIssue,
  type ImportNormalizedPlan,
  type ImportPreviewResult
} from '@/types/plan-import'
import type { PageState } from '@/types/api'

const pastePlaceholder = `把计划 JSON 粘贴到这里（schema_version 需为 ${PLAN_SCHEMA_VERSION}）`

const jsonText = ref('')
const fileName = ref('')
const fileNote = ref('')
const fileBusy = ref(false)

const previewing = ref(false)
const confirming = ref(false)
const hasPreviewed = ref(false)

const previewPlan = ref<ImportNormalizedPlan | null>(null)
const previewErrors = ref<ImportIssue[]>([])
const previewWarnings = ref<ImportIssue[]>([])
const previewStats = ref<{ byteSize: number; dayCount: number; itemCount: number; errorCount: number; warningCount: number } | null>(null)

const hardFailure = ref<FailureView | null>(null)
const confirmFailure = ref<FailureView | null>(null)
const localJsonError = ref('')
const idempotencyKey = ref('')

const byteSize = computed(() => utf8ByteLength(jsonText.value))

const canPreview = computed(() => jsonText.value.trim().length > 0)

const previewState = computed<PageState>(() => {
  if (previewing.value || confirming.value) return 'loading'
  if (hardFailure.value && hardFailure.value.sessionExpired) return 'sessionExpired'
  if (hardFailure.value) return 'error'
  if (!hasPreviewed.value) return 'empty'
  return 'success'
})

/** errors 非空即不能确认（docs/p1-plan-import.md §8）；确认按钮据此禁用。 */
const canConfirm = computed(
  () => hasPreviewed.value && !previewing.value && !confirming.value && !hardFailure.value && previewErrors.value.length === 0 && jsonText.value.trim().length > 0
)

/* ---------- 以下全是纯展示派生：不新增请求、不新增状态位，只把已有的 ref 组合成步骤条 ---------- */
const pasteOpen = ref(false)
const showGuide = ref(false)

/** 粘贴面按需展开；选文件读进来的内容同样要看得见、改得动，所以有内容就展开（jsonText 的读写逻辑没变）。 */
const showTextarea = computed(() => pasteOpen.value || jsonText.value.trim().length > 0)

/** 步骤条的每一步都写成文字，不允许"只有颜色在说话"（色觉障碍与屏幕取反下仍读得懂）。 */
const stepItems = computed<Array<{ key: string; label: string; icon: FaIconName; tone: FaIconTone; note: string }>>(() => {
  const hasContent = canPreview.value
  const errorCount = previewErrors.value.length
  return [
    {
      key: 'content',
      label: '取内容',
      icon: 'upload',
      tone: hasContent ? 'success' : 'accent',
      note: hasContent ? `已填入 ${formatBytes(byteSize.value)}` : '选文件或粘贴'
    },
    {
      key: 'preview',
      label: '校验',
      icon: 'shield',
      tone: errorCount > 0 && hasPreviewed.value ? 'danger' : hasPreviewed.value ? 'success' : 'sub',
      note: previewing.value
        ? '正在校验…'
        : !hasContent
          ? '待填内容'
          : hasPreviewed.value
            ? errorCount > 0
              ? `${errorCount} 处错误`
              : '已通过'
            : '待校验'
    },
    {
      key: 'draft',
      label: '存草稿',
      icon: 'records',
      tone: canConfirm.value ? 'accent' : 'sub',
      note: confirming.value ? '正在保存…' : canConfirm.value ? '可保存' : '校验通过后开放'
    }
  ]
})

function onPasteInput(evt: Event): void {
  jsonText.value = inputValue(evt)
  fileNote.value = ''
}

function resetAll(): void {
  // 确认进行中不能清空：请求已经发出去了，清掉原文会让结果页与本地状态对不上
  if (confirming.value) {
    uni.showToast({ title: '正在保存草稿，请稍候', icon: 'none' })
    return
  }
  jsonText.value = ''
  fileName.value = ''
  fileNote.value = ''
  previewPlan.value = null
  previewErrors.value = []
  previewWarnings.value = []
  previewStats.value = null
  hardFailure.value = null
  confirmFailure.value = null
  localJsonError.value = ''
  idempotencyKey.value = ''
  hasPreviewed.value = false
}

/** 把后端 `data.errors/warnings` 读成 ImportIssue[]（confirm 失败与 preview 失败同形）。 */
function issuesOf(raw: unknown): ImportIssue[] {
  if (!Array.isArray(raw)) return []
  return raw
    .filter((x): x is Record<string, unknown> => typeof x === 'object' && x !== null)
    .map((x) => ({
      path: typeof x.path === 'string' ? x.path : '',
      code: typeof x.code === 'string' ? x.code : String(x.code ?? ''),
      message: typeof x.message === 'string' ? x.message : String(x.message ?? ''),
      value: x.value
    }))
}

function planOf(raw: unknown): ImportNormalizedPlan | null {
  if (raw && typeof raw === 'object' && 'plan' in raw) {
    const p = (raw as { plan?: unknown }).plan
    if (p && typeof p === 'object') return p as ImportNormalizedPlan
  }
  return null
}

function applyPreview(res: ImportPreviewResult): void {
  previewPlan.value = res.plan ?? null
  previewErrors.value = res.errors ?? []
  previewWarnings.value = res.warnings ?? []
  const days = res.plan?.days ?? []
  const itemCount = days.reduce((s, d) => s + (d.items?.length ?? 0), 0)
  previewStats.value = {
    byteSize: res.byteSize ?? byteSize.value,
    dayCount: res.dayCount ?? days.length,
    itemCount: res.itemCount ?? itemCount,
    errorCount: res.errorCount ?? (res.errors?.length ?? 0),
    warningCount: res.warningCount ?? (res.warnings?.length ?? 0)
  }
}

async function runPreview(): Promise<void> {
  if (!canPreview.value || previewing.value) return
  previewing.value = true
  hardFailure.value = null
  confirmFailure.value = null
  localJsonError.value = ''

  // 本地字节上限自检（服务端仍是权威，§8：超 256 KiB 直接 PAYLOAD_TOO_LARGE）
  if (byteSize.value > IMPORT_LIMITS.maxBytes) {
    localJsonError.value = `内容约 ${formatBytes(byteSize.value)}，超过 ${IMPORT_LIMITS.maxBytes / 1024} KiB 上限，请精简（§8）。`
    previewing.value = false
    return
  }
  // 本地先解析一次，语法错立刻反馈（§3.5「前端本地 JSON.parse 失败即提示」）
  try {
    JSON.parse(jsonText.value)
  } catch (err) {
    localJsonError.value = `不是合法的 JSON：${err instanceof Error ? err.message : String(err)}（MALFORMED_JSON）`
    previewing.value = false
    return
  }

  try {
    const res = await previewPlanImport(jsonText.value)
    applyPreview(res)
    // 一次新的预览 = 一次新的「确认意图」→ 换新幂等键，之后重试沿用
    idempotencyKey.value = ''
    hasPreviewed.value = true
  } catch (err) {
    const view = toFailureView(err)
    if (view.code === 'PLAN_IMPORT_INVALID' && isApiError(err)) {
      // preview 结构/字段校验失败：把定位错误渲染出来，规范化 plan 可能为 null
      previewPlan.value = planOf(err.data)
      previewErrors.value = issuesOf((err.data as { errors?: unknown } | null)?.errors)
      previewWarnings.value = issuesOf((err.data as { warnings?: unknown } | null)?.warnings)
      previewStats.value = null
      hasPreviewed.value = true
    } else {
      // 网络/会话/其它：整体失败态
      hardFailure.value = view
      previewPlan.value = null
      previewErrors.value = []
      previewWarnings.value = []
      previewStats.value = null
      hasPreviewed.value = false
    }
  } finally {
    previewing.value = false
  }
}

/** 确认保存：键在同一次确认的重试间保持稳定（A17）。 */
async function confirm(): Promise<void> {
  if (!canConfirm.value) return
  if (!idempotencyKey.value) idempotencyKey.value = uuidV4()
  confirming.value = true
  confirmFailure.value = null
  try {
    const res = await confirmPlanImport(jsonText.value, idempotencyKey.value)
    // 契约（docs/p1-plan-import.md §10）承诺导入只出 DRAFT，绝不激活；不符就是后端行为变了
    if (res.status !== 'DRAFT') {
      uni.showToast({ title: `导入返回了非草稿状态 ${res.status}，已停止跳转`, icon: 'none' })
      confirmFailure.value = {
        title: '导入结果与契约不符',
        detail: `服务端返回状态 ${res.status}，本轮契约只允许 DRAFT。`,
        code: 'CLIENT_CONTRACT_MISMATCH',
        traceId: null,
        fields: [],
        importErrors: [],
        importWarnings: [],
        sessionExpired: false,
        versionConflict: false,
        invalidTransition: false,
        activePlanExists: false,
        retryAfter: null
      }
      return
    }
    uni.showToast({ title: res.idempotentReplay ? '已复用首次导入结果' : '已保存为草稿', icon: 'none' })
    uni.redirectTo({ url: `/pages/plan/detail?id=${encodeURIComponent(res.planId)}` })
  } catch (err) {
    const view = toFailureView(err)
    confirmFailure.value = view
    // 服务端重新完整校验失败：更新预览错误清单（不信任 preview，§10）
    if (view.code === 'PLAN_IMPORT_INVALID' && isApiError(err)) {
      previewPlan.value = planOf(err.data)
      previewErrors.value = issuesOf((err.data as { errors?: unknown } | null)?.errors)
      previewWarnings.value = issuesOf((err.data as { warnings?: unknown } | null)?.warnings)
    }
    // 保留 idempotencyKey：失败重试沿用同一个键（幂等）
  } finally {
    confirming.value = false
  }
}

// 微信会话选文件：所有 wx.* 调用都包在本函数的微信端条件编译分支内；readFile 回调包 Promise（§1）
function onChooseFile(): void {
  // :class 的禁用只改透明度，<view> 仍然可点：必须在这里挡住，否则会叠出多个选文件回调
  if (fileBusy.value || previewing.value || confirming.value) return
  // #ifdef MP-WEIXIN
  const wx = (globalThis as unknown as {
    wx?: {
      chooseMessageFile(opt: {
        count: number
        type: 'file'
        extension?: string[]
        success: (res: { tempFiles?: Array<{ path: string; name?: string; size?: number }> }) => void
        fail: (err: { errMsg?: string }) => void
      }): void
      getFileSystemManager(): {
        readFile(opt: {
          filePath: string
          encoding?: 'utf8'
          success: (res: { data?: string | ArrayBuffer }) => void
          fail: (err: { errMsg?: string }) => void
        }): void
      }
    }
  }).wx

  if (!wx || typeof wx.chooseMessageFile !== 'function') {
    uni.showModal({ title: '当前环境不支持', content: '未能调用微信 chooseMessageFile，请改用「粘贴 JSON」。', showCancel: false })
    return
  }

  fileBusy.value = true
  fileNote.value = ''
  wx.chooseMessageFile({
    count: 1,
    type: 'file',
    extension: ['json'],
    success: (chosen) => {
      const file = chosen.tempFiles && chosen.tempFiles[0]
      if (!file) {
        fileBusy.value = false
        fileNote.value = '未选择文件。'
        return
      }
      // readFile 没有 Promise 形式：用回调包装
      wx.getFileSystemManager().readFile({
        filePath: file.path,
        encoding: 'utf8',
        success: (read) => {
          fileBusy.value = false
          const text = typeof read.data === 'string' ? read.data : ''
          if (!text) {
            fileNote.value = '文件内容为空或不是 UTF-8 文本。'
            return
          }
          jsonText.value = text
          fileName.value = file.name || '从会话选择的文件'
          void runPreview()
        },
        fail: (e) => {
          fileBusy.value = false
          fileNote.value = `读取文件失败：${e && e.errMsg ? e.errMsg : '未知错误'}`
        }
      })
    },
    fail: (e) => {
      fileBusy.value = false
      const msg = e && e.errMsg ? e.errMsg : ''
      if (!/cancel/i.test(msg)) fileNote.value = `选择文件失败：${msg}`
    }
  })
  // #endif

  // #ifndef MP-WEIXIN
  uni.showModal({
    title: '此平台不能选文件',
    content: '只有微信小程序能从聊天选择文件（其它平台 uni 不提供 chooseMessageFile）。请改用「粘贴 JSON」，或在微信中打开本小程序。',
    showCancel: false
  })
  // #endif
}
</script>

<style lang="scss" scoped>
/* 图标步骤条：三块同级的浅色卡，替代原来的整段说明文字（不是卡片套卡片） */
.pi-steps {
  display: flex;
  align-items: stretch;
  gap: $fa-space-grid;
  margin-bottom: $fa-space-section;
}

.pi-step {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8rpx;
  padding: $fa-space-card;
  border-radius: $fa-radius;
  background-color: $fa-surface;
  border: 1px solid $fa-line;
}

.pi-step__label {
  font-size: $fa-font-ui-lg;
  font-weight: 600;
  color: $fa-text;
}

.pi-step__note {
  font-size: $fa-font-ui;
  line-height: 1.5;
  color: $fa-text-sub;
  word-break: break-all;
}

.pi-path {
  color: $fa-accent; /* 强调文字用深金，淡黄做前景不达标 */
  font-weight: 600;
  word-break: break-all;
}

.pi-toggle {
  margin-top: $fa-space-tight;
  width: 240rpx;
}

.pi-guide__body {
  display: block;
  margin-top: $fa-space-tight;
}

.pi-filemeta,
.pi-textmeta {
  display: block;
  margin-top: $fa-space-tight;
  word-break: break-all;
}

/* 读取失败/未选择这类原因必须成行显示：色值只加强，不承载信息 */
.pi-warn {
  display: block;
  margin-top: $fa-space-tight;
  color: $fa-danger;
}

.pi-textarea {
  margin-top: 12rpx;
  min-height: 320rpx;
}

.pi-actions {
  margin-bottom: $fa-space-section;
}

.pi-localerr {
  display: block;
  margin-bottom: $fa-space-section;
}

.pi-confirm {
  margin-top: $fa-space-section;
}

.pi-confirm__tip {
  display: block;
  margin-top: $fa-space-tight;
}

.pi-key {
  display: block;
  margin-top: 8rpx;
  word-break: break-all;
}
</style>
