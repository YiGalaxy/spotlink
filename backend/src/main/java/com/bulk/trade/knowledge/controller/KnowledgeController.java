package com.bulk.trade.knowledge.controller;

import com.bulk.trade.knowledge.service.KnowledgeService;
import com.bulk.trade.shared.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "知识库", description = "平台规则检索（RAG）")
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @Operation(summary = "知识库状态",
            description = "查看分块数、已嵌入数与嵌入模型。pending 大于 0 时检索质量会打折。")
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.success(knowledgeService.stats());
    }

    @Operation(summary = "为未嵌入的分块补算向量",
            description = "嵌入服务曾经不可用时分块会被存下但不带向量，仍可用关键词检索。"
                    + "服务恢复后调用这个接口补齐。")
    @PostMapping("/embed-pending")
    public ApiResponse<Map<String, Object>> embedPending(@RequestParam(defaultValue = "200") int limit) {
        int done = knowledgeService.embedPending(limit);
        return ApiResponse.success(Map.of("embedded", done, "stats", knowledgeService.stats()));
    }

    @Operation(summary = "检索测试",
            description = "直接查看混合检索命中了哪些段落，用于调参和排查「为什么答错了」")
    @GetMapping("/search")
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
