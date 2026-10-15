/**
 * 动作字典类型（docs/p1-api.md §6，只读）。
 * 本轮没有写接口：字典维护（role=ADMIN）属后续阶段。
 */

/** 动作类别：决定 §5 的「类型 × 字段矩阵」用哪一套校验（docs/p1-plan-import.md §5）。 */
export type ExerciseCategory = 'STRENGTH' | 'CARDIO' | 'MOBILITY'

export const CATEGORY_OPTIONS: Array<{ value: ExerciseCategory; label: string }> = [
  { value: 'STRENGTH', label: '力量（STRENGTH）' },
  { value: 'CARDIO', label: '有氧（CARDIO）' },
  { value: 'MOBILITY', label: '柔韧（MOBILITY）' }
]

export function categoryLabel(category: string | null | undefined): string {
  const hit = CATEGORY_OPTIONS.find((o) => o.value === category)
  return hit ? hit.label : category ?? '未分类'
}

/**
 * 响应项：`{ id, exerciseCode, name, category, muscleGroups[], equipment[], sourceUrl: null, instructions: null }`。
 * `source_url`/`instructions` 本轮恒为 null（种子脚本留空，不伪造权威链接，docs/p1-plan-import.md §9）。
 */
export interface ExerciseItem {
  id: string
  exerciseCode: string
  name: string
  category: ExerciseCategory
  muscleGroups: string[]
  equipment: string[]
  sourceUrl: string | null
  instructions: string | null
}

/** `GET /api/exercises` 查询参数；只返回 `status='ACTIVE'`，排序白名单 `name`（§6）。 */
export interface ExerciseListQuery {
  category?: ExerciseCategory | ''
  equipment?: string
  keyword?: string
  page?: number
  size?: number
  sort?: string
}
