package com.hfj.fitagent.modules.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.web.FieldError;
import com.hfj.fitagent.modules.auth.mapper.UserAccountMapper;
import com.hfj.fitagent.modules.profile.dto.UpsertProfileRequest;
import com.hfj.fitagent.modules.profile.entity.UserProfileEntity;
import com.hfj.fitagent.modules.profile.mapper.UserProfileMapper;
import com.hfj.fitagent.modules.profile.vo.ProfileVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 档案服务：一人一档、乐观锁、写入面收窄。无 Spring 上下文、无数据库。
 */
class UserProfileServiceTest {

    private static final long USER = 7L;
    private static final long OTHER_USER = 8L;

    private UserProfileMapper profileMapper;
    private UserAccountMapper accountMapper;
    private UserProfileService service;

    @BeforeEach
    void setUp() {
        profileMapper = mock(UserProfileMapper.class);
        accountMapper = mock(UserAccountMapper.class);
        service = new UserProfileService(profileMapper, accountMapper);
        // data_revision 自增命中 1 行是 AccountRowGuard 的前置条件，默认让它成功；
        // 需要断言"不该递增"的用例用 verify(never()) 覆盖
        when(accountMapper.incrementDataRevision(USER)).thenReturn(1);
    }

    private static UpsertProfileRequest request(BigDecimal heightCm, List<String> equipment, Integer lockVersion) {
        return new UpsertProfileRequest(heightCm, equipment, List.of("每次训练不超过 60 分钟"),
                LocalDate.of(1994, 5, 8), "MALE", "BEGINNER", lockVersion);
    }

    private static UserProfileEntity storedProfile(long userId) {
        UserProfileEntity entity = new UserProfileEntity();
        entity.setId(100L);
        entity.setUserId(userId);
        entity.setHeightCm(new BigDecimal("175.00"));
        entity.setTrainingLevel("BEGINNER");
        entity.setSexForEquation("UNSPECIFIED");
        entity.setEquipment(List.of("BARBELL"));
        entity.setLockVersion(3);
        // 本轮不开放写入的列：库里可能有历史值，必须原样透出而不是被覆盖
        entity.setActivityCategory("LIGHT");
        entity.setDietPreferences(List.of("NO_PORK"));
        entity.setProfessionalGuidanceRequired(Boolean.FALSE);
        return entity;
    }

    @Test
    @DisplayName("无档案返回 null，由控制器转成 200 + data:null，而不是 404")
    void findReturnsNullWhenAbsent() {
        when(profileMapper.selectOne(any())).thenReturn(null);

        assertThat(service.find(USER)).isNull();
    }

    @Test
    @DisplayName("创建只写契约允许的六个字段，未开放列一律留在实体默认（由数据库默认值兜底）")
    void createWritesOnlyAllowedFields() {
        when(profileMapper.selectOne(any())).thenReturn(storedProfile(USER));

        service.create(USER, request(new BigDecimal("175.00"), List.of("BARBELL", "MAT"), null));

        ArgumentCaptor<UserProfileEntity> captor = ArgumentCaptor.forClass(UserProfileEntity.class);
        verify(profileMapper).insert(captor.capture());
        UserProfileEntity written = captor.getValue();
        assertThat(written.getUserId()).isEqualTo(USER);
        assertThat(written.getHeightCm()).isEqualByComparingTo("175.00");
        assertThat(written.getEquipment()).containsExactly("BARBELL", "MAT");
        assertThat(written.getTrainingConstraints()).containsExactly("每次训练不超过 60 分钟");
        assertThat(written.getTrainingLevel()).isEqualTo("BEGINNER");
        assertThat(written.getSexForEquation()).isEqualTo("MALE");
        assertThat(written.getBirthDate()).isEqualTo(LocalDate.of(1994, 5, 8));
        // 本轮无消费方的列：不写就不该出现在 INSERT 里
        assertThat(written.getActivityCategory()).isNull();
        assertThat(written.getDietPreferences()).isNull();
        assertThat(written.getProfessionalGuidanceRequired()).isNull();
        assertThat(written.getActivityConfirmedAt()).isNull();
    }

    @Test
    @DisplayName("创建成功后同事务递增 data_revision")
    void createBumpsDataRevision() {
        when(profileMapper.selectOne(any())).thenReturn(storedProfile(USER));

        service.create(USER, request(new BigDecimal("175.00"), null, null));

        verify(accountMapper).incrementDataRevision(USER);
    }

    @Test
    @DisplayName("并发首次创建撞 UNIQUE(user_id)：转 409 VERSION_CONFLICT 并提示重读，不落 500、不加修订号")
    void duplicateCreateBecomesVersionConflict() {
        when(profileMapper.insert(any(UserProfileEntity.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uq_user_profile_3"));

        assertThatThrownBy(() -> service.create(USER, request(new BigDecimal("175.00"), null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT);
                    assertThat(e.getMessage()).contains("重新读取");
                });
        verify(accountMapper, never()).incrementDataRevision(anyLong());
    }

    @Test
    @DisplayName("创建不允许携带 lockVersion：没有既有版本可比")
    void createRejectsLockVersion() {
        assertThatThrownBy(() -> service.create(USER, request(new BigDecimal("175.00"), null, 0)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN_SERVER_FIELD);
                    assertThat((List<?>) e.payload()).extracting(f -> ((FieldError) f).field())
                            .containsExactly("lockVersion");
                });
        verify(profileMapper, never()).insert(any(UserProfileEntity.class));
    }

    @Test
    @DisplayName("省略枚举时落回数据库默认值，而不是隐式猜测")
    void omittedEnumsFallBackToColumnDefaults() {
        when(profileMapper.selectOne(any())).thenReturn(storedProfile(USER));

        service.create(USER, new UpsertProfileRequest(new BigDecimal("175.00"), null, null, null, null, null, null));

        ArgumentCaptor<UserProfileEntity> captor = ArgumentCaptor.forClass(UserProfileEntity.class);
        verify(profileMapper).insert(captor.capture());
        assertThat(captor.getValue().getTrainingLevel()).isEqualTo("BEGINNER");
        assertThat(captor.getValue().getSexForEquation()).isEqualTo("UNSPECIFIED");
        // 未填即为未填：不补一个空数组冒充"用户选了 0 件器械"
        assertThat(captor.getValue().getEquipment()).isNull();
        assertThat(captor.getValue().getTrainingConstraints()).isNull();
    }

    @Test
    @DisplayName("枚举只接受大写字面量，其它值定位到具体字段")
    void rejectsNonCanonicalEnumLiteral() {
        assertThatThrownBy(() -> service.create(USER, request(new BigDecimal("175.00"), List.of("barbell"), null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat((List<?>) e.payload()).extracting(f -> ((FieldError) f).code())
                            .containsExactly("INVALID_ENUM_VALUE");
                });

        assertThatThrownBy(() -> service.update(USER,
                new UpsertProfileRequest(new BigDecimal("175.00"), null, null, null, null, "beginner", 3)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat((List<?>) e.payload()).extracting(f -> ((FieldError) f).field())
                                .containsExactly("trainingLevel"));

        verify(profileMapper, never()).updateById(any(UserProfileEntity.class));
    }

    @Test
    @DisplayName("PUT 携带过期 lockVersion → 409，且不写库")
    void staleLockVersionRejected() {
        when(profileMapper.selectOne(any())).thenReturn(storedProfile(USER));

        assertThatThrownBy(() -> service.update(USER, request(new BigDecimal("180.00"), null, 2)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));
        verify(profileMapper, never()).updateById(any(UserProfileEntity.class));
        verify(accountMapper, never()).incrementDataRevision(anyLong());
    }

    @Test
    @DisplayName("PUT 匹配 lockVersion 才更新；@Version 拦下并发写时同样转 409")
    void updateWithMatchingVersion() {
        when(profileMapper.selectOne(any())).thenReturn(storedProfile(USER));
        when(profileMapper.updateById(any(UserProfileEntity.class))).thenReturn(1);

        ProfileVo vo = service.update(USER, request(new BigDecimal("180.00"), List.of("DUMBBELL"), 3));

        verify(profileMapper).updateById(any(UserProfileEntity.class));
        verify(accountMapper).incrementDataRevision(USER);
        // 真实库里回读到的就是刚写入的值；单测里 mock 返回同一个被改写的实例，因此断言提交值
        assertThat(vo.heightCm()).isEqualByComparingTo("180.00");
    }

    @Test
    @DisplayName("更新影响 0 行（并发抢先）→ 409，不加修订号")
    void updateLosingRaceBecomesVersionConflict() {
        UserProfileEntity entity = storedProfile(USER);
        when(profileMapper.selectOne(any())).thenReturn(entity);
        when(profileMapper.updateById(any(UserProfileEntity.class))).thenReturn(0);

        assertThatThrownBy(() -> service.update(USER, request(new BigDecimal("180.00"), null, 3)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));
        verify(accountMapper, never()).incrementDataRevision(anyLong());
    }

    @Test
    @DisplayName("没有档案就 PUT → RESOURCE_NOT_FOUND（与越权同形）")
    void updateWithoutProfileIsNotFound() {
        when(profileMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.update(USER, request(new BigDecimal("180.00"), null, 0)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("档案读取条件始终带调用者的 user_id，他人档案取不到")
    void readIsAlwaysScopedToTheCaller() {
        // 真实数据库里 (id,user_id) 条件不匹配的他人行会返回 null；这里模拟"条件命中了才有一行"
        when(profileMapper.selectOne(any())).thenReturn(storedProfile(USER));

        ProfileVo vo = service.find(USER);

        ArgumentCaptor<QueryWrapper<UserProfileEntity>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(profileMapper).selectOne(captor.capture());
        QueryWrapper<UserProfileEntity> wrapper = captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("user_id");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(USER);
        assertThat(wrapper.getParamNameValuePairs().values()).doesNotContain(OTHER_USER);
        assertThat(vo).isNotNull();
    }

    @Test
    @DisplayName("他人 user_id 查不到行时是 null/404，不会把别人的档案当自己的返回")
    void otherUsersProfileIsInvisible() {
        when(profileMapper.selectOne(any())).thenReturn(null);

        assertThat(service.find(USER)).isNull();
        assertThatThrownBy(() -> service.update(USER, request(new BigDecimal("180.00"), null, 0)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
