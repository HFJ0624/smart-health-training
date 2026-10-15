/**
 * 档案与目标类型（docs/p1-api.md §4）。
 * 枚举字面量取自 `docs/p1-database.md` §user_profile / §user_goal，契约层大小写敏感（§10.1）。
 */

export type TrainingLevel = 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED'
export type SexForEquation = 'MALE' | 'FEMALE' | 'UNSPECIFIED'
export type GoalType = 'FAT_LOSS' | 'MUSCLE_GAIN' | 'MAINTAIN' | 'GENERAL_FITNESS'
export type GoalStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED'

/**
 * 器械代码词表：与后端 `user_profile.equipment` 共用同一套常量表并保持同步
 * （docs/p1-plan-import.md §9 末尾）。前端展示中文名。
 */
export const EQUIPMENT_CODES = [
  'BARBELL',
  'DUMBBELL',
  'KETTLEBELL',
  'BENCH',
  'CABLE_MACHINE',
  'MACHINE',
  'TREADMILL',
  'BIKE',
  'ROWER',
  'ELLIPTICAL',
  'STAIR_CLIMBER',
  'JUMP_ROPE',
  'MAT',
  'BODYWEIGHT',
  'OUTDOOR',
  'BAND'
] as const

export type EquipmentCode = (typeof EQUIPMENT_CODES)[number]

export const EQUIPMENT_LABELS: Record<EquipmentCode, string> = {
  BARBELL: '杠铃',
  DUMBBELL: '哑铃',
  KETTLEBELL: '壶铃',
  BENCH: '训练凳',
  CABLE_MACHINE: '绳索器械',
  MACHINE: '固定器械',
  TREADMILL: '跑步机',
  BIKE: '动感单车',
  ROWER: '划船机',
  ELLIPTICAL: '椭圆机',
  STAIR_CLIMBER: '楼梯机',
  JUMP_ROPE: '跳绳',
  MAT: '瑜伽垫',
  BODYWEIGHT: '自重',
  OUTDOOR: '户外',
  BAND: '弹力带'
}

export const TRAINING_LEVEL_OPTIONS: Array<{ value: TrainingLevel; label: string }> = [
  { value: 'BEGINNER', label: '新手（BEGINNER）' },
  { value: 'INTERMEDIATE', label: '中级（INTERMEDIATE）' },
  { value: 'ADVANCED', label: '进阶（ADVANCED）' }
]

export const SEX_OPTIONS: Array<{ value: SexForEquation; label: string }> = [
  { value: 'UNSPECIFIED', label: '不指定（默认）' },
  { value: 'MALE', label: '男' },
  { value: 'FEMALE', label: '女' }
]

export const GOAL_TYPE_OPTIONS: Array<{ value: GoalType; label: string }> = [
  { value: 'FAT_LOSS', label: '减脂' },
  { value: 'MUSCLE_GAIN', label: '增肌' },
  { value: 'MAINTAIN', label: '维持体重' },
  { value: 'GENERAL_FITNESS', label: '综合体能' }
]

export function equipmentLabel(code: string): string {
  return (EQUIPMENT_LABELS as Record<string, string>)[code] ?? code
}

export function trainingLevelLabel(level: string | null | undefined): string {
  const hit = TRAINING_LEVEL_OPTIONS.find((o) => o.value === level)
  return hit ? hit.label : level ?? '未设置'
}

export function sexLabel(sex: string | null | undefined): string {
  const hit = SEX_OPTIONS.find((o) => o.value === sex)
  return hit ? hit.label : sex ?? '未设置'
}

export function goalTypeLabel(type: string | null | undefined): string {
  const hit = GOAL_TYPE_OPTIONS.find((o) => o.value === type)
  return hit ? hit.label : type ?? '未设置'
}

/** 目标状态的中文口径（值域 = `Vocabulary.GOAL_STATUS`，迁移里的 ENUM 三值）。 */
export const GOAL_STATUS_LABELS: Record<GoalStatus, string> = {
  DRAFT: '草稿',
  ACTIVE: '生效中',
  ARCHIVED: '已归档'
}

export function goalStatusLabel(status: string | null | undefined): string {
  const hit = (GOAL_STATUS_LABELS as Record<string, string>)[status ?? '']
  return hit ?? status ?? '未知状态'
}

/**
 * 档案（`GET/POST/PUT /api/me/profile`，§4.1–§4.2）。
 * 本轮不开放写入的字段（`activityCategory`/`dietPreferences`/`professionalGuidanceRequired`）
 * 在类型里不出现——不录入无人使用的数据（§4.2、§2 范围外清单）。
 */
export interface UserProfile {
  id: string
  userId: string
  /** DECIMAL(6,2)：>0、≤2 位小数；无医学阈值 */
  heightCm: number
  trainingLevel: TrainingLevel
  /** 1–16 项，代码必须在 EQUIPMENT_CODES 内 */
  equipment: EquipmentCode[]
  birthDate: string | null
  sexForEquation: SexForEquation
  /** 字符串**数组**（落库为 JSON 数组），不是单条长文本（docs/p1-api.md §4.2） */
  trainingConstraints: string[] | null
  /** 后端 `ProfileVo` 无此字段（昵称属 `user_account`），运行时应为 undefined，故标可选 */
  nickname?: string | null
  /**
   * 本轮「只读不写」的三列：请求 DTO 里没有它们（写了即 `UNKNOWN_FIELD`），
   * 但 `ProfileVo` 会如实回显已有值，所以类型上声明为可选只读字段。
   */
  activityCategory?: string | null
  dietPreferences?: string[] | null
  professionalGuidanceRequired?: boolean | null
  /** UTC 瞬时值，只读 */
  createdAt?: string | null
  updatedAt?: string | null
  /** 乐观锁：更新必须回传上次读取的值（§1「乐观锁」） */
  lockVersion: number
}

/** 表单可写部分：创建与修改共用。 */
export interface UserProfileForm {
  heightCm: number
  trainingLevel: TrainingLevel
  equipment: EquipmentCode[]
  birthDate: string | null
  sexForEquation: SexForEquation
  /** 字符串**数组**（落库为 JSON 数组），不是单条长文本（docs/p1-api.md §4.2） */
  trainingConstraints: string[] | null
  /**
   * 契约文档 §4.2 的请求示例带了 `nickname`，但后端 `UpsertProfileRequest`（record）里**没有**该组件，
   * 而全局 `FAIL_ON_UNKNOWN_PROPERTIES` 会把任何多余键判成 422 `UNKNOWN_FIELD`
   * （已核对 `backend/.../modules/profile/dto/UpsertProfileRequest.java` 与 `config/JacksonConfig.java`）。
   * 昵称归属 `user_account`，只在 `GET /api/me` 出参里可见，档案表单不提交它，因此这里降级为可选且永不写入。
   */
  nickname?: string | null
}

/** `POST /api/me/profile`：无 lockVersion。 */
export type ProfileCreateRequest = UserProfileForm

/** `PUT /api/me/profile`：必须带 lockVersion；并发首次创建会得到 409 → 改为读取后 PUT（§4.2）。 */
export interface ProfileUpdateRequest extends UserProfileForm {
  lockVersion: number
}

/** 目标（`/api/me/goals`，§4.3）。 */
export interface UserGoal {
  id: string
  userId: string
  goalType: GoalType
  targetWeightKg: number | null
  weeklySessionsTarget: number | null
  startsOn: string
  endsOn: string | null
  status: GoalStatus
  lockVersion: number
  /** 列保留但本轮恒为 null，页面不展示（§4.3 说明） */
  dailyIntakeTargetKcal: number | null
  /** 后端 `GoalVo` 附带的只读时间戳（列表默认按 `createdAt,desc` 排序，展示创建时间有意义） */
  createdAt?: string | null
  updatedAt?: string | null
}

/** `POST /api/me/goals` 请求。`activate` 默认 true，激活时事务内归档旧 ACTIVE。 */
export interface GoalCreateRequest {
  goalType: GoalType
  targetWeightKg: number | null
  weeklySessionsTarget: number | null
  startsOn: string
  endsOn: string | null
  activate: boolean
}

/** `PUT /api/me/goals/{id}` 请求：DRAFT/ACTIVE 可改，ARCHIVED 不可改。 */
export interface GoalUpdateRequest {
  goalType: GoalType
  targetWeightKg: number | null
  weeklySessionsTarget: number | null
  startsOn: string
  endsOn: string | null
  lockVersion: number
}

export interface GoalListQuery {
  status?: GoalStatus | ''
  page?: number
  size?: number
  sort?: string
}
