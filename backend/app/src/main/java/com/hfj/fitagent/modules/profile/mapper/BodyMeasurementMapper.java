package com.hfj.fitagent.modules.profile.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.profile.entity.BodyMeasurementEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * body_measurement 持久化。
 *
 * findLatest 是本系统唯一的"最新体重"读法：按 measured_at（实际测量时刻）判定，
 * 不按 business_date，也不维护任何冗余的"当前体重"字段。
 * 因此补录一条业务日期更早的记录不会成为最新体重（验收 A7）；
 * 删除最新一条后自然回退到上一条；无记录返回 null 由上层转成空状态（验收 A8）。
 * id DESC 用于同一毫秒内多条记录时的稳定确定性。
 */
@Mapper
public interface BodyMeasurementMapper extends BaseMapper<BodyMeasurementEntity> {

    @Select("""
            SELECT id, user_id, measured_at, business_date, weight_kg, waist_cm, body_fat_percent,
                   source, client_record_uuid, notes, lock_version, created_at, updated_at
            FROM body_measurement
            WHERE user_id = #{userId}
            ORDER BY measured_at DESC, id DESC
            LIMIT 1
            """)
    BodyMeasurementEntity findLatest(@Param("userId") long userId);

    @Select("""
            SELECT id FROM body_measurement
            WHERE user_id = #{userId} AND client_record_uuid = #{clientRecordUuid}
            """)
    Long findByIdempotencyKey(@Param("userId") long userId, @Param("clientRecordUuid") String clientRecordUuid);
}
