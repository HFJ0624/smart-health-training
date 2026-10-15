package com.hfj.fitagent.modules.modelaccess.controller;

import com.hfj.fitagent.common.web.ApiResponse;
import com.hfj.fitagent.modules.modelaccess.api.ProviderRef;
import com.hfj.fitagent.modules.modelaccess.dto.SaveCredentialRequest;
import com.hfj.fitagent.modules.modelaccess.dto.UpdateBudgetPolicyRequest;
import com.hfj.fitagent.modules.modelaccess.service.BalanceSnapshotService;
import com.hfj.fitagent.modules.modelaccess.service.BudgetPolicyService;
import com.hfj.fitagent.modules.modelaccess.service.ModelCredentialService;
import com.hfj.fitagent.modules.modelaccess.service.ProviderCatalogService;
import com.hfj.fitagent.modules.modelaccess.vo.BalanceVo;
import com.hfj.fitagent.modules.modelaccess.vo.BudgetPolicyVo;
import com.hfj.fitagent.modules.modelaccess.vo.ModelCredentialVo;
import com.hfj.fitagent.security.AuthenticatedUsers;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * BYOK 模型设置接口（docs/agent-v1-design.md §11）。
 *
 * <p>供应商是路径里的固定字面量：客户端不提交 providerCode，因此也就无法用输入去挑选外呼地址
 * （V10 迁移注释："由输入决定外呼地址"等于把服务端变成 SSRF 跳板）。
 *
 * <p>本接口在任何响应里都不出现完整 Key、密文、nonce 或指纹（§6.1）；
 * 余额与预算是两本账，不做任何汇率折算（§11.1 第 10 条）。
 */
@RestController
public class ModelSettingsController {

    /** 本轮唯一供应商的固定码；与 model_provider.provider_code 同口径。 */
    public static final String PROVIDER_CODE = "DEEPSEEK";
    private static final String CREDENTIAL_PATH = "/api/model-credentials/deepseek";
    private static final String BUDGET_PATH = "/api/budgets/deepseek";

    private final ModelCredentialService credentialService;
    private final BalanceSnapshotService balanceService;
    private final BudgetPolicyService budgetPolicyService;
    private final ProviderCatalogService catalogService;

    public ModelSettingsController(ModelCredentialService credentialService,
                                   BalanceSnapshotService balanceService,
                                   BudgetPolicyService budgetPolicyService,
                                   ProviderCatalogService catalogService) {
        this.credentialService = credentialService;
        this.balanceService = balanceService;
        this.budgetPolicyService = budgetPolicyService;
        this.catalogService = catalogService;
    }

    /** 凭据摘要；从未保存过 Key 时 {@code data: null}（200，不是 404）。 */
    @GetMapping(CREDENTIAL_PATH)
    public ApiResponse<ModelCredentialVo> credential() {
        return ApiResponse.ok(credentialService.readMasked(AuthenticatedUsers.requireId(), PROVIDER_CODE));
    }

    /** 保存或替换本人的 Key；同 Key 重放不产生新的 credential_version。 */
    @PutMapping(CREDENTIAL_PATH)
    public ResponseEntity<ApiResponse<ModelCredentialVo>> save(@Valid @RequestBody SaveCredentialRequest request) {
        ModelCredentialVo vo = credentialService.saveOrReplace(AuthenticatedUsers.requireId(), PROVIDER_CODE,
                request.apiKey(), request.label());
        return ResponseEntity.ok(ApiResponse.ok(vo));
    }

    /** 撤销：只阻止后续新调用发起，已发出的请求费用不退（§6.1）。 */
    @DeleteMapping(CREDENTIAL_PATH)
    public ApiResponse<ModelCredentialVo> revoke() {
        return ApiResponse.ok(credentialService.revoke(AuthenticatedUsers.requireId(), PROVIDER_CODE));
    }

    /** 供应商账户余额快照：失败时回落到上次成功值并标 stale，绝不显示 0（protocol §8）。 */
    @GetMapping(CREDENTIAL_PATH + "/balance")
    public ApiResponse<BalanceVo> balance() {
        return ApiResponse.ok(balanceService.read(AuthenticatedUsers.requireId(), PROVIDER_CODE));
    }

    /** 本应用侧预算策略（与供应商余额分账显示，不折算）。 */
    @GetMapping(BUDGET_PATH)
    public ApiResponse<BudgetPolicyVo> budget() {
        ProviderRef provider = catalogService.requireProvider(PROVIDER_CODE);
        String currency = budgetPolicyService.quoteCurrency(provider);
        return ApiResponse.ok(budgetPolicyService.find(AuthenticatedUsers.requireId(), PROVIDER_CODE, currency));
    }

    /** 保存预算；限额留空是不设限而不是 0，币种只有一份。 */
    @PutMapping(BUDGET_PATH)
    public ApiResponse<BudgetPolicyVo> saveBudget(@Valid @RequestBody UpdateBudgetPolicyRequest request) {
        return ApiResponse.ok(budgetPolicyService.upsert(AuthenticatedUsers.requireId(), PROVIDER_CODE, request));
    }
}
