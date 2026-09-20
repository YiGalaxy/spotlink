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
 * 为检索生成嵌入向量。
 *
 * <p><b>Claude 并不提供嵌入接口</b>，所以这是顾问这套技术栈里唯一不属于 Anthropic
 * 的部分。模型是由 Ollama 在本地提供的 BGE-M3，选它有三个理由：中文表现强、每次调用
 * 不花钱、无需联网即可运行——对一个要在任何机器上都能演示的项目来说，最后一点很重要。
 *
 * <p>接口刻意做得很窄：文本进，向量出。换成托管式嵌入 API 时，需要改的只有这个类，
 * 别无其他。
 */
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

    /**
     * 为一段文本生成嵌入向量。
     *
     * @return 向量；嵌入服务不可用时返回 null——调用方无论如何都会把分块存下来，
     *         这样它至少还能被关键词检索到。有半个语料库也好过没有，而且入库过程
     *         之后可以重跑。
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
                // 否则，换成维度不同的模型之后，错误会晚得多才暴露出来，而且是以
                // 向量写入时数据库报错的形式出现。
                log.error("Embedding model {} returned {} dimensions, expected {}",
                        model, response.embedding().size(), DIMENSIONS);
                return null;
            }
            return response.toArray();
        } catch (Exception e) {
            // 每段文本记一次日志，而不是抛异常：缺少嵌入向量损失的是检索质量，并不会
            // 让语料库变得不可用。
            log.warn("Embedding failed ({}): {}", model, e.getMessage());
            return null;
        }
    }

    /**
     * 把向量打包成存入 {@code embedding} 列的字节。
     *
     * <p>每个维度 4 个字节，小端序，不带头部——宽度固定在 {@link #DIMENSIONS}，
     * 而自描述的格式只会成为让一行损坏数据看起来合理的途径。
     *
     * <p><b>为什么用字节，而不是它以前用的那种 JSON 数组。</b>PostgreSQL 有
     * {@code vector} 类型，值以文本形式传输，再由 SQL 做类型转换。MySQL 没有这种
     * 类型，所以列是 {@code BLOB}，编码方式由我们自己定。这里选字节而不是 JSON，
     * 是因为浮点数的最短十进制表示并不等于它的值：经由文本往返之后，存下来的向量会与
     * 算出来的那个在末尾比特上不同，而这种差异一直不会被察觉，直到两个完全相同的问题
     * 排出了不同的名次。
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

    /**
     * 余弦相似度，值越大表示越接近。
     *
     * <p>在这里算而不是在 SQL 里算，是因为 MySQL 既没有向量类型也没有距离运算符。
     * 这让检索变成对分块表的一次扫描：在当前语料规模下没有开销，而这也是真正上规模的
     * 部署需要换成专用向量库的原因。这一点记录在 README 里，而不是留给谁从延迟曲线上
     * 去发现。
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
