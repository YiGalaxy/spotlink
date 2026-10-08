package com.spotlink.knowledge;

import com.spotlink.knowledge.entity.KnowledgeChunk;
import com.spotlink.knowledge.mapper.KnowledgeChunkMapper;
import com.spotlink.knowledge.service.EmbeddingService;
import com.spotlink.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeServiceTest {

    private KnowledgeChunkMapper chunkMapper;
    private EmbeddingService embeddingService;
    private KnowledgeService knowledgeService;

    @BeforeEach
    void setUp() {
        chunkMapper = mock(KnowledgeChunkMapper.class);
        embeddingService = mock(EmbeddingService.class);
        knowledgeService = new KnowledgeService(chunkMapper, embeddingService);
    }

    @Test
    void fusesVectorAndKeywordRanksWithoutComparingTheirRawScores() {
        when(embeddingService.embed("保证金如何退还")).thenReturn(new float[]{1f, 0f});
        when(chunkMapper.loadEmbedded()).thenReturn(List.of(
                vectorRow(1L, "向量首位", new float[]{1f, 0f}),
                vectorRow(2L, "共同命中", new float[]{0.8f, 0.6f})));
        when(chunkMapper.searchByKeyword("保证金如何退还", 15)).thenReturn(List.of(
                row(2L, "共同命中"), row(1L, "向量首位")));

        List<KnowledgeService.Passage> results = knowledgeService.search("保证金如何退还", 4);

        assertThat(results).extracting(KnowledgeService.Passage::chunkId)
                .containsExactly(1L, 2L);
        assertThat(results.get(0).score()).isEqualTo(1.0 / 61 + 0.25 / 62);
        assertThat(results.get(1).score()).isEqualTo(1.0 / 62 + 0.25 / 61);
        assertThat(results.get(0).content()).isEqualTo("向量首位");
    }

    @Test
    void keywordSearchStillWorksWhenEmbeddingIsUnavailableAndTopKIsApplied() {
        when(embeddingService.embed("交收规则")).thenReturn(null);
        when(chunkMapper.searchByKeyword("交收规则", 15)).thenReturn(List.of(
                row(11L, "第一段"), row(12L, "第二段"), row(13L, "第三段")));

        List<KnowledgeService.Passage> results = knowledgeService.search("交收规则", 2);

        assertThat(results).extracting(KnowledgeService.Passage::chunkId)
                .containsExactly(11L, 12L);
        assertThat(results).extracting(KnowledgeService.Passage::content)
                .containsExactly("第一段", "第二段");
        verify(chunkMapper, never()).loadEmbedded();
    }

    @Test
    void blankQuestionReturnsNoResultsWithoutCallingRetrievers() {
        assertThat(knowledgeService.search("  \n", 4)).isEmpty();

        verify(embeddingService, never()).embed("  \n");
        verify(chunkMapper, never()).searchByKeyword("  \n", 15);
        verify(chunkMapper, never()).loadEmbedded();
    }

    private Map<String, Object> vectorRow(Long id, String content, float[] embedding) {
        Map<String, Object> row = row(id, content);
        row.put("embedding", EmbeddingService.toBytes(embedding));
        return row;
    }

    private Map<String, Object> row(Long id, String content) {
        return new java.util.HashMap<>(Map.of(
                "id", id,
                "docCode", "RULE-TEST",
                "docTitle", "测试规则",
                "content", content));
    }
}
