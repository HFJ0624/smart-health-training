package com.hfj.fitagent.modules.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.web.PageQuery;
import com.hfj.fitagent.common.web.PageResult;
import com.hfj.fitagent.common.web.SortColumns;
import com.hfj.fitagent.modules.auth.mapper.UserAccountMapper;
import com.hfj.fitagent.modules.profile.dto.CreateGoalRequest;
import com.hfj.fitagent.modules.profile.dto.FieldErrors;
import com.hfj.fitagent.modules.profile.dto.UpdateGoalRequest;
import com.hfj.fitagent.modules.profile.dto.Vocabulary;
import com.hfj.fitagent.modules.profile.entity.UserGoalEntity;
import com.hfj.fitagent.modules.profile.mapper.UserGoalMapper;
import com.hfj.fitagent.modules.profile.vo.GoalVo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 基础目标（user_goal）。
 *
 * <p>状态机：{@code DRAFT → ACTIVE → ARCHIVED}。就地改 DRAFT/ACTIVE，ARCHIVED 只读留史；
 * 物理删除只允许 DRAFT。与设计包的一处不一致需要知情：user_goal 没有 plan 那套
 * {@code plan_group_uuid/revision_no} 版本机制，所以目标是"就地改 + 归档留史"（docs/p1-api.md §4.3）。
 *
 * <p>"每用户最多一个 ACTIVE" 由 {@code UNIQUE(active_user_id)} 生成列保证，本类<b>从不</b>写该列：
 * 实体里根本没有声明它，所以 ORM 连拼出这条 SQL 的机会都没有。激活事务内的顺序固定为
 * 锁 user_account → 归档旧 ACTIVE → 激活本草稿，反过来的话同一事务会先看到两行同 user_id 而直接撞唯一索引。
 */
@Service
public class UserGoalService {

    private final UserGoalMapper goalMapper;
    private final UserAccountMapper accountMapper;

    public UserGoalService(UserGoalMapper goalMapper, UserAccountMapper accountMapper) {
        this.goalMapper = goalMapper;
        this.accountMapper = accountMapper;
    }

    /**
     * 历史分页。排序列只来自 {@code columns} 白名单映射后的数据库列名，请求侧的字段名永不进入 SQL。
     *
     * @param status 空表示全部状态；非法取值报 INVALID_ENUM_VALUE
     */
    @Transactional(readOnly = true)
    public PageResult<GoalVo> list(long userId, PageQuery query, SortColumns columns, String status) {
        String normalized = normalizeStatusFilter(status);
        QueryWrapper<UserGoalEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        if (normalized != null) {
            wrapper.eq("status", normalized);
        }
        applyOrder(query, columns, wrapper);

        IPage<UserGoalEntity> result = goalMapper.selectPage(new Page<>(query.page(), query.size()), wrapper);
        List<GoalVo> items = result.getRecords().stream().map(GoalVo::from).toList();
        return PageResult.from(result, items);
    }

    /** 当前 ACTIVE，无则 null（首页显示"未设置目标"，不是 404）。 */
    @Transactional(readOnly = true)
    public GoalVo findActive(long userId) {
        Long activeId = goalMapper.findActiveId(userId);
        return activeId == null ? null : GoalVo.from(load(userId, activeId));
    }

    /**
     * 新建目标。{@code activate=true}（契约默认值）时在同一事务里替换当前 ACTIVE；
     * 先落 DRAFT 再走既有的"归档 + 激活"两步，是为了让激活唯一入口只有一条路径。
     */
    @Transactional
    public GoalVo create(long userId, CreateGoalRequest req) {
        FieldErrors.requireEnum("goalType", req.goalType(), Vocabulary.GOAL_TYPE);
        requireDateOrder(req.startsOn(), req.endsOn());

        UserGoalEntity entity = new UserGoalEntity();
        entity.setUserId(userId);
        entity.setGoalType(req.goalType());
        entity.setTargetWeightKg(req.targetWeightKg());
        entity.setWeeklySessionsTarget(req.weeklySessionsTarget());
        entity.setStartsOn(req.startsOn());
        entity.setEndsOn(req.endsOn());
        entity.setStatus(Vocabulary.STATUS_DRAFT);
        // daily_intake_target_kcal 不设置：本轮接口不暴露饮食目标，列保留（NULL）

        try {
            goalMapper.insert(entity);
            if (req.activateOrDefault()) {
                activateDraftInTransaction(userId, entity.getId(), initialLockVersion(entity));
            }
        } catch (DuplicateKeyException e) {
            // 只可能是 UNIQUE(active_user_id) 拦下并发激活：整个事务回滚，客户端重读后再试
            throw new BizException(ErrorCode.VERSION_CONFLICT, "已有目标被并发激活，请重新读取后重试");
        }
        AccountRowGuard.bumpDataRevision(accountMapper, userId);
        return GoalVo.from(load(userId, entity.getId()));
    }

    /** DRAFT 与 ACTIVE 可就地改；ARCHIVED 一律拒绝（改历史等于伪造历史）。 */
    @Transactional
    public GoalVo update(long userId, long id, UpdateGoalRequest req) {
        FieldErrors.requireEnum("goalType", req.goalType(), Vocabulary.GOAL_TYPE);
        requireDateOrder(req.startsOn(), req.endsOn());

        UserGoalEntity entity = load(userId, id);
        requireEditable(entity);
        if (!Objects.equals(entity.getLockVersion(), req.lockVersion())) {
            throw BizException.versionConflict();
        }
        entity.setGoalType(req.goalType());
        entity.setTargetWeightKg(req.targetWeightKg());
        entity.setWeeklySessionsTarget(req.weeklySessionsTarget());
        entity.setStartsOn(req.startsOn());
        entity.setEndsOn(req.endsOn());
        // status 不在 PUT 里改：状态迁移只有 activate/archive 两个显式动作。
        // dailyIntakeTargetKcal 也不在这里写：它在本轮永为 NULL（实体是 select=false），
        // 但 updateById 会因为该列的 updateStrategy=ALWAYS 把它写成 NULL —— 本轮没有非空值可丢，
        // P2 一旦开始写饮食目标，这个字段必须先在实体层修（详见交付说明的"基础层遗留问题"）。
        if (goalMapper.updateById(entity) == 0) {
            throw BizException.versionConflict();
        }
        AccountRowGuard.bumpDataRevision(accountMapper, userId);
        return GoalVo.from(load(userId, id));
    }

    /** 只有 DRAFT 可以物理删除；ACTIVE 走 archive 留史，ARCHIVED 是终态。 */
    @Transactional
    public void delete(long userId, long id) {
        UserGoalEntity entity = load(userId, id);
        if (!Vocabulary.STATUS_DRAFT.equals(entity.getStatus())) {
            throw BizException.invalidTransition("只有草稿目标可以删除，生效中的目标请归档");
        }
        if (goalMapper.delete(new QueryWrapper<UserGoalEntity>()
                .eq("id", id)
                .eq("user_id", userId)) == 0) {
            throw BizException.notFound();
        }
        AccountRowGuard.bumpDataRevision(accountMapper, userId);
    }

    @Transactional
    public GoalVo activate(long userId, long id, Integer lockVersion) {
        requireLockVersion(lockVersion);
        UserGoalEntity entity = load(userId, id);
        requireDraft(entity);
        if (!Objects.equals(entity.getLockVersion(), lockVersion)) {
            throw BizException.versionConflict();
        }
        try {
            activateDraftInTransaction(userId, id, lockVersion);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.VERSION_CONFLICT, "已有目标被并发激活，请重新读取后重试");
        }
        AccountRowGuard.bumpDataRevision(accountMapper, userId);
        return GoalVo.from(load(userId, id));
    }

    /** 仅 ACTIVE 可归档；归档后首页显示"未设置目标"。 */
    @Transactional
    public GoalVo archive(long userId, long id, Integer lockVersion) {
        requireLockVersion(lockVersion);
        UserGoalEntity entity = load(userId, id);
        if (!Vocabulary.STATUS_ACTIVE.equals(entity.getStatus())) {
            throw BizException.invalidTransition("只有生效中的目标可以归档");
        }
        if (!Objects.equals(entity.getLockVersion(), lockVersion)) {
            throw BizException.versionConflict();
        }
        // 归档不会违反 UNIQUE(active_user_id)（NULL 不参与唯一性），但仍先锁账户行，
        // 与其它迁移共用同一加锁顺序，避免与并发激活互相等待成环
        AccountRowGuard.lockAccount(accountMapper, userId);
        entity.setStatus(Vocabulary.STATUS_ARCHIVED);
        if (goalMapper.updateById(entity) == 0) {
            throw BizException.versionConflict();
        }
        AccountRowGuard.bumpDataRevision(accountMapper, userId);
        return GoalVo.from(load(userId, id));
    }

    /**
     * 激活的唯一路径：锁账户行 → 归档旧 ACTIVE → 激活草稿。三步必须在同一事务、同一顺序，
     * 顺序颠倒会让 UNIQUE(active_user_id) 在事务内就看到两行同 user_id。
     */
    private void activateDraftInTransaction(long userId, long goalId, int lockVersion) {
        AccountRowGuard.lockAccount(accountMapper, userId);
        goalMapper.archiveActive(userId);
        if (goalMapper.activateDraft(goalId, userId, lockVersion) == 0) {
            throw BizException.versionConflict();
        }
    }

    private static int initialLockVersion(UserGoalEntity entity) {
        // 新建行的 lock_version 由 @Version 初始化为 0；显式兜底，避免依赖拦截器的赋值时机
        return entity.getLockVersion() == null ? 0 : entity.getLockVersion();
    }

    private void applyOrder(PageQuery query, SortColumns columns, QueryWrapper<UserGoalEntity> wrapper) {
        // 排序列名只可能来自 SortColumns 的静态映射，请求侧的字段名不会直接进入 SQL
        if (query.direction() == PageQuery.Direction.ASC) {
            wrapper.orderByAsc(query.column(columns));
        } else {
            wrapper.orderByDesc(query.column(columns));
        }
        // 排序列同值时用 id 兜底，保证翻页确定性（否则同 created_at 的行可能重复或漏出）
        wrapper.orderByDesc("id");
    }

    private String normalizeStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim();
        FieldErrors.requireEnum("status", value, Vocabulary.GOAL_STATUS);
        return value;
    }

    private void requireLockVersion(Integer lockVersion) {
        if (lockVersion == null) {
            throw FieldErrors.field("lockVersion", "REQUIRED", "状态迁移必须携带 lockVersion", null);
        }
    }

    private void requireDateOrder(LocalDate startsOn, LocalDate endsOn) {
        if (endsOn != null && endsOn.isBefore(startsOn)) {
            throw FieldErrors.field("endsOn", "ENDS_BEFORE_STARTS", "结束日期不能早于开始日期", endsOn.toString());
        }
    }

    private void requireEditable(UserGoalEntity entity) {
        if (Vocabulary.STATUS_ARCHIVED.equals(entity.getStatus())) {
            throw BizException.invalidTransition("已归档的目标不可修改");
        }
    }

    private void requireDraft(UserGoalEntity entity) {
        if (!Vocabulary.STATUS_DRAFT.equals(entity.getStatus())) {
            throw BizException.invalidTransition("只有草稿目标可以激活");
        }
    }

    /**
     * (id, user_id) 复合条件读取：别人的 id 与不存在的 id 返回同一个结果（404 RESOURCE_NOT_FOUND），
     * 不区分"不存在"和"越权"，避免泄露资源是否存在（docs/p1-api.md §1 越权条目）。
     */
    private UserGoalEntity load(long userId, long id) {
        UserGoalEntity entity = goalMapper.selectOne(new QueryWrapper<UserGoalEntity>()
                .eq("id", id)
                .eq("user_id", userId));
        if (entity == null) {
            throw BizException.notFound();
        }
        return entity;
    }
}
