package com.bulk.trade.knowledge.service;

import com.bulk.trade.knowledge.entity.KnowledgeChunk;
import com.bulk.trade.knowledge.mapper.KnowledgeChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Retrieval over the platform's rule documents.
 *
 * <p><b>Hybrid search, fused by reciprocal rank.</b> Vector search finds
 * paraphrases; trigram similarity finds exact terms. Neither covers both:
 * "履约担保金" and "保证金" are semantically close but lexically distinct, while
 * a question naming a specific clause is answered by the literal string rather
 * than by embedding proximity. Fusing the two ranked lists by reciprocal rank —
 * {@code sum(1 / (k + rank))} — needs no score calibration between two
 * incomparable scales, which is precisely the problem a weighted sum would
 * create.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    /** Candidates pulled from each method before fusion. */
    private static final int CANDIDATES_PER_METHOD = 15;

    /** RRF damping constant from the original paper. Blunts the top ranks' advantage. */
    private static final int RRF_K = 60;

    /** Passages handed to the model. More context is not better: it dilutes. */
    private static final int DEFAULT_TOP_K = 4;

    private final KnowledgeChunkMapper chunkMapper;
    private final EmbeddingService embeddingService;

    /**
     * A retrieved passage with its provenance.
     *
     * @param docCode source document code, shown to the user as the citation
     * @param title   source document title
     * @param content the passage itself
     * @param score   fused score, for ranking and for explaining why this came back
     */
    public record Passage(Long chunkId, String docCode, String title, String content, double score) {
    }

    /**
     * Finds the passages most likely to answer a question.
     *
     * <p>Degrades rather than fails: with no embedding service the keyword half
     * still returns results, which is why ingestion stores unembedded chunks
     * instead of skipping them.
     */
    public List<Passage> search(String question, int topK) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        int limit = topK <= 0 ? DEFAULT_TOP_K : topK;

        List<Map<String, Object>> vectorHits = vectorSearch(question);
        List<Map<String, Object>> keywordHits = chunkMapper.searchByKeyword(
                question, CANDIDATES_PER_METHOD);

        Map<Long, Double> fused = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();

        accumulate(vectorHits, fused, byId);
        accumulate(keywordHits, fused, byId);

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
                            entry.getValue());
                })
                .toList();
    }

    /** Fills embeddings for chunks ingested before the service was available. */
    @Transactional
    public int embedPending(int limit) {
        List<KnowledgeChunk> pending = chunkMapper.findUnembedded(limit);
        int done = 0;
        for (KnowledgeChunk chunk : pending) {
            float[] vector = embeddingService.embed(chunk.getContent());
            if (vector == null) {
                // The service is down or the model is missing; stop rather than
                // hammer it once per chunk.
                log.warn("Embedding unavailable, stopping after {} chunk(s)", done);
                break;
            }
            chunkMapper.updateEmbedding(chunk.getId(), EmbeddingService.toVectorLiteral(vector));
            done++;
        }
        if (done > 0) {
            log.info("Embedded {} chunk(s) with {}", done, embeddingService.modelName());
        }
        return done;
    }

    public Map<String, Object> stats() {
        int all = chunkMapper.countAll();
        int pending = chunkMapper.countUnembedded();
        return Map.of(
                "chunks", all,
                "embedded", all - pending,
                "pending", pending,
                "model", embeddingService.modelName(),
                "embeddingEnabled", embeddingService.isEnabled());
    }

    // ------------------------------------------------------------------

    private List<Map<String, Object>> vectorSearch(String question) {
        float[] vector = embeddingService.embed(question);
        if (vector == null) {
            return List.of();
        }
        return chunkMapper.searchByVector(
                EmbeddingService.toVectorLiteral(vector), CANDIDATES_PER_METHOD);
    }

    /**
     * Adds one ranked list into the fused scores.
     *
     * <p>Rank, not raw score: a cosine similarity and a trigram similarity are
     * not comparable numbers, and blending them by weight would be a guess
     * dressed up as arithmetic.
     */
    private void accumulate(List<Map<String, Object>> hits,
                            Map<Long, Double> fused,
                            Map<Long, Map<String, Object>> byId) {
        List<Map<String, Object>> ordered = new ArrayList<>(hits);
        for (int rank = 0; rank < ordered.size(); rank++) {
            Map<String, Object> row = ordered.get(rank);
            Long id = ((Number) row.get("id")).longValue();
            fused.merge(id, 1.0 / (RRF_K + rank + 1), Double::sum);
            byId.putIfAbsent(id, row);
        }
    }
}
