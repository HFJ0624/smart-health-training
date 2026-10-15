package com.hfj.fitagent.modules.coach;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * coach 模块的配置（{@code fitagent.coach.*}）。
 *
 * <p>命名说明：设计 §10 把线程池参数写在 {@code fitagent.agent.worker.*} 下，但 worker 属于 coach
 * （它写 coach 的表），而 {@code agent/*} 不允许引用业务表——本模块按 AGENTS.md 的边界把它落在
 * {@code fitagent.coach.worker.*}。需要回到文档口径时改这一处前缀即可，字段不变。
 *
 * <p>全部是<b>工程闸门</b>：数字大小不构成任何产品或健康结论（AGENTS.md）。
 * 缺省值与设计 §10 一致（核心 1、最大 2、队列 32），非正数一律回落默认而不是回落到 0
 * ——0 个线程的 worker 会让每个 run 永远停在 QUEUED。
 *
 * @param providerCode        本轮唯一供应商码（与 model_provider.provider_code 同一口径；不做大小写折叠以外的加工）
 * @param maxQuestionChars    单次问题长度上限（超出即 VALIDATION_FAILED，不截断后照常计费）
 * @param titleFromQuestionChars 会话标题从首个问题截取的最大字符数
 * @param historyTurns        历史回放带入的轮数上限（design §8：只取 user + 最终可见 assistant）
 * @param worker              后台执行线程池与租约
 */
@ConfigurationProperties(prefix = "fitagent.coach")
public record CoachProperties(String providerCode,
                              Integer maxQuestionChars,
                              Integer titleFromQuestionChars,
                              Integer historyTurns,
                              Worker worker) {

    public static final String DEFAULT_PROVIDER_CODE = "DEEPSEEK";
    public static final int DEFAULT_MAX_QUESTION_CHARS = 2000;
    public static final int DEFAULT_TITLE_CHARS = 60;
    public static final int DEFAULT_HISTORY_TURNS = 6;

    public CoachProperties {
        providerCode = providerCode == null || providerCode.isBlank()
                ? DEFAULT_PROVIDER_CODE : providerCode.trim().toUpperCase(java.util.Locale.ROOT);
        maxQuestionChars = positive(maxQuestionChars, DEFAULT_MAX_QUESTION_CHARS);
        titleFromQuestionChars = positive(titleFromQuestionChars, DEFAULT_TITLE_CHARS);
        historyTurns = positive(historyTurns, DEFAULT_HISTORY_TURNS);
        worker = worker == null ? Worker.defaults() : worker;
    }

    public static CoachProperties defaults() {
        return new CoachProperties(null, null, null, null, null);
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    /**
     * @param coreSize/maxSize/queueCapacity 有界线程池（§10：不用无界线程、不用消息队列）
     * @param leaseSeconds   租约时长：心跳按 leaseHeartbeatSeconds 续租，过期即视为失去 Worker
     * @param leaseHeartbeatSeconds 心跳间隔；必须小于租约，否则永远续不上
     * @param scanIntervalSeconds 启动后的补偿扫描间隔（补领遗漏的 QUEUED、恢复失联的 RUNNING）
     * @param scanBatchSize  单轮扫描上限，防止一次拉起整张表
     */
    public record Worker(Integer coreSize,
                         Integer maxSize,
                         Integer queueCapacity,
                         Integer leaseSeconds,
                         Integer leaseHeartbeatSeconds,
                         Integer scanIntervalSeconds,
                         Integer scanBatchSize) {

        public static final int DEFAULT_CORE = 1;
        public static final int DEFAULT_MAX = 2;
        public static final int DEFAULT_QUEUE = 32;
        public static final int DEFAULT_LEASE_SECONDS = 60;
        public static final int DEFAULT_LEASE_HEARTBEAT_SECONDS = 20;
        public static final int DEFAULT_SCAN_INTERVAL_SECONDS = 60;
        public static final int DEFAULT_SCAN_BATCH = 20;

        public Worker {
            coreSize = positive(coreSize, DEFAULT_CORE);
            maxSize = positive(maxSize, DEFAULT_MAX);
            if (maxSize < coreSize) {
                maxSize = coreSize;
            }
            queueCapacity = positive(queueCapacity, DEFAULT_QUEUE);
            leaseSeconds = positive(leaseSeconds, DEFAULT_LEASE_SECONDS);
            leaseHeartbeatSeconds = positive(leaseHeartbeatSeconds, DEFAULT_LEASE_HEARTBEAT_SECONDS);
            if (leaseHeartbeatSeconds >= leaseSeconds) {
                // 心跳比租约还慢等于没有心跳：配置写错就在这里说清楚，而不是让 run 陆续过期
                throw new IllegalStateException(
                        "fitagent.coach.worker.lease-heartbeat-seconds 必须小于 lease-seconds");
            }
            scanIntervalSeconds = positive(scanIntervalSeconds, DEFAULT_SCAN_INTERVAL_SECONDS);
            scanBatchSize = positive(scanBatchSize, DEFAULT_SCAN_BATCH);
        }

        public static Worker defaults() {
            return new Worker(null, null, null, null, null, null, null);
        }

        private static int positive(Integer value, int fallback) {
            return value == null || value <= 0 ? fallback : value;
        }
    }
}
