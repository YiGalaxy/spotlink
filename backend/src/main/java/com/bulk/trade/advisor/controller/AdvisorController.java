package com.bulk.trade.advisor.controller;

import com.bulk.trade.advisor.client.AdvisorClientFactory;
import com.bulk.trade.advisor.config.AdvisorProperties;
import com.bulk.trade.advisor.dto.ConversationDetail;
import com.bulk.trade.advisor.dto.ConversationSummary;
import com.bulk.trade.advisor.dto.CreateConversationRequest;
import com.bulk.trade.advisor.dto.MessageView;
import com.bulk.trade.advisor.dto.SendMessageRequest;
import com.bulk.trade.advisor.service.ConversationService;
import com.bulk.trade.advisor.tool.AdvisorContext;
import com.bulk.trade.advisor.tool.ToolRegistry;
import com.bulk.trade.shared.security.LoginUser;
import com.bulk.trade.shared.security.SecurityUtils;
import com.bulk.trade.shared.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "AI 顾问", description = "工具调用型交易顾问与会话管理")
@RestController
@RequestMapping("/api/advisor")
@RequiredArgsConstructor
public class AdvisorController {

    private final ConversationService conversationService;
    private final AdvisorClientFactory clientFactory;
    private final ToolRegistry toolRegistry;

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
        return ApiResponse.success(conversationService.sendMessage(
                id, request.message(), AdvisorContext.current()));
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
