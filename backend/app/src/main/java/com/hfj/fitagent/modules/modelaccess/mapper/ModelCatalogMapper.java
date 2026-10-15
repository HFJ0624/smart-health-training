package com.hfj.fitagent.modules.modelaccess.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.modelaccess.entity.ModelCatalogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * model_catalog 持久化。
 *
 * <p>注意：实体含 JSON 列 {@code capability_snapshot}，依赖 {@code autoResultMap} 上的
 * Jackson3TypeHandler，所以返回<b>实体</b>的查询只能走 BaseMapper（QueryWrapper）；
 * 本类的自定义 SQL 只返回标量列，不参与 JSON 映射。
 */
@Mapper
public interface ModelCatalogMapper extends BaseMapper<ModelCatalogEntity> {

    /**
     * "给定供应商取 ACTIVE 模型，按核查日期与 id 确定性择一"的 id 查询（V11 注释里的固定形态）。
     * 返回标量，避免自定义 SQL 绕过 autoResultMap。
     */
    @Select("""
            SELECT id FROM model_catalog
            WHERE provider_id = #{providerId} AND status = 'ACTIVE'
            ORDER BY source_checked_on DESC, id ASC
            LIMIT 1
            """)
    Long findFirstActiveId(@Param("providerId") long providerId);

    /** 指定 model_code 的 ACTIVE 行 id（大小写由服务端规范化后精确匹配）。 */
    @Select("""
            SELECT id FROM model_catalog
            WHERE provider_id = #{providerId} AND model_code = #{modelCode} AND status = 'ACTIVE'
            ORDER BY source_checked_on DESC, id ASC
            LIMIT 1
            """)
    Long findActiveIdByModelCode(@Param("providerId") long providerId, @Param("modelCode") String modelCode);

    /** 该供应商 ACTIVE 模型数：> 1 且调用方没指定 modelCode 时不能替用户挑，必须报歧义。 */
    @Select("SELECT COUNT(*) FROM model_catalog WHERE provider_id = #{providerId} AND status = 'ACTIVE'")
    int countActive(@Param("providerId") long providerId);
}
