package com.hfj.fitagent.modules.training;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hfj.fitagent.common.AppClock;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.util.Ids;
import com.hfj.fitagent.common.web.FieldError;
import com.hfj.fitagent.common.web.PageQuery;
import com.hfj.fitagent.common.web.PageResult;
import com.hfj.fitagent.common.web.SortColumns;
import com.hfj.fitagent.modules.profile.api.ProfileContextReader;
import com.hfj.fitagent.modules.training.dto.PlanDraftSpec;
import com.hfj.fitagent.modules.training.dto.PlanUpsertRequest;
import com.hfj.fitagent.modules.training.entity.ExerciseReferenceEntity;
import com.hfj.fitagent.modules.training.entity.GoalSnapshot;
import com.hfj.fitagent.modules.training.entity.TrainingPlanDayEntity;
import com.hfj.fitagent.modules.training.entity.TrainingPlanEntity;
import com.hfj.fitagent.modules.training.entity.TrainingPlanItemEntity;
import com.hfj.fitagent.modules.training.mapper.TrainingPlanDayMapper;
import com.hfj.fitagent.modules.training.mapper.TrainingPlanItemMapper;
import com.hfj.fitagent.modules.training.mapper.TrainingPlanMapper;
import com.hfj.fitagent.modules.training.vo.GoalSnapshotVo;
import com.hfj.fitagent.modules.training.vo.PlanDayVo;
import com.hfj.fitagent.modules.training.vo.PlanDetailVo;
import com.hfj.fitagent.modules.training.vo.PlanItemVo;
import com.hfj.fitagent.modules.training.vo.PlanListVo;
import com.hfj.fitagent.modules.training.vo.TodayPlanVo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 训练计划聚合引擎：版本头 + 计划日 + 动作条目作为一个聚合读写（docs/p1-api.md §7）。
 *
 * <p>三条不可让步的规则在这里落地：
 * <ol>
 *   <li><b>聚合完整性</b>：头/日/条目同事务，任一失败整体回滚，不留半份计划（验收 A9/A10）；</li>
 *   <li><b>版本不可覆盖</b>：ACTIVE/ARCHIVED 只能"另存为新草稿"，来源版本一行都不改（验收 A11）；</li>
 *   <li><b>每用户最多一个 ACTIVE</b>：激活事务按固定顺序加锁（先 user_account，再计划行），
 *       {@code UNIQUE(active_user_id)} 作最终防线，冲突译成 ACTIVE_PLAN_EXISTS（验收 A12）。
 *       {@code active_user_id} 是数据库 STORED 生成列，实体里根本没有这个字段，
 *       因此 ORM 不可能写它（验收 A13）。</li>
 * </ol>
 *
 * <p>删除子行的顺序必须是<b>先 item 后 day</b>：day 被 item 以外键 RESTRICT 引用，
 * 反过来会当场触发外键错误、让整个事务回滚（见 {@code TrainingPlanItemMapper#deleteByPlan} 的注释）。
 * P1 之所以能"删旧插新"，是因为还没有 workout_session 引用计划日；P2 接入实际打卡后必须改成增量更新，
 * 这条约束已记在 docs/p1-database.md §6 与 docs/p1-architecture.md §7。
 *
 * <p>身份一律来自方法入参 {@code userId}，而调用方（控制器）只从认证上下文取值：
 * 请求体与查询参数里没有 userId 这个入口。
 */
@Service
public class TrainingPlanService {

    static final String SOURCE_MANUAL = "MANUAL";
    static final String SOURCE_IMPORT = "IMPORT";

    private static final String COPY_TITLE_SUFFIX = "（副本）";
    private static final int PLAN_TITLE_MAX = PlanAggregateValidator.PLAN_TITLE_MAX;

    /** goal_snapshot 写入前的体积护栏（docs/p1-database.md §4.4：JSON 必须校验版本和体积）。 */
    private static final int GOAL_SNAPSHOT_MAX_BYTES = 8 * 1024;

    /** 排序白名单：请求字段名 → 数据库列名。 */
    private static final SortColumns LIST_SORT = SortColumns.of(
            "updatedAt", "updated_at",
            "createdAt", "created_at",
            "revisionNo", "revision_no");

    private final TrainingPlanMapper planMapper;
    private final TrainingPlanDayMapper dayMapper;
    private final TrainingPlanItemMapper itemMapper;
    private final ExerciseDictionary dictionary;
    private final PlanAggregateValidator validator;
    private final ProfileContextReader profileContext;
    private final PlanRevisionTicker revisionTicker;
    private final AppClock clock;
    private final ObjectMapper objectMapper;

    public TrainingPlanService(TrainingPlanMapper planMapper,
                               TrainingPlanDayMapper dayMapper,
                               TrainingPlanItemMapper itemMapper,
                               ExerciseDictionary dictionary,
                               PlanAggregateValidator validator,
                               ProfileContextReader profileContext,
                               PlanRevisionTicker revisionTicker,
                               AppClock clock,
                               ObjectMapper objectMapper) {
        this.planMapper = planMapper;
        this.dayMapper = dayMapper;
        this.itemMapper = itemMapper;
        this.dictionary = dictionary;
        this.validator = validator;
        this.profileContext = profileContext;
        this.revisionTicker = revisionTicker;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    // ==================== 新建 / 就地编辑 ====================

    /**
     * 手工新建草稿：{@code status=DRAFT}、{@code source=MANUAL}、服务端生成小写
     * {@code planGroupUuid}、{@code revisionNo=1}、{@code basePlanId=null}，
     * {@code goalSnapshot} 由当前档案与 ACTIVE 目标现场抓取（缺失项进 absentFields，不补造）。
     */
    @Transactional
    public PlanDetailVo create(long userId, PlanUpsertRequest req) {
        PlanValidationOutcome outcome = requireValid(toSpec(req));
        long planId = insertAggregate(userId, trim(req.title()), req.startsOn(), req.endsOn(),
                SOURCE_MANUAL, PlanStatus.DRAFT, Ids.newUuid(), 1, null, ofOutcome(outcome));
        return loadAggregate(userId, planId);
    }

    /**
     * 就地编辑，<b>仅 DRAFT</b>。聚合替换：先删条目、再删计划日、然后按入参重建，全部在同一事务内。
     *
     * <p>头行的乐观锁由实体上的 {@code @Version} 守卫（影响 0 行即 VERSION_CONFLICT，不重试覆盖）；
     * 子行的重建不需要版本条件——它们只属于这一行头，且整体替换。
     */
    @Transactional
    public PlanDetailVo update(long userId, long id, PlanUpsertRequest req) {
        TrainingPlanEntity header = requireOwned(userId, id);
        requireStatus(header, PlanStatus.DRAFT, "只有草稿可以就地编辑；生效或已归档的版本请另存为新草稿");
        if (req.lockVersion() == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "就地编辑必须携带 lockVersion",
                    List.of(FieldError.of("lockVersion", PlanImportErrorCode.REQUIRED, "lockVersion 必填")));
        }
        if (!req.lockVersion().equals(header.getLockVersion())) {
            throw BizException.versionConflict();
        }

        PlanValidationOutcome outcome = requireValid(toSpec(req));

        itemMapper.deleteByPlan(id, userId);
        dayMapper.deleteByPlan(id, userId);

        header.setTitle(trim(req.title()));
        header.setStartsOn(req.startsOn());
        header.setEndsOn(req.endsOn());
        if (planMapper.updateById(header) == 0) {
            throw BizException.versionConflict();
        }
        insertDays(userId, id, ofOutcome(outcome));
        revisionTicker.bump(userId);
        return loadAggregate(userId, id);
    }

    // ==================== 版本：revise / copy ====================

    /**
     * 基于 ACTIVE/ARCHIVED 生成<b>同组</b>新草稿：{@code revisionNo = 组内 max + 1}、
     * {@code basePlanId = 来源版本 id}。
     *
     * <p>对来源版本<b>只读不写</b>（验收 A11）：任何"就地改生效版本"的写法都会让用户已经
     * 打卡依据的计划内容凭空变化，历史就不可信了。
     */
    @Transactional
    public PlanDetailVo revise(long userId, long id) {
        TrainingPlanEntity source = requireOwned(userId, id);
        PlanStatus status = PlanStatus.parseStrict(source.getStatus());
        if (status != PlanStatus.ACTIVE && status != PlanStatus.ARCHIVED) {
            throw BizException.invalidTransition("只有生效中或已归档的版本可以另存为新草稿；草稿请直接编辑");
        }
        Integer max = planMapper.findMaxRevisionNo(userId, source.getPlanGroupUuid());
        int revisionNo = (max == null ? source.getRevisionNo() : max) + 1;

        long planId = insertAggregate(userId, source.getTitle(), source.getStartsOn(), source.getEndsOn(),
                source.getSource(), PlanStatus.DRAFT, source.getPlanGroupUuid(), revisionNo, id,
                copyOf(userId, id));
        return loadAggregate(userId, planId);
    }

    /**
     * 复制成<b>新计划组</b>：全新的 {@code planGroupUuid}、{@code revisionNo=1}、
     * 标题追加"（副本）"、{@code source} 保留原值。任意状态都可复制（含 DISCARDED）。
     *
     * <p>{@code basePlanId} 留 null：它的语义是"同一组内基于哪一版改的"，
     * 跨组填它会把版本链与计划组链两套含义混在一起（docs/p1-database.md §3.6）。
     */
    @Transactional
    public PlanDetailVo copy(long userId, long id) {
        TrainingPlanEntity source = requireOwned(userId, id);
        long planId = insertAggregate(userId, copyTitle(source.getTitle()), source.getStartsOn(),
                source.getEndsOn(), source.getSource(), PlanStatus.DRAFT, Ids.newUuid(), 1, null,
                copyOf(userId, id));
        return loadAggregate(userId, planId);
    }

    // ==================== 状态迁移 ====================

    /**
     * 确认生效。事务内顺序是硬性的（docs/p1-database.md §6）：
     * {@code lockAccount}（SELECT ... FOR UPDATE，把同一用户的状态迁移串行化）→
     * 校验归属与版本 → {@code archiveActive}（旧 ACTIVE 让出唯一索引位）→ {@code activateDraft}。
     *
     * <p>{@code archiveActive} 必须排在前面：否则新草稿写入 ACTIVE 时
     * {@code UNIQUE(active_user_id)} 当场冲突，激活永远不成功。
     *
     * <p>并发两路激活时，后到的那条撞唯一索引，异常被译成 409 ACTIVE_PLAN_EXISTS 并让整个事务回滚，
     * 因此"归档 + 激活"要么整体生效、要么整体不生效，库里始终只剩一个 ACTIVE（验收 A12）。
     */
    @Transactional
    public PlanDetailVo activate(long userId, long id, int lockVersion) {
        Long account = planMapper.lockAccount(userId);
        if (account == null) {
            throw BizException.notFound();
        }
        Long owned = planMapper.lockPlan(id, userId);
        if (owned == null) {
            throw BizException.notFound();
        }
        TrainingPlanEntity header = requireOwned(userId, id);
        requireStatus(header, PlanStatus.DRAFT, "只有草稿可以确认生效");
        if (!Integer.valueOf(lockVersion).equals(header.getLockVersion())) {
            throw BizException.versionConflict();
        }
        try {
            planMapper.archiveActive(userId);
            int activated = planMapper.activateDraft(id, userId, lockVersion, clock.now());
            if (activated == 0) {
                throw BizException.versionConflict();
            }
        } catch (DuplicateKeyException e) {
            // uq_training_plan_4(active_user_id)：并发激活只有一路能赢
            throw new BizException(ErrorCode.ACTIVE_PLAN_EXISTS,
                    "已存在生效中的计划，本次激活已整体回滚；请刷新后确认替换");
        }
        revisionTicker.bump(userId);
        return loadAggregate(userId, id);
    }

    /** 归档当前生效计划：仅 ACTIVE。归档后首页显示"未设置计划"。 */
    @Transactional
    public PlanDetailVo archive(long userId, long id, int lockVersion) {
        TrainingPlanEntity header = requireOwned(userId, id);
        requireStatus(header, PlanStatus.ACTIVE, "只有生效中的计划可以归档");
        if (planMapper.archiveById(id, userId, lockVersion) == 0) {
            throw BizException.versionConflict();
        }
        revisionTicker.bump(userId);
        return loadAggregate(userId, id);
    }

    /**
     * 丢弃草稿：仅 DRAFT，状态置 {@code DISCARDED} 并<b>保留全部行</b>。
     * 不提供物理删除——历史版本一旦能删，"计划不可覆盖"就无法证明。
     */
    @Transactional
    public PlanDetailVo discard(long userId, long id, int lockVersion) {
        TrainingPlanEntity header = requireOwned(userId, id);
        requireStatus(header, PlanStatus.DRAFT, "只有草稿可以丢弃");
        if (planMapper.discardDraft(id, userId, lockVersion) == 0) {
            throw BizException.versionConflict();
        }
        revisionTicker.bump(userId);
        return loadAggregate(userId, id);
    }

    // ==================== 导入落库（供 PlanImportService 调用） ====================

    /**
     * 把已完整校验的导入聚合成草稿：{@code status=DRAFT}、{@code source=IMPORT}，
     * 组、版本号与目标快照由服务端生成——<b>导入不可能直接把计划变成 ACTIVE</b>
     * （docs/p1-plan-import.md §7）。
     *
     * <p>幂等由调用方负责；本方法只保证头 + 日 + 条目在同一事务里齐全写入。
     * 事务边界刻意留在这里而不是包住整个 confirm：Redis 的幂等记账不该被数据库回滚牵着走。
     *
     * @return 新计划 id
     */
    @Transactional
    public long saveImported(long userId, PlanValidationOutcome outcome, String title,
                             LocalDate startsOn, LocalDate endsOn) {
        return insertAggregate(userId, title, startsOn, endsOn, SOURCE_IMPORT, PlanStatus.DRAFT,
                Ids.newUuid(), 1, null, ofOutcome(outcome));
    }

    // ==================== 读取 ====================

    @Transactional(readOnly = true)
    public PlanDetailVo findById(long userId, long id) {
        return loadAggregate(userId, id);
    }

    /** 当前生效计划（完整聚合）；无 ACTIVE 返回 null，接口层据此给出"未设置计划"的空状态。 */
    @Transactional(readOnly = true)
    public PlanDetailVo findActive(long userId) {
        Long id = planMapper.findActiveId(userId);
        return id == null ? null : loadAggregate(userId, id);
    }

    /** 版本头（导入幂等重放用它补 revisionNo/status，不必读整个聚合）。 */
    @Transactional(readOnly = true)
    public PlanListVo findHeader(long userId, long planId) {
        return toListVo(requireOwned(userId, planId));
    }

    /**
     * 「今天的计划」：今天 = {@code clock.businessDate(zone)} 的 ISO 星期，在<b>当前 ACTIVE</b>
     * 计划里查对应的计划日。
     *
     * <p>三种返回形态（docs/p1-api.md §7）：
     * 无 ACTIVE 计划 → {@code activePlanId=null}；
     * 有 ACTIVE 但该星期没排程、或 {@code endsOn} 已过 → {@code day=null} 而计划标识仍给出，
     * 页面才能区分"今天休息"与"根本没计划"；
     * 正常 → {@code day} 给出当天条目（休息日的 items 为空数组）。
     */
    @Transactional(readOnly = true)
    public TodayPlanVo findToday(long userId, ZoneId zone) {
        LocalDate today = clock.businessDate(zone);
        int isoWeekday = today.getDayOfWeek().getValue();
        Long activeId = planMapper.findActiveId(userId);
        if (activeId == null) {
            return new TodayPlanVo(null, null, null, isoWeekday, today, null);
        }
        TrainingPlanEntity header = requireOwned(userId, activeId);
        boolean expired = header.getEndsOn() != null && today.isAfter(header.getEndsOn());
        if (expired) {
            return new TodayPlanVo(str(header.getId()), header.getPlanGroupUuid(), header.getRevisionNo(),
                    isoWeekday, today, null);
        }
        TrainingPlanDayEntity dayRow = dayMapper.findByPlanAndWeekday(header.getId(), userId, isoWeekday);
        if (dayRow == null) {
            return new TodayPlanVo(str(header.getId()), header.getPlanGroupUuid(), header.getRevisionNo(),
                    isoWeekday, today, null);
        }
        List<TrainingPlanItemEntity> items = itemMapper.listByDay(dayRow.getId(), userId);
        PlanDayVo day = toDayVo(dayRow, items, dictionary.loadByIds(exerciseIds(items)));
        return new TodayPlanVo(str(header.getId()), header.getPlanGroupUuid(), header.getRevisionNo(),
                isoWeekday, today, day);
    }

    /**
     * 版本列表：默认含全部状态（含 ARCHIVED/DISCARDED），{@code status} 支持逗号分隔多选且必须是
     * 四个大写字面量之一；{@code groupId} 先规范化成小写 UUID 再查；
     * 排序列只从 {@link #LIST_SORT} 白名单取，绝不把请求字段名当列名。
     */
    @Transactional(readOnly = true)
    public PageResult<PlanListVo> list(long userId, String statusCsv, String groupId,
                                       Integer page, Integer size, String sort) {
        PageQuery query = PageQuery.of(page, size, defaultSort(sort), LIST_SORT.allowedFields(), "updatedAt");
        QueryWrapper<TrainingPlanEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        List<String> statuses = parseStatuses(statusCsv);
        if (!statuses.isEmpty()) {
            wrapper.in("status", statuses);
        }
        if (!Ids.isBlank(groupId)) {
            wrapper.eq("plan_group_uuid", canonicalGroupId(groupId));
        }
        boolean asc = query.direction() == PageQuery.Direction.ASC;
        wrapper.orderBy(true, asc, query.column(LIST_SORT));
        // 同一列值大量相等时（revisionNo 相同的跨组记录很常见）追加主键，保证翻页稳定
        wrapper.orderBy(true, asc, "id");

        Page<TrainingPlanEntity> rows = planMapper.selectPage(new Page<>(query.page(), query.size()), wrapper);
        List<PlanListVo> items = new ArrayList<>(rows.getRecords().size());
        for (TrainingPlanEntity row : rows.getRecords()) {
            items.add(toListVo(row));
        }
        return PageResult.from(rows, items);
    }

    // ==================== 校验与规格转换 ====================

    /**
     * 手工提交 → 中性聚合草案，好让导入与手工共用同一套规则（{@link PlanAggregateValidator}）。
     *
     * <p>顶层 notes 传 null：P1 的 training_plan 表没有 notes 列，手工 DTO 因此不收这个键。
     */
    static PlanDraftSpec toSpec(PlanUpsertRequest req) {
        List<PlanDraftSpec.DaySpec> days = new ArrayList<>();
        if (req.days() != null) {
            for (PlanUpsertRequest.Day day : req.days()) {
                List<PlanDraftSpec.ItemSpec> items = new ArrayList<>();
                if (day.items() != null) {
                    for (PlanUpsertRequest.Item item : day.items()) {
                        items.add(new PlanDraftSpec.ItemSpec(
                                Ids.canonicalExerciseCode(item.exerciseCode()), item.positionNo(),
                                item.targetSets(), item.repsMin(), item.repsMax(),
                                item.targetExternalLoadKg(), item.targetDurationSeconds(),
                                item.restSeconds(), item.notes()));
                    }
                }
                days.add(new PlanDraftSpec.DaySpec(day.isoWeekday(), day.title(), day.isRestDay(),
                        day.notes(), items));
            }
        }
        return new PlanDraftSpec(req.title(), req.startsOn(), req.endsOn(), null, days);
    }

    /** 规则只有一份：违规统一映射成 VALIDATION_FAILED 的字段错误。 */
    PlanValidationOutcome requireValid(PlanDraftSpec spec) {
        PlanValidationOutcome outcome = validator.validate(spec);
        if (outcome.hasErrors()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "训练计划校验未通过，共 " + outcome.errors().size() + " 处错误",
                    PlanAggregateValidator.toFieldErrors(outcome.errors()));
        }
        return outcome;
    }

    // ==================== 落库 ====================

    private long insertAggregate(long userId, String title, LocalDate startsOn, LocalDate endsOn, String source,
                                 PlanStatus status, String planGroupUuid, int revisionNo, Long basePlanId,
                                 List<DayToInsert> days) {
        TrainingPlanEntity header = new TrainingPlanEntity();
        header.setUserId(userId);
        // planGroupUuid 只来自 Ids.newUuid() 或来源版本，永不被请求值覆盖
        header.setPlanGroupUuid(planGroupUuid);
        header.setRevisionNo(revisionNo);
        header.setBasePlanId(basePlanId);
        header.setTitle(trim(title));
        header.setSource(source);
        header.setStatus(status.code());
        header.setGoalSnapshot(goalSnapshotOf(userId));
        header.setStartsOn(startsOn);
        header.setEndsOn(endsOn);
        header.setConfirmedAt(null);
        header.setLockVersion(0);
        planMapper.insert(header);

        insertDays(userId, header.getId(), days);
        revisionTicker.bump(userId);
        return header.getId();
    }

    /**
     * 子行落库。user_id 在这里统一赋值而不是交给各个构造点：
     * 三张表的 user_id 都是 NOT NULL，而且复合外键 {@code (plan_day_id, user_id)} 要求
     * 子行的 user_id 与父行一致——留空就是数据库层面的当场失败。
     */
    private void insertDays(long userId, long planId, List<DayToInsert> days) {
        for (DayToInsert day : days) {
            day.day().setUserId(userId);
            day.day().setPlanId(planId);
            dayMapper.insert(day.day());
            for (TrainingPlanItemEntity item : day.items()) {
                item.setUserId(userId);
                item.setPlanDayId(day.day().getId());
                itemMapper.insert(item);
            }
        }
    }

    /** 校验产物 → 待插入实体（position_no 已规范化，exerciseId 来自字典）。 */
    private static List<DayToInsert> ofOutcome(PlanValidationOutcome outcome) {
        List<DayToInsert> days = new ArrayList<>(outcome.days().size());
        for (PlanValidationOutcome.ResolvedDay resolved : outcome.days()) {
            TrainingPlanDayEntity day = new TrainingPlanDayEntity();
            day.setIsoWeekday(resolved.isoWeekday());
            day.setTitle(resolved.title());
            day.setIsRestDay(resolved.isRestDay());
            day.setNotes(resolved.notes());

            List<TrainingPlanItemEntity> items = new ArrayList<>(resolved.items().size());
            for (PlanValidationOutcome.ResolvedItem resolvedItem : resolved.items()) {
                TrainingPlanItemEntity item = new TrainingPlanItemEntity();
                item.setExerciseId(resolvedItem.exercise().getId());
                item.setPositionNo(resolvedItem.positionNo());
                item.setTargetSets(resolvedItem.targetSets());
                item.setRepsMin(resolvedItem.repsMin());
                item.setRepsMax(resolvedItem.repsMax());
                item.setTargetExternalLoadKg(resolvedItem.targetExternalLoadKg());
                item.setTargetDurationSeconds(resolvedItem.targetDurationSeconds());
                item.setRestSeconds(resolvedItem.restSeconds());
                item.setNotes(resolvedItem.notes());
                items.add(item);
            }
            days.add(new DayToInsert(day, items));
        }
        return days;
    }

    /**
     * 读出来源版本的子行并脱成"可直接插入"的新实体（不带 id，也不带 planDayId）。
     *
     * <p>必须新建实体而不是把读到的行改一改再插：带着 id 的实体会让 MyBatis-Plus 走更新分支，
     * 于是复制出来的"新版本"直接改写来源版本的子行——正是验收 A11 要防的事。
     */
    private List<DayToInsert> copyOf(long userId, long sourcePlanId) {
        List<TrainingPlanDayEntity> sourceDays = dayMapper.listByPlan(sourcePlanId, userId);
        Map<Long, List<TrainingPlanItemEntity>> byDay =
                groupItems(itemMapper.listByPlan(sourcePlanId, userId));

        List<DayToInsert> copied = new ArrayList<>(sourceDays.size());
        for (TrainingPlanDayEntity source : sourceDays) {
            TrainingPlanDayEntity day = new TrainingPlanDayEntity();
            day.setUserId(userId);
            day.setIsoWeekday(source.getIsoWeekday());
            day.setTitle(source.getTitle());
            day.setIsRestDay(source.getIsRestDay());
            day.setNotes(source.getNotes());

            List<TrainingPlanItemEntity> items = new ArrayList<>();
            for (TrainingPlanItemEntity sourceItem : byDay.getOrDefault(source.getId(), List.of())) {
                TrainingPlanItemEntity item = new TrainingPlanItemEntity();
                item.setUserId(userId);
                item.setExerciseId(sourceItem.getExerciseId());
                item.setPositionNo(sourceItem.getPositionNo());
                item.setTargetSets(sourceItem.getTargetSets());
                item.setRepsMin(sourceItem.getRepsMin());
                item.setRepsMax(sourceItem.getRepsMax());
                item.setTargetExternalLoadKg(sourceItem.getTargetExternalLoadKg());
                item.setTargetDurationSeconds(sourceItem.getTargetDurationSeconds());
                item.setRestSeconds(sourceItem.getRestSeconds());
                item.setNotes(sourceItem.getNotes());
                items.add(item);
            }
            copied.add(new DayToInsert(day, items));
        }
        return copied;
    }

    /** 一个待插入的计划日连同它的条目（id 留空由数据库生成）。 */
    private record DayToInsert(TrainingPlanDayEntity day, List<TrainingPlanItemEntity> items) {
    }

    // ==================== 读取映射 ====================

    private PlanDetailVo loadAggregate(long userId, long planId) {
        TrainingPlanEntity header = requireOwned(userId, planId);
        List<TrainingPlanDayEntity> dayRows = dayMapper.listByPlan(planId, userId);
        List<TrainingPlanItemEntity> itemRows = itemMapper.listByPlan(planId, userId);
        Map<Long, List<TrainingPlanItemEntity>> byDay = groupItems(itemRows);
        Map<Long, ExerciseReferenceEntity> exercises = dictionary.loadByIds(exerciseIds(itemRows));

        List<PlanDayVo> days = new ArrayList<>(dayRows.size());
        for (TrainingPlanDayEntity day : dayRows) {
            days.add(toDayVo(day, byDay.getOrDefault(day.getId(), List.of()), exercises));
        }
        return new PlanDetailVo(
                str(header.getId()),
                str(header.getUserId()),
                header.getPlanGroupUuid(),
                header.getRevisionNo(),
                str(header.getBasePlanId()),
                header.getTitle(),
                header.getSource(),
                header.getStatus(),
                header.getStartsOn(),
                header.getEndsOn(),
                header.getConfirmedAt(),
                header.getLockVersion(),
                header.getCreatedAt(),
                header.getUpdatedAt(),
                toSnapshotVo(header.getGoalSnapshot()),
                days);
    }

    private PlanItemVo toItemVo(TrainingPlanItemEntity row, Map<Long, ExerciseReferenceEntity> exercises) {
        ExerciseReferenceEntity exercise = exercises.get(row.getExerciseId());
        return new PlanItemVo(
                str(row.getId()),
                str(row.getExerciseId()),
                exercise == null ? null : exercise.getExerciseCode(),
                exercise == null ? null : exercise.getName(),
                exercise == null ? null : exercise.getCategory(),
                row.getPositionNo(),
                row.getTargetSets(),
                row.getRepsMin(),
                row.getRepsMax(),
                row.getTargetExternalLoadKg(),
                row.getTargetDurationSeconds(),
                row.getRestSeconds(),
                row.getNotes());
    }

    private PlanDayVo toDayVo(TrainingPlanDayEntity day, List<TrainingPlanItemEntity> items,
                              Map<Long, ExerciseReferenceEntity> exercises) {
        List<PlanItemVo> voItems = new ArrayList<>(items.size());
        for (TrainingPlanItemEntity item : items) {
            voItems.add(toItemVo(item, exercises));
        }
        return new PlanDayVo(str(day.getId()), str(day.getPlanId()), day.getIsoWeekday(), day.getTitle(),
                day.getIsRestDay(), day.getNotes(), voItems);
    }

    private PlanListVo toListVo(TrainingPlanEntity header) {
        return new PlanListVo(
                str(header.getId()),
                str(header.getUserId()),
                header.getPlanGroupUuid(),
                header.getRevisionNo(),
                str(header.getBasePlanId()),
                header.getTitle(),
                header.getSource(),
                header.getStatus(),
                header.getStartsOn(),
                header.getEndsOn(),
                header.getConfirmedAt(),
                header.getLockVersion(),
                header.getCreatedAt(),
                header.getUpdatedAt());
    }

    // ==================== 归属 / 状态 / 参数守卫 ====================

    /** (id, user_id) 复合条件读取：越权与不存在同形，都是 404，避免泄露资源是否存在。 */
    TrainingPlanEntity requireOwned(long userId, long id) {
        TrainingPlanEntity header = planMapper.selectOne(new QueryWrapper<TrainingPlanEntity>()
                .eq("id", id).eq("user_id", userId));
        if (header == null) {
            throw BizException.notFound();
        }
        return header;
    }

    private static void requireStatus(TrainingPlanEntity header, PlanStatus expected, String why) {
        if (!expected.matches(header.getStatus())) {
            throw BizException.invalidTransition(why + "（当前状态：" + header.getStatus() + "）");
        }
    }

    private static String defaultSort(String sort) {
        // §7 声明的默认是 updatedAt,desc，与 PageQuery 未给 sort 时的 DESC 一致
        return Ids.isBlank(sort) ? "updatedAt,desc" : sort;
    }

    private static List<String> parseStatuses(String csv) {
        List<String> statuses = new ArrayList<>();
        if (Ids.isBlank(csv)) {
            return statuses;
        }
        for (String raw : csv.split(",")) {
            String value = raw.trim();
            if (value.isEmpty()) {
                continue;
            }
            PlanStatus status = PlanStatus.parseStrict(value);
            if (status == null) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "status 取值不合法",
                        List.of(FieldError.of("status", PlanImportErrorCode.INVALID_ENUM_VALUE,
                                "status 只支持 DRAFT / ACTIVE / ARCHIVED / DISCARDED（大写，逗号分隔）", raw)));
            }
            if (!statuses.contains(status.code())) {
                statuses.add(status.code());
            }
        }
        return statuses;
    }

    private static String canonicalGroupId(String raw) {
        try {
            return Ids.canonicalUuid(raw);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "groupId 必须是 UUID 文本",
                    List.of(FieldError.of("groupId", "INVALID_UUID_FORMAT", "groupId 必须是 UUID 文本", raw)));
        }
    }

    // ==================== 目标快照 ====================

    /**
     * 现场读取档案与 ACTIVE 目标生成快照。
     *
     * <p>缺失项进 {@code absentFields} 并保持 null——不补造默认身高、不把"没有目标"写成 0 次/周：
     * 未知当 0 会让后续任何换算都得到一个看起来可信的错误结果（docs/p1-database.md §3.9）。
     * 写入前校验 schema_version 与体积（§4.4）。
     */
    private GoalSnapshot goalSnapshotOf(long userId) {
        ProfileContextReader.PlanContext context = profileContext.readForPlanContext(userId);
        if (context == null) {
            context = ProfileContextReader.PlanContext.empty();
        }
        List<String> absent = new ArrayList<>();
        if (context.heightCm() == null) {
            absent.add("heightCm");
        }
        if (context.trainingLevel() == null) {
            absent.add("trainingLevel");
        }
        if (context.equipment() == null || context.equipment().isEmpty()) {
            absent.add("equipment");
        }
        String goalId = null;
        String goalType = null;
        if (context.goalId() == null) {
            absent.addAll(List.of("goalId", "goalType", "targetWeightKg", "weeklySessionsTarget"));
        } else {
            goalId = Long.toString(context.goalId());
            goalType = context.goalType();
            if (goalType == null) {
                absent.add("goalType");
            }
            if (context.targetWeightKg() == null) {
                absent.add("targetWeightKg");
            }
            if (context.weeklySessionsTarget() == null) {
                absent.add("weeklySessionsTarget");
            }
        }
        GoalSnapshot snapshot = new GoalSnapshot(GoalSnapshot.SCHEMA_VERSION, clock.now(), goalId, goalType,
                context.targetWeightKg(), context.weeklySessionsTarget(), context.heightCm(),
                context.trainingLevel(),
                context.equipment() == null ? List.of() : List.copyOf(context.equipment()),
                List.copyOf(absent));
        requireSnapshotSize(snapshot);
        return snapshot;
    }

    private void requireSnapshotSize(GoalSnapshot snapshot) {
        if (!GoalSnapshot.SCHEMA_VERSION.equals(snapshot.schemaVersion())) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "goal_snapshot 的 schema_version 不在白名单内");
        }
        int bytes = objectMapper.writeValueAsString(snapshot).getBytes(StandardCharsets.UTF_8).length;
        if (bytes > GOAL_SNAPSHOT_MAX_BYTES) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "goal_snapshot 超出 " + GOAL_SNAPSHOT_MAX_BYTES + " 字节上限");
        }
    }

    private static GoalSnapshotVo toSnapshotVo(GoalSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return new GoalSnapshotVo(snapshot.schemaVersion(), snapshot.capturedAt(), snapshot.goalId(),
                snapshot.goalType(), snapshot.targetWeightKg(), snapshot.weeklySessionsTarget(),
                snapshot.heightCm(), snapshot.trainingLevel(), snapshot.equipment(), snapshot.absentFields());
    }

    // ==================== 小工具 ====================

    static Map<Long, List<TrainingPlanItemEntity>> groupItems(List<TrainingPlanItemEntity> items) {
        Map<Long, List<TrainingPlanItemEntity>> byDay = new LinkedHashMap<>();
        for (TrainingPlanItemEntity item : items) {
            byDay.computeIfAbsent(item.getPlanDayId(), key -> new ArrayList<>()).add(item);
        }
        return byDay;
    }

    static List<Long> exerciseIds(List<TrainingPlanItemEntity> items) {
        List<Long> ids = new ArrayList<>();
        for (TrainingPlanItemEntity item : items) {
            if (item.getExerciseId() != null && !ids.contains(item.getExerciseId())) {
                ids.add(item.getExerciseId());
            }
        }
        return ids;
    }

    private static String copyTitle(String sourceTitle) {
        String base = sourceTitle == null ? "" : sourceTitle;
        if (base.length() + COPY_TITLE_SUFFIX.length() > PLAN_TITLE_MAX) {
            base = base.substring(0, PLAN_TITLE_MAX - COPY_TITLE_SUFFIX.length());
        }
        return base + COPY_TITLE_SUFFIX;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String str(Long value) {
        return value == null ? null : Long.toString(value);
    }
}
