package com.hfj.fitagent.modules.modelaccess.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.modelaccess.entity.ModelCredentialEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * model_credential 持久化。
 *
 * <p>两条硬约定：
 * <ol>
 *   <li><b>每个查询都以 user_id 为条件</b>（{@code (id, user_id)} 复合唯一键就是为此存在的）：
 *       别人的凭据 id 与不存在的 id 得到同一个结果，不区分"不存在"和"越权"。</li>
 *   <li>本表无 JSON 列，但 {@code ciphertext}/{@code nonce}/{@code key_fingerprint} 是二进制材料：
 *       自定义 SQL 只取标量/状态列，需要密文时才走 BaseMapper 的实体查询，
 *       这样"顺手 SELECT * 把密文带进某个日志对象"的机会被压到最小。</li>
 * </ol>
 */
@Mapper
public interface ModelCredentialMapper extends BaseMapper<ModelCredentialEntity> {

    /** 用于状态判断的 id 查询：不取密文、不取指纹。 */
    @Select("""
            SELECT id FROM model_credential WHERE user_id = #{userId} AND provider_id = #{providerId}
            """)
    Long findId(@Param("userId") long userId, @Param("providerId") long providerId);

    /** 该用户在用（ACTIVE）凭据涉及的供应商：预算上下文用，> 1 即歧义。 */
    @Select("""
            SELECT DISTINCT provider_id FROM model_credential
            WHERE user_id = #{userId} AND status = 'ACTIVE'
            ORDER BY provider_id ASC
            """)
    List<Long> findActiveProviderIds(@Param("userId") long userId);
}
