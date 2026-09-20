package com.bulk.trade.knowledge.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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
     * Converts a vector to the literal pgvector accepts.
     *
     * <p>Passed as text and cast in SQL because the JDBC driver has no type
     * mapping for {@code vector}; adding one would mean depending on a driver
     * extension for a single column.
     */
    public static String toVectorLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
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
