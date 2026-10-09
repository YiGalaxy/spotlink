package com.spotlink.knowledge;

import com.spotlink.knowledge.mapper.KnowledgeChunkMapper;
import com.spotlink.knowledge.service.EmbeddingService;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
@SpringBootTest
@Transactional
class KnowledgeIndexIntegrationTest {
    @Autowired KnowledgeChunkMapper chunks;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.apache.ibatis.session.SqlSession session;
    @Test void vectorRequiresCurrentProviderContentAndPublishedDocument() {
        long doc = 999_710_001L, chunk = 999_710_002L;
        jdbc.update("INSERT INTO t_knowledge_doc(id,doc_code,title) VALUES(?,?,?)", doc, "INDEX-" + UUID.randomUUID(), "向量版本验收");
        String content = "向量索引版本、原文与停用验收";
        jdbc.update("INSERT INTO t_knowledge_chunk(id,doc_id,chunk_index,content) VALUES(?,?,0,?)", chunk, doc, content);
        String fingerprint = EmbeddingService.sha256(UUID.randomUUID().toString());
        assertThat(chunks.findUnembedded(200, fingerprint)).extracting(com.spotlink.knowledge.entity.KnowledgeChunk::getId).contains(chunk);
        assertThat(chunks.updateEmbedding(chunk, EmbeddingService.toBytes(new float[]{1, 0}), fingerprint, 2, EmbeddingService.sha256(content))).isEqualTo(1);
        assertThat(chunks.loadEmbedded(fingerprint, 2)).extracting(row -> ((Number)row.get("id")).longValue()).contains(chunk);
        assertThat(chunks.loadEmbedded("different-provider", 2)).isEmpty();
        assertThat(chunks.loadEmbedded(fingerprint, 3)).isEmpty();
        jdbc.update("UPDATE t_knowledge_chunk SET content='原文已修改' WHERE id=?", chunk);
        session.clearCache();
        assertThat(chunks.loadEmbedded(fingerprint, 2)).isEmpty();
        assertThat(chunks.updateEmbedding(chunk, EmbeddingService.toBytes(new float[]{1, 0}), fingerprint, 2, EmbeddingService.sha256(content))).isZero();
        jdbc.update("UPDATE t_knowledge_doc SET status=0 WHERE id=?", doc);
        session.clearCache();
        assertThat(chunks.findUnembedded(200, fingerprint)).extracting(com.spotlink.knowledge.entity.KnowledgeChunk::getId).doesNotContain(chunk);
        assertThat(chunks.searchByKeyword("原文已修改", 10)).extracting(row -> ((Number)row.get("id")).longValue()).doesNotContain(chunk);
    }
}
