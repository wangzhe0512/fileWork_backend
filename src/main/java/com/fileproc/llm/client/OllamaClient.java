package com.fileproc.llm.client;

import com.fileproc.llm.config.OllamaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Ollama HTTP API 客户端
 * <p>
 * 封装对本地 Ollama 服务的两类调用：
 * 1. generate()：单次文本生成（调用 /api/generate，stream=false）
 * 2. isHealthy()：健康检查（调用 /api/tags，超时3秒）
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OllamaClient {

    private final OllamaProperties properties;
    private final RestTemplateBuilder restTemplateBuilder;

    /**
     * 调用 Ollama 生成接口
     *
     * @param prompt 完整的提示词
     * @return 模型输出的文本内容（response 字段），调用失败返回 null
     */
    public String generate(String prompt) {
        RestTemplate restTemplate = buildRestTemplate(properties.getTimeoutSeconds());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", properties.getModel());
        requestBody.put("prompt", prompt);
        requestBody.put("stream", false);  // 非流式，等待完整响应
        // 控制输出格式，减少废话
        Map<String, Object> options = new HashMap<>();
        options.put("temperature", 0.1);   // 低温度，输出更确定性
        options.put("num_predict", 2048);  // 最大输出 token 数
        requestBody.put("options", options);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            String url = properties.getBaseUrl() + "/api/generate";
            log.debug("[OllamaClient] 发送推理请求: model={}, promptLen={}", properties.getModel(), prompt.length());

            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object responseText = response.getBody().get("response");
                if (responseText != null) {
                    String result = responseText.toString().trim();
                    log.debug("[OllamaClient] 推理完成: responseLen={}", result.length());
                    return result;
                }
            }
            log.warn("[OllamaClient] 推理响应异常: status={}, body={}", response.getStatusCode(), response.getBody());
            return null;

        } catch (ResourceAccessException e) {
            log.warn("[OllamaClient] 连接 Ollama 超时或拒绝连接: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("[OllamaClient] 推理调用失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 检查 Ollama 服务是否可用
     *
     * @return true=可用，false=不可用
     */
    public boolean isHealthy() {
        if (!properties.isEnabled()) {
            log.debug("[OllamaClient] 大模型引擎已禁用（llm.ollama.enabled=false）");
            return false;
        }

        RestTemplate healthRestTemplate = buildRestTemplate(properties.getHealthCheckTimeoutSeconds());
        try {
            String url = properties.getBaseUrl() + "/api/tags";
            ResponseEntity<String> response = healthRestTemplate.getForEntity(url, String.class);
            boolean healthy = response.getStatusCode().is2xxSuccessful();
            log.debug("[OllamaClient] 健康检查结果: healthy={}", healthy);
            return healthy;
        } catch (Exception e) {
            log.info("[OllamaClient] Ollama 健康检查失败，将使用旧引擎降级: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 构建指定超时时间的 RestTemplate
     */
    private RestTemplate buildRestTemplate(int timeoutSeconds) {
        return restTemplateBuilder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }
}
