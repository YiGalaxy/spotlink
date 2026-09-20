package com.bulk.trade.trading.controller;

import com.bulk.trade.shared.notify.TaskBroadcaster;
import com.bulk.trade.shared.security.SecurityUtils;
import com.bulk.trade.shared.web.ApiResponse;
import com.bulk.trade.trading.dto.TaskView;
import com.bulk.trade.trading.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * What the caller has to act on, and a stream that says when that changes.
 *
 * <p>The list and the stream are two halves of one feature. The list answers
 * "what is pending"; without the stream the answer is only true as of the last
 * time the user thought to reload — which is exactly the complaint that led
 * here. A seller who has to refresh to discover a buyer accepted is a seller
 * who finds out too late.
 *
 * <p>The same aggregation is reachable through the AI advisor, and that is
 * deliberate: the screen and the assistant must not disagree about what is
 * pending. Both call {@code TaskService}.
 */
@Tag(name = "待办", description = "当前需要本企业处理的事项，及变更推送")
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TaskBroadcaster taskBroadcaster;

    @Operation(summary = "我的待办",
            description = "跨模块汇总：待答复的摘牌、待签合同、待起草合同、待开始的交收、待确认的完成。按紧急程度排序。")
    @GetMapping
    public ApiResponse<List<TaskView>> mine() {
        return ApiResponse.success(taskService.findTasks(SecurityUtils.currentEnterpriseIdOrNull()));
    }

    @Operation(summary = "待办变更推送",
            description = "服务器单向推送。事件只说明「你的待办变了」，不带内容——客户端收到后重新拉取列表。")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        // The tenant comes from the token, so a connection can only ever
        // receive its own enterprise's events. There is no parameter here to
        // misuse, which is why this endpoint needs no permission beyond being
        // signed in.
        return taskBroadcaster.register(SecurityUtils.currentEnterpriseIdOrNull());
    }

    @Operation(summary = "推送连接状态")
    @GetMapping("/stream/stats")
    public ApiResponse<Map<String, Object>> streamStats() {
        return ApiResponse.success(Map.of(
                "connections", taskBroadcaster.connectionCount(SecurityUtils.currentEnterpriseIdOrNull())));
    }
}
