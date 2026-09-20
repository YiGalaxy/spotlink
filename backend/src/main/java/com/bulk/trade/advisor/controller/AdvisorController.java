package com.bulk.trade.advisor.controller;

import com.bulk.trade.advisor.agent.AgentLoop;
import com.bulk.trade.advisor.agent.AgentResult;
import com.bulk.trade.advisor.client.AdvisorClientFactory;
import com.bulk.trade.advisor.config.AdvisorProperties;
import com.bulk.trade.advisor.dto.ChatRequest;
import com.bulk.trade.advisor.dto.ChatResponse;
import com.bulk.trade.advisor.tool.AdvisorContext;
import com.bulk.trade.advisor.tool.ToolRegistry;
import com.bulk.trade.shared.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "AI 顾问", description = "工具调用型交易顾问")
@RestController
@RequestMapping("/api/advisor")
@RequiredArgsConstructor
public class AdvisorController {

    private final AgentLoop agentLoop;
    private final AdvisorClientFactory clientFactory;
    private final ToolRegistry toolRegistry;

    @Operation(summary = "向 AI 顾问提问",
            description = "顾问会按需调用平台工具取数，返回答案与完整的工具调用链")
    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        AdvisorContext context = AdvisorContext.current();
        AgentResult result = agentLoop.run(request.message(), List.of(), context);
        return ApiResponse.success(ChatResponse.from(result));
    }

    /**
     * Readiness probe. Reports which endpoint and model the advisor would use
     * without revealing the key, so a misconfiguration is visible before a user
     * hits a failing chat request.
     */
    @Operation(summary = "查看顾问配置状态")
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        AdvisorProperties properties = clientFactory.properties();
        return ApiResponse.success(Map.of(
                "available", clientFactory.isAvailable(),
                "enabled", properties.enabled(),
                "endpoint", properties.baseUrl(),
                "model", properties.model(),
                "maxIterations", properties.effectiveMaxIterations(),
                "registeredTools", toolRegistry.all().stream().map(t -> t.name()).toList()));
    }
}
