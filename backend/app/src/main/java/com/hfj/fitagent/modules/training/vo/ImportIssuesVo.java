package com.hfj.fitagent.modules.training.vo;

import java.util.List;

/**
 * 422 {@code PLAN_IMPORT_INVALID} 的 data 载荷：{@code {errors[], warnings[]}}
 * （docs/p1-api.md §2 要求"导入校验未通过 → data.errors[].path 定位"）。
 *
 * <p>warnings 与 errors 一起返回：校验有错时同样要让用户看到"顺序被重排"这类事实，
 * 否则修完错误再预览会冒出新提示，来回两轮。
 */
public record ImportIssuesVo(List<ImportIssueVo> errors, List<ImportIssueVo> warnings) {

    public ImportIssuesVo {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
