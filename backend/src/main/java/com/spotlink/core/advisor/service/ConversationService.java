package com.spotlink.advisor.service;

import com.spotlink.advisor.agent.AdvisorAgent;
import com.spotlink.advisor.agent.AgentResult;
import com.spotlink.advisor.agent.ConversationTurn;
import com.spotlink.advisor.agent.AdvisorInputPolicy;
import com.spotlink.advisor.agent.ConversationContext;
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
import java.util.Objects;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final AdvisorRequestGuard guard;
    private final PlatformTransactionManager transactionManager;
    private final com.spotlink.advisor.langchain.LangChainGateway langChain;
    private final com.spotlink.advisor.config.AdvisorModelSettingsService modelSettings;

    // ------------------------------------------------------------------
    // 会话管理
    // ------------------------------------------------------------------

    public List<ConversationSummary> listMine(Long userId) {
        var current = com.spotlink.shared.security.SecurityUtils.currentUserOrNull();
        return conversationMapper.findOwned(userId, current == null ? null : current.getEnterpriseId(), current != null)
                .stream()
                .map(row -> new ConversationSummary(
                        row.getId(), row.getTitle(),
                        row.getMessageCount() == null ? 0 : row.getMessageCount(),
                        row.getLastMessageAt(), row.getCreatedAt(), engine(row)))
                .toList();
    }

    public ConversationDetail create(Long userId, Long enterpriseId, String title) {
        return create(userId, enterpriseId, title, null);
    }

    public ConversationDetail create(Long userId, Long enterpriseId, String title, String engine) {
        if (engine == null) engine = modelSettings.defaultEngine();
        if (!java.util.Set.of("spring-ai", "langchain").contains(engine)) throw BusinessException.of(ResultCode.BAD_REQUEST, "未知顾问引擎");
        Conversation conversation = new Conversation();
        conversation.setEngine(engine);
        conversation.setUserId(userId);
        conversation.setEnterpriseId(enterpriseId);
        conversation.setTitle(title == null || title.isBlank() ? "新对话" : title.trim());
        conversation.setMessageCount(0);
        // 创建时就写入时间戳而不是留空，这样排序就是一个普通的倒序排序，
        // 不必为 null 单独开一个分支。
        conversation.setLastMessageAt(OffsetDateTime.now());
        conversationMapper.insert(conversation);

        return new ConversationDetail(conversation.getId(), conversation.getTitle(), conversation.getContextNote(), List.of(), engine(conversation));
    }

    public ConversationDetail get(Long conversationId, Long userId) {
        Conversation conversation = requireOwned(conversationId, userId);
        List<MessageView> messages = messageMapper.findConversationMessages(conversationId)
                .stream()
                .map(entity -> MessageView.from(entity, objectMapper))
                .toList();

        return new ConversationDetail(conversation.getId(), conversation.getTitle(), conversation.getContextNote(), messages, engine(conversation));
    }

    public void updateContext(Long conversationId, Long userId, String note) {
        Conversation conversation = requireOwned(conversationId, userId);
        if (note != null && note.length() > 2000) throw BusinessException.of(ResultCode.BAD_REQUEST, "采购需求不能超过2000个字符");
        conversation.setContextNote(note == null || note.isBlank() ? "" : AdvisorInputPolicy.normalize(note));
        conversationMapper.updateById(conversation);
    }

    public void delete(Long conversationId, Long userId) {
        requireOwned(conversationId, userId);
        langChain.cancel(conversationId, userId);
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
     * <p>模型返回后，以短事务原子保存问答和计数，避免半个问答进入后续上下文。
     */
    public MessageView sendMessage(Long conversationId, String userMessage, LoginUser user) {
        String content = AdvisorInputPolicy.normalize(userMessage);
        Conversation conversation = requireOwned(conversationId, user.getUserId());
        if (!Objects.equals(conversation.getEnterpriseId(), user.getEnterpriseId())) throw BusinessException.of(ResultCode.CONVERSATION_NOT_FOUND);
        try (var lease = guard.acquire(user.getUserId())) {
            List<ConversationTurn> history = loadHistory(conversationId);
            AgentResult result = "langchain".equals(engine(conversation))
                    ? langChain.run(conversationId, content, history, user, conversation.getContextNote())
                    : advisorAgent.run(content, history, user, conversation.getContextNote());
            // 模型调用不占用数据库事务；问答与计数在同一短事务中提交。
            return new TransactionTemplate(transactionManager).execute(status -> {
                requireOwned(conversationId, user.getUserId());
                // 先锁定仍存在的会话行；删除或更新失败时，不能留下悬空消息。
                touchConversation(conversation, content);
                persistUserMessage(conversation, user, content);
                AdvisorMessage assistantRow = persistAssistantMessage(conversation, user, result);
                return MessageView.from(assistantRow, objectMapper);
            });
        }
    }

    /**
     * 以纯文本轮次的形式加载对话记录的末尾一段。
     *
     * <p>先按由新到旧并带上 limit 读取，然后反转，这样截断保留下来的是最近的消息，
     * 而不是最早的消息。
     */
    private List<ConversationTurn> loadHistory(Long conversationId) {
        List<AdvisorMessage> recent = messageMapper.findRecentMessages(conversationId, MAX_HISTORY_MESSAGES);
        Collections.reverse(recent);

        List<ConversationTurn> turns = new ArrayList<>(recent.size());
        for (AdvisorMessage row : recent) {
            String content = row.getContent();
            if (AdvisorMessage.Role.ASSISTANT.equals(row.getRole()) && row.getProductsJson() != null && !row.getProductsJson().equals("[]")) {
                // 保留用户可见卡片的编号，续问“第一条”可重新查详情；旧快照不能当新报价。
                content += "\n上一轮展示的挂牌快照（仅帮助定位，价格及余量需要重新查询）：\n" + row.getProductsJson();
            }
            turns.add(new ConversationTurn(row.getRole(), content, MessageView.from(row, objectMapper).products()));
        }
        return ConversationContext.bounded(turns);
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
        var current = com.spotlink.shared.security.SecurityUtils.currentUserOrNull();
        if (current != null && !Objects.equals(conversation.getEnterpriseId(), current.getEnterpriseId()))
            throw BusinessException.of(ResultCode.CONVERSATION_NOT_FOUND);
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
        try { message.setProductsJson(objectMapper.writeValueAsString(result.products())); }
        catch (Exception e) { throw new IllegalStateException("商品卡片编码失败"); }
        try { message.setKnowledgeJson(objectMapper.writeValueAsString(result.knowledge())); }
        catch (Exception e) { throw new IllegalStateException("知识引用编码失败"); }
        message.setToolCalls(serialiseToolCalls(result));
        message.setInputTokens(toLong(result.inputTokens()));
        message.setOutputTokens(toLong(result.outputTokens()));
        // 缓存统计尚未采集，保持未知；LangChain 可报告真实模型循环轮数。
        message.setCacheReadTokens(null);
        message.setCacheCreationTokens(null);
        message.setIterations(result.iterations());
        message.setRunId(result.runId());
        message.setModel(result.model());
        messageMapper.insert(message);
        return message;
    }

    private AdvisorMessage newMessage(Conversation conversation, LoginUser user) {
        AdvisorMessage message = new AdvisorMessage();
        message.setConversationId(conversation.getId());
        message.setEnterpriseId(user.getEnterpriseId());
        message.setUserId(user.getUserId());
        message.setEngine(engine(conversation));
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

        int changed = conversationMapper.recordCompletedTurn(conversation.getId(), OffsetDateTime.now(),
                isFirstTurn ? deriveTitle(firstQuestion) : null);
        if (changed != 1) throw BusinessException.of(ResultCode.CONVERSATION_NOT_FOUND);
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

    public void cancel(Long conversationId, Long userId) {
        requireOwned(conversationId, userId);
        langChain.cancel(conversationId, userId);
    }

    private static String engine(Conversation conversation) {
        return conversation.getEngine() == null ? "spring-ai" : conversation.getEngine();
    }
}
