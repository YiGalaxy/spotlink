package com.spotlink.shared;

import com.spotlink.admin.service.AdminAuditService;
import com.spotlink.advisor.entity.Conversation;
import com.spotlink.advisor.mapper.ConversationMapper;
import com.spotlink.shared.audit.AuditLog;
import com.spotlink.shared.audit.mapper.AuditLogMapper;
import com.spotlink.identity.mapper.UserMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest
@Transactional
class PersistenceQueryIntegrationTest {
    @Autowired AuditLogMapper audit;
    @Autowired AdminAuditService auditService;
    @Autowired ConversationMapper conversations;
    @Autowired UserMapper users;

    @Test void auditFiltersAreBoundAndLimitAndDescendingOrderRemainCorrect() {
        String module = "persistence-query-test";
        for (int i = 0; i < 3; i++) {
            AuditLog row = new AuditLog();
            row.setModule(module); row.setAction(i == 2 ? "reject" : "approve");
            row.setUsername("scope-member-" + i); row.setSuccess(i != 2);
            audit.insert(row);
        }
        var latest = auditService.search("  " + module + "  ", null, "member", null, 1);
        assertThat(latest).hasSize(1);
        assertThat(latest.getFirst().action()).isEqualTo("reject");
        assertThat(auditService.search(module, "approve", "member", true, 200)).hasSize(2);
        assertThat(auditService.search(module, null, null, false, 0)).hasSize(1);
        assertThat(auditService.search(module + "' OR 1=1 --", null, null, null, 200)).isEmpty();
        assertThat(auditService.search(module, null, "' OR 1=1 --", null, 200)).isEmpty();
    }

    @Test void accountQueryKeepsSoftDeletedRowsOutAndCannotInjectSql() {
        assertThat(users.findByUsername("seller01")).isNotNull();
        assertThat(users.findByUsername("seller01' OR 1=1 --")).isNull();
        var seller = users.findByUsername("seller01");
        assertThat(users.findEnterpriseMembers(seller.getEnterpriseId(), true, 200))
                .allMatch(user -> seller.getEnterpriseId().equals(user.getEnterpriseId()));
        users.deleteById(seller.getId());
        assertThat(users.findByUsername("seller01")).isNull();
        assertThat(users.searchAccounts("seller01", null)).isEmpty();
    }

    @Test void conversationAtomicIncrementPreservesOwnershipAndSoftDelete() {
        Conversation conversation = new Conversation();
        conversation.setUserId(81101L); conversation.setEnterpriseId(81201L);
        conversation.setTitle("新会话"); conversation.setMessageCount(0);
        conversations.insert(conversation);
        assertThat(conversations.recordCompletedTurn(conversation.getId(), OffsetDateTime.now(), "首次问题")).isEqualTo(1);
        assertThat(conversations.recordCompletedTurn(conversation.getId(), OffsetDateTime.now(), "重复首轮不改标题")).isEqualTo(1);
        var updated = conversations.selectById(conversation.getId());
        assertThat(updated.getMessageCount()).isEqualTo(4);
        assertThat(updated.getTitle()).isEqualTo("首次问题");
        assertThat(conversations.findOwned(81101L, 81201L, true)).hasSize(1);
        assertThat(conversations.findOwned(81101L, 81202L, true)).isEmpty();
        assertThat(conversations.findOwned(81102L, 81201L, true)).isEmpty();
        conversations.deleteById(conversation.getId());
        assertThat(conversations.recordCompletedTurn(conversation.getId(), OffsetDateTime.now(), null)).isZero();
        assertThat(conversations.findOwned(81101L, 81201L, true)).isEmpty();
    }
}
