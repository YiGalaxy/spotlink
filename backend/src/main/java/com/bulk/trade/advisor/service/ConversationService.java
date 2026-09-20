package com.bulk.trade.advisor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.advisor.agent.AgentLoop;
import com.bulk.trade.advisor.agent.AgentResult;
import com.bulk.trade.advisor.agent.ConversationTurn;
import com.bulk.trade.advisor.dto.ConversationDetail;
import com.bulk.trade.advisor.dto.ConversationSummary;
import com.bulk.trade.advisor.dto.MessageView;
import com.bulk.trade.advisor.entity.AdvisorMessage;
import com.bulk.trade.advisor.entity.Conversation;
import com.bulk.trade.advisor.mapper.AdvisorMessageMapper;
import com.bulk.trade.advisor.mapper.ConversationMapper;
import com.bulk.trade.advisor.tool.AdvisorContext;
import com.bulk.trade.shared.exception.BusinessException;
import com.bulk.trade.shared.web.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    /**
     * How many prior messages are replayed as context.
     *
     * <p>Bounded on purpose. The whole history is re-sent on every turn, so an
     * unbounded transcript grows the input bill quadratically while adding
     * little: what happened twenty turns ago rarely bears on the current
     * question.
     */
    private static final int MAX_HISTORY_MESSAGES = 20;

    /** Characters of the first question used as the conversation title. */
    private static final int TITLE_MAX_LENGTH = 24;

    private final ConversationMapper conversationMapper;
    private final AdvisorMessageMapper messageMapper;
    private final AgentLoop agentLoop;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------
    // Conversation management
    // ------------------------------------------------------------------

    public List<ConversationSummary> listMine(Long userId) {
        return conversationMapper.selectList(Wrappers.<Conversation>lambdaQuery()
                        .eq(Conversation::getUserId, userId)
                        .orderByDesc(Conversation::getLastMessageAt)
                        .orderByDesc(Conversation::getId))
                .stream()
                .map(row -> new ConversationSummary(
                        row.getId(), row.getTitle(),
                        row.getMessageCount() == null ? 0 : row.getMessageCount(),
                        row.getLastMessageAt(), row.getCreatedAt()))
                .toList();
    }

    public ConversationDetail create(Long userId, Long enterpriseId, String title) {
        OffsetDateTime now = OffsetDateTime.now();

        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setEnterpriseId(enterpriseId);
        conversation.setTitle(title == null || title.isBlank() ? "新对话" : title.trim());
        conversation.setMessageCount(0);
        // Stamped at creation rather than left null so ordering is a plain
        // descending sort with no null-handling special case.
        conversation.setLastMessageAt(now);
        conversationMapper.insert(conversation);

        return new ConversationDetail(conversation.getId(), conversation.getTitle(), List.of());
    }

    public ConversationDetail get(Long conversationId, Long userId) {
        Conversation conversation = requireOwned(conversationId, userId);
        List<MessageView> messages = messageMapper.selectList(
                        Wrappers.<AdvisorMessage>lambdaQuery()
                                .eq(AdvisorMessage::getConversationId, conversationId)
                                .orderByAsc(AdvisorMessage::getId))
                .stream()
                .map(entity -> MessageView.from(entity, objectMapper))
                .toList();

        return new ConversationDetail(conversation.getId(), conversation.getTitle(), messages);
    }

    public void delete(Long conversationId, Long userId) {
        requireOwned(conversationId, userId);
        // Soft delete on the conversation; its messages stay as history.
        conversationMapper.deleteById(conversationId);
        log.info("Conversation {} deleted by user {}", conversationId, userId);
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    /**
     * Runs one turn and persists it.
     *
     * <p>The model is called <em>before</em> anything is written. Persisting the
     * user's question first would leave a dangling question behind whenever the
     * upstream call fails, and a transcript full of unanswered questions is
     * worse than one missing the turn that failed.
     *
     * <p>The two inserts are not wrapped in a transaction. They are independent
     * writes with no invariant between them — losing one leaves a shorter
     * transcript, not corrupt data.
     */
    public MessageView sendMessage(Long conversationId, String userMessage, AdvisorContext context) {
        Conversation conversation = requireOwned(conversationId, context.userId());

        List<ConversationTurn> history = loadHistory(conversationId);
        AgentResult result = agentLoop.run(userMessage, history, context);

        persistUserMessage(conversation, context, userMessage);
        AdvisorMessage assistantRow = persistAssistantMessage(conversation, context, result);
        touchConversation(conversation, userMessage);

        return MessageView.from(assistantRow, objectMapper);
    }

    /**
     * Loads the tail of the transcript as plain-text turns.
     *
     * <p>Read newest-first with a limit, then reversed, so the cut keeps the
     * most recent messages rather than the oldest.
     */
    public List<ConversationTurn> loadHistory(Long conversationId) {
        List<AdvisorMessage> recent = messageMapper.selectList(
                Wrappers.<AdvisorMessage>lambdaQuery()
                        .eq(AdvisorMessage::getConversationId, conversationId)
                        .orderByDesc(AdvisorMessage::getId)
                        .last("limit " + MAX_HISTORY_MESSAGES));
        Collections.reverse(recent);

        List<ConversationTurn> turns = new ArrayList<>(recent.size());
        for (AdvisorMessage row : recent) {
            turns.add(new ConversationTurn(row.getRole(), row.getContent()));
        }
        return turns;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Loads a conversation only if the caller owns it.
     *
     * <p>A conversation belonging to someone else reports "not found" rather
     * than "forbidden": a distinct error would confirm that the id exists, which
     * is information the caller has no business having.
     */
    private Conversation requireOwned(Long conversationId, Long userId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw BusinessException.of(ResultCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    private void persistUserMessage(Conversation conversation, AdvisorContext context, String content) {
        AdvisorMessage message = newMessage(conversation, context);
        message.setRole(AdvisorMessage.Role.USER);
        message.setContent(content);
        messageMapper.insert(message);
    }

    private AdvisorMessage persistAssistantMessage(Conversation conversation,
                                                   AdvisorContext context,
                                                   AgentResult result) {
        AdvisorMessage message = newMessage(conversation, context);
        message.setRole(AdvisorMessage.Role.ASSISTANT);
        message.setContent(result.answer());
        message.setToolCalls(serialiseToolCalls(result));
        message.setIterations(result.iterations());
        message.setInputTokens(result.inputTokens());
        message.setOutputTokens(result.outputTokens());
        message.setCacheReadTokens(result.cacheReadTokens());
        message.setCacheCreationTokens(result.cacheCreationTokens());
        messageMapper.insert(message);
        return message;
    }

    private AdvisorMessage newMessage(Conversation conversation, AdvisorContext context) {
        AdvisorMessage message = new AdvisorMessage();
        message.setConversationId(conversation.getId());
        message.setEnterpriseId(context.enterpriseId());
        message.setUserId(context.userId());
        return message;
    }

    /**
     * Bumps counters and, on the very first question, derives a title.
     *
     * <p>{@code message_count} is incremented in SQL rather than read into Java
     * and written back, so two turns arriving together cannot lose an update.
     */
    private void touchConversation(Conversation conversation, String firstQuestion) {
        boolean isFirstTurn = conversation.getMessageCount() == null || conversation.getMessageCount() == 0;

        var update = Wrappers.<Conversation>lambdaUpdate()
                .eq(Conversation::getId, conversation.getId())
                .setSql("message_count = message_count + 2")
                .set(Conversation::getLastMessageAt, OffsetDateTime.now());

        if (isFirstTurn) {
            update.set(Conversation::getTitle, deriveTitle(firstQuestion));
        }
        conversationMapper.update(null, update);
    }

    private String deriveTitle(String question) {
        String cleaned = question.replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= TITLE_MAX_LENGTH) {
            return cleaned;
        }
        return cleaned.substring(0, TITLE_MAX_LENGTH) + "…";
    }

    private String serialiseToolCalls(AgentResult result) {
        if (result.toolInvocations().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(result.toolInvocations());
        } catch (Exception e) {
            // The answer still matters; a missing trail only costs detail.
            log.warn("Could not serialise tool-call trail", e);
            return null;
        }
    }
}
