package com.hfj.fitagent.modules.training.dto;

import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /api/plans/{id}/discard}（丢弃草稿）。
 *
 * <p>仅 DRAFT 可丢弃，动作是把状态置为 DISCARDED 并<b>保留全部行</b>：
 * 计划版本一旦允许物理删除，"版本不可覆盖"就不再可证明（docs/p1-api.md §7）。
 */
public record DiscardRequest(@NotNull(message = "lockVersion 必填")
                             Integer lockVersion) {
}
