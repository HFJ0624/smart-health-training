package com.hfj.fitagent.modules.modelaccess.service;

import com.hfj.fitagent.common.AppClock;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.modelaccess.ModelAccessProperties;
import com.hfj.fitagent.modules.modelaccess.Vocabulary;
import com.hfj.fitagent.modules.modelaccess.api.CredentialHandle;
import com.hfj.fitagent.modules.modelaccess.api.ProviderRef;
import com.hfj.fitagent.modules.modelaccess.crypto.KeyProperties;
import com.hfj.fitagent.modules.modelaccess.entity.ProviderBalanceSnapshotEntity;
import com.hfj.fitagent.modules.modelaccess.mapper.ProviderBalanceSnapshotMapper;
import com.hfj.fitagent.modules.modelaccess.vo.BalanceItemVo;
import com.hfj.fitagent.modules.modelaccess.vo.BalanceVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 余额快照的抓取、落库与降级展示（设计 §6.3、协议 §8）。
 *
 * <p>"失败不清零"是本类的核心：查询失败时<b>不写新行</b>，改为返回上一次的成功快照并标记 {@code stale}，
 * 从没成功过就返回 {@code UNAVAILABLE}。任何路径都不会给出 0。
 *
 * <p>语义提醒（会直接误导界面文案，所以写在这里）：这是<b>供应商账户级</b>余额，
 * 可能被本应用之外的调用改变，也不是单把 Key 的额度；它<b>不参与</b>预算判定，
 * 预算看的是本应用的账本（budget_daily_usage）。
 *
 * <p>明文 Key 只存在于 {@link ModelCredentialService#decryptForCall} 到 {@link DeepSeekBalanceGateway#fetch}
 * 这一段调用栈上，finally 立即擦除。
 */
@Service
public class BalanceSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(BalanceSnapshotService.class);

    private final ProviderCatalogService catalogService;
    private final ModelCredentialService credentialService;
    private final DeepSeekBalanceGateway gateway;
    private final ProviderBalanceSnapshotMapper snapshotMapper;
    private final ModelAccessProperties properties;
    private final AppClock clock;

    public BalanceSnapshotService(ProviderCatalogService catalogService,
                                  ModelCredentialService credentialService,
                                  DeepSeekBalanceGateway gateway,
                                  ProviderBalanceSnapshotMapper snapshotMapper,
                                  ModelAccessProperties properties,
                                  AppClock clock) {
        this.catalogService = catalogService;
        this.credentialService = credentialService;
        this.gateway = gateway;
        this.snapshotMapper = snapshotMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 读余额：先试着问供应商，成功则落新快照，失败则降级到上一次的成功值。
     *
     * <p>凭据缺失/无效不降级——那是要用户动手修的确定事实（抛 {@code CREDENTIAL_MISSING} /
     * {@code CREDENTIAL_INVALID}），只有"外呼本身失败"才走 stale 路径。
     */
    @Transactional
    public BalanceVo read(long userId, String providerCode) {
        ProviderRef provider = catalogService.requireProvider(providerCode);
        if (!provider.balanceSupported()) {
            // V10 注释：把不支持的能力标成可用会产出一张永远失败的按钮，因此不外呼也不给空壳快照
            throw new BizException(ErrorCode.BALANCE_UNAVAILABLE, "该供应商未提供余额查询接口");
        }
        CredentialHandle handle = credentialService.requireUsable(userId, provider.providerId());
        try {
            BalanceReading reading = fetchWithPlaintext(handle, provider);
            Instant fetchedAt = clock.now();
            List<ProviderBalanceSnapshotEntity> saved = persist(userId, provider, reading, fetchedAt);
            if (saved.isEmpty()) {
                // 供应商回了 200 但一个分项都没有：不编造余额，降级到上次结果
                return fallback(userId, provider, "供应商未返回任何余额分项");
            }
            return toVo(provider.providerCode(), BalanceVo.STATE_FRESH, false, fetchedAt,
                    reading.isAvailable(), saved, null);
        } catch (BizException e) {
            if (e.errorCode() != ErrorCode.BALANCE_UNAVAILABLE) {
                throw e;
            }
            return fallback(userId, provider, "本次查询失败，展示上次成功结果");
        } catch (DataIntegrityViolationException e) {
            // 例如供应商给出负数：列上的 CHECK 会拒绝入库，此时同样不覆盖上一次的值
            log.warn("余额快照落库被拒绝 type={}", e.getClass().getSimpleName());
            return fallback(userId, provider, "供应商返回值无法入账");
        }
    }

    /** 解密 → 外呼 → 立刻擦除明文。 */
    private BalanceReading fetchWithPlaintext(CredentialHandle handle, ProviderRef provider) {
        char[] apiKey = credentialService.decryptForCall(handle);
        try {
            return gateway.fetch(provider, apiKey);
        } finally {
            KeyProperties.wipe(apiKey);
        }
    }

    /** 上一次的成功快照（跨币种各行同批），没有则 UNAVAILABLE。 */
    private BalanceVo fallback(long userId, ProviderRef provider, String note) {
        List<ProviderBalanceSnapshotEntity> last = lastBatch(userId, provider.providerId());
        if (last.isEmpty()) {
            return BalanceVo.unavailable(provider.providerCode(), "暂无法获取余额（从未查询成功）");
        }
        Instant fetchedAt = last.get(0).getFetchedAt();
        boolean aged = isAged(fetchedAt);
        return toVo(provider.providerCode(), BalanceVo.STATE_STALE, true, fetchedAt,
                last.get(0).getIsAvailable(), last, note + (aged ? "（数据已超过 " + properties.balance().staleAfter() + "）" : ""));
    }

    /** 陈旧判定由应用推导（fetched_at 与当前时刻之差），不是库存列。 */
    private boolean isAged(Instant fetchedAt) {
        return fetchedAt == null
                || clock.now().isAfter(fetchedAt.plus(properties.balance().staleAfter()));
    }

    /** 同一次抓取按币种各落一行；同一响应里重复币种只保留第一条（唯一键含 currency）。 */
    private List<ProviderBalanceSnapshotEntity> persist(long userId, ProviderRef provider,
                                                        BalanceReading reading, Instant fetchedAt) {
        Map<String, BalanceReading.CurrencyAmount> byCurrency = new LinkedHashMap<>();
        for (BalanceReading.CurrencyAmount amount : reading.amounts()) {
            String currency = Vocabulary.canonicalCurrency(amount.currency());
            if (currency.isEmpty()) {
                continue;
            }
            if (byCurrency.putIfAbsent(currency, amount) != null) {
                log.warn("余额响应内同一币种出现多次，已保留第一条 currency={}", currency);
            }
        }
        List<ProviderBalanceSnapshotEntity> saved = new ArrayList<>(byCurrency.size());
        for (BalanceReading.CurrencyAmount amount : byCurrency.values()) {
            ProviderBalanceSnapshotEntity entity = new ProviderBalanceSnapshotEntity();
            entity.setUserId(userId);
            entity.setProviderId(provider.providerId());
            entity.setCurrency(amount.currency());
            entity.setTotalBalance(amount.total());
            entity.setGrantedBalance(amount.granted());
            entity.setToppedUpBalance(amount.toppedUp());
            entity.setIsAvailable(reading.isAvailable());
            entity.setFetchedAt(fetchedAt);
            entity.setSourceHttpStatus(reading.httpStatus());
            entity.setSourceErrorCode(reading.sourceErrorCode());
            snapshotMapper.insert(entity);
            saved.add(entity);
        }
        log.info("余额快照已落库 userId={} providerCode={} currencies={} fetchedAt={}",
                userId, provider.providerCode(), byCurrency.keySet(), fetchedAt);
        return saved;
    }

    /** 只读：取最近一批快照（按币种各一行）。 */
    @Transactional(readOnly = true)
    public List<ProviderBalanceSnapshotEntity> lastBatch(long userId, long providerId) {
        ProviderBalanceSnapshotEntity[] rows = snapshotMapper.findLatestBatch(userId, providerId);
        return rows == null ? List.of() : List.of(rows);
    }

    private BalanceVo toVo(String providerCode, String state, boolean stale, Instant fetchedAt,
                           Boolean isAvailable, List<ProviderBalanceSnapshotEntity> rows, String note) {
        List<BalanceItemVo> items = rows.stream()
                .map(r -> new BalanceItemVo(r.getCurrency(), r.getTotalBalance(),
                        r.getGrantedBalance(), r.getToppedUpBalance()))
                .toList();
        return new BalanceVo(providerCode, state, stale, fetchedAt, isAvailable, items, note);
    }
}
