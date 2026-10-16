package com.hfj.fitagent.modules.workout.controller;

import com.hfj.fitagent.common.web.ApiResponse;
import com.hfj.fitagent.common.web.PageResult;
import com.hfj.fitagent.modules.workout.WorkoutService;
import com.hfj.fitagent.modules.workout.dto.WorkoutRequests;
import com.hfj.fitagent.modules.workout.vo.WorkoutVos;
import com.hfj.fitagent.security.AuthenticatedUsers;
import com.hfj.fitagent.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;

/**
 * 训练执行接口（阶段 2）。
 *
 * <p>身份与时区一律从认证上下文取（{@code AuthenticatedUsers}），请求里**没有** userId 参数；
 * 每个按 id 访问的路径都单独校验归属，不给"列表加了 user_id 条件就以为详情也安全"的机会。
 */
@RestController
@RequestMapping("/api/workout/sessions")
public class WorkoutController {

    private final WorkoutService workoutService;

    public WorkoutController(WorkoutService workoutService) {
        this.workoutService = workoutService;
    }

    /** 开始或恢复：201 只在真的新建了一条草稿时给，恢复已有草稿是 200。 */
    @PostMapping
    public ResponseEntity<ApiResponse<WorkoutVos.StartResultVo>> start(@Valid @RequestBody WorkoutRequests.Start req) {
        CurrentUser user = AuthenticatedUsers.require();
        WorkoutVos.StartResultVo result = workoutService.start(user.id(), user.zone(), req);
        HttpStatus status = Boolean.TRUE.equals(result.resumedExisting()) ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ApiResponse.ok(result));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<WorkoutVos.SessionSummaryVo>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentUser user = AuthenticatedUsers.require();
        return ResponseEntity.ok(ApiResponse.ok(
                workoutService.list(user.id(), status, from, to, page, size)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<WorkoutVos.SessionDetailVo>> detail(@PathVariable Long id) {
        CurrentUser user = AuthenticatedUsers.require();
        return ResponseEntity.ok(ApiResponse.ok(workoutService.detail(user.id(), id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<WorkoutVos.SessionDetailVo>> save(@PathVariable Long id,
                                                                       @Valid @RequestBody WorkoutRequests.Save req) {
        CurrentUser user = AuthenticatedUsers.require();
        return ResponseEntity.ok(ApiResponse.ok(workoutService.save(user.id(), id, req)));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<ApiResponse<WorkoutVos.SessionDetailVo>> complete(@PathVariable Long id,
                                                                           @Valid @RequestBody WorkoutRequests.Complete req) {
        CurrentUser user = AuthenticatedUsers.require();
        return ResponseEntity.ok(ApiResponse.ok(workoutService.complete(user.id(), id, req)));
    }

    @PostMapping("/{id}/discard")
    public ResponseEntity<ApiResponse<WorkoutVos.SessionDetailVo>> discard(@PathVariable Long id,
                                                                          @Valid @RequestBody WorkoutRequests.VersionOnly req) {
        CurrentUser user = AuthenticatedUsers.require();
        return ResponseEntity.ok(ApiResponse.ok(workoutService.discard(user.id(), id, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        CurrentUser user = AuthenticatedUsers.require();
        workoutService.delete(user.id(), id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
