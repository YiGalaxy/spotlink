package com.bulk.trade.knowledge.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

/** 通过 Ollama BGE-M3 为检索生成嵌入向量。 */
@Slf4j
@Service
public class EmbeddingService {

    /** BGE-M3 的输出维度。数据库列固定为这个值以与之匹配。 */
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

    /** 生成向量；服务不可用时返回 null，由调用方降级为关键词检索。 */
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
                // 提前拦截维度不匹配，避免写入后才失败。
                log.error("Embedding model {} returned {} dimensions, expected {}",
                        model, response.embedding().size(), DIMENSIONS);
                return null;
            }
            return response.toArray();
        } catch (Exception e) {
            // 单个分块失败不应阻断知识库入库。
            log.warn("Embedding failed ({}): {}", model, e.getMessage());
            return null;
        }
    }

    /** 将 float 向量按小端序编码为 BLOB。 */
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

    /** {@link #toBytes} 的逆运算；传入 null 或格式非法的值时返回空数组。 */
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

    /** 计算余弦相似度；长度不匹配或零向量返回 0。 */
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

        /** Jackson 反序列化得到的是装箱类型的列表；而其余代码要的是基本类型。 */
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
