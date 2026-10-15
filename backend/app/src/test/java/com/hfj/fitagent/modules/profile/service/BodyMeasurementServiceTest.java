package com.hfj.fitagent.modules.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hfj.fitagent.common.AppClock;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.web.DateRange;
import com.hfj.fitagent.common.web.FieldError;
import com.hfj.fitagent.common.web.PageQuery;
import com.hfj.fitagent.common.web.PageResult;
import com.hfj.fitagent.modules.auth.mapper.UserAccountMapper;
import com.hfj.fitagent.modules.profile.controller.BodyRecordController;
import com.hfj.fitagent.modules.profile.dto.CreateBodyRecordRequest;
import com.hfj.fitagent.modules.profile.dto.UpdateBodyRecordRequest;
import com.hfj.fitagent.modules.profile.entity.BodyMeasurementEntity;
import com.hfj.fitagent.modules.profile.mapper.BodyMeasurementMapper;
import com.hfj.fitagent.modules.profile.vo.BodyRecordVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 体重记录：业务日期推导、幂等重放、最新体重口径。无 Spring 上下文、无数据库。
 *
 * <p>时钟固定在 {@code 2026-10-14T07:20:00Z}，所以"是否晚于现在"是可判定的，不依赖跑测试的机器时间。
 * 用它挑出来的瞬时值都刻意跨过了 Asia/Shanghai 的日界，好让"业务日期由时区决定"这件事可观测。
 */
class BodyMeasurementServiceTest {

    private static final long USER = 7L;
    private static final long RECORD_ID = 12L;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final String UUID_LOWER = "3f1a2b3c-4d5e-4f60-8a7b-9c0d1e2f3a4b";
    private static final Instant NOW = Instant.parse("2026-10-14T07:20:00Z");
    /** UTC 的 10-13 下午 = 上海的 10-14 凌晨：同一条记录，业务日期归属取决于时区。 */
    private static final Instant CROSS_DAY = Instant.parse("2026-10-13T16:30:00Z");

    private BodyMeasurementMapper measurementMapper;
    private UserAccountMapper accountMapper;
    private AppClock clock;
    private BodyMeasurementService service;

    @BeforeEach
    void setUp() {
        measurementMapper = mock(BodyMeasurementMapper.class);
        accountMapper = mock(UserAccountMapper.class);
        clock = new AppClock(Clock.fixed(NOW, UTC));
        service = new BodyMeasurementService(measurementMapper, clock, accountMapper);
        when(accountMapper.incrementDataRevision(USER)).thenReturn(1);
    }

    private static BodyMeasurementEntity stored(Instant measuredAt, LocalDate businessDate, String weight) {
        BodyMeasurementEntity entity = new BodyMeasurementEntity();
        entity.setId(RECORD_ID);
        entity.setUserId(USER);
        entity.setMeasuredAt(measuredAt);
        entity.setBusinessDate(businessDate);
        entity.setWeightKg(new BigDecimal(weight));
        entity.setSource("MANUAL");
        entity.setClientRecordUuid(UUID_LOWER);
        entity.setLockVersion(0);
        return entity;
    }

    private static CreateBodyRecordRequest createRequest(Instant measuredAt, String clientUuid) {
        return new CreateBodyRecordRequest(measuredAt, new BigDecimal("72.50"), new BigDecimal("88.00"),
                null, "晨起空腹", clientUuid);
    }

    /** 模拟 MyBatis-Plus 在 INSERT 后回填自增主键与初始版本号。 */
    private void stubInsertAssigningIdentity() {
        when(measurementMapper.insert(any(BodyMeasurementEntity.class))).thenAnswer(invocation -> {
            BodyMeasurementEntity entity = invocation.getArgument(0);
            entity.setId(RECORD_ID);
            entity.setLockVersion(0);
            return 1;
        });
    }

    @Test
    @DisplayName("businessDate 由 measuredAt 在用户时区推导：上海归属 10-14，source 恒为 MANUAL")
    void businessDateIsDerivedFromMeasuredAtInUserZone() {
        stubInsertAssigningIdentity();
        when(measurementMapper.selectOne(any()))
                .thenReturn(stored(CROSS_DAY, LocalDate.of(2026, 10, 14), "72.50"));

        service.create(USER, SHANGHAI, createRequest(CROSS_DAY, UUID_LOWER));

        ArgumentCaptor<BodyMeasurementEntity> captor = ArgumentCaptor.forClass(BodyMeasurementEntity.class);
        verify(measurementMapper).insert(captor.capture());
        assertThat(captor.getValue().getBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(captor.getValue().getMeasuredAt()).isEqualTo(CROSS_DAY);
        assertThat(captor.getValue().getSource()).isEqualTo("MANUAL");
        assertThat(captor.getValue().getUserId()).isEqualTo(USER);
    }

    @Test
    @DisplayName("同一瞬时值在 UTC 时区归属 10-13：业务日期跟的是账户时区，不是服务器默认")
    void businessDateFollowsTheCallersZone() {
        stubInsertAssigningIdentity();
        when(measurementMapper.selectOne(any()))
                .thenReturn(stored(CROSS_DAY, LocalDate.of(2026, 10, 13), "72.50"));

        service.create(USER, UTC, createRequest(CROSS_DAY, UUID_LOWER));

        ArgumentCaptor<BodyMeasurementEntity> captor = ArgumentCaptor.forClass(BodyMeasurementEntity.class);
        verify(measurementMapper).insert(captor.capture());
        assertThat(captor.getValue().getBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 13));
    }

    @Test
    @DisplayName("晚于当前时间 5 分钟即拒绝（FUTURE_MEASURED_AT），5 分钟内收下 —— 纯工程护栏")
    void rejectsImplausiblyFutureMeasuredAt() {
        assertThatThrownBy(() -> service.create(USER, SHANGHAI,
                createRequest(NOW.plusSeconds(361), UUID_LOWER)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat((List<?>) e.payload()).singleElement().satisfies(f -> {
                        FieldError fieldError = (FieldError) f;
                        assertThat(fieldError.field()).isEqualTo("measuredAt");
                        assertThat(fieldError.code()).isEqualTo("FUTURE_MEASURED_AT");
                    });
                });
        verify(measurementMapper, never()).insert(any(BodyMeasurementEntity.class));
        verify(accountMapper, never()).incrementDataRevision(anyLong());

        stubInsertAssigningIdentity();
        when(measurementMapper.selectOne(any()))
                .thenReturn(stored(NOW.plusSeconds(240), LocalDate.of(2026, 10, 14), "72.50"));

        BodyRecordVo vo = service.create(USER, SHANGHAI, createRequest(NOW.plusSeconds(240), UUID_LOWER));

        assertThat(vo.created()).isTrue();
    }

    @Test
    @DisplayName("clientRecordUuid 规范化为小写入库；格式非法给 INVALID_UUID_FORMAT 而不是 500")
    void uuidIsCanonicalizedOrRejected() {
        stubInsertAssigningIdentity();
        when(measurementMapper.selectOne(any()))
                .thenReturn(stored(NOW, LocalDate.of(2026, 10, 14), "72.50"));

        service.create(USER, SHANGHAI, createRequest(NOW, UUID_LOWER.toUpperCase(Locale.ROOT)));

        ArgumentCaptor<BodyMeasurementEntity> captor = ArgumentCaptor.forClass(BodyMeasurementEntity.class);
        verify(measurementMapper).insert(captor.capture());
        assertThat(captor.getValue().getClientRecordUuid()).isEqualTo(UUID_LOWER);

        assertThatThrownBy(() -> service.create(USER, SHANGHAI, createRequest(NOW, "not-a-uuid")))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat((List<?>) e.payload()).extracting(f -> ((FieldError) f).code())
                                .containsExactly("INVALID_UUID_FORMAT"));
    }

    @Test
    @DisplayName("新建返回 created=true / idempotentReplay=false 并递增 data_revision")
    void freshInsertReportsCreated() {
        stubInsertAssigningIdentity();
        when(measurementMapper.selectOne(any()))
                .thenReturn(stored(NOW, LocalDate.of(2026, 10, 14), "72.50"));

        BodyRecordVo vo = service.create(USER, SHANGHAI, createRequest(NOW, UUID_LOWER));

        assertThat(vo.created()).isTrue();
        assertThat(vo.idempotentReplay()).isFalse();
        verify(measurementMapper, never()).findByIdempotencyKey(anyLong(), any());
        verify(accountMapper).incrementDataRevision(USER);
    }

    @Test
    @DisplayName("同 uuid 二次提交：撞 uq_body_measurement_3 后回读既有行，created=false + 幂等标记，修订号不变")
    void duplicateUuidReplaysExistingRecord() {
        when(measurementMapper.insert(any(BodyMeasurementEntity.class)))
                .thenThrow(new DuplicateKeyException("uq_body_measurement_3"));
        when(measurementMapper.findByIdempotencyKey(USER, UUID_LOWER)).thenReturn(RECORD_ID);
        when(measurementMapper.selectOne(any()))
                .thenReturn(stored(NOW, LocalDate.of(2026, 10, 13), "70.00"));

        // 大写形态的同一个 UUID：规范化后必须命中既有行，而不是新增一条
        BodyRecordVo vo = service.create(USER, SHANGHAI,
                createRequest(NOW, UUID_LOWER.toUpperCase(Locale.ROOT)));

        assertThat(vo.created()).isFalse();
        assertThat(vo.idempotentReplay()).isTrue();
        assertThat(vo.id()).isEqualTo(String.valueOf(RECORD_ID));
        assertThat(vo.weightKg()).isEqualByComparingTo("70.00");
        verify(measurementMapper).findByIdempotencyKey(eq(USER), eq(UUID_LOWER));
        verify(accountMapper, never()).incrementDataRevision(anyLong());
    }

    @Test
    @DisplayName("最新体重只委托 Mapper 的 measured_at 排序口径：补录更早业务日期的记录不会成为最新")
    void latestDelegatesToMeasuredAtOrdering() {
        // 库里"最新"的那一行：measured_at 就是刚刚，但 business_date 是上周 —— 这正是一条补录
        BodyMeasurementEntity backfilled = stored(NOW, LocalDate.of(2026, 10, 6), "75.00");
        when(measurementMapper.findLatest(USER)).thenReturn(backfilled);

        BodyRecordVo vo = service.latest(USER);

        verify(measurementMapper).findLatest(USER);
        // 服务没有自己按 business_date 反推"最新"：除了 findLatest 之外不存在第二种读法
        verify(measurementMapper, never()).selectOne(any());
        verify(measurementMapper, never()).selectPage(any(), any());
        assertThat(vo.businessDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(vo.measuredAt()).isEqualTo(NOW);
        assertThat(vo.weightKg()).isEqualByComparingTo("75.00");
    }

    @Test
    @DisplayName("无记录时 latest 返回 null（空状态，不是 404）")
    void latestIsNullWhenNoRecords() {
        when(measurementMapper.findLatest(USER)).thenReturn(null);

        assertThat(service.latest(USER)).isNull();
    }

    @Test
    @DisplayName("改测量时间会重算 businessDate；uuid 与 source 保持原值不被改写")
    void updateRecomputesBusinessDate() {
        when(measurementMapper.selectOne(any()))
                .thenReturn(stored(NOW, LocalDate.of(2026, 10, 14), "72.50"));
        when(measurementMapper.updateById(any(BodyMeasurementEntity.class))).thenReturn(1);

        // 改成 UTC 10-13 15:00（上海 10-13 23:00）：业务日期从 10-14 退回 10-13
        BodyRecordVo vo = service.update(USER, RECORD_ID, SHANGHAI, new UpdateBodyRecordRequest(
                Instant.parse("2026-10-13T15:00:00Z"), new BigDecimal("71.00"), null, null, null,
                UUID_LOWER.toUpperCase(Locale.ROOT), 0));

        ArgumentCaptor<BodyMeasurementEntity> captor = ArgumentCaptor.forClass(BodyMeasurementEntity.class);
        verify(measurementMapper).updateById(captor.capture());
        assertThat(captor.getValue().getBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(captor.getValue().getWeightKg()).isEqualByComparingTo("71.00");
        assertThat(captor.getValue().getSource()).isEqualTo("MANUAL");
        assertThat(captor.getValue().getClientRecordUuid()).isEqualTo(UUID_LOWER);
        verify(accountMapper).incrementDataRevision(USER);
        assertThat(vo).isNotNull();
    }

    @Test
    @DisplayName("不传 measuredAt 时既保留原测量时间也保留原业务日期")
    void updateKeepsBusinessDateWhenMeasuredAtAbsent() {
        when(measurementMapper.selectOne(any()))
                .thenReturn(stored(CROSS_DAY, LocalDate.of(2026, 10, 6), "72.50"));
        when(measurementMapper.updateById(any(BodyMeasurementEntity.class))).thenReturn(1);

        service.update(USER, RECORD_ID, SHANGHAI,
                new UpdateBodyRecordRequest(null, new BigDecimal("73.00"), null, null, null, null, 0));

        ArgumentCaptor<BodyMeasurementEntity> captor = ArgumentCaptor.forClass(BodyMeasurementEntity.class);
        verify(measurementMapper).updateById(captor.capture());
        assertThat(captor.getValue().getBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(captor.getValue().getMeasuredAt()).isEqualTo(CROSS_DAY);
    }

    @Test
    @DisplayName("clientRecordUuid 不可变：传不同值即 IMMUTABLE_FIELD，且不写库")
    void clientRecordUuidIsImmutable() {
        when(measurementMapper.selectOne(any()))
                .thenReturn(stored(NOW, LocalDate.of(2026, 10, 14), "72.50"));

        String different = "00000000-0000-4000-8000-000000000000";
        assertThatThrownBy(() -> service.update(USER, RECORD_ID, SHANGHAI,
                new UpdateBodyRecordRequest(null, new BigDecimal("73.00"), null, null, null, different, 0)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat((List<?>) e.payload()).extracting(f -> ((FieldError) f).code())
                            .containsExactly("IMMUTABLE_FIELD");
                });
        verify(measurementMapper, never()).updateById(any(BodyMeasurementEntity.class));
        verify(accountMapper, never()).incrementDataRevision(anyLong());
    }

    @Test
    @DisplayName("PUT 的 lockVersion 不符 → 409，不做任何写入")
    void updateWithStaleVersionRejected() {
        BodyMeasurementEntity existing = stored(NOW, LocalDate.of(2026, 10, 14), "72.50");
        existing.setLockVersion(5);
        when(measurementMapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> service.update(USER, RECORD_ID, SHANGHAI,
                new UpdateBodyRecordRequest(null, new BigDecimal("73.00"), null, null, null, null, 4)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));
        verify(measurementMapper, never()).updateById(any(BodyMeasurementEntity.class));
    }

    @Test
    @DisplayName("越权访问他人记录：(id,user_id) 查不到 → RESOURCE_NOT_FOUND")
    void otherUsersRecordIsNotFound() {
        when(measurementMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.update(USER, RECORD_ID, SHANGHAI,
                new UpdateBodyRecordRequest(null, new BigDecimal("73.00"), null, null, null, null, 0)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> service.delete(USER, RECORD_ID))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verify(measurementMapper, never()).delete(any());
    }

    @Test
    @DisplayName("删除按 (id,user_id) 限定作用域并递增修订号；latest 的回退由 Mapper 排序口径天然完成")
    void deleteIsScopedAndBumpsRevision() {
        when(measurementMapper.selectOne(any()))
                .thenReturn(stored(NOW, LocalDate.of(2026, 10, 14), "72.50"));
        when(measurementMapper.delete(any())).thenReturn(1);

        service.delete(USER, RECORD_ID);

        ArgumentCaptor<QueryWrapper<BodyMeasurementEntity>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(measurementMapper).delete(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("user_id");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains(USER, RECORD_ID);
        verify(accountMapper).incrementDataRevision(USER);
    }

    @Test
    @DisplayName("列表按业务日期区间过滤（含端点），排序列走白名单映射；读视图不带写判定位")
    void listFiltersByBusinessDateRange() {
        Page<BodyMeasurementEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(stored(NOW, LocalDate.of(2026, 10, 13), "72.50")));
        page.setTotal(1L);
        when(measurementMapper.selectPage(any(), any())).thenReturn(page);

        DateRange range = new DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 14));
        PageQuery query = PageQuery.of(1, 20, "weightKg,asc", BodyRecordController.SORT.allowedFields(),
                BodyRecordController.DEFAULT_SORT_FIELD);
        PageResult<BodyRecordVo> result = service.list(USER, range, query, BodyRecordController.SORT);

        assertThat(query.column(BodyRecordController.SORT)).isEqualTo("weight_kg");
        assertThat(BodyRecordController.SORT.allowedFields())
                .containsExactlyInAnyOrder("measuredAt", "businessDate", "weightKg");
        ArgumentCaptor<QueryWrapper<BodyMeasurementEntity>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(measurementMapper).selectPage(any(), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("business_date").contains("user_id");
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .contains(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 14), USER);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).created()).isNull();
        assertThat(result.items().get(0).idempotentReplay()).isNull();
    }

    @Test
    @DisplayName("默认窗口是最近 90 天含今天；跨度超 366 天由 DateRange 拒绝")
    void defaultWindowAndSpanLimits() {
        // 固定时钟的 UTC 07:20 在上海已经是同一天 15:20
        LocalDate today = clock.businessDate(SHANGHAI);
        assertThat(today).isEqualTo(LocalDate.of(2026, 10, 14));

        DateRange defaulted = DateRange.of(null, null,
                today.minusDays(BodyRecordController.DEFAULT_WINDOW_DAYS - 1L), today);
        assertThat(defaulted.from()).isEqualTo(LocalDate.of(2026, 7, 17));
        assertThat(defaulted.to()).isEqualTo(today);

        assertThatThrownBy(() -> DateRange.of("2026-01-01", "2027-12-31", today, today))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat((List<?>) e.payload()).extracting(f -> ((FieldError) f).code())
                                .containsExactly("RANGE_TOO_WIDE"));
    }
}
