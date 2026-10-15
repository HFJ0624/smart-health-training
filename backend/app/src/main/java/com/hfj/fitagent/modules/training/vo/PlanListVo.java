package com.hfj.fitagent.modules.training.vo;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 计划版本列表项（{@code GET /api/plans}，docs/p1-api.md §7）——只有版本头摘要，不含 days/items。
 *
 * <p>列表页按 {@code planGroupUuid} 分组展示，因此该字段与 {@code revisionNo}、{@code basePlanId}
 * 必须一起给出：页面要能还原"这条是哪个版本的第几版、从哪一版改来的"。
 *
 * <p>全部 64 位 id 以字符串输出；{@code startsOn}/{@code endsOn} 是业务日期（YYYY-MM-DD），
 * {@code createdAt}/{@code updatedAt}/{@code confirmedAt} 是 UTC 瞬时值，两组字段不混用。
 */
public record PlanListVo(String id,
                         String userId,
                         String planGroupUuid,
                         Integer revisionNo,
                         String basePlanId,
                         String title,
                         String source,
                         String status,
                         LocalDate startsOn,
                         LocalDate endsOn,
                         Instant confirmedAt,
                         Integer lockVersion,
                         Instant createdAt,
                         Instant updatedAt) {
}
