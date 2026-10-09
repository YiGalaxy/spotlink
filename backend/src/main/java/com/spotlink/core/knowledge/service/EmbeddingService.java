package com.spotlink.knowledge.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.document.MetadataMode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;

/** Spring AI 向量适配；本地 Ollama 与兼容 /v1/embeddings 的服务使用同一契约。 */
@Slf4j
@Service
public class EmbeddingService {
    public static final int DIMENSIONS = 1024;
    private final EmbeddingModel embeddingModel;
    private final String model;
    private final boolean enabled;
    private final int dimensions;
    private final String fingerprint;

    public EmbeddingService(
            @Value("${bulk.rag.embedding.base-url:http://ollama:11434}") String baseUrl,
            @Value("${bulk.rag.embedding.model:bge-m3}") String model,
            @Value("${bulk.rag.embedding.enabled:false}") boolean enabled,
            @Value("${bulk.rag.embedding.dimensions:1024}") int dimensions,
            @Value("${bulk.rag.embedding.api-key:ollama}") String key,
            @Value("${bulk.rag.embedding.revision:1}") String revision,
            @Value("${bulk.rag.embedding.timeout-seconds:45}") int timeout) {
        String endpoint = baseUrl.replaceAll("/+$", "");
        var http = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build());
        http.setReadTimeout(Duration.ofSeconds(Math.clamp(timeout, 1, 120)));
        var api = OpenAiApi.builder().baseUrl(endpoint).apiKey(key.isBlank() ? "local" : key)
                .embeddingsPath(endpoint.endsWith("/v1") ? "/embeddings" : "/v1/embeddings")
                .restClientBuilder(RestClient.builder().requestFactory(http))
                .responseErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
                    @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException {
                        return response.getStatusCode().value() >= 300;
                    }
                    @Override public void handleError(java.net.URI uri, org.springframework.http.HttpMethod method,
                            org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException {
                        throw new org.springframework.web.client.RestClientException("向量服务 HTTP " + response.getStatusCode().value());
                    }
                }).build();
        embeddingModel = new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model(model).build(), RetryTemplate.builder().maxAttempts(1).build());
        this.model = model;
        this.enabled = enabled;
        this.dimensions = Math.clamp(dimensions, 1, 4096);
        fingerprint = sha256(endpoint + "\n" + model + "\n" + this.dimensions + "\n" + revision);
    }

    public boolean isEnabled() { return enabled; }
    public int dimensions() { return dimensions; }
    public String fingerprint() { return fingerprint; }
    public String modelName() { return model; }
    public static String sha256(String text) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** 超时、服务故障、错维度或非法数值不生成假向量，由调用方降级检索。 */
    public float[] embed(String text) {
        if (!enabled || text == null || text.isBlank() || text.length() > 8000) return null;
        try {
            float[] vector = embeddingModel.embed(text);
            if (vector == null || vector.length != dimensions || cosineSimilarity(vector, vector) == 0) {
                log.warn("Embedding response failed dimension/numeric validation");
                return null;
            }
            return vector;
        } catch (Exception e) {
            log.warn("Embedding unavailable ({})", e.getClass().getSimpleName());
            return null;
        }
    }
    public static byte[] toBytes(float[] vector) {
        if (vector == null || vector.length == 0) return null;
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) buffer.putFloat(value);
        return buffer.array();
    }
    public static float[] fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length < Float.BYTES || bytes.length % Float.BYTES != 0) return new float[0];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) vector[i] = buffer.getFloat();
        return vector;
    }
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a.length == 0 || a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            if (!Float.isFinite(a[i]) || !Float.isFinite(b[i])) return 0;
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
