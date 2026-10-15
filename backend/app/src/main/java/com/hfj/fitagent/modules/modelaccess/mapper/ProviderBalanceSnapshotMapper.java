package com.hfj.fitagent.modules.modelaccess.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.modelaccess.entity.ProviderBalanceSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * provider_balance_snapshot 持久化。
 *
 * <p>只插不改：快照是不可变事实，"失败不清零"靠<b>不写新行</b>实现，
 * 所以本接口没有任何 UPDATE。取"上一次成功值"用唯一键上的索引反向扫描
 * （{@code uq_provider_balance_snapshot_1} 覆盖了 (user_id, provider_id, currency, fetched_at)）。
 */
@Mapper
public interface ProviderBalanceSnapshotMapper extends BaseMapper<ProviderBalanceSnapshotEntity> {

    /** 某币种最近一次成功快照；没有即返回 null（显示"暂无法获取"，绝不显示 0）。 */
    @Select("""
            SELECT id, user_id, provider_id, currency, total_balance, granted_balance, topped_up_balance,
                   is_available, fetched_at, source_http_status, source_error_code, created_at, updated_at
            FROM provider_balance_snapshot
            WHERE user_id = #{userId} AND provider_id = #{providerId} AND currency = #{currency}
            ORDER BY fetched_at DESC, id DESC
            LIMIT 1
            """)
    ProviderBalanceSnapshotEntity findLatest(@Param("userId") long userId, @Param("providerId") long providerId,
                                            @Param("currency") String currency);

    /** 抓取时刻（同一批）所有币种的最新快照：跨币种分开返回，绝不相减。 */
    @Select("""
            SELECT id, user_id, provider_id, currency, total_balance, granted_balance, topped_up_balance,
                   is_available, fetched_at, source_http_status, source_error_code, created_at, updated_at
            FROM provider_balance_snapshot
            WHERE user_id = #{userId} AND provider_id = #{providerId}
              AND fetched_at = (
                SELECT MAX(fetched_at) FROM provider_balance_snapshot
                WHERE user_id = #{userId} AND provider_id = #{providerId}
              )
            ORDER BY currency ASC
            """)
    ProviderBalanceSnapshotEntity[] findLatestBatch(@Param("userId") long userId,
                                                    @Param("providerId") long providerId);
}
