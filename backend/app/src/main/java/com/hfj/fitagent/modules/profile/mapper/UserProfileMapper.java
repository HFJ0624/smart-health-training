package com.hfj.fitagent.modules.profile.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.profile.entity.UserProfileEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * user_profile 持久化。
 *
 * 约定：本表的 JSON 列（equipment 等）依赖实体的 autoResultMap + Jackson3TypeHandler，
 * 自定义 @Select 不会带上该 typeHandler，所以这里只提供标量查询；
 * 读实体一律走 BaseMapper.selectOne/selectById。
 */
@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfileEntity> {

    @Select("SELECT id FROM user_profile WHERE user_id = #{userId} FOR UPDATE")
    Long lockByUserId(@Param("userId") long userId);
}
