package com.spotlink.knowledge.service;

import com.spotlink.knowledge.entity.KnowledgeChunk;
import com.spotlink.knowledge.mapper.KnowledgeChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 平台规则文档的混合检索：向量召回语义近似，ngram 召回关键词，再按 RRF 融合。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    /** 融合之前，从每种检索方式各取多少个候选。 */
    private static final int CANDIDATES_PER_METHOD = 15;

    /** RRF 阻尼常数。 */
    private static final int RRF_K = 60;

    /** 关键词权重较低；嵌入不可用时仍可作为降级检索。 */
    private static final double VECTOR_WEIGHT = 1.0;
    private static final double KEYWORD_WEIGHT = 0.25;

    /** 默认返回的段落数。 */
    private static final int DEFAULT_TOP_K = 4;

    private final KnowledgeChunkMapper chunkMapper;
    private final EmbeddingService embeddingService;

    /**
     * 一段被检索到的文本，连同它的出处。
     *
     * @param docCode 来源文档编号，作为引用展示给用户
     * @param title   来源文档标题
     * @param content 段落本身
     * @param score   融合后的分数，用于排序，也用于解释它为什么会被返回
     */
    public record Passage(Long chunkId, String docCode, String title, String content, double score,
                          String version, String source, int chunkIndex) {
    }

    /** 检索相关段落；嵌入不可用时降级为关键词检索。 */
    public List<Passage> search(String question, int topK) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        int limit = topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, 8);
        if (question.length() > 2000) return List.of();

        List<Map<String, Object>> vectorHits = vectorSearch(question);
        List<Map<String, Object>> keywordHits = chunkMapper.searchByKeyword(
                question, CANDIDATES_PER_METHOD);

        Map<Long, Double> fused = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();

        accumulate(vectorHits, VECTOR_WEIGHT, fused, byId);
        accumulate(keywordHits, KEYWORD_WEIGHT, fused, byId);

        return fused.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> {
                    Map<String, Object> row = byId.get(entry.getKey());
                    return new Passage(
                            entry.getKey(),
                            String.valueOf(row.get("docCode")),
                            String.valueOf(row.get("docTitle")),
                            String.valueOf(row.get("content")),
                            entry.getValue(), row.get("version") == null ? "v1" : row.get("version").toString(),
                            row.get("source") == null ? "平台知识库" : row.get("source").toString(),
                            row.get("chunkIndex") == null ? 0 : ((Number) row.get("chunkIndex")).intValue());
                })
                .toList();
    }

    /** 为嵌入服务还不可用时就已入库的分块补算向量。 */
    public int embedPending(int limit) {
        List<KnowledgeChunk> pending = chunkMapper.findUnembedded(Math.clamp(limit, 1, 200), embeddingService.fingerprint());
        int done = 0;
        for (KnowledgeChunk chunk : pending) {
            float[] vector = embeddingService.embed(chunk.getContent());
            if (vector == null) {
                // 嵌入服务不可用时停止，避免重复失败。
                log.warn("Embedding unavailable, stopping after {} chunk(s)", done);
                break;
            }
            // 每块独立提交；HTTP 不占数据库事务，已成功分块在中断后仍可复用。
            done += chunkMapper.updateEmbedding(chunk.getId(), EmbeddingService.toBytes(vector),
                    embeddingService.fingerprint(), embeddingService.dimensions(), EmbeddingService.sha256(chunk.getContent()));
        }
        if (done > 0) {
            log.info("Embedded {} chunk(s) with {}", done, embeddingService.modelName());
        }
        return done;
    }

    public Map<String, Object> stats() {
        int all = chunkMapper.countAll();
        int pending = chunkMapper.countUnembedded(embeddingService.fingerprint());
        return Map.of(
                "chunks", all,
                "embedded", all - pending,
                "pending", pending,
                "model", embeddingService.modelName(),
                "embeddingEnabled", embeddingService.isEnabled(),
                "dimensions", embeddingService.dimensions(), "indexFingerprint", embeddingService.fingerprint());
    }

    // ------------------------------------------------------------------

    /** 在 Java 中扫描已嵌入分块并按余弦相似度排序。 */
    private List<Map<String, Object>> vectorSearch(String question) {
        float[] query = embeddingService.embed(question);
        if (query == null) {
            return List.of();
        }

        record Scored(Map<String, Object> row, double score) {
        }

        return chunkMapper.loadEmbedded(embeddingService.fingerprint(), embeddingService.dimensions()).stream()
                .map(row -> new Scored(row,
                        EmbeddingService.cosineSimilarity(
                                query, EmbeddingService.fromBytes((byte[]) row.get("embedding")))))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(CANDIDATES_PER_METHOD)
                // 下游只需要元数据和正文，不再携带向量。
                .map(scored -> {
                    Map<String, Object> row = new LinkedHashMap<>(scored.row());
                    row.remove("embedding");
                    return row;
                })
                .toList();
    }

    /** 将一份有序结果按加权 RRF 累加。 */
    private void accumulate(List<Map<String, Object>> hits,
                            double weight,
                            Map<Long, Double> fused,
                            Map<Long, Map<String, Object>> byId) {
        List<Map<String, Object>> ordered = new ArrayList<>(hits);
        for (int rank = 0; rank < ordered.size(); rank++) {
            Map<String, Object> row = ordered.get(rank);
            Long id = ((Number) row.get("id")).longValue();
            fused.merge(id, weight / (RRF_K + rank + 1), Double::sum);
            byId.putIfAbsent(id, row);
        }
    }
}
