package com.bulk.trade.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bulk.trade.knowledge.entity.KnowledgeChunk;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 知识分块的持久化与检索。
 *
 * <p><b>从 PostgreSQL 迁移而来，其中三条检索语句里有两条改变的是形态，而不只是
 * 语法。</b>
 *
 * <ul>
 *   <li><b>向量检索完全离开了 SQL。</b>PostgreSQL 有 pgvector 和 {@code <=>}
 *       余弦距离运算符；MySQL 两者都没有。现在最近邻的打分在
 *       {@code KnowledgeService} 里、针对 {@link #loadEmbedded()} 返回的那些行
 *       完成。在当前这个语料规模下，那不过是对几十行做一次扫描，几乎没有开销；它
 *       同时也是这次迁移中唯一不具备扩展性的部分，所以才把它写下来，而不是留给人
 *       从延迟曲线上发现。</li>
 *   <li><b>关键词检索改用了全文索引。</b>{@code word_similarity} 是一个 trigram
 *       函数；在这里的对应物是带 ngram 解析器的 {@code FULLTEXT} 索引，通过
 *       {@code MATCH ... AGAINST} 查询。解析器的选择并非无关紧要——MySQL 默认的
 *       分词器按空白切分，而中文没有空白，所以不用 ngram 的话，一整段中文就是一个
 *       词元，永远匹配不上任何东西。</li>
 * </ul>
 *
 * <p>别名都加了反引号。PostgreSQL 会把未加引号的标识符折叠为小写，所以在那边
 * {@code AS docId} 是写成双引号的；而在 MySQL 里，双引号包起来的是字符串字面量
 * 而不是标识符，那个别名会悄无声息地变成一个常量。
 */
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /**
     * 还没有嵌入向量的分块，以便失败之后能接着把入库做完。
     *
     * <p>这里不加别名：{@code map-underscore-to-camel-case} 已经处理了实体绑定，
     * 在 SQL 里再写一遍只会多出一件需要同步维护的事。
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
     * 写入一个嵌入向量。
     *
     * <p>传进来的向量已经是打包好的字节；至于为什么不用文本形式，见
     * {@code EmbeddingService.toBytes}。
     */
    @Update("UPDATE t_knowledge_chunk SET embedding = #{embedding} WHERE id = #{id}")
    void updateEmbedding(@Param("id") Long id, @Param("embedding") byte[] embedding);

    /**
     * 全部已嵌入的分块，供在 Java 中打分使用。
     *
     * <p>刻意不加过滤、不分页。在这里加 {@code LIMIT} 等于撒谎：这些行<em>就是</em>
     * 候选集，而截断它，会在最近邻恰好排在后面时把它丢掉。控制开销是调用方的事，
     * 而诚实地控制开销的办法是改用向量索引，不是去猜哪些行无关紧要。
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
     * 按全文相关度做关键词召回。
     *
     * <p>它之所以存在，是因为单靠向量检索会对字面类查询答非所问。一个点名某个确切
     * 词的问题——"溢短装"、"清算通"——要靠那个字面字符串来回答，而嵌入有可能把语义
     * 相近、但并非该词的另一个词排到前面。
     *
     * <p><b>依然不设分数阈值。</b>PostgreSQL 版本是吃过亏才明白这一点的：按阈值过滤
     * 时，一旦没有行越过阈值就什么都返回不了，而调用方分不清"没有匹配"和"阈值定高了"。
     * 排序后取前 N 个，总能返回当前最好的候选；至于哪些能留下来，由与向量那一路的融合
     * 来决定。
     *
     * <p><b>用自然语言模式，不用布尔模式。</b>布尔模式不会产生这里用来排序的相关度
     * 分数，而"是一份按名次排好的列表"正是这一路的全部意义——RRF 融合的正是名次。
     *
     * <p>有一个值得知道的限制：MySQL 的 ngram 解析器会丢弃短于
     * {@code ngram_token_size}（默认为 2）的词元，所以单字查询在本方法下什么都返回
     * 不了。向量那一路仍然能回答这类问题，因此后果是召回率下降，而不是走进死胡同。
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
