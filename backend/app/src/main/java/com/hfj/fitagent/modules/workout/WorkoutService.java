package com.hfj.fitagent.modules.workout;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hfj.fitagent.common.AppClock;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.util.Ids;
import com.hfj.fitagent.common.web.FieldError;
import com.hfj.fitagent.common.web.PageQuery;
import com.hfj.fitagent.common.web.PageResult;
import com.hfj.fitagent.modules.training.ExerciseService;
import com.hfj.fitagent.modules.training.TrainingPlanService;
import com.hfj.fitagent.modules.training.vo.ExerciseVo;
import com.hfj.fitagent.modules.training.vo.PlanDayVo;
import com.hfj.fitagent.modules.training.vo.PlanDetailVo;
import com.hfj.fitagent.modules.training.vo.PlanItemVo;
import com.hfj.fitagent.modules.workout.dto.WorkoutRequests;
import com.hfj.fitagent.modules.workout.entity.WorkoutSessionEntity;
import com.hfj.fitagent.modules.workout.entity.WorkoutSessionEntryEntity;
import com.hfj.fitagent.modules.workout.entity.WorkoutSessionSetEntity;
import com.hfj.fitagent.modules.workout.mapper.WorkoutSessionEntryMapper;
import com.hfj.fitagent.modules.workout.mapper.WorkoutSessionMapper;
import com.hfj.fitagent.modules.workout.mapper.WorkoutSessionSetMapper;
import com.hfj.fitagent.modules.workout.vo.WorkoutVos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 训练执行记录（阶段 2）。三张表一个聚合：session → entry → set。
 *
 * <p>四条不能让步的规则：
 * <ol>
 *   <li><b>幂等</b>：{@code (user_id, client_record_uuid)} 唯一。并发两次「开始」时第二条必然撞键，
 *       撞键不是错误——回读已有草稿返回，所以"重复开始"永远得到同一场次，也不会留下第二条草稿。</li>
 *   <li><b>单草稿闸门</b>：{@code UNIQUE(active_user_id)}（数据库派生列）。撞它同样按"恢复已有草稿"处理。</li>
 *   <li><b>参考版本不能是草稿</b>：计划子项只有在 DRAFT 态才会被 {@code TrainingPlanService.update()} 删掉重建，
 *       禁掉 DRAFT 引用之后，被引用的计划日/条目在数据库层面永远不会消失，历史执行因此永远能回查原版本。</li>
 *   <li><b>不替用户确认</b>：{@code is_completed} 只来自入参；计划值只作为参考回显，绝不补成实际值。</li>
 * </ol>
 */
@Service
public class WorkoutService {

    private static final Logger log = LoggerFactory.getLogger(WorkoutService.class);
    private static final int MAX_ENTRIES = 40;
    private static final int MAX_SETS_PER_ENTRY = 60;

    private final WorkoutSessionMapper sessionMapper;
    private final WorkoutSessionEntryMapper entryMapper;
    private final WorkoutSessionSetMapper setMapper;
    private final TrainingPlanService planService;
    private final ExerciseService exerciseService;
    private final WorkoutRevisionTicker revisionTicker;
    private final AppClock clock;

    public WorkoutService(WorkoutSessionMapper sessionMapper,
                          WorkoutSessionEntryMapper entryMapper,
                          WorkoutSessionSetMapper setMapper,
                          TrainingPlanService planService,
                          ExerciseService exerciseService,
                          WorkoutRevisionTicker revisionTicker,
                          AppClock clock) {
        this.sessionMapper = sessionMapper;
        this.entryMapper = entryMapper;
        this.setMapper = setMapper;
        this.planService = planService;
        this.exerciseService = exerciseService;
        this.revisionTicker = revisionTicker;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 开始 / 恢复

    /**
     * 开始一场训练，或恢复同一幂等键/同一进行中的草稿。
     *
     * <p>返回的 {@code resumedExisting} 让前端能如实说"继续上次未完成的记录"，
     * 而不是每次都提示"已开始新训练"——后者在重复点击时是假话。
     */
    @Transactional
    public WorkoutVos.StartResultVo start(long userId, ZoneId zone, WorkoutRequests.Start req) {
        String uuid = requireUuid(req.clientRecordUuid());
        Reference reference = resolveReference(userId, req.planId(), req.planDayId(),
                req.businessDate() == null ? clock.businessDate(zone) : req.businessDate());
        LocalDate businessDate = reference.businessDate();

        WorkoutSessionEntity existing = findByUuid(userId, uuid);
        if (existing != null) {
            requireSameIntent(existing, reference, businessDate);
            return new WorkoutVos.StartResultVo(detail(userId, existing.getId()), true);
        }

        Long activeDraft = sessionMapper.findDraftId(userId);
        if (activeDraft != null) {
            // 已有草稿就不新建：闸门在数据库里，这里只是把"恢复"说清楚，不承担正确性
            return new WorkoutVos.StartResultVo(detail(userId, activeDraft), true);
        }

        WorkoutSessionEntity row = new WorkoutSessionEntity();
        row.setUserId(userId);
        row.setClientRecordUuid(uuid);
        row.setBusinessDate(businessDate);
        row.setTimezone(zone.getId());
        row.setPlanId(reference.planId());
        row.setPlanDayId(reference.planDayId());
        row.setPlanRevisionNo(reference.planRevisionNo());
        row.setStartedAt(clock.now());
        row.setStatus(WorkoutSessionStatus.DRAFT);
        row.setLockVersion(0);
        try {
            sessionMapper.insert(row);
        } catch (DuplicateKeyException race) {
            // 并发双开始：输家撞 (user_id,client_record_uuid) 或 UNIQUE(active_user_id)，
            // 回读赢家那一条返回——绝不能把重复点击变成两条草稿或一个 500
            Long winner = firstNonNull(findByUuidId(userId, uuid), sessionMapper.findDraftId(userId));
            if (winner == null) {
                throw race;
            }
            log.info("并发开始命中唯一闸门，返回既有草稿 userId={} sessionId={}", userId, winner);
            return new WorkoutVos.StartResultVo(detail(userId, winner), true);
        }
        revisionTicker.bump(userId);
        return new WorkoutVos.StartResultVo(detail(userId, row.getId()), false);
    }

    // ------------------------------------------------------------------ 查询

    @Transactional(readOnly = true)
    public PageResult<WorkoutVos.SessionSummaryVo> list(long userId, String status, LocalDate from, LocalDate to,
                                                        Integer page, Integer size) {
        PageQuery query = PageQuery.of(page, size, null, List.of("businessDate", "startedAt", "createdAt"), "businessDate");
        LambdaQueryWrapper<WorkoutSessionEntity> wrapper = new LambdaQueryWrapper<WorkoutSessionEntity>()
                .eq(WorkoutSessionEntity::getUserId, userId);
        if (!Ids.isBlank(status)) {
            wrapper.eq(WorkoutSessionEntity::getStatus, parseStatus(status));
        }
        if (from != null) {
            wrapper.ge(WorkoutSessionEntity::getBusinessDate, from);
        }
        if (to != null) {
            wrapper.le(WorkoutSessionEntity::getBusinessDate, to);
        }
        wrapper.orderByDesc(WorkoutSessionEntity::getBusinessDate).orderByDesc(WorkoutSessionEntity::getStartedAt);

        Page<WorkoutSessionEntity> result = sessionMapper.selectPage(new Page<>(query.page(), query.size(), true), wrapper);
        List<Long> ids = result.getRecords().stream().map(WorkoutSessionEntity::getId).toList();
        Map<Long, int[]> counts = entryAndSetCounts(userId, ids);
        List<WorkoutVos.SessionSummaryVo> items = new ArrayList<>(result.getRecords().size());
        for (WorkoutSessionEntity row : result.getRecords()) {
            int[] c = counts.getOrDefault(row.getId(), new int[]{0, 0});
            items.add(new WorkoutVos.SessionSummaryVo(idText(row.getId()), row.getBusinessDate(),
                    row.getStatus().name(), idText(row.getPlanId()), idText(row.getPlanDayId()),
                    row.getPlanRevisionNo(), row.getStartedAt(), row.getEndedAt(),
                    durationOf(row.getStartedAt(), row.getEndedAt()), c[0], c[1], row.getNotes(),
                    row.getLockVersion()));
        }
        return PageResult.from(result, items);
    }

    @Transactional(readOnly = true)
    public WorkoutVos.SessionDetailVo detail(long userId, long sessionId) {
        WorkoutSessionEntity row = requireSession(userId, sessionId);
        List<WorkoutSessionEntryEntity> entries = entryMapper.listBySession(sessionId, userId);
        List<Long> entryIds = entries.stream().map(WorkoutSessionEntryEntity::getId).toList();
        Map<Long, List<WorkoutSessionSetEntity>> setsByEntry = new HashMap<>();
        if (!entryIds.isEmpty()) {
            for (WorkoutSessionSetEntity set : setMapper.listBySessions(List.of(sessionId), userId)) {
                setsByEntry.computeIfAbsent(set.getEntryId(), k -> new ArrayList<>()).add(set);
            }
        }
        Map<Long, ExerciseVo> exercises = exerciseService.mapByIds(
                entries.stream().map(WorkoutSessionEntryEntity::getExerciseId).toList());
        Map<Long, PlanItemVo> planItems = planItemsOf(userId, row.getPlanId());
        String planTitle = null;
        Integer planWeekday = null;
        if (row.getPlanId() != null) {
            PlanDetailVo plan = planService.findById(userId, row.getPlanId());
            planTitle = plan == null ? null : plan.title();
            if (plan != null && row.getPlanDayId() != null) {
                planWeekday = plan.days().stream()
                        .filter(d -> d.id().equals(idText(row.getPlanDayId())))
                        .map(PlanDayVo::isoWeekday).findFirst().orElse(null);
            }
        }

        List<WorkoutVos.EntryVo> entryVos = new ArrayList<>(entries.size());
        for (WorkoutSessionEntryEntity entry : entries) {
            List<WorkoutSessionSetEntity> sets = setsByEntry.getOrDefault(entry.getId(), List.of());
            ExerciseVo exercise = exercises.get(entry.getExerciseId());
            PlanItemVo reference = entry.getPlanItemId() == null ? null : planItems.get(entry.getPlanItemId());
            int completed = 0;
            for (WorkoutSessionSetEntity set : sets) {
                if (Boolean.TRUE.equals(set.getIsCompleted())) {
                    completed++;
                }
            }
            entryVos.add(new WorkoutVos.EntryVo(idText(entry.getId()), entry.getPositionNo(),
                    idText(entry.getExerciseId()),
                    exercise == null ? null : exercise.exerciseCode(),
                    exercise == null ? null : exercise.name(),
                    exercise == null ? null : exercise.category(),
                    idText(entry.getPlanItemId()),
                    reference == null ? null : reference.targetSets(),
                    reference == null ? null : reference.repsMin(),
                    reference == null ? null : reference.repsMax(),
                    reference == null ? null : reference.targetExternalLoadKg(),
                    reference == null ? null : reference.targetDurationSeconds(),
                    entry.getTargetDurationSeconds(), entry.getDistanceMeters(), completed, sets.size(),
                    entry.getNotes(),
                    sets.stream().map(s -> new WorkoutVos.SetVo(idText(s.getId()), s.getSetNo(), s.getReps(),
                            s.getExternalLoadKg(), s.getDurationSeconds(),
                            Boolean.TRUE.equals(s.getIsCompleted()), s.getNotes())).toList()));
        }
        return new WorkoutVos.SessionDetailVo(idText(row.getId()), row.getClientRecordUuid(),
                row.getBusinessDate(), row.getTimezone(), row.getStatus().name(),
                idText(row.getPlanId()), planTitle, row.getPlanRevisionNo(),
                idText(row.getPlanDayId()), planWeekday, row.getStartedAt(), row.getEndedAt(),
                durationOf(row.getStartedAt(), row.getEndedAt()), row.getNotes(), row.getLockVersion(), entryVos);
    }

    /** 本人某业务日期的全部已确认场次（metrics 与"当天对比"的唯一入口）。 */
    @Transactional(readOnly = true)
    public List<WorkoutSessionEntity> completedOn(long userId, LocalDate businessDate) {
        return sessionMapper.listCompletedOn(userId, businessDate);
    }

    @Transactional(readOnly = true)
    public List<WorkoutSessionEntity> completedBetween(long userId, LocalDate from, LocalDate to) {
        return sessionMapper.listCompletedBetween(userId, from, to);
    }

    // ------------------------------------------------------------------ 保存 / 结束 / 丢弃 / 删除

    /**
     * 聚合保存：整棵子树按入参重建。
     *
     * <p>已确认结束的场次也允许编辑（用户会发现记错了组数），但状态与 ended_at 不动；
     * 版本不匹配一律 409，不做"读最新再覆盖"的自动重试，否则会静默吞掉另一个会话的写入。
     */
    @Transactional
    public WorkoutVos.SessionDetailVo save(long userId, long sessionId, WorkoutRequests.Save req) {
        WorkoutSessionEntity row = requireSession(userId, sessionId);
        if (req.lockVersion() == null || !req.lockVersion().equals(row.getLockVersion())) {
            throw BizException.versionConflict();
        }
        List<WorkoutRequests.EntryInput> entries = req.entries() == null ? List.of() : req.entries();
        validateEntries(userId, row, entries);

        setMapper.deleteBySession(sessionId, userId);
        entryMapper.deleteBySession(sessionId, userId);
        insertEntries(userId, sessionId, entries);

        if (row.getStatus() == WorkoutSessionStatus.COMPLETED) {
            if (sessionMapper.updateCompletedNotes(sessionId, userId, row.getLockVersion(), trim(req.notes())) == 0) {
                throw BizException.versionConflict();
            }
        } else {
            row.setNotes(trim(req.notes()));
            if (sessionMapper.updateById(row) == 0) {
                throw BizException.versionConflict();
            }
        }
        revisionTicker.bump(userId);
        return detail(userId, sessionId);
    }

    /**
     * 确认结束。只从 DRAFT 迁移；对已 COMPLETED 的场次重复调用是幂等成功（回读当前状态），
     * 不重复写 ended_at、不重复递增修订号——否则"多点一次结束"会改变统计口径。
     */
    @Transactional
    public WorkoutVos.SessionDetailVo complete(long userId, long sessionId, WorkoutRequests.Complete req) {
        WorkoutSessionEntity row = requireSession(userId, sessionId);
        if (row.getStatus() == WorkoutSessionStatus.COMPLETED) {
            return detail(userId, sessionId);
        }
        if (row.getStatus() == WorkoutSessionStatus.DISCARDED) {
            throw BizException.invalidTransition("已丢弃的训练记录不能再确认结束");
        }
        Instant endedAt = req.endedAt() == null ? clock.now() : req.endedAt();
        if (endedAt.isBefore(row.getStartedAt())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "结束时间不能早于开始时间",
                    List.of(FieldError.of("endedAt", "ENDED_BEFORE_STARTED", "结束时间早于 started_at")));
        }
        if (sessionMapper.completeDraft(sessionId, userId, req.lockVersion(), endedAt) == 0) {
            throw BizException.versionConflict();
        }
        revisionTicker.bump(userId);
        return detail(userId, sessionId);
    }

    @Transactional
    public WorkoutVos.SessionDetailVo discard(long userId, long sessionId, WorkoutRequests.VersionOnly req) {
        WorkoutSessionEntity row = requireSession(userId, sessionId);
        if (row.getStatus() == WorkoutSessionStatus.DISCARDED) {
            return detail(userId, sessionId);
        }
        if (row.getStatus() == WorkoutSessionStatus.COMPLETED) {
            throw BizException.invalidTransition("已确认结束的记录不能丢弃，请改用删除");
        }
        if (sessionMapper.discardDraft(sessionId, userId, req.lockVersion()) == 0) {
            throw BizException.versionConflict();
        }
        revisionTicker.bump(userId);
        return detail(userId, sessionId);
    }

    /** 删除本人记录：整棵子树一起删，先 set 后 entry 再 session（外键 RESTRICT 的顺序要求）。 */
    @Transactional
    public void delete(long userId, long sessionId) {
        WorkoutSessionEntity row = requireSession(userId, sessionId);
        setMapper.deleteBySession(sessionId, userId);
        entryMapper.deleteBySession(sessionId, userId);
        if (sessionMapper.deleteById(row.getId()) == 0) {
            throw BizException.notFound();
        }
        revisionTicker.bump(userId);
    }

    // ------------------------------------------------------------------ 校验与内部件

    private void validateEntries(long userId, WorkoutSessionEntity session, List<WorkoutRequests.EntryInput> entries) {
        if (entries.size() > MAX_ENTRIES) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "一次训练最多记录 " + MAX_ENTRIES + " 个动作",
                    List.of(FieldError.of("entries", "TOO_MANY_ENTRIES", "超出上限")));
        }
        Set<Integer> positions = new HashSet<>();
        Set<Long> planItemIds = new HashSet<>();
        Set<Long> dayItemIds = itemIdsOfPlanDay(userId, session.getPlanId(), session.getPlanDayId());
        for (int i = 0; i < entries.size(); i++) {
            WorkoutRequests.EntryInput entry = entries.get(i);
            String where = "entries[" + i + "]";
            if (entry.positionNo() == null || entry.positionNo() < 1 || !positions.add(entry.positionNo())) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "动作顺序号必须是 ≥1 且不重复",
                        List.of(FieldError.of(where + ".positionNo", "INVALID_POSITION", "顺序号非法或重复")));
            }
            Long exerciseId = requireId(entry.exerciseId(), where + ".exerciseId");
            if (exerciseService.mapByIds(List.of(exerciseId)).isEmpty()) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "动作不在字典里",
                        List.of(FieldError.of(where + ".exerciseId", "EXERCISE_NOT_FOUND", "字典里没有这个动作")));
            }
            if (entry.sets() != null && entry.sets().size() > MAX_SETS_PER_ENTRY) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "单个动作最多 " + MAX_SETS_PER_ENTRY + " 组",
                        List.of(FieldError.of(where + ".sets", "TOO_MANY_SETS", "超出上限")));
            }
            Set<Integer> setNos = new HashSet<>();
            if (entry.sets() != null) {
                for (int s = 0; s < entry.sets().size(); s++) {
                    WorkoutRequests.SetInput set = entry.sets().get(s);
                    String setWhere = where + ".sets[" + s + "]";
                    if (set.setNo() == null || set.setNo() < 1 || !setNos.add(set.setNo())) {
                        throw new BizException(ErrorCode.VALIDATION_FAILED, "组序号必须是 ≥1 且不重复",
                                List.of(FieldError.of(setWhere + ".setNo", "INVALID_SET_NO", "组序号非法或重复")));
                    }
                    if (set.isCompleted() == null) {
                        throw new BizException(ErrorCode.VALIDATION_FAILED, "是否完成必须由用户明确给出",
                                List.of(FieldError.of(setWhere + ".isCompleted", "REQUIRED", "服务端不替用户确认")));
                    }
                    if (set.reps() != null && set.reps() < 0) {
                        throw negative(setWhere + ".reps");
                    }
                    if (set.externalLoadKg() != null && set.externalLoadKg().signum() < 0) {
                        throw negative(setWhere + ".externalLoadKg");
                    }
                }
            }
            if (entry.planItemId() != null && !entry.planItemId().isBlank()) {
                Long planItemId = requireId(entry.planItemId(), where + ".planItemId");
                if (!planItemIds.add(planItemId)) {
                    throw new BizException(ErrorCode.VALIDATION_FAILED, "同一个计划条目不能被拆成两条实际记录",
                            List.of(FieldError.of(where + ".planItemId", "DUPLICATE_PLAN_ITEM", "重复引用")));
                }
                if (!dayItemIds.contains(planItemId)) {
                    throw new BizException(ErrorCode.VALIDATION_FAILED, "参考条目不属于本场次引用的那个计划日",
                            List.of(FieldError.of(where + ".planItemId", "PLAN_ITEM_MISMATCH", "归属不符")));
                }
            }
        }
    }

    private static BizException negative(String field) {
        return new BizException(ErrorCode.VALIDATION_FAILED, "数值不能为负",
                List.of(FieldError.of(field, "NEGATIVE_VALUE", "该列只接受 ≥0 的值")));
    }

    private void insertEntries(long userId, long sessionId, List<WorkoutRequests.EntryInput> entries) {
        for (WorkoutRequests.EntryInput entry : entries) {
            WorkoutSessionEntryEntity row = new WorkoutSessionEntryEntity();
            row.setUserId(userId);
            row.setSessionId(sessionId);
            row.setExerciseId(requireId(entry.exerciseId(), "exerciseId"));
            row.setPlanItemId(entry.planItemId() == null || entry.planItemId().isBlank()
                    ? null : requireId(entry.planItemId(), "planItemId"));
            row.setPositionNo(entry.positionNo());
            row.setTargetDurationSeconds(entry.durationSeconds());
            row.setDistanceMeters(entry.distanceMeters());
            row.setNotes(trim(entry.notes()));
            entryMapper.insert(row);
            if (entry.sets() == null) {
                continue;
            }
            for (WorkoutRequests.SetInput set : entry.sets()) {
                WorkoutSessionSetEntity setRow = new WorkoutSessionSetEntity();
                setRow.setUserId(userId);
                setRow.setEntryId(row.getId());
                setRow.setSetNo(set.setNo());
                setRow.setReps(set.reps());
                setRow.setExternalLoadKg(set.externalLoadKg());
                setRow.setDurationSeconds(set.durationSeconds());
                setRow.setIsCompleted(Boolean.TRUE.equals(set.isCompleted()));
                setRow.setNotes(trim(set.notes()));
                setMapper.insert(setRow);
            }
        }
    }

    /**
     * 解析参考计划：三件事一次做全——版本不是草稿、计划日属于该计划、业务日期落在计划区间且星期对得上。
     * 第二条正是"计划子项永不被删"这条不变式的前提。
     */
    private Reference resolveReference(long userId, String planIdText, String planDayIdText, LocalDate businessDate) {
        boolean none = Ids.isBlank(planIdText) && Ids.isBlank(planDayIdText);
        if (none) {
            return new Reference(null, null, null, businessDate);
        }
        if (Ids.isBlank(planIdText) || Ids.isBlank(planDayIdText)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "planId 与 planDayId 必须成对给出",
                    List.of(FieldError.of("planDayId", "REQUIRED", "缺少其中之一")));
        }
        long planId = requireId(planIdText, "planId");
        long planDayId = requireId(planDayIdText, "planDayId");
        PlanDetailVo plan = planService.findById(userId, planId);
        if (plan == null) {
            throw BizException.notFound();
        }
        if ("DRAFT".equals(plan.status())) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION,
                    "训练记录只能参考已确认过的计划版本；草稿会被就地改写，不能作为历史依据",
                    List.of(FieldError.of("planId", "PLAN_NOT_CONFIRMED", "草稿计划不能作为参考")));
        }
        if (plan.startsOn() != null && businessDate.isBefore(plan.startsOn())) {
            throw outOfRange(businessDate);
        }
        if (plan.endsOn() != null && businessDate.isAfter(plan.endsOn())) {
            throw outOfRange(businessDate);
        }
        PlanDayVo day = plan.days().stream()
                .filter(d -> d.id().equals(idText(planDayId)))
                .findFirst().orElseThrow(() -> new BizException(ErrorCode.VALIDATION_FAILED,
                        "计划日不属于该计划版本", List.of(FieldError.of("planDayId", "PLAN_DAY_MISMATCH", "归属不符"))));
        if (day.isoWeekday() != businessDate.getDayOfWeek().getValue()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "业务日期的星期与所参考计划日不一致",
                    List.of(FieldError.of("businessDate", "PLAN_DAY_DATE_MISMATCH", "星期不匹配")));
        }
        return new Reference(planId, planDayId, plan.revisionNo(), businessDate);
    }

    private static BizException outOfRange(LocalDate businessDate) {
        return new BizException(ErrorCode.VALIDATION_FAILED,
                "该业务日期不在所参考计划的起止区间内",
                List.of(FieldError.of("businessDate", "OUT_OF_PLAN_RANGE", "超出计划区间: " + businessDate)));
    }

    private Map<Long, PlanItemVo> planItemsOf(long userId, Long planId) {
        if (planId == null) {
            return Map.of();
        }
        PlanDetailVo plan = planService.findById(userId, planId);
        if (plan == null) {
            return Map.of();
        }
        Map<Long, PlanItemVo> out = new HashMap<>();
        for (PlanDayVo day : plan.days()) {
            for (PlanItemVo item : day.items()) {
                out.put(Long.valueOf(item.id()), item);
            }
        }
        return out;
    }

    /**
     * 本场次参考的那个计划日下的条目 id 集合。
     *
     * <p>没有参考计划时返回空集——此时任何 {@code planItemId} 都是无依据的引用，必须拒绝，
     * 否则"自主记录"能被伪造成有计划的执行，统计分母就会被凭空捏造。
     */
    private Set<Long> itemIdsOfPlanDay(long userId, Long planId, Long planDayId) {
        if (planId == null || planDayId == null) {
            return Set.of();
        }
        PlanDetailVo plan = planService.findById(userId, planId);
        if (plan == null) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (PlanDayVo day : plan.days()) {
            if (day.id().equals(String.valueOf(planDayId))) {
                for (PlanItemVo item : day.items()) {
                    ids.add(Long.valueOf(item.id()));
                }
            }
        }
        return ids;
    }

    private void requireSameIntent(WorkoutSessionEntity existing, Reference reference, LocalDate businessDate) {
        boolean same = java.util.Objects.equals(existing.getPlanDayId(), reference.planDayId())
                && businessDate.equals(existing.getBusinessDate());
        if (!same) {
            throw new BizException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    "同一个幂等键已经用于另一次训练开始，不能改参考与日期",
                    List.of(FieldError.of("clientRecordUuid", "IDEMPOTENCY_KEY_CONFLICT", "内容不一致")));
        }
    }

    private Map<Long, int[]> entryAndSetCounts(long userId, List<Long> sessionIds) {
        Map<Long, int[]> out = new HashMap<>();
        if (sessionIds.isEmpty()) {
            return out;
        }
        // set 表没有 session_id，靠 entry 反查一次父子关系，避免逐条查（N+1）
        Map<Long, Long> sessionOfEntry = new HashMap<>();
        for (WorkoutSessionEntryEntity entry : entryMapper.listBySessions(sessionIds, userId)) {
            sessionOfEntry.put(entry.getId(), entry.getSessionId());
            out.computeIfAbsent(entry.getSessionId(), k -> new int[]{0, 0})[0]++;
        }
        for (WorkoutSessionSetEntity set : setMapper.listBySessions(sessionIds, userId)) {
            if (!Boolean.TRUE.equals(set.getIsCompleted())) {
                continue;
            }
            Long sessionId = sessionOfEntry.get(set.getEntryId());
            if (sessionId != null) {
                out.computeIfAbsent(sessionId, k -> new int[]{0, 0})[1]++;
            }
        }
        return out;
    }

    private WorkoutSessionEntity requireSession(long userId, long sessionId) {
        WorkoutSessionEntity row = sessionMapper.selectOne(new LambdaQueryWrapper<WorkoutSessionEntity>()
                .eq(WorkoutSessionEntity::getId, sessionId)
                .eq(WorkoutSessionEntity::getUserId, userId));
        if (row == null) {
            throw BizException.notFound();
        }
        return row;
    }

    private WorkoutSessionEntity findByUuid(long userId, String uuid) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<WorkoutSessionEntity>()
                .eq(WorkoutSessionEntity::getUserId, userId)
                .eq(WorkoutSessionEntity::getClientRecordUuid, uuid));
    }

    private Long findByUuidId(long userId, String uuid) {
        WorkoutSessionEntity row = findByUuid(userId, uuid);
        return row == null ? null : row.getId();
    }

    private static Long firstNonNull(Long a, Long b) {
        return a != null ? a : b;
    }

    private static String requireUuid(String raw) {
        String canonical = Ids.canonicalUuid(raw);
        if (canonical == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "clientRecordUuid 必须是合法 UUID",
                    List.of(FieldError.of("clientRecordUuid", "INVALID_UUID", "格式不符")));
        }
        return canonical;
    }

    private static Long requireId(String raw, String field) {
        if (Ids.isBlank(raw)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, field + " 必填",
                    List.of(FieldError.of(field, "REQUIRED", "缺少该字段")));
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, field + " 必须是数字字符串",
                    List.of(FieldError.of(field, "INVALID_ID", raw)));
        }
    }

    private static WorkoutSessionStatus parseStatus(String status) {
        try {
            return WorkoutSessionStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "status 只接受 DRAFT/COMPLETED/DISCARDED",
                    List.of(FieldError.of("status", "INVALID_ENUM_VALUE", status)));
        }
    }

    private static Integer durationOf(Instant startedAt, Instant endedAt) {
        if (startedAt == null || endedAt == null) {
            return null;
        }
        long seconds = Duration.between(startedAt, endedAt).getSeconds();
        return (int) Math.max(0, seconds);
    }

    /** BIGINT 主键一律以字符串出参（契约 §1）；null 保持 null，不能变成 "null"。 */
    private static String idText(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 解析后的参考：三个字段一起成立或一起为空，不允许半吊子状态进数据库。 */
    private record Reference(Long planId, Long planDayId, Integer planRevisionNo, LocalDate businessDate) {
    }
}
