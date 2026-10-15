package com.hfj.fitagent.modules.profile.controller;

import com.hfj.fitagent.common.web.ApiResponse;
import com.hfj.fitagent.common.web.PageQuery;
import com.hfj.fitagent.common.web.PageResult;
import com.hfj.fitagent.common.web.SortColumns;
import com.hfj.fitagent.modules.profile.dto.CreateGoalRequest;
import com.hfj.fitagent.modules.profile.dto.GoalTransitionRequest;
import com.hfj.fitagent.modules.profile.dto.UpdateGoalRequest;
import com.hfj.fitagent.modules.profile.service.UserGoalService;
import com.hfj.fitagent.modules.profile.vo.GoalVo;
import com.hfj.fitagent.security.AuthenticatedUsers;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 目标接口（docs/p1-api.md §4.3）。
 *
 * <p>排序白名单在本接口显式声明，只有 {@code createdAt} 与 {@code startsOn} 两个请求侧字段名，
 * 映射到列名后才交给 MyBatis-Plus 的排序条件；不在白名单即 INVALID_SORT_FIELD，绝不拼接进 SQL。
 */
@RestController
@RequestMapping("/api/me/goals")
public class GoalController {

    /** 本接口的排序白名单：请求侧字段名 → 数据库列名。公开是为了让契约可被测试直接引用。 */
    public static final SortColumns SORT = SortColumns.of(
            "createdAt", "created_at",
            "startsOn", "starts_on");

    public static final String DEFAULT_SORT_FIELD = "createdAt";

    private final UserGoalService goalService;

    public GoalController(UserGoalService goalService) {
        this.goalService = goalService;
    }

    /** 历史分页；status 不传即全部状态。 */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<GoalVo>>> list(@RequestParam(required = false) String status,
                                                                @RequestParam(required = false) Integer page,
                                                                @RequestParam(required = false) Integer size,
                                                                @RequestParam(required = false) String sort) {
        PageQuery query = PageQuery.of(page, size, sort, SORT.allowedFields(), DEFAULT_SORT_FIELD);
        PageResult<GoalVo> result = goalService.list(AuthenticatedUsers.requireId(), query, SORT, status);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /** 当前 ACTIVE 目标，无则 {@code data: null}（200，不是 404）。 */
    @GetMapping("/active")
    public ResponseEntity<ApiResponse<GoalVo>> active() {
        return ResponseEntity.ok(ApiResponse.ok(goalService.findActive(AuthenticatedUsers.requireId())));
    }

    /** 新建；{@code activate=true}（默认）时事务内归档旧 ACTIVE 再激活本条。 */
    @PostMapping
    public ResponseEntity<ApiResponse<GoalVo>> create(@Valid @RequestBody CreateGoalRequest request) {
        GoalVo created = goalService.create(AuthenticatedUsers.requireId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(created));
    }

    /** DRAFT/ACTIVE 可就地改，ARCHIVED 拒绝；需 lockVersion。 */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<GoalVo>> update(@PathVariable long id,
                                                      @Valid @RequestBody UpdateGoalRequest request) {
        GoalVo updated = goalService.update(AuthenticatedUsers.requireId(), id, request);
        return ResponseEntity.ok(ApiResponse.ok(updated));
    }

    /** 只删 DRAFT；ACTIVE/ARCHIVED 一律 INVALID_STATE_TRANSITION（历史永久保留）。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable long id) {
        goalService.delete(AuthenticatedUsers.requireId(), id);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<GoalVo>> activate(@PathVariable long id,
                                                        @Valid @RequestBody GoalTransitionRequest request) {
        GoalVo activated = goalService.activate(AuthenticatedUsers.requireId(), id, request.lockVersion());
        return ResponseEntity.ok(ApiResponse.ok(activated));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<GoalVo>> archive(@PathVariable long id,
                                                       @Valid @RequestBody GoalTransitionRequest request) {
        GoalVo archived = goalService.archive(AuthenticatedUsers.requireId(), id, request.lockVersion());
        return ResponseEntity.ok(ApiResponse.ok(archived));
    }
}
