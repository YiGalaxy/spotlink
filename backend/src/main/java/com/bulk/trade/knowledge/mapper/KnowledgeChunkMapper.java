package com.bulk.trade.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bulk.trade.knowledge.entity.KnowledgeChunk;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * Knowledge chunk persistence and retrieval.
 *
 * <p><b>Ported from PostgreSQL, where two of the three retrieval queries
 * changed shape rather than syntax.</b>
 *
 * <ul>
 *   <li><b>Vector search left SQL entirely.</b> PostgreSQL had pgvector and a
 *       {@code <=>} cosine-distance operator; MySQL has neither. Nearest
 *       neighbours are now scored in {@code KnowledgeService} over the rows
 *       {@link #loadEmbedded()} returns. At this corpus size that is a scan of
 *       a few dozen rows and costs nothing; it is also the one part of the port
 *       that does not scale, which is why it is written down rather than left
 *       to be discovered from a latency graph.</li>
 *   <li><b>Keyword search became a full-text index.</b> {@code word_similarity}
 *       was a trigram function; the equivalent here is a {@code FULLTEXT} index
 *       with the ngram parser, queried through {@code MATCH ... AGAINST}. The
 *       parser choice is not incidental — MySQL's default tokeniser splits on
 *       whitespace, Chinese has none, so without ngram an entire Chinese
 *       passage is one token and nothing ever matches it.</li>
 * </ul>
 *
 * <p>Aliases are backticked. PostgreSQL folded unquoted identifiers to lower
 * case, so {@code AS docId} was written in double quotes there; in MySQL a
 * double-quoted string is a literal rather than an identifier, and the alias
 * would quietly become a constant.
 */
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /**
     * Chunks with no embedding yet, so ingestion can be resumed after a failure.
     *
     * <p>No aliases: {@code map-underscore-to-camel-case} handles the entity
     * binding, and restating it in SQL is one more thing to keep in step.
     */
    @Select("""
            SELECT id, doc_id, chunk_index, content
            FROM t_knowledge_chunk
            WHERE embedding IS NULL
            ORDER BY id
            LIMIT #{limit}
            """)
    List<KnowledgeChunk> findUnembedded(@Param("limit") int limit);

    /**
     * Stores an embedding.
     *
     * <p>The vector arrives already packed into bytes; see
     * {@code EmbeddingService.toBytes} for why it is not text.
     */
    @Update("UPDATE t_knowledge_chunk SET embedding = #{embedding} WHERE id = #{id}")
    void updateEmbedding(@Param("id") Long id, @Param("embedding") byte[] embedding);

    /**
     * Every embedded chunk, for scoring in Java.
     *
     * <p>Deliberately unfiltered and unpaged. A {@code LIMIT} here would be a
     * lie: these rows <em>are</em> the candidate set, and truncating it would
     * drop the nearest neighbour whenever it happened to sort late. Bounding
     * the cost is the caller's problem, and the honest way to bound it is to
     * move to a vector index, not to guess which rows do not matter.
     */
    @Select("""
            SELECT c.id, c.content, c.embedding,
                   c.doc_id AS `docId`,
                   d.title AS `docTitle`,
                   d.doc_code AS `docCode`
            FROM t_knowledge_chunk c
            JOIN t_knowledge_doc d ON d.id = c.doc_id AND d.deleted = 0
            WHERE c.embedding IS NOT NULL
            """)
    List<Map<String, Object>> loadEmbedded();

    /**
     * Keyword recall by full-text relevance.
     *
     * <p>Exists because vector search alone answers the wrong question for
     * lexical queries. A question naming an exact term — "溢短装", "清算通" —
     * is answered by the literal string, while an embedding may place a
     * semantically similar but different term first.
     *
     * <p><b>No score threshold, still.</b> The PostgreSQL version learned this
     * the hard way: filtering by a threshold returns nothing when no row clears
     * it, and the caller cannot tell "no match" from "threshold too high".
     * Ranking and taking the top N always returns the best available
     * candidates, and fusion with the vector half decides what survives.
     *
     * <p><b>Natural language mode, not boolean.</b> Boolean mode does not
     * produce the relevance score this orders by, and being a ranked list is
     * the whole point of this half — RRF fuses ranks.
     *
     * <p>One limitation worth knowing: MySQL's ngram parser discards tokens
     * shorter than {@code ngram_token_size} (2 by default), so a single
     * character query returns nothing from this method. The vector half still
     * answers such a question, so the effect is degraded recall rather than a
     * dead end.
     */
    @Select("""
            SELECT c.id, c.content,
                   c.doc_id AS `docId`,
                   d.title AS `docTitle`,
                   d.doc_code AS `docCode`,
                   MATCH(c.content) AGAINST(#{query} IN NATURAL LANGUAGE MODE) AS score
            FROM t_knowledge_chunk c
            JOIN t_knowledge_doc d ON d.id = c.doc_id AND d.deleted = 0
            WHERE MATCH(c.content) AGAINST(#{query} IN NATURAL LANGUAGE MODE) > 0
            ORDER BY score DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> searchByKeyword(@Param("query") String query,
                                              @Param("limit") int limit);

    @Select("SELECT count(*) FROM t_knowledge_chunk WHERE embedding IS NULL")
    int countUnembedded();

    @Select("SELECT count(*) FROM t_knowledge_chunk")
    int countAll();
}
