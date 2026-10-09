package com.spotlink.advisor;

import com.spotlink.advisor.agent.AdvisorAgent;
import com.spotlink.advisor.agent.AgentResult;
import com.spotlink.advisor.agent.ConversationTurn;
import com.spotlink.advisor.entity.AdvisorMessage;
import com.spotlink.advisor.mapper.AdvisorMessageMapper;
import com.spotlink.advisor.mapper.ConversationMapper;
import com.spotlink.advisor.service.ConversationService;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.security.LoginUser;
import com.spotlink.shared.web.ResultCode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 模型使用固定替身；持久化、短事务、历史读取和 Redis 请求锁使用真实隔离服务。 */
@Tag("integration")
@SpringBootTest
class ConversationPersistenceTest {
    @Autowired ConversationService service;
    @Autowired ConversationMapper conversations;
    @Autowired AdvisorMessageMapper messages;
    @MockitoBean AdvisorAgent agent;
    private final List<Long> ids = new ArrayList<>();
    private LoginUser user;

    @BeforeEach void begin() {
        user = LoginUser.builder().userId(System.nanoTime()).enterpriseId(899_130_101L)
                .username("会话隔离验收").permissions(Set.of()).build();
        authenticate(user);
    }

    @AfterEach void clean() {
        SecurityContextHolder.clearContext();
        for (Long id : ids) {
            messages.findConversationMessages(id).forEach(row -> messages.deleteById(row.getId()));
            conversations.deleteById(id);
        }
    }

    @Test void completeTurnPersistsBothMessagesAndReplaysOnlyThisConversation() {
        long id = create();
        when(agent.run(anyString(), anyList(), any(), nullable(String.class)))
                .thenReturn(AgentResult.of("已查到本企业库存。", List.of(), 12, 6));
        service.sendMessage(id, "我有哪些库存", user);
        service.sendMessage(id, "哪些可用", user);
        var detail = service.get(id, user.getUserId());
        assertThat(detail.messages()).extracting(row -> row.role()).containsExactly("user", "assistant", "user", "assistant");
        assertThat(conversations.selectById(id).getMessageCount()).isEqualTo(4);
        assertThat(detail.title()).isEqualTo("我有哪些库存");
        verify(agent).run(eq("哪些可用"), eq(List.of(ConversationTurn.user("我有哪些库存"),
                new ConversationTurn("assistant", "已查到本企业库存。"))), eq(user), nullable(String.class));
        long separate = create();
        service.sendMessage(separate, "独立会话", user);
        verify(agent).run(eq("独立会话"), eq(List.of()), eq(user), nullable(String.class));
    }

    @Test void modelFailureLeavesNoQuestionOrCounterAndNextTryCanSucceed() {
        long id = create();
        when(agent.run(anyString(), anyList(), any(), nullable(String.class)))
                .thenThrow(BusinessException.of(ResultCode.ADVISOR_UNAVAILABLE))
                .thenReturn(AgentResult.of("恢复后的回答。", List.of(), null, null));
        assertThatThrownBy(() -> service.sendMessage(id, "失败问题", user)).isInstanceOf(BusinessException.class);
        assertEmpty(id);
        service.sendMessage(id, "重试问题", user);
        assertThat(service.get(id, user.getUserId()).messages()).hasSize(2);
    }

    @Test void secondInsertFailureRollsBackQuestionTitleAndCounter() {
        long id = create();
        // 故意返回空答案，使真实数据库拒绝第二条 NOT NULL 写入。
        when(agent.run(anyString(), anyList(), any(), nullable(String.class)))
                .thenReturn(AgentResult.of(null, List.of(), null, null));
        assertThatThrownBy(() -> service.sendMessage(id, "不能留下半条问答", user)).isInstanceOf(DataAccessException.class);
        assertEmpty(id);
    }

    @Test void deletionWhileModelIsRunningDiscardsTheResult() {
        long id = create();
        when(agent.run(anyString(), anyList(), any(), nullable(String.class))).thenAnswer(call -> {
            service.delete(id, user.getUserId());
            return AgentResult.of("迟到回答", List.of(), null, null);
        });
        assertThatThrownBy(() -> service.sendMessage(id, "模型运行期间删除", user)).isInstanceOf(BusinessException.class);
        assertThat(messages.findConversationMessages(id)).isEmpty();
    }

    @Test void colleagueInSameEnterpriseCannotReadEditSendOrDelete() {
        long id = create();
        LoginUser colleague = LoginUser.builder().userId(user.getUserId() + 1).enterpriseId(user.getEnterpriseId())
                .username("同企业其他人").permissions(Set.of()).build();
        authenticate(colleague);
        assertThat(service.listMine(colleague.getUserId())).isEmpty();
        assertThatThrownBy(() -> service.get(id, colleague.getUserId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.updateContext(id, colleague.getUserId(), "篡改")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.delete(id, colleague.getUserId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.sendMessage(id, "读同事会话", colleague)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(agent);
    }

    @Test void historyKeepsLatestTenCompleteTurnsInOrder() {
        long id = create();
        for (int i = 0; i < 12; i++) {
            insert(id, "user", "历史问题" + i);
            insert(id, "assistant", "历史回答" + i);
        }
        when(agent.run(anyString(), anyList(), any(), nullable(String.class))).thenAnswer(call -> {
            List<ConversationTurn> history = call.getArgument(1);
            assertThat(history).hasSize(20);
            assertThat(history.getFirst().content()).isEqualTo("历史问题2");
            assertThat(history.getLast().content()).isEqualTo("历史回答11");
            return AgentResult.of("最新回答", List.of(), null, null);
        });
        service.sendMessage(id, "当前问题", user);
    }

    private long create() {
        long id = service.create(user.getUserId(), user.getEnterpriseId(), null).id();
        ids.add(id);
        return id;
    }
    private void assertEmpty(long id) {
        assertThat(messages.findConversationMessages(id)).isEmpty();
        var row = conversations.selectById(id);
        assertThat(row.getMessageCount()).isZero();
        assertThat(row.getTitle()).isEqualTo("新对话");
    }
    private void insert(long id, String role, String text) {
        var row = new AdvisorMessage(); row.setConversationId(id); row.setUserId(user.getUserId());
        row.setEnterpriseId(user.getEnterpriseId()); row.setRole(role); row.setContent(text); messages.insert(row);
    }
    private static void authenticate(LoginUser user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }
}
