package com.bulk.trade.knowledge.service;

import com.bulk.trade.knowledge.entity.KnowledgeChunk;
import com.bulk.trade.knowledge.mapper.KnowledgeChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * Relative weight of each retrieval method in the fusion.
     *
     * <p><b>The keyword half is heavily discounted because it does not work
     * well on Chinese, and that was measured, not assumed.</b> For the question
     * "货到了发现重量不对怎么办", trigram similarity scored the correct passage
     * — the one defining 磅差 — at 0.0000, while scoring two unrelated passages
     * at 0.0769. pg_trgm builds character trigrams for spelling tolerance in
     * Latin scripts; a Chinese multi-character term produces few shared
     * trigrams, so the ranking is close to noise.
     *
     * <p>Rank fusion rewards a document that appears in <em>both</em> lists, so
     * that noise did not merely fail to help — it actively displaced the
     * correct answer by promoting unrelated passages to the top of the keyword
     * ranking. Weighting is a mitigation, not a fix: real Chinese keyword
     * search needs segmentation (zhparser or pg_jieba) producing a tsvector,
     * which is the documented next step rather than a claim that this is
     * already right.
     *
     * <p>The keyword path keeps real value as a <em>fallback</em>: when the
     * embedding service is down it is the only retrieval available, and poor
     * recall beats none.
     */
    private static final double VECTOR_WEIGHT = 1.0;
    private static final double KEYWORD_WEIGHT = 0.25;

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
            chunkMapper.updateEmbedding(chunk.getId(), EmbeddingService.toBytes(vector));
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

    /**
     * Nearest chunks by cosine similarity, scored in Java.
     *
     * <p>PostgreSQL did this in one indexed query with pgvector's {@code <=>}.
     * MySQL has no vector type, so the comparison moved here and this is now a
     * scan: every embedded chunk is read, scored, and sorted.
     *
     * <p><b>Correct, and honestly bounded.</b> At the current corpus — a dozen
     * passages — the scan is free and the ranking is exact, which is strictly
     * better than an approximate index would be. The failure mode is size, not
     * logic: at ten thousand chunks this reads ten thousand blobs per question.
     * The fix at that point is a vector store, not a bigger {@code LIMIT},
     * which is why {@code loadEmbedded} deliberately returns everything.
     *
     * <p>A chunk whose stored blob is the wrong width yields a similarity of
     * zero rather than an exception — a corrupt row should cost one candidate,
     * not the whole answer.
     */
    private List<Map<String, Object>> vectorSearch(String question) {
        float[] query = embeddingService.embed(question);
        if (query == null) {
            return List.of();
        }

        record Scored(Map<String, Object> row, double score) {
        }

        return chunkMapper.loadEmbedded().stream()
                .map(row -> new Scored(row,
                        EmbeddingService.cosineSimilarity(
                                query, EmbeddingService.fromBytes((byte[]) row.get("embedding")))))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(CANDIDATES_PER_METHOD)
                // The embedding is dropped here: it is the largest field by far
                // and nothing downstream reads it.
                .map(scored -> {
                    Map<String, Object> row = new LinkedHashMap<>(scored.row());
                    row.remove("embedding");
                    return row;
                })
                .toList();
    }

    /**
     * Adds one ranked list into the fused scores.
     *
     * <p>Rank, not raw score: a cosine similarity and a trigram similarity are
     * not comparable numbers, and blending those by weight would be a guess
     * dressed up as arithmetic. Weighting the <em>contributions</em> of each
     * list is a different thing — that is a statement about how much the list
     * can be trusted, which is measurable.
     */
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
