package com.hfj.fitagent.modules.training.vo;

import java.util.List;

/**
 * 动作字典项（{@code GET /api/exercises} 的响应项，docs/p1-api.md §6）。
 *
 * <p>字典本轮只读：用户不能新增/修改动作，未知动作码也绝不会因为导入而被自动补进字典。
 * {@code sourceUrl}/{@code instructions} 本轮恒为 null——种子数据刻意留空，
 * 不引入未经来源核对的说明文本、也不编造权威链接。
 *
 * @param id 64 位主键以字符串输出，避免 JS 精度丢失
 */
public record ExerciseVo(String id,
                         String exerciseCode,
                         String name,
                         String category,
                         List<String> muscleGroups,
                         List<String> equipment,
                         String sourceUrl,
                         String instructions) {
}
