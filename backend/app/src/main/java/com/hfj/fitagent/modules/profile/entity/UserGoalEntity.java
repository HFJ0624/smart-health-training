package com.hfj.fitagent.modules.profile.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * user_goal。
 *
 * active_user_id 是 STORED 生成列，本类刻意不声明该字段：
 * MySQL 不允许向 STORED 生成列写入（除 DEFAULT），声明了就会让 ORM 有机会发出非法 SQL。
 * "每用户最多一个 ACTIVE" 由数据库的 UNIQUE(active_user_id) 保证，不是靠应用记得检查。
 */
@TableName("user_goal")
@Getter
@Setter
public class UserGoalEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** FAT_LOSS / MUSCLE_GAIN / MAINTAIN / GENERAL_FITNESS */
    private String goalType;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal targetWeightKg;

    /**
     * 列保留但本轮页面与接口不暴露。
     *
     * 三个策略都必须 NEVER：只声明 select=false 而不锁写入策略的话，
     * updateStrategy=ALWAYS 会让每一次 updateById 都把该列写回 NULL，
     * 等 P2 真的开始写饮食目标时，任何一次目标编辑都会静默清空它。
     */
    @TableField(select = false, insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private BigDecimal dailyIntakeTargetKcal;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer weeklySessionsTarget;

    private LocalDate startsOn;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate endsOn;

    /** DRAFT / ACTIVE / ARCHIVED */
    private String status;

    @Version
    private Integer lockVersion;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
