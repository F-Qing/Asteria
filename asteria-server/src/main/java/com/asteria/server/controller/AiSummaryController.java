package com.asteria.server.controller;

import com.asteria.common.result.ApiResponse;
import com.asteria.pojo.entity.DTO.AiSummaryDTO;
import com.asteria.pojo.entity.VO.AiSummaryVo;
import com.asteria.server.Services.AiService;
import com.asteria.server.ai.AiHeaders;
import com.asteria.server.ai.AiRequestConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-summary")
public class AiSummaryController {
    @Autowired
    private AiService aiService;

    @PostMapping
    public ApiResponse<AiSummaryVo> createSummary(@RequestBody AiSummaryDTO dto, HttpServletRequest request) {
        // 用 from() 而不是 require()：命中缓存时根本不需要 AI 配置，
        // 没配 AI 的用户也应该能看到之前生成过的总结；真正需要 AI 时由 Service 抛 40020
        AiRequestConfig aiConfig = AiHeaders.from(request);
        return aiService.createSummary(dto, aiConfig);
    }

    /**
     * 查询题库已生成的总结：GET /api/knowledge-summary?bankId=42
     * 只读缓存，不调 AI；从没生成过返回 data=null（前端显示空态）。
     */
    @GetMapping
    public ApiResponse<AiSummaryVo> getSummary(@RequestParam("bankId") Long bankId) {
        return ApiResponse.ok(aiService.getSummary(bankId));
    }
}