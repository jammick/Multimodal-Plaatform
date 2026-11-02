package com.cais.common.integration;

/** 外部依赖暂时不可用且允许按策略重试时抛出。 */
public class RetryableIntegrationException extends RuntimeException {
    public RetryableIntegrationException(String message, Throwable cause) { super(message, cause); }
    public RetryableIntegrationException(String message) { super(message); }
}
