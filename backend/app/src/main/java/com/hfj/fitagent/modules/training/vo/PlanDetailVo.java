package com.hfj.fitagent.modules.training.vo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 计划版本详情：与 {@code POST/PUT /api/plans} 的入参同形的完整聚合
 * （头 + days[] + items[]，docs/p1-api.md §7）。
 *
 * <p>读写同形是刻意的：页面把详情对象改一改再提交，不需要维护"读取模型"与"写入模型"两套结构，
 * 而这两套结构不一致正是"编辑后字段丢失"这类缺陷的来源。
 *
 * <p>{@code source} 与 {@code status} 只读：写入路径由服务端决定
 * （新建/导入恒为 DRAFT，source 为 MANUAL/IMPORT），客户端无法通过本结构把计划直接置为 ACTIVE。
 */
public record PlanDetailVo(String id,
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
                           Instant updatedAt,
                           GoalSnapshotVo goalSnapshot,
                           List<PlanDayVo> days) {
}
