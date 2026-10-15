package com.hfj.fitagent.modules.training;

import com.hfj.fitagent.common.IdempotencyService;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.training.dto.PlanDraftSpec;
import com.hfj.fitagent.modules.training.vo.ImportConfirmVo;
import com.hfj.fitagent.modules.training.vo.ImportIssueVo;
import com.hfj.fitagent.modules.training.vo.ImportIssuesVo;
import com.hfj.fitagent.modules.training.vo.ImportPreviewVo;
import com.hfj.fitagent.modules.training.vo.PlanListVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JSON 导入：preview（无副作用）→ confirm（幂等落库为 DRAFT）。
 * 契约见 docs/p1-plan-import.md §8–§10 与 docs/p1-api.md §8。
 *
 * <p>四个要点：
 * <ol>
 *   <li><b>preview 完全没有副作用</b>：不落库、不占用幂等键，可以反复调用；</li>
 *   <li><b>confirm 从原始文本重新走一遍完整校验</b>，不信任 preview 的结果——
 *       两次调用之间字典可能被维护、幂等键可能被别的请求用掉；</li>
 *   <li><b>先占幂等键再执行</b>（{@link IdempotencyService#claimOrReplay}），
 *       成功后 {@code complete}，任何异常都 {@code release}，
 *       这样"失败"不会被固化成 24 小时的重放结果，客户端可以用同键重试；</li>
 *   <li><b>导入永远只产出 DRAFT、source=IMPORT</b>，且绝不把未知动作代码写进字典。</li>
 * </ol>
 *
 * <p>日志只记字节数与错误数，不记 JSON 原文（docs/p1-architecture.md §6：
 * 请求体原文不进日志，避免用户计划内容被复制到日志系统里）。
 */
@Service
public class PlanImportService {

    /** 请求体 UTF-8 字节上限：256 KiB（docs/p1-plan-import.md §8；普通 JSON 接口的 64 KiB 不适用）。 */
    public static final int MAX_IMPORT_BYTES = 256 * 1024;

    private static final Logger log = LoggerFactory.getLogger(PlanImportService.class);

    private final PlanImportParsers parsers;
    private final PlanAggregateValidator aggregateValidator;
    private final TrainingPlanService planService;
    private final IdempotencyService idempotency;

    public PlanImportService(PlanImportParsers parsers,
                            PlanAggregateValidator aggregateValidator,
                            TrainingPlanService planService,
                            IdempotencyService idempotency) {
        this.parsers = parsers;
        this.aggregateValidator = aggregateValidator;
        this.planService = planService;
        this.idempotency = idempotency;
    }

    /**
     * 校验 + 规范化预览。
     *
     * @throws BizException MALFORMED_JSON / PAYLOAD_TOO_LARGE / UNSUPPORTED_SCHEMA_VERSION /
     *                      UNKNOWN_FIELD / FORBIDDEN_SERVER_FIELD / PLAN_IMPORT_INVALID
     */
    public ImportPreviewVo preview(long userId, String content) {
        Validated validated = validate(content);
        log.debug("导入预览 userId={} bytes={} warnings={}", userId, byteLength(content),
                validated.outcome().warnings().size());
        return toPreview(validated.spec(), validated.outcome());
    }

    /**
     * 确认保存。
     *
     * <p>顺序：规范化幂等键 → <b>先占位</b>（占不到就说明同键请求正在跑，直接 DUPLICATE_REQUEST）→
     * 命中重放则原样返回首次结果 → 否则完整重校验并写聚合 → {@code complete} 记结果。
     *
     * <p>重放分支不重新校验：那条路径的语义是"回到第一次成功的时刻"，
     * 若此刻字典变了就把已经建好的计划判成非法，用户会看到一个无法解释的失败。
     *
     * @param rawKey 请求头 {@code Idempotency-Key} 原文；为空则服务端生成并在响应里回传
     */
    public ImportConfirmVo confirm(long userId, String content, String rawKey) {
        String key = idempotency.normalizeKey(rawKey);
        Optional<String> replay = idempotency.claimOrReplay(userId, key);
        if (replay.isPresent()) {
            return replayResult(userId, key, replay.get());
        }
        try {
            Validated validated = validate(content);
            PlanDraftSpec spec = validated.spec();
            long planId = planService.saveImported(userId, validated.outcome(),
                    spec.title().trim(), spec.startsOn(), spec.endsOn());
            idempotency.complete(userId, key, Long.toString(planId));
            log.debug("导入确认 userId={} bytes={} planId={}", userId, byteLength(content), planId);
            return new ImportConfirmVo(Long.toString(planId), 1, PlanStatus.DRAFT.code(), key, false);
        } catch (RuntimeException e) {
            // 释放占位，允许同键重试；BizException 与意外异常都走这一条
            idempotency.release(userId, key);
            throw e;
        }
    }

    // ==================== 校验流水线 ====================

    /** 已完整校验的输入：草案（头字段）+ 规范化聚合（子行与字典解析结果）。 */
    private record Validated(PlanDraftSpec spec, PlanValidationOutcome outcome) {
    }

    private Validated validate(String content) {
        requireWithinCap(content);
        PlanImportParsers.Result parsed = parsers.parse(content);
        if (parsed.hasErrors()) {
            throw failure(parsed.errors(), List.of());
        }
        PlanValidationOutcome outcome = aggregateValidator.validate(parsed.spec());
        if (outcome.hasErrors()) {
            throw failure(outcome.errors(), outcome.warnings());
        }
        return new Validated(parsed.spec(), outcome);
    }

    private static void requireWithinCap(String content) {
        if (content == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "content 不能为空");
        }
        int bytes = byteLength(content);
        if (bytes > MAX_IMPORT_BYTES) {
            throw new BizException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "导入内容 " + bytes + " 字节，超过 " + MAX_IMPORT_BYTES + " 字节上限");
        }
    }

    /** 按 UTF-8 字节计数，不是字符数：中文备注里一个字符占 3 字节。 */
    private static int byteLength(String content) {
        return content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 把字段级发现映射成对外信封码（docs/p1-api.md §2）：
     * 版本与字段来源问题各用自己的码（前端提示方式不同："下载新模板" / "该字段由服务端生成"），
     * 其余一律 {@code PLAN_IMPORT_INVALID} 并把 {@code errors[].path} 交给页面逐行高亮。
     *
     * <p>按 §8 的校验顺序取优先级，因此一次请求只报一个信封码：结构层已经短路，
     * 不会出现"版本不支持 + 12 处字段错误"这种混杂结果。
     */
    static BizException failure(List<PlanIssue> errors, List<PlanIssue> warnings) {
        ImportIssuesVo payload = new ImportIssuesVo(toVos(errors), toVos(warnings));
        ErrorCode envelope = envelopeOf(errors);
        if (errors.size() == 1 && !envelope.code().equals(ErrorCode.PLAN_IMPORT_INVALID.code())) {
            // 单因短路：直接用那条发现的人类可读文案，比"校验未通过"更具体
            return new BizException(envelope, errors.get(0).message(), payload);
        }
        return new BizException(envelope,
                envelope.defaultMessage() + "，共 " + errors.size() + " 处错误", payload);
    }

    private static ErrorCode envelopeOf(List<PlanIssue> errors) {
        for (PlanIssue issue : errors) {
            if (PlanImportErrorCode.UNSUPPORTED_SCHEMA_VERSION.equals(issue.code())) {
                return ErrorCode.UNSUPPORTED_SCHEMA_VERSION;
            }
        }
        for (PlanIssue issue : errors) {
            if (PlanImportErrorCode.FORBIDDEN_SERVER_FIELD.equals(issue.code())) {
                return ErrorCode.FORBIDDEN_SERVER_FIELD;
            }
        }
        for (PlanIssue issue : errors) {
            if (PlanImportErrorCode.UNKNOWN_FIELD.equals(issue.code())) {
                return ErrorCode.UNKNOWN_FIELD;
            }
        }
        return ErrorCode.PLAN_IMPORT_INVALID;
    }

    private static List<ImportIssueVo> toVos(List<PlanIssue> issues) {
        List<ImportIssueVo> vos = new ArrayList<>(issues.size());
        for (PlanIssue issue : issues) {
            vos.add(new ImportIssueVo(issue.path(), issue.code(), issue.message(), issue.value()));
        }
        return vos;
    }

    // ==================== 幂等重放 ====================

    private ImportConfirmVo replayResult(long userId, String key, String storedPlanId) {
        long planId;
        try {
            planId = Long.parseLong(storedPlanId);
        } catch (NumberFormatException e) {
            // 占位值被写坏（不该发生）：当成首次结果返回，但版本与状态留空而不是编造一个
            return new ImportConfirmVo(storedPlanId, null, null, key, true);
        }
        PlanListVo header = planService.findHeader(userId, planId);
        return new ImportConfirmVo(storedPlanId, header.revisionNo(), header.status(), key, true);
    }

    // ==================== 预览装配 ====================

    /**
     * 预览展示的是<b>服务端规范化后</b>的数据：position_no 已重排、动作已解析出名称与类别。
     *
     * <p>顶层 notes 在 P1 没有对应列，已由校验器直接判 {@code PLAN_NOTES_UNSUPPORTED} 拦下，
     * 不会出现"预览里有、保存后没了"。
     */
    private static ImportPreviewVo toPreview(PlanDraftSpec spec, PlanValidationOutcome outcome) {
        List<ImportPreviewVo.Day> days = new ArrayList<>();
        for (PlanValidationOutcome.ResolvedDay day : outcome.days()) {
            List<ImportPreviewVo.Item> items = new ArrayList<>(day.items().size());
            for (PlanValidationOutcome.ResolvedItem item : day.items()) {
                items.add(new ImportPreviewVo.Item(item.positionNo(),
                        item.exercise().getExerciseCode(), item.exercise().getName(),
                        item.exercise().getCategory(), item.targetSets(), item.repsMin(), item.repsMax(),
                        item.targetExternalLoadKg(), item.targetDurationSeconds(), item.restSeconds(),
                        item.notes()));
            }
            days.add(new ImportPreviewVo.Day(day.isoWeekday(),
                    PlanAggregateValidator.weekdayLabel(day.isoWeekday()), day.title(), day.isRestDay(),
                    day.notes(), items));
        }
        List<ImportIssueVo> warnings = toVos(outcome.warnings());
        return new ImportPreviewVo(spec.title(), spec.startsOn(), spec.endsOn(), spec.notes(), days,
                warnings, warnings.size());
    }
}
