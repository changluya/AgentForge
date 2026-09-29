package com.changlu.agentforge.ai.agent.retry;

import java.util.function.Supplier;

/**
 * @description 模型调用重试，失败后按指数退避重试，全部失败抛出最后一次异常
 * @author changlu
 * @date 2026/9/16
 */
public class AiCallRetrySupport {

    private AiCallRetrySupport() {
        // 工具类，禁止实例化
    }

    /** 重试监听：每次准备进入下一次重试前回调一次 */
    @FunctionalInterface
    public interface RetryListener {
        void onRetry(int retryCount, int maxRetries, Exception lastException, long delayMs);
    }

    /**
     * @param callable 模型调用
     * @param retryCount 重试次数，0表示只调用一次
     * @param delayMillis 首次重试等待时间，后续每次翻倍
     */
    public static <T> T execute(Supplier<T> callable, int retryCount, long delayMillis) {
        return execute(callable, retryCount, delayMillis, null);
    }

    /**
     * @param callable 模型调用
     * @param retryCount 重试次数，0表示只调用一次
     * @param delayMillis 首次重试等待时间，后续每次翻倍
     * @param listener 重试回调，可为null
     */
    public static <T> T execute(
            Supplier<T> callable, int retryCount, long delayMillis, RetryListener listener) {
        int maxRetries = Math.max(retryCount, 0);
        int attempts = maxRetries + 1;
        long delay = Math.max(delayMillis, 0L);
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return callable.get();
            } catch (RuntimeException e) {
                lastError = e;
                if (attempt == attempts) {
                    break;
                }
                if (listener != null) {
                    listener.onRetry(attempt, maxRetries, e, delay);
                }
                sleep(delay);
                delay = delay <= 0L ? 0L : delay * 2;
            }
        }
        throw lastError;
    }

    private static void sleep(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI调用重试等待被中断", e);
        }
    }
}
