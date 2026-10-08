package com.spotlink.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 一段可被检索的文本。
 *
 * <p><b>嵌入向量没有被映射；在 MySQL 下这仍然是正确的做法。</b>在 PostgreSQL 上这么
 * 做，是因为 pgvector 的类型没有对应的 MyBatis 处理器。而在这里，该列只是一个普通的
 * {@code BLOB}，处理器<em>本可以</em>映射它——但检索并不希望它被映射。打分是在 Java
 * 里一次性对全部已嵌入分块进行的，若让这些分块逐个经过实体管线把 4 KB 的 blob 还原成
 * {@code float[]}，那么为了回答一个问题就要把整个语料库都分配出来。检索路径直接读
 * blob，一次解码一个；而本实体负责的是普通读取，那些读取从不需要这个向量。
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

    /** 实体路径永远不会把它加载出来；见类注释。 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private float[] embedding;

    private OffsetDateTime createdAt;
}
