package com.fileproc.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Ollama 大模型配置绑定类
 * <p>
 * 对应 application.yml 中的 llm.ollama.* 配置节。
 * 所有配置项均支持环境变量注入（见 application.yml 中的 ${} 占位符）。
 * </p>
 */
@Component
@ConfigurationProperties(prefix = "llm.ollama")
public class OllamaProperties {

    /**
     * Ollama 服务地址，默认本机部署
     */
    private String baseUrl = "http://localhost:11434";

    /**
     * 使用的模型名称，推荐 qwen3:8b 或 qwen2.5:7b
     */
    private String model = "qwen3:8b";

    /**
     * 是否启用大模型引擎；false 时直接使用旧字符串匹配引擎
     */
    private boolean enabled = true;

    /**
     * 单次推理超时时间（秒），CPU推理较慢，建议120~300秒
     */
    private int timeoutSeconds = 180;

    /**
     * 健康检查超时时间（秒）
     */
    private int healthCheckTimeoutSeconds = 3;

    /**
     * 置信度阈值：>= 该值视为高置信度（status=confirmed），< 该值标记为低置信度提示（status=uncertain）
     */
    private double confidenceThreshold = 0.8;

    /**
     * 每个 Word 段落片段的最大字符数，超过则强制截断
     */
    private int maxSegmentChars = 1500;

    // ========== Getters & Setters ==========

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int getHealthCheckTimeoutSeconds() { return healthCheckTimeoutSeconds; }
    public void setHealthCheckTimeoutSeconds(int healthCheckTimeoutSeconds) {
        this.healthCheckTimeoutSeconds = healthCheckTimeoutSeconds;
    }

    public double getConfidenceThreshold() { return confidenceThreshold; }
    public void setConfidenceThreshold(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    public int getMaxSegmentChars() { return maxSegmentChars; }
    public void setMaxSegmentChars(int maxSegmentChars) { this.maxSegmentChars = maxSegmentChars; }
}
