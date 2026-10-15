package com.hfj.fitagent.modules.training;

import com.hfj.fitagent.common.web.ApiResponse;
import com.hfj.fitagent.common.web.PageResult;
import com.hfj.fitagent.modules.training.dto.ActivateRequest;
import com.hfj.fitagent.modules.training.dto.ArchiveRequest;
import com.hfj.fitagent.modules.training.dto.CopyRequest;
import com.hfj.fitagent.modules.training.dto.DiscardRequest;
import com.hfj.fitagent.modules.training.dto.PlanUpsertRequest;
import com.hfj.fitagent.modules.training.dto.ReviseRequest;
import com.hfj.fitagent.modules.training.vo.PlanDetailVo;
import com.hfj.fitagent.modules.training.vo.PlanListVo;
import com.hfj.fitagent.modules.training.vo.TodayPlanVo;
import com.hfj.fitagent.security.AuthenticatedUsers;
import com.hfj.fitagent.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 训练计划接口（docs/p1-api.md §7）。控制器只做三件事：
 * 从认证上下文取身份、把参数交给 Service、包上统一响应——<b>不写业务规则</b>
 * （docs/p1-architecture.md §4 第 1 条）。
 *
 * <p>身份一律 {@code AuthenticatedUsers.require()}：请求里没有任何可以指定 userId 的入口，
 * "前端传 userId 决定身份"在结构上不可能发生。
 *
 * <p>路径顺序有讲究：{@code /api/plans/import} 由 PlanImportController 提供，
 * 与这里的 {@code /api/plans/{id}} 不冲突（前者只注册 POST，后者只有 GET）。
 */
@RestController
public class TrainingPlanController {

    private final TrainingPlanService service;

    public TrainingPlanController(TrainingPlanService service) {
        this.service = service;
    }

    /**
     * 版本列表。{@code status} 逗号分隔多选（默认全部状态，含 ARCHIVED/DISCARDED），
     * {@code groupId} 用来只看同一计划的各版本，排序默认 {@code updatedAt,desc}。
     */
    @GetMapping("/api/plans")
    public ApiResponse<PageResult<PlanListVo>> list(@RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String groupId,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size,
                                                   @RequestParam(required = false) String sort) {
        return ApiResponse.ok(service.list(AuthenticatedUsers.requireId(), status, groupId, page, size, sort));
    }

    /** 当前生效计划（完整聚合）；无则 {@code data: null}，首页显示"未设置计划"。 */
    @GetMapping("/api/plans/active")
    public ApiResponse<PlanDetailVo> active() {
        return ApiResponse.ok(service.findActive(AuthenticatedUsers.requireId()));
    }

    /**
     * 今天的计划：业务日期与星期由服务端按<b>该用户的时区</b>推导，
     * 客户端不传日期，否则改机时就能伪造"今天"。
     */
    @GetMapping("/api/plans/today")
    public ApiResponse<TodayPlanVo> today() {
        CurrentUser user = AuthenticatedUsers.require();
        return ApiResponse.ok(service.findToday(user.id(), user.zone()));
    }

    /** 单版本详情；(id, user_id) 复合校验，越权与不存在同形（404）。 */
    @GetMapping("/api/plans/{id}")
    public ApiResponse<PlanDetailVo> detail(@PathVariable("id") long id) {
        return ApiResponse.ok(service.findById(AuthenticatedUsers.requireId(), id));
    }

    /** 新建草稿：source=MANUAL，组与版本号与目标快照都由服务端生成。 */
    @PostMapping("/api/plans")
    public ResponseEntity<ApiResponse<PlanDetailVo>> create(@Valid @RequestBody PlanUpsertRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(service.create(AuthenticatedUsers.requireId(), req)));
    }

    /** 就地编辑草稿（聚合替换）。子行"删旧插新"在同一事务内完成。 */
    @PutMapping("/api/plans/{id}")
    public ApiResponse<PlanDetailVo> update(@PathVariable("id") long id,
                                           @Valid @RequestBody PlanUpsertRequest req) {
        return ApiResponse.ok(service.update(AuthenticatedUsers.requireId(), id, req));
    }

    /**
     * 基于 ACTIVE/ARCHIVED 另存同组新草稿。
     *
     * <p>请求体可以为空或 {@code {}}：版本坐标（组、revisionNo、basePlanId、目标快照）一律服务端生成，
     * 因此这个 DTO 没有任何字段，出现多余键即被全局 {@code FAIL_ON_UNKNOWN_PROPERTIES} 拒绝。
     */
    @PostMapping("/api/plans/{id}/revise")
    public ResponseEntity<ApiResponse<PlanDetailVo>> revise(@PathVariable("id") long id,
        @RequestBody(required = false) ReviseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(service.revise(AuthenticatedUsers.requireId(), id)));
    }

    /** 复制成新计划组的草稿；任意状态可用。请求体同样不接受字段。 */
    @PostMapping("/api/plans/{id}/copy")
    public ResponseEntity<ApiResponse<PlanDetailVo>> copy(@PathVariable("id") long id,
        @RequestBody(required = false) CopyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(service.copy(AuthenticatedUsers.requireId(), id)));
    }

    /** 确认生效：归档旧 ACTIVE 后激活本草稿；并发只胜出一路。 */
    @PostMapping("/api/plans/{id}/activate")
    public ApiResponse<PlanDetailVo> activate(@PathVariable("id") long id,
                                             @Valid @RequestBody ActivateRequest req) {
        return ApiResponse.ok(service.activate(AuthenticatedUsers.requireId(), id, req.lockVersion()));
    }

    /** 归档当前生效计划。 */
    @PostMapping("/api/plans/{id}/archive")
    public ApiResponse<PlanDetailVo> archive(@PathVariable("id") long id,
                                             @Valid @RequestBody ArchiveRequest req) {
        return ApiResponse.ok(service.archive(AuthenticatedUsers.requireId(), id, req.lockVersion()));
    }

    /** 丢弃草稿：置 DISCARDED，行保留。 */
    @PostMapping("/api/plans/{id}/discard")
    public ApiResponse<PlanDetailVo> discard(@PathVariable("id") long id,
                                             @Valid @RequestBody DiscardRequest req) {
        return ApiResponse.ok(service.discard(AuthenticatedUsers.requireId(), id, req.lockVersion()));
    }
}
