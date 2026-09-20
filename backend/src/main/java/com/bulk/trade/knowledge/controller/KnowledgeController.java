package com.bulk.trade.knowledge.controller;

import com.bulk.trade.knowledge.service.KnowledgeService;
import com.bulk.trade.shared.web.ApiResponse;
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
 * The rule corpus the assistant answers from.
 *
 * <p><b>Moved under the console, and the path changed with the audience.</b> It
 * used to sit at {@code /api/knowledge} and be readable without a token, on the
 * argument that a venue publishes its rules. That argument is about the rules;
 * it was never true of this. What these endpoints serve is the operator's view
 * of the corpus — how many chunks are embedded, what the retrieval scored a
 * question, and a button that makes the server call an embedding model once per
 * chunk. That last one was reachable by any signed-in member, and it runs
 * sequential HTTP calls to Ollama inside one transaction. It is a maintenance
 * surface, and it now sits behind the authority that names it.
 *
 * <p>The advisor is unaffected: it reads {@link KnowledgeService} in process,
 * never over HTTP.
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
