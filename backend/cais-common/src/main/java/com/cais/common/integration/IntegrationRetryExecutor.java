package com.cais.common.integration;

import java.util.concurrent.Callable;

/** 只重试明确标记为可恢复的外部故障，避免把参数、权限和状态机错误盲目重放。 */
public final class IntegrationRetryExecutor {
    private IntegrationRetryExecutor() { }

    public static <T> T execute(String dependency, IntegrationRetryPolicy policy, Callable<T> action) {
        RetryableIntegrationException lastFailure = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            try {
                return action.call();
            } catch (RetryableIntegrationException ex) {
                lastFailure = ex;
                if (attempt == policy.maxAttempts()) break;
                pause(policy.backoffMillis());
            } catch (Exception ex) {
                throw new IllegalStateException(dependency + " adapter invocation failed", ex);
            }
        }
        throw new RetryableIntegrationException(dependency + " remains unavailable after "
                + policy.maxAttempts() + " attempts", lastFailure);
    }

    private static void pause(long backoffMillis) {
        if (backoffMillis == 0) return;
        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RetryableIntegrationException("integration retry interrupted", ex);
        }
    }
}
