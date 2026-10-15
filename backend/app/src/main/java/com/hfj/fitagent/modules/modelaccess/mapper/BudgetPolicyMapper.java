package com.hfj.fitagent.modules.modelaccess.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.modelaccess.entity.BudgetPolicyEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * budget_policy 持久化。
 *
 * <p>{@code (user_id, provider_id, currency)} 唯一：不同币种是彼此独立的策略行，
 * 因此不存在"跨币种合并"这种代码可能。
 *
 * <p>无 JSON 列，自定义 SQL 返回实体安全。
 */
@Mapper
public interface BudgetPolicyMapper extends BaseMapper<BudgetPolicyEntity> {

    /** 精确取一份策略（预算上下文与 GET 都用这个形态）。 */
    @Select("""
            SELECT id, user_id, provider_id, currency, max_task_cost, max_daily_cost, budget_timezone,
                   max_model_calls, max_tool_calls, max_output_tokens, enabled, lock_version,
                   created_at, updated_at
            FROM budget_policy
            WHERE user_id = #{userId} AND provider_id = #{providerId} AND currency = #{currency}
            """)
    BudgetPolicyEntity find(@Param("userId") long userId, @Param("providerId") long providerId,
                            @Param("currency") String currency);

    /** 该用户在该供应商的全部策略（币种歧义时用于判断"只有一份"）。 */
    @Select("""
            SELECT currency FROM budget_policy
            WHERE user_id = #{userId} AND provider_id = #{providerId}
            ORDER BY currency ASC
            """)
    List<String> findCurrencies(@Param("userId") long userId, @Param("providerId") long providerId);
}
