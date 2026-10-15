package com.hfj.fitagent.modules.modelaccess.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * model_provider：模型供应商白名单（V10）。
 *
 * <p>本表是<b>系统配置</b>，运维写入，用户只读：实体上没有任何"可写入"的接口路径，
 * {@code baseUrl} 尤其如此——外呼地址若由输入决定，服务端就变成 SSRF 跳板（V10 注释与设计 §7）。
 *
 * <p>{@code status} 声明为 String 而非 Java enum：枚举合法性由 {@code Vocabulary} 显式判定，
 * 不依赖 MySQL ENUM 的大小写不敏感比较。
 *
 * <p>本表没有 lock_version（不可变配置由运维直接改库，改错靠审计而不是乐观锁）。
 */
@TableName("model_provider")
@Getter
@Setter
public class ModelProviderEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 供应商标识码，服务端规范化大写后匹配（如 DEEPSEEK）。 */
    private String providerCode;

    private String displayName;

    /** 外呼基地址；只由运维写入。 */
    private String baseUrl;

    /** FALSE 时余额网关不外呼（把不支持的能力标成可用会产出一张永远失败的按钮）。 */
    private Boolean balanceSupported;

    /** 适配器代码，决定用哪个协议实现，不由用户选择。 */
    private String adapterCode;

    /** ACTIVE / DISABLED。 */
    private String status;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
