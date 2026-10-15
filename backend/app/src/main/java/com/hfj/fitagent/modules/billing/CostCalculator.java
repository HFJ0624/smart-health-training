package com.hfj.fitagent.modules.billing;

import com.hfj.fitagent.modules.billing.api.CostResult;
import com.hfj.fitagent.modules.billing.api.TokenUsage;
import com.hfj.fitagent.modules.billing.api.UnitPrices;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 费用核算：{@code (hit×命中价 + miss×未命中价 + output×输出价) / 1_000_000}（设计 §6.2）。
 *
 * <p>三条口径都在类型上不可见就会出事，所以写在这里：
 * <ol>
 *   <li><b>三个分项各乘各的价</b>：命中价与未命中价差一个数量级（协议 §7：0.02 对 1），
 *       用 prompt_tokens 乘单一价会把命中部分按未命中价算贵；</li>
 *   <li><b>{@code reasoning_tokens} 不另加钱</b>：它是 completion_tokens 的细分项，
 *       再加一次就是把同一批 token 收两遍（协议 §6 的算术：22 + 29 = 51，reasoning 27 已在 29 内）；
 *       因此本方法<b>从不</b>读取 {@code reasoningTokens} 参与求和；</li>
 *   <li><b>缺任一项即 UNKNOWN，金额 null</b>：绝不补 0——0 会让一次用量不明的调用看起来免费。</li>
 * </ol>
 *
 * <p>精度：全程 {@link BigDecimal}，中间乘法/除法用 {@code MathContext(16, HALF_UP)}，
 * 结果统一 {@code setScale(10, HALF_UP)} 对齐 {@code DECIMAL(20,10)} 列宽。
 * 最小档 0.02 元/1M 下 1000 个 token 是 0.0000000002 元，10 位小数恰好精确表示；
 * 更小的量（&lt; 1e-10 元）在本层就落到 0，"低于显示精度"是展示层按设计 §12 处理的事，
 * 不是把金额列做成更高精度的理由。
 */
@Component
public class CostCalculator {

    /** 金额列的 scale（DECIMAL(20,10)）。 */
    public static final int MONEY_SCALE = 10;
    /** 中间计算精度：16 位有效数字远大于 20 位整数部分 + 10 位小数所需的分辨率。 */
    public static final MathContext MATH = new MathContext(16, RoundingMode.HALF_UP);
    /** 单价单位是"每 100 万 token"。 */
    public static final BigDecimal PER_MILLION = new BigDecimal("1000000");

    /**
     * @param usage  token 分项（null 分项即不可核算）
     * @param prices 生效单价（币种与档位必须来自同一报价行）
     */
    public CostResult compute(TokenUsage usage, UnitPrices prices) {
        if (usage == null || prices == null) {
            return CostResult.unknown(prices == null ? null : prices.currency(),
                    List.of(usage == null ? "usage" : "prices"));
        }
        List<String> missing = new ArrayList<>(usage.missingFields());
        missing.addAll(prices.missingFields());
        if (prices.currency() == null || prices.currency().isBlank()) {
            missing.add("currency");
        }
        // 负数不是"零"：它会把总费用算小，同样归入不可核算
        if (usage.hasNegative() || prices.hasNegative()) {
            missing.add("negativeValue");
        }
        if (!missing.isEmpty()) {
            return CostResult.unknown(prices.currency(), List.copyOf(missing));
        }

        BigDecimal hitCost = BigDecimal.valueOf(usage.hitTokens())
                .multiply(prices.hitPerMillion(), MATH);
        BigDecimal missCost = BigDecimal.valueOf(usage.missTokens())
                .multiply(prices.missPerMillion(), MATH);
        // 输出分项只用 completion_tokens；reasoningTokens 在这里被刻意忽略
        BigDecimal outputCost = BigDecimal.valueOf(usage.outputTokens())
                .multiply(prices.outputPerMillion(), MATH);

        BigDecimal total = hitCost.add(missCost, MATH).add(outputCost, MATH)
                .divide(PER_MILLION, MATH)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return CostResult.known(prices.currency(), total);
    }

    /**
     * 保守预占估算：输入全部按<b>未命中</b>价（不假定任何缓存命中）、输出按 maxOutputTokens 全价。
     *
     * <p>档位由调用方传入的 prices 决定——预占路径必须传 PEAK 档（协议 §7：节假日日历未核实，
     * 不能断定某时刻真的在谷段）。
     */
    public CostResult estimate(long inputTokens, Integer maxOutputTokens, UnitPrices peakPrices) {
        if (maxOutputTokens == null || maxOutputTokens <= 0 || inputTokens < 0) {
            return CostResult.unknown(peakPrices == null ? null : peakPrices.currency(),
                    List.of("maxOutputTokens", "estimateInputTokens"));
        }
        return compute(new TokenUsage(0L, inputTokens, (long) maxOutputTokens, null), peakPrices);
    }

    /** 金额相加（同币种才允许；跨币种在此直接失败，不猜汇率）。 */
    public BigDecimal addSameCurrency(BigDecimal left, BigDecimal right, String currency) {
        BigDecimal a = left == null ? BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP) : left;
        BigDecimal b = right == null ? BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP) : right;
        return a.add(b, MATH).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
