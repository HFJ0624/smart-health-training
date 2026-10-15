package com.hfj.fitagent.agent.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 按 providerId 定位 {@link ModelClient}，并把"生产构建不得用合成模型"变成运行期断言。
 *
 * <p>为什么需要它：一个供应商可能有多个实现候选（真实的 DeepSeekClient + dev 下的 FakeModelClient）。
 * 让调用方（billing 网关）自己从 {@code List<ModelClient>} 里挑，就会出现"挑到哪个算哪个"的静默行为——
 * 而挑到 fake 的后果是费用与验收结论整体失真。
 *
 * <p>规则：
 * <ol>
 *   <li>profiles 含 prod/production 且候选是 {@link ModelClient#synthetic()} → 抛错，绝不返回；</li>
 *   <li>dev/test 且 {@code fitagent.agent.fake-model.enabled=true} → 优先返回合成实现（本地无 Key 也能跑通闭环），
 *       并在每次解析时打一条 warn，保证"现在用的是假模型"可被日志证明；</li>
 *   <li>否则返回真实实现；同一 providerId 有多个真实实现 → 启动期失败（装配歧义不能留到线上去猜）。</li>
 * </ol>
 */
@Component
public class ModelClientLocator {

    private static final Logger log = LoggerFactory.getLogger(ModelClientLocator.class);
    private static final Set<String> PROD_PROFILES = Set.of("prod", "production");
    private static final String FAKE_ENABLED_PROPERTY = "fitagent.agent.fake-model.enabled";

    private final Map<String, List<ModelClient>> byProvider;
    private final Environment environment;

    @Autowired
    public ModelClientLocator(ObjectProvider<ModelClient> clients, Environment environment) {
        this(clients.stream().toList(), environment);
    }

    /** 测试与显式装配用。 */
    public ModelClientLocator(List<ModelClient> clients, Environment environment) {
        Map<String, List<ModelClient>> grouped = new LinkedHashMap<>();
        for (ModelClient client : (clients == null ? List.<ModelClient>of() : clients)) {
            String key = normalize(client.providerId());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(client);
        }
        // 真实实现重复装配 = 谁生效看运气，必须在启动期炸掉；合成实现与真实实现并存是 dev 的预期状态
        grouped.forEach((provider, candidates) -> {
            long real = candidates.stream().filter(client -> !client.synthetic()).count();
            if (real > 1) {
                throw new IllegalStateException("provider " + provider + " 有 " + real
                        + " 个真实 ModelClient 实现，装配歧义：" + candidates);
            }
        });
        this.byProvider = Map.copyOf(grouped);
        this.environment = environment;
    }

    public ModelClient forProvider(String providerId) {
        String key = normalize(providerId);
        List<ModelClient> candidates = byProvider.getOrDefault(key, List.of());
        if (candidates.isEmpty()) {
            throw new ModelProviderException(ProviderErrorKind.CONFIGURATION_REJECTED, "MODEL_NOT_CONFIGURED",
                    "没有 provider=" + key + " 的模型实现：检查 model_provider 种子与适配器装配");
        }
        boolean production = isProduction();
        boolean fakeEnabled = environment != null
                && environment.getProperty(FAKE_ENABLED_PROPERTY, Boolean.class, false);
        ModelClient chosen = null;
        if (fakeEnabled && !production) {
            chosen = candidates.stream().filter(ModelClient::synthetic).findFirst().orElse(null);
        }
        if (chosen == null) {
            chosen = candidates.stream().filter(client -> !client.synthetic()).findFirst().orElse(null);
        }
        if (chosen == null) {
            // 只剩合成实现：这只有在 dev/test 下才允许
            if (production) {
                throw new ModelProviderException(ProviderErrorKind.CONFIGURATION_REJECTED,
                        "SYNTHETIC_MODEL_REJECTED",
                        "prod 环境只装配了合成模型实现，拒绝调用（生产必须连真实供应商或如实报 MODEL_NOT_CONFIGURED）");
            }
            chosen = candidates.get(0);
        }
        if (chosen.synthetic()) {
            log.warn("解析到合成模型实现 provider={} class={} —— 本次结果来自本地脚本，不是供应商",
                    key, chosen.getClass().getSimpleName());
        }
        return chosen;
    }

    public boolean isProduction() {
        return environment != null
                && Arrays.stream(environment.getActiveProfiles()).anyMatch(PROD_PROFILES::contains);
    }

    public Set<String> knownProviders() {
        return byProvider.keySet();
    }

    private static String normalize(String providerId) {
        return providerId == null ? "" : providerId.trim().toUpperCase(Locale.ROOT);
    }
}
