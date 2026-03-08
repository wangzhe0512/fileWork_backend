package com.fileproc.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池配置
 * <p>
 * 专用于报告生成的 "reportExecutor" 线程池，避免占用默认 SimpleAsyncTaskExecutor。
 * 用法：@Async("reportExecutor")
 * </p>
 */
@EnableAsync
@Configuration
public class AsyncConfig {

    @Bean("reportExecutor")
    public Executor reportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：同时最多3个报告并发生成
        executor.setCorePoolSize(3);
        // 最大线程数
        executor.setMaxPoolSize(10);
        // 队列容量：排队等待生成的任务上限
        executor.setQueueCapacity(50);
        // 空闲线程回收等待时间（秒）
        executor.setKeepAliveSeconds(60);
        // 线程名前缀，便于日志追踪
        executor.setThreadNamePrefix("report-gen-");
        // 拒绝策略：由调用方线程执行（降级同步执行），保证不丢任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 应用关闭时等待任务完成
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
