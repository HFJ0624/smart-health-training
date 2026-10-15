package com.hfj.fitagent.modules.modelaccess.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * provider_balance_snapshot：供应商账户级余额快照（V14）。
 *
 * <p>三条语义都写在这里以免被实现带偏：
 * <ol>
 *   <li>三个金额列可空且 {@code updateStrategy = ALWAYS}，<b>NULL 表示"供应商没给这个分项"</b>，
 *       与 0（"确实没钱了"）完全不同；查询失败不落新行，因此"上次值 + 陈旧标记"永不被 0 覆盖。</li>
 *   <li>官方字段是 {@code is_available}（Boolean，可为 NULL），<b>没有</b> {@code is_expired}
 *       （协议 §8 已更正旧设计包），陈旧由 fetched_at 推导，不是库存列。</li>
 *   <li>一个 balance_infos[] 元素一行，唯一键含 currency；跨币种永不相减。</li>
 * </ol>
 *
 * <p>本表只插不改（快照不可变），因此没有 lock_version。
 */
@TableName("provider_balance_snapshot")
@Getter
@Setter
public class ProviderBalanceSnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long providerId;

    /** CNY / USD，服务端规范化大写。 */
    private String currency;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal totalBalance;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal grantedBalance;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal toppedUpBalance;

    /** 供应商报告的"余额是否足以继续调用"；NULL 表示响应里没有该字段。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Boolean isAvailable;

    /** UTC 抓取时刻，同时是"最新快照"的排序依据。 */
    private Instant fetchedAt;

    /** 被接受的响应 HTTP 状态（成功路径恒 200）。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer sourceHttpStatus;

    /** 供应商在 200 响应内自报的结果码原文；没有则 NULL，不猜测。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String sourceErrorCode;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
