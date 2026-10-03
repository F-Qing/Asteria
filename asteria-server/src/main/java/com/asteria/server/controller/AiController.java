package com.asteria.server.controller;

import com.asteria.common.result.ApiResponse;
import com.asteria.server.Services.AiService;
import com.asteria.server.ai.AiHeaders;
import com.asteria.server.ai.AiTestResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 相关接口，统一前缀 /api/ai。
 *
 * <p>配置文件（X-AI-* 请求头）由前端每次请求带上，后端不存 —— 详见 {@link AiHeaders}。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    @Autowired
    private AiService aiService;

    /**
     * 测试 AI 配置是否可用：POST /api/ai/test
     *
     * <p>没有请求体 —— 配置全在请求头（X-AI-*）里，前端 POST 一下就行。
     *
     * <p>返回 data = { ok, message }：ok 是布尔结果，message 说明原因
     * （如"API Key 无效（HTTP 401）"），前端直接把 message 显示给用户。
     *
     * <p>注意这里 code 永远是 0 —— "测试没通过"是业务结果，不是接口错误。
     */
    @PostMapping("/test")
    public ApiResponse<AiTestResult> test(HttpServletRequest request) {
        AiTestResult result = aiService.testConnection(AiHeaders.from(request));
        return ApiResponse.ok(result);
    }

}
