package com.hfj.fitagent.modules.modelaccess.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.modelaccess.entity.ModelProviderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * model_provider 持久化。
 *
 * <p>本表是白名单配置，读多写零：应用侧没有任何写入路径，{@code base_url} 也不接受任何请求参数。
 * provider_code 的匹配走"服务端先规范化大写，再等值比较"，与 exercise_code 同一口径；
 * 列本身是 CI 排序规则，两者叠加保证同一个供应商只有一个键。
 */
@Mapper
public interface ModelProviderMapper extends BaseMapper<ModelProviderEntity> {

    /** 给定供应商码取 ACTIVE 行；取不到即 MODEL_NOT_CONFIGURED，不做任何默认猜测。 */
    @Select("""
            SELECT id FROM model_provider WHERE provider_code = #{providerCode} AND status = 'ACTIVE'
            """)
    Long findActiveIdByCode(@Param("providerCode") String providerCode);

    /** ACTIVE 供应商总数：用于"无法唯一确定供应商"时如实报错，而不是随便挑一个。 */
    @Select("SELECT COUNT(*) FROM model_provider WHERE status = 'ACTIVE'")
    int countActive();
}
