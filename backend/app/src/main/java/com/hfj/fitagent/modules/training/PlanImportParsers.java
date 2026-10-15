package com.hfj.fitagent.modules.training;

import com.hfj.fitagent.modules.training.dto.PlanDraftSpec;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导入文档的解析契约（docs/p1-plan-import.md §8 校验顺序的第 2–5 阶）：
 * JSON 可解析 → {@code schema_version} → 未知/禁用字段 → 类型与必填。
 *
 * <p>刻意只服务两个导入端点（{@code POST /api/plans/import/preview} 与
 * {@code POST /api/plans/import}）：它们接收<b>原始文本</b>，键名是 snake_case，
 * 数值必须逐类型判定（字符串数字、布尔当数字、0/1 当布尔都要拒绝），
 * 而且每条错误必须带 JSON 路径供页面逐行高亮。
 * 手工编辑接口的入参已由 Spring 绑定成 DTO，绑定层不同，
 * 但第 6–11 阶的规则（长度、范围、结构、字典、类型×字段矩阵、跨字段）
 * 由 {@link PlanAggregateValidator} 一处实现，两个入口共用。
 *
 * <p>解析器不做字典查询，也不做业务校验：它的错误列表非空时调用方直接短路返回。
 */
public interface PlanImportParsers {

    /** 导入文档的模板版本；不精确等于该值即 {@code UNSUPPORTED_SCHEMA_VERSION}。 */
    String SCHEMA_VERSION = "fitagent.training_plan/1";

    /**
     * @param spec   解析出的草案；{@code errors} 为空时才可信
     * @param errors 第 2–5 阶的字段错误；非空即短路，不进入后续校验
     */
    record Result(PlanDraftSpec spec, List<PlanIssue> errors) {

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    /**
     * 解析原始 JSON 文本。
     *
     * @throws com.hfj.fitagent.common.error.BizException 文本不是合法 JSON 对象时抛
     *         {@code MALFORMED_JSON}——它与"JSON 合法但内容不合规"是两类失败，前端提示方式不同
     */
    Result parse(String content);

    /** 输入里允许出现的键名（snake_case），逐层一份；未列出的键一律拒绝。 */
    Set<String> ROOT_KEYS = Set.of("schema_version", "title", "starts_on", "ends_on", "notes", "days");
    Set<String> DAY_KEYS = Set.of("iso_weekday", "title", "is_rest_day", "notes", "items");
    Set<String> ITEM_KEYS = Set.of("exercise_code", "position_no", "target_sets", "reps_min", "reps_max",
            "target_external_load_kg", "target_duration_seconds", "rest_seconds", "notes");

    /**
     * 服务端字段黑名单（docs/p1-plan-import.md §7 逐条照抄）：这些键出现在<b>任何层级</b>都判
     * {@code FORBIDDEN_SERVER_FIELD}，与拼错的未知键（{@code UNKNOWN_FIELD}）分开，
     * 因为文案要告诉用户"该字段由服务端生成"。
     *
     * <p>camelCase 与 snake_case 两种写法都收进表内：黑名单的目的是挡住注入，
     * 少收一个写法就等于留一个洞。
     */
    Set<String> SERVER_FIELD_BLACKLIST = Set.of(
            "id", "userId", "user_id", "owner", "ownerUserId", "planId", "plan_id",
            "planGroupUuid", "plan_group_uuid", "revisionNo", "revision_no",
            "basePlanId", "base_plan_id", "source", "status", "activeUserId", "active_user_id",
            "lockVersion", "lock_version", "goalSnapshot", "goal_snapshot",
            "confirmedAt", "confirmed_at", "createdAt", "created_at", "updatedAt", "updated_at",
            "exerciseId", "exercise_id", "planDayId", "plan_day_id", "businessDate", "dataRevision");

    /** 数值/日期字段的可读名，出现在 INVALID_TYPE 与 INVALID_DATE_FORMAT 文案里，指明该改哪一项。 */
    Map<String, String> FIELD_LABELS = Map.ofEntries(
            Map.entry("title", "计划名称"),
            Map.entry("starts_on", "开始日期"),
            Map.entry("ends_on", "结束日期"),
            Map.entry("notes", "备注"),
            Map.entry("days", "计划日列表"),
            Map.entry("iso_weekday", "星期"),
            Map.entry("is_rest_day", "是否休息日"),
            Map.entry("items", "动作列表"),
            Map.entry("exercise_code", "动作代码"),
            Map.entry("position_no", "动作顺序"),
            Map.entry("target_sets", "组数"),
            Map.entry("reps_min", "次数下界"),
            Map.entry("reps_max", "次数上界"),
            Map.entry("target_external_load_kg", "外部负重（kg）"),
            Map.entry("target_duration_seconds", "时长（秒）"),
            Map.entry("rest_seconds", "组间休息（秒）"));
}
