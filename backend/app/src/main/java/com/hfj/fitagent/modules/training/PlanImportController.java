package com.hfj.fitagent.modules.training;

import com.hfj.fitagent.common.web.ApiResponse;
import com.hfj.fitagent.modules.training.dto.ImportConfirmRequest;
import com.hfj.fitagent.modules.training.dto.ImportPreviewRequest;
import com.hfj.fitagent.modules.training.vo.ImportConfirmVo;
import com.hfj.fitagent.modules.training.vo.ImportPreviewVo;
import com.hfj.fitagent.security.AuthenticatedUsers;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计划 JSON 导入接口（docs/p1-api.md §8、docs/p1-plan-import.md §10）。
 *
 * <p>两个端点接收<b>完全相同的原始文本</b>：微信侧无论从会话选文件还是粘贴，
 * 提交的都是同一份 UTF-8 JSON 文本，能力等价（§1）。
 * 后端不接收 multipart、不保存原件、不做图片/PDF 解析。
 */
@RestController
public class PlanImportController {

    /** 幂等键的请求头名（docs/p1-api.md §1"幂等"）。 */
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final PlanImportService service;

    public PlanImportController(PlanImportService service) {
        this.service = service;
    }

    /**
     * 校验 + 预览。<b>无副作用</b>：不落库、不占用幂等键，可以反复调用。
     *
     * <p>校验不通过时返回 422，错误体里 {@code errors[].path} 定位到数组下标，页面逐行高亮；
     * 通过时返回服务端规范化后的计划（含字典解析出的名称与类型、重排后的 position_no）。
     */
    @PostMapping("/api/plans/import/preview")
    public ApiResponse<ImportPreviewVo> preview(@Valid @RequestBody ImportPreviewRequest req) {
        return ApiResponse.ok(service.preview(AuthenticatedUsers.requireId(), req.content()));
    }

    /**
     * 确认保存为草稿。
     *
     * <p>{@code Idempotency-Key} 可缺省：服务端生成一个并在响应里回传，客户端存下来用于重试。
     * 首次成功 201，命中重放 200（同一条计划不会被写两次，验收 A17）。
     */
    @PostMapping("/api/plans/import")
    public ResponseEntity<ApiResponse<ImportConfirmVo>> confirm(
            @Valid @RequestBody ImportConfirmRequest req,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        ImportConfirmVo result = service.confirm(AuthenticatedUsers.requireId(), req.content(), idempotencyKey);
        if (result.idempotentReplay()) {
            return ResponseEntity.ok(ApiResponse.ok(result));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(result));
    }
}
