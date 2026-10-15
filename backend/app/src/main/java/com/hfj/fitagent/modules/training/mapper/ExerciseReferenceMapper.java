package com.hfj.fitagent.modules.training.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.training.entity.ExerciseReferenceEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * exercise_reference 持久化（本轮只读，字典维护属后续阶段）。
 *
 * 不提供返回实体的自定义 @Select：muscle_groups / equipment 是 JSON 列，
 * 依赖实体 autoResultMap 上的 Jackson3TypeHandler，自定义 SQL 不会带上它。
 * 查询一律走 BaseMapper + QueryWrapper（在 Service 里按 exercise_code IN (...) 批量取）。
 */
@Mapper
public interface ExerciseReferenceMapper extends BaseMapper<ExerciseReferenceEntity> {
}
