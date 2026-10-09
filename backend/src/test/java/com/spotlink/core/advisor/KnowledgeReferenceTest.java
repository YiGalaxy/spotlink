package com.spotlink.advisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotlink.advisor.dto.AdvisorKnowledgeReference;
import com.spotlink.advisor.dto.MessageView;
import com.spotlink.advisor.entity.AdvisorMessage;
import com.spotlink.advisor.tool.ToolCallRecorder;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KnowledgeReferenceTest {
    @Test void referencesAreScopedToOneTurnAndClearOnDrain() {
        var reference = new AdvisorKnowledgeReference(123L, "RULE", "真实原文", "v2", "已核验", 1, "库存不可质押");
        ToolCallRecorder.begin();
        ToolCallRecorder.knowledge(reference);
        assertThat(ToolCallRecorder.knowledge()).containsExactly(reference);
        ToolCallRecorder.drain();
        assertThat(ToolCallRecorder.knowledge()).isEmpty();
        ToolCallRecorder.begin();
        assertThat(ToolCallRecorder.knowledge()).isEmpty();
        ToolCallRecorder.drain();
    }
    @Test void historicalMessagePreservesRawTextVersionAndLargeIdentifier() throws Exception {
        var json = new ObjectMapper();
        var reference = new AdvisorKnowledgeReference(999999999999999999L, "RULE", "真实原文", "v2", "业务说明", 3, "<script>不是可执行内容</script>\n中文原文");
        var entity = new AdvisorMessage();
        entity.setKnowledgeJson(json.writeValueAsString(java.util.List.of(reference)));
        assertThat(entity.getKnowledgeJson()).contains("\"chunkId\":\"999999999999999999\"");
        assertThat(MessageView.from(entity, json).knowledge()).containsExactly(reference);
        entity.setKnowledgeJson(null);
        assertThat(MessageView.from(entity, json).knowledge()).isEmpty();
    }
}
