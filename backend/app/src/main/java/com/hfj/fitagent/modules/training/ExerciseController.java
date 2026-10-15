package com.hfj.fitagent.modules.training;

import com.hfj.fitagent.common.web.ApiResponse;
import com.hfj.fitagent.common.web.PageResult;
import com.hfj.fitagent.modules.training.vo.ExerciseVo;
import com.hfj.fitagent.security.AuthenticatedUsers;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 动作字典接口（docs/p1-api.md §6）。
 *
 * <p>只读：本轮没有任何写入口，字典维护（{@code role=ADMIN}）属后续阶段——
 * 所以这里也不摆一个"敬请期待"的按钮或空方法（docs/p1-scope.md §2）。
 *
 * <p>需要认证，但所有人读到的是同一份系统字典，因此没有归属条件。
 */
@RestController
public class ExerciseController {

    private final ExerciseService service;

    public ExerciseController(ExerciseService service) {
        this.service = service;
    }

    /**
     * {@code GET /api/exercises?category=&equipment=&keyword=&page=&size=&sort=name,asc}
     *
     * <p>只返回 {@code status='ACTIVE'} 的动作；排序白名单 {@code name} / {@code exerciseCode}，
     * 默认 {@code name,asc}；{@code size} 上限 100，超出即 VALIDATION_FAILED 而不是静默截断。
     */
    @GetMapping("/api/exercises")
    public ApiResponse<PageResult<ExerciseVo>> list(@RequestParam(required = false) String category,
                                                   @RequestParam(required = false) String equipment,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size,
                                                   @RequestParam(required = false) String sort) {
        // 身份只用于鉴权（未登录到不了这里）；字典本身与用户无关
        AuthenticatedUsers.requireId();
        return ApiResponse.ok(service.list(category, equipment, keyword, page, size, sort));
    }
}
