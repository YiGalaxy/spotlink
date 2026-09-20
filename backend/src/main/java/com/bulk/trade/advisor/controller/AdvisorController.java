package com.bulk.trade.advisor.controller;

import com.bulk.trade.advisor.dto.ConversationDetail;
import com.bulk.trade.advisor.dto.ConversationSummary;
import com.bulk.trade.advisor.dto.CreateConversationRequest;
import com.bulk.trade.advisor.dto.MessageView;
import com.bulk.trade.advisor.dto.SendMessageRequest;
import com.bulk.trade.advisor.service.ConversationService;
import com.bulk.trade.advisor.tool.AdvisorTools;
import com.bulk.trade.advisor.tool.ContractAdvisorTools;
import com.bulk.trade.advisor.tool.InventoryAdvisorTools;
import com.bulk.trade.advisor.tool.KnowledgeAdvisorTools;
import com.bulk.trade.advisor.tool.MarketAdvisorTools;
import com.bulk.trade.shared.security.LoginUser;
import com.bulk.trade.shared.security.SecurityUtils;
import com.bulk.trade.shared.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Tag(name = "AI 顾问", description = "基于 Spring AI 的工具调用型交易顾问")
@RestController
@RequestMapping("/api/advisor")
@RequiredArgsConstructor
public class AdvisorController {

    /** Placeholder used when no key is configured; see application.yml. */
    private static final String UNCONFIGURED_KEY = "not-configured";

    private final ConversationService conversationService;

    @Value("${spring.ai.anthropic.api-key:}")
    private String apiKey;

    @Value("${spring.ai.anthropic.base-url:}")
    private String baseUrl;

    @Value("${spring.ai.anthropic.chat.model:}")
    private String model;

    // ------------------------------------------------------------------
    // Conversations
    // ------------------------------------------------------------------

    @Operation(summary = "我的会话列表", description = "按最近消息时间倒序，不含消息内容")
    @GetMapping("/conversations")
    public ApiResponse<List<ConversationSummary>> listConversations() {
        return ApiResponse.success(conversationService.listMine(SecurityUtils.currentUserId()));
    }

    @Operation(summary = "新建会话")
    @PostMapping("/conversations")
    public ApiResponse<ConversationDetail> createConversation(
            @Valid @RequestBody(required = false) CreateConversationRequest request) {
        LoginUser user = SecurityUtils.currentUser();
        String title = request == null ? null : request.title();
        return ApiResponse.success(
                conversationService.create(user.getUserId(), user.getEnterpriseId(), title));
    }

    @Operation(summary = "会话详情", description = "包含全部消息与各自的工具调用链")
    @GetMapping("/conversations/{id}")
    public ApiResponse<ConversationDetail> getConversation(@PathVariable Long id) {
        return ApiResponse.success(
                conversationService.get(id, SecurityUtils.currentUserId()));
    }

    @Operation(summary = "删除会话", description = "软删除，消息作为历史保留")
    @DeleteMapping("/conversations/{id}")
    public ApiResponse<Void> deleteConversation(@PathVariable Long id) {
        conversationService.delete(id, SecurityUtils.currentUserId());
        return ApiResponse.success();
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    @Operation(summary = "发送消息",
            description = "顾问会带上该会话的历史上下文，按需调用平台工具取数，返回答案与完整调用链")
    @PostMapping("/conversations/{id}/messages")
    public ApiResponse<MessageView> sendMessage(@PathVariable Long id,
                                                @Valid @RequestBody SendMessageRequest request) {
        return ApiResponse.success(
                conversationService.sendMessage(id, request.message(), SecurityUtils.currentUser()));
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    /**
     * Readiness probe. Reports the endpoint and model in use without revealing
     * the key, so a misconfiguration is visible before a chat request fails.
     */
    @Operation(summary = "查看顾问配置状态")
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        boolean configured = apiKey != null
                && !apiKey.isBlank()
                && !UNCONFIGURED_KEY.equals(apiKey);

        return ApiResponse.success(Map.of(
                "available", configured,
                "endpoint", baseUrl == null ? "" : baseUrl,
                "model", model == null ? "" : model,
                "framework", "Spring AI",
                "registeredTools", registeredToolNames()));
    }

    /**
     * Names the tools the model can currently call.
     *
     * <p>Read from the annotations rather than a hard-coded list, so this can
     * never drift from what is actually registered.
     */
    private List<String> registeredToolNames() {
        // Every class whose @Tool methods are handed to the agent. Kept in one
        // place so this list cannot drift from what is actually registered.
        return Stream.of(AdvisorTools.class, InventoryAdvisorTools.class,
                        KnowledgeAdvisorTools.class, ContractAdvisorTools.class,
                        MarketAdvisorTools.class)
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .map(method -> {
                    Tool tool = method.getAnnotation(Tool.class);
                    return tool.name().isBlank() ? method.getName() : tool.name();
                })
                .sorted()
                .toList();
    }
}
