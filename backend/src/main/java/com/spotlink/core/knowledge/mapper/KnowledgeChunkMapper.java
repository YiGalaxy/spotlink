package com.spotlink.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spotlink.knowledge.entity.KnowledgeChunk;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** 知识分块持久化与检索；向量在 Java 中计算，关键词使用 MySQL ngram FULLTEXT。 */
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /** 查询未嵌入的分块，支持失败后续处理。 */
    @Select("""
            SELECT c.id, c.doc_id, c.chunk_index, c.content
            FROM t_knowledge_chunk c JOIN t_knowledge_doc d ON d.id=c.doc_id
            WHERE d.deleted=0 AND d.status=1 AND (c.embedding IS NULL OR c.embedding_fingerprint IS NULL
                OR c.embedding_fingerprint <> #{fingerprint} OR c.embedding_content_hash IS NULL
                OR c.embedding_content_hash <> SHA2(c.content,256))
            ORDER BY c.id
            LIMIT #{limit}
            """)
    List<KnowledgeChunk> findUnembedded(@Param("limit") int limit, @Param("fingerprint") String fingerprint);

    /** 写入已编码的嵌入向量。 */
    @Update("""
            UPDATE t_knowledge_chunk c JOIN t_knowledge_doc d ON d.id=c.doc_id
            SET c.embedding=#{embedding}, c.embedding_fingerprint=#{fingerprint},
                c.embedding_dimensions=#{dimensions}, c.embedding_content_hash=#{hash}, c.embedded_at=NOW(6)
            WHERE c.id=#{id} AND SHA2(c.content,256)=#{hash} AND d.deleted=0 AND d.status=1
            """)
    int updateEmbedding(@Param("id") Long id, @Param("embedding") byte[] embedding,
                        @Param("fingerprint") String fingerprint, @Param("dimensions") int dimensions, @Param("hash") String hash);

    /** 查询全部已嵌入分块，供 Java 计算相似度。 */
    @Select("""
            SELECT c.id, c.content, c.embedding,
                   c.doc_id AS `docId`,
                   d.title AS `docTitle`,
                   d.doc_code AS `docCode`
            FROM t_knowledge_chunk c
            JOIN t_knowledge_doc d ON d.id = c.doc_id AND d.deleted = 0 AND d.status=1
            WHERE c.embedding IS NOT NULL AND c.embedding_fingerprint=#{fingerprint}
                AND c.embedding_content_hash=SHA2(c.content,256) AND c.embedding_dimensions=#{dimensions}
            ORDER BY c.id LIMIT 5000
            """)
    List<Map<String, Object>> loadEmbedded(@Param("fingerprint") String fingerprint, @Param("dimensions") int dimensions);

    /** 按 MySQL ngram FULLTEXT 相关度召回关键词候选。 */
    @Select("""
            SELECT c.id, c.content,
                   c.doc_id AS `docId`,
                   d.title AS `docTitle`,
                   d.doc_code AS `docCode`,
                   MATCH(c.content) AGAINST(#{query} IN NATURAL LANGUAGE MODE) AS score
            FROM t_knowledge_chunk c
            JOIN t_knowledge_doc d ON d.id = c.doc_id AND d.deleted = 0 AND d.status=1
            WHERE MATCH(c.content) AGAINST(#{query} IN NATURAL LANGUAGE MODE) > 0
            ORDER BY score DESC, c.id ASC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> searchByKeyword(@Param("query") String query,
                                              @Param("limit") int limit);

    @Select("""
            SELECT count(*) FROM t_knowledge_chunk c JOIN t_knowledge_doc d ON d.id=c.doc_id
            WHERE d.deleted=0 AND d.status=1 AND (c.embedding IS NULL OR c.embedding_fingerprint IS NULL
                OR c.embedding_fingerprint <> #{fingerprint} OR c.embedding_content_hash IS NULL
                OR c.embedding_content_hash <> SHA2(c.content,256))
            """)
    int countUnembedded(@Param("fingerprint") String fingerprint);

    @Select("SELECT count(*) FROM t_knowledge_chunk c JOIN t_knowledge_doc d ON d.id=c.doc_id WHERE d.deleted=0 AND d.status=1")
    int countAll();
}
