package com.hfj.fitagent.modules.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.auth.mapper.UserAccountMapper;
import com.hfj.fitagent.modules.profile.dto.FieldErrors;
import com.hfj.fitagent.modules.profile.dto.UpsertProfileRequest;
import com.hfj.fitagent.modules.profile.dto.Vocabulary;
import com.hfj.fitagent.modules.profile.entity.UserProfileEntity;
import com.hfj.fitagent.modules.profile.mapper.UserProfileMapper;
import com.hfj.fitagent.modules.profile.vo.ProfileVo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 身体档案（user_profile，与用户 1:1）。
 *
 * <p>写入面收窄到契约允许的六个字段：身高、训练经验、器械、出生日期、性别参数、训练限制备注。
 * {@code activityCategory} / {@code dietPreferences} / {@code professionalGuidanceRequired} 本轮没有消费方，
 * 请求 DTO 里根本没有这些字段，因此它们在结构上不可能被写入——而不是"记得不写"。
 * {@code nickname} 属于 user_account，不在本模块写（跨模块，见交付说明）。
 *
 * <p>PUT 是全量替换：省略即清空。可空业务列在实体上是 {@code updateStrategy=ALWAYS}，
 * 用户真的把器械清掉时会写成 NULL，而不是被 NOT_NULL 策略跳过导致"删不掉"。
 * 两个 NOT NULL 的 ENUM 列（training_level / sex_for_equation）在省略时落回数据库默认值，
 * 这是与 V2 迁移一致的确定性行为，不是隐式猜测。
 */
@Service
public class UserProfileService {

    private final UserProfileMapper profileMapper;
    private final UserAccountMapper accountMapper;

    public UserProfileService(UserProfileMapper profileMapper, UserAccountMapper accountMapper) {
        this.profileMapper = profileMapper;
        this.accountMapper = accountMapper;
    }

    /**
     * 无档案返回 null。这是正常空状态（页面显示"建立档案"），不是 404——
     * 区分"没有档案"与"没权限"是契约 docs/p1-api.md §4.1 的硬要求。
     */
    @Transactional(readOnly = true)
    public ProfileVo find(long userId) {
        return ProfileVo.from(selectByUserId(userId));
    }

    @Transactional
    public ProfileVo create(long userId, UpsertProfileRequest req) {
        // 创建没有既有版本可比：带 lockVersion 就是想让服务端拿它做比较，按服务端字段拒绝
        if (req.lockVersion() != null) {
            throw FieldErrors.forbiddenServerField("lockVersion", req.lockVersion());
        }
        validate(req);

        UserProfileEntity entity = new UserProfileEntity();
        entity.setUserId(userId);
        applyWritableFields(entity, req);
        try {
            profileMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // uq_user_profile_3 (user_id) 是一人一档的正确性来源，不是提示性检查：
            // 并发首次创建只有一方胜出，败方按 409 重新读取后改走 PUT
            throw new BizException(ErrorCode.VERSION_CONFLICT, "档案已存在，请重新读取后改用 PUT 修改");
        }
        AccountRowGuard.bumpDataRevision(accountMapper, userId);
        // 回读而不是直接用入参实体：createdAt/updatedAt 与 ENUM 默认值都由数据库产生
        return ProfileVo.from(requireByUserId(userId));
    }

    @Transactional
    public ProfileVo update(long userId, UpsertProfileRequest req) {
        if (req.lockVersion() == null) {
            throw FieldErrors.field("lockVersion", "REQUIRED", "更新档案必须携带 lockVersion", null);
        }
        validate(req);

        UserProfileEntity entity = requireByUserId(userId);
        if (!Objects.equals(entity.getLockVersion(), req.lockVersion())) {
            throw BizException.versionConflict();
        }
        applyWritableFields(entity, req);
        // 未开放的列保持原值：updateById 会带着读回来的 activityCategory / dietPreferences /
        // professionalGuidanceRequired 原样写回，值不变，等价于不触碰。
        if (profileMapper.updateById(entity) == 0) {
            throw BizException.versionConflict();
        }
        AccountRowGuard.bumpDataRevision(accountMapper, userId);
        return ProfileVo.from(requireByUserId(userId));
    }

    /**
     * 只写契约允许的字段。枚举取值必须精确命中大写字面量（docs/p1-database.md §5 补充规则 1）。
     */
    private void validate(UpsertProfileRequest req) {
        FieldErrors.requireEnum("trainingLevel", req.trainingLevel(), Vocabulary.TRAINING_LEVEL);
        FieldErrors.requireEnum("sexForEquation", req.sexForEquation(), Vocabulary.SEX_FOR_EQUATION);
        FieldErrors.requireEnumList("equipment", req.equipment(), Vocabulary.EQUIPMENT);
    }

    private void applyWritableFields(UserProfileEntity entity, UpsertProfileRequest req) {
        entity.setHeightCm(req.heightCm());
        entity.setBirthDate(req.birthDate());
        entity.setEquipment(req.equipment() == null ? null : List.copyOf(req.equipment()));
        entity.setTrainingConstraints(
                req.trainingConstraints() == null ? null : List.copyOf(req.trainingConstraints()));
        entity.setTrainingLevel(
                req.trainingLevel() == null ? Vocabulary.DEFAULT_TRAINING_LEVEL : req.trainingLevel());
        entity.setSexForEquation(
                req.sexForEquation() == null ? Vocabulary.DEFAULT_SEX_FOR_EQUATION : req.sexForEquation());
    }

    private UserProfileEntity selectByUserId(long userId) {
        // 用 QueryWrapper 而不是 LambdaQueryWrapper：列名是这里的常量字面量，不来自任何输入，
        // 而 Lambda 形式要在解析 getter→列名时读 TableInfo 缓存，脱离 MyBatis 上下文就不可用（单测会崩）
        return profileMapper.selectOne(new QueryWrapper<UserProfileEntity>().eq("user_id", userId));
    }

    /** 没有档案就改档案 = 资源不存在（与越权同形，不泄露是否存在）。 */
    private UserProfileEntity requireByUserId(long userId) {
        UserProfileEntity entity = selectByUserId(userId);
        if (entity == null) {
            throw BizException.notFound();
        }
        return entity;
    }
}
