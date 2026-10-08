package com.spotlink.admin.controller;

import com.spotlink.advisor.config.AdvisorModelClientFactory;
import com.spotlink.advisor.config.AdvisorModelSettings;
import com.spotlink.advisor.config.AdvisorModelSettingsService;
import com.spotlink.shared.audit.AuditService;
import com.spotlink.shared.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 平台默认模型只允许具有专用权限的运营人员设置；本地地址属于部署者可信配置。 */
@RestController
@RequestMapping("/api/admin/advisor-model")
@RequiredArgsConstructor
public class AdminAdvisorController {
    private final AdvisorModelSettingsService settings;
    private final AdvisorModelClientFactory clients;
    private final AuditService audit;

    @GetMapping
    @PreAuthorize("hasAuthority('admin:advisor')")
    public ApiResponse<AdvisorModelSettingsService.View> get() { return ApiResponse.success(settings.view()); }

    @PutMapping
    @PreAuthorize("hasAuthority('admin:advisor:write')")
    public ApiResponse<AdvisorModelSettingsService.View> save(@Valid @RequestBody AdvisorModelSettingsService.Update request) {
        return ApiResponse.success(settings.save(request));
    }

    @DeleteMapping
    @PreAuthorize("hasAuthority('admin:advisor:write')")
    public ApiResponse<AdvisorModelSettingsService.View> reset() { return ApiResponse.success(settings.reset()); }

    // 独立显式动作，只测试已保存配置，绝不发送聊天历史、企业数据或业务工具。
    @PostMapping("/test")
    @PreAuthorize("hasAuthority('admin:advisor:write')")
    public ApiResponse<ConnectionResult> test() {
        long start = System.nanoTime();
        try {
            AdvisorModelSettings snapshot = settings.current();
            ProbeTool probe = new ProbeTool();
            String response = clients.create(snapshot).prompt()
                    .user("这是连接测试。请先调用 platform_connection_probe 工具，再用中文回复连接正常。不要输出思考过程。"
                            + (snapshot.model().startsWith("qwen3") ? "\n/no_think" : ""))
                    .tools(probe).call().content();
            boolean connected = response != null && !response.isBlank();
            ConnectionResult result = new ConnectionResult(connected, probe.called, (System.nanoTime()-start)/1_000_000,
                    connected ? (probe.called ? "连接正常，工具调用已验证" : "模型已响应，但未完成工具调用验证；请选用支持工具调用的模型") : "服务未返回有效内容");
            audit.record("advisor", "test-model", "platform-model", 1L, null, result);
            return ApiResponse.success(result);
        } catch (Exception e) {
            // 不返回供应商错误原文、URL 或模型返回内容，防止回显密钥。
            ConnectionResult result = new ConnectionResult(false, false, (System.nanoTime()-start)/1_000_000,
                    "连接失败，请检查服务地址、模型名、密钥、服务状态或超时时间");
            audit.record("advisor", "test-model", "platform-model", 1L, null, result);
            return ApiResponse.success(result);
        }
    }

    public record ConnectionResult(boolean connected, boolean toolCalling, long durationMs, String message) {}
    public static class ProbeTool {
        private boolean called;
        @Tool(name="platform_connection_probe", description="平台模型连接测试专用工具，返回固定字符串，不访问任何业务数据")
        public String probe() { called = true; return "connection-probe-ok"; }
    }
}
