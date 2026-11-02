package com.cais.common.integration;

/** 外部适配器短暂故障的有限重试策略，业务级重试与消息队列延时重试仍由调用方负责。 */
public record IntegrationRetryPolicy(int maxAttempts, long backoffMillis) {
    public IntegrationRetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
        if (backoffMillis < 0) throw new IllegalArgumentException("backoffMillis must not be negative");
    }

    public static IntegrationRetryPolicy shortNetworkRetry() {
        return new IntegrationRetryPolicy(3, 200);
    }
}
