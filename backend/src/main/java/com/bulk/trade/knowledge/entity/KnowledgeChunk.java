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
 * <p>The embedding is handled outside MyBatis — pgvector needs a type the
 * mapper cannot guess — so it is not mapped as a column here. Retrieval goes
 * through hand-written SQL; this entity covers the ordinary reads.
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

    /** Not mapped: see the class comment. */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private float[] embedding;

    private OffsetDateTime createdAt;
}
