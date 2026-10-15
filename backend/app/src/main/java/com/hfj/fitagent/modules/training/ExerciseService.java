package com.hfj.fitagent.modules.training;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hfj.fitagent.common.util.Ids;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.web.FieldError;
import com.hfj.fitagent.common.web.PageQuery;
import com.hfj.fitagent.common.web.PageResult;
import com.hfj.fitagent.common.web.SortColumns;
import com.hfj.fitagent.modules.training.entity.ExerciseReferenceEntity;
import com.hfj.fitagent.modules.training.mapper.ExerciseReferenceMapper;
import com.hfj.fitagent.modules.training.vo.ExerciseVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 动作字典读取（docs/p1-api.md §6）。本轮<b>只读</b>：
 * 页面从 {@code status='ACTIVE'} 的动作里选，用户不能新增/修改字典；
 * 导入时遇到未知动作代码也只报错，绝不自动把陌生代码塞进字典（§8 同源原则）。
 *
 * <p>查询全部走 {@code QueryWrapper} 的参数占位：LIKE 的值是绑定参数，
 * 用户输入的 {@code %} 与 {@code _} 先转义，因此既没有 SQL 拼接，
 * 也不会让一个含通配符的关键词意外扩大匹配范围。
 */
@Service
public class ExerciseService {

    /** 排序白名单：请求字段名 → 数据库列名。不在表内的字段直接拒绝，绝不进 SQL。 */
    private static final SortColumns SORT = SortColumns.of("name", "name", "exerciseCode", "exercise_code");

    /**
     * 器械代码词表（docs/p1-plan-import.md §9）。这份常量与 {@code user_profile.equipment}
     * 共用同一套口径，前后端各存一份并保持同步是文档指定的做法。
     *
     * <p>为什么在这里校验：equipment 是 JSON 数组列，筛选要用 {@code JSON_CONTAINS}，
     * 其候选值必须是合法 JSON 文本。不做白名单校验的话，一个带引号的参数会直接把
     * MySQL 的 JSON 解析错误变成 500。
     */
    private static final List<String> EQUIPMENT_CODES = List.of(
            "BARBELL", "DUMBBELL", "KETTLEBELL", "BENCH", "CABLE_MACHINE", "MACHINE", "TREADMILL",
            "BIKE", "ROWER", "ELLIPTICAL", "STAIR_CLIMBER", "JUMP_ROPE", "MAT", "BODYWEIGHT",
            "OUTDOOR", "BAND");

    private final ExerciseReferenceMapper mapper;

    public ExerciseService(ExerciseReferenceMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param category  STRENGTH / CARDIO / MOBILITY，严格大写字面量（docs/p1-database.md §5 补充规则 1）
     * @param equipment 器械代码，必须命中词表
     * @param keyword   对 name 与 exercise_code 做包含匹配（同时覆盖前缀场景）
     */
    @Transactional(readOnly = true)
    public PageResult<ExerciseVo> list(String category, String equipment, String keyword,
                                       Integer page, Integer size, String sort) {
        PageQuery query = PageQuery.of(page, size, defaultSort(sort), SORT.allowedFields(), "name");

        QueryWrapper<ExerciseReferenceEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("status", ExerciseDictionary.STATUS_ACTIVE);
        if (!Ids.isBlank(category)) {
            wrapper.eq("category", requireCategory(category));
        }
        if (!Ids.isBlank(equipment)) {
            wrapper.apply("JSON_CONTAINS(equipment, {0})", jsonCandidate(requireEquipment(equipment)));
        }
        if (!Ids.isBlank(keyword)) {
            String pattern = escapeLike(keyword.trim());
            wrapper.and(nested -> nested.like("name", pattern).or().like("exercise_code", pattern));
        }
        wrapper.orderBy(true, query.direction() == PageQuery.Direction.ASC, query.column(SORT));
        // 等值列（name 理论上唯一，但排序稳定性不该依赖它）：追加 id 兜底，避免翻页时行序抖动
        wrapper.orderBy(true, query.direction() == PageQuery.Direction.ASC, "id");

        Page<ExerciseReferenceEntity> result =
                mapper.selectPage(new Page<>(query.page(), query.size()), wrapper);
        List<ExerciseVo> items = new ArrayList<>(result.getRecords().size());
        for (ExerciseReferenceEntity row : result.getRecords()) {
            items.add(toVo(row));
        }
        return PageResult.from(result, items);
    }

    private static String defaultSort(String sort) {
        // §6 的默认是 name,asc，而 PageQuery 未给 sort 时按 DESC 处理，所以这里显式补默认值
        return Ids.isBlank(sort) ? "name,asc" : sort;
    }

    private static String requireCategory(String raw) {
        String value = raw.trim();
        if (ExerciseCategory.parseStrict(value) == null) {
            throw invalid("category", "动作类别只支持 STRENGTH / CARDIO / MOBILITY（大写）", raw);
        }
        return value;
    }

    private static String requireEquipment(String raw) {
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!EQUIPMENT_CODES.contains(value)) {
            throw invalid("equipment", "器械代码不在词表内，请参考动作字典页面的器械取值", raw);
        }
        return value;
    }

    private static BizException invalid(String field, String message, Object rejected) {
        // INVALID_ENUM_VALUE：契约层对枚举一律严格（docs/p1-database.md §5 补充规则 1）
        return new BizException(ErrorCode.VALIDATION_FAILED, message,
                List.of(FieldError.of(field, PlanImportErrorCode.INVALID_ENUM_VALUE, message, rejected)));
    }

    /** JSON_CONTAINS 的候选值必须是 JSON 文本，字符串要多一层引号。值本身仍走绑定参数。 */
    private static String jsonCandidate(String code) {
        return "\"" + code + "\"";
    }

    /**
     * LIKE 通配符转义：用户写的 {@code %} / {@code _} 是字面量而不是通配符，
     * 否则搜 "100%" 会命中所有以 100 结尾的动作名。MySQL 默认转义符是反斜杠。
     */
    private static String escapeLike(String keyword) {
        StringBuilder sb = new StringBuilder(keyword.length() + 8);
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            if (c == '_' || c == '%' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static ExerciseVo toVo(ExerciseReferenceEntity row) {
        return new ExerciseVo(
                row.getId() == null ? null : Long.toString(row.getId()),
                row.getExerciseCode(),
                row.getName(),
                row.getCategory(),
                row.getMuscleGroups() == null ? List.of() : List.copyOf(row.getMuscleGroups()),
                row.getEquipment() == null ? List.of() : List.copyOf(row.getEquipment()),
                row.getSourceUrl(),
                row.getInstructions());
    }
}
