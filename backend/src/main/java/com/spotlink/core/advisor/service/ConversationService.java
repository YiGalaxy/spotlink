package com.spotlink.advisor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.spotlink.advisor.agent.AdvisorAgent;
import com.spotlink.advisor.agent.AgentResult;
import com.spotlink.advisor.agent.ConversationTurn;
import com.spotlink.advisor.dto.ConversationDetail;
import com.spotlink.advisor.dto.ConversationSummary;
import com.spotlink.advisor.dto.MessageView;
import com.spotlink.advisor.entity.AdvisorMessage;
import com.spotlink.advisor.entity.Conversation;
import com.spotlink.advisor.mapper.AdvisorMessageMapper;
import com.spotlink.advisor.mapper.ConversationMapper;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.security.LoginUser;
import com.spotlink.shared.web.ResultCode;
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
     * 作为上下文回放的历史消息条数。
     *
     * <p>刻意设了上限。每轮都会把全部历史重发一遍，所以不加限制的对话记录会让输入开销按平方
     * 增长，而收益很小：二十轮之前发生的事很少与当前这个问题有关。
     */
    private static final int MAX_HISTORY_MESSAGES = 20;

    /** 用作会话标题的首个问题的截取字符数。 */
    private static final int TITLE_MAX_LENGTH = 24;

    private final ConversationMapper conversationMapper;
    private final AdvisorMessageMapper messageMapper;
    private final AdvisorAgent advisorAgent;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------
    // 会话管理
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
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setEnterpriseId(enterpriseId);
        conversation.setTitle(title == null || title.isBlank() ? "新对话" : title.trim());
        conversation.setMessageCount(0);
        // 创建时就写入时间戳而不是留空，这样排序就是一个普通的倒序排序，
        // 不必为 null 单独开一个分支。
        conversation.setLastMessageAt(OffsetDateTime.now());
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
        // 对会话做软删除；它的消息作为历史保留下来。
        conversationMapper.deleteById(conversationId);
        log.info("Conversation {} deleted by user {}", conversationId, userId);
    }

    // ------------------------------------------------------------------
    // 对话
    // ------------------------------------------------------------------

    /**
     * 执行一轮对话并持久化。
     *
     * <p>模型是在写入任何东西<em>之前</em>调用的。如果先把用户的问题写进去，上游调用失败时
     * 就会留下一个悬空的问题，而一份满是无人应答问题的对话记录，比缺少失败那一轮的记录更糟。
     *
     * <p>两次插入没有包在同一个事务里。它们是相互独立的写入，彼此之间没有不变式 ——
     * 丢掉一条只会让对话记录变短，不会产生脏数据。
     */
    public MessageView sendMessage(Long conversationId, String userMessage, LoginUser user) {
        Conversation conversation = requireOwned(conversationId, user.getUserId());

        List<ConversationTurn> history = loadHistory(conversationId);
        AgentResult result = advisorAgent.run(userMessage, history, user);

        persistUserMessage(conversation, user, userMessage);
        AdvisorMessage assistantRow = persistAssistantMessage(conversation, user, result);
        touchConversation(conversation, userMessage);

        return MessageView.from(assistantRow, objectMapper);
    }

    /**
     * 以纯文本轮次的形式加载对话记录的末尾一段。
     *
     * <p>先按由新到旧并带上 limit 读取，然后反转，这样截断保留下来的是最近的消息，
     * 而不是最早的消息。
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
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 只在调用方拥有该会话时才加载它。
     *
     * <p>属于别人的会话报「不存在」而不是「无权限」：一个单独的错误码等于确认这个 id 确实
     * 存在，而这是调用方没有资格获得的信息。
     */
    private Conversation requireOwned(Long conversationId, Long userId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw BusinessException.of(ResultCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    private void persistUserMessage(Conversation conversation, LoginUser user, String content) {
        AdvisorMessage message = newMessage(conversation, user);
        message.setRole(AdvisorMessage.Role.USER);
        message.setContent(content);
        messageMapper.insert(message);
    }

    private AdvisorMessage persistAssistantMessage(Conversation conversation,
                                                   LoginUser user,
                                                   AgentResult result) {
        AdvisorMessage message = newMessage(conversation, user);
        message.setRole(AdvisorMessage.Role.ASSISTANT);
        message.setContent(result.answer());
        message.setToolCalls(serialiseToolCalls(result));
        message.setInputTokens(toLong(result.inputTokens()));
        message.setOutputTokens(toLong(result.outputTokens()));
        // Spring AI 既不报告缓存 token 数，也不报告循环轮数，所以这几项保持为 null，
        // 而不是填一个会误导人的 0。
        message.setCacheReadTokens(null);
        message.setCacheCreationTokens(null);
        message.setIterations(null);
        messageMapper.insert(message);
        return message;
    }

    private AdvisorMessage newMessage(Conversation conversation, LoginUser user) {
        AdvisorMessage message = new AdvisorMessage();
        message.setConversationId(conversation.getId());
        message.setEnterpriseId(user.getEnterpriseId());
        message.setUserId(user.getUserId());
        return message;
    }

    /**
     * 累加计数，并在第一个问题时推导出标题。
     *
     * <p>{@code message_count} 是在 SQL 里自增的，而不是读进 Java 再写回去，
     * 所以两轮对话同时到达也不会丢掉一次更新。
     */
    private void touchConversation(Conversation conversation, String firstQuestion) {
        boolean isFirstTurn = conversation.getMessageCount() == null
                || conversation.getMessageCount() == 0;

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
            // 答案本身仍然重要；缺了轨迹只是损失一点细节。
            log.warn("Could not serialise tool-call trail", e);
            return null;
        }
    }

    private Long toLong(Integer value) {
        return value == null ? null : value.longValue();
    }
}
