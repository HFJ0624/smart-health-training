package com.hfj.fitagent.modules.training.dto;

import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /api/plans/{id}/activate}（确认生效）。
 *
 * <p>只需要 {@code lockVersion}：取自上次读取的值，不符即 409 VERSION_CONFLICT。
 * "替换当前 ACTIVE"不是可选语义——事务内先归档旧 ACTIVE 再激活本草稿，
 * 并发激活由 {@code UNIQUE(active_user_id)} 兜底，只胜出一路（docs/p1-scope.md 验收 A12）。
 */
public record ActivateRequest(@NotNull(message = "lockVersion 必填")
                              Integer lockVersion) {
}
