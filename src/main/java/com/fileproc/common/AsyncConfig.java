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
 * reportExecutor：专用于报告生成，用法：@Async("reportExecutor")
 * llmExecutor：专用于大模型反向解析（CPU推理，串行不适合高并发），用法：@Async("llmExecutor")
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

    /**
     * 大模型反向解析专用线程池
     * <p>
     * CPU 推理不适合高并发（内存有限），核心线程数设为 1，最多同时处理 1 个反向解析任务，
     * 队列中最多等待 5 个任务。超出队列时由调用方线程同步执行（保证不丢任务）。
     * 用法：@Async("llmExecutor")
     * </p>
     */
    @Bean("llmExecutor")
    public Executor llmExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：CPU推理串行执行，1个即可
        executor.setCorePoolSize(1);
        // 最大线程数：最多同时2个（防止CPU打满）
        executor.setMaxPoolSize(2);
        // 队列容量：最多5个任务排队
        executor.setQueueCapacity(5);
        // 空闲线程回收等待时间（秒）
        executor.setKeepAliveSeconds(120);
        // 线程名前缀，便于日志追踪
        executor.setThreadNamePrefix("llm-reverse-");
        // 拒绝策略：由调用方线程执行（降级同步执行），保证不丢任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 应用关闭时等待大模型推理任务完成（LLM任务可能较慢，给足时间）
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(300);
        executor.initialize();
        return executor;
    }
}
