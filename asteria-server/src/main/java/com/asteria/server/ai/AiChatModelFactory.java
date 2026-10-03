package com.asteria.server.ai;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 按用户的配置（请求头带来的 key / baseUrl / model）现场造一个 OpenAiChatModel。
 *
 * <p>为什么不做成启动时的单例 bean：BYOK 模式下 key 和 baseUrl 每个请求都可能不同，
 * 复用同一个会把 A 用户的 key 用到 B 用户的请求上。这里每请求现造，用完即弃。
 *
 * <p>工厂本身无状态，所以它是单例；产品（ChatModel）每次都是新的。
 */
@Component
public class AiChatModelFactory {

    /**
     * 支持 thinking 开关的 DeepSeek 模型。
     *
     * <p>只认这几个：老的 {@code deepseek-chat} / {@code deepseek-reasoner} 不认 thinking 字段，
     * 乱发会被判成"未知参数"直接 400，所以这里必须按模型名收口。
     */
    private static final List<String> DEEPSEEK_THINKING_MODELS =
            List.of("deepseek-flash", "deepseek-v4-flash", "deepseek-v4-pro");

    /** DeepSeek 关思考的请求体片段 */
    private static final Map<String, Object> DEEPSEEK_THINKING_DISABLED =
            Map.of("thinking", Map.of("type", "disabled"));

    /**
     * @param temperature 温度由【调用方按功能】决定：测试连通性传 null（跟服务商默认），
     *                    题目解析传 0.3（要稳定），以后聊天传 0.7（要自然）。null = 不设置该参数。
     */
    public OpenAiChatModel create(AiRequestConfig config, Double temperature) {
        return create(config, temperature, null, null);
    }

    /**
     * 完整版：温度 + 服务商专有参数 + 输出格式。
     *
     * @param responseFormat 约束模型的输出格式（如 {@code json_object}）；null = 不限制
     */
    public OpenAiChatModel create(AiRequestConfig config, Double temperature,
                                  Map<String, Object> extraBody, ResponseFormat responseFormat) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(config.endpoint(""))         // 顺手去掉用户可能多填的结尾斜杠
                .apiKey(config.apiKey())              // ★ key 只用在这一处
                .completionsPath("/chat/completions") // 前端 baseUrl 已含版本段，别再补 /v1
                .build();

        // 关掉 Spring AI 默认的"重试 10 次 + 指数退避"：
        // 批量解析是一道一道跑，宁可快速失败、由外层记账，也不要一道题卡几分钟
        RetryTemplate noRetry = RetryTemplate.builder().maxAttempts(1).build();

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(config.model());
        if (temperature != null) {
            options.temperature(temperature);
        }
        if (extraBody != null && !extraBody.isEmpty()) {
            options.extraBody(extraBody);
        }
        if (responseFormat != null) {
            options.responseFormat(responseFormat);
        }

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options.build())
                .retryTemplate(noRetry)
                .build();
    }

    /**
     * 【题库导入专用】结构化抽取用的模型：额外要求 {@code response_format=json_object}。
     *
     * <p>为什么要 JSON 模式：导入要的是**结构**（题干/选项/答案分字段），不是"看起来像标准格式的文本"。
     * 让模型直接吐 JSON，格式由协议兜住；解析失败、字段缺失、残题这些都能在程序里一眼看出来，
     * 不用再拿正则去猜它有没有漏写「题目：」标签。
     *
     * <p>同时会关掉 DeepSeek 的思考模式（{@link #disableThinkingIfDeepSeek}）：抽取是机械活，
     * 思考只会更慢更贵，还让 temperature 失效。
     */
    public OpenAiChatModel createForImport(AiRequestConfig config, Double temperature) {
        return create(config, temperature, disableThinkingIfDeepSeek(config),
                ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
    }

    /**
     * DeepSeek 上把思考模式关掉：是 DeepSeek 的 V4 系模型 → 返回关思考的 extraBody，否则返回 null。
     *
     * <p>为什么：DeepSeek-V4 系列（含 flash）的 thinking **默认是开的**，而"照抄 + 归类"这类活
     * 开着思考只会更慢更贵，还让 temperature <b>直接失效</b>（官方文档：thinking 模式下 temperature 无效）。
     *
     * <p>为什么要按模型名收口：thinking 是 DeepSeek 专有字段，发给 OpenAI / Kimi / 通义 会直接 400；
     * 老的 {@code deepseek-chat} / {@code deepseek-reasoner} 也不认它。
     */
    private Map<String, Object> disableThinkingIfDeepSeek(AiRequestConfig config) {
        String provider = lower(config.provider());
        String baseUrl = lower(config.baseUrl());
        String model = lower(config.model());

        // provider 是设置页选的服务商；用户选「自定义」但填了 DeepSeek 地址时，靠 baseUrl 兜底
        boolean isDeepSeek = provider.contains("deepseek") || baseUrl.contains("deepseek");
        if (!isDeepSeek) {
            return null;
        }
        boolean thinkingModel = DEEPSEEK_THINKING_MODELS.stream().anyMatch(model::startsWith);
        return thinkingModel ? DEEPSEEK_THINKING_DISABLED : null;
    }

    private String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }
}
