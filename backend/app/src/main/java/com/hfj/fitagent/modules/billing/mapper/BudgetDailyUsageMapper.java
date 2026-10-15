package com.hfj.fitagent.modules.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.billing.entity.BudgetDailyUsageEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * budget_daily_usage 持久化。
 *
 * <p>四条语句共同构成"不会双花"的账本写入面：
 * <ol>
 *   <li>{@link #ensureAccount} 用 {@code INSERT ... ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)}
 *       分配或复用当日账户行，并把 id 回灌进实体——预占事务由此总能拿到同一行来加锁；</li>
 *   <li>{@link #lockAccount} 是 {@code FOR UPDATE}：固定锁序里唯一可锁的一层
 *       （{@code agent_run} 本轮不存在，见 V17 注释；该表落地后锁序必须是 run → daily_usage）；</li>
 *   <li>三个金额转移语句都带 {@code >= 0} 与"不为负"守卫，写错公式时影响 0 行而不是给出负额度；</li>
 *   <li>守卫条件里重复的 {@code currency} 断言是刻意冗余的：跨币种相减在这套账里是致命错误。</li>
 * </ol>
 */
@Mapper
public interface BudgetDailyUsageMapper extends BaseMapper<BudgetDailyUsageEntity> {

    /** 分配或复用 (user, provider, currency, budget_date) 账户行；id 回灌到入参实体。 */
    @Insert("""
            INSERT INTO budget_daily_usage
              (user_id, provider_id, currency, budget_date, settled_cost, reserved_cost, uncertain_reserved_cost, lock_version)
            VALUES
              (#{userId}, #{providerId}, #{currency}, #{budgetDate}, 0, 0, 0, 0)
            ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int ensureAccount(BudgetDailyUsageEntity account);

    /** 行锁：并发预占的串行点。 */
    @Select("""
            SELECT id, user_id, provider_id, currency, budget_date, settled_cost, reserved_cost,
                   uncertain_reserved_cost, lock_version, created_at, updated_at
            FROM budget_daily_usage
            WHERE id = #{id} AND user_id = #{userId}
            FOR UPDATE
            """)
    BudgetDailyUsageEntity lockAccount(@Param("id") long id, @Param("userId") long userId);

    /** 预占：reserved_cost 增加保守估算额。 */
    @Update("""
            UPDATE budget_daily_usage
            SET reserved_cost = reserved_cost + #{amount}, lock_version = lock_version + 1
            WHERE id = #{id} AND user_id = #{userId} AND currency = #{currency}
              AND reserved_cost + #{amount} >= 0
            """)
    int addReserved(@Param("id") long id, @Param("userId") long userId, @Param("currency") String currency,
                    @Param("amount") BigDecimal amount);

    /** 结算：reserved 减估算额、settled 加实际额；任何一边变负都拒绝执行。 */
    @Update("""
            UPDATE budget_daily_usage
            SET reserved_cost = reserved_cost - #{estimate},
                settled_cost = settled_cost + #{actual},
                lock_version = lock_version + 1
            WHERE id = #{id} AND user_id = #{userId} AND currency = #{currency}
              AND reserved_cost >= #{estimate} AND settled_cost + #{actual} >= 0
            """)
    int settle(@Param("id") long id, @Param("userId") long userId, @Param("currency") String currency,
               @Param("estimate") BigDecimal estimate, @Param("actual") BigDecimal actual);

    /** 用量不明：估算额从 reserved 平移到 uncertain_reserved，总占用不变（不释放额度）。 */
    @Update("""
            UPDATE budget_daily_usage
            SET reserved_cost = reserved_cost - #{estimate},
                uncertain_reserved_cost = uncertain_reserved_cost + #{estimate},
                lock_version = lock_version + 1
            WHERE id = #{id} AND user_id = #{userId} AND currency = #{currency}
              AND reserved_cost >= #{estimate}
            """)
    int markUncertain(@Param("id") long id, @Param("userId") long userId, @Param("currency") String currency,
                      @Param("estimate") BigDecimal estimate);

    /** 确定未送达：释放预占（只有这一条路径会让总占用下降）。 */
    @Update("""
            UPDATE budget_daily_usage
            SET reserved_cost = reserved_cost - #{estimate},
                lock_version = lock_version + 1
            WHERE id = #{id} AND user_id = #{userId} AND currency = #{currency}
              AND reserved_cost >= #{estimate}
            """)
    int release(@Param("id") long id, @Param("userId") long userId, @Param("currency") String currency,
                @Param("estimate") BigDecimal estimate);
}
