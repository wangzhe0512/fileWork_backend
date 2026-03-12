---
name: llm-placeholder-naming
overview: 集成本地大模型（Ollama + Qwen2.5-7B），在反向生成完成后异步为每个占位符自动生成业务语义化的显示名称（`name` 字段），替代当前技术性的占位符标识（如"清单模板-数据表-B3"）。
---

## 用户需求

利用本地私有化部署的轻量大模型（Ollama + Qwen2.5-7B），为反向生成出的占位符自动生成业务语义化名称（如"企业名称"、"注册资本"），替代当前展示给用户的技术性名称（如"清单模板-数据表-B3"）。

## 产品概述

在反向生成企业子模板完成后，异步调用本地 Ollama 大模型 API，根据每个占位符的技术标识、来源Sheet名、以及从Excel读取到的实际样本值，智能推断该占位符的业务含义，并自动将 `name` 字段更新为语义化名称，提升用户在占位符确认页面的阅读和操作效率。

**约束**：

- 纯本地部署，不调用任何外部 API，确保数据安全
- 服务器：8核16G 阿里云 ECS，无 GPU，CPU 推理
- 准确率优先，可接受较慢的异步处理速度
- 大模型降级：Ollama 不可用时，`name` 自动降级使用 `sourceField` 或 `placeholderName` 最后一段作为兜底

## 核心功能

- **LLM命名服务**：封装 Ollama HTTP API（`/api/generate`）调用，构建中文Prompt，输出简洁业务语义名称（2-6个汉字）
- **异步批量命名任务**：反向生成完成后，异步逐个调用大模型为每条占位符生成 `name`，每条成功后立即写回数据库
- **降级兜底**：Ollama 超时/不可用时，`name` 自动使用 `sourceField` 或 `placeholderName` 最后一段填充
- **配置化管理**：`llm.ollama.base-url`、`llm.ollama.model`、`llm.ollama.enabled`、`llm.ollama.timeout-seconds` 等配置项支持环境变量覆盖
- **幂等重试入口**：提供 `POST /company-template/{id}/trigger-naming` 接口，支持手动重新触发命名（如首次模型未启动，命名后手动重跑）

## 技术栈

- **框架**：Spring Boot 3.2.5 + Java 17（与现有项目一致）
- **HTTP 客户端**：Spring 内置 `RestTemplate`（`spring-boot-starter-web` 已依赖，无需新增依赖）
- **异步框架**：Spring `@Async` + 现有 `AsyncConfig`（复用 `reportExecutor` 线程池或新增独立 `llmExecutor`）
- **配置绑定**：`@ConfigurationProperties` 绑定 `llm.ollama.*`
- **持久化**：MyBatis-Plus（现有 `CompanyTemplatePlaceholderMapper`）

---

## 实现方案

### 整体策略

新增独立的 `LlmNamingService` 封装 Ollama 调用，新增 `PlaceholderNamingAsyncService` 负责反向生成完成后的异步批量命名流程，通过 `CompanyTemplateController.initModulesAndPlaceholders()` 结束后异步触发。

核心技术决策：

1. **用 `RestTemplate` 而非 WebClient**：项目已有 `RestTemplate` 用法（`spring-boot-starter-web`），无需引入响应式依赖，且 CPU 推理本身是阻塞等待，响应式无额外收益
2. **逐条调用 + 立即落库**：不批量等待全部完成，每条占位符命名成功后立即 `updateById`，保证部分失败不影响已完成结果；遇到异常则降级并继续
3. **独立线程池 `llmExecutor`**：核心线程数 1（CPU 推理不适合高并发），避免挤占 `reportExecutor`；接口返回 202 时任务已入队
4. **Prompt 工程**：单次调用只处理一个占位符，提供 `placeholderName`、`sourceSheet`、`sourceField`、`expectedValue` 四个字段组成中

[User Cancelled]