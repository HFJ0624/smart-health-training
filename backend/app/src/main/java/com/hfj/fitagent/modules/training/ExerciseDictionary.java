package com.hfj.fitagent.modules.training;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hfj.fitagent.common.util.Ids;
import com.hfj.fitagent.modules.training.entity.ExerciseReferenceEntity;
import com.hfj.fitagent.modules.training.mapper.ExerciseReferenceMapper;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 动作字典的批量读取入口。
 *
 * <p>存在的理由有两个：
 * <ol>
 *   <li>导入/编辑一次请求可能带 120 个动作代码，必须 <b>一次 IN 查询</b>取回，不能逐个查（N+1）；</li>
 *   <li>{@code muscle_groups}/{@code equipment} 是 JSON 列，依赖实体 {@code autoResultMap} 上的
 *       Jackson3TypeHandler，所以只能走 BaseMapper + QueryWrapper，不能自定义 @Select
 *       （见 {@link ExerciseReferenceMapper} 的类注释）。</li>
 * </ol>
 *
 * <p>状态过滤刻意留白：按代码取回时连 DISABLED 一起返回，好让校验器区分
 * {@code EXERCISE_NOT_FOUND} 与 {@code EXERCISE_DISABLED}——两者对用户的下一步动作不一样。
 * 查询一律用参数占位（MP 把集合展开成 {@code IN (#{...}, ...)}），没有任何 SQL 字符串拼接。
 */
@Component
public class ExerciseDictionary {

    static final String STATUS_ACTIVE = "ACTIVE";

    private final ExerciseReferenceMapper mapper;

    public ExerciseDictionary(ExerciseReferenceMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 一次批量查询，按规范化后的大写代码返回。
     *
     * @param codes 原始代码（大小写混用无所谓，内部用 {@link Ids#canonicalExerciseCode} 规范化）
     * @return key = 规范化代码；查不到的代码不会出现在 map 里（调用方据此报 EXERCISE_NOT_FOUND）
     */
    public Map<String, ExerciseReferenceEntity> loadByCodes(Collection<String> codes) {
        Set<String> canonical = codes.stream()
                .map(Ids::canonicalExerciseCode)
                .filter(code -> !code.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (canonical.isEmpty()) {
            return Map.of();
        }
        List<ExerciseReferenceEntity> rows = mapper.selectList(
                new QueryWrapper<ExerciseReferenceEntity>().in("exercise_code", canonical));
        Map<String, ExerciseReferenceEntity> byCode = new LinkedHashMap<>();
        for (ExerciseReferenceEntity row : rows) {
            byCode.put(Ids.canonicalExerciseCode(row.getExerciseCode()), row);
        }
        return byCode;
    }

    /** 一次批量查询，按字典主键返回，用于把计划条目渲染成带代码/名称/类别的 VO。 */
    public Map<Long, ExerciseReferenceEntity> loadByIds(Collection<Long> ids) {
        Set<Long> distinct = ids.stream().filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (distinct.isEmpty()) {
            return Map.of();
        }
        List<ExerciseReferenceEntity> rows = mapper.selectList(
                new QueryWrapper<ExerciseReferenceEntity>().in("id", distinct));
        Map<Long, ExerciseReferenceEntity> byId = new LinkedHashMap<>();
        for (ExerciseReferenceEntity row : rows) {
            byId.put(row.getId(), row);
        }
        return byId;
    }
}
