package com.bulk.trade.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bulk.trade.knowledge.entity.KnowledgeChunk;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * Knowledge chunk persistence.
 *
 * <p>Retrieval is written by hand rather than expressed through the wrapper
 * API: the vector operator ({@code <=>}), the trigram similarity function and
 * the reciprocal-rank fusion are all PostgreSQL-side, and pretending otherwise
 * would mean pulling every row into Java to score it.
 */
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /**
     * Chunks with no embedding yet, so ingestion can be resumed after a failure.
     *
     * <p>Aliases are double-quoted: PostgreSQL folds unquoted identifiers to
     * lower case, so {@code AS docId} arrives as {@code docid} and the mapper —
     * which looks for {@code docId} — silently binds null.
     */
    @Select("""
            SELECT id,
                   doc_id AS "docId",
                   chunk_index AS "chunkIndex",
                   content
            FROM t_knowledge_chunk
            WHERE embedding IS NULL
            ORDER BY id
            LIMIT #{limit}
            """)
    List<KnowledgeChunk> findUnembedded(@Param("limit") int limit);

    /**
     * Stores an embedding.
     *
     * <p>The vector arrives as a string literal and is cast in SQL: the JDBC
     * driver has no mapping for pgvector's type, and adding one would mean a
     * dependency on a driver extension for a single column.
     */
    @Update("UPDATE t_knowledge_chunk SET embedding = CAST(#{vector} AS vector) WHERE id = #{id}")
    void updateEmbedding(@Param("id") Long id, @Param("vector") String vector);

    /**
     * Vector nearest neighbours.
     *
     * <p>{@code <=>} is cosine distance, ascending — smaller is closer.
     */
    @Select("""
            SELECT c.id, c.content,
                   c.doc_id AS "docId",
                   d.title AS "docTitle",
                   d.doc_code AS "docCode",
                   1 - (c.embedding <=> CAST(#{vector} AS vector)) AS score
            FROM t_knowledge_chunk c
            JOIN t_knowledge_doc d ON d.id = c.doc_id AND d.deleted = 0
            WHERE c.embedding IS NOT NULL
            ORDER BY c.embedding <=> CAST(#{vector} AS vector)
            LIMIT #{limit}
            """)
    List<Map<String, Object>> searchByVector(@Param("vector") String vector,
                                             @Param("limit") int limit);

    /**
     * Keyword recall by trigram similarity.
     *
     * <p>Exists because vector search alone answers the wrong question for
     * lexical queries. A question naming an exact term — "溢短装", "清算通" —
     * is answered by the literal string, while an embedding may place a
     * semantically similar but different term first.
     *
     * <p><b>{@code word_similarity}, not {@code similarity}, and no threshold
     * filter.</b> Two corrections to the obvious version of this query, both
     * learned by running it:
     *
     * <ul>
     *   <li>{@code similarity(a, b)} compares two strings of comparable length.
     *       A five-character question against a two-hundred-character passage
     *       scores low no matter how well it matches, so the {@code %} operator
     *       — which requires a score above 0.3 — returns nothing for exactly
     *       the queries this method exists to serve.
     *       {@code word_similarity} asks the right question instead: how well
     *       does the query match some part of the passage.</li>
     *   <li>Filtering by a threshold silently returns zero results when nothing
     *       clears it, and the caller cannot tell "no match" from "threshold
     *       too high". Ranking and taking the top N always returns the best
     *       available candidates, and fusion with the vector half decides what
     *       actually survives.</li>
     * </ul>
     */
    @Select("""
            SELECT c.id, c.content,
                   c.doc_id AS "docId",
                   d.title AS "docTitle",
                   d.doc_code AS "docCode",
                   word_similarity(#{query}, c.content) AS score
            FROM t_knowledge_chunk c
            JOIN t_knowledge_doc d ON d.id = c.doc_id AND d.deleted = 0
            ORDER BY word_similarity(#{query}, c.content) DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> searchByKeyword(@Param("query") String query,
                                              @Param("limit") int limit);

    @Select("SELECT count(*) FROM t_knowledge_chunk WHERE embedding IS NULL")
    int countUnembedded();

    @Select("SELECT count(*) FROM t_knowledge_chunk")
    int countAll();
}
