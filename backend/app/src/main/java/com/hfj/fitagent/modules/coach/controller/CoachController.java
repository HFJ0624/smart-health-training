package com.hfj.fitagent.modules.coach.controller;

import com.hfj.fitagent.common.web.ApiResponse;
import com.hfj.fitagent.common.web.PageQuery;
import com.hfj.fitagent.common.web.PageResult;
import com.hfj.fitagent.modules.coach.dto.QuestionRequest;
import com.hfj.fitagent.modules.coach.service.CoachApplicationService;
import com.hfj.fitagent.modules.coach.vo.CoachMessageVo;
import com.hfj.fitagent.modules.coach.vo.ConversationVo;
import com.hfj.fitagent.modules.coach.vo.EstimateVo;
import com.hfj.fitagent.modules.coach.vo.ItemsVo;
import com.hfj.fitagent.modules.coach.vo.RunStateVo;
import com.hfj.fitagent.modules.coach.vo.RunStepVo;
import com.hfj.fitagent.modules.coach.vo.RunSubmitVo;
import com.hfj.fitagent.security.AuthenticatedUsers;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 教练接口（docs/agent-v1-design.md §11、§11.1 载荷契约）。
 *
 * <p>控制器只做三件事：取认证上下文、转分页参数、把 202 与 200 分清楚。
 * 所有可信输入（userId、时区、幂等键关联的用户）都在这里取定后传给 Service，
 * Service 签名里不出现"客户端说的用户"。
 *
 * <p>{@code Idempotency-Key} 只有 {@code POST .../runs} 需要：同键同内容返回同一 run，
 * 同键不同内容返回冲突（§10）。
 */
@RestController
@RequestMapping("/api/coach")
public class CoachController {

    private final CoachApplicationService coach;

    public CoachController(CoachApplicationService coach) {
        this.coach = coach;
    }

    /** 新建会话；标题由服务端从首个问题截取，请求体是空对象。 */
    @PostMapping("/conversations")
    public ResponseEntity<ApiResponse<ConversationVo>> createConversation() {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(coach.createConversation(AuthenticatedUsers.requireId())));
    }

    /** 会话列表：排序口径固定为"最近活跃在前"（§11 没有开放排序字段，就不接受 sort 参数）。 */
    @GetMapping("/conversations")
    public ApiResponse<PageResult<ConversationVo>> conversations(@RequestParam(required = false) Integer page,
                                                                @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size, null, java.util.List.of("lastMessageAt"), "lastMessageAt");
        return ApiResponse.ok(coach.listConversations(AuthenticatedUsers.requireId(), query));
    }

    @GetMapping("/conversations/{id}")
    public ApiResponse<ConversationVo> conversation(@PathVariable("id") long id) {
        return ApiResponse.ok(coach.conversation(AuthenticatedUsers.requireId(), id));
    }

    @PostMapping("/conversations/{id}/archive")
    public ApiResponse<ConversationVo> archive(@PathVariable("id") long id) {
        return ApiResponse.ok(coach.archive(AuthenticatedUsers.requireId(), id));
    }

    /** 历史消息：{@code {items:[…]}} 壳，不返回裸数组（§11.1 第 4 条）。 */
    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<ItemsVo<CoachMessageVo>> messages(@PathVariable("id") long id,
                                                         @RequestParam(required = false) Integer after,
                                                         @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(coach.messages(AuthenticatedUsers.requireId(), id, after, limit));
    }

    /** 预估本次提问：不发起任何收费请求（§11）。 */
    @PostMapping("/conversations/{id}/estimate")
    public ApiResponse<EstimateVo> estimate(@PathVariable("id") long id,
                                            @Valid @RequestBody QuestionRequest request) {
        return ApiResponse.ok(coach.estimate(AuthenticatedUsers.requireId(), id,
                request.question(), AuthenticatedUsers.requireZone()));
    }

    /** 提交问题：202 + runId，前端按 runId 轮询，不让一个 HTTP 请求等整段循环。 */
    @PostMapping("/conversations/{id}/runs")
    public ResponseEntity<ApiResponse<RunSubmitVo>> submit(@PathVariable("id") long id,
                                                           @Valid @RequestBody QuestionRequest request,
                                                           @RequestHeader("Idempotency-Key") String idempotencyKey) {
        RunSubmitVo vo = coach.submit(AuthenticatedUsers.requireId(), id, request.question(),
                idempotencyKey, AuthenticatedUsers.requireZone());
        // HTTP 202 才是"已受理、尚未执行"的语义；信封 code 仍是 OK，前端只按 code 分支（p1-api.md §1）
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(vo));
    }

    @GetMapping("/runs/{id}")
    public ApiResponse<RunStateVo> run(@PathVariable("id") long id) {
        return ApiResponse.ok(coach.runState(AuthenticatedUsers.requireId(), id));
    }

    /** 脱敏操作摘要：不含模型原始推理文本、请求体或任何密钥材料（§11.1 第 6 条）。 */
    @GetMapping("/runs/{id}/steps")
    public ApiResponse<ItemsVo<RunStepVo>> steps(@PathVariable("id") long id) {
        return ApiResponse.ok(coach.runSteps(AuthenticatedUsers.requireId(), id));
    }

    /** 幂等取消：只有这个调用才是取消，停止轮询不是（§10）。 */
    @PostMapping("/runs/{id}/cancel")
    public ApiResponse<RunStateVo> cancel(@PathVariable("id") long id) {
        return ApiResponse.ok(coach.cancel(AuthenticatedUsers.requireId(), id));
    }
}
