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
 * 在平台规则文档之上的检索。
 *
 * <p><b>混合检索，按加权倒数名次融合。</b>向量检索能找到换了说法的表达；MySQL
 * ngram FULLTEXT 能找到确切的词。两者都覆盖不了全部："履约担保金"和"保证金"语义
 * 上接近，字面上却是两回事；而一个点名某条具体条款的问题，靠的是字面字符串，而不是
 * 嵌入上的接近程度。
 * 把两份按名次排好的列表用倒数名次融合——{@code sum(1 / (k + rank))}——就不需要在
 * 两个不可比的量纲之间做分数校准，而加权求和恰恰会引出这个问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    /** 融合之前，从每种检索方式各取多少个候选。 */
    private static final int CANDIDATES_PER_METHOD = 15;

    /** 原论文中的 RRF 阻尼常数。用来削弱靠前名次的优势。 */
    private static final int RRF_K = 60;

    /**
     * 融合时每种检索方式的相对权重。
     *
     * <p><b>关键词这一路被大幅打折，因为它在中文上效果不好，而这是量出来的，不是
     * 猜的。</b>对于问题"货到了发现重量不对怎么办"，trigram 相似度给正确的那一段
     * ——也就是定义磅差的那一段——打出的分数是 0.0000，却给两段毫不相干的文本打了
     * 0.0769。pg_trgm 构造的是字符级 trigram，为的是容忍拉丁文字里的拼写差异；而一个
     * 中文多字词能产生的公共 trigram 很少，所以这个排序接近于噪声。
     *
     * <p>名次融合会奖励<em>同时</em>出现在两份列表里的文档，所以那些噪声不只是没帮上
     * 忙——它还把不相干的段落推到关键词排序的顶部，从而把正确答案挤了下去。加权是一种
     * 缓解，不是修复：真正的中文关键词检索需要分词（zhparser 或 pg_jieba）来产生
     * tsvector，这是记录在案的下一步，而不是断言现在这样已经对了。
     *
     * <p>关键词这一路作为<em>兜底</em>仍有实际价值：嵌入服务挂掉时，它是唯一可用的
     * 检索手段，而召回差总好过没有。
     */
    private static final double VECTOR_WEIGHT = 1.0;
    private static final double KEYWORD_WEIGHT = 0.25;

    /** 交给模型的段落数。上下文不是越多越好：多了会被稀释。 */
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
    public record Passage(Long chunkId, String docCode, String title, String content, double score) {
    }

    /**
     * 找出最有可能回答某个问题的段落。
     *
     * <p>是降级而不是失败：没有嵌入服务时，关键词那一路仍然能返回结果，这正是入库时
     * 要把未能嵌入的分块存下来、而不是跳过的原因。
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

    /** 为嵌入服务还不可用时就已入库的分块补算向量。 */
    @Transactional
    public int embedPending(int limit) {
        List<KnowledgeChunk> pending = chunkMapper.findUnembedded(limit);
        int done = 0;
        for (KnowledgeChunk chunk : pending) {
            float[] vector = embeddingService.embed(chunk.getContent());
            if (vector == null) {
                // 服务挂了，或者模型不存在；就此停下，而不是为每一个分块都硬砸一次。
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
     * 按余弦相似度取最近的若干分块，在 Java 中打分。
     *
     * <p>PostgreSQL 用 pgvector 的 {@code <=>} 一条走索引的查询就能做完。MySQL 没有
     * 向量类型，所以比较挪到了这里，于是它变成了一次扫描：读出每一个已嵌入的分块，
     * 逐个打分再排序。
     *
     * <p><b>结果正确，边界也交代得诚实。</b>在当前语料下——十几段文本——这次扫描没有
     * 开销，排序也是精确的，这严格优于近似索引所能给出的结果。它的失效模式是数据规模，
     * 不是逻辑：到了一万个分块，每问一个问题就要读一万个 blob。到那时候该做的是上向量
     * 库，而不是把 {@code LIMIT} 调大——这正是 {@code loadEmbedded} 刻意返回全部的
     * 原因。
     *
     * <p>某个分块存下来的 blob 宽度不对时，得到的是相似度为零，而不是抛异常——一行坏
     * 数据应该只损失一个候选，而不是毁掉整个答案。
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
                // 在这里把嵌入向量丢掉：它是体积上遥遥领先的最大字段，而下游没有任何
                // 地方会读它。
                .map(scored -> {
                    Map<String, Object> row = new LinkedHashMap<>(scored.row());
                    row.remove("embedding");
                    return row;
                })
                .toList();
    }

    /**
     * 把一份排好序的列表并入融合分数。
     *
     * <p>用的是名次，不是原始分数：余弦相似度和 trigram 相似度不是可比的数字，按权重
     * 把它们混起来，等于把一次猜测包装成算术。而给每份列表的<em>贡献</em>加权是另一回
     * 事——那是在说明这份列表有多可信，而这是可以量化的。
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
