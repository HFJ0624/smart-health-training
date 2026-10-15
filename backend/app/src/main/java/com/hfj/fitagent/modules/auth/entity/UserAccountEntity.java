package com.hfj.fitagent.modules.auth.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * user_account 的数据库映射。
 *
 * created_at / updated_at / data_revision 一律由数据库或显式 SQL 维护，
 * 插入与更新策略设为 NEVER，避免 ORM 把应用时钟写进去造成与数据库不一致。
 * 本类不作为任何接口响应体（对外用 vo）。
 */
@TableName("user_account")
@Getter
@Setter
public class UserAccountEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String wechatAppId;

    private String wechatOpenId;

    private String wechatUnionId;

    private String nickname;

    /** USER / ADMIN */
    private String role;

    /** ACTIVE / DISABLED / DELETION_PENDING */
    private String status;

    private String timezone;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long dataRevision;

    private Instant lastLoginAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
