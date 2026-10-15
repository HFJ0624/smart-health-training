/**
 * 动作字典接口封装（docs/p1-api.md §6，只读，本轮无写接口）。
 */

import { get } from '@/utils/request'
import type { ApiPage } from '@/types/api'
import type { ExerciseItem, ExerciseListQuery } from '@/types/exercise'

/**
 * `GET /api/exercises`：只返回 `status='ACTIVE'`；
 * `keyword` 对 `name`/`exercise_code` 做前缀与包含匹配（服务端转义后走占位符，不拼 SQL）；
 * 排序白名单 `name`，默认 `name,asc`。
 */
export function fetchExercises(query: ExerciseListQuery = {}): Promise<ApiPage<ExerciseItem>> {
  return get<ApiPage<ExerciseItem>>('/exercises', {
    category: query.category || undefined,
    equipment: query.equipment || undefined,
    keyword: query.keyword || undefined,
    page: query.page,
    size: query.size,
    sort: query.sort ?? 'name,asc'
  })
}
