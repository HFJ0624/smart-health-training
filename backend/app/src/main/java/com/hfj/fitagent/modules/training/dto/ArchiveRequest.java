package com.hfj.fitagent.modules.training.dto;

import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /api/plans/{id}/archive}（归档当前生效计划）。
 *
 * <p>仅 ACTIVE 可归档；归档后首页显示"未设置计划"。状态迁移同样要带 {@code lockVersion}，
 * 否则两个页面同时点归档会把第二次变成对已归档行的无效写。
 */
public record ArchiveRequest(@NotNull(message = "lockVersion 必填")
                             Integer lockVersion) {
}
