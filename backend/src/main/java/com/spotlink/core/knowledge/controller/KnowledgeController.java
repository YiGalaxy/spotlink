package com.spotlink.knowledge.controller;

import com.spotlink.knowledge.service.KnowledgeService;
import com.spotlink.shared.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 助手据以作答的规则语料库。
 *
 * <p><b>已挪到运营后台之下，路径也随受众一起改了。</b>它原先位于
 * {@code /api/knowledge}，不带令牌就能读，理由是"交易场所会公开自己的规则"。那条
 * 理由针对的是规则本身；而对这里的接口它从来不成立。这些接口提供的是运营视角下的语料
 * 库——嵌入了多少分块、检索给某个问题打了多少分，以及一个让服务端为每个分块调用一次
 * 嵌入模型的按钮。最后那个按钮原本任何已登录会员都能触发，而它会在一个事务里对 Ollama
 * 发起一连串顺序 HTTP 调用。这是一个运维面，现在它被挡在点名它的那个权限之后。
 *
 * <p>顾问不受影响：它在进程内直接读 {@link KnowledgeService}，从不走 HTTP。
 */
@Tag(name = "知识库", description = "平台规则检索（RAG），运营维护用")
@RestController
@RequestMapping("/api/admin/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @Operation(summary = "知识库状态",
            description = "分块数、已嵌入数与嵌入模型。pending 大于 0 时向量检索会漏掉这部分分块。")
    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('admin:knowledge')")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.success(knowledgeService.stats());
    }

    @Operation(summary = "为未嵌入的分块补算向量",
            description = "嵌入服务曾经不可用时分块会被存下但不带向量，仍可用关键词检索。"
                    + "服务恢复后调用这个接口补齐。逐条调用嵌入模型，耗时随待补数量增长。")
    @PostMapping("/embed-pending")
    @PreAuthorize("hasAuthority('admin:knowledge:embed')")
    public ApiResponse<Map<String, Object>> embedPending(@RequestParam(defaultValue = "200") int limit) {
        int done = knowledgeService.embedPending(limit);
        return ApiResponse.success(Map.of("embedded", done, "stats", knowledgeService.stats()));
    }

    @Operation(summary = "检索测试",
            description = "查看混合检索命中了哪些段落，用于调参和排查「为什么答错了」。"
                    + "顾问的每次回答都走同一条检索路径，所以这里的结果就是它看到的东西。")
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('admin:knowledge')")
    public ApiResponse<List<Map<String, Object>>> search(
            @RequestParam String question,
            @RequestParam(defaultValue = "5") int topK) {
        return ApiResponse.success(knowledgeService.search(question, topK).stream()
                .map(p -> Map.<String, Object>of(
                        "docCode", p.docCode(),
                        "title", p.title(),
                        "score", String.format("%.6f", p.score()),
                        "content", p.content()))
                .toList());
    }
}
