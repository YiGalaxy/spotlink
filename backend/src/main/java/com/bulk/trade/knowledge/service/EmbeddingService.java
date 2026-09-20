package com.bulk.trade.knowledge.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

/**
 * Produces embeddings for retrieval.
 *
 * <p><b>Claude does not provide an embedding endpoint</b>, so this is the one
 * part of the advisor stack that is not Anthropic. The model is a local
 * BGE-M3 served by Ollama, chosen for three reasons: it is strong in Chinese,
 * it costs nothing per call, and it runs without network access — which matters
 * for a project meant to be demonstrable on any machine.
 *
 * <p>The interface is deliberately narrow: text in, vector out. Swapping in a
 * hosted embedding API is a change to this class and nothing else.
 */
@Slf4j
@Service
public class EmbeddingService {

    /** BGE-M3 output width. The database column is fixed to match. */
    public static final int DIMENSIONS = 1024;

    private final RestClient restClient;
    private final String model;
    private final boolean enabled;

    public EmbeddingService(
            @Value("${bulk.rag.embedding.base-url:http://localhost:11434}") String baseUrl,
            @Value("${bulk.rag.embedding.model:bge-m3}") String model,
            @Value("${bulk.rag.embedding.enabled:true}") boolean enabled) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.model = model;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Embeds one passage.
     *
     * @return the vector, or null when embedding is unavailable — callers store
     *         the chunk anyway so it stays findable by keyword. Half a corpus
     *         is better than none, and ingestion can be re-run later.
     */
    public float[] embed(String text) {
        if (!enabled || text == null || text.isBlank()) {
            return null;
        }
        try {
            EmbeddingResponse response = restClient.post()
                    .uri("/api/embeddings")
                    .body(Map.of("model", model, "prompt", text))
                    .retrieve()
                    .body(EmbeddingResponse.class);

            if (response == null || response.embedding() == null
                    || response.embedding().isEmpty()) {
                log.warn("Embedding service returned nothing for a {} character passage", text.length());
                return null;
            }
            if (response.embedding().size() != DIMENSIONS) {
                // A model swap with a different width would otherwise fail much
                // later, as a database error on a vector insert.
                log.error("Embedding model {} returned {} dimensions, expected {}",
                        model, response.embedding().size(), DIMENSIONS);
                return null;
            }
            return response.toArray();
        } catch (Exception e) {
            // Logged once per passage rather than thrown: a missing embedding
            // costs retrieval quality, it does not make the corpus unusable.
            log.warn("Embedding failed ({}): {}", model, e.getMessage());
            return null;
        }
    }

    /**
     * Packs a vector into the bytes stored in the {@code embedding} column.
     *
     * <p>Four bytes per dimension, little-endian, no header — the width is fixed
     * at {@link #DIMENSIONS} and a self-describing format would only be a way
     * for a corrupt row to look plausible.
     *
     * <p><b>Why bytes rather than the JSON array this used to be.</b> PostgreSQL
     * had a {@code vector} type and the value travelled as text to be cast in
     * SQL. MySQL has no such type, so the column is a {@code BLOB} and the
     * encoding is ours. Bytes over JSON because a float's shortest decimal
     * representation is not its value: round-tripping through text would make a
     * stored vector differ in the last bits from the one that was computed, and
     * the difference would be invisible right up until two identical questions
     * ranked differently.
     */
    public static byte[] toBytes(float[] vector) {
        if (vector == null || vector.length == 0) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    /** The inverse of {@link #toBytes}; empty for a null or malformed value. */
    public static float[] fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length < Float.BYTES) {
            return new float[0];
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    /**
     * Cosine similarity, higher meaning closer.
     *
     * <p>Computed here rather than in SQL because MySQL has no vector type and
     * no distance operator. That makes retrieval a scan of the chunk table: free
     * at this corpus size, and the reason a real deployment at scale would move
     * to a dedicated vector store. Recorded in the README rather than left for
     * someone to discover from a latency graph.
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public String modelName() {
        return model;
    }

    private record EmbeddingResponse(List<Float> embedding) {

        /** Jackson deserialises into a boxed list; the rest of the code wants primitives. */
        float[] toArray() {
            float[] values = new float[embedding.size()];
            for (int i = 0; i < values.length; i++) {
                Float value = embedding.get(i);
                values[i] = value == null ? 0f : value;
            }
            return values;
        }
    }
}
