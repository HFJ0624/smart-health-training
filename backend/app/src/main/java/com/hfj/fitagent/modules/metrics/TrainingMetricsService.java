package com.hfj.fitagent.modules.metrics;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.web.FieldError;
import com.hfj.fitagent.modules.metrics.support.MetricsDataRevisionReader;
import com.hfj.fitagent.modules.metrics.vo.MetricsVos;
import com.hfj.fitagent.modules.training.TrainingPlanService;
import com.hfj.fitagent.modules.training.vo.PlanDayVo;
import com.hfj.fitagent.modules.training.vo.PlanDetailVo;
import com.hfj.fitagent.modules.training.vo.PlanItemVo;
import com.hfj.fitagent.modules.workout.WorkoutSessionStatus;
import com.hfj.fitagent.modules.workout.WorkoutService;
import com.hfj.fitagent.modules.workout.entity.WorkoutSessionEntity;
import com.hfj.fitagent.modules.workout.vo.WorkoutVos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 训练统计：只读、确定性计算，不建统计表、不调模型（docs/stage2-design.md §2 与 §6）。
 *
 * <p>算法上不能让步的五件事：
 * <ol>
 *   <li><b>只认已确认记录</b>：数据只来自 {@code WorkoutService.completedOn/completedBetween}
 *       （SQL 里已过滤 {@code status='COMPLETED'}），这里再按返回 VO 的状态复核一次。
 *       草稿与已丢弃永不参与，否则"计划了"会被统计成"练过了"。</li>
 *   <li><b>以计划条目为单位、先跨场次汇总再封顶</b>：某条目计划 3 组，两场各确认 2 组时按
 *       {@code min(4,3)=3} 计入而不是 4——先封顶会凭空造出一个永远掉不下来的 100%，
 *       超出部分另记为 {@code extraSets}，不抵消别的条目的缺口。</li>
 *   <li><b>分母按 (planId, planDayId) 分组</b>：training_plan 的一行就是一个不可变版本，
 *       一天里既引用 v1 又引用 v2 时两组分母各算各的，合并等于用 v2 的目标去评价 v1 的执行。</li>
 *   <li><b>计划与实际是两组独立字段</b>（{@link MetricsVos.PlannedSide} / {@link MetricsVos.ActualSide}），
 *       实际值缺失时永不拿计划值回填。</li>
 *   <li><b>未知不折算成 0 或 100%</b>：算不出来的指标 {@code value=null} 且必须带原因码；
 *       0 只允许出现在"直接计数"上——那天确实一场没练，是事实而不是缺失。</li>
 * </ol>
 *
 * <p>依赖方向：只调 {@code WorkoutService} 与 {@code TrainingPlanService} 的公开方法，不碰它们的 Mapper；
 * {@code user_account.data_revision} 的读收敛在 {@link MetricsDataRevisionReader} 一个文件里。
 */
@Service
public class TrainingMetricsService {

    private final WorkoutService workoutService;
    private final TrainingPlanService planService;
    private final MetricsDataRevisionReader revisionReader;

    public TrainingMetricsService(WorkoutService workoutService,
                                  TrainingPlanService planService,
                                  MetricsDataRevisionReader revisionReader) {
        this.workoutService = workoutService;
        this.planService = planService;
        this.revisionReader = revisionReader;
    }

    // ================================================================== 当天对比

    /**
     * 当天"计划安排 vs 已确认执行" + 当天记录摘要。
     *
     * <p>顺序是刻意的：先按生效计划把分母立起来（一条记录都没有也要有安排），
     * 再按场次当时参考的版本补组，最后才灌入已确认的实际值。反过来做，
     * "今天安排了但没记录"会退化成"什么都没有"，页面就分不清"没练"与"没记录"。
     */
    @Transactional(readOnly = true)
    public MetricsVos.DayComparison day(long userId, LocalDate businessDate) {
        String dataRevision = revisionReader.currentRevisionText(userId);
        MetricsVos.Context base = MetricsVos.Context.of(dataRevision);
        int isoWeekday = businessDate.getDayOfWeek().getValue();

        List<WorkoutVos.SessionDetailVo> sessions =
                completedDetails(userId, workoutService.completedOn(userId, businessDate));

        List<String> dayMissing = new ArrayList<>();
        Map<String, PlanDetailVo> planCache = new LinkedHashMap<>();
        Map<String, GroupAcc> groups = new LinkedHashMap<>();

        PlanDetailVo active = findActiveQuietly(userId);
        if (active == null) {
            dayMissing.add(MetricsRules.NO_PLAN_SCHEDULED);
        } else if (!coversDate(active, businessDate)) {
            dayMissing.add(mark(MetricsRules.NO_PLAN_SCHEDULED,
                    "生效计划 " + active.id() + " 的起止区间不含 " + businessDate));
        } else {
            PlanDayVo scheduledDay = dayByWeekday(active, isoWeekday);
            if (scheduledDay == null) {
                dayMissing.add(mark(MetricsRules.NO_PLAN_SCHEDULED,
                        "生效计划里没有星期 " + isoWeekday + " 的安排（休息日或漏排）"));
            } else {
                openGroup(groups, userId, active.id(), scheduledDay.id(), null, true, planCache, dayMissing);
            }
        }

        // 场次可能参考的是已归档版本：单独成组，不与生效版本共用分母
        for (WorkoutVos.SessionDetailVo session : sessions) {
            if (session.planId() != null && session.planDayId() != null) {
                GroupAcc group = openGroup(groups, userId, session.planId(), session.planDayId(),
                        session.planRevisionNo(), false, planCache, dayMissing);
                group.attachSession(session.id());
            }
        }

        SelfInitiatedAcc selfInitiated = new SelfInitiatedAcc();
        DaySummaryAcc summary = new DaySummaryAcc();
        for (WorkoutVos.SessionDetailVo session : sessions) {
            summary.addSession(session);
            GroupAcc group = session.planId() == null || session.planDayId() == null
                    ? null : groups.get(groupKey(session.planId(), session.planDayId()));
            for (WorkoutVos.EntryVo entry : safeList(session.entries())) {
                summary.addEntry(session.id(), entry);
                if (entry.planItemId() == null || entry.planItemId().isBlank()) {
                    selfInitiated.add(session.id(), entry);
                    continue;
                }
                if (group == null) {
                    dayMissing.add(mark(MetricsRules.PLAN_ITEM_WITHOUT_PLAN_REFERENCE,
                            "session=" + session.id() + ",entry=" + entry.id()));
                    selfInitiated.add(session.id(), entry);
                    continue;
                }
                ItemAcc item = group.items.get(entry.planItemId());
                if (item == null) {
                    item = ItemAcc.fromEntry(entry);
                    group.items.put(item.planItemId, item);
                    group.missing.add(mark(MetricsRules.PLAN_ITEM_NOT_IN_REFERENCED_DAY,
                            "planItem=" + item.planItemId));
                }
                item.add(session.id(), entry);
            }
        }

        List<MetricsVos.PlanVersionComparison> comparisons = new ArrayList<>(groups.size());
        for (GroupAcc group : groups.values()) {
            comparisons.add(group.toVo(base));
        }
        return new MetricsVos.DayComparison(businessDate, isoWeekday, MetricsRules.RULE_VERSION, dataRevision,
                comparisons, selfInitiated.toVo(base, !sessions.isEmpty()), summary.toVo(base, !sessions.isEmpty()),
                List.copyOf(dayMissing));
    }

    // ================================================================== 本周统计

    /**
     * 本周（周一至周日）：场次数、不同训练日期数、有依据的动作时长、缺失说明。
     *
     * <p>周层面<b>不出现百分比</b>，更不把每天的百分比平均：三天 100% 与七天 0% 平均出的 37.5%
     * 不代表任何真实发生过的一周（stage2 §6）。只累加有据可查的绝对量。
     *
     * <p>周界必须是周一：随便一个日期当周界会让同一周的不同查询取到不同区间，
     * "本周"就失去唯一含义，页面与 Agent 工具都无法引用它。
     */
    @Transactional(readOnly = true)
    public MetricsVos.WeekSummary week(long userId, LocalDate weekStartDate) {
        if (weekStartDate.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "weekStartDate 必须是周一（ISO 星期 1），周统计按整周口径",
                    List.of(FieldError.of("weekStartDate", "NOT_WEEK_START",
                            "周统计的边界必须是周一", weekStartDate.toString())));
        }
        LocalDate weekEndDate = weekStartDate.plusDays(6);
        String dataRevision = revisionReader.currentRevisionText(userId);
        MetricsVos.Context base = MetricsVos.Context.of(dataRevision);

        List<WorkoutVos.SessionDetailVo> sessions = completedDetails(userId,
                workoutService.completedBetween(userId, weekStartDate, weekEndDate));

        Map<LocalDate, WeekDayAcc> days = new TreeMap<>();
        List<String> missing = new ArrayList<>();
        List<String> sessionIds = new ArrayList<>();
        for (WorkoutVos.SessionDetailVo session : sessions) {
            sessionIds.add(session.id());
            WeekDayAcc day = days.computeIfAbsent(session.businessDate(), WeekDayAcc::new);
            day.addSession(session, missing);
            for (WorkoutVos.EntryVo entry : safeList(session.entries())) {
                day.addEntry(session.id(), entry, missing);
            }
        }

        // 周合计直接从每日累加器再来一遍：两条路径各算各的迟早会对不上
        int confirmedSetsTotal = 0;
        int elapsedSecondsTotal = 0;
        DurationSum weekMovement = new DurationSum();
        for (WeekDayAcc day : days.values()) {
            confirmedSetsTotal += day.confirmedSets;
            elapsedSecondsTotal += day.elapsedSeconds;
            weekMovement.absorb(day.movement);
        }

        boolean hasSession = !sessions.isEmpty();
        List<MetricsVos.Metric> metrics = new ArrayList<>();
        metrics.add(MetricsVos.Metric.count(base, MetricsRules.COMPLETED_SESSION_COUNT, sessions.size(),
                MetricsRules.UNIT_SESSIONS, sessionIds, List.of(), List.of()));
        metrics.add(MetricsVos.Metric.count(base, MetricsRules.DISTINCT_TRAINING_DAY_COUNT, days.size(),
                MetricsRules.UNIT_DAYS, sessionIds, List.of(), List.of()));
        metrics.add(MetricsVos.Metric.count(base, MetricsRules.CONFIRMED_SETS_TOTAL, confirmedSetsTotal,
                MetricsRules.UNIT_SETS, sessionIds, List.of(), List.of()));
        metrics.add(MetricsVos.Metric.count(base, MetricsRules.SESSION_ELAPSED_SECONDS, elapsedSecondsTotal,
                MetricsRules.UNIT_SECONDS, sessionIds, List.of(), missing));
        metrics.add(weekMovement.metric(base, MetricsRules.EVIDENCED_MOVEMENT_SECONDS, hasSession,
                MetricsRules.DURATION_NOT_RECORDED, sessionIds, List.of(), missing));

        List<MetricsVos.WeekDayRow> rows = new ArrayList<>(days.size());
        for (WeekDayAcc day : days.values()) {
            rows.add(day.toVo());
        }
        return new MetricsVos.WeekSummary(weekStartDate, weekEndDate, MetricsRules.RULE_VERSION, dataRevision,
                metrics, rows, List.copyOf(missing));
    }

    // ================================================================== 读数

    /**
     * 只取已确认场次。SQL 已经过滤过一次，这里按返回 VO 的状态再确认一次：
     * 统计口径的成立不能只依赖别人的一条 WHERE。
     *
     * <p>每场次一次 {@code detail()} 是有意的取舍：metrics 因此完全不碰 workout 的 Mapper，
     * 读放大被"一天/一周最多几场"这个边界挡住。真需要批量优化时应在 workout 上加一个
     * 公开的批量读方法，而不是在这里自己拼查询。
     */
    private List<WorkoutVos.SessionDetailVo> completedDetails(long userId, List<WorkoutSessionEntity> rows) {
        List<WorkoutVos.SessionDetailVo> out = new ArrayList<>();
        for (WorkoutSessionEntity row : safeList(rows)) {
            if (row.getStatus() != WorkoutSessionStatus.COMPLETED) {
                continue;
            }
            WorkoutVos.SessionDetailVo detail = workoutService.detail(userId, row.getId());
            if (detail != null && WorkoutSessionStatus.COMPLETED.name().equals(detail.status())) {
                out.add(detail);
            }
        }
        return out;
    }

    /** 生效计划读不到就按"没有安排"处理：统计接口不该因为"还没建计划"而报错。 */
    private PlanDetailVo findActiveQuietly(long userId) {
        try {
            return planService.findActive(userId);
        } catch (BizException e) {
            if (e.errorCode() == ErrorCode.RESOURCE_NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    private static boolean coversDate(PlanDetailVo plan, LocalDate businessDate) {
        if (plan.startsOn() != null && businessDate.isBefore(plan.startsOn())) {
            return false;
        }
        return plan.endsOn() == null || !businessDate.isAfter(plan.endsOn());
    }

    private static PlanDayVo dayByWeekday(PlanDetailVo plan, int isoWeekday) {
        for (PlanDayVo day : safeList(plan.days())) {
            if (day.isoWeekday() != null && day.isoWeekday() == isoWeekday) {
                return day;
            }
        }
        return null;
    }

    private static String groupKey(String planId, String planDayId) {
        return planId + "|" + planDayId;
    }

    /**
     * 打开"计划版本 × 计划日"分组，并把该计划日的条目立成分母。
     *
     * <p>同一个 key 重复打开只补标记（是否生效安排、场次快照版本号），不新建第二组分母——
     * 分母只属于那一个版本，这是"不同版本不混分母"的落地方式。
     */
    private GroupAcc openGroup(Map<String, GroupAcc> groups, long userId, String planId, String planDayId,
                               Integer sessionRevisionNo, boolean scheduled,
                               Map<String, PlanDetailVo> cache, List<String> dayMissing) {
        String key = groupKey(planId, planDayId);
        GroupAcc existing = groups.get(key);
        if (existing != null) {
            existing.scheduled = existing.scheduled || scheduled;
            if (existing.revisionNo == null) {
                existing.revisionNo = sessionRevisionNo;
            }
            return existing;
        }
        GroupAcc group = new GroupAcc(planId, planDayId, sessionRevisionNo);
        group.scheduled = scheduled;
        groups.put(key, group);

        PlanDetailVo plan = loadPlanQuietly(userId, planId, cache);
        if (plan == null) {
            // 计划行按不变式不会被删；真读不到就标注，绝不凭空造一个分母
            group.planLoaded = false;
            group.missing.add(mark(MetricsRules.PLAN_VERSION_UNAVAILABLE, "plan=" + planId));
            dayMissing.add(mark(MetricsRules.PLAN_VERSION_UNAVAILABLE, "plan=" + planId));
            return group;
        }
        group.title = plan.title();
        group.status = plan.status();
        group.revisionNo = plan.revisionNo();
        if (sessionRevisionNo != null && !sessionRevisionNo.equals(plan.revisionNo())) {
            group.missing.add(mark(MetricsRules.PLAN_REVISION_SNAPSHOT_MISMATCH,
                    "场次快照=" + sessionRevisionNo + ",计划行=" + plan.revisionNo()));
        }
        PlanDayVo day = null;
        for (PlanDayVo candidate : safeList(plan.days())) {
            if (planDayId.equals(candidate.id())) {
                day = candidate;
                break;
            }
        }
        if (day == null) {
            group.missing.add(mark(MetricsRules.NO_PLAN_ITEMS, "planDay=" + planDayId + " 不属于该版本"));
            return group;
        }
        group.restDay = Boolean.TRUE.equals(day.isRestDay());
        for (PlanItemVo item : safeList(day.items())) {
            group.items.put(item.id(), ItemAcc.fromPlan(item));
        }
        return group;
    }

    /** 同一版本在一次请求里只读一次；读不到按"版本不可用"处理，而不是让整个统计接口 404。 */
    private PlanDetailVo loadPlanQuietly(long userId, String planIdText, Map<String, PlanDetailVo> cache) {
        if (cache.containsKey(planIdText)) {
            return cache.get(planIdText);
        }
        PlanDetailVo plan = null;
        try {
            plan = planService.findById(userId, Long.parseLong(planIdText.trim()));
        } catch (BizException e) {
            if (e.errorCode() != ErrorCode.RESOURCE_NOT_FOUND) {
                throw e;
            }
        } catch (NumberFormatException e) {
            // 参考 id 不是数字：外部数据异常，按不可用处理，其余口径照常给出
        }
        cache.put(planIdText, plan);
        return plan;
    }

    // ================================================================== 计算与格式化小件

    /**
     * 一个条目的实际动作时长：条目上记了就用它；否则只有<b>全部</b>已确认组都记录了时长才汇总。
     * 返回 null 表示未知——"没记"与"记了 0 秒"是两回事，补 0 会凭空造出一个偏小的总量。
     */
    private static Integer entryMovementSeconds(WorkoutVos.EntryVo entry) {
        if (entry.durationSeconds() != null) {
            return entry.durationSeconds();
        }
        int sum = 0;
        boolean anyConfirmed = false;
        for (WorkoutVos.SetVo set : safeList(entry.sets())) {
            if (!Boolean.TRUE.equals(set.isCompleted())) {
                continue;
            }
            anyConfirmed = true;
            if (set.durationSeconds() == null) {
                return null;
            }
            sum += set.durationSeconds();
        }
        return anyConfirmed ? sum : null;
    }

    /** 百分比：先乘 100 再按固定刻度除，与计算顺序无关，也不会出现二进制浮点尾巴。 */
    private static BigDecimal percent(long numerator, long denominator) {
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), MetricsRules.PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    /** 比率类指标：先要求有已确认依据，再要求分母不为 0，否则 null + 原因码（绝不落到 0% 或 100%）。 */
    private static MetricsVos.Metric ratio(MetricsVos.Context ctx, String code, String unit, boolean evidence,
                                           long numerator, long denominator, String noEvidenceReason,
                                           String noDenominatorReason, List<String> sessionIds,
                                           List<String> entryIds, List<String> missing) {
        if (!evidence) {
            return MetricsVos.Metric.unavailable(ctx, code, unit, noEvidenceReason, sessionIds, entryIds, missing);
        }
        if (denominator <= 0) {
            return MetricsVos.Metric.unavailable(ctx, code, unit, noDenominatorReason, sessionIds, entryIds, missing);
        }
        return MetricsVos.Metric.of(ctx, code, percent(numerator, denominator), unit, sessionIds, entryIds, missing);
    }

    /** 依赖"有已确认记录"的计数：没有依据时同样是 null，而不是假的 0。 */
    private static MetricsVos.Metric evidenceCount(MetricsVos.Context ctx, String code, String unit, long value,
                                                   boolean evidence, String noEvidenceReason,
                                                   List<String> sessionIds, List<String> entryIds,
                                                   List<String> missing) {
        return evidence
                ? MetricsVos.Metric.of(ctx, code, BigDecimal.valueOf(value), unit, sessionIds, entryIds, missing)
                : MetricsVos.Metric.unavailable(ctx, code, unit, noEvidenceReason, sessionIds, entryIds, missing);
    }

    private static String mark(String reason, String detail) {
        return detail == null || detail.isBlank() ? reason : reason + ":" + detail;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    // ================================================================== 累加器

    /** 一个"计划版本 × 计划日"的分组：计划侧来自计划行，实际侧只来自已确认记录。 */
    private static final class GroupAcc {
        private final String planId;
        private final String planDayId;
        private final Map<String, ItemAcc> items = new LinkedHashMap<>();
        private final List<String> sessionIds = new ArrayList<>();
        private final List<String> missing = new ArrayList<>();
        private Integer revisionNo;
        private String title;
        private String status;
        private boolean scheduled;
        private boolean planLoaded = true;
        private boolean restDay;

        private GroupAcc(String planId, String planDayId, Integer revisionNo) {
            this.planId = planId;
            this.planDayId = planDayId;
            this.revisionNo = revisionNo;
        }

        private void attachSession(String sessionId) {
            if (!sessionIds.contains(sessionId)) {
                sessionIds.add(sessionId);
            }
        }

        private MetricsVos.PlanVersionComparison toVo(MetricsVos.Context base) {
            MetricsVos.Context ctx = base.forPlan(planId, revisionNo);
            List<ItemAcc> ordered = new ArrayList<>(items.values());
            // List.sort 稳定：同 positionNo 时保持计划里的条目顺序，不引入第二个排序口径
            ordered.sort(Comparator.comparingInt(ItemAcc::sortPosition));

            long plannedSets = 0;
            long cappedSets = 0;
            long extraSets = 0;
            long repsEvaluated = 0;
            long repsInRange = 0;
            boolean anyRepsRangeKnown = false;
            boolean evidence = false;
            DurationSum recorded = new DurationSum();
            DurationSum capped = new DurationSum();
            LinkedHashSet<String> entryIds = new LinkedHashSet<>();
            List<String> groupMissing = new ArrayList<>(missing);

            for (ItemAcc item : ordered) {
                entryIds.addAll(item.entryIds);
                // "有依据"= 这一组计划版本下至少确认过一组；只开了场次而一组都没确认时，
                // 完成度必须是 null + NO_CONFIRMED_SESSION，而不是看起来像事实的 0%
                evidence = evidence || item.confirmedSets > 0;
                if (item.targetSets != null && item.targetSets > 0) {
                    plannedSets += item.targetSets;
                    // 先跨场次汇总（confirmedSets 已在 ItemAcc 里累加完）再封顶
                    cappedSets += Math.min(item.confirmedSets, item.targetSets);
                    extraSets += Math.max(0, item.confirmedSets - item.targetSets);
                }
                if (item.durationKnownCount > 0) {
                    recorded.addKnown(item.durationSum, item.durationKnownCount);
                }
                if (item.durationUnknownCount > 0) {
                    recorded.markUnknown(item.durationUnknownCount);
                    groupMissing.add(mark(MetricsRules.DURATION_NOT_RECORDED,
                            "planItem=" + item.planItemId + ",entry=" + String.join(",", item.durationUnknownEntryIds)));
                }
                // 封顶合计只收"时长完整已知"的条目：把未知条目按 0 秒塞进封顶值，等于一边承认缺失一边假装算全
                if (item.durationUnknownCount == 0 && item.durationKnownCount > 0) {
                    if (item.targetDurationSeconds == null) {
                        capped.markUnknown(1);
                        groupMissing.add(mark(MetricsRules.NO_PLAN_DURATION_TARGET, "planItem=" + item.planItemId));
                    } else {
                        capped.addKnown(Math.min(item.durationSum, item.targetDurationSeconds), 1);
                    }
                }
                if (item.rangeKnown()) {
                    anyRepsRangeKnown = true;
                    repsEvaluated += item.setsWithReps;
                    repsInRange += item.setsInRepsRange;
                }
            }

            List<MetricsVos.Metric> metrics = new ArrayList<>();
            metrics.add(ratio(ctx, MetricsRules.SET_COMPLETION_PCT, MetricsRules.UNIT_PERCENT, evidence,
                    cappedSets, plannedSets, MetricsRules.NO_CONFIRMED_SESSION, MetricsRules.NO_TARGET,
                    sessionIds, List.copyOf(entryIds), groupMissing));
            metrics.add(evidenceCount(ctx, MetricsRules.PLANNED_TARGET_SETS, MetricsRules.UNIT_SETS, plannedSets,
                    plannedSets > 0, planLoaded ? MetricsRules.NO_TARGET : MetricsRules.PLAN_VERSION_UNAVAILABLE,
                    sessionIds, List.copyOf(entryIds), groupMissing));
            // 封顶值与额外组都以"计划给过目标组数"为前提：没有目标就无从封顶，也无从谈超额
            boolean setsComputable = evidence && plannedSets > 0;
            String setsNotComputableReason = evidence ? MetricsRules.NO_TARGET : MetricsRules.NO_CONFIRMED_SESSION;
            metrics.add(evidenceCount(ctx, MetricsRules.CONFIRMED_SETS_CAPPED, MetricsRules.UNIT_SETS, cappedSets,
                    setsComputable, setsNotComputableReason, sessionIds, List.copyOf(entryIds), groupMissing));
            metrics.add(evidenceCount(ctx, MetricsRules.EXTRA_SETS, MetricsRules.UNIT_SETS, extraSets,
                    setsComputable, setsNotComputableReason, sessionIds, List.copyOf(entryIds), groupMissing));
            metrics.add(recorded.metric(ctx, MetricsRules.RECORDED_MOVEMENT_SECONDS, evidence,
                    MetricsRules.DURATION_NOT_RECORDED, sessionIds, List.copyOf(entryIds), groupMissing));
            metrics.add(capped.metric(ctx, MetricsRules.CAPPED_MOVEMENT_SECONDS, evidence,
                    anyDurationTarget(ordered) ? MetricsRules.DURATION_NOT_RECORDED : MetricsRules.NO_TARGET,
                    sessionIds, List.copyOf(entryIds), groupMissing));
            metrics.add(repsMetric(ctx, MetricsRules.REPS_IN_TARGET_RANGE_PCT, evidence, anyRepsRangeKnown,
                    repsInRange, repsEvaluated, sessionIds, List.copyOf(entryIds), groupMissing));
            if (restDay && plannedSets == 0) {
                groupMissing.add(mark(MetricsRules.NO_TARGET, "该计划日是休息日，本来就没有可完成的量"));
            }

            List<MetricsVos.ItemComparison> itemVos = new ArrayList<>(ordered.size());
            for (ItemAcc item : ordered) {
                itemVos.add(item.toVo(ctx));
            }
            return new MetricsVos.PlanVersionComparison(planId, planDayId, title, revisionNo, status, scheduled,
                    itemVos, List.copyOf(metrics), List.copyOf(sessionIds), List.copyOf(groupMissing));
        }

        private static boolean anyDurationTarget(List<ItemAcc> items) {
            for (ItemAcc item : items) {
                if (item.targetDurationSeconds != null) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 次数是否在目标范围：分母是"记录了次数的已确认组"，与"组是否被确认"完全无关，
         * 因此组级与条目级共用同一个算法，只是指标码不同。
         */
        private static MetricsVos.Metric repsMetric(MetricsVos.Context ctx, String code, boolean evidence,
                                                    boolean rangeKnown, long inRange, long evaluated,
                                                    List<String> sessionIds, List<String> entryIds,
                                                    List<String> missing) {
            if (!evidence) {
                return MetricsVos.Metric.unavailable(ctx, code, MetricsRules.UNIT_PERCENT,
                        MetricsRules.NO_CONFIRMED_SESSION, sessionIds, entryIds, missing);
            }
            if (!rangeKnown) {
                return MetricsVos.Metric.unavailable(ctx, code, MetricsRules.UNIT_PERCENT,
                        MetricsRules.NO_REPS_TARGET, sessionIds, entryIds, missing);
            }
            return ratio(ctx, code, MetricsRules.UNIT_PERCENT, true, inRange, evaluated,
                    MetricsRules.NO_CONFIRMED_SESSION, MetricsRules.NO_REPS_RECORDED, sessionIds, entryIds, missing);
        }
    }

    /** 一个计划条目的累加：组数与时长各自汇总，封顶留给分组（保证跨场次先汇总）。 */
    private static final class ItemAcc {
        private final String planItemId;
        private final String exerciseId;
        private final String exerciseCode;
        private final String exerciseName;
        private final Integer positionNo;
        private final Integer targetSets;
        private final Integer repsMin;
        private final Integer repsMax;
        private final Integer targetDurationSeconds;
        private final BigDecimal targetExternalLoadKg;
        private final boolean targetFromPlan;
        private final List<String> sessionIds = new ArrayList<>();
        private final List<String> entryIds = new ArrayList<>();
        private final List<String> durationUnknownEntryIds = new ArrayList<>();
        private int confirmedSets;
        private int durationSum;
        private int durationKnownCount;
        private int durationUnknownCount;
        private int setsWithReps;
        private int setsInRepsRange;
        private int setsOutOfRepsRange;
        private int setsWithoutReps;

        private ItemAcc(String planItemId, String exerciseId, String exerciseCode, String exerciseName,
                        Integer positionNo, Integer targetSets, Integer repsMin, Integer repsMax,
                        Integer targetDurationSeconds, BigDecimal targetExternalLoadKg, boolean targetFromPlan) {
            this.planItemId = planItemId;
            this.exerciseId = exerciseId;
            this.exerciseCode = exerciseCode;
            this.exerciseName = exerciseName;
            this.positionNo = positionNo;
            this.targetSets = targetSets;
            this.repsMin = repsMin;
            this.repsMax = repsMax;
            this.targetDurationSeconds = targetDurationSeconds;
            this.targetExternalLoadKg = targetExternalLoadKg;
            this.targetFromPlan = targetFromPlan;
        }

        private static ItemAcc fromPlan(PlanItemVo item) {
            return new ItemAcc(item.id(), item.exerciseId(), item.exerciseCode(), item.exerciseName(),
                    item.positionNo(), item.targetSets(), item.repsMin(), item.repsMax(),
                    item.targetDurationSeconds(), item.targetExternalLoadKg(), true);
        }

        /** 计划里没这个条目时退回场次自带的参考回显值：宁可标注来源可疑，也不丢掉已确认的记录。 */
        private static ItemAcc fromEntry(WorkoutVos.EntryVo entry) {
            return new ItemAcc(entry.planItemId(), entry.exerciseId(), entry.exerciseCode(), entry.exerciseName(),
                    entry.positionNo(), entry.planTargetSets(), entry.planRepsMin(), entry.planRepsMax(),
                    entry.planTargetDurationSeconds(), entry.planTargetExternalLoadKg(), false);
        }

        private Integer sortPosition() {
            return positionNo == null ? Integer.MAX_VALUE : positionNo;
        }

        private boolean rangeKnown() {
            return repsMin != null || repsMax != null;
        }

        private void add(String sessionId, WorkoutVos.EntryVo entry) {
            if (!sessionIds.contains(sessionId)) {
                sessionIds.add(sessionId);
            }
            entryIds.add(entry.id());
            int confirmed = nz(entry.completedSets());
            confirmedSets += confirmed;
            if (confirmed > 0) {
                Integer duration = entryMovementSeconds(entry);
                if (duration == null) {
                    durationUnknownCount++;
                    durationUnknownEntryIds.add(entry.id());
                } else {
                    durationSum += duration;
                    durationKnownCount++;
                }
            }
            for (WorkoutVos.SetVo set : safeList(entry.sets())) {
                if (!Boolean.TRUE.equals(set.isCompleted())) {
                    continue;
                }
                if (set.reps() == null) {
                    setsWithoutReps++;
                } else if (repsInRange(set.reps())) {
                    setsWithReps++;
                    setsInRepsRange++;
                } else {
                    setsWithReps++;
                    setsOutOfRepsRange++;
                }
            }
        }

        /** 单侧缺界时按已知那一侧判定：计划没给上界不等于无限，只是那一侧无从判起。 */
        private boolean repsInRange(int reps) {
            if (repsMin == null && repsMax == null) {
                return false;
            }
            if (repsMin != null && reps < repsMin) {
                return false;
            }
            return repsMax == null || reps <= repsMax;
        }

        private MetricsVos.ItemComparison toVo(MetricsVos.Context ctx) {
            int capped = targetSets == null || targetSets <= 0 ? 0 : Math.min(confirmedSets, targetSets);
            int extra = targetSets == null || targetSets <= 0 ? 0 : Math.max(0, confirmedSets - targetSets);
            boolean evidence = confirmedSets > 0;
            // 封顶时长同样只在"该条目时长完整已知且计划给过目标时长"时才有值，未知绝不落到 0 秒
            boolean durationFullyKnown = durationUnknownCount == 0 && durationKnownCount > 0;
            Integer cappedDuration = durationFullyKnown && targetDurationSeconds != null
                    ? Math.min(durationSum, targetDurationSeconds) : null;

            List<String> itemMissing = new ArrayList<>();
            if (!durationUnknownEntryIds.isEmpty()) {
                itemMissing.add(mark(MetricsRules.DURATION_NOT_RECORDED,
                        "entry=" + String.join(",", durationUnknownEntryIds)));
            }
            if (targetSets == null && targetDurationSeconds == null) {
                itemMissing.add(mark(MetricsRules.NO_TARGET,
                        "planItem=" + planItemId + " 既没有目标组数也没有目标时长"));
            }

            DurationSum recorded = new DurationSum();
            if (durationKnownCount > 0) {
                recorded.addKnown(durationSum, durationKnownCount);
            }
            if (durationUnknownCount > 0) {
                recorded.markUnknown(durationUnknownCount);
            }
            DurationSum cappedDurationSum = new DurationSum();
            if (evidence) {
                if (durationFullyKnown && targetDurationSeconds != null) {
                    cappedDurationSum.addKnown(Math.min(durationSum, targetDurationSeconds), 1);
                } else {
                    // 时长未知、或计划没给目标时长：这一项没有封顶值，null + 原因码
                    cappedDurationSum.markUnknown(1);
                }
            }

            boolean itemSetsComputable = evidence && targetSets != null && targetSets > 0;
            String itemSetsNotComputableReason = evidence ? MetricsRules.NO_TARGET : MetricsRules.NO_CONFIRMED_SESSION;
            List<MetricsVos.Metric> metrics = List.of(
                    evidenceCount(ctx, MetricsRules.ITEM_CONFIRMED_SETS, MetricsRules.UNIT_SETS, confirmedSets,
                            evidence, MetricsRules.NO_CONFIRMED_SESSION, sessionIds, entryIds, itemMissing),
                    evidenceCount(ctx, MetricsRules.ITEM_CONFIRMED_SETS_CAPPED, MetricsRules.UNIT_SETS, capped,
                            itemSetsComputable, itemSetsNotComputableReason, sessionIds, entryIds, itemMissing),
                    evidenceCount(ctx, MetricsRules.ITEM_EXTRA_SETS, MetricsRules.UNIT_SETS, extra,
                            itemSetsComputable, itemSetsNotComputableReason, sessionIds, entryIds, itemMissing),
                    evidenceCount(ctx, MetricsRules.ITEM_PLANNED_TARGET_SETS, MetricsRules.UNIT_SETS,
                            targetSets == null ? 0 : targetSets, targetSets != null && targetSets > 0,
                            MetricsRules.NO_TARGET, sessionIds, entryIds, itemMissing),
                    ratio(ctx, MetricsRules.ITEM_SET_COMPLETION_PCT, MetricsRules.UNIT_PERCENT, evidence,
                            capped, targetSets == null ? 0 : targetSets, MetricsRules.NO_CONFIRMED_SESSION,
                            MetricsRules.NO_TARGET, sessionIds, entryIds, itemMissing),
                    recorded.metric(ctx, MetricsRules.ITEM_RECORDED_MOVEMENT_SECONDS, evidence,
                            MetricsRules.DURATION_NOT_RECORDED, sessionIds, entryIds, itemMissing),
                    cappedDurationSum.metric(ctx, MetricsRules.ITEM_CAPPED_MOVEMENT_SECONDS, evidence,
                            targetDurationSeconds == null ? MetricsRules.NO_TARGET
                                    : MetricsRules.DURATION_NOT_RECORDED, sessionIds, entryIds, itemMissing),
                    GroupAcc.repsMetric(ctx, MetricsRules.ITEM_REPS_IN_TARGET_RANGE_PCT, evidence, rangeKnown(),
                            setsInRepsRange, setsWithReps, sessionIds, entryIds, itemMissing));

            String durationReason;
            if (!evidence) {
                durationReason = MetricsRules.NO_CONFIRMED_SESSION;
            } else if (durationUnknownCount > 0 || durationKnownCount == 0) {
                durationReason = MetricsRules.DURATION_NOT_RECORDED;
            } else if (targetDurationSeconds == null) {
                durationReason = MetricsRules.NO_PLAN_DURATION_TARGET;
            } else {
                durationReason = null;
            }
            MetricsVos.PlannedSide planned = new MetricsVos.PlannedSide(targetSets, repsMin, repsMax,
                    targetExternalLoadKg, targetDurationSeconds,
                    targetFromPlan ? null : MetricsRules.PLAN_VERSION_UNAVAILABLE);
            MetricsVos.ActualSide actual = new MetricsVos.ActualSide(confirmedSets, capped, extra,
                    durationKnownCount > 0 ? durationSum : null, cappedDuration, durationReason);
            String repsReason = !rangeKnown() ? MetricsRules.NO_REPS_TARGET
                    : (setsWithReps == 0 ? MetricsRules.NO_REPS_RECORDED : null);
            MetricsVos.RepsCheck reps = new MetricsVos.RepsCheck(rangeKnown(), confirmedSets, setsWithReps,
                    setsInRepsRange, setsOutOfRepsRange, setsWithoutReps,
                    rangeKnown() && setsWithReps > 0 ? percent(setsInRepsRange, setsWithReps) : null, repsReason);
            return new MetricsVos.ItemComparison(planItemId, exerciseId, exerciseCode, exerciseName, positionNo,
                    planned, actual, reps, metrics, List.copyOf(sessionIds), List.copyOf(entryIds),
                    List.copyOf(itemMissing));
        }
    }

    /** 自主动作（不参考任何计划条目）：没有分母，因此只单列，不进任何完成度、也不抵消缺口。 */
    private static final class SelfInitiatedAcc {
        private final List<MetricsVos.SelfInitiatedEntry> entries = new ArrayList<>();
        private final List<String> sessionIds = new ArrayList<>();
        private final List<String> entryIds = new ArrayList<>();
        private final List<String> missing = new ArrayList<>();
        private final DurationSum movement = new DurationSum();
        private int confirmedSets;

        private void add(String sessionId, WorkoutVos.EntryVo entry) {
            int confirmed = nz(entry.completedSets());
            confirmedSets += confirmed;
            Integer duration = confirmed > 0 ? entryMovementSeconds(entry) : null;
            if (confirmed > 0) {
                if (duration == null) {
                    movement.markUnknown(1);
                    missing.add(mark(MetricsRules.DURATION_NOT_RECORDED,
                            "session=" + sessionId + ",entry=" + entry.id()));
                } else {
                    movement.addKnown(duration, 1);
                }
            }
            if (!sessionIds.contains(sessionId)) {
                sessionIds.add(sessionId);
            }
            entryIds.add(entry.id());
            entries.add(new MetricsVos.SelfInitiatedEntry(sessionId, entry.id(), entry.exerciseId(),
                    entry.exerciseName(), entry.positionNo(), confirmed, duration, entry.distanceMeters()));
        }

        private MetricsVos.SelfInitiatedSummary toVo(MetricsVos.Context base, boolean hasConfirmedSession) {
            List<MetricsVos.Metric> metrics = new ArrayList<>();
            metrics.add(MetricsVos.Metric.count(base, MetricsRules.SELF_INITIATED_ENTRY_COUNT, entries.size(),
                    MetricsRules.UNIT_ENTRIES, sessionIds, entryIds, List.of()));
            metrics.add(MetricsVos.Metric.count(base, MetricsRules.SELF_INITIATED_CONFIRMED_SETS, confirmedSets,
                    MetricsRules.UNIT_SETS, sessionIds, entryIds, List.of()));
            metrics.add(movement.metric(base, MetricsRules.SELF_INITIATED_MOVEMENT_SECONDS, hasConfirmedSession,
                    MetricsRules.DURATION_NOT_RECORDED, sessionIds, entryIds, missing));
            return new MetricsVos.SelfInitiatedSummary(List.copyOf(entries), List.copyOf(metrics),
                    List.copyOf(missing));
        }
    }

    /** 当天记录摘要：场次历时与动作时长各自成立，<b>永不相加</b>成"总活动时长"。 */
    private static final class DaySummaryAcc {
        private final List<String> sessionIds = new ArrayList<>();
        private final List<String> missing = new ArrayList<>();
        private final DurationSum movement = new DurationSum();
        private int recordedEntries;
        private int confirmedSets;
        private int elapsedSeconds;
        private Instant firstStartedAt;
        private Instant lastEndedAt;

        private void addSession(WorkoutVos.SessionDetailVo session) {
            sessionIds.add(session.id());
            if (session.durationSeconds() == null) {
                missing.add(mark(MetricsRules.SESSION_ELAPSED_UNAVAILABLE, "session=" + session.id()));
            } else {
                elapsedSeconds += session.durationSeconds();
            }
            if (session.startedAt() != null && (firstStartedAt == null || session.startedAt().isBefore(firstStartedAt))) {
                firstStartedAt = session.startedAt();
            }
            if (session.endedAt() != null && (lastEndedAt == null || session.endedAt().isAfter(lastEndedAt))) {
                lastEndedAt = session.endedAt();
            }
        }

        private void addEntry(String sessionId, WorkoutVos.EntryVo entry) {
            recordedEntries++;
            int confirmed = nz(entry.completedSets());
            confirmedSets += confirmed;
            if (confirmed <= 0) {
                return;
            }
            Integer duration = entryMovementSeconds(entry);
            if (duration == null) {
                movement.markUnknown(1);
                missing.add(mark(MetricsRules.DURATION_NOT_RECORDED, "session=" + sessionId + ",entry=" + entry.id()));
            } else {
                movement.addKnown(duration, 1);
            }
        }

        private MetricsVos.DayRecordSummary toVo(MetricsVos.Context base, boolean hasConfirmedSession) {
            List<MetricsVos.Metric> metrics = new ArrayList<>();
            metrics.add(MetricsVos.Metric.count(base, MetricsRules.COMPLETED_SESSION_COUNT, sessionIds.size(),
                    MetricsRules.UNIT_SESSIONS, sessionIds, List.of(), List.of()));
            metrics.add(MetricsVos.Metric.count(base, MetricsRules.CONFIRMED_SETS_TOTAL, confirmedSets,
                    MetricsRules.UNIT_SETS, sessionIds, List.of(), List.of()));
            metrics.add(MetricsVos.Metric.count(base, MetricsRules.SESSION_ELAPSED_SECONDS, elapsedSeconds,
                    MetricsRules.UNIT_SECONDS, sessionIds, List.of(), missing));
            metrics.add(movement.metric(base, MetricsRules.RECORDED_MOVEMENT_SECONDS, hasConfirmedSession,
                    MetricsRules.DURATION_NOT_RECORDED, sessionIds, List.of(), missing));
            return new MetricsVos.DayRecordSummary(sessionIds.size(), List.copyOf(sessionIds), recordedEntries,
                    confirmedSets, elapsedSeconds, movement.secondsKnown() ? (int) movement.seconds() : null,
                    firstStartedAt, lastEndedAt, List.copyOf(metrics), List.copyOf(missing));
        }
    }

    /** 周里某一天：只存绝对量，不存百分比（周层面平均日百分比是假口径）。 */
    private static final class WeekDayAcc {
        private final LocalDate businessDate;
        private int sessionCount;
        private int confirmedSets;
        private int elapsedSeconds;
        private final DurationSum movement = new DurationSum();
        private final List<String> missing = new ArrayList<>();

        private WeekDayAcc(LocalDate businessDate) {
            this.businessDate = businessDate;
        }

        private void addSession(WorkoutVos.SessionDetailVo session, List<String> missing) {
            sessionCount++;
            if (session.durationSeconds() == null) {
                missing.add(mark(MetricsRules.SESSION_ELAPSED_UNAVAILABLE, "session=" + session.id()));
            } else {
                elapsedSeconds += session.durationSeconds();
            }
        }

        private void addEntry(String sessionId, WorkoutVos.EntryVo entry, List<String> missing) {
            int confirmed = nz(entry.completedSets());
            confirmedSets += confirmed;
            if (confirmed <= 0) {
                return;
            }
            Integer duration = entryMovementSeconds(entry);
            if (duration == null) {
                movement.markUnknown(1);
                missing.add(mark(MetricsRules.DURATION_NOT_RECORDED, "session=" + sessionId + ",entry=" + entry.id()));
            } else {
                movement.addKnown(duration, 1);
            }
        }

        private MetricsVos.WeekDayRow toVo() {
            String reason = null;
            if (movement.secondsKnown()) {
                reason = movement.secondsUnknown() ? MetricsRules.DURATION_NOT_RECORDED : null;
            } else {
                reason = confirmedSets > 0 ? MetricsRules.DURATION_NOT_RECORDED : MetricsRules.NO_CONFIRMED_SESSION;
            }
            return new MetricsVos.WeekDayRow(businessDate, sessionCount, confirmedSets,
                    movement.secondsKnown() ? (int) movement.seconds() : null, reason, elapsedSeconds);
        }
    }

    /**
     * 时长合计的统一出口：已知值累加、未知只计数，"一条已知都没有"时返回 null + 原因码。
     *
     * <p>这一层存在的原因是同一个坑最容易踩在时长上——把"没记"加成 0 秒，总量看着合理，
     * 实则每一天的缺口都被静抹平了。
     */
    private static final class DurationSum {
        private long seconds;
        private int knownCount;
        private int unknownCount;

        private void addKnown(int value, int items) {
            seconds += value;
            knownCount += items;
        }

        private void markUnknown(int items) {
            unknownCount += items;
        }

        /** 把下层累加器原样并进来：合计与明细必须出自同一次累加，否则缺口会在某一侧凭空消失。 */
        private void absorb(DurationSum other) {
            seconds += other.seconds;
            knownCount += other.knownCount;
            unknownCount += other.unknownCount;
        }

        private boolean secondsKnown() {
            return knownCount > 0;
        }

        /** 存在未知项：合计只是部分量，必须让调用方把它写进 missingFields。 */
        private boolean secondsUnknown() {
            return unknownCount > 0;
        }

        private long seconds() {
            return seconds;
        }

        private MetricsVos.Metric metric(MetricsVos.Context ctx, String code, boolean evidence,
                                         String noValueReason, List<String> sessionIds, List<String> entryIds,
                                         List<String> missing) {
            if (knownCount > 0) {
                return MetricsVos.Metric.of(ctx, code, BigDecimal.valueOf(seconds), MetricsRules.UNIT_SECONDS,
                        sessionIds, entryIds, missing);
            }
            return MetricsVos.Metric.unavailable(ctx, code, MetricsRules.UNIT_SECONDS,
                    evidence ? noValueReason : MetricsRules.NO_CONFIRMED_SESSION, sessionIds, entryIds, missing);
        }
    }
}
