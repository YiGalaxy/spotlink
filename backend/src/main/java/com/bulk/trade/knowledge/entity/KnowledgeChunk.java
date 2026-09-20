package com.bulk.trade.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * A retrievable passage.
 *
 * <p><b>The embedding is not mapped, and under MySQL that is still the right
 * call.</b> On PostgreSQL it was because pgvector's type had no MyBatis
 * handler. Here the column is a plain {@code BLOB} that a handler <em>could</em>
 * map — but retrieval does not want it mapped. Scoring happens in Java over
 * every embedded chunk at once, and hydrating a 4 KB blob into a {@code float[]}
 * for each of them through the entity pipeline would allocate the whole corpus
 * to answer one question. The retrieval path reads blobs directly and decodes
 * them one at a time; this entity covers the ordinary reads, which never need
 * the vector.
 */
@Getter
@Setter
@TableName("t_knowledge_chunk")
public class KnowledgeChunk {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long docId;
    private Integer chunkIndex;
    private String content;
    private Integer tokenCount;

    /** Never hydrated by the entity path; see the class comment. */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private float[] embedding;

    private OffsetDateTime createdAt;
}
