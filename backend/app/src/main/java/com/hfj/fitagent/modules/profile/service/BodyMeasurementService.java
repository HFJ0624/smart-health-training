package com.hfj.fitagent.modules.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hfj.fitagent.common.AppClock;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.util.Ids;
import com.hfj.fitagent.common.web.DateRange;
import com.hfj.fitagent.common.web.PageQuery;
import com.hfj.fitagent.common.web.PageResult;
import com.hfj.fitagent.common.web.SortColumns;
import com.hfj.fitagent.modules.auth.mapper.UserAccountMapper;
import com.hfj.fitagent.modules.profile.dto.CreateBodyRecordRequest;
import com.hfj.fitagent.modules.profile.dto.FieldErrors;
import com.hfj.fitagent.modules.profile.dto.UpdateBodyRecordRequest;
import com.hfj.fitagent.modules.profile.dto.Vocabulary;
import com.hfj.fitagent.modules.profile.entity.BodyMeasurementEntity;
import com.hfj.fitagent.modules.profile.mapper.BodyMeasurementMapper;
import com.hfj.fitagent.modules.profile.vo.BodyRecordVo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * 体重/身体记录（body_measurement）。
 *
 * <p>三条不可让渡的语义：
 * <ol>
 *   <li><b>businessDate 由服务端推导</b>：{@code clock.businessDateOf(measuredAt, 用户时区)}。客户端不传该字段，
 *       所以补录一条业务日期更早的记录只会移动历史曲线的横轴位置，不会成为"最新体重"（验收 A7）。</li>
 *   <li><b>"最新"只有一个读法</b>：{@link BodyMeasurementMapper#findLatest(long)} 的
 *       {@code ORDER BY measured_at DESC, id DESC LIMIT 1}。本类不用 business_date 排序、不算
 *       {@code MAX(business_date)}、也不在 user_profile 冗余"当前体重"，删除最新一条后天然回退（验收 A8）。</li>
 *   <li><b>幂等靠唯一索引</b>：{@code uq_body_measurement_3 (user_id, client_record_uuid)}。
 *       UUID 先规范化成小写（docs/p1-database.md §5），撞索引就回读既有行并返回
 *       {@code created=false, idempotentReplay=true}，而不是抛 500（验收 A6）。</li>
 * </ol>
 */
@Service
public class BodyMeasurementService {

    /**
     * 允许的前向时钟偏移：只防误填，不是业务规则（阈值已在 docs/p1-api.md §5 确认清单第 4 项挂账）。
     */
    static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    private final BodyMeasurementMapper measurementMapper;
    private final AppClock clock;
    private final UserAccountMapper accountMapper;

    public BodyMeasurementService(BodyMeasurementMapper measurementMapper,
                                  AppClock clock,
                                  UserAccountMapper accountMapper) {
        this.measurementMapper = measurementMapper;
        this.clock = clock;
        this.accountMapper = accountMapper;
    }

    /** 历史分页：按业务日期区间过滤，排序列只来自白名单映射。 */
    @Transactional(readOnly = true)
    public PageResult<BodyRecordVo> list(long userId, DateRange range, PageQuery query, SortColumns columns) {
        QueryWrapper<BodyMeasurementEntity> wrapper = new QueryWrapper<BodyMeasurementEntity>()
                .eq("user_id", userId)
                .between("business_date", range.from(), range.to());
        // 排序列名只可能来自 SortColumns 的静态映射，请求侧的字段名不会直接进入 SQL
        if (query.direction() == PageQuery.Direction.ASC) {
            wrapper.orderByAsc(query.column(columns));
        } else {
            wrapper.orderByDesc(query.column(columns));
        }
        // 同值行用 id 兜底，保证翻页确定性
        wrapper.orderByDesc("id");

        IPage<BodyMeasurementEntity> result =
                measurementMapper.selectPage(new Page<>(query.page(), query.size()), wrapper);
        List<BodyRecordVo> items = result.getRecords().stream().map(BodyRecordVo::from).toList();
        return PageResult.from(result, items);
    }

    /**
     * 最近一次测量。委托 Mapper 的 {@code ORDER BY measured_at DESC, id DESC LIMIT 1}，
     * 无记录返回 null 由上层转成空状态。
     */
    @Transactional(readOnly = true)
    public BodyRecordVo latest(long userId) {
        return BodyRecordVo.from(measurementMapper.findLatest(userId));
    }

    /**
     * @param zone 认证主体的业务时区（user_account.timezone），businessDate 只按它推导
     * @return 带 created 标记的响应；重复提交时 created=false 且行数不变
     */
    @Transactional
    public BodyRecordVo create(long userId, ZoneId zone, CreateBodyRecordRequest req) {
        Instant measuredAt = req.measuredAt();
        requirePlausibleMeasuredAt(measuredAt);
        String clientUuid = canonicalUuid(req.clientRecordUuid());

        BodyMeasurementEntity entity = new BodyMeasurementEntity();
        entity.setUserId(userId);
        entity.setMeasuredAt(measuredAt);
        entity.setBusinessDate(clock.businessDateOf(measuredAt, zone));
        entity.setWeightKg(req.weightKg());
        entity.setWaistCm(req.waistCm());
        entity.setBodyFatPercent(req.bodyFatPercent());
        entity.setSource(Vocabulary.SOURCE_MANUAL);
        entity.setClientRecordUuid(clientUuid);
        entity.setNotes(blankToNull(req.notes()));

        try {
            measurementMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 同一条逻辑记录重试：回读既有行返回，不加修订号（数据没有变化），也不让前端看到 500。
            // 重复键在 InnoDB 只回滚该语句，事务本身仍可用，因此这里可以在同一事务内继续 SELECT。
            return BodyRecordVo.written(replayExisting(userId, clientUuid), false);
        }
        AccountRowGuard.bumpDataRevision(accountMapper, userId);
        return BodyRecordVo.written(requireById(userId, entity.getId()), true);
    }

    /**
     * 修改：{@code clientRecordUuid} 不可变（它是幂等键，改了就不再是同一条逻辑记录）；
     * 修正 measuredAt 时同步重算 businessDate。
     */
    @Transactional
    public BodyRecordVo update(long userId, long id, ZoneId zone, UpdateBodyRecordRequest req) {
        BodyMeasurementEntity entity = requireById(userId, id);
        // 版本先判：不匹配就不该做任何赋值与写库，避免把"过期页面"的意图带进对象再靠拦截器兜底
        if (!Objects.equals(entity.getLockVersion(), req.lockVersion())) {
            throw BizException.versionConflict();
        }

        if (req.clientRecordUuid() != null) {
            String requested = canonicalUuid(req.clientRecordUuid());
            if (!requested.equals(entity.getClientRecordUuid())) {
                throw FieldErrors.immutable("clientRecordUuid", req.clientRecordUuid());
            }
        }
        if (req.measuredAt() != null) {
            requirePlausibleMeasuredAt(req.measuredAt());
            entity.setMeasuredAt(req.measuredAt());
            entity.setBusinessDate(clock.businessDateOf(req.measuredAt(), zone));
        }
        entity.setWeightKg(req.weightKg());
        entity.setWaistCm(req.waistCm());
        entity.setBodyFatPercent(req.bodyFatPercent());
        entity.setNotes(blankToNull(req.notes()));

        if (measurementMapper.updateById(entity) == 0) {
            throw BizException.versionConflict();
        }
        AccountRowGuard.bumpDataRevision(accountMapper, userId);
        return BodyRecordVo.from(requireById(userId, id));
    }

    /**
     * 删除。归属校验失败与"不存在"同形；删掉的如果是最新一条，{@link #latest(long)} 因为走
     * {@code ORDER BY measured_at DESC} 而天然回退到上一条，不需要任何补偿逻辑。
     */
    @Transactional
    public void delete(long userId, long id) {
        requireById(userId, id);
        if (measurementMapper.delete(new QueryWrapper<BodyMeasurementEntity>()
                .eq("id", id)
                .eq("user_id", userId)) == 0) {
            throw BizException.notFound();
        }
        AccountRowGuard.bumpDataRevision(accountMapper, userId);
    }

    private void requirePlausibleMeasuredAt(Instant measuredAt) {
        if (measuredAt == null) {
            throw FieldErrors.field("measuredAt", "REQUIRED", "测量时间不能为空", null);
        }
        if (clock.isImplausibleFuture(measuredAt, MAX_FUTURE_SKEW)) {
            throw FieldErrors.field("measuredAt", "FUTURE_MEASURED_AT",
                    "测量时间不能晚于当前时间 5 分钟以上", measuredAt.toString());
        }
    }

    /** 非法格式 → 字段级 INVALID_UUID_FORMAT；不能让它落到 500。 */
    private String canonicalUuid(String raw) {
        try {
            return Ids.canonicalUuid(raw);
        } catch (IllegalArgumentException e) {
            throw FieldErrors.field("clientRecordUuid", "INVALID_UUID_FORMAT",
                    "clientRecordUuid 必须是合法 UUID", raw);
        }
    }

    private BodyMeasurementEntity replayExisting(long userId, String clientUuid) {
        Long existingId = measurementMapper.findByIdempotencyKey(userId, clientUuid);
        if (existingId == null) {
            // 唯一索引冲突却查不到行：只可能是并发删除，语义上等同于"请重读"
            throw new BizException(ErrorCode.VERSION_CONFLICT, "记录已被并发变更，请重新读取");
        }
        return requireById(userId, existingId);
    }

    /** (id, user_id) 复合条件：越权与不存在一律 404。 */
    private BodyMeasurementEntity requireById(long userId, long id) {
        BodyMeasurementEntity entity = measurementMapper.selectOne(new QueryWrapper<BodyMeasurementEntity>()
                .eq("id", id)
                .eq("user_id", userId));
        if (entity == null) {
            throw BizException.notFound();
        }
        return entity;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
