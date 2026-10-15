package com.hfj.fitagent.modules.auth.vo;

import java.time.Instant;

/**
 * `GET /api/me` 的响应：启动时探活用（见 docs/p1-api.md §3.4）。
 *
 * 只暴露账户自身的可见字段。wechat_app_id / wechat_open_id / wechat_union_id 永不出现在此，
 * 数据库 Entity 也不直接作为响应（docs/p1-architecture.md §4 规则 2）。
 *
 * @param userId      user_account.id，BigInt 以字符串出参
 * @param dataRevision 数据修订号，BigInt 以字符串出参；档案/目标/体重/计划变更后由数据库侧递增
 * @param lastLoginAt 最近一次成功登录时间，UTC 瞬时值；从未登录过为 null
 * @param createdAt   账号创建时间，由数据库默认值产生，UTC 瞬时值
 * @param devMode     当前会话是否来自开发登录
 */
public record MeVo(String userId,
                   String nickname,
                   String role,
                   String status,
                   String timezone,
                   String dataRevision,
                   Instant lastLoginAt,
                   Instant createdAt,
                   boolean devMode) {
}
