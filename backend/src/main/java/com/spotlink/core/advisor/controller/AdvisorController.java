package com.spotlink.advisor.controller;

import com.spotlink.advisor.agent.AdvisorAgent;
import com.spotlink.advisor.config.AdvisorModelSettingsService;
import com.spotlink.advisor.dto.ConversationDetail;
import com.spotlink.advisor.dto.ConversationSummary;
import com.spotlink.advisor.dto.CreateConversationRequest;
import com.spotlink.advisor.dto.MessageView;
import com.spotlink.advisor.dto.SendMessageRequest;
import com.spotlink.advisor.dto.UpdateContextRequest;
import org.springframework.web.bind.annotation.PutMapping;
import com.spotlink.advisor.service.ConversationService;
import com.spotlink.shared.security.LoginUser;
import com.spotlink.shared.security.SecurityUtils;
import com.spotlink.shared.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.support.AopUtils;
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

@Tag(name = "AI 顾问", description = "基于 Spring AI 的工具调用型交易顾问")
@RestController
@RequestMapping("/api/advisor")
@RequiredArgsConstructor
public class AdvisorController {

    private final ConversationService conversationService;
    private final AdvisorAgent advisorAgent;
    private final AdvisorModelSettingsService modelSettings;

    // ------------------------------------------------------------------
    // 会话
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

    @Operation(summary = "更新本会话的采购需求", description = "仅会话所有者可编辑；作为用户背景数据，不改变权限")
    @PutMapping("/conversations/{id}/context")
    public ApiResponse<Void> updateContext(@PathVariable Long id, @Valid @RequestBody UpdateContextRequest request) {
        conversationService.updateContext(id, SecurityUtils.currentUserId(), request.note());
        return ApiResponse.success();
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
    // 状态
    // ------------------------------------------------------------------

    /**
     * 配置状态不主动调用模型；普通用户只看到模型和启用状态，
     * 地址与密钥管理位于有专用权限的后台接口。
     */
    @Operation(summary = "查看顾问配置状态")
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        var config = modelSettings.view();
        return ApiResponse.success(Map.of(
                "available", config.available(),
                "enabled", config.enabled(),
                "configured", config.hasKey(),
                "model", config.model(),
                "provider", "OpenAI-compatible",
                "framework", "Spring AI",
                "registeredTools", registeredToolNames()));
    }

    /**
     * 列出模型当前可以调用的工具名。
     *
     * <p>从智能体交给 Spring AI 的那些 bean 本身扫描出来，而不是取自这里手写的一份
     * 清单。这个方法更早的版本靠手写类名，并且注释还声称它永远不会漂移——**而它第一次
     * 新增工具类时就漂移了，报告九个工具，实际有十三个**。一个被人拿来「代替检查」的
     * 报告，是最不该存在第二份真相的地方。
     */
    private List<String> registeredToolNames() {
        return advisorAgent.toolBeans().stream()
                // 这些 bean 是 CGLIB 代理：@ToolRecordingAspect 是按注解匹配的，
                // 所以 Spring 把它们包了一层。getClass() 拿到的是代理类，而代理类的
                // 已声明方法不包含继承来的那些——列表会变成空的。
                .map(AopUtils::getTargetClass)
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
