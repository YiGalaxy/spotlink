package com.bulk.trade.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bulk.trade.knowledge.entity.KnowledgeChunk;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** 知识分块持久化与检索；向量在 Java 中计算，关键词使用 MySQL ngram FULLTEXT。 */
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /** 查询未嵌入的分块，支持失败后续处理。 */
    @Select("""
            SELECT id, doc_id, chunk_index, content
            FROM t_knowledge_chunk
            WHERE embedding IS NULL
            ORDER BY id
            LIMIT #{limit}
            """)
    List<KnowledgeChunk> findUnembedded(@Param("limit") int limit);

    /** 写入已编码的嵌入向量。 */
    @Update("UPDATE t_knowledge_chunk SET embedding = #{embedding} WHERE id = #{id}")
    void updateEmbedding(@Param("id") Long id, @Param("embedding") byte[] embedding);

    /** 查询全部已嵌入分块，供 Java 计算相似度。 */
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

    /** 按 MySQL ngram FULLTEXT 相关度召回关键词候选。 */
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
