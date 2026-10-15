package com.hfj.fitagent.modules.coach.tools;

import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.tool.AgentTool;
import com.hfj.fitagent.agent.tool.Evidence;
import com.hfj.fitagent.agent.tool.ToolDefinition;
import com.hfj.fitagent.agent.tool.ToolErrorCode;
import com.hfj.fitagent.agent.tool.ToolResult;
import com.hfj.fitagent.common.web.PageQuery;
import com.hfj.fitagent.common.web.PageResult;
import com.hfj.fitagent.common.web.SortColumns;
import com.hfj.fitagent.modules.profile.service.BodyMeasurementService;
import com.hfj.fitagent.modules.profile.vo.BodyRecordVo;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.stereotype.Component;

import com.hfj.fitagent.common.web.DateRange;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code get_weight_trend}：区间内的<b>真实</b>体重序列（baseline §3.3 确认可注册——
 * {@code body_measurement} 有真实序列，{@code BodyMeasurementService} 有 16 个测试）。
 *
 * <p>四条口径都在这里定死，因为它们都是"模型会自己补、而补出来就像事实"的地方：
 * <ol>
 *   <li><b>不做预测、不做热量缺口、不做任何医学阈值</b>（AGENTS.md）：只有实测值、
 *       首末差值与覆盖区间。返回体里<b>没有</b> {@code projectedWeight} 或 {@code kcalDeficit} 这种字段，
 *       所以结构上不可能被当成服务端给的结论。</li>
 *   <li><b>同日多条的去重规则被写进结果</b>（{@code dedupeRule}）：同一业务日期保留
 *       {@code measured_at} 最新的一条（与 {@code BodyMeasurementService.findLatest} 的
 *       {@code ORDER BY measured_at DESC, id DESC} 同一口径）。规则<b>声明</b>出来而不是悄悄平均，
 *       因为"平均同一天两次称重"会把晨晚差异抹成一个从没发生过的数字。</li>
 *   <li><b>{@code coveredFrom/coveredTo} 取实际首末测量日</b>，不是请求区间：
 *       请求 30 天但只有 3 条记录时，说"这 30 天平均…"就是把空档说成了数据。</li>
 *   <li><b>不足两条一律 {@code changeKg = null} 并列 missingFields</b>：
 *       "没有记录"与"记录不足以求差"都不能被 0 冒充（§9）。</li>
 * </ol>
 *
 * <p>31 天上限是<b>工程闸门</b>（一次读取的边界），不是产品或健康建议。
 */
@Component
public class GetWeightTrendTool implements AgentTool<GetWeightTrendTool.Args, GetWeightTrendTool.Facts> {

    /** 区间跨度上限（含首尾）：工程闸门，与 {@code DateRange.MAX_SPAN_DAYS} 无关。 */
    public static final int MAX_RANGE_DAYS = 31;

    /** 单次读取的记录上限：分页服务的硬上限，超出即如实标不完整而不是"取前 N 条当全部"。 */
    public static final int MAX_RECORDS = 100;

    /** 去重规则的机器名：结果里带着它，界面与模型都无法把它误读成"平均"。 */
    public static final String DEDUPE_RULE = "LATEST_MEASURED_AT_PER_BUSINESS_DATE";

    /** 本接口的排序白名单只用到 measuredAt；映射显式给出，请求字段名永不进 SQL。 */
    static final SortColumns SORT = SortColumns.of("measuredAt", "measured_at", "businessDate", "business_date");

    private final BodyMeasurementService measurementService;

    public GetWeightTrendTool(BodyMeasurementService measurementService) {
        this.measurementService = measurementService;
    }

    /** 两个日期都是 ISO {@code YYYY-MM-DD}；跨度 ≤ {@link #MAX_RANGE_DAYS} 天。 */
    public record Args(
            @NotNull(message = "start_date 不能为空")
            @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "必须是 YYYY-MM-DD")
            String startDate,
            @NotNull(message = "end_date 不能为空")
            @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "必须是 YYYY-MM-DD")
            String endDate
    ) {
    }

    /**
     * @param coveredFrom/coveredTo <b>实际</b>首条与末条测量的业务日期；无记录时 null
     * @param changeKg            末条减首条；少于 2 条时 null（不是 0）
     * @param points              去重后的实测点，按 measuredAt 升序
     */
    public record Facts(String startDate,
                        String endDate,
                        String dedupeRule,
                        Integer recordCountInWindow,
                        Integer pointCount,
                        String coveredFrom,
                        String coveredTo,
                        BigDecimal firstWeightKg,
                        BigDecimal lastWeightKg,
                        BigDecimal changeKg,
                        List<Point> points,
                        Long dataRevision) {
    }

    /** 实测点：值与单位都在同一字段名里说清楚（{@code weightKg} 的 kg 是列名的一部分，不是推断）。 */
    public record Point(String measurementId, String businessDate, String measuredAt, BigDecimal weightKg) {
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(CoachToolNames.GET_WEIGHT_TREND,
                "读取指定区间内的真实体重测量记录（含同日去重）。返回实测点、实际覆盖区间与首末差值。"
                        + "没有任何预测、目标达成度或热量缺口：这些本工具不提供，也不要自行推算。"
                        + "记录不足两条时 changeKg 为 null 并在 missingFields 中列出。",
                CoachToolSchemas.object(List.of("start_date", "end_date"),
                        List.<String[]>of(new String[]{"start_date", CoachToolSchemas.isoDate("区间起始业务日期（含）")},
                                new String[]{"end_date", CoachToolSchemas.isoDate(
                                        "区间结束业务日期（含）；跨度不得超过 " + MAX_RANGE_DAYS + " 天")})));
    }

    @Override
    public Class<Args> argumentType() {
        return Args.class;
    }

    @Override
    public ToolResult<Facts> execute(Args args, AgentContext ctx) {
        LocalDate start = parse(args.startDate());
        LocalDate end = parse(args.endDate());
        if (start == null || end == null) {
            return ToolResult.error(ToolErrorCode.VALIDATION_FAILED, "start_date / end_date 必须是 YYYY-MM-DD");
        }
        if (end.isBefore(start)) {
            return ToolResult.error(ToolErrorCode.VALIDATION_FAILED, "end_date 不能早于 start_date");
        }
        // 跨度按"含首尾的天数"计：31 天的区间就是 start + 30 == end
        if (start.plusDays(MAX_RANGE_DAYS - 1L).isBefore(end)) {
            return ToolResult.error(ToolErrorCode.VALIDATION_FAILED,
                    "查询跨度不得超过 " + MAX_RANGE_DAYS + " 天（工程上限，不是健康建议）");
        }

        PageResult<BodyRecordVo> page = measurementService.list(ctx.userId(),
                new DateRange(start, end),
                PageQuery.of(1, MAX_RECORDS, "measuredAt,asc", SORT.allowedFields(), "measuredAt"),
                SORT);
        List<BodyRecordVo> rows = page == null || page.items() == null ? List.of() : page.items();
        long total = page == null ? 0 : page.total();

        // 同日去重：入参按 measuredAt 升序，后写的同日期记录覆盖前写的 → 每个业务日期留下最新一次测量
        Map<LocalDate, BodyRecordVo> latestPerDate = new LinkedHashMap<>();
        for (BodyRecordVo row : rows) {
            if (row.businessDate() != null) {
                latestPerDate.put(row.businessDate(), row);
            }
        }
        List<BodyRecordVo> points = new ArrayList<>(latestPerDate.values());
        List<String> missing = new ArrayList<>();

        if (points.isEmpty()) {
            missing.add("weightMeasurements");
            Facts empty = new Facts(start.toString(), end.toString(), DEDUPE_RULE, 0, 0, null, null,
                    null, null, null, List.of(), ctx.dataRevision());
            return ToolResult.ok(empty, List.of(), List.copyOf(missing), ctx.dataRevision());
        }

        List<Evidence> evidence = new ArrayList<>(points.size());
        List<Point> payload = new ArrayList<>(points.size());
        for (BodyRecordVo row : points) {
            evidence.add(new Evidence(CoachToolNames.TABLE_BODY_MEASUREMENT, row.id(), null,
                    row.businessDate(), CoachToolNames.RULE_NONE, ctx.dataRevision()));
            payload.add(new Point(row.id(), row.businessDate().toString(),
                    row.measuredAt() == null ? null : row.measuredAt().toString(), row.weightKg()));
        }

        BigDecimal first = points.get(0).weightKg();
        BigDecimal last = points.get(points.size() - 1).weightKg();
        BigDecimal change = first != null && last != null ? last.subtract(first) : null;
        if (points.size() < 2) {
            // 一个点求不出"变化"：点名缺失项，绝不用 0.00 表示"没变"
            missing.add("secondMeasurementForChange");
        }
        if (change == null) {
            missing.add("changeKg");
        }
        if (total > rows.size()) {
            // 一次没读完就明说：区间内还有记录没进本轮，结论口径必须按不完整处理
            missing.add("measurementWindowTruncated");
        }

        Facts facts = new Facts(start.toString(), end.toString(), DEDUPE_RULE, (int) total, points.size(),
                points.get(0).businessDate().toString(),
                points.get(points.size() - 1).businessDate().toString(),
                first, last, change, List.copyOf(payload), ctx.dataRevision());
        if (missing.isEmpty()) {
            return ToolResult.ok(facts, List.copyOf(evidence), ctx.dataRevision());
        }
        return ToolResult.partial(facts, List.copyOf(evidence), List.copyOf(missing), ctx.dataRevision(),
                "区间内实测记录不足以给出完整趋势；请按 missingFields 如实说明，不要补值");
    }

    private static LocalDate parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
