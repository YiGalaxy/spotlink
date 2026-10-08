package com.spotlink.trading.controller;

import com.spotlink.shared.notify.TaskBroadcaster;
import com.spotlink.shared.security.SecurityUtils;
import com.spotlink.shared.web.ApiResponse;
import com.spotlink.trading.dto.TaskView;
import com.spotlink.trading.service.TaskService;
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
 * 调用方需要处理的事项，以及一条在该事项变化时发出通知的推送流。
 *
 * <p>列表和推送流是同一个功能的两半。列表回答“有什么待办”；没有推送流，这个
 * 答案只在用户上次想起来刷新时的那一刻成立——而正是这种抱怨把设计引到了
 * 这里。一个必须靠刷新才能发现买方已经摘牌的卖方，就是一个知道得太晚的
 * 卖方。
 *
 * <p>同一套汇总结果也能通过 AI 顾问拿到，这是有意为之：界面和助手对“有何
 * 待办”的说法不能不一致。两者都调用 {@code TaskService}。
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
        // 租户来自令牌，因此一条连接只可能收到本企业自己的事件。这里没有任何
        // 可供误用的参数，这正是该端点除登录之外无需额外权限的原因。
        return taskBroadcaster.register(SecurityUtils.currentEnterpriseIdOrNull());
    }

    @Operation(summary = "推送连接状态")
    @GetMapping("/stream/stats")
    public ApiResponse<Map<String, Object>> streamStats() {
        return ApiResponse.success(Map.of(
                "connections", taskBroadcaster.connectionCount(SecurityUtils.currentEnterpriseIdOrNull())));
    }
}
